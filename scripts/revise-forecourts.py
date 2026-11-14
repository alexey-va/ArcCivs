#!/usr/bin/env python3
"""Revise only generated gravel in accepted v2 schematics; preserve old identities."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
from importlib.util import module_from_spec, spec_from_file_location
import json
from pathlib import Path
import shutil
import sys
import tempfile

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = spec_from_file_location(name, path)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


curate = load("forecourt_curate", HERE / "curate-catalog.py")
assemble = load("forecourt_assemble", HERE / "assemble-catalog.py")
POLICY = "forecourt-natural-v1"


def revised_id(identifier, revision):
    return identifier.rsplit("/", 2)[0] + "/" + revision + "/" + identifier.rsplit("/", 1)[1]


def revise_payload(row, receipt, payload, normalizer):
    size, palette, cells = normalizer.read_converted(payload)
    if size != row["size"]:
        raise ValueError("Forecourt frame differs: " + row["schematicId"])
    w, h, length = size
    front_x, ground, outside_front = row["origin"]
    if outside_front != length:
        raise ValueError("Expected the curated south front")
    last_change = {tuple(r["position"]): r for r in receipt["changes"]}
    changes = Counter()
    for (x, y, z), change in last_change.items():
        if change["reason"] != "shared-ground-forecourt" or change["after"] != "minecraft:gravel":
            continue
        if not (0 <= x < w and 0 <= y < h and 0 <= z < length and y == ground and
                (x in (0, w - 1) or z == 0 or z > length - 3)):
            raise ValueError("Generated cell crosses the retained source frame")
        at = (y * length + z) * w + x
        if palette[cells[at]] != "minecraft:gravel":
            raise ValueError("Generated before image differs: " + row["schematicId"])
        # This version reproduces already published immutable v1 payloads byte for byte.
        after = "minecraft:dirt_path" if x == front_x and z > length - 3 else "minecraft:dirt"
        if after not in palette:
            palette.append(after)
        cells[at] = palette.index(after)
        changes[after] += 1
    if not changes:
        return payload, changes
    data = normalizer.lib.encode(size, row["origin"], palette, cells, "ArcCivs v2 " + row["sourceId"])
    normalizer.lib.verify_output(data, size, row["origin"], palette, cells)
    return data, changes


def revise(catalog, base, additional, atelier, out):
    raw = catalog.read_bytes()
    active = json.loads(raw)
    proof = json.loads((base / "civs/v2/catalog.json").read_text())
    if active["schemaVersion"] != 2 or not active["completeInventory"] or active["recipeSha256"] != proof["recipeSha256"]:
        raise ValueError("Expected one complete verified source recipe")
    if out.exists():
        raise ValueError("Output already exists")
    for name, sha in (("normalize-blueprint-library.py", curate.NORMALIZER_SHA),
                      ("convert-blueprint-library.py", curate.CONVERTER_SHA), ("sponge.py", curate.SPONGE_SHA)):
        curate.pinned(atelier / name, sha)
    sys.path.insert(0, str(atelier))
    normalizer = load("forecourt_normalizer", atelier / "normalize-blueprint-library.py")
    originals = {r["schematicId"]: r for r in proof["entries"] if r["verdict"] == "READY"}
    current = [r for r in active["entries"] if r["verdict"] == "READY"]
    old_rows = {r["schematicId"]: r for r in current + active.get("retainedEntries", [])}
    groups = defaultdict(list)
    for row in current:
        if row["type"] != "DECORATION" and originals.get(row["schematicId"]) == row:
            groups[(row["style"], row["family"], row["revision"])].append(row)
    out.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=out.name + ".staging-", dir=out.parent))
    try:
        for identifier, row in old_rows.items():
            path = assemble.payload_path(base, identifier)
            if not path.is_file() or assemble.digest(path.read_bytes()) != row["sha256"]:
                path = assemble.payload_path(additional, identifier)
            blob = path.read_bytes()
            if assemble.digest(blob) != row["sha256"]:
                raise ValueError("Accepted payload hash differs: " + identifier)
            target = assemble.payload_path(staging, identifier)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(blob)
        replacement, counts, retained = {}, Counter(), list(active.get("retainedEntries", []))
        for (_, _, old_revision), group in sorted(groups.items()):
            assemble.validate_families(group)
            prepared = []
            for row in group:
                blob, receipt = assemble.verified_payload(base, row)
                data, changes = revise_payload(row, json.loads(receipt), blob, normalizer)
                prepared.append((row, data, changes))
            if not any(c for _, _, c in prepared):
                continue
            revision = assemble.digest((POLICY + ":" + old_revision).encode())[:20]
            ids = {str(r["level"]): revised_id(r["schematicId"], revision) for r in group}
            for row, data, changes in prepared:
                new = copy.deepcopy(row)
                identifier = ids[str(row["level"])]
                if identifier in old_rows or assemble.payload_path(staging, identifier).exists():
                    raise ValueError("Revised identity already exists: " + identifier)
                digest = assemble.digest(data)
                new.update(revision=revision, schematicId=identifier, sha256=digest)
                new["worksite"].update(schematicId=identifier, schematicSha256=digest, upgradeSchematics=ids)
                replacement[row["schematicId"]] = new
                retained.append(row)
                counts.update(changes)
                target = assemble.payload_path(staging, identifier)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        if not replacement:
            raise ValueError("No proven generated gravel was revised")
        active["entries"] = [replacement.get(r.get("schematicId"), r) for r in active["entries"]]
        active["retainedEntries"] = sorted(retained, key=lambda r: r["schematicId"])
        active["forecourtMigration"] = dict(policy=POLICY, fromCatalogSha256=assemble.digest(raw),
            revisedLevels=len(replacement), changes=dict(counts), nativeRuntimeAcceptance=False)
        rows = [r for r in active["entries"] if r["verdict"] == "READY"] + active["retainedEntries"]
        if len({r["schematicId"] for r in rows}) != len(rows):
            raise ValueError("Duplicate active or retained identity")
        assemble.validate_families(rows)
        (staging / "civs/v2/catalog.json").write_bytes(assemble.canonical(active))
        staging.rename(out)
        return active["forecourtMigration"]
    except BaseException:
        shutil.rmtree(staging)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("catalog", "base", "additional", "atelier", "out"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(revise(args.catalog, args.base, args.additional, args.atelier, args.out), sort_keys=True))
