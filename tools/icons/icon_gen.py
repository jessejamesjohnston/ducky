# Run from the repo root: uv run --no-project --with pillow --with cairosvg python tools/icons/icon_gen.py
"""Ducky's adaptive icon: the rubber duck inside a timer ring, one geometry for every output.

Writes, from the same numbers:
  app/src/main/res/drawable/ic_launcher_fg_<accent>.xml   12 foregrounds (ring + duck per accent)
  app/src/main/res/drawable/ic_launcher_monochrome.xml    the themed-icon layer (one silhouette)
  app/src/main/res/mipmap-anydpi/ic_launcher[_<accent>].xml
  app/src/main/res/values/launcher_colors.xml             the 12 tile colors
  docs/images/icon.png                                    README icon (192 px, rounded square)

The icon follows the user's accent through 12 launcher aliases. Every accent draws the same mark
(white ring, yellow duck) on its own tile:
  deep  the accent hue, deepened (HLS lightness only) until the yellow duck and its shade reach
        3:1 against the tile. Accents already that dark (red, teal, indigo, purple, pink, brown)
        keep their seed; orange, amber, olive, green, cyan and blue get darker.
  ink   kept for comparison: the accent seed as it is, with the ring and the duck in ink (the
        other way to keep amber and olive legible; not used by any alias today).
Nothing is written unless every mark color in every variant is at least 3:1 against its tile.

Coordinates are on the 108-unit adaptive grid; a launcher shows the inner 72 (18 to 90), and the
66-unit circle in the middle is what every mask keeps.
"""
import colorsys
import io
import math
import pathlib
import sys

# ------------------------------------------------------------------ palette
YEL = "#FFD34D"         # the merrimentlabs.com duck
YEL_SHADE = "#FAC447"   # its belly shade, as one solid color
BEAK = "#F0694A"
INK = "#221A14"
WHITE = "#FFFFFF"
MIN_CONTRAST = 3.0
DEEP_TARGET = 0.163     # tile luminance ceiling for "deep" tiles (yellow 3.5:1, shade 3.1:1)

# Parallel to PRESET_SEEDS in ui/TimerTheme.kt and ALIASES in LauncherIcon.kt.
ACCENTS = [
    ("red", "#D32F2F", "deep"),
    ("orange", "#F4511E", "deep"),
    ("amber", "#F9A825", "deep"),
    ("olive", "#9E9D24", "deep"),
    ("green", "#43A047", "deep"),
    ("teal", "#007D5D", "deep"),
    ("cyan", "#0097A7", "deep"),
    ("blue", "#1E88E5", "deep"),
    ("indigo", "#3949AB", "deep"),
    ("purple", "#8E24AA", "deep"),
    ("pink", "#D81B60", "deep"),
    ("brown", "#6D4C41", "deep"),
]
DEFAULT = "blue"

# ------------------------------------------------------------------ geometry
CX = CY = 54.0
R = 24.5            # ring radius (center of the stroke)
SW = 6.5            # ring stroke: outer diameter 2 * (R + SW / 2) = 55.5 of the visible 72
ARC_END = 250       # time left: from 12 o'clock clockwise to about 8:20

# The merrimentlabs.com duck (its SVG artwork, viewBox 96 x 80): body, belly shade, beak, eye.
BODY = ("M14 46c0-8 7-12 16-12h20c3-3 3-8 1-13-3-8 3-17 13-17 9 0 15 7 14 15-1 5-3 8-6 10 "
        "7 2 12 8 12 17 0 14-13 24-35 24S14 62 14 46z")
SHADE = "M14 46c0-8 7-12 16-12h20c-6 6-7 13-2 20 5 7 16 8 27 3-4 11-16 17-32 17C27 74 14 62 14 46z"
BEAK_D = "M77 18c6-1 12 0 15 3-2 4-8 6-15 5z"
BEAK_MONO = "M75 18.6c7-1.6 13.5-0.4 17 2.4-2 4.6-9 6.6-17 5.2z"  # overlaps the head: one silhouette
EYE = (68.0, 15.0, 3.6)
DS = 0.45                          # duck scale
DUCK_C = (53.0, 39.0)              # the duck's visual center in its own space
DUCK_AT = (54.5, 55.5)             # where that center sits on the grid (a little low: the head is tall)
DTX = DUCK_AT[0] - DUCK_C[0] * DS
DTY = DUCK_AT[1] - DUCK_C[1] * DS


def circle_d(cx, cy, r):
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0z"


def arc_d(a0, a1):
    def pt(a):
        t = math.radians(a)
        return CX + R * math.sin(t), CY - R * math.cos(t)
    (x0, y0), (x1, y1) = pt(a0), pt(a1)
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{x0:.2f},{y0:.2f}A{R:g},{R:g} 0 {large},1 {x1:.2f},{y1:.2f}"


ARC = arc_d(0, ARC_END)
TRACK = circle_d(CX, CY, R)
EYE_D = circle_d(*EYE)

# ------------------------------------------------------------------ color math


def _rgb(h):
    h = h.lstrip("#")
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]


def _hex(r, g, b):
    return "#" + "".join(f"{round(max(0, min(1, c)) * 255):02X}" for c in (r, g, b))


def lum(h):
    f = lambda c: c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = _rgb(h)
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def contrast(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def deepen(h, target):
    H, L, S = colorsys.rgb_to_hls(*_rgb(h))
    while lum(_hex(*colorsys.hls_to_rgb(H, L, S))) > target:
        L -= 0.002
    return _hex(*colorsys.hls_to_rgb(H, L, S))


def shade(h, k):
    """Same hue and saturation, lightness times k (the ring's elapsed track)."""
    H, L, S = colorsys.rgb_to_hls(*_rgb(h))
    return _hex(*colorsys.hls_to_rgb(H, L * k, S))


def variant(name, seed, mode):
    if mode == "deep":
        tile = deepen(seed, DEEP_TARGET)
        return dict(name=name, seed=seed, mode=mode, tile=tile, track=shade(tile, 0.72), arc=WHITE,
                    duck=YEL, belly=YEL_SHADE, beak=BEAK, eye=INK)
    return dict(name=name, seed=seed, mode=mode, tile=seed, track=shade(seed, 0.86), arc=INK,
                duck=INK, belly=None, beak=None, eye=None)


def mark_colors(v):
    """The colors that draw the mark's outline against the tile (beak and eye sit inside it)."""
    return {k: v[k] for k in ("arc", "duck", "belly") if v[k]}


VARIANTS = [variant(*a) for a in ACCENTS]
# For comparison only: amber and olive with an ink mark on the bright seed, and a high-contrast
# tile (the app has no high-contrast launcher alias).
ALTERNATIVES = [variant("amber", "#F9A825", "ink"), variant("olive", "#9E9D24", "ink")]
HIGH_CONTRAST = dict(name="high contrast", seed="#000000", mode="deep", tile="#14100C", track="#3A332D",
                     arc=WHITE, duck=YEL, belly=YEL_SHADE, beak=BEAK, eye="#000000")

# ------------------------------------------------------------------ SVG


def svg_mark(v, mono_color=None):
    t = f'transform="translate({DTX:.3f} {DTY:.3f}) scale({DS})"'
    if mono_color:
        return (f'<path d="{ARC}" fill="none" stroke="{mono_color}" stroke-width="{SW}" stroke-linecap="round"/>'
                f'<g {t}><path fill="{mono_color}" fill-rule="evenodd" d="{BODY} {EYE_D}"/>'
                f'<path fill="{mono_color}" d="{BEAK_MONO}"/></g>')
    ring = (f'<path d="{TRACK}" fill="none" stroke="{v["track"]}" stroke-width="{SW}"/>'
            f'<path d="{ARC}" fill="none" stroke="{v["arc"]}" stroke-width="{SW}" stroke-linecap="round"/>')
    if v["mode"] == "ink":
        return ring + (f'<g {t}><path fill="{v["duck"]}" fill-rule="evenodd" d="{BODY} {EYE_D}"/>'
                       f'<path fill="{v["duck"]}" d="{BEAK_MONO}"/></g>')
    return ring + (f'<g {t}><clipPath id="b"><path d="{BODY}"/></clipPath>'
                   f'<path fill="{v["beak"]}" d="{BEAK_D}"/>'
                   f'<path fill="{v["duck"]}" d="{BODY}"/>'
                   f'<path fill="{v["belly"]}" d="{SHADE}" clip-path="url(#b)"/>'
                   f'<path fill="{v["eye"]}" d="{EYE_D}"/></g>')


def svg_icon(v, view="0 0 108 108", size=108, tile=True):
    back = f'<rect width="108" height="108" fill="{v["tile"]}"/>' if tile else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{view}">'
            f'{back}{svg_mark(v)}</svg>')


def svg_mono(color="#000", plate=None, view="0 0 108 108", size=108):
    back = f'<rect width="108" height="108" fill="{plate}"/>' if plate else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{view}">'
            f'{back}{svg_mark(None, mono_color=color)}</svg>')

# ------------------------------------------------------------------ VectorDrawable

HDR = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
       '    android:width="108dp"\n    android:height="108dp"\n'
       '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')
DUCK_GROUP = (f'    <group\n        android:translateX="{DTX:.3f}"\n        android:translateY="{DTY:.3f}"\n'
              f'        android:scaleX="{DS}"\n        android:scaleY="{DS}">\n')
GEN = "GENERATED by tools/icons/icon_gen.py; edit that, not this."


def vd_path(d, fill=None, stroke=None, width=None, cap=False, evenodd=False):
    a = []
    if fill:
        a.append(f'android:fillColor="{fill}"')
    if evenodd:
        a.append('android:fillType="evenOdd"')
    if stroke:
        a.append(f'android:strokeColor="{stroke}"')
        a.append(f'android:strokeWidth="{width}"')
        if cap:
            a.append('android:strokeLineCap="round"')
    a.append(f'android:pathData="{d}"')
    return "<path\n" + "".join(f"            {x}\n" for x in a[:-1]) + f"            {a[-1]} />"


def vd_ring(track, arc):
    out = ""
    if track:
        out += "    " + vd_path(TRACK, stroke=track, width=SW).replace("\n            ", "\n        ") + "\n"
    out += "    " + vd_path(ARC, stroke=arc, width=SW, cap=True).replace("\n            ", "\n        ") + "\n"
    return out


def vd_silhouette(color):
    return (DUCK_GROUP
            + "        " + vd_path(f"{BODY} {EYE_D}", fill=color, evenodd=True) + "\n"
            + "        " + vd_path(BEAK_MONO, fill=color) + "\n"
            + "    </group>\n")


def vd_foreground(v):
    head = HDR + f"    <!-- {GEN}\n         Ducky, {v['name']} accent ({v['mode']}): tile {v['tile']}. -->\n"
    ring = vd_ring(v["track"], v["arc"])
    if v["mode"] == "ink":
        return head + ring + vd_silhouette(v["duck"]) + "</vector>\n"
    duck = (DUCK_GROUP
            + "        " + vd_path(BEAK_D, fill=v["beak"]) + "\n"
            + "        " + vd_path(BODY, fill=v["duck"]) + "\n"
            + "        <group>\n"
            + f'            <clip-path android:pathData="{BODY}" />\n'
            + "            " + vd_path(SHADE, fill=v["belly"]).replace("\n            ", "\n                ") + "\n"
            + "        </group>\n"
            + "        " + vd_path(EYE_D, fill=v["eye"]) + "\n"
            + "    </group>\n")
    return head + ring + duck + "</vector>\n"


def vd_monochrome():
    return (HDR + f"    <!-- {GEN}\n         Themed-icon layer: the arc and the duck as one silhouette (alpha only). -->\n"
            + vd_ring(None, "#FFFFFFFF") + vd_silhouette("#FFFFFFFF") + "</vector>\n")


def adaptive(name):
    suffix = f"_{name}" if name else ""
    color = name or DEFAULT
    note = (f"    <!-- {GEN} {color} accent, swapped in via the .Launcher{color.capitalize()} alias. -->\n"
            if name else f"    <!-- {GEN} The application icon: the default (water blue) accent. -->\n")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            + note +
            f'    <background android:drawable="@color/launcher_bg_{color}" />\n'
            f'    <foreground android:drawable="@drawable/ic_launcher_fg_{color}" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')


def colors_xml():
    rows = "".join(f'    <color name="launcher_bg_{v["name"]}">{v["tile"]}</color>\n' for v in VARIANTS)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            f"    <!-- {GEN}\n         Launcher tile per accent: the accent seed, deepened where the yellow duck\n"
            "         needs it to reach 3:1 against the tile. -->\n"
            + rows + "</resources>\n")

# ------------------------------------------------------------------ checks


def contrast_table(vs):
    rows, bad = [], []
    for v in vs:
        cs = {k: contrast(c, v["tile"]) for k, c in mark_colors(v).items()}
        rows.append((v, cs))
        bad += [(v["name"], k, x) for k, x in cs.items() if x < MIN_CONTRAST]
    return rows, bad

# ------------------------------------------------------------------ main


def render(svg_text, px):
    import cairosvg
    from PIL import Image
    png = cairosvg.svg2png(bytestring=svg_text.encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


VISIBLE = "18 18 72 72"


def main():
    root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2]
    rows, bad = contrast_table(VARIANTS + ALTERNATIVES + [HIGH_CONTRAST])
    for v, cs in rows:
        print(f"{v['name']:13s} {v['mode']:4s} seed {v['seed']} tile {v['tile']}  "
              + "  ".join(f"{k} {x:.2f}" for k, x in cs.items()))
    if bad:
        sys.exit(f"below {MIN_CONTRAST}:1, nothing written: {bad}")
    if len(VARIANTS) != 12 or len({v['name'] for v in VARIANTS}) != 12:
        sys.exit("expected 12 distinct accents")

    res = root / "app/src/main/res"
    written = 0
    for v in VARIANTS:
        (res / f"drawable/ic_launcher_fg_{v['name']}.xml").write_text(vd_foreground(v))
        (res / f"mipmap-anydpi/ic_launcher_{v['name']}.xml").write_text(adaptive(v["name"]))
        written += 1
    assert written == 12
    (res / "drawable/ic_launcher_monochrome.xml").write_text(vd_monochrome())
    (res / "mipmap-anydpi/ic_launcher.xml").write_text(adaptive(None))
    (res / "values/launcher_colors.xml").write_text(colors_xml())

    blue = next(v for v in VARIANTS if v["name"] == DEFAULT)
    # The README icon is the 72 dp a launcher shows, rounded like a launcher tile.
    visible_svg = svg_icon(blue, view=VISIBLE, size=512)
    from PIL import Image, ImageDraw
    big = render(visible_svg, 768)
    assert big.getchannel("A").getextrema() == (255, 255), "the visible area must be opaque"
    m = Image.new("L", (768, 768), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 767, 767), radius=int(768 * 0.22), fill=255)
    readme = Image.new("RGBA", (768, 768), (0, 0, 0, 0))
    readme.paste(big, (0, 0), m)
    readme.resize((192, 192), Image.LANCZOS).save(root / "docs/images/icon.png")
    print("wrote 12 foregrounds, 13 adaptive icons, monochrome, colors, README icon")


if __name__ == "__main__":
    main()
