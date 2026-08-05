#!/usr/bin/env python3
"""ساخت آیکون و تصویر بارگذاری (presplash) برنامه اسکنر کریپتو"""
from PIL import Image, ImageDraw, ImageFilter

SIZE = 512
S = 4  # scale for anti-aliasing
img = Image.new("RGBA", (SIZE * S, SIZE * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# پس‌زمینه تیره با گرادیان
bg = Image.new("RGBA", (SIZE * S, SIZE * S))
for y in range(SIZE * S):
    t = y / (SIZE * S)
    r = int(13 + 8 * t)
    g = int(17 + 6 * t)
    b = int(31 + 22 * t)
    for x in range(SIZE * S):
        bg.putpixel((x, y), (r, g, b, 255))

# گوشه‌های گرد
mask = Image.new("L", (SIZE * S, SIZE * S), 0)
md = ImageDraw.Draw(mask)
radius = 96 * S
md.rounded_rectangle([0, 0, SIZE * S - 1, SIZE * S - 1], radius=radius, fill=255)
img = Image.composite(img, bg, mask)
d = ImageDraw.Draw(img)

# خط روند (chart line) از پایین چپ به بالا راست
pts = [(60 * S, 400 * S), (150 * S, 330 * S), (220 * S, 360 * S),
       (300 * S, 250 * S), (380 * S, 280 * S), (452 * S, 130 * S)]
d.line(pts, fill=(56, 189, 248, 255), width=14 * S, joint="curve")

# نقطه انتهایی (فلش/دایره)
d.ellipse([452 * S - 26 * S, 130 * S - 26 * S, 452 * S + 26 * S, 130 * S + 26 * S],
          fill=(56, 189, 248, 255))

# کندل سبز صعودی
cx = 236 * S
d.rectangle([cx - 34 * S, 190 * S, cx + 34 * S, 330 * S], fill=(34, 197, 94, 255))
d.rectangle([cx - 8 * S, 130 * S, cx + 8 * S, 190 * S], fill=(34, 197, 94, 255))
d.rectangle([cx - 8 * S, 330 * S, cx + 8 * S, 385 * S], fill=(34, 197, 94, 255))

# کندل قرمز نزولی
cx2 = 330 * S
d.rectangle([cx2 - 34 * S, 250 * S, cx2 + 34 * S, 360 * S], fill=(239, 68, 68, 255))
d.rectangle([cx2 - 8 * S, 200 * S, cx2 - 8 * S, 250 * S], fill=(239, 68, 68, 255))
d.rectangle([cx2 - 8 * S, 360 * S, cx2 - 8 * S, 410 * S], fill=(239, 68, 68, 255))

# ذره‌بین (اسکنر)
mx, my, mr = 128 * S, 138 * S, 64 * S
d.ellipse([mx - mr, my - mr, mx + mr, my + mr], outline=(255, 255, 255, 235), width=12 * S)
d.line([mx + mr * 0.7, my + mr * 0.7, mx + mr * 1.5, my + mr * 1.5],
       fill=(255, 255, 255, 235), width=14 * S)

# کاهش اندازه با ضدآلیاسینگ
img = img.resize((SIZE, SIZE), Image.LANCZOS)
img.save("/home/user/tapgame/icon.png")

# ---------- presplash ----------
W, H = 720, 1280
ps = Image.new("RGBA", (W, H))
for y in range(H):
    t = y / H
    r = int(10 + 10 * t)
    g = int(13 + 8 * t)
    b = int(26 + 30 * t)
    for x in range(W):
        ps.putpixel((x, y), (r, g, b, 255))
icon_small = img.resize((260, 260), Image.LANCZOS)
ps.paste(icon_small, ((W - 260) // 2, 380), icon_small)
ps.save("/home/user/tapgame/presplash.png")
print("icon.png + presplash.png created")
