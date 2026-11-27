"""Draws the ClipBridge logo and writes the PNG sizes and the Windows .ico.

Run from the project folder:   python tools/make_logo.py
Needs Pillow (pip install Pillow). The generated files are already included in the project,
so you only run this if you want to change the design.
"""
import os
from PIL import Image, ImageDraw

S = 2048            # draw big, then shrink for smooth edges
OUT_PNG = os.path.join("src", "clipbridge", "gui")
OUT_ICO = os.path.join("assets", "clipbridge.ico")
SIZES = [16, 24, 32, 48, 64, 128, 256]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_logo():
    # background: rounded square with a diagonal teal -> blue gradient
    top, bottom = (20, 184, 166), (37, 99, 235)
    grad = Image.new("RGB", (S, S))
    gp = grad.load()
    for y in range(S):
        for x in range(S):
            gp[x, y] = lerp(top, bottom, (x + y) / (2.0 * S))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((60, 60, S - 60, S - 60), radius=430, fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # clipboard board (white) with a soft shadow
    bx0, by0, bx1, by1 = 520, 440, 1528, 1740
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((bx0 + 30, by0 + 50, bx1 + 30, by1 + 50), radius=120, fill=(0, 0, 0, 70))
    img = Image.alpha_composite(img, shadow)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((bx0, by0, bx1, by1), radius=120, fill=(255, 255, 255, 255))

    # clip at the top
    d.rounded_rectangle((770, 340, 1278, 600), radius=90, fill=(30, 41, 59, 255))
    d.rounded_rectangle((870, 420, 1178, 500), radius=40, fill=(255, 255, 255, 255))

    # text lines
    line = (203, 213, 225, 255)
    for i, w in enumerate((760, 760, 520)):
        y = 760 + i * 190
        d.rounded_rectangle((640, y, 640 + w, y + 80), radius=40, fill=line)

    # exchange badge: two arrows in a circle, bottom right
    cx, cy, r = 1440, 1500, 330
    d.ellipse((cx - r - 36, cy - r - 36, cx + r + 36, cy + r + 36), fill=(255, 255, 255, 255))
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(30, 41, 59, 255))
    white = (255, 255, 255, 255)
    # arrow pointing right (upper)
    d.rounded_rectangle((cx - 190, cy - 120, cx + 90, cy - 56), radius=32, fill=white)
    d.polygon([(cx + 70, cy - 210), (cx + 220, cy - 88), (cx + 70, cy + 34)], fill=white)
    # arrow pointing left (lower)
    d.rounded_rectangle((cx - 90, cy + 56, cx + 190, cy + 120), radius=32, fill=white)
    d.polygon([(cx - 70, cy - 34), (cx - 220, cy + 88), (cx - 70, cy + 210)], fill=white)
    return img


def main():
    os.makedirs(OUT_PNG, exist_ok=True)
    os.makedirs("assets", exist_ok=True)
    big = draw_logo()
    for size in SIZES:
        small = big.resize((size, size), Image.LANCZOS)
        small.save(os.path.join(OUT_PNG, "logo-%d.png" % size))
    big.resize((1024, 1024), Image.LANCZOS).save(os.path.join("assets", "logo-1024.png"))
    big.resize((256, 256), Image.LANCZOS).save(OUT_ICO, sizes=[(s, s) for s in SIZES])
    print("wrote", len(SIZES), "PNG sizes, assets/logo-1024.png and", OUT_ICO)


if __name__ == "__main__":
    main()
