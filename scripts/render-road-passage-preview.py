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
        "title": "Auto2 · исходный проход" if stage == "before" else "Auto2 · промежуточный блок",
        "subtitle": "Точные блоки из снимка мира" if stage == "before" else "Офлайн-прогон алгоритма ремонта · не живой сервер",
        "concept": ("Подход к карьеру из снимка мира. Края модели совпадают с границей захваченного участка."
                    if stage == "before" else "Прямые деревянные марши разделены цельным блоком на повороте. Ровные участки — тропинка. Резерв карьера сохранён."),
        "size": size,
        "palette": palette,
        "operations": operations,
        "preview": {
            "defaultView": "bend",
            "views": [
                {"id": "bend", "label": "Поворот", "camera": [-6, 10, -8], "target": [3.4, 3.0, 3.7], "fov": 55},
                {"id": "walk", "label": "С тропинки", "camera": [2.5, 2.62, .25], "target": [3.4, 5.0, 4.8], "fov": 70},
                {"id": "plan", "label": "План", "camera": [4.5, 17, 4.5], "target": [4.5, 1, 4.5], "perspective": False},
                {"id": "landing", "label": "Промежуточный блок", "camera": [-2, 8, -3], "target": [3.1, 3.8, 4.0], "fov": 50},
            ],
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scene", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--previous", type=Path, help="Previous algorithm replay, shown as its exact rejected after state")
    args = parser.parse_args()
    export = json.loads(args.scene.read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    for stage in ("before", "after"):
        destination = args.out / f"{stage}.atelier.json"
        destination.write_text(json.dumps(recipe(export, stage), indent=2, ensure_ascii=False) + "\n")
        print(destination)
    if args.previous:
        previous = json.loads(args.previous.read_text())
        proposal = recipe({**previous, "before": previous["after"]}, "before")
        proposal["title"] = "Auto2 · прежний поворот"
        proposal["subtitle"] = "Предыдущий офлайн-прогон · отклонённый вариант"
        proposal["concept"] = "Ступень стоит прямо на углу. Это точный предыдущий результат для сравнения с промежуточным блоком."
        destination = args.out / "previous.atelier.json"
        destination.write_text(json.dumps(proposal, indent=2, ensure_ascii=False) + "\n")
        print(destination)


if __name__ == "__main__":
    main()
