#!/usr/bin/env python3
"""Apply reviewed, exact-source tavern furniture cells to a fresh private derivative.

The manifest and schematic assets stay private. This adapter never guesses which
slab is a chair, rewrites an accepted asset, or contacts a server.
"""
from __future__ import annotations
import argparse
from array import array
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys


def state_key(state):
    name, _, props = state.partition("[")
    return name + ("[" + ",".join(sorted(props.rstrip("]").split(","))) + "]" if props else "")


def load_recipe(path):
    document = json.loads(path.read_bytes())
    if document.get("schema") != 1 or document.get("coordinate_space") != "raw-schematic":
        raise ValueError("Unsupported furniture recipe")
    rows = document.get("variants")
    if not isinstance(rows, list) or len(rows) > 512:
        raise ValueError("Furniture variants must be a bounded list")
    indexed = {}
    for row in rows:
        key = row["source_id"], row["source_sha256"]
        if key in indexed or not re.fullmatch(r"[a-zA-Z0-9_ /-]{1,240}", key[0]) or ".." in key[0].split("/") or \
                not re.fullmatch(r"[0-9a-f]{64}", key[1]) or row.get("status") not in {"changed", "already_usable", "blocked"}:
            raise ValueError("Invalid or duplicate furniture source identity")
        cells = row.get("cells", [])
        if not isinstance(cells, list) or len(cells) > 512 or bool(cells) != (row["status"] == "changed"):
            raise ValueError("Furniture changes do not match their verdict")
        points = [tuple(cell["point"]) for cell in cells]
        if len(set(points)) != len(points) or any(len(p) != 3 or any(type(v) is not int for v in p) for p in points):
            raise ValueError("Invalid or duplicate furniture cell")
        indexed[key] = row
    return indexed


def apply(size, palette, cells, source_id, source_sha256, recipe, native):
    row = recipe.get((source_id, source_sha256))
    if row is None:
        if any(identifier == source_id for identifier, _ in recipe):
            raise ValueError("Furniture source hash differs")
        return list(palette), array("H", cells), []
    if row["status"] == "blocked":
        return list(palette), array("H", cells), []
    states, result, proof = list(palette), array("H", cells), []
    for cell in row.get("cells", []):
        point = cell["point"]
        if any(not 0 <= point[a] < size[a] for a in range(3)):
            raise ValueError("Furniture cell is outside its exact frame")
        i = (point[1] * size[2] + point[2]) * size[0] + point[0]
        before, after = states[result[i]], cell["after"]
        if state_key(before) != state_key(cell["before"]):
            raise ValueError(f"Furniture before-state differs at {point}")
        if state_key(after) not in native or not after.startswith("minecraft:"):
            raise ValueError("Furniture target lacks exact native geometry")
        # Furniture authoring never edits a real container or a technical marker.
        for value in (before, after):
            name = value.partition("[")[0]
            if name in {"minecraft:barrel", "minecraft:chest", "minecraft:trapped_chest", "minecraft:structure_void", "minecraft:barrier"}:
                raise ValueError("Furniture cannot change storage or technical markers")
        if after not in states:
            states.append(after)
        result[i] = states.index(after)
        proof.append({"position": list(point), "before": before, "after": after, "reason": "reviewed-tavern-furniture"})
    return states, result, proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "recipe", "atelier", "native-facts", "out"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--source-id", required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise ValueError("Furniture output must be a fresh private directory")
    sys.path.insert(0, str(args.atelier))
    spec = importlib.util.spec_from_file_location("furniture_normalizer", args.atelier / "normalize-blueprint-library.py")
    normalizer = importlib.util.module_from_spec(spec); spec.loader.exec_module(normalizer)
    blob = args.source.read_bytes(); source_sha = hashlib.sha256(blob).hexdigest()
    recipe = load_recipe(args.recipe)
    row = recipe.get((args.source_id, source_sha))
    if row is None or row["status"] == "blocked":
        raise ValueError("Standalone export requires an exact reviewed source")
    def module(name, path):
        spec = importlib.util.spec_from_file_location(name, path)
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
        return value
    curate = module("furniture_curator", Path(__file__).with_name("curate-catalog.py"))
    dining = module("furniture_dining", Path(__file__).with_name("enrich-tavern-dining.py"))
    for filename, expected in (("normalize-blueprint-library.py", curate.NORMALIZER_SHA),
                               ("convert-blueprint-library.py", curate.CONVERTER_SHA), ("sponge.py", curate.SPONGE_SHA)):
        curate.pinned(args.atelier / filename, expected)
    native_blob = curate.pinned(args.native_facts, curate.NATIVE_FACTS_SHA)
    native = {state_key(state): facts for state, facts in json.loads(native_blob)["states"].items()}
    size, palette, cells = normalizer.read_converted(blob)
    states, result, proof = apply(size, palette, cells, args.source_id, source_sha, recipe, native)
    origin = row.get("origin")
    if not isinstance(origin, list) or len(origin) != 3 or any(type(v) is not int for v in origin):
        raise ValueError("Standalone export requires the exact authored origin")
    scene = curate.Scene(size, states, result, origin, {"blocks": {}, "_native": native})
    requested = {tuple(seat["point"]) for table in row.get("dining", []) for seat in table["seats"]}
    proofs = dining.dining_proofs(scene, requested_seats=requested)
    if not requested or not requested <= {tuple(seat["seat"]) for seat in proofs}:
        raise ValueError("Reviewed dining anchors lack a native seat and entrance proof")
    derivative = normalizer.lib.encode(size, origin, states, result, "Reviewed tavern furniture")
    normalizer.lib.verify_output(derivative, size, origin, states, result)
    visual = ["minecraft:barrel[facing=south,open=false]" if state.partition("[")[0] == "minecraft:structure_void"
        else "minecraft:air" if state.partition("[")[0] == "minecraft:barrier" else state for state in states]
    visual_states = list(dict.fromkeys(visual)); remap = [visual_states.index(state) for state in visual]
    visual_cells = [remap[pid] for pid in result]
    preview = normalizer.lib.encode(size, origin, visual_states, visual_cells, "Tavern furniture native preview")
    args.out.mkdir(parents=True)
    (args.out / "payload.schem").write_bytes(derivative)
    (args.out / "scene.schem").write_bytes(preview)
    (args.out / "scene.blocks.json").write_text(json.dumps({"version": 1, "size": size,
        "materials": [{"block": state} for state in visual_states], "blocks": [[i % size[0], i // (size[0] * size[2]),
        i // size[0] % size[2], pid] for i, pid in enumerate(visual_cells) if visual_states[pid] != "minecraft:air"]}))
    (args.out / "scene.atelier.json").write_text(json.dumps({"route": [[origin[0], origin[1] + 1, origin[2]]]}))
    furniture = []
    for record in row.get("furniture", []):
        value = dict(record)
        value["point"] = [record["point"][a] - origin[a] for a in range(3)]
        value.update(sourceId=args.source_id, sourceSha256=source_sha)
        value["vanillaBlocks"] = [{**block, "point": [block["point"][a] - origin[a] for a in range(3)]}
            for block in record.get("vanillaBlocks", [])]
        furniture.append(value)
    (args.out / "furniture.json").write_text(json.dumps({"schema": 1, "coordinateSpace": "building-local",
        "sites": {args.source_id: {"sourceId": args.source_id, "sourceSha256": source_sha,
                                  "furniture": furniture}}}, indent=2) + "\n")
    report = {"sourceId": args.source_id, "sourceSchematicSha256": source_sha,
        "schematicSha256": hashlib.sha256(preview).hexdigest(), "payloadSha256": hashlib.sha256(derivative).hexdigest(),
        "recipeSha256": hashlib.sha256(args.recipe.read_bytes()).hexdigest(),
        "changes": proof, "diningSeats": proofs, "nativeRuntimeAcceptance": False}
    (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"sourceId": args.source_id, "changes": len(proof), "schematicSha256": report["schematicSha256"]}))


if __name__ == "__main__":
    main()
