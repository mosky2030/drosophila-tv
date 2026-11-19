# -*- coding: utf-8 -*-
"""从分层 PSD 还原可用的果蝇精灵：镜像补全右侧腿/翅，输出 64×64 精灵与预览。

用法:
  python tools/psd_build.py <psd> <outdir> [--sprite 64] [--content-h 62]

图层约定（用户 2026-11-19 的 psd）:
  '图层 3 拷贝 3' = 头    '图层 3 拷贝 2' = 腹部   '图层 1' = 左翅
  '图层 5' '图层 8' '图层 7' = 左侧前/中/后腿
  最大的那层（'图层 4'）= 背景，丢弃
"""
import sys, os, math
import numpy as np
from PIL import Image, ImageOps, ImageFilter
from psd_tools import PSDImage

psd_path, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)

SPRITE = 64          # 画布
CONTENT_H = 60       # 果蝇在画布里的高度

psd = PSDImage.open(psd_path)
W, H = psd.width, psd.height

# ---- 1. 取层 ----
layers = {}
raw = {}
for l in psd:
    im = l.composite(force=True)
    if im is None:
        continue
    full = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    full.paste(im.convert("RGBA"), (l.bbox[0], l.bbox[1]))
    raw[l.name] = (l.bbox, full)

areas = {n: (b[2]-b[0])*(b[3]-b[1]) for n, (b, _) in raw.items()}
bg_name = max(areas, key=areas.get)
print("丢弃背景层:", bg_name, areas[bg_name])

f = lambda s: np.array(raw[s][1], dtype=np.float32)

head = f("图层 3 拷贝 3")
abd = f("图层 3 拷贝 2")
legs = [f("图层 5"), f("图层 8"), f("图层 7")]      # 前 / 中 / 后
wing = f("图层 1")

# ---- 2. 镜像轴：头+腹的并集 bbox 中心 ----
bm = (head[:, :, 3] > 0) | (abd[:, :, 3] > 0)
ys, xs = np.nonzero(bm)
cx = (xs.min() + xs.max() + 1) / 2.0
print(f"镜像轴 x = {cx:.1f}  （头腹并集 {xs.min()}..{xs.max()}）")

def mirror(arr, axis=cx):
    flip = arr[:, ::-1, :]
    shift = int(round(2 * axis - (W - 1)))
    return np.roll(flip, shift, axis=1)

head_r, abd_r = mirror(head), mirror(abd)
wing_r = mirror(wing)
legs_r = [mirror(l) for l in legs]

def over(dst, src):
    a = src[:, :, 3:4] / 255.0
    out = dst.copy()
    out[:, :, :3] = src[:, :, :3] * a + dst[:, :, :3] * (1 - a)
    out[:, :, 3:4] = np.clip(dst[:, :, 3:4] + src[:, :, 3:4] - dst[:, :, 3:4] * a, 0, 255)
    return out

# 层级（底→顶）：翅 → 头 → 腹 → 腿（与原 PSD 一致，腿压在身体上）
canvas = np.zeros((H, W, 4), np.float32)
for part in [wing_r, wing, head_r, head, abd_r, abd] + legs_r + legs:
    canvas = over(canvas, part)

Image.fromarray(canvas.astype(np.uint8), "RGBA").save(os.path.join(outdir, "fly_full_1440.png"))

# ---- 3. 裁到内容并按 content 高度降采样 ----
a = canvas[:, :, 3]
ys, xs = np.nonzero(a > 8)
x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
print("内容 bbox", (x0, y0, x1, y1), "尺寸", (x1 - x0, y1 - y0))
crop = canvas[y0:y1, x0:x1]

scale = CONTENT_H / (y1 - y0)
w_new = max(1, int(round((x1 - x0) * scale)))
h_new = CONTENT_H
print(f"降采样 {x1-x0}×{y1-y0} -> {w_new}×{h_new}  (scale {scale:.4f})")

sm = Image.fromarray(crop.astype(np.uint8), "RGBA").resize((w_new, h_new), Image.LANCZOS)
sm.save(os.path.join(outdir, "fly_content_64.png"))

# 贴到画布（水平居中，底部留 1px）
sprite = Image.new("RGBA", (SPRITE, SPRITE), (0, 0, 0, 0))
ox = (SPRITE - w_new) // 2
oy = SPRITE - 2 - h_new
sprite.alpha_composite(sm, (ox, oy))
sprite.save(os.path.join(outdir, "fly_sprite_64.png"))
print("sprite paste at", (ox, oy))

S = 8
for name, s in [("fly_sprite_64_x8", sprite)]:
    bg = Image.new("RGBA", s.size, (22, 24, 28, 255))
    bg.alpha_composite(s)
    bg.resize((s.width * S, s.height * S), Image.NEAREST).save(os.path.join(outdir, name + ".png"))

# 白底 + 深底 两版对照
for tag, col in [("white", (255, 255, 255, 255)), ("dark", (16, 18, 22, 255))]:
    bg = Image.new("RGBA", sprite.size, col)
    bg.alpha_composite(sprite)
    bg.resize((sprite.width * 6, sprite.height * 6), Image.NEAREST).save(os.path.join(outdir, f"fly_sprite_64_on_{tag}.png"))
print("done")
