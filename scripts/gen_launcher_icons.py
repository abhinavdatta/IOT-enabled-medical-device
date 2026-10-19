"""Generate Android launcher icons for Triveni from icons/triveni_icon_1024.png.

Outputs:
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png            (legacy API 24/25)
  res/mipmap-*/ic_launcher_foreground.png                                (adaptive icon layer)
Adaptive background color is sampled from the master image corners and printed.
"""
from PIL import Image
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # repo root (script lives in scripts/)
SRC = os.path.join(ROOT, "icons", "triveni_icon_1024.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# density -> (legacy launcher px, adaptive 108dp canvas px)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# The art inside the master spans ~70% of the frame; shrink to 96/108 of the
# adaptive canvas so drop + ripples stay inside the 66dp safe circle.
FG_FIT = 96 / 108

master = Image.open(SRC).convert("RGBA")

# Sample the four corners for the adaptive background color.
px = master.load()
corners = [px[4, 4], px[4, master.height - 5], px[master.width - 5, 4], px[master.width - 5, master.height - 5]]
avg = tuple(sum(c[i] for c in corners) // 4 for i in range(3))
print("BACKGROUND_COLOR=#%02X%02X%02X" % avg)

for density, (legacy_px, fg_px) in DENSITIES.items():
    out_dir = os.path.join(RES, "mipmap-" + density)
    os.makedirs(out_dir, exist_ok=True)

    # Legacy square icon.
    master.resize((legacy_px, legacy_px), Image.LANCZOS).save(
        os.path.join(out_dir, "ic_launcher.png"), "PNG")

    # Adaptive foreground: art centered on a transparent 108dp canvas.
    art_px = round(fg_px * FG_FIT)
    art = master.resize((art_px, art_px), Image.LANCZOS)
    canvas = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
    off = (fg_px - art_px) // 2
    canvas.paste(art, (off, off), art)
    canvas.save(os.path.join(out_dir, "ic_launcher_foreground.png"), "PNG")

    print("wrote", out_dir, legacy_px, fg_px)
print("DONE")
