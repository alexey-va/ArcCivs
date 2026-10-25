#!/usr/bin/env python3
"""Convert the Kotlin road-maintenance replay export to exact-state Atelier previews.

This script chooses cameras and copies exported blocks. It does not implement
road selection, grades, materials, admission or excavation policy.
"""

import argparse
import json
from pathlib import Path


def recipe(export, stage):
    bounds = export["bounds"]
    origin = bounds["min"]
    size = [bounds["max"][axis] - origin[axis] + 1 for axis in ("x", "y", "z")]
    palette = {}
    materials = {}
    operations = []
    for cell in export[stage]:
        data = cell["data"]
        if data in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
            continue
        if data not in materials:
            name = f"state_{len(materials)}"
            materials[data] = name
            palette[name] = {"block": data, "color": "#988467", "name": data}
        at = [cell["position"][axis] - origin[axis] for axis in ("x", "y", "z")]
        operations.append({"op": "box", "from": at, "to": at, "material": materials[data]})
    return {
        "version": 1,
        "seed": 0,
        "title": "Auto2 · native capture" if stage == "before" else "Auto2 · offline admitted repairs",
        "subtitle": "Exact captured block states" if stage == "before" else "Production algorithm replay; not a live result",
        "concept": ("Captured quarry approach: stone steps and natural cut banks. The shown edges end at the capture boundary."
                    if stage == "before" else "Bounded corridor repairs with DIRT_PATH and dark oak stairs; unsupported shoulders and protected building columns stay unchanged."),
        "size": size,
        "palette": palette,
        "operations": operations,
        "preview": {
            "defaultView": "bend",
            "views": [
                {"id": "bend", "label": "Поворот", "camera": [-6, 10, -8], "target": [3.4, 3.0, 3.7], "fov": 55},
                {"id": "walk", "label": "С тропинки", "camera": [2.5, 2.62, .25], "target": [3.4, 5.0, 4.8], "fov": 70},
                {"id": "plan", "label": "План", "camera": [4.5, 17, 4.5], "target": [4.5, 1, 4.5], "perspective": False},
            ],
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scene", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    export = json.loads(args.scene.read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    for stage in ("before", "after"):
        destination = args.out / f"{stage}.atelier.json"
        destination.write_text(json.dumps(recipe(export, stage), indent=2, ensure_ascii=False) + "\n")
        print(destination)


if __name__ == "__main__":
    main()
