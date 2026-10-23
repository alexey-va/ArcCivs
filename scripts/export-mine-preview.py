#!/usr/bin/env python3
"""Turn an actual CivsMineV4Test export into an offline Atelier block-model scene."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys


def point(value):
    return tuple(value[key] for key in ("x", "y", "z"))


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--atelier", type=Path,
                        default=repo.parent / "ruscrafting-ops/scripts/location-atelier")
    parser.add_argument("--library", type=Path,
                        default=repo.parent / "artifacts/arccivs-20261017/curated")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    assert plan["version"] == 4
    sys.path.insert(0, str(args.atelier))
    from sponge import read_schematic
    spec = importlib.util.spec_from_file_location("mine_preview_importer", args.atelier / "import-schematic.py")
    importer = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = importer
    spec.loader.exec_module(importer)
    row = next(row for row in json.loads((args.library / "curation-report.json").read_text())["files"]
               if row["id"] == plan["schematicId"])
    blob = (args.library / row["relativePath"]).read_bytes()
    assert hashlib.sha256(blob).hexdigest() == row["sha256"]
    source = read_schematic(blob)
    footprint = [point(step["point"]) for step in plan["steps"]]
    low = [min(p[axis] for p in footprint) - 2 for axis in range(3)]
    high = [max(p[axis] for p in footprint) + 2 for axis in range(3)]
    source_low = source["worldEditOffset"]
    sx, sy, sz = source["size"]
    # Context, not a proposed world edit: real schematic embedded in uniform stone.
    voxels = {(x, y, z): "minecraft:stone" for x in range(low[0], high[0] + 1)
              for z in range(low[2], high[2] + 1) for y in range(low[1], source_low[1] + 1)}
    empty = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void"}
    for index, state in enumerate(source["cells"]):
        p = (index % sx + source_low[0], index // (sx * sz) + source_low[1],
             index // sx % sz + source_low[2])
        base = state.split("[", 1)[0]
        if base == "minecraft:barrier":
            continue
        if base in empty:
            voxels.pop(p, None)
        else:
            voxels[p] = state
    for step in plan["steps"]:
        p = point(step["point"])
        if step.get("after"):
            voxels[p] = step["after"]
        else:
            voxels.pop(p, None)
    extent = list(voxels) + [(0, 0, 0)]
    lo = [min(p[axis] for p in extent) for axis in range(3)]
    hi = [max(p[axis] for p in extent) for axis in range(3)]
    size = [hi[axis] - lo[axis] + 1 for axis in range(3)]

    def local(p):
        return [p[axis] - lo[axis] for axis in range(3)]

    cells = ["minecraft:air"] * (size[0] * size[1] * size[2])
    for p, state in voxels.items():
        x, y, z = local(p)
        cells[(y * size[2] + z) * size[0] + x] = state
    palette, lookup = importer._palette_for_cells(cells, None)
    cx, cy, cz = point(plan["center"])
    bottom = point(plan["bottom"])
    lowest = plan["floors"][-1]["feetY"]
    first = plan["floors"][0]["feetY"]
    gen = point(plan["generator"])
    dx, dz = (gen[0] - cx) // 21, (gen[2] - cz) // 21

    def relative(d, s, y):
        return cx + dx * d - dz * s + .5, y, cz + dz * d + dx * s + .5

    # A single tier and centreline cross-section are the authoritative spatial views.
    views = [
        {"id": "tier", "label": "Ярус · три рукава и четыре ответвления",
         "camera": local((cx + .5, first + 45, cz + .51)),
         "target": local((cx + .5, first - 1, cz + .5)), "fov": 58, "cutY": first - lo[1]},
        {"id": "section", "label": f"Разрез · одна лестница, {len(plan['floors'])} ярусов",
         "camera": local((cx + (dx or 1) * 36, first + 8, cz + 42)),
         "target": local((cx, (lowest + bottom[1]) / 2, cz)), "fov": 55,
         "cutY": source_low[1] - lo[1], "cutZ": cz - lo[2]},
        {"id": "ladder", "label": "Лестница · вид с нижней площадки",
         "camera": local(relative(-1.5, 1.5, lowest + 1.62)),
         "target": local((bottom[0] + .5, bottom[1] + 1, bottom[2] + .5)), "fov": 70},
        {"id": "shaft", "label": "Центральный проём 3 × 3",
         "camera": local((cx + .5, bottom[1] + 3, cz + .51)),
         "target": local((cx + .5, lowest - 1, cz + .5)), "fov": 70,
         "cutY": bottom[1] + 1 - lo[1]},
        {"id": "junction", "label": "Боковой узел · глаза игрока",
         "camera": local(relative(0, 8, first + 1.62)),
         "target": local(relative(0, 13, first + 1.62)), "fov": 78},
        {"id": "gallery", "label": "Главный туннель · глаза игрока",
         "camera": local(relative(4, 0, lowest + 1.62)),
         "target": local(relative(20, 0, lowest + 1.62)), "fov": 70},
    ]
    recipe = {"version": 1, "title": "ArcCivs V4 · " + args.plan.stem, "seed": 0, "size": size,
              "origin": [-v for v in lo], "palette": palette,
              "operations": importer.rle_box_operations(size, cells, lookup),
              "preview": {"defaultView": "tier", "views": views},
              "lighting": {"targetLevel": 1, "samples": [local((p["x"], floor["feetY"], p["z"]))
                  for floor in [plan["entranceLanding"]] + plan["floors"] for p in floor["cells"]]},
              "provenance": {"sourcePlanSha256": hashlib.sha256(args.plan.read_bytes()).hexdigest(),
                  "sourceKotlinSha256": hashlib.sha256((repo / "src/main/kotlin/ru/arc/civs/CivsMinePlan.kt").read_bytes()).hexdigest(),
                  "sourceSchematicSha256": row["sha256"], "buildingLevel": plan["buildingLevel"],
                  "policy": "Actual Kotlin V4 planned block states over the original building, synthetic stone context. "
                            "Offline geometry and vanilla model preview, not live-world or native-client acceptance."}}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(recipe, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"recipe": str(args.out), "size": size, "steps": len(plan["steps"]),
                      "floors": len(plan["floors"]), "provenance": recipe["provenance"]}))


if __name__ == "__main__":
    main()
