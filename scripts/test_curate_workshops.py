import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("workshop_curation", Path(__file__).with_name("curate-workshops.py"))
curation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(curation)


class FamilyPlacementTest(unittest.TestCase):
    def test_original_families_keep_level_one(self):
        for style in curation.STYLES:
            for family in curation.FAMILIES:
                self.assertEqual(curation.placement_reference_index(style, family), 0)

    def test_reviewed_override_is_exactly_scoped(self):
        family = "craftsmanship/metallurgy/smeltery"
        with patch.dict(curation.PLACEMENT_LEVELS, {("nordic", family): 2}, clear=True):
            self.assertEqual(curation.placement_reference_index("nordic", family), 1)
            self.assertEqual(curation.placement_reference_index("medievaloak", family), 0)
            self.assertEqual(curation.placement_reference_index("nordic", "education/library"), 0)

    def test_invalid_reference_cannot_silently_select_another_level(self):
        for value in (0, 4, -1, True, "2"):
            with patch.dict(curation.PLACEMENT_LEVELS, {("nordic", "test"): value}, clear=True):
                with self.assertRaises(ValueError):
                    curation.placement_reference_index("nordic", "test")


if __name__ == "__main__":
    unittest.main()
