"""Builds app icons + in-app logo from the master logo PNG (tools/brand/phase-logo.png)."""
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools/brand/phase-logo.png"
RES = ROOT / "android/app/src/main/res"
im = Image.open(SRC).convert("RGBA")
a = np.asarray(im).astype(np.float32)

# 1) Glyphs (bars + PHASE) as white-on-transparent: alpha from luminance so the glow survives.
lum = a[..., :3].max(-1)
inner = np.zeros_like(lum, dtype=bool)
inner[200:1060, 200:1060] = True  # inside the rounded tile, away from its rim
alpha = np.where(inner, np.clip((lum - 18) / (255 - 18), 0, 1), 0)
rgb = np.where(alpha[..., None] > 0, a[..., :3] / np.maximum(alpha[..., None], 1e-3), 0)
glyph = np.dstack([np.clip(rgb, 0, 255), alpha * 255]).astype(np.uint8)
glyph = Image.fromarray(glyph, "RGBA").crop((270, 300, 1000, 930))  # bars + wordmark with glow margin

# In-app logo (Home header), generous resolution.
(RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
glyph.resize((glyph.width // 2, glyph.height // 2), Image.LANCZOS).save(RES / "drawable-nodpi/logo_phase.png")

# 2) Adaptive icon foreground: 108dp canvas, content inside the 66dp safe zone.
for d, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    box = int(px * 0.56)
    g = glyph.copy()
    g.thumbnail((box, box), Image.LANCZOS)
    fg.alpha_composite(g, ((px - g.width) // 2, (px - g.height) // 2))
    out = RES / f"mipmap-{d}"
    out.mkdir(parents=True, exist_ok=True)
    fg.save(out / "ic_launcher_foreground.png")
    # Legacy square icon (pre-adaptive launchers / some stores): tile from the source.
    tile = im.crop((107, 123, 1146, 1117)).resize((px * 48 // 108, px * 48 // 108), Image.LANCZOS)
    tile.save(out / "ic_launcher.png")

# 3) Play Store icon 512x512 (Play applies its own mask): black tile + glyph.
play = Image.new("RGBA", (512, 512), (6, 6, 8, 255))
g = glyph.copy(); g.thumbnail((360, 360), Image.LANCZOS)
play.alpha_composite(g, ((512 - g.width) // 2, (512 - g.height) // 2))
play.convert("RGB").save(ROOT / "docs/play-store/icon-512.png")
print("ok", glyph.size)
