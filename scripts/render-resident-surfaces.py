#!/usr/bin/env python3
"""Render compiled equipment/nameplate fixtures with Minecraft assets.

This is an offline source preview, not a client capture. Input JSON is exported
by CivsEquipmentMenuTest and CivsHologramsTest after running the real presenters.
"""
from __future__ import annotations

import argparse
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
from io import BytesIO
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from zipfile import ZipFile

import numpy as np
from PIL import Image, ImageDraw


def module(name, path):
    spec = spec_from_file_location(name, path)
    loaded = module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


def digest(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--fonts", type=Path, required=True)
    parser.add_argument("--surface-renderer", type=Path, required=True)
    parser.add_argument("--only", help="Render one inventory first for representative inspection")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    surfaces = module("resident_surface_assets", args.surface_renderer)
    core = surfaces.CORE
    glyphs, advances = core.load_font_glyphs(args.client, faithful32=None, raster_scale=2, font_assets=args.fonts)
    stack = SimpleNamespace(glyphs=glyphs, advances=advances, raster_scale=2)
    report = []

    def draw(image, x, y, value, shadow=True):
        core.draw_text(image, (x * 2, y * 2), value, stack, shadow=shadow)

    def tooltip(image, slot, item, left, top):
        lines = [item.get("name") or item["material"]] + item.get("lore", [])
        width = max(core.measured_width(line, advances) for line in lines)
        height = 8 + 10 * len(lines)
        x = min(426 - width - 10, left + 8 + slot % 9 * 18 + 18)
        y = max(8, min(240 - height - 8, top + 18 + slot // 9 * 18))
        box = ImageDraw.Draw(image)
        box.rectangle((x * 2 - 6, y * 2 - 6, (x + width + 4) * 2, (y + height - 4) * 2), fill=(16, 0, 16, 245))
        box.rectangle((x * 2 - 4, y * 2 - 4, (x + width + 3) * 2, (y + height - 5) * 2), outline=(80, 0, 170, 255), width=2)
        for index, line in enumerate(lines):
            draw(image, x, y + index * 10, line)

    with ZipFile(args.client) as client:
        for fixture in sorted(args.fixtures.glob("equipment-*.json")):
            data = json.loads(fixture.read_text())
            if args.only and data["id"] != args.only:
                continue
            image = core.synthetic_minecraft_scene((852, 480), 2, hud=False)
            image.alpha_composite(Image.new("RGBA", image.size, (0, 0, 0, 105)))
            panel = surfaces.generic_container_texture(args.client, None, 6, 2)
            left, top = (426 - panel.width // 2) // 2, (240 - panel.height // 2) // 2
            image.alpha_composite(panel, (left * 2, top * 2))
            draw(image, left + 8, top + 6, data["title"], shadow=False)
            draw(image, left + 8, top + 128, "§8Инвентарь", shadow=False)

            def item_icon(item, x, y):
                if not item:
                    return
                if item["material"] == "SHIELD":
                    # Native shields use the built-in entity renderer, not an item sprite.
                    # Retain the real board UV and aspect ratio as an explicit flat preview.
                    atlas = Image.open(BytesIO(client.read("assets/minecraft/textures/entity/shield_base_nopattern.png"))).convert("RGBA")
                    board = atlas.crop((1, 1, 13, 23)).resize((16, 28), Image.Resampling.NEAREST)
                    icon = Image.new("RGBA", (32, 32))
                    icon.alpha_composite(board, (8, 2))
                else:
                    icon = surfaces.vanilla_item_icon([client], item["material"], 32)
                image.alpha_composite(icon, (x * 2, y * 2))
                if item["amount"] > 1:
                    label = str(item["amount"])
                    width = core.measured_width(label, advances)
                    draw(image, x + 17 - width, y + 9, "§f" + label)
                if item.get("damage"):
                    # The fixture's actual damage value is shown in the tooltip export;
                    # native durability thresholds remain client-owned.
                    pass

            for slot, item in enumerate(data["slots"]):
                item_icon(item, left + 8 + slot % 9 * 18, top + 18 + slot // 9 * 18)
            for slot, item in enumerate(data["playerSlots"]):
                item_icon(item, left + 8 + slot % 9 * 18,
                          top + (197 if slot < 9 else 139 + (slot // 9 - 1) * 18))
            destination = args.out / (data["id"] + ".png")
            image.convert("RGB").save(destination)
            hover = image.copy()
            slot = 6 if data["id"] == "equipment-equipped" else 49
            if data["slots"][slot]:
                tooltip(hover, slot, data["slots"][slot], left, top)
                hover.convert("RGB").save(args.out / (data["id"] + "-hover.png"))
            report.append({"fixture": fixture.name, "sha256": digest(fixture), "image": destination.name,
                           "titleWidth": core.measured_width(data["title"], advances), "nativeTitleSpace": 160})

        labels_path = args.fixtures / "nameplates.json"
        if not args.only and labels_path.is_file():
            projection = module("resident_surface_projection", Path(__file__).with_name("render-placement-preview.py"))
            labels = json.loads(labels_path.read_text())
            stone = np.asarray(Image.open(BytesIO(client.read("assets/minecraft/textures/block/stone.png"))).convert("RGBA"))
            grass = np.asarray(Image.open(BytesIO(client.read("assets/minecraft/textures/block/grass_block_top.png"))).convert("RGBA")).copy()
            grass[..., :3] = grass[..., :3] * np.array([.48, .69, .29])
            skin = Image.open(BytesIO(client.read("assets/minecraft/textures/entity/player/wide/steve.png"))).convert("RGBA")
            # A vanilla skin reference mannequin establishes eye/head clearance.
            # It does not predict the profession skin selected by Citizens.
            face = np.asarray(skin.crop((8, 8, 16, 16)))
            shirt = np.asarray(skin.crop((20, 20, 28, 32)))
            legs = np.asarray(skin.crop((4, 20, 8, 32)))
            for distance in (4, 8):
                view = projection.View((0, 1.62, distance), (0, 1.55, 0), width=1280, height=720, fov=70)
                for x in range(-16, 17):
                    for z in range(-16, 17):
                        view.quad([(x, 0, z), (x + 1, 0, z), (x + 1, 0, z + 1), (x, 0, z + 1)], grass)
                # Model dimensions are the vanilla 32-pixel model at 0.9375 display scale.
                view.cuboid((-.2344, 1.40625, -.2344), (.46875, .46875, .46875), face)
                view.cuboid((-.2344, .703125, -.1172), (.46875, .703125, .2344), shirt)
                view.cuboid((-.2344, 0, -.1172), (.2344, .703125, .2344), legs)
                view.cuboid((0, 0, -.1172), (.2344, .703125, .2344), legs)
                view.cuboid((-.46875, .703125, -.1172), (.2344, .703125, .2344), shirt)
                view.cuboid((.2344, .703125, -.1172), (.2344, .703125, .2344), shirt)
                view.cuboid((1.1, 0, -.5), (1, 1, 1), stone)
                for key, center in (("npc", (0, labels["npc"]["offset"], 0)), ("building", (1.6, 1.3, 0))):
                    value = labels[key]
                    lines = value["text"].split("\n")
                    rendered_lines = [core.render_text_line(line, stack) for line in lines]
                    widths = [line.width for line in rendered_lines]
                    texture = Image.new("RGBA", (max(widths), 9 * 2 * len(lines) + 4), (15, 23, 30, 115 if key == "npc" else 150))
                    for index, line in enumerate(rendered_lines):
                        texture.alpha_composite(line, ((texture.width - line.width) // 2, index * 18))
                    world_width = texture.width / 2 * .025 * value["scale"]
                    world_height = texture.height / 2 * .025 * value["scale"]
                    bottom = np.array(center)
                    top_line = bottom + view.up * world_height
                    right = view.right * world_width / 2
                    view.quad([top_line - right, top_line + right, bottom + right, bottom - right], np.asarray(texture))
                output = view.image()
                output.save(args.out / f"nameplates-player-{distance}m.png")
            report.append({"fixture": labels_path.name, "sha256": digest(labels_path), "playerDistances": [4, 8]})

    sources = [Path(__file__).parent.parent / "src/main/kotlin/ru/arc/civs" / name for name in
               ("CivsEquipmentMenu.kt", "CivsHolograms.kt", "CivsText.kt")]
    (args.out / "report.json").write_text(json.dumps({
        "evidence": "Compiled presenter fixture plus offline Minecraft texture/font rendering",
        "sourceSha256": {path.name: digest(path) for path in sources},
        "rendererSha256": digest(__file__), "clientSha256": digest(args.client), "fontsSha256": digest(args.fonts),
        "limitations": ["No native-client packet, input, lighting or culling acceptance",
                        "Inventory icons use canonical texture previews, not the native 3D item renderer; shield uses its actual board UV as a flat preview",
                        "Player reference mannequin is a vanilla skin with simplified UVs, not the production NPC skin",
                        "TextDisplay raster origin is approximated; no animated native interpolation acceptance"],
        "views": report,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
