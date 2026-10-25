#!/usr/bin/env python3
"""Curate the three private research/industry families with the workshop pipeline.

Only verified MineColonies hut hearts become control markers. No private
schematic or generated preview belongs in public Git. Use --styles/--families
for the representative inspection, then omit them for the complete 27 files.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

FAMILIES = {
    "university": ("education/university", "minecolonies:blockhutuniversity"),
    "smeltery": ("craftsmanship/metallurgy/smeltery", "minecolonies:blockhutsmeltery"),
    "crusher": ("craftsmanship/masonry/crusher", "minecolonies:blockhutcrusher"),
}
STYLES = ("nordic", "medievaloak", "caledonia")
PLACEMENT_LEVELS = {("nordic", FAMILIES["smeltery"][0]): 2}


def verify_smeltery_access(pipeline, normalizer_path, out, report):
    """Prove the reviewed common route without carving any source block.

    The outside starting cell relies on normal placement terrain checks. L2/L3
    retain their authored closed gate; the existing native resident gate-opening
    policy and ordinary player interaction can open it. This is source evidence,
    not an observed runtime route.
    """
    selected = [row for row in report["files"] if row["style"] == "nordic" and row["family"] == FAMILIES["smeltery"][0]]
    if not selected:
        return []
    normalizer = pipeline.module("civs_industry_access", normalizer_path)
    route = [(4, 2, z) for z in range(21, 11, -1)] + [(5, 2, 12)]
    results = []
    for row in selected:
        blob = (out / row["relativePath"]).read_bytes()
        size, palette, cells = normalizer.read_converted(blob)
        if size != [9, 17, 21] or pipeline.origin_of(normalizer, blob) != [4, 1, 21] or row["primaryControl"] != [6, 2, 12]:
            raise ValueError("Reviewed Nordic smeltery access frame changed")
        checks = []
        for x, y, z in route:
            if z == size[2]:
                checks.append({"feet": [x, y, z], "outsideFrame": True})
                continue
            states = {key: palette[cells[(yy * size[2] + z) * size[0] + x]]
                      for key, yy in (("floor", y - 1), ("feet", y), ("head", y + 1))}
            names = {key: state.split("[", 1)[0] for key, state in states.items()}
            gate = (x, y, z) == (4, 2, 20) and names["feet"] == "minecraft:spruce_fence_gate"
            if names["floor"] not in {"minecraft:dirt", "minecraft:cobblestone"} or names["head"] != "minecraft:air" or \
                    not (names["feet"] == "minecraft:air" or gate):
                raise ValueError(f"Reviewed source approach obstructed: {row['id']} at {[x, y, z]}: {states}")
            checks.append({"feet": [x, y, z], **states, "requiresGateOpening": gate})
        results.append({"id": row["id"], "schematicSha256": row["sha256"], "route": checks,
                        "control": row["primaryControl"], "routeLength": len(route) - 1,
                        "endpointAdjacentToControl": True, "nativeRuntimeObserved": False})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--normalizer", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--styles", nargs="+", choices=STYLES, default=list(STYLES))
    parser.add_argument("--families", nargs="+", choices=FAMILIES, default=list(FAMILIES))
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if len(set(args.styles)) != len(args.styles) or len(set(args.families)) != len(args.families):
        parser.error("Selections must be unique")
    expected = {
        f"minecolonies/{style}/{FAMILIES[family][0]}{level}": FAMILIES[family][1]
        for style in args.styles for family in args.families for level in (1, 2, 3)
    }
    hearts = {}
    for line in (args.library / "normalized-main-v4/placement-plan.jsonl").open():
        row = json.loads(line)
        identifier = row["id"]
        if identifier not in expected:
            continue
        heart = row["primaryHeart"]
        if identifier in hearts or not heart["markerPlaced"] or \
                heart["sourceState"].split("[", 1)[0] != expected[identifier] or \
                heart["status"] != "primary-technical-heart-preserved":
            raise ValueError(f"No verified original hut heart: {identifier}")
        hearts[identifier] = heart
    if set(hearts) != set(expected):
        raise ValueError(f"Missing verified hearts: {set(expected) - set(hearts)}")

    pipeline_path = Path(__file__).with_name("curate-workshops.py")
    spec = importlib.util.spec_from_file_location("civs_industry_curation", pipeline_path)
    pipeline = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pipeline)
    pipeline.STYLES = tuple(args.styles)
    pipeline.FAMILIES = tuple(FAMILIES[key][0] for key in args.families)
    pipeline.PLACEMENT_LEVELS = PLACEMENT_LEVELS
    sys.argv = [str(pipeline_path), "--library", str(args.library), "--normalizer", str(args.normalizer),
                "--out", str(args.out)] + (["--verify-only"] if args.verify_only else [])
    # The shared pipeline checks the actual pre-marker cell, exact v4 reconstruction,
    # all output cells, rotations, bounded preview size and identical upgrade frames.
    pipeline.main()
    report = (args.out / "curation-report.json").read_bytes()
    access = verify_smeltery_access(pipeline, args.normalizer.resolve(), args.out, json.loads(report))
    manifest = {
        "schemaVersion": 1,
        "privateAssets": True,
        "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "sharedPipelineSha256": hashlib.sha256(pipeline_path.read_bytes()).hexdigest(),
        "curationReportSha256": hashlib.sha256(report).hexdigest(),
        "styles": args.styles,
        "families": args.families,
        "verifiedSourceHearts": hearts,
        "placementOverrides": [{"style": style, "family": family, "referenceLevel": level}
                               for (style, family), level in PLACEMENT_LEVELS.items() if style in args.styles and
                               family in pipeline.FAMILIES],
        "reviewedAccess": access,
        "runtimeAcceptance": "Not performed; source and offline preview evidence only.",
    }
    data = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    target = args.out / "industry-manifest.json"
    if args.verify_only:
        if target.read_bytes() != data:
            raise ValueError("Industry manifest changed")
    else:
        target.write_bytes(data)


if __name__ == "__main__":
    main()
