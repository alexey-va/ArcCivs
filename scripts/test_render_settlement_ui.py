import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile


SPEC = importlib.util.spec_from_file_location("settlement_preview", Path(__file__).with_name("render-settlement-ui.py"))
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)
RENDERER = Path(__file__).resolve().parents[2] / "ruscrafting-ops/.agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py"


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

    def test_exact_pack_provider_resolves_references_spaces_and_client_fallback(self):
        core = preview.load_renderer(RENDERER).CORE
        with tempfile.TemporaryDirectory() as directory:
            pack, client = Path(directory) / "pack.zip", Path(directory) / "client.jar"
            with ZipFile(pack, "w") as archive:
                archive.writestr("assets/arc/font/dialog_ui_preview.json", json.dumps({"providers": [
                    {"type": "reference", "id": "arc:panel"},
                    {"type": "space", "advances": {self.spacer: -1}},
                ]}))
                archive.writestr("assets/arc/font/panel.json", json.dumps({"providers": [
                    {"type": "bitmap", "file": "arc:panel.png", "chars": [self.panel]},
                ]}))
            with ZipFile(client, "w") as archive:
                archive.writestr("assets/minecraft/font/default.json", json.dumps({"providers": [
                    {"type": "bitmap", "file": "minecraft:font.png", "chars": [self.icon]},
                ]}))
            preview.validate_glyphs({self.panel: "arc:dialog_ui_preview", self.spacer: "arc:dialog_ui_preview",
                                     self.icon: "minecraft:default"}, pack, client, core)
            with self.assertRaisesRegex(ValueError, "does not define"):
                preview.validate_glyphs({self.panel: "minecraft:default"}, pack, client, core)
            with self.assertRaises(core.SurfaceRenderError):
                preview.validate_glyphs({self.panel: "arc:missing"}, pack, client, core)


if __name__ == "__main__":
    unittest.main()
