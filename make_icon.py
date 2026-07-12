#!/usr/bin/env python3
"""Build the launcher icon from the chosen artwork in ADD/pict/ok/icon.png.

The AI-generated source has a fake-transparency checkerboard baked into the
pixels and no crisp circle edge, so the icon is rebuilt instead of cropped:
the checkerboard (and every light neutral area connected to the border) is
flooded to pure white, the artwork is located by its saturated pixels, and
the result is a clean white disc with the artwork centered on it.
"""
from PIL import Image, ImageDraw

SOURCE = "ADD/pict/ok/icon.png"
TARGET = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

SIZE = 432          # xxxhdpi launcher icon, 108dp * 4
SUPER = 4           # supersampling factor for the disc's antialiased edge
ART_FRACTION = 0.80 # artwork's share of the disc diameter

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

# Scale the artwork to its share of the disc and center it on a white square.
scale = ART_FRACTION * SIZE / max(art.size)
art = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
icon = Image.new("RGB", (SIZE, SIZE), (255, 255, 255))
icon.paste(art, ((SIZE - art.width) // 2, (SIZE - art.height) // 2))

# Cut the full-bleed disc with a supersampled circular mask.
mask = Image.new("L", (SIZE * SUPER, SIZE * SUPER), 0)
ImageDraw.Draw(mask).ellipse([0, 0, SIZE * SUPER - 1, SIZE * SUPER - 1], fill=255)
mask = mask.resize((SIZE, SIZE), Image.LANCZOS)
icon.putalpha(mask)

icon.save(TARGET)
print(f"Saved {TARGET}")
