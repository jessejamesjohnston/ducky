# Tools

Two small Python scripts that regenerate files checked into this repo. Neither is part of the
Gradle build; run them from the repo root when you change what they produce. Both run with
[uv](https://docs.astral.sh/uv/), which fetches their libraries into a throwaway environment.

## Launcher icons: `icons/icon_gen.py`

```
uv run --no-project --with pillow --with cairosvg python tools/icons/icon_gen.py
```

Writes every launcher icon file from one set of numbers: the 12 accent foregrounds
(`app/src/main/res/drawable/ic_launcher_fg_*.xml`), the themed-icon layer
(`ic_launcher_monochrome.xml`), the adaptive icons in `app/src/main/res/mipmap-anydpi*/`, the
tile colors in `values/launcher_colors.xml`, and the README icon `docs/images/icon.png`. Each
generated file says so in its header; edit the generator, never the XML.

It prints the contrast of the ring and the duck against every tile and writes nothing if any of
them falls under 3:1. `cairosvg` needs the Cairo library from your system package manager
(`libcairo2` on Debian and Ubuntu, `cairo` on Homebrew).

## README images: `readme-images/readme_images.py`

```
uv run --no-project --with pillow python tools/readme-images/readme_images.py
```

Rebuilds the screenshots and the hero image in `docs/images/` from the captures in
`readme-images/screenshots/`. The captures come from release builds on the Android emulator with
the status bar in demo mode (`adb shell settings put global sysui_demo_allowed 1`, then the
SystemUI demo broadcasts for a 9:00 clock, a full battery and no notifications). Pass file names
to rebuild only some outputs, for example `hero.png`.
