#!/usr/bin/env python3
"""Render a placeholder BikeTracker launcher icon: green background, white bike silhouette."""
from PIL import Image, ImageDraw

GREEN = (0x2E, 0x7D, 0x32, 255)
WHITE = (255, 255, 255, 255)

BASE = 432
S = 4
N = BASE * S

img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(img)


def sc(v):
    return v * S


d.rectangle([0, 0, N, N], fill=GREEN)

stroke = sc(14)

# Two wheels
r = sc(70)
left_c = (sc(140), sc(300))
right_c = (sc(300), sc(300))
for c in (left_c, right_c):
    d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], outline=WHITE, width=stroke)

# Frame (simplified bike triangle shapes)
seat = (sc(190), sc(150))
pedal = (sc(190), sc(300))
handlebar = (sc(280), sc(150))

lines = [
    (left_c, seat),
    (seat, pedal),
    (pedal, left_c),
    (pedal, right_c),
    (pedal, handlebar),
    (handlebar, right_c),
    (seat, handlebar),
]
for a, b in lines:
    d.line([a, b], fill=WHITE, width=stroke)

# Seat and handlebar caps
d.ellipse([seat[0] - sc(10), seat[1] - sc(10), seat[0] + sc(10), seat[1] + sc(10)], fill=WHITE)
d.ellipse(
    [handlebar[0] - sc(10), handlebar[1] - sc(10), handlebar[0] + sc(10), handlebar[1] + sc(10)],
    fill=WHITE,
)

img = img.resize((BASE, BASE), Image.LANCZOS)
img.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
print("Saved icon")
