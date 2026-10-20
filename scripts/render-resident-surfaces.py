#!/usr/bin/env python3
"""Render compiled equipment/nameplate fixtures with Minecraft assets.

This is an offline source preview, not a client capture. Input JSON is exported
by CivsEquipmentMenuTest and CivsHologramsTest after running the real presenters.
"""
from __future__ import annotations

import argparse
import base64
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
from io import BytesIO
import json
import math
from pathlib import Path
import sys
from types import SimpleNamespace
from zipfile import ZipFile

import numpy as np
import yaml
from PIL import Image, ImageDraw


def module(name, path):
    spec = spec_from_file_location(name, path)
    loaded = module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


def digest(path):
    return sha256(Path(path).read_bytes()).hexdigest()


def transformed(vertices, transform, feet, height):
    """Same scale -> left quaternion -> passenger translation as the exported Kotlin pose."""
    points = np.asarray(vertices, dtype=float) * np.asarray(transform["scale"])
    q = np.asarray(transform["rotation"])
    points = points + 2 * np.cross(q[:3], np.cross(q[:3], points) + q[3] * points)
    return points + np.asarray(transform["translation"]) + np.asarray(feet) + (0, height, 0)


def render_neighbour_states(args, client, projection):
    fixture = args.fixtures / "placement-neighbours.json"
    if not fixture.is_file():
        return None
    data = json.loads(fixture.read_text())

    def texture(name):
        return np.asarray(Image.open(BytesIO(client.read("assets/minecraft/textures/block/" + name + ".png"))).convert("RGBA"))

    grass = texture("grass_block_top").copy()
    grass[..., :3] = grass[..., :3] * (.48, .69, .29)
    textures = {"SMOOTH_SANDSTONE": texture("sandstone_top"), "RED_CONCRETE": texture("red_concrete")}
    stone = texture("stone")
    outputs = []
    for state in data["scenes"]:
        ground = state["groundY"]
        view = projection.View(state["eye"], state["target"], width=1280, height=720, fov=70)
        for x in range(-12, 32):
            for z in range(-15, 28):
                view.quad([(x, ground, z), (x + 1, ground, z), (x + 1, ground, z + 1), (x, ground, z + 1)], grass)
        # These low masses locate saved bounds; they are expressly not replacement building designs.
        for box in state["existing"]:
            low, high = box["min"], box["max"]
            view.cuboid((low["x"] + .3, ground, low["z"] + .3),
                        (high["x"] - low["x"] + .4, .35, high["z"] - low["z"] + .4), stone)
        candidate = state.get("candidate")
        if candidate:
            low, high = candidate["min"], candidate["max"]
            # A translucent plan annotation identifies the proposed area, not another runtime entity.
            overlay = np.full((16, 16, 4), (231, 155, 54, 95), dtype=np.uint8)
            view.quad([(low["x"], ground + .02, low["z"]), (high["x"] + 1, ground + .02, low["z"]),
                       (high["x"] + 1, ground + .02, high["z"] + 1), (low["x"], ground + .02, high["z"] + 1)], overlay)
        for cue in state["cues"]:
            view.cuboid((cue["x"], cue["y"], cue["z"]), (cue["sx"], cue["sy"], cue["sz"]), textures[cue["material"]])
        filename = f"placement-neighbours-{state['id']}.png"
        view.image().save(args.out / filename)
        outputs.append(filename)
    return {"fixture": fixture.name, "sha256": digest(fixture), "sourceSha256": data["sourceSha256"], "views": outputs,
            "context": "Compiled footprint marks. Low stone masses and orange plan annotation locate boxes; neither is a runtime building or display."}


def render_workplace_states(args, client, projection):
    fixture = args.fixtures / "workplace-decor.json"
    if not fixture.is_file():
        return None
    states = json.loads(fixture.read_text())["states"]

    def texture(name):
        return np.asarray(Image.open(BytesIO(client.read("assets/minecraft/textures/" + name + ".png"))).convert("RGBA"))

    grass = texture("block/grass_block_top").copy()
    grass[..., :3] = grass[..., :3] * (.48, .69, .29)
    side, lid, bottom = [texture("block/barrel_" + name) for name in ("side", "top", "bottom")]
    barrel_faces = [([(-.5, 1, -.5), (.5, 1, -.5), (.5, 1, .5), (-.5, 1, .5)], side, 1),
                    ([(-.5, 0, .5), (.5, 0, .5), (.5, 0, -.5), (-.5, 0, -.5)], side, .6),
                    ([(-.5, 1, .5), (.5, 1, .5), (.5, 0, .5), (-.5, 0, .5)], bottom, .9),
                    ([(.5, 1, -.5), (-.5, 1, -.5), (-.5, 0, -.5), (.5, 0, -.5)], lid, .85),
                    ([(.5, 1, .5), (.5, 1, -.5), (.5, 0, -.5), (.5, 0, .5)], side, .85),
                    ([(-.5, 1, -.5), (-.5, 1, .5), (-.5, 0, .5), (-.5, 0, -.5)], side, .8)]
    outputs = []
    for state in states:
        view = projection.View((3.2, 1.62, -3.4), (0, .55, 0), width=1280, height=720, fov=60)
        for x in range(-10, 11):
            for z in range(-10, 11):
                view.quad([(x, 0, z), (x + 1, 0, z), (x + 1, 0, z + 1), (x, 0, z + 1)], grass)
        for face, pixels, shade in barrel_faces:
            view.quad(face, pixels, shade)
        for piece in state["pieces"]:
            if piece.get("itemDisplayTransform", "FIXED") != "FIXED" or piece.get("entityYaw", 0) or piece.get("entityPitch", 0):
                raise ValueError("Workplace preview supports the exported zero-yaw/pitch FIXED items only")
            # Generated/handheld FIXED Y180 is followed by ItemDisplayRenderer's Y180.
            # They cancel before right rotation -> scale -> left rotation -> translation.
            # Keep both native faces at +/-1/32; thin alpha-boundary edge faces are not drawn.
            for z in (-1 / 32, 1 / 32):
                points = np.asarray([(-.5, .5, z), (.5, .5, z), (.5, -.5, z), (-.5, -.5, z)])
                q = np.asarray(piece["transform"].get("rightRotation", [0, 0, 0, 1]))
                points = points + 2 * np.cross(q[:3], np.cross(q[:3], points) + q[3] * points)
                points = transformed(points, piece["transform"], (0, 0, 0), 0)
                view.quad(points, texture("item/" + piece["material"].lower()))
        filename = f"workplace-{state['id']}-player.png"
        view.image().save(args.out / filename)
        outputs.append(filename)
    return {"fixture": fixture.name, "sha256": digest(fixture), "views": outputs,
            "barrel": "Default north-facing barrel, actual side and end textures; decorative items have no hitbox"}


def render_cart_states(args, client, core, stack, labels, projection):
    fixture = args.fixtures / "courier-cart.json"
    if not fixture.is_file() or args.cart_grounding is None:
        return None
    data = json.loads(fixture.read_text())
    model = json.loads(args.cart_grounding.read_text())
    statuses = yaml.safe_load((Path(__file__).parent.parent / "src/main/resources/locales.yml").read_text())["ru"]["workers"]["status"]
    empty_statuses = {"empty-before-pickup": "moving-to-pickup", "return-empty": "waiting-orders"}
    if data["item"] != model["identity"]["itemsadder_id"]:
        raise ValueError("The cart fixture and measured model refer to different ItemsAdder items")
    for source in model["model_provenance"]:
        if digest(source["path"]) != source["sha256"]:
            raise ValueError("The current cart model differs from the measured grounding export")
    texture_root = Path(model["identity"]["resourcepack_root"])
    textures = {}

    def model_texture(key):
        if key not in textures:
            packed = model["render"]["textures"][key]
            pixels = base64.b64decode(packed["data_uri"].split(",", 1)[1])
            if sha256(pixels).hexdigest() != packed["sha256"]:
                raise ValueError("Measured cart texture hash mismatch")
            textures[key] = np.asarray(Image.open(BytesIO(pixels)).convert("RGBA"))
        return textures[key]

    def vanilla(name):
        return np.asarray(Image.open(BytesIO(client.read("assets/minecraft/textures/" + name + ".png"))).convert("RGBA"))

    grass = vanilla("block/grass_block_top").copy()
    grass[..., :3] = grass[..., :3] * (.48, .69, .29)
    skin = Image.open(BytesIO(client.read("assets/minecraft/textures/entity/player/wide/steve.png"))).convert("RGBA")
    skin_parts = [("head", (-.2344, 1.40625, -.2344), (.46875, .46875, .46875), (8, 8, 16, 16)),
                  ("body", (-.2344, .703125, -.1172), (.46875, .703125, .2344), (20, 20, 28, 32)),
                  ("left-leg", (-.2344, 0, -.1172), (.2344, .703125, .2344), (4, 20, 8, 32)),
                  ("right-leg", (0, 0, -.1172), (.2344, .703125, .2344), (4, 20, 8, 32)),
                  ("left-arm", (-.46875, .703125, -.1172), (.2344, .703125, .2344), (20, 20, 28, 32)),
                  ("right-arm", (.2344, .703125, -.1172), (.2344, .703125, .2344), (20, 20, 28, 32))]

    def cube_faces(origin, size):
        x, y, z = origin
        xx, yy, zz = np.asarray(origin) + size
        return [[(x, yy, z), (xx, yy, z), (xx, yy, zz), (x, yy, zz)],
                [(x, y, zz), (xx, y, zz), (xx, y, z), (x, y, z)],
                [(x, yy, zz), (xx, yy, zz), (xx, y, zz), (x, y, zz)],
                [(xx, yy, z), (x, yy, z), (x, y, z), (xx, y, z)],
                [(xx, yy, zz), (xx, yy, z), (xx, y, z), (xx, y, zz)],
                [(x, yy, z), (x, yy, zz), (x, y, zz), (x, y, z)]]

    def shade(points):
        points = np.asarray(points)
        normal = np.cross(points[1] - points[0], points[2] - points[0])
        normal /= max(np.linalg.norm(normal), 1e-8)
        return .64 + .36 * abs(normal @ np.array([-.34, .85, .40]))

    outputs = []
    frames = []
    for state in data["states"]:
        feet = np.asarray(state["feet"])
        cameras = [("player", feet + (3.5, 1.62, 5.5), feet + (0, 1.30, .50), (0, 1, 0), 60)]
        if state["id"] == "loaded":
            cameras += [("front", feet + (0, 1.62, 5.0), feet + (0, 1.2, .5), (0, 1, 0), 60),
                        ("side", feet + (5, 1.62, .7), feet + (0, .8, .7), (0, 1, 0), 60),
                        ("plan", feet + (0, 6, .7), feet + (0, 0, .7), (0, 0, -1), 50)]
        for name, eye, target, up, fov in cameras:
            view = projection.View(eye, target, width=1280, height=720, fov=fov, up=up)
            for x in range(-12, 15):
                for z in range(-12, 15):
                    view.quad([(x, 0, z), (x + 1, 0, z), (x + 1, 0, z + 1), (x, 0, z + 1)], grass)
            # The mannequin supplies familiar player scale; the cart uses every authored model face.
            radians = math.radians(-state["yaw"])
            actor = dict(scale=[1, 1, 1], translation=[0, 0, 0], rotation=[0, math.sin(radians / 2), 0, math.cos(radians / 2)])
            for _, origin, size, uv in skin_parts:
                for face in cube_faces(origin, size):
                    points = transformed(face, actor, feet, 0)
                    view.quad(points, np.asarray(skin.crop(uv)), shade(points))
            for face in model["render"]["faces"]:
                points = transformed(face["positions_item_display_base"], state["cart"], feet, state["nativeHeight"])
                uv = [(u, 1 - v) for u, v in face["uv_normalized"]]
                view.quad(points, model_texture(face["texture"]), shade(points), uv=uv)
            for piece in state["cargo"]:
                if piece["block"]:
                    # Exported fixture uses cobblestone, whose six actual block faces share one texture.
                    texture = vanilla("block/" + piece["material"].lower())
                    for face in cube_faces((0, 0, 0), (1, 1, 1)):
                        points = transformed(face, piece["transform"], feet, state["nativeHeight"])
                        view.quad(points, texture, shade(points))
                else:
                    texture = vanilla("item/" + piece["material"].lower())
                    points = transformed([(-.5, .5, 0), (.5, .5, 0), (.5, -.5, 0), (-.5, -.5, 0)],
                                         piece["transform"], feet, state["nativeHeight"])
                    view.quad(points, texture)
            if name != "plan":
                value = labels["courier"]
                label_text = value["text"]
                if state["id"] in empty_statuses:
                    label_text = label_text.replace(statuses["carrying-cargo"], statuses[empty_statuses[state["id"]]])
                lines = [core.render_text_line(line, stack) for line in label_text.split("\n")]
                texture = Image.new("RGBA", (max(line.width for line in lines) + 4, 18 * len(lines) + 4), (28, 24, 19, 105))
                for index, line in enumerate(lines):
                    texture.alpha_composite(line, ((texture.width - line.width) // 2, index * 18))
                bottom = feet + (0, value["offset"], 0)
                right = view.right * texture.width / 2 * .025 * value["scale"] / 2
                top = bottom + view.up * texture.height / 2 * .025 * value["scale"]
                view.quad([top - right, top + right, bottom + right, bottom - right], np.asarray(texture))
            output = view.image()
            filename = f"cart-{state['id']}-{name}.png"
            output.save(args.out / filename)
            outputs.append({"image": filename, "state": state["id"], "timeMillis": state["timeMillis"],
                            "eye": eye.tolist(), "target": target.tolist(), "yaw": state["yaw"]})
            if name == "player":
                frames.append((state, output.resize((640, 360), Image.Resampling.LANCZOS)))
    storyboard = Image.new("RGB", (1280, 400 * math.ceil(len(frames) / 2)), (26, 24, 21))
    pen = ImageDraw.Draw(storyboard)
    for index, (state, frame) in enumerate(frames):
        x, y = index % 2 * 640, index // 2 * 400
        storyboard.paste(frame, (x, y + 32))
        pen.text((x + 12, y + 10), f"{state['timeMillis']} ms | {state['id']} | yaw {state['yaw']} deg", fill=(231, 214, 183))
    storyboard.save(args.out / "cart-motion-states.png")
    return {"fixture": fixture.name, "sha256": digest(fixture), "modelGroundingSha256": digest(args.cart_grounding),
            "modelProvenance": model["model_provenance"], "modelFaces": len(model["render"]["faces"]), "views": outputs,
            "emptyStatusSamples": empty_statuses, "emptyStatusSource": "ru.workers.status in canonical locales.yml; sampled preview states"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--fonts", type=Path, required=True)
    parser.add_argument("--surface-renderer", type=Path, required=True)
    parser.add_argument("--only", help="Render one inventory first for representative inspection")
    parser.add_argument("--cart-grounding", type=Path, help="Measured model export with exact ItemDisplay context/base faces")
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
                for key, center in (("npc", (0, labels["npc"]["offset"], 0)), ("building", (1.6, labels["building"]["offset"], 0))):
                    value = labels[key]
                    lines = value["text"].split("\n")
                    rendered_lines = [core.render_text_line(line, stack) for line in lines]
                    widths = [line.width for line in rendered_lines]
                    texture = Image.new("RGBA", (max(widths), 9 * 2 * len(lines) + 4), tuple(value.get("background", [15, 23, 30, 150])))
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
            cart_report = render_cart_states(args, client, core, stack, labels, projection)
            if cart_report:
                report.append(cart_report)
            workplace_report = render_workplace_states(args, client, projection)
            if workplace_report:
                report.append(workplace_report)
            neighbours_report = render_neighbour_states(args, client, projection)
            if neighbours_report:
                report.append(neighbours_report)

    sources = [Path(__file__).parent.parent / "src/main/kotlin/ru/arc/civs" / name for name in
               ("CivsEquipmentMenu.kt", "CivsHolograms.kt", "CivsCourierCart.kt", "CivsWorkplaceDecor.kt", "CivsBoundaryRenderer.kt", "CivsText.kt")]
    sources.append(Path(__file__).parent.parent / "src/main/resources/locales.yml")
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
