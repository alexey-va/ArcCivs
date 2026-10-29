#!/usr/bin/env python3
"""Prove up to six existing tavern chairs without changing a schematic byte.

Writes a private metadata overlay for assemble-catalog.py. The assembler applies
it before comparing previously accepted rows, so an accepted identity cannot be
silently rewritten. Shape facts are offline evidence; runtime rechecks chairs.
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path
import json
import sys

import importlib.util


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded; spec.loader.exec_module(loaded)
    return loaded


curate = module("dining_curator", Path(__file__).with_name("curate-catalog.py"))
assemble = module("dining_assembler", Path(__file__).with_name("assemble-catalog.py"))


def overlaps(a, b):
    return all(a[i] < b[i + 3] - .00001 and a[i + 3] > b[i] + .00001 for i in range(3))


def clear(scene, box, chair=None, table_contact=None):
    for x in range(math.floor(box[0]), math.floor(box[3]) + 1):
        for z in range(math.floor(box[2]), math.floor(box[5]) + 1):
            for y in range(math.floor(box[1]) - 1, math.floor(box[4]) + 1):
                p = (x, y, z)
                if p == chair:
                    continue
                state = scene.at(p)
                if state == "outside":
                    return False
                if (curate.name(state) in curate.HAZARDS | {"minecraft:water", "minecraft:powder_snow", "minecraft:cactus", "minecraft:sweet_berry_bush"}
                        or p in scene.possible_water) and overlaps(box, [x, y, z, x + 1, y + 1, z + 1]):
                    return False
                if p == table_contact:
                    continue
                for shape in scene.boxes(state, doors=True):
                    if overlaps(box, [shape[a] + p[a % 3] for a in range(6)]):
                        return False
    return True


def chair(scene, p):
    state = scene.at(p); props = curate.properties(state)
    support = scene.at((p[0], p[1] - 1, p[2]))
    supported = curate.name(support) not in curate.HAZARDS | {"minecraft:water", "minecraft:ladder"} and any(
        .87 <= shape[4] <= 1 and shape[0] <= .5 <= shape[3] and shape[2] <= .5 <= shape[5]
        for shape in scene.boxes(support))
    return (curate.name(state).endswith("_stairs") and props.get("half") == "bottom" and
            props.get("shape") == "straight" and props.get("waterlogged", "false") == "false" and
            props.get("facing") in curate.VECTORS and supported)


def dining_proofs(scene, scale=1.0, requested_seats=None):
    """Match the adult runtime seat envelope and a reachable integer route goal."""
    reachable, _ = scene.reachable()
    requested = None if requested_seats is None else {tuple(point) for point in requested_seats}
    if requested is not None and len(requested) > 6:
        raise ValueError("At most six requested dining seats")
    result = []
    for p in sorted((scene.position(i) for i, pid in enumerate(scene.cells)
                     if curate.name(scene.palette[pid]).endswith("_stairs")), key=lambda p: (p[1], p[2], p[0])):
        if requested is not None and p not in requested:
            continue
        if p[1] > scene.origin[1] + 8 or not chair(scene, p):
            continue
        back = curate.properties(scene.at(p))["facing"]
        bx, bz = curate.VECTORS[back]; dx, dz = -bx, -bz
        pair = None
        for distance in (1, 2):
            opposite = (p[0] + dx * distance * 2, p[1], p[2] + dz * distance * 2)
            if not chair(scene, opposite) or curate.VECTORS[curate.properties(scene.at(opposite))["facing"]] != (dx, dz):
                continue
            table = (p[0] + dx * distance, p[1], p[2] + dz * distance)
            if not any(shape[4] >= .9 for shape in scene.boxes(scene.at(table))):
                table = (table[0], table[1] + 1, table[2])
                if not scene.boxes(scene.at(table)):
                    continue
            pair = opposite, table
            break
        if pair is None:
            continue
        opposite, table = pair
        offset = max(.2 * scale, .15)
        x, z = p[0] + .5 + dx * offset, p[2] + .5 + dz * offset
        tops = [s[4] for s in scene.boxes(scene.at(p)) if s[0] <= x - p[0] <= s[3] and s[2] <= z - p[2] <= s[5]]
        if not tops or not .49 <= max(tops) <= .51:
            continue
        feet_y = p[1] + .5 - .7040625 * scale
        def envelope(left, bottom, rear, right, top, front):
            corners = [(x + (dz * side + dx * depth) * scale, z + (-dx * side + dz * depth) * scale)
                       for side in (left, right) for depth in (rear, front)]
            return [min(a for a, _ in corners), feet_y + bottom * scale, min(b for _, b in corners),
                    max(a for a, _ in corners), feet_y + top * scale, max(b for _, b in corners)]
        body = [envelope(-.30, .705, -.18, .30, 1.43, .18), envelope(-.29, 1.38, -.29, .29, 1.91, .29)]
        legs = envelope(-.49, .44, -.10, .49, .87, .76)
        contact = table == (p[0] + dx, p[1], p[2] + dz) and any(
            all(box[a] <= 0 and box[a + 3] >= 1 for a in range(3)) for box in scene.boxes(scene.at(table)))
        if not all(clear(scene, box) for box in body) or not clear(scene, legs, p, table if contact else None):
            continue
        approaches = []
        for ax, az in curate.VECTORS.values():
            if (ax, az) == (bx, bz):
                continue
            for q in reachable:
                if q[0] == p[0] + ax and q[2] == p[2] + az and abs(q[1] - p[1]) <= 1.125 and q[1] - math.floor(q[1]) <= .125:
                    swept = [min(q[0] + .5 - .299 * scale, x - .299 * scale), q[1] + .001,
                             min(q[2] + .5 - .299 * scale, z - .299 * scale),
                             max(q[0] + .5 + .299 * scale, x + .299 * scale), q[1] + 1.799 * scale,
                             max(q[2] + .5 + .299 * scale, z + .299 * scale)]
                    if clear(scene, swept, p):
                        approaches.append(q)
        if not approaches:
            continue
        approach = min(approaches, key=lambda q: (abs(q[1] - p[1]), q[0], q[2], q[1]))
        result.append({"seat": list(p), "sourceChairState": scene.at(p), "oppositeChair": list(opposite),
                       "table": list(table), "tableState": scene.at(table), "approach": list(approach),
                       "routeGoal": [approach[0], math.floor(approach[1]), approach[2]], "adultScale": 1,
                       "checks": ["opposed-grounded-bottom-stairs", "table", "native-seated-envelope",
                                  "entrance-route", "adjacent-swept-transition"]})
        if len(result) == 6:
            break
    return result


def enrich(root, library, atelier, native_facts, output):
    if output.exists():
        raise ValueError("Metadata overlay output must be fresh")
    catalog_bytes = (root / "civs/v2/catalog.json").read_bytes()
    catalog = json.loads(catalog_bytes)
    if not catalog.get("completeInventory") or catalog.get("totalSources") != 14_830:
        raise ValueError("Dining enrichment requires the complete, verified assembly")
    if catalog.get("metadataEnrichment"):
        raise ValueError("Enrich the unaccepted base, never an already enriched catalog")
    if catalog["recipeInputs"]["scriptSha256"] != curate.digest(Path(curate.__file__).read_bytes()):
        raise ValueError("Curation geometry source differs from the catalog")
    for filename, expected in (("normalize-blueprint-library.py", curate.NORMALIZER_SHA), ("convert-blueprint-library.py", curate.CONVERTER_SHA), ("sponge.py", curate.SPONGE_SHA)):
        curate.pinned(atelier / filename, expected)
    sys.path.insert(0, str(atelier))
    normalizer = module("dining_normalizer", atelier / "normalize-blueprint-library.py")
    registry = json.loads(curate.pinned(library / "registry-preflight-1.21.11.json", curate.REGISTRY_SHA))
    native = json.loads(curate.pinned(native_facts, curate.NATIVE_FACTS_SHA))
    registry["_native"] = {curate.state_key(s): fact for s, fact in native["states"].items()}
    rotations = json.loads(curate.pinned(library / "native-validation/rotation-lookup/rotation-map.json", curate.ROTATIONS_SHA))
    rows = []
    for entry in catalog["entries"]:
        if entry["verdict"] != "READY" or entry["type"] != "TAVERN":
            continue
        blob, receipt_blob = assemble.verified_payload(root, entry)
        receipt = json.loads(receipt_blob)
        size, palette, cells = normalizer.read_converted(blob)
        if size != entry["size"]:
            raise ValueError("Payload frame differs")
        scene = curate.Scene(size, palette, cells, entry["origin"], registry)
        curate.fluid_check(scene)
        if scene.changes:
            raise ValueError("Dining enrichment cannot repair or mutate voxels")
        proofs = dining_proofs(scene)
        source_blob = curate.pinned(library / "converted-v2" / (entry["sourceId"] + ".schem"), entry["convertedSha256"])
        source_size, source_palette, source_cells = normalizer.read_converted(source_blob)
        turns = receipt["sourceQuarterTurns"]
        turned_size = [size[0] - 2, size[1] - 1, size[2] - 3]
        for proof in proofs:
            turned = [n - 1 for n in proof["seat"]]
            source = normalizer.rotate_position(turned, turned_size, (-turns) % 4)
            source = [source[a] - receipt["sourceFrameTranslation"][a] for a in range(3)]
            if not all(0 <= source[a] < source_size[a] for a in range(3)):
                raise ValueError("A dining chair is not an authored source cell")
            idx = (source[1] * source_size[2] + source[2]) * source_size[0] + source[0]
            state = source_palette[source_cells[idx]]
            repair = next((cell for cell in receipt.get("sourceFurnitureChanges", []) if cell["position"] == source), None)
            if repair is not None:
                if curate.state_key(state) != curate.state_key(repair["before"]):
                    raise ValueError("Dining furniture source before-state differs")
                state = repair["after"]
            rotated = rotations[state][turns]
            if curate.state_key(rotated) != curate.state_key(proof["sourceChairState"]):
                raise ValueError("Dining chair source transform differs")
            proof.update(sourcePosition=source, sourceState=state)
        rows.append({"schematicId": entry["schematicId"], "schematicSha256": entry["sha256"],
                     "sourceId": entry["sourceId"], "sourceSha256": entry["sourceSha256"], "seats": proofs,
                     "reason": None if proofs else "no-verified-paired-chair-with-safe-approach"})
        print(entry["sourceId"], len(proofs), flush=True)
    overlay = {"schemaVersion": 1, "kind": "tavern-dining", "sourceCatalogSha256": curate.digest(catalog_bytes),
               "scriptSha256": curate.digest(Path(__file__).read_bytes()), "curationScriptSha256": catalog["recipeInputs"]["scriptSha256"],
               "nativeFactsSha256": curate.NATIVE_FACTS_SHA, "registrySha256": curate.REGISTRY_SHA,
               "normalizerSha256": curate.NORMALIZER_SHA, "rotationsSha256": curate.ROTATIONS_SHA,
               "nativeRuntimeAcceptance": False, "entries": rows}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(curate.canonical(overlay))
    return {"tavernLevels": len(rows), "withSeats": sum(bool(r["seats"]) for r in rows), "seats": sum(len(r["seats"]) for r in rows),
            "overlaySha256": curate.digest(output.read_bytes())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("root", "library", "atelier", "native-facts", "out"):
        parser.add_argument("--" + option, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(enrich(args.root, args.library, args.atelier, args.native_facts, args.out)))


if __name__ == "__main__":
    main()
