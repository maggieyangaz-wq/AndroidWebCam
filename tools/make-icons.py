#!/usr/bin/env python3
"""Builds the app's launcher icon and the viewer page's favicon from design/app-icon.png.

The design is a square artwork with its own rounded frame. Android adaptive icons are
masked by the launcher (circle, rounded square, ...), and only the centre 72 of 108 dp
is visible, so:
  - the frame is dropped and the artwork is scaled so that everything important stays
    inside the 66 dp safe zone (visible with every mask shape);
  - the background layer is the same artwork, extended beyond its edges and blurred, so
    the sharp artwork (foreground, with a soft edge) blends into it seamlessly.

Usage: python3 tools/make-icons.py   (needs Pillow and numpy)
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "design", "app-icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
FAVICON = os.path.join(ROOT, "core", "src", "main", "resources", "web", "favicon.png")

# Measured on the source artwork (1254 px): frame width and corner radius of the inner area.
FRAME_INSET = 12 / 1254
CORNER_RADIUS = 290 / 1254
# Farthest important detail (phone corners) from the centre, as a fraction of the artwork side.
CONTENT_RADIUS = 632 / 1254
CONTENT_RADIUS_DP = 34.0  # keep it inside the 72 dp viewport circle (safe zone: 33 dp)
LAYER_DP = 108.0
MASTER = 1296  # working size of a 108 dp layer, in pixels
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def rounded_rect_mask(size, inset, radius, feather):
    """Alpha (0..1) of a rounded rectangle; fades from 1 to 0 over `feather` px at its edge."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) + 0.5
    lo, hi = inset + radius, size - inset - radius
    dx = np.maximum(np.maximum(lo - x, x - hi), 0)
    dy = np.maximum(np.maximum(lo - y, y - hi), 0)
    outside = np.hypot(dx, dy) - radius  # > 0 outside the shape
    inner = np.minimum(np.minimum(x - inset, size - inset - x), np.minimum(y - inset, size - inset - y))
    distance = np.where((dx > 0) & (dy > 0), outside, -inner)  # signed distance, < 0 inside
    return np.clip(-distance / max(feather, 1e-6), 0, 1)


def push_pull(color, weight):
    """Fills pixels with low weight from their surroundings (pyramid push-pull)."""
    h, w = weight.shape
    if h <= 2 or w <= 2:
        total = max(weight.sum(), 1e-6)
        mean = (color * weight[..., None]).sum(axis=(0, 1)) / total
        return np.broadcast_to(mean, color.shape).copy()
    hp, wp = h + h % 2, w + w % 2
    c = np.zeros((hp, wp, 3), np.float32)
    k = np.zeros((hp, wp), np.float32)
    c[:h, :w] = color * weight[..., None]
    k[:h, :w] = weight
    c2 = c.reshape(hp // 2, 2, wp // 2, 2, 3).mean(axis=(1, 3))
    k2 = k.reshape(hp // 2, 2, wp // 2, 2).mean(axis=(1, 3))
    coarse = push_pull(c2 / np.maximum(k2, 1e-6)[..., None], np.minimum(k2 * 2, 1))
    up = np.stack([
        np.asarray(Image.fromarray(np.ascontiguousarray(coarse[..., i]), "F").resize((wp, hp), Image.BILINEAR))
        for i in range(3)
    ], axis=-1)[:h, :w]
    return weight[..., None] * color + (1 - weight[..., None]) * up


def blur(rgb, radius):
    image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
    return np.asarray(image.filter(ImageFilter.GaussianBlur(radius)), np.float32)


def to_image(rgb, alpha=None):
    data = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None:
        return Image.fromarray(data, "RGB")
    return Image.fromarray(np.dstack([data, np.clip(alpha * 255, 0, 255).astype(np.uint8)]), "RGBA")


def main():
    source = Image.open(SOURCE).convert("RGB")
    side = source.width
    if source.height != side:
        sys.exit("design/app-icon.png must be square")

    # Scale so the farthest important detail sits CONTENT_RADIUS_DP from the centre.
    art = int(round(MASTER * CONTENT_RADIUS_DP / LAYER_DP / CONTENT_RADIUS))
    offset = (MASTER - art) // 2
    artwork = np.asarray(source.resize((art, art), Image.LANCZOS), np.float32)
    interior = rounded_rect_mask(art, FRAME_INSET * art, CORNER_RADIUS * art, 2)

    color = np.zeros((MASTER, MASTER, 3), np.float32)
    valid = np.zeros((MASTER, MASTER), np.float32)
    color[offset:offset + art, offset:offset + art] = artwork
    valid[offset:offset + art, offset:offset + art] = interior
    extended = push_pull(color, valid)  # the artwork's colours continued beyond its frame

    background = blur(extended, MASTER * 0.03)
    soft_edge = rounded_rect_mask(art, FRAME_INSET * art, CORNER_RADIUS * art, art * 0.06)
    alpha = np.zeros((MASTER, MASTER), np.float32)
    alpha[offset:offset + art, offset:offset + art] = soft_edge

    foreground_image = to_image(extended, alpha)
    background_image = to_image(background)
    for density, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        foreground_image.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_foreground.webp"), quality=92, method=6)
        background_image.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher_background.webp"), quality=92, method=6)

    # Favicon / login logo of the viewer page: the design as drawn, transparent around its frame.
    shape = rounded_rect_mask(side, 0.004 * side, 0.245 * side, 1.5)
    to_image(np.asarray(source, np.float32), shape).resize((128, 128), Image.LANCZOS).save(FAVICON, optimize=True)

    if len(sys.argv) > 1:  # optional: preview of the launcher icon under common mask shapes
        preview(foreground_image, background_image, sys.argv[1])


def preview(foreground, background, path):
    size = 288  # 72 dp viewport at 4x
    layer = background.convert("RGBA")
    layer.alpha_composite(foreground)
    crop = (MASTER - MASTER * 72 / 108) / 2
    viewport = layer.crop((int(crop), int(crop), int(MASTER - crop), int(MASTER - crop))).resize((size, size), Image.LANCZOS)
    sheet = Image.new("RGBA", (size * 3 + 40, size + 20), (236, 236, 236, 255))
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) + 0.5
    r = size / 2
    circle = np.clip(r - np.hypot(x - r, y - r), 0, 1)
    squircle = np.clip(1 - (np.abs((x - r) / r) ** 5 + np.abs((y - r) / r) ** 5), 0, 1) * 30
    masks = [circle, np.clip(squircle, 0, 1), rounded_rect_mask(size, 0, size * 0.22, 1)]
    for i, mask in enumerate(masks):
        icon = viewport.copy()
        icon.putalpha(Image.fromarray((mask * 255).astype(np.uint8), "L"))
        sheet.alpha_composite(icon, (10 + i * (size + 10), 10))
    sheet.save(path)


if __name__ == "__main__":
    main()
