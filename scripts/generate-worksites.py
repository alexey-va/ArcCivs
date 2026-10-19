#!/usr/bin/env python3
"""Generate small spatial contracts from the private, hash-verified curated huts.

Only coordinates and source digests enter Git. Optional previews are private
derivatives of the original voxels, never replacement building schematics.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

STYLES = ("nordic", "medievaloak", "caledonia")
TYPES = {"farmer": "FARM", "lumberjack": "FORESTER", "miner": "QUARRY"}
EMPTY = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:barrier"}
MINE = {
    "nordic": ((-4, -2, -11), "north", (-4, -14)),
    "medievaloak": ((7, -5, -8), "west", (4, -8)),
    "caledonia": ((-3, -5, -10), "east", (0, -10)),
}
VECTORS = {"north": (0, -1), "east": (1, 0), "south": (0, 1), "west": (-1, 0)}


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


def rotate(point, turns):
    x, y, z = point
    for _ in range(turns % 4):
        x, z = -z, x
    return [x, y, z]


def box(points):
    return {"min": [min(p[a] for p in points) for a in range(3)],
            "max": [max(p[a] for p in points) for a in range(3)]}


def inside(point, bounds):
    return all(bounds["min"][a] <= point[a] <= bounds["max"][a] for a in range(3))


def farm(style, level):
    if style == "medievaloak":
        center = {(x, 1, z) for x in range(11, 14) for z in range(-17, -14)}
        soil = [[x, 1, z] for z in range(-20, -11) for x in range(8, 17)
                if (x, 1, z) not in center]
        water = [[x, 1, z] for z in (-17, -15) for x in (11, 13)]
        return {"soilCells": soil, "irrigationCells": water, "irrigationExisting": True,
                "workEntry": [7, 2, -16], "alternateEntry": [12, 2, -11],
                "gateCells": [[7, 2, -16], [12, 2, -11]] if level == 3 else [],
                "preservedCenter": {"min": [11, 1, -17], "max": [13, 3, -15]}}
    cx, cz = (14, -13) if style == "nordic" else (13, -7)
    return {"soilCells": [[x, 0, z] for z in range(cz - 4, cz + 5)
                          for x in range(cx - 4, cx + 5) if (x, z) != (cx, cz)],
            "irrigationCells": [[cx, 0, cz]], "irrigationExisting": False,
            "workEntry": [cx - 5, 1, cz], "gateCells": []}


def mine(style, level):
    bottom, facing, center = MINE[style]
    x, y, z = bottom
    dx, dz = VECTORS[facing]
    # A 2-wide, 3-high gallery with one crosscut; 6 Y separates usable floors.
    # Coordinates describe clearance/support, never a resource respawn volume.
    route = {(d, s) for d in range(1, 13) for s in (0, 1)}
    route |= {(d, s) for d in (7, 8) for s in range(-6, 8)}
    route |= {(d, s) for d in range(-1, 2) for s in range(-1, 2)}
    route -= {(0, 0), (-1, 0)}  # uninterrupted ladder and its solid backing column
    floors = []
    for index in range(1, level + 1):
        feet_y = y - index * 6
        support = [[x + dx * d - dz * s, feet_y - 1, z + dz * d + dx * s]
                   for d, s in sorted(route)]
        floors.append({"index": index, "feetY": feet_y, "height": 3,
                       "landing": [x + dx, feet_y, z + dz], "floorCells": support})
    return {"ladderBottom": list(bottom), "ladderFacing": facing,
            "firstSubgroundPoint": [x, y - 1, z], "shaftCenter": [center[0], y - 1, center[1]],
            "shaftFootprint": [[x + dx * d - dz * s, z + dz * d + dx * s]
                               for d in range(-1, 2) for s in range(-1, 2)],
            "floors": floors}


def state_at(snapshot, point):
    x, y, z = [point[a] - snapshot["worldEditOffset"][a] for a in range(3)]
    sx, sy, sz = snapshot["size"]
    if not (0 <= x < sx and 0 <= y < sy and 0 <= z < sz):
        return None
    return snapshot["cells"][(y * sz + z) * sx + x]


def validate_site(site, snapshot):
    bounds = site["schematicBounds"]
    if site["type"] == "FARM":
        field = site["farm"]
        soil, water = field["soilCells"], field["irrigationCells"]
        assert len(soil) == (72 if field["irrigationExisting"] else 80)
        assert len({tuple(p) for p in soil + water}) == len(soil) + len(water)
        for p in soil:
            assert any(p[1] == w[1] and max(abs(p[0] - w[0]), abs(p[2] - w[2])) <= 4 for w in water)
            if field["irrigationExisting"]:
                assert state_at(snapshot, p) == "minecraft:dirt", (site["schematicId"], p)
                assert not inside(p, field["preservedCenter"])
                assert all(state_at(snapshot, [p[0], p[1] + dy, p[2]]).split("[", 1)[0] in EMPTY for dy in (1, 2))
            else:
                assert p[0] >= bounds["max"][0] + 3  # two untouched columns between hut and field
        if field["irrigationExisting"]:
            assert all(state_at(snapshot, p).startswith("minecraft:water[") for p in water)
            assert all(state_at(snapshot, p).startswith("minecraft:oak_fence_gate[") for p in field["gateCells"])
        points = soil + water + [field["workEntry"]]
    elif site["type"] == "QUARRY":
        shaft = site["mine"]
        ladder = state_at(snapshot, shaft["ladderBottom"])
        assert ladder.startswith("minecraft:ladder[") and "facing=" + shaft["ladderFacing"] in ladder
        assert shaft["firstSubgroundPoint"][1] == bounds["min"][1] - 1
        assert len(shaft["floors"]) == site["level"]
        points = [shaft["ladderBottom"], shaft["firstSubgroundPoint"]]
        for floor in shaft["floors"]:
            cells = {tuple(p) for p in floor["floorCells"]}
            assert len(cells) == len(floor["floorCells"])
            assert max(p[1] + floor["height"] for p in cells) < bounds["min"][1]
            connected, pending = set(), [next(iter(cells))]
            while pending:
                p = pending.pop()
                if p in connected:
                    continue
                connected.add(p)
                pending.extend(q for q in ((p[0] + 1, p[1], p[2]), (p[0] - 1, p[1], p[2]),
                                          (p[0], p[1], p[2] + 1), (p[0], p[1], p[2] - 1))
                               if q in cells and q not in connected)
            assert connected == cells
            points += floor["floorCells"]
        assert all(a["feetY"] - b["feetY"] == 6 for a, b in zip(shaft["floors"], shaft["floors"][1:]))
    else:
        points = [site["forester"]["workEntry"], *site["forester"]["searchBounds"].values()]
    for turns in range(4):
        moved = [rotate(p, turns) for p in points]
        assert len({tuple(p) for p in moved}) == len({tuple(p) for p in points})
        assert [rotate(p, 4 - turns) for p in moved] == points


def generate(library, atelier):
    sys.path.insert(0, str(atelier))
    from sponge import read_schematic
    rows = json.loads((library / "curation-report.json").read_text())["files"]
    sites, snapshots, family_frames = {}, {}, {}
    for row in rows:
        style, role = row["family"].split("/")
        if style not in STYLES or role not in TYPES or row["level"] not in (1, 2, 3):
            continue
        identifier = row["id"]
        blob = (library / row["relativePath"]).read_bytes()
        assert hashlib.sha256(blob).hexdigest() == row["sha256"], identifier
        snapshot = read_schematic(blob)
        offset, size = snapshot["worldEditOffset"], snapshot["size"]
        assert offset == [-n for n in row["origin"]] and size == row["size"]
        bounds = {"min": offset, "max": [offset[a] + size[a] - 1 for a in range(3)]}
        assert family_frames.setdefault(row["family"], bounds) == bounds
        site = {"schematicId": identifier, "schematicSha256": row["sha256"], "family": row["family"],
                "type": TYPES[role], "level": row["level"], "schematicBounds": bounds, "entrance": [0, 1, 0]}
        if role == "farmer":
            site["farm"] = farm(style, row["level"])
        elif role == "miner":
            site["mine"] = mine(style, row["level"])
        else:
            site["forester"] = {"workEntry": [0, 1, 0],
                                "searchBounds": {"min": [bounds["min"][0] - 12, -4, bounds["min"][2] - 12],
                                                 "max": [bounds["max"][0] + 12, 24, bounds["max"][2] + 12]}}
        validate_site(site, snapshot)
        sites[identifier], snapshots[identifier] = site, snapshot
    assert len(sites) == 27 and len(family_frames) == 9
    return {"version": 1, "coordinateSpace": "schematic-origin-relative", "sites": sites}, snapshots


def preview(document, snapshots, output, atelier, identifiers):
    """Editable private recipes; actual textures/schematics are baked by atelier CLI."""
    importer = module("worksite_schematic_import", atelier / "import-schematic.py")
    output.mkdir(parents=True, exist_ok=True)
    for identifier in identifiers:
        site, source = document["sites"][identifier], snapshots[identifier]
        bounds = site["schematicBounds"]
        voxels = {}
        sx, _, sz = source["size"]
        for i, state in enumerate(source["cells"]):
            if state.split("[", 1)[0] in EMPTY | {"minecraft:structure_void"}:
                continue
            p = (i % sx + bounds["min"][0], i // (sx * sz) + bounds["min"][1],
                 i // sx % sz + bounds["min"][2])
            voxels[p] = state
        # Planar ground is preview context, never an instruction to flatten live terrain.
        context = box(list(voxels) + site.get("farm", {}).get("soilCells", []))
        for x in range(context["min"][0] - 2, context["max"][0] + 3):
            for z in range(context["min"][2] - 2, max(3, context["max"][2] + 3)):
                if not (bounds["min"][0] <= x <= bounds["max"][0] and bounds["min"][2] <= z <= bounds["max"][2]):
                    voxels[(x, 0, z)] = "minecraft:grass_block[snowy=false]"
        if "farm" in site:
            field = site["farm"]
            for x, y, z in field["soilCells"]:
                voxels[x, y, z] = "minecraft:farmland[moisture=7]"
                voxels[x, y + 1, z] = "minecraft:wheat[age=7]"
            for p in field["irrigationCells"]:
                voxels[tuple(p)] = "minecraft:water[level=0]"
        if "mine" in site:
            shaft = site["mine"]
            bottom = shaft["floors"][-1]["feetY"]
            lo = box([p for floor in shaft["floors"] for p in floor["floorCells"]] + [shaft["firstSubgroundPoint"]])
            for x in range(lo["min"][0] - 2, lo["max"][0] + 3):
                for z in range(lo["min"][2] - 2, lo["max"][2] + 3):
                    for y in range(bottom - 2, 1):
                        if not inside((x, y, z), bounds):
                            voxels[x, y, z] = "minecraft:stone"
            for y in range(bottom, shaft["firstSubgroundPoint"][1] + 1):
                for x, z in shaft["shaftFootprint"]:
                    voxels.pop((x, y, z), None)
            x, _, z = shaft["ladderBottom"]
            dx, dz = VECTORS[shaft["ladderFacing"]]
            for y in range(bottom, shaft["firstSubgroundPoint"][1] + 1):
                voxels[x - dx, y, z - dz] = "minecraft:cobblestone"
                voxels[x, y, z] = "minecraft:ladder[facing=" + shaft["ladderFacing"] + ",waterlogged=false]"
            for floor in shaft["floors"]:
                for x, y, z in floor["floorCells"]:
                    voxels[x, y, z] = "minecraft:stone"
                    for dy in range(1, floor["height"] + 1):
                        voxels.pop((x, y + dy, z), None)
        extent = box(list(voxels) + [(0, 0, 0)])
        allowed = {tuple(p) for p in site.get("farm", {}).get("soilCells", [])}
        for i, state in enumerate(source["cells"]):
            if state.split("[", 1)[0] in EMPTY | {"minecraft:structure_void"}:
                continue
            p = (i % sx + bounds["min"][0], i // (sx * sz) + bounds["min"][1],
                 i // sx % sz + bounds["min"][2])
            assert p in allowed or voxels.get(p) == state, (identifier, "Changed authored voxel", p)
        size = [extent["max"][a] - extent["min"][a] + 1 for a in range(3)]
        origin = [-n for n in extent["min"]]
        cells = ["minecraft:air"] * (size[0] * size[1] * size[2])
        for p, state in voxels.items():
            x, y, z = [p[a] - extent["min"][a] for a in range(3)]
            cells[(y * size[2] + z) * size[0] + x] = state
        palette, lookup = importer._palette_for_cells(cells, None)
        recipe = {"version": 1, "title": "Worksite proposal: " + identifier, "seed": 0,
                  "size": size, "origin": origin, "palette": palette,
                  "operations": importer.rle_box_operations(size, cells, lookup),
                  "provenance": {"sourceSchematicSha256": site["schematicSha256"],
                                 "policy": "Original building voxels plus proposed worksite at mature state; synthetic flat terrain; not runtime acceptance"}}
        name = identifier.split("/")[2] + "--" + identifier.split("/")[-1]
        (output / (name + ".atelier.json")).write_text(json.dumps(recipe, separators=(",", ":")) + "\n")


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, default=root.parent / "artifacts/arccivs-20261017/curated")
    parser.add_argument("--atelier", type=Path, default=root.parent / "ruscrafting-ops/scripts/location-atelier")
    parser.add_argument("--output", type=Path, default=root / "src/main/resources/worksites.json")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--preview-dir", type=Path)
    parser.add_argument("--preview-id", action="append", default=[])
    args = parser.parse_args()
    document, snapshots = generate(args.library.resolve(), args.atelier.resolve())
    # Compact per-cell arrays stay readable and avoid thousands of coordinate-only lines.
    payload = json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n"
    if args.verify:
        assert args.output.read_text() == payload, "Regenerate stale worksite metadata"
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload)
    if args.preview_dir:
        preview(document, snapshots, args.preview_dir, args.atelier.resolve(), args.preview_id)
    print(json.dumps({"sites": len(document["sites"]), "rotationsChecked": 108,
                      "sourceHashesVerified": 27, "output": str(args.output), "verified": args.verify}))


if __name__ == "__main__":
    main()
