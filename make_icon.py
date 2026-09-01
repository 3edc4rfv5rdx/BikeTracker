#!/usr/bin/env python3
"""Build the launcher icons from the chosen artwork in ADD/pict/ok/icon.png.

The source is a two-tone silhouette: white artwork on a black field. Its
luminance is turned straight into the alpha channel (with a ramp that keeps the
anti-aliased edges but flattens the compression noise in the black field), so
the artwork comes out as pure white on transparency and the launcher's blue
background layer shows through. Two outputs: the adaptive-icon foreground layer
(transparent, artwork sized to the launcher's visible safe zone) and a legacy
full-bleed blue disc for the pre-adaptive icon slot.

Both fit the artwork by its smallest enclosing circle rather than its bounding
box: every launcher mask, and the legacy disc, is round or nearly so, and a
box fit lets the corners of a near-square drawing — here the wheel rims — run
outside the visible area.
"""
from PIL import Image, ImageDraw

SOURCE = "ADD/pict/ok/icon.png"
TARGET_LEGACY = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
TARGET_FOREGROUND = "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"

SIZE = 432          # xxxhdpi launcher icon, 108dp * 4
SUPER = 4           # supersampling factor for the disc's antialiased edge
BACKGROUND = (0x01, 0x38, 0x95)  # ic_launcher_background, shared across the apps
# Luminance window mapped onto alpha: below LO is field, above HI is artwork.
ALPHA_LO, ALPHA_HI = 48, 190
# Share of the icon taken by the artwork's enclosing circle. The launcher masks
# off the outer 18 of 108dp, so 0.667 would touch a round mask exactly; back off
# a little for breathing room. The legacy disc has no mask and can run fuller.
FG_FRACTION = 0.63
ART_FRACTION = 0.80

src = Image.open(SOURCE).convert("L")

# Luminance -> alpha, clamped through the ramp so the field is fully transparent
# and the strokes fully opaque, with only the true edge pixels in between.
alpha = src.point(lambda v: 0 if v <= ALPHA_LO else 255 if v >= ALPHA_HI
                  else round((v - ALPHA_LO) * 255 / (ALPHA_HI - ALPHA_LO)))
alpha = alpha.crop(alpha.getbbox())
art = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
art.putalpha(alpha)

# Per row, only the outermost opaque pixels can be the farthest from any centre.
px = alpha.load()
extremes = []
for y in range(alpha.height):
    xs = [x for x in range(alpha.width) if px[x, y] > 128]
    if xs:
        extremes.append((y, xs[0], xs[-1]))


def enclosing_radius(cx, cy):
    return max(max((x0 - cx) ** 2, (x1 - cx) ** 2) + (y - cy) ** 2
               for y, x0, x1 in extremes) ** 0.5


# Smallest enclosing circle, by pattern search from the bounding box centre.
centre = ((alpha.width - 1) / 2, (alpha.height - 1) / 2)
radius = enclosing_radius(*centre)
step = max(alpha.size) / 16
while step >= 0.5:
    moved = True
    while moved:
        moved = False
        for dx, dy in ((step, 0), (-step, 0), (0, step), (0, -step)):
            probe = (centre[0] + dx, centre[1] + dy)
            r = enclosing_radius(*probe)
            if r < radius:
                centre, radius, moved = probe, r, True
    step /= 2


def artwork_on(background, fraction):
    """The artwork, its enclosing circle sized to `fraction` of the icon and
    concentric with it, over a square of `background`."""
    scale = fraction * SIZE / 2 / radius
    scaled = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    square = Image.new("RGBA", (SIZE, SIZE), background)
    square.alpha_composite(scaled, (round(SIZE / 2 - centre[0] * scale),
                                    round(SIZE / 2 - centre[1] * scale)))
    return square


# Adaptive foreground layer: transparent, the background layer paints the blue.
# Doubles as the monochrome layer, which the system tints by this same alpha.
artwork_on((0, 0, 0, 0), FG_FRACTION).save(TARGET_FOREGROUND)
print(f"Saved {TARGET_FOREGROUND}")

# Legacy icon: the same artwork on a full-bleed blue disc with transparent corners.
icon = artwork_on(BACKGROUND + (255,), ART_FRACTION)
mask = Image.new("L", (SIZE * SUPER, SIZE * SUPER), 0)
ImageDraw.Draw(mask).ellipse([0, 0, SIZE * SUPER - 1, SIZE * SUPER - 1], fill=255)
icon.putalpha(mask.resize((SIZE, SIZE), Image.LANCZOS))
icon.save(TARGET_LEGACY)
print(f"Saved {TARGET_LEGACY}")
