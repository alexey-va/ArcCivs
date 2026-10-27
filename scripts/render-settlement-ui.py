#!/usr/bin/env python3
"""Render actual CivsUiTest exports with the canonical Minecraft surface renderer.

Run the test with CIVS_UI_PREVIEW_OUT=<fixtures> first. This wrapper validates
the exported source hashes and registers the real pack providers; it does not
reconstruct menu content or draw a second implementation of the UI.
"""
from __future__ import annotations

import argparse
from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

import yaml


def digest(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def is_private(character: str) -> bool:
    point = ord(character)
    return 0xE000 <= point <= 0xF8FF or 0xF0000 <= point <= 0xFFFFD or 0x100000 <= point <= 0x10FFFD


SHARED_SPACING_FONTS = {
    "arc:dialog_ui_preview", "arc:dialog_panel_navy", "arc:dialog_panel_plum",
    "arc:dialog_panel_evergreen", "arc:dialog_panel_cocoa", "arc:dialog_panel_ornate", "arc:dialog_field_icons",
}
SHARED_SPACING = {chr(0xFA200 + bit): -(1 << bit) for bit in range(10)} | {
    chr(0xFA220 + bit): 1 << bit for bit in range(10)
}


def bind_font(bindings: dict[str, str], character: str, font: str) -> None:
    # These library spaces have identical advances and no pixels in every declared skin.
    # The legacy text renderer has one font binding per codepoint; bitmap glyphs remain strict.
    if character in SHARED_SPACING and font in SHARED_SPACING_FONTS:
        font = "arc:dialog_ui_preview"
    if character in bindings and bindings[character] != font:
        raise ValueError(f"Conflicting fonts for U+{ord(character):04X}: {bindings[character]} and {font}")
    bindings[character] = font


def component_fonts(value: object, inherited: str = "minecraft:default") -> dict[str, str]:
    """Read inherited Adventure font keys without changing the exported text."""
    result: dict[str, str] = {}
    if isinstance(value, list):
        children = value
        font = inherited
    elif isinstance(value, dict):
        font = value.get("font", inherited)
        if not isinstance(font, str) or not font:
            raise ValueError(f"Invalid component font: {font!r}")
        text = value.get("text")
        if isinstance(text, str):
            for character in text:
                if is_private(character):
                    bind_font(result, character, font)
        children = [child for child in value.values() if isinstance(child, (dict, list))]
    else:
        return result
    for child in children:
        for character, child_font in component_fonts(child, font).items():
            bind_font(result, character, child_font)
    return result


def fixture_glyphs(exported: dict, registry: dict) -> dict[str, str]:
    bindings = component_fonts(exported.get("components", {}))
    used = {character for value in exported["content"].values() for character in value if is_private(character)}
    named = {glyph["character"] for glyph in registry["glyphs"]}
    named.update(chr(int(point[2:], 16)) for point in registry["spaces"])
    default = set(map(chr, [*range(0xE540, 0xE5A0), *range(0xE600, 0xE606), *range(0xF0F01, 0xF0F13)]))
    # Older exports contain raw components for the body only. Their existing
    # button glyphs retain the explicit registry mapping, also checked in-pack.
    for character in used - bindings.keys():
        if character in named:
            bindings[character] = registry["font"]
        elif character in default:
            bindings[character] = "minecraft:default"
        else:
            raise ValueError(f"Unregistered private-use glyph: U+{ord(character):04X}")
    return {character: bindings[character] for character in sorted(used)}


def validate_glyphs(bindings: dict[str, str], resource_pack: Path, client_jar: Path, core) -> None:
    """Use the canonical resolver, including references and client fallbacks."""
    with ZipFile(resource_pack) as pack, ZipFile(client_jar) as client:
        for font in sorted(set(bindings.values())):
            providers = core.resolve_providers(pack, font, fallback_archives=(client,))
            supported = set()
            advances = {}
            for provider in providers:
                if provider.get("type") == "space":
                    supported.update(provider.get("advances", {}))
                    advances.update(provider.get("advances", {}))
                elif provider.get("type") == "bitmap":
                    supported.update("".join(provider.get("chars", [])))
            if font in SHARED_SPACING_FONTS:
                for character in bindings.keys() & SHARED_SPACING.keys():
                    if advances.get(character) != SHARED_SPACING[character]:
                        raise ValueError(f"Font {font} has a different shared spacing advance for U+{ord(character):04X}")
            for character, actual_font in bindings.items():
                if actual_font == font and character not in supported:
                    raise ValueError(f"Font {font} does not define U+{ord(character):04X}")


def load_renderer(path: Path):
    spec = importlib.util.spec_from_file_location("civs_surface_renderer", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot load canonical surface renderer: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    workspace = root.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--only", nargs="+", help="Exact fixture names, without .json")
    parser.add_argument("--client-jar", type=Path, required=True)
    parser.add_argument("--resource-pack", type=Path, required=True)
    parser.add_argument("--asset-cache", type=Path, required=True)
    parser.add_argument("--background-image", type=Path, help="Optional world backdrop forwarded to the native surface renderer")
    parser.add_argument("--gui-scale", type=int, default=3)
    parser.add_argument("--ops-root", type=Path, default=workspace / "ruscrafting-ops")
    parser.add_argument("--surface-renderer", type=Path,
                        default=workspace / "ruscrafting-ops/.agents/skills/design-ruscrafting-surfaces/scripts/render_plugin_surface_dump.py")
    args = parser.parse_args()
    output = args.output.resolve()
    source_dir = output / "controller"
    source_dir.mkdir(parents=True, exist_ok=True)
    registry_path = args.ops_root / "docs/assets/dialogue-ui/library.json"
    registry = json.loads(registry_path.read_text())
    fixtures = [args.fixtures / f"{name}.json" for name in args.only] if args.only else sorted(args.fixtures.glob("*.json"))
    sources, dialogs, bindings, provenance = {}, [], {}, {}
    for path in fixtures:
        exported = json.loads(path.read_text())
        if "dialog" not in exported:
            continue
        for source, expected in exported["sourceSha256"].items():
            if digest(root / source) != expected:
                raise ValueError(f"Stale presenter export: {path.name}; changed {source}")
        identifier = path.stem
        content = exported["content"]
        (source_dir / f"{identifier}.yml").write_text(yaml.safe_dump(content, allow_unicode=True, sort_keys=False))
        sources[identifier] = {"path": f"controller/{identifier}.yml"}
        dialog = exported["dialog"]
        dialog["viewport"] = [640, 360]
        dialogs.append(dialog)
        for character, font in fixture_glyphs(exported, registry).items():
            bind_font(bindings, character, font)
        provenance[identifier] = {"fixtureSha256": digest(path), "sourceSha256": exported["sourceSha256"]}
    if not dialogs:
        raise ValueError("No actual dialog exports were selected")
    validate_glyphs(bindings, args.resource_pack, args.client_jar, load_renderer(args.surface_renderer).CORE)
    glyphs = [{"character": character, "provider": bindings[character]} for character in sorted(bindings)]
    manifest = {
        "schema": 1, "plugin": "ArcCivs — settlement dialogs",
        "minecraft-version": "1.21.11", "minecraft-client-sha1": "ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd",
        "minecraft-asset-index-sha1": "e81d9e2536723a11eb68e11c5642708926b8be5f",
        "gallery-accent": "#9bd48d", "sources": sources, "glyphs": glyphs,
        "surfaces": {"dialogs": dialogs},
        "limitations": ["Actual compiled presenter components with fixture state. Offline preview, not native-client acceptance.",
                        "Minecraft 1.21.11 and the supplied exact resource pack; reconstructed world background."],
    }
    manifest_path = output / "visual-preview.yml"
    manifest_path.write_text(yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False))
    cache = output / ".surface-cache"
    if not cache.exists():
        cache.symlink_to(args.asset_cache.resolve(), target_is_directory=True)
    command = [sys.executable, "-B", str(args.surface_renderer), "--manifest", str(manifest_path),
               "--project-root", str(output), "--output", str(output / "render"),
               "--client-jar", str(args.client_jar), "--resource-pack", str(args.resource_pack),
               "--gui-scale", str(args.gui_scale)]
    if args.background_image:
        command.extend(["--background-image", str(args.background_image)])
    completed = subprocess.run(command, check=False)
    (output / "evidence.json").write_text(json.dumps({
        "guiScale": args.gui_scale, "physicalViewport": [640 * args.gui_scale, 360 * args.gui_scale],
        "resourcePackSha256": digest(args.resource_pack), "registrySha256": digest(registry_path),
        "rendererExitCode": completed.returncode, "exports": provenance,
        "glyphFonts": {f"U+{ord(character):04X}": font for character, font in sorted(bindings.items())},
    }, indent=2, ensure_ascii=False))
    raise SystemExit(completed.returncode)


if __name__ == "__main__":
    main()
