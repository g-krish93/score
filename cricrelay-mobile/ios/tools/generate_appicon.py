#!/usr/bin/env python3
"""Render the CricRelay Live iOS app icon to match the Android adaptive launcher icon.

Android source (108x108 viewport, see android/app/src/main/res):
  - adaptive background color: #07080C
  - foreground vector:
      teal circle  (#22D3A8) center (54,54) r=46
      dark stumps  (#0F172A): bail M38,52 h32 v8; stumps M42,36 w8 h28 & M58,36 w8 h28

We rasterise full-bleed (iOS applies its own squircle mask) at high supersampling
for crisp antialiased edges, then downscale.
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw

VIEWPORT = 108.0
TARGET = 1024
SS = 4  # supersample factor

BG = (0x07, 0x08, 0x0C, 255)
TEAL = (0x22, 0xD3, 0xA8, 255)
DARK = (0x0F, 0x17, 0x2A, 255)


def render(size: int) -> Image.Image:
    canvas = size * SS
    scale = canvas / VIEWPORT
    img = Image.new("RGBA", (canvas, canvas), BG)
    d = ImageDraw.Draw(img)

    def sx(v: float) -> float:
        return v * scale

    # teal circle: center (54,54) r=46
    cx, cy, r = sx(54), sx(54), sx(46)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=TEAL)

    # dark stumps + bail
    # bail: x[38..70], y[52..60]
    d.rectangle([sx(38), sx(52), sx(70), sx(60)], fill=DARK)
    # left stump: x[42..50], y[36..64]
    d.rectangle([sx(42), sx(36), sx(50), sx(64)], fill=DARK)
    # right stump: x[58..66], y[36..64]
    d.rectangle([sx(58), sx(36), sx(66), sx(64)], fill=DARK)

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    out_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "CricRelay",
        "Assets.xcassets",
        "AppIcon.appiconset",
    )
    os.makedirs(out_dir, exist_ok=True)
    icon = render(TARGET)
    # App Store / universal icon must be opaque (no alpha channel).
    icon = icon.convert("RGB")
    path = os.path.join(out_dir, "AppIcon-1024.png")
    icon.save(path, "PNG")
    print(f"wrote {path} ({icon.size[0]}x{icon.size[1]})")


if __name__ == "__main__":
    main()
