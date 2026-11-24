"""Draws the app icon (original artwork) at 1024 px, plus icon.png for the window and app.ico for Windows."""
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

S = 1024
root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

# background gradient, diagonal: indigo -> coral
top, bottom = (58, 44, 168), (255, 112, 92)
bg = Image.new("RGB", (S, S))
px = bg.load()
for y in range(S):
    for x in range(S):
        t = min(1, max(0, (x * 0.35 + y * 0.65) / S))
        px[x, y] = lerp(top, bottom, t)

# macOS-style squircle mask with margin (824 px body in a 1024 canvas)
m = 100; body = S - 2 * m
mask = Image.new("L", (S * 4, S * 4), 0)
d = ImageDraw.Draw(mask)
d.rounded_rectangle([m * 4, m * 4, (S - m) * 4, (S - m) * 4], radius=int(body * 0.225 * 4), fill=255)
mask = mask.resize((S, S), Image.LANCZOS)

icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
shadow.putalpha(mask.point(lambda v: v * 0.35))
shadow = shadow.filter(ImageFilter.GaussianBlur(18))
icon.alpha_composite(shadow, (0, 14))
fg = bg.convert("RGBA"); fg.putalpha(mask)
icon.alpha_composite(fg)

# white artwork drawn at 4x then reduced
A = Image.new("RGBA", (S * 4, S * 4), (0, 0, 0, 0))
g = ImageDraw.Draw(A)
W = (255, 255, 255, 255)
c = S * 2; R = int(S * 0.27 * 4); w = int(S * 0.045 * 4)
# circular "rebuild" arrow: runs clockwise and ends in an arrow head, leaving a gap at the top
a0, a1 = -62, 236
g.arc([c - R, c - R, c + R, c + R], start=a0, end=a1, fill=W, width=w)
th = math.radians(a1)
mx, my = c + (R - w / 2) * math.cos(th), c + (R - w / 2) * math.sin(th)   # middle of the stroke
tx, ty = -math.sin(th), math.cos(th)      # clockwise tangent
nx, ny = math.cos(th), math.sin(th)       # outward normal
hw, hl = w * 1.25, w * 1.7
g.polygon([(mx + nx * hw, my + ny * hw), (mx - nx * hw, my - ny * hw), (mx + tx * hl, my + ty * hl)], fill=W)
# two beamed eighth notes in the middle
u = S * 4 / 1024
hr, vr, sw = 64 * u, 46 * u, 24 * u
def note(cx, cy, top):
    g.ellipse([cx - hr, cy - vr, cx + hr, cy + vr], fill=W)
    g.rectangle([cx + hr - sw, top, cx + hr, cy - 6 * u], fill=W)
n1 = (c - 110 * u, c + 120 * u); n2 = (c + 90 * u, c + 90 * u)
t1, t2 = c - 150 * u, c - 180 * u
note(*n1, t1); note(*n2, t2)
x1 = n1[0] + hr; x2 = n2[0] + hr
g.polygon([(x1 - sw, t1), (x2, t2), (x2, t2 + 58 * u), (x1 - sw, t1 + 58 * u)], fill=W)
A = A.resize((S, S), Image.LANCZOS)
icon.alpha_composite(A)

(root / "packaging").mkdir(parents=True, exist_ok=True)
icon.save(root / "packaging/icon-1024.png")
icon.resize((256, 256), Image.LANCZOS).save(root / "src/main/resources/com/arialbold/xmrb/icon.png")
icon.save(root / "packaging/app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icons written")
