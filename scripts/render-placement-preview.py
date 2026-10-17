#!/usr/bin/env python3
"""Render the compiled placement export with exact Minecraft textures and glyphs.

This bounded software projection is source evidence, not a native-client capture.
The export owns all cue geometry, panel transforms, labels and obstacle fixtures.
"""

from __future__ import annotations

import argparse
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path
import sys
from types import SimpleNamespace
from zipfile import ZipFile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def point(value):
    return np.array([value[key] for key in ("x", "y", "z")], dtype=float)


class View:
    def __init__(self, eye, target, width=1600, height=900, fov=70, up=(0, 1, 0)):
        self.eye = np.array(eye, dtype=float)
        self.forward = np.array(target, dtype=float) - self.eye
        self.forward /= np.linalg.norm(self.forward)
        self.right = np.cross(self.forward, up)
        self.right /= np.linalg.norm(self.right)
        self.up = np.cross(self.right, self.forward)
        self.width, self.height = width, height
        self.focal = height / (2 * math.tan(math.radians(fov / 2)))
        self.depth = np.full((height, width), np.inf)
        self.color = np.zeros((height, width, 3), dtype=float)
        horizon = np.linspace(0, 1, height)[:, None, None]
        self.color[:] = np.array([118, 166, 195]) * (1 - horizon) + np.array([183, 205, 211]) * horizon

    def project(self, vertices):
        relative = np.asarray(vertices) - self.eye
        z = relative @ self.forward
        return np.stack((self.width / 2 + relative @ self.right * self.focal / z,
                         self.height / 2 - relative @ self.up * self.focal / z, z), axis=-1)

    def triangle(self, vertices, uv, texture, shade=1, tiled=False):
        projected = self.project(vertices)
        if np.any(projected[:, 2] <= .02):
            return
        x0, y0 = np.maximum(np.floor(projected[:, :2].min(axis=0)).astype(int), (0, 0))
        x1, y1 = np.minimum(np.ceil(projected[:, :2].max(axis=0)).astype(int), (self.width - 1, self.height - 1))
        if x0 > x1 or y0 > y1:
            return
        a, b, c = projected
        denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(denominator) < 1e-7:
            return
        yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        wa = ((b[1] - c[1]) * (xx + .5 - c[0]) + (c[0] - b[0]) * (yy + .5 - c[1])) / denominator
        wb = ((c[1] - a[1]) * (xx + .5 - c[0]) + (a[0] - c[0]) * (yy + .5 - c[1])) / denominator
        wc = 1 - wa - wb
        inverse = wa / a[2] + wb / b[2] + wc / c[2]
        distance = 1 / np.maximum(inverse, 1e-8)
        depth = self.depth[y0:y1 + 1, x0:x1 + 1]
        mask = (wa >= -1e-7) & (wb >= -1e-7) & (wc >= -1e-7) & (distance < depth)
        if not mask.any():
            return
        coords = ((wa / a[2])[..., None] * uv[0] + (wb / b[2])[..., None] * uv[1] +
                  (wc / c[2])[..., None] * uv[2]) / np.maximum(inverse[..., None], 1e-8)
        coords = coords % 1 if tiled else np.clip(coords, 0, .999999)
        sample = texture[(coords[..., 1] * texture.shape[0]).astype(int),
                         (coords[..., 0] * texture.shape[1]).astype(int)]
        mask &= sample[..., 3] > 0
        alpha = sample[..., 3:4] / 255
        color = self.color[y0:y1 + 1, x0:x1 + 1]
        color[mask] = (color * (1 - alpha) + sample[..., :3] * shade * alpha)[mask]
        depth[mask] = distance[mask]

    def quad(self, vertices, texture, shade=1, uv=None, tiled=False):
        vertices = np.array(vertices)
        uv = np.array(uv or [(0, 0), (1, 0), (1, 1), (0, 1)], dtype=float)
        for indexes in ([0, 1, 2], [0, 2, 3]):
            self.triangle(vertices[indexes], uv[indexes], texture, shade, tiled)

    def cuboid(self, origin, size, texture):
        x, y, z = origin
        xx, yy, zz = np.array(origin) + size
        faces = [([(x, yy, z), (xx, yy, z), (xx, yy, zz), (x, yy, zz)], 1),
                 ([(x, y, zz), (xx, y, zz), (xx, y, z), (x, y, z)], .55),
                 ([(x, yy, zz), (xx, yy, zz), (xx, y, zz), (x, y, zz)], .9),
                 ([(xx, yy, z), (x, yy, z), (x, y, z), (xx, y, z)], .82),
                 ([(xx, yy, zz), (xx, yy, z), (xx, y, z), (xx, y, zz)], .72),
                 ([(x, yy, z), (x, yy, zz), (x, y, zz), (x, y, z)], .75)]
        for vertices, shade in faces:
            self.quad(vertices, texture, shade)

    def image(self):
        return Image.fromarray(np.clip(self.color, 0, 255).astype(np.uint8))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scene", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--fonts", type=Path, required=True)
    parser.add_argument("--font-core", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", help="Render one scene for first-section inspection")
    args = parser.parse_args()
    sys.path.insert(0, str(args.font_core.parent))
    import minecraft_surface_core as font_core
    glyphs, advances = font_core.load_font_glyphs(args.client, faithful32=None, raster_scale=4, font_assets=args.fonts)
    stack = SimpleNamespace(glyphs=glyphs, advances=advances, raster_scale=4)
    scenes = json.loads(args.scene.read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    textures = {}
    with ZipFile(args.client) as client:
        for name in ("orange_concrete", "lime_concrete", "cyan_concrete", "light_blue_concrete", "stone", "grass_block_top"):
            texture = np.asarray(Image.open(BytesIO(client.read(f"assets/minecraft/textures/block/{name}.png"))).convert("RGBA")).copy()
            if name == "grass_block_top":
                texture[..., :3] = texture[..., :3] * np.array([.48, .69, .29])
            textures[name.upper()] = texture

    render_report = []
    for scene in scenes["scenes"]:
        if args.only and scene["id"] != args.only:
            continue
        cameras = [("player", point(scene["eye"]), point(scene["cameraTarget"]), (0, 1, 0), 70)]
        if scene["id"] == "moved-rotated":
            cameras += [("overview", (27, 83, 27), (7, 66, 1), (0, 1, 0), 50),
                        ("side", (35, 72, 2), (7, 68, 2), (0, 1, 0), 50),
                        ("plan", (6, 92, 1), (6, 64, 1), (0, 0, -1), 50)]
        for camera_name, eye, target, up, fov in cameras:
            view = View(eye, target, fov=fov, up=up)
            ground_y = scene["groundY"] + 1
            # Tessellated fixture floor keeps near-plane clipping bounded without replacing exported cues.
            for x in range(-22, 31, 2):
                for z in range(-25, 33, 2):
                    view.quad([(x, ground_y, z), (x + 2, ground_y, z),
                               (x + 2, ground_y, z + 2), (x, ground_y, z + 2)], textures["GRASS_BLOCK_TOP"],
                              uv=[(0, 0), (2, 0), (2, 2), (0, 2)], tiled=True)
            for obstacle in scene["obstacles"]:
                lower = [obstacle[f"min{axis}"] for axis in "XYZ"]
                upper = [obstacle[f"max{axis}"] for axis in "XYZ"]
                view.cuboid(lower, np.array(upper) - lower, textures["STONE"])
            for cue in scene["cues"]:
                view.cuboid([cue[axis] for axis in "xyz"], [cue[f"s{axis}"] for axis in "xyz"], textures[cue["material"]])
            label_metrics = []
            for label in scene["labels"]:
                glyph_image = font_core.render_text_line(label["color"] + label["text"], stack)
                backdrop = Image.new("RGBA", glyph_image.size, (15, 23, 30, 190))
                backdrop.alpha_composite(glyph_image)
                texture = np.asarray(backdrop)
                width = glyph_image.width / stack.raster_scale * .025 * label["scale"]
                height = glyph_image.height / stack.raster_scale * .025 * label["scale"]
                center = point(label["center"])
                bottom = center.copy()
                bottom[1] -= label["height"] / 2 - label["inset"]
                yaw = math.radians(label["yaw"])
                right = np.array([math.cos(yaw), 0, math.sin(yaw)])
                top = bottom + np.array([0, height, 0])
                view.quad([top - right * width / 2, top + right * width / 2,
                           bottom + right * width / 2, bottom - right * width / 2], texture)
                label_metrics.append({"key": label["key"], "text": label["text"], "renderedWidth": width,
                                      "hitWidth": label["width"], "withinHitWidth": width <= label["width"]})
            image = view.image()
            draw = ImageDraw.Draw(image)
            if camera_name == "player":
                cx, cy = view.width // 2, view.height // 2
                draw.line((cx - 5, cy, cx + 5, cy), fill=(245, 245, 245), width=1)
                draw.line((cx, cy - 5, cx, cy + 5), fill=(245, 245, 245), width=1)
            draw.rectangle((0, 0, view.width, 74), fill=(18, 27, 35))
            title = f"ArcCivs / {scene['id']} / {camera_name}"
            font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 24)
            small = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 17)
            draw.text((24, 12), title, font=font, fill=(238, 238, 231))
            note = f"Source projection | distance {scene['distance']:.2f} m | shift {scene['shift']['side']:+.2f}/{scene['shift']['up']:+.2f} m | explicit fixture, no schematic mesh/native client"
            draw.text((24, 44), note, font=small, fill=(178, 194, 203))
            name = f"{scene['id']}-{camera_name}.png"
            image.save(args.out / name)
            render_report.append({"image": name, "eye": list(eye), "target": list(target), "verticalFov": fov,
                                  "labels": label_metrics})
            print(name)
    (args.out / "render-report.json").write_text(json.dumps({
        "evidence": scenes["evidence"], "sourceSha256": scenes["sourceSha256"],
        "exportSha256": sha256(args.scene.read_bytes()).hexdigest(),
        "rendererSha256": sha256(Path(__file__).read_bytes()).hexdigest(),
        "assetSha256": {str(path): sha256(path.read_bytes()).hexdigest() for path in (args.client, args.fonts)},
        "limitations": ["Explicit flat terrain and obstacle fixture, no actual schematic surface model",
                        "TextDisplay vertical raster origin is approximated within the exported label footprint",
                        "No native packet, culling, input, animation or lighting acceptance"],
        "views": render_report}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
