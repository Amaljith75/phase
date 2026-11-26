"""Generates Google Play store graphics: 512x512 icon and 1024x500 feature graphic."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/play-store")
OUT.mkdir(parents=True, exist_ok=True)
BG = (11, 11, 14)
STEMS = [(255, 181, 71), (255, 77, 94), (139, 108, 255), (62, 230, 224)]


def font(size, bold=True):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]:
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def instrument(size):
    """Four stem pads around a metal transport disc (the app's main control)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    g = size * 0.045
    pad = (size - g) / 2
    boxes = [(0, 0), (pad + g, 0), (0, pad + g), (pad + g, pad + g)]
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for (x, y), c in zip(boxes, STEMS):
        r = size * 0.12
        gd.rounded_rectangle([x, y, x + pad, y + pad], r, fill=c + (90,))
        d.rounded_rectangle([x, y, x + pad, y + pad], r, fill=(28, 28, 34, 255), outline=c + (255,), width=max(2, size // 90))
        # LED ladder
        for i in range(6):
            lx = x + pad * 0.14 if x == 0 else x + pad * 0.78
            ly = y + pad * 0.16 + i * pad * 0.12
            d.rounded_rectangle([lx, ly, lx + pad * 0.08, ly + pad * 0.08], 3, fill=c + (255 if i > 1 else 70,))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.05))
    img = Image.alpha_composite(glow, img)
    d = ImageDraw.Draw(img)
    cx = cy = size / 2
    R = size * 0.2
    d.ellipse([cx - R, cy - R, cx + R, cy + R], fill=(8, 8, 10, 255), outline=(225, 225, 232, 255), width=max(3, size // 50))
    t = R * 0.42
    d.polygon([(cx - t * 0.6, cy - t), (cx - t * 0.6, cy + t), (cx + t, cy)], fill=(237, 237, 242, 255))
    return img


icon = Image.new("RGBA", (512, 512), BG + (255,))
inst = instrument(400)
icon.alpha_composite(inst, (56, 56))
icon.convert("RGB").save(OUT / "icon-512.png")

fg = Image.new("RGBA", (1024, 500), BG + (255,))
d = ImageDraw.Draw(fg)
for i in range(500):
    d.line([(0, i), (1024, i)], fill=(17 + i // 60, 17 + i // 60, 22 + i // 50, 255))
fg.alpha_composite(instrument(380), (590, 60))
d = ImageDraw.Draw(fg)
d.text((60, 140), "PHASE", font=font(110), fill=(237, 237, 242))
d.text((64, 270), "Split any song into stems.", font=font(34, False), fill=(185, 185, 196))
d.text((64, 318), "Remix it with your fingers.", font=font(34, False), fill=(185, 185, 196))
d.text((64, 392), "ON-DEVICE AI  ·  OFFLINE", font=font(22), fill=STEMS[0])
fg.convert("RGB").save(OUT / "feature-graphic-1024x500.png")
print("wrote", list(OUT.iterdir()))
