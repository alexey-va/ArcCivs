#!/usr/bin/env python3
"""Private ArcCivs workshops, using the existing pinned MineColonies curation pipeline.

No third-party schematic belongs in public Git. Level frames align by their
primary hut marker; explicit air in the union footprint removes obsolete edges
when upgrading. Source cells and their states are otherwise conserved.
"""
from __future__ import annotations

import argparse
import hashlib
from array import array
import importlib.util
import json
from pathlib import Path
import sys

NORMALIZER_SHA = "dac91d0cad598a3f1b08784da948c18df4409b427922d9d222b300e7579784da"
ROTATIONS_SHA = "9b98c8f3ff389a6410629578da9a57149bb5bee530e6fe1806504a4d38acd29c"
SKIPS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:barrier", "minecraft:structure_void"}

FAMILIES = ("craftsmanship/carpentry/sawmill", "craftsmanship/metallurgy/blacksmith",
            "craftsmanship/masonry/stonemason", "education/library")
STYLES = ("nordic", "medievaloak", "caledonia")


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result



def digest(blob):
    return hashlib.sha256(blob).hexdigest()


def read_pinned(path, expected):
    blob = path.read_bytes()
    if digest(blob) != expected:
        raise ValueError(f"Source digest changed: {path}")
    return blob


def origin_of(normalizer, blob):
    _, root, _ = normalizer.read_gzip_nbt(blob)
    return [-root["Metadata"]["WEOffset" + axis] for axis in "XYZ"]


def same_cells(left, right):
    ls, lp, lc = left
    rs, rp, rc = right
    return ls == rs and len(lc) == len(rc) and all(lp[a] == rp[b] for a, b in zip(lc, rc))


def surface_count(size, palette, cells):
    sx, _, sz = size
    solid = {(i % sx, i // (sx * sz), (i // sx) % sz)
             for i, pid in enumerate(cells) if palette[pid].split("[", 1)[0] not in SKIPS}
    sides = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
    return sum(any((x + dx, y + dy, z + dz) not in solid for dx, dy, dz in sides)
               for x, y, z in solid)


def boundary_states(size, palette, cells, origin):
    sx, sy, sz = size
    x, ground, _ = origin
    return {label: palette[cells[(y * sz + sz - 1) * sx + x]] if 0 <= y < sy else None
            for label, y in (("floor", ground), ("feet", ground + 1), ("head", ground + 2))}


def bounds_after_turn(size, origin, turns):
    points = []
    for x in (0, size[0] - 1):
        for y in (0, size[1] - 1):
            for z in (0, size[2] - 1):
                px, py, pz = x - origin[0], y - origin[1], z - origin[2]
                for _ in range(turns):
                    px, pz = -pz, px
                points.append((px, py, pz))
    return [[min(p[a] for p in points) for a in range(3)],
            [max(p[a] for p in points) for a in range(3)]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True, help="Private converted-v2 + normalized-main-v4 library")
    parser.add_argument("--normalizer", type=Path, required=True, help="Exact hash-pinned normalize-blueprint-library.py")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    out = args.out.resolve()
    normalizer_path = args.normalizer.resolve()
    atelier = normalizer_path.parent
    read_pinned(normalizer_path, NORMALIZER_SHA)
    sys.path.insert(0, str(atelier))
    normalizer = module("civs_workshop_normalizer", normalizer_path)
    library = args.library.resolve()
    rotations = json.loads(read_pinned(library / "native-validation/rotation-lookup/rotation-map.json", ROTATIONS_SHA))
    plan_path = library / "normalized-main-v4/placement-plan.jsonl"
    wanted = {f"minecolonies/{style}/{family}{level}" for style in STYLES for family in FAMILIES for level in (1, 2, 3)}
    plans = {}
    for line in plan_path.read_text().splitlines():
        row = json.loads(line)
        if row["id"] in wanted:
            if row["id"] in plans:
                raise ValueError("Duplicate input identity")
            plans[row["id"]] = row
    if set(plans) != wanted:
        raise ValueError(f"Missing source plans: {wanted - set(plans)}")
    outputs, files, family_reports = {}, [], []
    for style in STYLES:
        for family in FAMILIES:
            identifiers = [f"minecolonies/{style}/{family}{level}" for level in (1, 2, 3)]
            source = []
            for identifier in identifiers:
                plan = plans[identifier]
                blob = read_pinned(library / "converted-v2" / (identifier + ".schem"), plan["inputSchematicSha256"])
                size, palette, cells = normalizer.read_converted(blob)
                primary = plan["primaryHeart"]["sourcePosition"]
                x, y, z = primary
                index = (y * size[2] + z) * size[0] + x
                marker = plan["primaryHeart"]
                if marker["markerPlaced"]:
                    if palette[cells[index]] != marker["convertedStateBeforeMarker"]:
                        raise ValueError("Primary hut state changed")
                    if normalizer.HEART_MARKER not in palette:
                        palette.append(normalizer.HEART_MARKER)
                    cells[index] = palette.index(normalizer.HEART_MARKER)
                v4 = read_pinned(library / "normalized-main-v4" / (identifier + ".schem"), plan["schematicSha256"])
                vs, vo, vp, vc = normalizer.normalize(size, palette, cells, plan["placement"], rotations)
                if not same_cells((vs, vp, vc), normalizer.read_converted(v4)) or origin_of(normalizer, v4) != vo:
                    raise ValueError(f"Exact v4 reconstruction failed: {identifier}")
                source.append((size, palette, cells, primary))
            minimum = [min(-row[3][a] for row in source) for a in range(3)]
            maximum = [max(row[0][a] - 1 - row[3][a] for row in source) for a in range(3)]
            union_size = [maximum[a] - minimum[a] + 1 for a in range(3)]
            first_shift = [-minimum[a] - source[0][3][a] for a in range(3)]
            placement = dict(plans[identifiers[0]]["placement"])
            placement["sourceApproach"] = [placement["sourceApproach"][a] + first_shift[a] for a in range(3)]
            canonical = None
            for level, (identifier, (size, palette, cells, primary)) in enumerate(zip(identifiers, source), 1):
                shift = [-minimum[a] - primary[a] for a in range(3)]
                palette = list(palette)
                if "minecraft:air" not in palette:
                    palette.append("minecraft:air")
                padded = array("H", [palette.index("minecraft:air")]) * (union_size[0] * union_size[1] * union_size[2])
                for index, pid in enumerate(cells):
                    x, y, z = index % size[0], index // (size[0] * size[2]), (index // size[0]) % size[2]
                    padded[((y + shift[1]) * union_size[2] + z + shift[2]) * union_size[0] + x + shift[0]] = pid
                target_size, base_origin, target_palette, target_cells = normalizer.normalize(union_size, palette, padded, placement, rotations)
                origin = [base_origin[0], base_origin[1], target_size[2]]
                primary_target = normalizer.rotate_position([-minimum[a] for a in range(3)], union_size, placement["quarterTurnsClockwise"])
                frame = (target_size, origin, primary_target)
                if canonical is None:
                    canonical = frame
                if frame != canonical:
                    raise ValueError(f"Family frame changed: {identifier}")
                if not 0 <= origin[0] < target_size[0] or not 0 <= origin[1] < target_size[1] - 2:
                    raise ValueError("Invalid ground approach")
                if sum(target_palette[pid] == normalizer.HEART_MARKER for pid in target_cells) != 1:
                    raise ValueError("Expected one unique control marker")
                volume = len(target_cells)
                surface = surface_count(target_size, target_palette, target_cells)
                if not 0 < volume <= 50_000 or not 0 < surface <= 6_000:
                    raise ValueError(f"Complete preview budget exceeded: {identifier}")
                relative = "civs/" + identifier + ".schem"
                blob = normalizer.lib.encode(target_size, origin, target_palette, target_cells, "ArcCivs " + identifier)
                normalizer.lib.verify_output(blob, target_size, origin, target_palette, target_cells)
                if not same_cells((target_size, target_palette, target_cells), normalizer.read_converted(blob)):
                    raise ValueError("Full-cell output readback failed")
                outputs[relative] = blob
                files.append({"id": relative.removesuffix(".schem"), "relativePath": relative, "style": style,
                    "family": family, "level": level, "sha256": digest(blob), "bytes": len(blob),
                    "size": target_size, "origin": origin, "primaryControl": primary_target,
                    "entranceFeet": [origin[0], origin[1] + 1, origin[2]], "front": "south",
                    "sourceSize": size, "sourcePrimary": primary, "sourceFrameTranslation": shift,
                    "explicitAirPaddingCells": volume - len(cells), "sourceBlueprintSha256": plans[identifier]["sourceSha256"],
                    "convertedInputSha256": plans[identifier]["inputSchematicSha256"],
                    "verifiedV4Sha256": plans[identifier]["schematicSha256"], "volume": volume,
                    "completeExposedSurface": surface, "allSourceCellsConservedExceptDocumentedHeart": True,
                    "exactV4Reconstructed": True, "fullCellReadbackVerified": True,
                    "boundsRelativeToOriginForQuarterTurns": [bounds_after_turn(target_size, origin, q) for q in range(4)],
                    "sourceColumnImmediatelyNorth": boundary_states(target_size, target_palette, target_cells, origin)})
            family_reports.append({"style": style, "family": family, "ids": ["civs/" + key for key in identifiers],
                "sourceUnionMinimumRelativeToPrimary": minimum, "sourceUnionMaximumRelativeToPrimary": maximum,
                "size": canonical[0], "origin": canonical[1], "primaryControl": canonical[2],
                "quarterTurnsClockwise": placement["quarterTurnsClockwise"], "allLevelFramesIdentical": True})
    report = {"schemaVersion": 1, "assetsNotice": "Private local/server use only; never publish third-party schematics in public Git.",
        "policy": "Reuse pinned v4 native rotations and reconstruction; align source frames by primary hut; fill only added union cells with clearing air; share L1 front and ground approach outside the south face.",
        "scriptSha256": digest(Path(__file__).read_bytes()), "normalizerSha256": NORMALIZER_SHA,
        "nativeRotationMapSha256": ROTATIONS_SHA, "placementPlanSha256": digest(plan_path.read_bytes()),
        "summary": {"files": len(files), "families": len(family_reports), "allLevelFramesIdentical": True,
            "maximumVolume": max(r["volume"] for r in files), "maximumCompleteExposedSurface": max(r["completeExposedSurface"] for r in files),
            "uniqueControlMarkers": True, "fullCellReadbackVerified": True, "nativeRuntimeAcceptance": "Not performed."},
        "families": family_reports, "files": files}
    outputs["curation-report.json"] = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode()
    if args.verify_only:
        for relative, expected in outputs.items():
            if (out / relative).read_bytes() != expected:
                raise ValueError(f"Output changed: {relative}")
    else:
        out.mkdir(parents=True, exist_ok=False)
        for relative, blob in outputs.items():
            target = out / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(blob)
    print(json.dumps({"out": str(out), "verifiedOnly": args.verify_only, **report["summary"]}))


if __name__ == "__main__":
    main()
