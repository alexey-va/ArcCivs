#!/usr/bin/env python3
"""Render actual CivsUiTest exports with the canonical Minecraft surface renderer.

Run the test with CIVS_UI_PREVIEW_OUT=<fixtures> first. This wrapper validates
the exported source hashes and registers the real pack providers; it does not
reconstruct menu content or draw a second implementation of the UI.
"""
from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import sys

import yaml


def digest(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


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
    sources, dialogs, values, provenance = {}, [], [], {}
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
        values.extend(content.values())
        provenance[identifier] = {"fixtureSha256": digest(path), "sourceSha256": exported["sourceSha256"]}
    if not dialogs:
        raise ValueError("No actual dialog exports were selected")
    characters = set("".join(values))
    named = {glyph["character"] for glyph in registry["glyphs"]}
    named.update(chr(int(point[2:], 16)) for point in registry["spaces"])
    default = set(map(chr, [*range(0xE540, 0xE5A0), *range(0xE600, 0xE606), *range(0xF0F01, 0xF0F13)]))
    used_private = {char for char in characters if 0xE000 <= ord(char) <= 0xF8FF or 0xF0000 <= ord(char) <= 0xFFFFD}
    if unknown := used_private - named - default:
        raise ValueError(f"Unregistered private-use glyphs: {sorted(hex(ord(char)) for char in unknown)}")
    glyphs = [{"character": char, "provider": registry["font"] if char in named else "minecraft:default"}
              for char in sorted(used_private)]
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
    completed = subprocess.run([sys.executable, "-B", str(args.surface_renderer), "--manifest", str(manifest_path),
                                "--project-root", str(output), "--output", str(output / "render"),
                                "--client-jar", str(args.client_jar), "--resource-pack", str(args.resource_pack),
                                "--gui-scale", str(args.gui_scale)], check=False)
    (output / "evidence.json").write_text(json.dumps({
        "guiScale": args.gui_scale, "physicalViewport": [640 * args.gui_scale, 360 * args.gui_scale],
        "resourcePackSha256": digest(args.resource_pack), "registrySha256": digest(registry_path),
        "rendererExitCode": completed.returncode, "exports": provenance,
    }, indent=2, ensure_ascii=False))
    raise SystemExit(completed.returncode)


if __name__ == "__main__":
    main()
