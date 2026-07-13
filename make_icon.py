#!/usr/bin/env python3
"""Build the launcher icons from the chosen artwork in ADD/pict/ok/icon.png.

The AI-generated source has a fake-transparency checkerboard baked into the
pixels and no crisp circle edge, so the icon is rebuilt instead of cropped:
the checkerboard (and every light neutral area connected to the border) is
flooded to pure white and the artwork is located by its saturated pixels.
Two outputs: the adaptive-icon foreground layer (white square, artwork sized
to the launcher's visible safe zone) and a legacy white-disc PNG.
"""
from PIL import Image, ImageDraw

SOURCE = "ADD/pict/ok/icon.png"
TARGET_LEGACY = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
TARGET_FOREGROUND = "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"

SIZE = 432          # xxxhdpi launcher icon, 108dp * 4
SUPER = 4           # supersampling factor for the disc's antialiased edge
ART_FRACTION = 0.88 # artwork's share of the legacy disc diameter
# Adaptive foreground: the launcher mask shows the central ~72 of 108dp. Sized visually — the
# artwork's diagonal extremes (the pin) may run under the mask edge, which looks fine.
FG_FRACTION = 0.78

src = Image.open(SOURCE).convert("RGB")
w, h = src.size

# Flood the checkerboard to white from all four corners. The fill also leaks
# into the (white) disc interior through white cells — harmless, it is white
# already. Saturated artwork pixels stay outside the threshold.
for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
    ImageDraw.floodfill(src, seed, (255, 255, 255), thresh=70)

# Bounding box of the artwork: anything still visibly non-white.
px = src.load()
xs, ys = [], []
for y in range(h):
    for x in range(w):
        r, g, b = px[x, y]
        if r < 235 or g < 235 or b < 235:
            xs.append(x)
            ys.append(y)
art = src.crop((min(xs), min(ys), max(xs) + 1, max(ys) + 1))

def artwork_on_white(scale):
    """The artwork at `scale`, centered on a white square."""
    scaled = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    square = Image.new("RGB", (SIZE, SIZE), (255, 255, 255))
    square.paste(scaled, ((SIZE - scaled.width) // 2, (SIZE - scaled.height) // 2))
    return square


# Adaptive foreground layer: opaque white, the launcher masks it to its own shape.
artwork_on_white(FG_FRACTION * SIZE / max(art.size)).save(TARGET_FOREGROUND)
print(f"Saved {TARGET_FOREGROUND}")

# Legacy icon: the same artwork on a full-bleed white disc with transparent corners.
icon = artwork_on_white(ART_FRACTION * SIZE / max(art.size))
mask = Image.new("L", (SIZE * SUPER, SIZE * SUPER), 0)
ImageDraw.Draw(mask).ellipse([0, 0, SIZE * SUPER - 1, SIZE * SUPER - 1], fill=255)
mask = mask.resize((SIZE, SIZE), Image.LANCZOS)
icon.putalpha(mask)
icon.save(TARGET_LEGACY)
print(f"Saved {TARGET_LEGACY}")
