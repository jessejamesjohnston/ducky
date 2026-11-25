#!/usr/bin/env python3
"""Rebuild the README images in docs/images/ from the screenshots beside this script.

    uv run --no-project --with pillow python tools/readme-images/readme_images.py

Sources are in screenshots/: phone captures (1080 x 2160) and a 10-inch tablet
capture in landscape (2560 x 1600), all from release builds on the Android
emulator with the status bar in demo mode. Outputs keep the file names and
pixel sizes the README uses:

  pond.png, alarm.png, settings.png  phone shots at 540 x 1080
  widgets.png                        the four-widget home screen at 540 x 850
  large-screen.png                   the two-pane pond at 1280 x 720
  hero.png                           1951 x 960: the two-pane window, the
                                     phone pond and the widgets on the sky to
                                     cream gradient, rounded with a soft shadow

icon.png is not made here (it comes from the launcher icon pipeline).
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SRC = HERE / "screenshots"
OUT = ROOT / "docs" / "images"

# The tablet capture is 16:10; this crop keeps the app's own content at 16:9
# and leaves out the status bar and the gesture handle.
TABLET_CROP = (0, 72, 2560, 1512)

HERO_SIZE = (1951, 960)
SKY, CREAM = (191, 227, 243), (254, 246, 236)
RADIUS = 36
SHADOW_OFFSET, SHADOW_BLUR, SHADOW_ALPHA = 12, 18, 80


def rgb(path: Path) -> Image.Image:
    im = Image.open(path)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        flat = Image.new("RGB", im.size, (255, 255, 255))
        flat.paste(im, mask=im.getchannel("A"))
        return flat
    return im.convert("RGB")


def fit(im: Image.Image, size: tuple[int, int]) -> Image.Image:
    return im.resize(size, Image.LANCZOS)


def gradient(size: tuple[int, int]) -> Image.Image:
    w, h = size
    col = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / (h - 1)
        col.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(SKY, CREAM)))
    return col.resize(size)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def place(canvas: Image.Image, shot: Image.Image, box: tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = box
    size = (x1 - x0, y1 - y0)
    tile = fit(shot, size)
    mask = rounded_mask(size, RADIUS)
    shadow = Image.new("L", canvas.size, 0)
    shadow.paste(Image.new("L", size, SHADOW_ALPHA), (x0, y0 + SHADOW_OFFSET), mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR))
    canvas.paste(Image.new("RGB", canvas.size, (40, 52, 64)), (0, 0), shadow)
    canvas.paste(tile, (x0, y0), mask)


def main() -> int:
    pond = rgb(SRC / "phone-01-pond-running.png")
    alarm = rgb(SRC / "phone-02-alarm.png")
    widgets = rgb(SRC / "phone-03-widgets.png")
    settings = rgb(SRC / "phone-04-settings.png")
    wide = rgb(SRC / "tablet-01-pond-landscape.png").crop(TABLET_CROP)

    outputs = {
        "pond.png": fit(pond, (540, 1080)),
        "alarm.png": fit(alarm, (540, 1080)),
        "settings.png": fit(settings, (540, 1080)),
        "widgets.png": fit(widgets, (540, 850)),
        "large-screen.png": fit(wide, (1280, 720)),
    }
    hero = gradient(HERO_SIZE)
    # Three slots: window left, phone in the center, widgets right.
    place(hero, wide, (56, 230, 944, 730))
    place(hero, pond, (1000, 60, 1420, 900))
    place(hero, widgets, (1476, 150, 1895, 810))
    outputs["hero.png"] = hero

    only = set(sys.argv[1:])
    for name, im in outputs.items():
        if only and name not in only:
            continue
        im.save(OUT / name, optimize=True)
        print(f"wrote docs/images/{name} {im.size[0]}x{im.size[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
