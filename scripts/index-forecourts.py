#!/usr/bin/env python3
"""Attach bounded exterior rows to an existing private v2 index without modifying an accepted schematic."""
import argparse
import json
from pathlib import Path
import sys

import importlib.util


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


here = Path(__file__).resolve().parent
v1 = load("forecourt_v1", here / "revise-forecourts.py")
curate, assemble = v1.curate, v1.assemble


def indexed(catalog, roots, base, atelier, requested=None):
    for name, sha in (("normalize-blueprint-library.py", curate.NORMALIZER_SHA),
                      ("convert-blueprint-library.py", curate.CONVERTER_SHA), ("sponge.py", curate.SPONGE_SHA)):
        curate.pinned(atelier / name, sha)
    sys.path.insert(0, str(atelier))
    decoder = load("forecourt_index_normalizer", atelier / "normalize-blueprint-library.py")
    raw = catalog.read_bytes()
    document = json.loads(raw)
    if document["schemaVersion"] != 2 or not document["completeInventory"] or not document["privateAssetsOnly"]:
        raise ValueError("Expected the complete private v2 source index")
    originals = {}
    if base:
        for row in json.loads((base / "civs/v2/catalog.json").read_text())["entries"]:
            if row["verdict"] == "READY":
                revision = assemble.digest((v1.POLICY + ":" + row["revision"]).encode())[:20]
                originals[v1.revised_id(row["schematicId"], revision)] = row
    report = dict(sourceIndexSha256=assemble.digest(raw), verified=0, projected=0, openRows=0, unavailable=[])
    for row in document["entries"] + document.get("retainedEntries", []):
        if row["verdict"] != "READY" or row["type"] == "DECORATION":
            continue
        identifier = row["schematicId"]
        if requested is not None and identifier not in requested:
            continue
        if requested is not None and requested[identifier] != row["sha256"]:
            raise ValueError("Requested hash differs from current index: " + identifier)
        if (row["worksite"]["schematicId"], row["worksite"]["schematicSha256"]) != (identifier, row["sha256"]):
            raise ValueError("Worksite identity differs: " + identifier)
        payload = None
        for root in roots:
            path = assemble.payload_path(root, identifier)
            if path.is_file():
                payload = path.read_bytes()
                break
        if payload is None and identifier in originals:
            old = originals[identifier]
            source, receipt = assemble.verified_payload(base, old)
            payload, _ = v1.revise_payload(old, json.loads(receipt), source, decoder)
        if payload is None:
            report["unavailable"].append(identifier)
            continue
        if assemble.digest(payload) != row["sha256"]:
            raise ValueError("Current schematic digest differs: " + identifier)
        size, palette, cells = decoder.read_converted(payload)
        if size != row["size"]:
            raise ValueError("Current schematic frame differs: " + identifier)
        scene = curate.Scene(size, palette, cells, row["origin"], {"blocks": {}})
        rows = curate.forecourt_rows(scene, row["control"])
        previous = row["worksite"].get("forecourt")
        if previous is not None and rows != previous:
            raise ValueError("Previously attested forecourt differs: " + identifier)
        report["verified"] += 1
        if rows:
            row["worksite"]["forecourt"] = rows
            report["projected"] += 1
            report["openRows"] += len(rows)
    if requested is not None:
        entries = {r["schematicId"]: dict(sha256=r["sha256"], forecourt=r["worksite"].get("forecourt", []))
                   for r in document["entries"] + document.get("retainedEntries", [])
                   if r.get("schematicId") in requested and r["schematicId"] not in report["unavailable"]}
        if set(entries) != set(requested):
            raise ValueError("Missing requested source geometry")
        return assemble.canonical(entries), report
    return assemble.canonical(document), report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--payload-root", type=Path, action="append", required=True)
    parser.add_argument("--v1-base", type=Path)
    parser.add_argument("--atelier", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--requested", type=Path, help="Verify these exact IDs and hashes and emit compact rows only")
    args = parser.parse_args()
    data, report = indexed(args.catalog, args.payload_root, args.v1_base, args.atelier,
                           json.loads(args.requested.read_text()) if args.requested else None)
    with args.out.open("xb") as output:
        output.write(data)
    print(json.dumps(report, sort_keys=True))
