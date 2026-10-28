#!/usr/bin/env python3
"""Convert bounded CivsTerrainDebug captures to the portable test-resource RLE format."""
import argparse
import json
from pathlib import Path

MAX_CELLS = 2_000_000


def convert(captures):
    if not captures:
        raise ValueError("at least one capture is required")
    minima = [min(c["bounds"]["min"][axis] for c in captures) for axis in ("x", "y", "z")]
    maxima = [max(c["bounds"]["max"][axis] for c in captures) for axis in ("x", "y", "z")]
    width, height, depth = [hi - lo + 1 for lo, hi in zip(minima, maxima)]
    if min(width, height, depth) <= 0 or width * height * depth > MAX_CELLS:
        raise ValueError("invalid or oversized fixture bounds")
    palette, palette_ids, by_state, cells = [], {}, {}, {}
    for capture in captures:
        if capture.get("error"):
            raise ValueError("capture contains an error")
        minimum = [capture["bounds"]["min"][axis] for axis in ("x", "y", "z")]
        maximum = [capture["bounds"]["max"][axis] for axis in ("x", "y", "z")]
        source_palette = capture["palette"]
        for x, y, z, source_index, available, *extra in capture["voxels"]:
            point = (minimum[0] + x, minimum[1] + y, minimum[2] + z)
            if any(p < lo or p > hi for p, lo, hi in zip(point, minimum, maximum)):
                raise ValueError("voxel outside capture bounds")
            if not available:
                continue
            if not isinstance(source_index, int) or not 0 <= source_index < len(source_palette):
                raise ValueError("invalid palette index")
            entry = source_palette[source_index]
            state = entry["block_data"]
            shape = entry.get("collision")
            unknown = bool(extra[0]) if extra else False
            # The Kotlin fixture currently resolves collision by state, so contradictory shapes cannot be merged safely.
            if state in by_state and by_state[state] != shape:
                raise ValueError("different collision shapes for the same block state")
            by_state[state] = shape
            key = json.dumps([state, shape, unknown], sort_keys=True, separators=(",", ":"))
            if key not in palette_ids:
                result = {"block_data": state}
                if shape is not None:
                    result["collision"] = shape
                if unknown:
                    result["unknown_opaque_identity"] = True
                palette_ids[key] = len(palette)
                palette.append(result)
            value = palette_ids[key]
            if point in cells and cells[point] != value:
                raise ValueError("overlapping captures disagree")
            cells[point] = value
    runs = []
    for y in range(minima[1], maxima[1] + 1):
        for z in range(minima[2], maxima[2] + 1):
            for x in range(minima[0], maxima[0] + 1):
                value = cells.get((x, y, z), -1)
                if runs and runs[-1] == value:
                    runs[-2] += 1
                else:
                    runs.extend([1, value])
    def position(values):
        return dict(zip(("x", "y", "z"), values))
    return {
        "version": 2,
        "order": "y,z,x",
        "bounds": {"min": position(minima), "max": position(maxima)},
        "provenance": {"source": "native terrain captures", "capture_times_ms": [c.get("captured_at_ms") for c in captures]},
        "palette": palette,
        "runs": runs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = convert([json.loads(path.read_text()) for path in args.captures])
    args.output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Wrote terrain fixture: {args.output}")


if __name__ == "__main__":
    main()
