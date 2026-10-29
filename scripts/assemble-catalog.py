#!/usr/bin/env python3
"""Publish a complete private v2 tree from manifest-authoritative curation batches.

No schematic or source catalog belongs in Git. This assembler copies only READY
payloads and explicitly retained revisions, verifies hashes and source coverage,
and renames a fresh staging directory only after all checks pass.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def payload_path(root, identifier):
    if not re.fullmatch(r"civs/v2/[a-z0-9_-]+/[a-z0-9_-]+/[a-f0-9]{20}/level[1-3]", identifier):
        raise ValueError("Unsafe curated identity: " + identifier)
    return root / (identifier + ".schem")


def verified_payload(root, row):
    data = payload_path(root, row["schematicId"]).read_bytes()
    if digest(data) != row["sha256"]:
        raise ValueError("Curated payload hash differs: " + row["schematicId"])
    receipt_path = root / "receipts" / (row["schematicId"].removeprefix("civs/v2/") + ".json")
    receipt = receipt_path.read_bytes()
    if json.loads(receipt)["entry"] != row:
        raise ValueError("Curated receipt differs: " + row["schematicId"])
    return data, receipt


def validate_families(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(row["style"], row["family"], row["revision"])].append(row)
    for key, group in groups.items():
        first = group[0]
        if sorted(r["level"] for r in group) != list(range(1, first["maxSupportedLevel"] + 1)):
            raise ValueError("Incomplete accepted family: " + str(key))
        for row in group:
            if any(row[field] != first[field] for field in ("type", "size", "origin", "control", "maxSupportedLevel", "referenceLevel")):
                raise ValueError("Accepted family frame changed: " + str(key))
        if first["type"] == "QUARRY":
            upgrades = {str(row["level"]): row["schematicId"] for row in group}
            if any(row["worksite"].get("upgradeSchematics") != upgrades for row in group):
                raise ValueError("Mine upgrade family proof differs: " + str(key))


def dining_overlay(documents, current, path):
    if path is None:
        return None, {}
    raw = path.read_bytes(); overlay = json.loads(raw)
    if len(documents) != 1 or overlay.get("schemaVersion") != 1 or overlay.get("kind") != "tavern-dining" or \
            overlay.get("sourceCatalogSha256") != digest((documents[0][0] / "civs/v2/catalog.json").read_bytes()):
        raise ValueError("Dining overlay must match one exact assembled base")
    if overlay.get("nativeRuntimeAcceptance") is not False or any(
            not re.fullmatch(r"[a-f0-9]{64}", overlay.get(key, "")) for key in
            ("scriptSha256", "curationScriptSha256", "nativeFactsSha256", "registrySha256", "normalizerSha256", "rotationsSha256")):
        raise ValueError("Dining overlay provenance is incomplete")
    recipe = documents[0][1]["recipeInputs"]
    if overlay["curationScriptSha256"] != recipe["scriptSha256"] or any(
            overlay[key] != recipe[key] for key in ("nativeFactsSha256", "registrySha256", "normalizerSha256", "rotationsSha256")):
        raise ValueError("Dining overlay geometry inputs differ")
    proofs = {}
    for proof in overlay["entries"]:
        identifier = proof["schematicId"]
        row = current.get(identifier)
        if identifier in proofs or row is None or row["type"] != "TAVERN" or \
                any(proof[key] != row[target] for key, target in (("schematicSha256", "sha256"), ("sourceId", "sourceId"), ("sourceSha256", "sourceSha256"))):
            raise ValueError("Dining proof identity differs")
        seats = [p["seat"] for p in proof["seats"]]
        if len(seats) > 6 or len({tuple(p) for p in seats}) != len(seats) or any(
                len(p) != 3 or any(type(v) is not int or not 0 <= v < row["size"][a] for a, v in enumerate(p)) for p in seats):
            raise ValueError("Dining anchors exceed their exact frame or bound")
        site = {**row["worksite"]}
        tavern = dict(site.get("tavern", {}))
        site["tavern"] = tavern
        tavern.pop("dining", None)
        if seats:
            tavern["dining"] = [[p[a] - row["origin"][a] for a in range(3)] for p in seats]
        if not tavern:
            site.pop("tavern", None)
        current[identifier] = {**row, "dining": seats, "worksite": site}
        proofs[identifier] = proof["seats"]
    if set(proofs) != {key for key, row in current.items() if row["type"] == "TAVERN"}:
        raise ValueError("Dining overlay must account for every READY tavern")
    return {**{k: v for k, v in overlay.items() if k != "entries"}, "overlaySha256": digest(raw)}, proofs


def assemble(batches, inventory_path, output, previous=None, *, expected_sources=14_830, dining=None):
    if output.exists():
        raise ValueError("Final output must be a fresh path; accepted trees are immutable")
    inventory_bytes = inventory_path.read_bytes()
    inventory = [json.loads(line) for line in inventory_bytes.splitlines()]
    inventory_by_id = {row["id"]: row for row in inventory}
    if len(inventory) != expected_sources or len(inventory_by_id) != expected_sources:
        raise ValueError("Expected the exact complete 14,830-source inventory")
    documents = [(root, json.loads((root / "civs/v2/catalog.json").read_text())) for root in batches]
    if not documents:
        raise ValueError("No curation batches")
    reference = documents[0][1]
    fields = ("schemaVersion", "recipeVersion", "recipeSha256", "recipeInputs", "placementPlanSha256",
              "registrySha256", "nativeRotationsSha256", "sourceInventorySha256", "sourceRepositories")
    entries, current, owners, seen_sources, orphan_counts = [], {}, {}, set(), {}
    for root, document in documents:
        if any(document.get(key) != reference.get(key) for key in fields):
            raise ValueError("Mixed curation recipes are not a publishable catalog")
        if document.get("schemaVersion") != 2 or document.get("sourceInventorySha256") != digest(inventory_bytes):
            raise ValueError("Batch inventory identity differs")
        ready_ids = set()
        for row in document["entries"]:
            source_id = row["sourceId"]
            if source_id in seen_sources or source_id not in inventory_by_id:
                raise ValueError("Duplicate or unknown source: " + source_id)
            if row["sourceSha256"] != inventory_by_id[source_id]["sha256"]:
                raise ValueError("Source hash differs: " + source_id)
            if row["verdict"] not in {"READY", "REJECTED", "METADATA_ONLY"}:
                raise ValueError("Unknown curation verdict")
            seen_sources.add(source_id); entries.append(row)
            if row["verdict"] != "READY":
                if not row.get("reasons"):
                    raise ValueError("Rejected source has no reason")
                continue
            identifier = row["schematicId"]
            if identifier in current:
                raise ValueError("Duplicate output identity: " + identifier)
            verified_payload(root, row)
            current[identifier] = row; owners[identifier] = root; ready_ids.add(identifier)
        disk_ids = {str(path.relative_to(root)).removesuffix(".schem") for path in (root / "civs/v2").rglob("*.schem")}
        orphan_counts[str(root)] = len(disk_ids - ready_ids)
    if seen_sources != set(inventory_by_id):
        raise ValueError("Incomplete source inventory")
    payload_entries = dict(current)
    enrichment, dining_proofs = dining_overlay(documents, current, dining)
    entries = [current[row["schematicId"]] if row["verdict"] == "READY" else row for row in entries]
    retained = {}
    if previous:
        old = json.loads((previous / "civs/v2/catalog.json").read_text())
        if old.get("schemaVersion") != 2:
            raise ValueError("Previous catalog schema differs")
        for row in old["entries"] + old.get("retainedEntries", []):
            if row["verdict"] != "READY":
                continue
            identifier = row["schematicId"]
            if identifier in current:
                if current[identifier] != row:
                    raise ValueError("An accepted identity was rewritten")
            elif identifier in retained and retained[identifier] != row:
                raise ValueError("Conflicting retained identity")
            else:
                verified_payload(previous, row)
                retained[identifier] = row; owners[identifier] = previous
    if len(retained) > 45_000:
        raise ValueError("Retained catalog metadata exceeds its explicit bound")
    validate_families(list(current.values()) + list(retained.values()))
    document = {key: reference[key] for key in fields}
    document.update(totalSources=len(entries), completeInventory=True, privateAssetsOnly=True,
                    nativeRuntimeAcceptance=False, entries=sorted(entries, key=lambda row: row["sourceId"]),
                    retainedEntries=sorted(retained.values(), key=lambda row: row["schematicId"]),
                    counts=dict(Counter(row["verdict"] for row in entries)), styles=sorted({row["style"] for row in entries}))
    if enrichment:
        document["metadataEnrichment"] = enrichment
    matrix = {}
    for style in document["styles"]:
        rows = [row for row in entries if row["style"] == style]
        matrix[style] = {kind: {"readyLevels": sum(row["verdict"] == "READY" for row in rows if row["type"] == kind),
                              "readyFamilies": sum(row["verdict"] == "READY" and row["level"] == 1 for row in rows if row["type"] == kind),
                              "rejected": sum(row["verdict"] == "REJECTED" for row in rows if row["type"] == kind),
                              "metadataOnly": sum(row["verdict"] == "METADATA_ONLY" for row in rows if row["type"] == kind)}
                         for kind in sorted({row["type"] for row in entries})}
    coverage = {"sourceCount": len(entries), "styleCount": len(matrix), "counts": document["counts"],
                "rejections": dict(Counter(reason for row in entries for reason in row["reasons"] if row["verdict"] == "REJECTED")),
                "matrix": matrix, "ignoredOrphanPayloads": orphan_counts, "nativeRuntimeAcceptance": False}
    if enrichment:
        coverage["dining"] = {"tavernLevels": len(dining_proofs), "withSeats": sum(bool(p) for p in dining_proofs.values()),
                              "seats": sum(len(p) for p in dining_proofs.values())}
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=output.name + ".staging-", dir=output.parent))
    try:
        for identifier, row in (current | retained).items():
            payload, receipt = verified_payload(owners[identifier], payload_entries.get(identifier, row))
            if identifier in dining_proofs:
                proof = json.loads(receipt)
                proof.update(entry=row, diningSeats=dining_proofs[identifier], metadataEnrichment=enrichment)
                receipt = canonical(proof)
            target = payload_path(staging, identifier); target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(payload)
            target = staging / "receipts" / (identifier.removeprefix("civs/v2/") + ".json")
            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(receipt)
        (staging / "coverage.json").write_bytes(canonical(coverage))
        (staging / "civs/v2/catalog.json").write_bytes(canonical(document))
        if dining:
            (staging / "dining-overlay.json").write_bytes(dining.read_bytes())
        os.rename(staging, output)
    except BaseException:
        shutil.rmtree(staging)
        raise
    return coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", type=Path, action="append", required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--previous-root", type=Path)
    parser.add_argument("--dining-overlay", type=Path, help="Exact metadata proof for a single assembled base")
    args = parser.parse_args()
    report = assemble(args.batch, args.inventory, args.out, args.previous_root, dining=args.dining_overlay)
    print(json.dumps({"out": str(args.out), "sources": report["sourceCount"], "styles": report["styleCount"], **report["counts"]}))


if __name__ == "__main__":
    main()
