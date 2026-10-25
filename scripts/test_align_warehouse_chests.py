import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("warehouse_alignment", Path(__file__).with_name("align-warehouse-chests.py"))
alignment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(alignment)


class WarehouseAlignmentTest(unittest.TestCase):
    def test_mismatched_side_by_side_chests_share_the_clear_aisle_in_all_directions(self):
        for facing in alignment.SIDES:
            direction = alignment.VECTORS[alignment.SIDES[(alignment.SIDES.index(facing) + 1) % 4]]
            first, second = (0, 0, 0), direction
            source = {first: ("west", "single"), second: ("east", "single")}
            result, pairs = alignment.aligned_rows(source, lambda _: {facing})
            self.assertEqual(result[first], (facing, "left"))
            self.assertEqual(result[second], (facing, "right"))
            self.assertEqual(len(pairs), 1)
            self.assertEqual(source[first], ("west", "single"))

    def test_long_rows_have_reciprocal_pairs_without_triple_chests(self):
        source = {(x, 3, 0): ("south", "single") for x in range(19)}
        result, pairs = alignment.aligned_rows(source, lambda _: {"north", "south"})
        self.assertEqual(len(pairs), 9)
        self.assertEqual(set(result), set(source))
        self.assertEqual(result[(18, 3, 0)], ("south", "single"))
        for x in range(0, 18, 2):
            self.assertEqual(result[(x, 3, 0)], ("south", "right"))
            self.assertEqual(result[(x + 1, 3, 0)], ("south", "left"))

    def test_separate_storeys_and_lone_chests_stay_separate(self):
        source = {(0, 0, 0): ("west", "single"), (0, 5, 0): ("north", "single")}
        self.assertEqual(alignment.aligned_rows(source, lambda _: set()), (source, []))

    def test_unreviewed_corner_and_blocked_shared_front_stop_generation(self):
        for positions in ([(0, 0, 0), (1, 0, 0), (1, 0, 1)], [(0, 0, 0), (1, 0, 0)]):
            source = {p: ("north", "single") for p in positions}
            with self.assertRaises(ValueError):
                alignment.aligned_rows(source, lambda p: {"north"} if p[0] == 0 else {"south"})


if __name__ == "__main__":
    unittest.main()
