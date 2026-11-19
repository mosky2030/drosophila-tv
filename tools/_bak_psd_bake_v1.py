# -*- coding: utf-8 -*-
"""把分层 PSD 烘成多帧 64×64 精灵（翅折叠 + 腿部微动）。

关键约束（沿用 2026-11-18 的教训）：右半边永远由左半边**逐像素镜像**得到，
不做第二次旋转，否则两侧落在不同像素网格上、越折越歪。

用法: python tools/psd_bake.py <psd> <outdir>
输出: <outdir>/frames/psd_frame0..N.png  +  review_strip.png
"""
import sys, os, math
import numpy as np
from PIL import Image, ImageFont, ImageDraw
from psd_tools import PSDImage

psd_path, outdir = sys.argv[1], sys.argv[2]
SPRITE, CONTENT_H = 64, 60

psd = PSDImage.open(psd_path)
W, H = psd.width, psd.height

raw = {}
for l in psd:
    im = l.composite(force=True)
    if im is None:
        continue
    full = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    full.paste(im.convert("RGBA"), (l.bbox[0], l.bbox[1]))
    raw[l.name] = (l.bbox, full)

areas = {n: (b[2]-b[0])*(b[3]-b[1]) for n, (b, _) in raw.items()}
raw.pop(max(areas, key=areas.get))          # 丢掉红色背景层

arr = {n: np.array(v[1], dtype=np.float32) for n, v in raw.items()}
head, abd, wing = arr["图层 3 拷贝 3"], arr["图层 3 拷贝 2"], arr["图层 1"]
legs = [arr["图层 5"], arr["图层 8"], arr["图层 7"]]     # 前 / 中 / 后（左）

bm = (head[:, :, 3] > 0) | (abd[:, :, 3] > 0)
xs = np.nonzero(bm)[1]
CX = (xs.min() + xs.max() + 1) / 2.0


def rot(a, pivot, theta):
    """把图层绕 pivot 旋转 theta（图像坐标系，y 向下，正角度=视觉顺时针）。"""
    if theta == 0:
        return a
    px, py = pivot
    c, s = math.cos(theta), math.sin(theta)
    m = (c, s, px - c * px - s * py,
         -s, c, py + s * px - c * py)
    img = Image.fromarray(a.astype(np.uint8), "RGBA").transform(
        (W, H), Image.AFFINE, m, resample=Image.BICUBIC)
    return np.array(img, dtype=np.float32)


def mirror(a):
    return np.roll(a[:, ::-1, :], int(round(2 * CX - (W - 1))), axis=1)


def over(dst, src):
    al = src[:, :, 3:4] / 255.0
    out = dst.copy()
    out[:, :, :3] = src[:, :, :3] * al + dst[:, :, :3] * (1 - al)
    out[:, :, 3:4] = np.clip(dst[:, :, 3:4] + src[:, :, 3:4] - dst[:, :, 3:4] * al, 0, 255)
    return out


def root_of(layer, side="left"):
    """图层靠身体那一端的支点：left 侧取 x 最大处。"""
    ys, xs = np.nonzero(layer[:, :, 3] > 8)
    xmax = xs.max()
    sel = ys[xs >= xmax - 2]
    return (float(xmax), float(np.median(sel)))


WING_PIVOT = root_of(wing)
LEG_PIVOTS = [root_of(l) for l in legs]
print("翅根", WING_PIVOT, "腿根", LEG_PIVOTS)

# 参考几何：翅根 -> 翅尖 的方向角（用于判断折向）
ys, xs = np.nonzero(wing[:, :, 3] > 8)
d = np.hypot(xs - WING_PIVOT[0], ys - WING_PIVOT[1])
tip = (float(xs[np.argmax(d)]), float(ys[np.argmax(d)]))
print("翅尖", tip, "方向角 %.1f°" % math.degrees(math.atan2(tip[1]-WING_PIVOT[1], tip[0]-WING_PIVOT[0])))


def compose(wing_theta=0.0, leg_thetas=(0.0, 0.0, 0.0)):
    lw = wing if wing_theta == 0 else rot(wing, WING_PIVOT, wing_theta)
    c = np.zeros((H, W, 4), np.float32)
    for part in [mirror(lw), lw, mirror(head), head, mirror(abd), abd]:
        c = over(c, part)
    for i, l in enumerate(legs):
        ll = l if leg_thetas[i] == 0 else rot(l, LEG_PIVOTS[i], leg_thetas[i])
        c = over(c, mirror(ll))
        c = over(c, ll)
    return c


def crop_box(comps):
    """所有帧共用一个裁剪窗口——否则每帧按自己的内容 bbox 裁剪会让整只果蝇在帧间跳动。"""
    x0 = y0 = 10**9; x1 = y1 = -1
    for c in comps:
        ys, xs = np.nonzero(c[:, :, 3] > 8)
        x0 = min(x0, xs.min()); x1 = max(x1, xs.max() + 1)
        y0 = min(y0, ys.min()); y1 = max(y1, ys.max() + 1)
    return int(x0), int(y0), int(x1), int(y1)


def to_sprite(c, box):
    x0, y0, x1, y1 = box
    aw, ah = x1 - x0, y1 - y0
    sc = CONTENT_H / ah
    wn = max(1, int(round(aw * sc)))
    im = Image.fromarray(c[y0:y1, x0:x1].astype(np.uint8), "RGBA").resize((wn, CONTENT_H), Image.LANCZOS)
    sp = Image.new("RGBA", (SPRITE, SPRITE), (0, 0, 0, 0))
    sp.alpha_composite(im, ((SPRITE - wn)//2, SPRITE - 2 - CONTENT_H))
    return sp


# 帧表：0 静止 / 1 抬腿（走路时交替）/ 2..4 收翅
FRAMES = [
    dict(name="rest",    wing=0.0,             legs=(0, 0, 0)),
    dict(name="step",    wing=0.0,             legs=(math.radians(15), math.radians(-11), math.radians(12))),
    dict(name="fold1",   wing=math.radians(-11), legs=(0, 0, 0)),
    dict(name="fold2",   wing=math.radians(-23), legs=(0, 0, 0)),
    dict(name="fold3",   wing=math.radians(-34), legs=(0, 0, 0)),
]

fdir = os.path.join(outdir, "frames")
os.makedirs(fdir, exist_ok=True)
comps = [compose(fr["wing"], fr["legs"]) for fr in FRAMES]
box = crop_box(comps)
print("公共裁剪窗口", box, "=> 内容", (box[2]-box[0], box[3]-box[1]))
imgs = []
for i, fr in enumerate(FRAMES):
    sp = to_sprite(comps[i], box)
    sp.save(os.path.join(fdir, f"psd_frame{i}.png"))
    imgs.append(sp)
    print("frame", i, fr["name"], "->", os.path.join(fdir, f"psd_frame{i}.png"))

# 审图条：6 倍并排
S = 6
strip = Image.new("RGBA", (len(imgs)*SPRITE*S + (len(imgs)+1)*8, SPRITE*S + 16*S), (22, 24, 28, 255))
for i, sp in enumerate(imgs):
    big = sp.resize((SPRITE*S, SPRITE*S), Image.NEAREST)
    strip.alpha_composite(big, (8 + i*(SPRITE*S+8), 8*S))
strip.save(os.path.join(outdir, "review_strip.png"))

# TV 尺寸实拍感：1:1 大小并排（18/32/56 px）
row = Image.new("RGBA", (400, 90), (28, 31, 38, 255))
x = 12
for px in (18, 32, 56):
    im = imgs[0].resize((px, px), Image.LANCZOS)
    row.alpha_composite(im, (x, 80 - px)); x += px + 24
row.resize((row.width*3, row.height*3), Image.NEAREST).save(os.path.join(outdir, "tv_sizes.png"))
print("done")
