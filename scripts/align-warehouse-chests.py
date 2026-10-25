#!/usr/bin/env python3
"""Align private warehouse chest rows without moving blocks or changing capacity.

Consumes the pinned rack-conversion manifest and its payload. Generated scenes,
schematics and coordinate manifests are private assets and must stay out of Git.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

BASELINE_SHA = "33031d94f1324f8bb4e97408ecfd960581ffdb141db32b0b12b2126952516cbf"
NORMALIZER_SHA = "dac91d0cad598a3f1b08784da948c18df4409b427922d9d222b300e7579784da"
CONVERTER_SHA = "5bcfbe5ba577fae85927752c06bdf46b3ee7ccae46c63e02c963db8e3a3a35c4"
SIDES = ("south", "west", "north", "east")
VECTORS = {"south": (0, 0, 1), "west": (-1, 0, 0), "north": (0, 0, -1), "east": (1, 0, 0)}
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
PASSABLE = AIR | {"minecraft:wall_torch", "minecraft:torch", "minecraft:ladder"}


def aligned_rows(chests, open_faces):
    """Source-verified straight rows share an aisle and reciprocal vanilla halves."""
    remaining = set(chests)
    result = dict(chests)
    pairs = []
    while remaining:
        first = min(remaining)
        remaining.remove(first)
        pending, row = [first], []
        while pending:
            position = pending.pop()
            row.append(position)
            for vector in VECTORS.values():
                neighbor = tuple(a + b for a, b in zip(position, vector))
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    pending.append(neighbor)
        if len(row) == 1:
            continue
        if len({p[0] for p in row}) == 1:
            common = {"east", "west"}
        elif len({p[2] for p in row}) == 1:
            common = {"north", "south"}
        else:
            raise ValueError("Warehouse chest row is not straight; review its layout")
        common.intersection_update(*(open_faces(p) for p in row))
        if not common:
            raise ValueError("Warehouse chest row has no shared clear aisle")
        facing = min(common, key=lambda side: (sum(chests[p][0] != side for p in row), SIDES.index(side)))
        row.sort()
        for position in row:
            result[position] = (facing, "single")
        for left, right in zip(row[::2], row[1::2]):
            delta = tuple(b - a for a, b in zip(left, right))
            # Native ChestBlock: LEFT connects clockwise from FACING, RIGHT counterclockwise.
            first_type = "left" if delta == VECTORS[SIDES[(SIDES.index(facing) + 1) % 4]] else "right"
            result[left] = (facing, first_type)
            result[right] = (facing, "right" if first_type == "left" else "left")
            pairs.append({"positions": [list(left), list(right)], "facing": facing})
    return result, pairs


def digest(blob):
    return hashlib.sha256(blob).hexdigest()


def pinned(path, expected):
    blob = path.read_bytes()
    if digest(blob) != expected:
        raise ValueError(f"Source digest differs: {path}")
    return blob


def write(path, blob):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != blob:
        raise ValueError(f"Refusing to overwrite different output: {path}")
    path.write_bytes(blob)


def write_json(path, value):
    write(path, (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode())


def scene(out, blob, size, palette, cells):
    sx, _, sz = size
    write(out / "scene.schem", blob)
    write_json(out / "report.json", {"schematicSha256": digest(blob)})
    write_json(out / "scene.atelier.json", {"name": "Exact private warehouse chest alignment", "route": []})
    write_json(out / "scene.blocks.json", {"version": 1, "size": size,
        "materials": [{"block": state} for state in palette],
        "blocks": [[i % sx, i // (sx * sz), (i // sx) % sz, value]
                   for i, value in enumerate(cells) if palette[value] not in AIR]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--atelier", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", choices=("medievaloak1",))
    args = parser.parse_args()
    manifest = json.loads(pinned(args.baseline / "warehouse-chests-manifest.json", BASELINE_SHA))
    normalizer_path = args.atelier / "normalize-blueprint-library.py"
    pinned(normalizer_path, NORMALIZER_SHA)
    pinned(args.atelier / "convert-blueprint-library.py", CONVERTER_SHA)
    sys.path.insert(0, str(args.atelier))
    spec = importlib.util.spec_from_file_location("warehouse_alignment_normalizer", normalizer_path)
    normalizer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(normalizer)
    receipts = []
    for source in manifest["files"]:
        style = source["family"].split("/")[0]
        key = f'{style}{source["level"]}'
        if args.only and args.only != key:
            continue
        relative = source["relativePath"]
        if relative != f'civs/minecolonies/{style}/craftsmanship/storage/warehouse{source["level"]}.schem':
            raise ValueError("Unexpected warehouse path")
        before = pinned(args.baseline / "payload" / relative, source["sha256"])
        size, palette, cells = normalizer.read_converted(before)
        _, nbt, _ = normalizer.read_gzip_nbt(before)
        if nbt.get("BlockEntities") or nbt.get("Entities"):
            raise ValueError("Warehouse template contains entity payloads")
        sx, sy, sz = size
        origin = [-nbt["Metadata"]["WEOffset" + axis] for axis in "XYZ"]
        if size != source["size"] or origin != source["origin"]:
            raise ValueError("Warehouse frame differs")
        def index(position):
            x, y, z = position
            return (y * sz + z) * sx + x
        def state_at(position):
            if any(position[a] < 0 or position[a] >= size[a] for a in range(3)):
                return "outside"
            return palette[cells[index(position)]]
        chests = {}
        for change in source["changes"]:
            if not change["new"].startswith("minecraft:chest["):
                continue
            position = tuple(change["position"])
            if state_at(position) != change["new"]:
                raise ValueError("Authored chest differs from pinned baseline")
            _, props = normalizer.lib.parts(change["new"])
            if props != {"facing": change["facing"], "type": "single", "waterlogged": "false"}:
                raise ValueError("Expected the reviewed dry single-chest baseline")
            if state_at((position[0], position[1] + 1, position[2])) not in AIR:
                raise ValueError("Chest lid is obstructed")
            chests[position] = (props["facing"], "single")
        def open_faces(position):
            x, y, z = position
            return {side for side, (dx, _, dz) in VECTORS.items()
                    if all(state_at((x + dx, y + h, z + dz)).split("[", 1)[0] in PASSABLE for h in (0, 1))}
        aligned, pairs = aligned_rows(chests, open_faces)
        updated, changes = list(cells), []
        for position, (facing, kind) in sorted(aligned.items()):
            state = f"minecraft:chest[facing={facing},type={kind},waterlogged=false]"
            if state == state_at(position):
                continue
            if state not in palette:
                palette.append(state)
            updated[index(position)] = palette.index(state)
            changes.append({"position": list(position), "before": state_at(position), "after": state})
        if Counter(palette[v].split("[", 1)[0] for v in cells) != Counter(palette[v].split("[", 1)[0] for v in updated):
            raise ValueError("Chest alignment changed the material bill")
        authored = {index(p) for p in chests}
        if any(a != b and i not in authored for i, (a, b) in enumerate(zip(cells, updated))):
            raise ValueError("Chest alignment changed an unauthored cell")
        after = normalizer.lib.encode(size, origin, palette, updated, "ArcCivs aligned warehouse chests", data_version=nbt["DataVersion"])
        normalizer.lib.verify_output(after, size, origin, palette, updated)
        previous = f'civs/legacy-warehouse-chests/{style}/warehouse{source["level"]}.schem'
        write(args.out / "payload" / relative, after)
        write(args.out / "payload" / previous, before)
        write(args.out / "payload" / source["legacyRelativePath"],
              pinned(args.baseline / "payload" / source["legacyRelativePath"], source["legacySha256"]))
        scene(args.out / "scenes" / key, after, size, palette, updated)
        scene(args.out / "scenes" / (key + "-before"), before, size, palette, cells)
        receipts.append({"id": source["id"], "relativePath": relative, "sha256": digest(after),
            "previousRelativePath": previous, "previousSha256": digest(before),
            "rackLegacyRelativePath": source["legacyRelativePath"], "rackLegacySha256": source["legacySha256"],
            "size": size, "origin": origin, "chestBlocks": len(chests), "slotsBefore": len(chests) * 27,
            "slotsAfter": len(chests) * 27, "doubleChests": len(pairs), "singleChests": len(chests) - 2 * len(pairs),
            "materialCountsUnchanged": True, "nonChestCellsUnchanged": True, "fullCellReadbackVerified": True,
            "pairs": pairs, "changes": changes})
        print(f"{key}: {len(pairs)} double chests, {len(chests) - 2 * len(pairs)} singles, {len(changes)} states changed")
    write_json(args.out / "warehouse-alignment-manifest.json", {"schemaVersion": 1, "privateAssetsOnly": True,
        "baselineManifestSha256": BASELINE_SHA, "generatorSha256": digest(Path(__file__).read_bytes()),
        "nativeReadbackVerified": False, "runtimeClientAcceptance": False, "files": receipts})


if __name__ == "__main__":
    main()
