#!/usr/bin/env python3
"""Exact-cell adapter checks use synthetic voxels, never private source packs."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("tavern_furniture", Path(__file__).with_name("prepare-tavern-furniture.py"))
furniture = importlib.util.module_from_spec(spec); spec.loader.exec_module(furniture)


class FurnitureRecipeTest(unittest.TestCase):
    def setUp(self):
        self.source = "minecolonies/fixture/fundamentals/tavern1"
        self.sha = "a" * 64
        self.cell = {"point": [1, 1, 1], "before": "minecraft:air", "after": "minecraft:oak_planks"}
        self.row = {"source_id": self.source, "source_sha256": self.sha, "status": "changed", "cells": [self.cell]}
        self.native = {"minecraft:air": {}, "minecraft:oak_planks": {}, "minecraft:barrel": {}}

    def recipe(self, row=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "recipe.json"
            path.write_text(json.dumps({"schema": 1, "coordinate_space": "raw-schematic", "variants": [row or self.row]}))
            return furniture.load_recipe(path)

    def test_only_the_reviewed_exact_cell_changes_and_all_other_voxels_are_identical(self):
        palette, cells, proof = furniture.apply([3, 3, 3], ["minecraft:air"], [0] * 27,
            self.source, self.sha, self.recipe(), self.native)
        self.assertEqual([13], [i for i, value in enumerate(cells) if value != 0])
        self.assertEqual("minecraft:oak_planks", palette[cells[13]])
        self.assertEqual([{"position": [1, 1, 1], "before": "minecraft:air", "after": "minecraft:oak_planks",
                           "reason": "reviewed-tavern-furniture"}], proof)

    def test_changed_source_hash_before_state_bounds_and_missing_native_state_all_fail(self):
        cases = [(self.row, "b" * 64, self.native),
                 ({**self.row, "cells": [{**self.cell, "before": "minecraft:stone"}]}, self.sha, self.native),
                 ({**self.row, "cells": [{**self.cell, "point": [3, 1, 1]}]}, self.sha, self.native),
                 (self.row, self.sha, {"minecraft:air": {}}),
                 ({**self.row, "cells": [{**self.cell, "after": "minecraft:barrel"}]}, self.sha, self.native)]
        for row, sha, native in cases:
            with self.subTest(row=row, sha=sha, native=native), self.assertRaises(ValueError):
                furniture.apply([3, 3, 3], ["minecraft:air"], [0] * 27, self.source, sha, self.recipe(row), native)

    def test_duplicate_points_and_cells_on_a_blocked_verdict_are_rejected(self):
        for row in ({**self.row, "cells": [self.cell, self.cell]}, {**self.row, "status": "blocked"}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                self.recipe(row)

    def test_blocked_or_unlisted_variant_preserves_existing_geometry_and_has_no_new_proof(self):
        blocked = {**self.row, "status": "blocked", "cells": []}
        for source in (self.source, "minecolonies/other/fundamentals/tavern1"):
            palette, cells, proof = furniture.apply([3, 3, 3], ["minecraft:air"], [0] * 27,
                source, self.sha, self.recipe(blocked), self.native)
            self.assertEqual(["minecraft:air"], palette)
            self.assertEqual([0] * 27, list(cells))
            self.assertEqual([], proof)


if __name__ == "__main__":
    unittest.main()
