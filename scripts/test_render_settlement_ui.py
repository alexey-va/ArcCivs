import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
from zipfile import ZipFile


SPEC = importlib.util.spec_from_file_location("settlement_preview", Path(__file__).with_name("render-settlement-ui.py"))
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)


class ExportedFontTest(unittest.TestCase):
    panel, spacer, icon = chr(0xFA300), chr(0xFA200), chr(0xE540)
    registry = {"font": "arc:dialog_ui", "glyphs": [{"character": chr(0xF9050)}], "spaces": {}}

    def test_component_font_inheritance_overrides_legacy_registry_assumptions(self):
        content = {"body-0": self.panel + self.spacer + self.icon, "back": chr(0xF9050)}
        exported = {"content": content, "components": {"body": [{"text": {
            "font": "arc:dialog_ui_preview", "text": self.panel,
            "extra": [{"text": self.spacer}, {"font": "minecraft:default", "text": self.icon}],
        }}]}}
        self.assertEqual(preview.fixture_glyphs(exported, self.registry), {
            self.panel: "arc:dialog_ui_preview", self.spacer: "arc:dialog_ui_preview",
            self.icon: "minecraft:default", chr(0xF9050): "arc:dialog_ui",
        })
        self.assertEqual(exported["content"], content)

    def test_conflicting_fonts_and_unknown_unmapped_glyph_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "Conflicting fonts"):
            preview.component_fonts([{"font": "arc:first", "text": self.panel},
                                     {"font": "arc:second", "text": self.panel}])
        with self.assertRaisesRegex(ValueError, "Unregistered private-use glyph"):
            preview.fixture_glyphs({"content": {"body-0": self.panel}}, self.registry)
        for font in (None, "", 42):
            with self.subTest(font=font), self.assertRaisesRegex(ValueError, "Invalid component font"):
                preview.component_fonts({"font": font, "text": self.panel})

    def test_wrapper_checks_resolved_glyphs_and_propagates_unknown_font_failure(self):
        # Reference resolution belongs to the canonical renderer's own suite.
        # Exercise our validator against that dependency's resolved-provider contract.
        core = SimpleNamespace(resolve_providers=Mock(return_value=[
            {"type": "bitmap", "chars": [self.panel]},
            {"type": "space", "advances": {self.spacer: -1}},
        ]))
        with tempfile.TemporaryDirectory() as directory:
            pack, client = Path(directory) / "pack.zip", Path(directory) / "client.jar"
            for path in (pack, client):
                with ZipFile(path, "w"):
                    pass
            preview.validate_glyphs({self.panel: "arc:dialog_ui_preview", self.spacer: "arc:dialog_ui_preview"}, pack, client, core)
            core.resolve_providers.assert_called_once()
            call = core.resolve_providers.call_args
            self.assertEqual(call.args[1], "arc:dialog_ui_preview")
            self.assertEqual(Path(call.args[0].filename), pack)
            self.assertEqual([Path(archive.filename) for archive in call.kwargs["fallback_archives"]], [client])
            with self.assertRaisesRegex(ValueError, "does not define"):
                preview.validate_glyphs({self.icon: "arc:dialog_ui_preview"}, pack, client, core)
            failure = ValueError("Unknown font: arc:missing")
            core.resolve_providers.side_effect = failure
            with self.assertRaises(ValueError) as raised:
                preview.validate_glyphs({self.panel: "arc:missing"}, pack, client, core)
            self.assertIs(raised.exception, failure)


if __name__ == "__main__":
    unittest.main()
