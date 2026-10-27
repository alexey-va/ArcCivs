#!/usr/bin/env python3
"""Export one exact private catalog entry for the existing native block renderer.

Technical skip cells become render air; a functional control marker becomes its
runtime barrel. The source schematic stays immutable and both hashes are saved.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import sys


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); sys.modules[name] = value; spec.loader.exec_module(value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "atelier", "out"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--id", required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise ValueError("Use a fresh private preview directory")
    curate = module("preview_curator", Path(__file__).with_name("curate-catalog.py"))
    assemble = module("preview_assembler", Path(__file__).with_name("assemble-catalog.py"))
    sys.path.insert(0, str(args.atelier))
    for filename, expected in (("normalize-blueprint-library.py", curate.NORMALIZER_SHA), ("convert-blueprint-library.py", curate.CONVERTER_SHA), ("sponge.py", curate.SPONGE_SHA)):
        curate.pinned(args.atelier / filename, expected)
    normalizer = module("preview_normalizer", args.atelier / "normalize-blueprint-library.py")
    catalog_bytes = (args.root / "civs/v2/catalog.json").read_bytes(); catalog = json.loads(catalog_bytes)
    row = next(r for r in catalog["entries"] + catalog.get("retainedEntries", []) if r.get("schematicId") == args.id)
    if row["verdict"] != "READY":
        raise ValueError("Only verified READY output has a native preview")
    source, receipt = assemble.verified_payload(args.root, row)
    size, palette, cells = normalizer.read_converted(source)
    visual = ["minecraft:barrel[facing=south,open=false]" if curate.name(state) == "minecraft:structure_void" and row["type"] != "DECORATION"
              else "minecraft:air" if curate.name(state) in curate.SKIP else state for state in palette]
    # Deduplicate after consuming technical markers; the renderer verifies every cell.
    states = list(dict.fromkeys(visual)); remap = [states.index(s) for s in visual]; mapped = [remap[c] for c in cells]
    blob = normalizer.lib.encode(size, row["origin"], states, mapped, "Offline catalog preview")
    points = [[i % size[0], i // (size[0] * size[2]), i // size[0] % size[2], c]
              for i, c in enumerate(mapped) if states[c] not in curate.AIR]
    args.out.mkdir(parents=True)
    (args.out / "scene.schem").write_bytes(blob)
    (args.out / "scene.blocks.json").write_bytes(curate.canonical({"version": 1, "size": size,
        "materials": [{"block": state} for state in states], "blocks": points}))
    (args.out / "scene.atelier.json").write_bytes(curate.canonical({"route": [[row["origin"][0], row["origin"][1] + 1, size[2]]]}))
    report = {"schematicSha256": curate.digest(blob), "sourceSchematicSha256": row["sha256"],
              "catalogSha256": curate.digest(catalog_bytes), "schematicId": args.id, "sourceId": row["sourceId"],
              "sourceSha256": row["sourceSha256"], "receiptSha256": curate.digest(receipt),
              "scriptSha256": curate.digest(Path(__file__).read_bytes()), "nativeRuntimeAcceptance": False,
              "derivation": "Exact payload cells; technical skip markers are AIR and functional control is BARREL. Native offline block render, no live client."}
    (args.out / "report.json").write_bytes(curate.canonical(report))
    print(json.dumps(report))


if __name__ == "__main__":
    main()
