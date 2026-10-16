"""Phone mockups for README from tools/shots.sh output.

Usage: python tools/mockups.py [SHOTS_DIR] [OUT_DIR]
Writes: hero.png, themes.png, zoom.png and downscaled raw shots to OUT_DIR/screens/.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools", "out", "shots")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "docs", "images")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font")

THEMES = [("dusk", "Dusk"), ("cartoon", "Cartoon"), ("paper", "Paper"), ("plum", "Plum"),
          ("ink", "Ink"), ("chalk", "Chalk"), ("classic", "Classic")]
VIEWS = [("full", "Year"), ("rise", "Season"), ("month", "Month"), ("days", "Days")]


def font(name, size):
    return ImageFont.truetype(os.path.join(FONT, name), size)


def shot(theme, view):
    return Image.open(os.path.join(SHOTS, f"{theme}_{view}.png")).convert("RGB")


def rounded_mask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), r, fill=255)
    return m


def phone(screen, width):
    """Plain phone frame: thin dark bezel, rounded corners, soft shadow. The screenshot is untouched."""
    sw = width
    sh = round(screen.height * sw / screen.width)
    scr = screen.resize((sw, sh), Image.LANCZOS)
    bez = round(sw * 0.035)
    W, H = sw + 2 * bez, sh + 2 * bez
    R = round(sw * 0.13)
    pad = round(sw * 0.12)  # room for the shadow
    img = Image.new("RGBA", (W + 2 * pad, H + 2 * pad), (0, 0, 0, 0))

    sh_mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(sh_mask).rounded_rectangle((pad, pad + bez, pad + W, pad + H + bez), R, fill=110)
    sh_mask = sh_mask.filter(ImageFilter.GaussianBlur(pad * 0.45))
    img.paste((0, 0, 0, 255), (0, 0), sh_mask)

    body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, W - 1, H - 1), R, fill=(24, 24, 28, 255))
    d.rounded_rectangle((1, 1, W - 2, H - 2), R - 1, outline=(70, 70, 78, 255), width=max(2, sw // 300))
    body.paste(scr, (bez, bez), rounded_mask((sw, sh), R - bez))
    img.alpha_composite(body, (pad, pad))
    return img, pad


def gradient(size, top, bottom):
    g = Image.new("RGB", (1, 256))
    for y in range(256):
        t = y / 255
        g.putpixel((0, y), tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g.resize(size, Image.BICUBIC).convert("RGBA")


def text_center(img, y, s, f, fill):
    d = ImageDraw.Draw(img)
    w = d.textlength(s, font=f)
    d.text(((img.width - w) / 2, y), s, font=f, fill=fill)


def hero():
    W, H = 2400, 1500
    img = gradient((W, H), (32, 20, 66), (12, 8, 30))
    text_center(img, 70, "Circular Calendar", font("unbounded.ttf", 96), (246, 240, 255))
    text_center(img, 200, "Your whole year as one circle. Zoom from months to seconds.",
                font("nunito.ttf", 46), (200, 186, 230))
    # Symmetric: light themes outside, dark inside, Dusk in the centre.
    picks = [("paper", "days", 380), ("plum", "diag", 430), ("dusk", "rise", 500),
             ("ink", "side", 430), ("chalk", "days", 380)]
    xs = [400, 790, 1200, 1610, 2000]
    # Layered: outer phones first, the centre one on top.
    for k in (0, 4, 1, 3, 2):
        (t, v, w), cx = picks[k], xs[k]
        p, pad = phone(shot(t, v), w)
        cy = 330 + (500 - w) + round(w * 1.15)
        img.alpha_composite(p, (cx - p.width // 2, cy - p.height // 2))
    return img.convert("RGB")


def grid(items, cols, title=None, w=420):
    cells = [(phone(shot(t, v), w), label) for t, v, label in items]
    (p0, pad), _ = cells[0]
    cw, ch = p0.width - pad, p0.height - pad
    rows = (len(cells) + cols - 1) // cols
    top = 170 if title else 90
    W = cols * cw + pad
    H = top + rows * (ch + (90 if title else 30)) + 20
    img = gradient((W, H), (245, 241, 234), (228, 222, 210))
    if title:
        text_center(img, 50, title, font("unbounded.ttf", 64), (40, 24, 50))
    lf = font("nunito.ttf", 44)
    for i, ((p, pad), label) in enumerate(cells):
        r, c = divmod(i, cols)
        n = min(cols, len(cells) - r * cols)
        x0 = (W - n * cw - pad) // 2
        x, y = x0 + c * cw, top + r * (ch + (90 if title else 30))
        img.alpha_composite(p, (x, y - pad // 2))
        if not label:
            continue
        d = ImageDraw.Draw(img)
        tw = d.textlength(label, font=lf)
        d.text((x + pad + (cw - pad - tw) / 2, y + ch - pad // 2 + 25), label, font=lf, fill=(60, 44, 70))
    return img.convert("RGB")


def rotate_strip():
    """The same view turned step by step, with arrows: shows the circle can be rotated."""
    w = 420
    cells = [phone(shot("dusk", f"rot{i}"), w) for i in range(3)]
    p0, pad = cells[0]
    gap = 170
    W = 3 * (p0.width - 2 * pad) + 2 * gap + 2 * pad
    H = p0.height + 170
    img = gradient((W, H), (32, 20, 66), (12, 8, 30))
    text_center(img, 50, "Turn it with two fingers", font("unbounded.ttf", 64), (246, 240, 255))
    x = 0
    for i, (p, _) in enumerate(cells):
        img.alpha_composite(p, (x, 150))
        if i < 2:
            ax = x + p.width - pad + gap // 2
            ay = 150 + p.height // 2
            d = ImageDraw.Draw(img)
            r = 52
            d.arc((ax - r, ay - r, ax + r, ay + r), 200, 520, fill=(255, 196, 92), width=10)
            d.polygon([(ax + r - 22, ay - 6), (ax + r + 22, ay - 6), (ax + r, ay + 26)], fill=(255, 196, 92))
        x += p.width - 2 * pad + gap
    return img.convert("RGB")


def main():
    os.makedirs(os.path.join(OUT, "screens"), exist_ok=True)
    hero().save(os.path.join(OUT, "hero.png"), optimize=True)
    grid([("plum", "side", ""), ("paper", "diag", ""), ("cartoon", "close", ""),
          ("chalk", "near", "")], 4).save(os.path.join(OUT, "angles.png"), optimize=True)
    for f in sorted(os.listdir(SHOTS)):
        if f.endswith(".png"):
            im = Image.open(os.path.join(SHOTS, f)).convert("RGB")
            im.resize((540, round(im.height * 540 / im.width)), Image.LANCZOS).save(
                os.path.join(OUT, "screens", f.replace(".png", ".jpg")), quality=88)
    print("ok", OUT)


if __name__ == "__main__":
    main()
