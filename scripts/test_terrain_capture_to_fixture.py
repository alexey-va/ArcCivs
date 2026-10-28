import copy
import unittest

from terrain_capture_to_fixture import convert


def capture(x, states):
    return {
        "captured_at_ms": 1000,
        "bounds": {"min": {"x": x, "y": 64, "z": 0}, "max": {"x": x + 1, "y": 64, "z": 0}},
        "palette": [
            {"block_data": "minecraft:stone", "collision": [[0, 0, 0, 1, 1, 1]]},
            {"block_data": "minecraft:air", "collision": []},
        ],
        "voxels": [[i, 0, 0, value, value is not None] for i, value in enumerate(states)],
    }


class TerrainCaptureFixtureTest(unittest.TestCase):
    def test_combines_overlapping_captures_and_preserves_unknown_cells(self):
        result = convert([capture(0, [0, None]), capture(2, [1, 0])])
        self.assertEqual(result["bounds"], {"min": {"x": 0, "y": 64, "z": 0}, "max": {"x": 3, "y": 64, "z": 0}})
        self.assertEqual(result["runs"], [1, 0, 1, -1, 1, 1, 1, 0])
        self.assertEqual(result["palette"][0]["block_data"], "minecraft:stone")
        self.assertNotIn("unknown_opaque_identity", result["palette"][0])

    def test_explicit_bounds_crop_and_preserve_unsampled_columns(self):
        result = convert([capture(0, [0, 1])], [-1, 64, 0, 1, 64, 0])
        self.assertEqual(result["runs"], [1, -1, 1, 0, 1, 1])
        self.assertEqual(result["bounds"]["min"]["x"], -1)
        self.assertEqual(result["bounds"]["max"]["x"], 1)
        cropped = convert([capture(0, [0, 1])], [1, 64, 0, 1, 64, 0])
        self.assertEqual(cropped["runs"], [1, 0])
        self.assertEqual(cropped["palette"][0]["block_data"], "minecraft:air")

    def test_conflicting_overlap_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "overlapping captures disagree"):
            convert([capture(0, [0, 0]), capture(1, [1, 0])])

    def test_conflicting_collision_shapes_are_rejected(self):
        other = copy.deepcopy(capture(2, [0, 0]))
        other["palette"][0]["collision"] = []
        with self.assertRaisesRegex(ValueError, "different collision shapes"):
            convert([capture(0, [0, 0]), other])

    def test_error_capture_is_not_treated_as_empty(self):
        bad = capture(0, [0, 0])
        bad["error"] = "chunks-unloaded"
        with self.assertRaisesRegex(ValueError, "capture contains an error"):
            convert([bad])


if __name__ == "__main__":
    unittest.main()
