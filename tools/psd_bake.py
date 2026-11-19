# -*- coding: utf-8 -*-
"""分层 PSD → 64×64 多帧精灵（v2：六条腿独立摆动 + 头部轻微左右摆 + 收翅）。

帧索引布局（app 与 web 演示必须一致）：
    0..2    IDLE  静止（头 -3.5° / 0° / +3.5°，做慢速摆头）
    3..10   WALK  八分之一步态（三脚架 gait：左前/右中/左后 与 右前/左中/右后 反相）
    11..13  BURST 收翅 -11° / -23° / -34°

两条原则：
1) 右半边只在**静止姿态**做逐像素镜像（x → 2cx - x），之后左右各自独立旋转，
   否则六条腿只能同向摆，做不出交替步态。
2) 所有帧共用一个裁剪窗口，否则整只果蝇会在帧间跳动。

用法: python tools/psd_bake.py <psd> <outdir>
"""
import sys, os, math, json
import numpy as np
from PIL import Image
from psd_tools import PSDImage

psd_path, outdir = sys.argv[1], sys.argv[2]
SPRITE, CONTENT_H = 64, 60

HEAD_MAX_DEG = 3.5
LEG_AMP_DEG = {"front": 16.0, "mid": 12.0, "hind": 18.0}
WING_FOLD_DEG = [-11.0, -23.0, -34.0]
WALK_STEPS = 8

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
raw.pop(max(areas, key=areas.get))          # 纯红背景层

arr = {n: np.array(v[1], dtype=np.float32) for n, v in raw.items()}
head, abd, wing = arr["图层 3 拷贝 3"], arr["图层 3 拷贝 2"], arr["图层 1"]
legsL = [arr["图层 5"], arr["图层 8"], arr["图层 7"]]       # 前 / 中 / 后（左）

bm = (head[:, :, 3] > 0) | (abd[:, :, 3] > 0)
xs = np.nonzero(bm)[1]
CX = (xs.min() + xs.max() + 1) / 2.0


# ---------- 基本操作 ----------
def crop_of(a, thresh=1):
    ys, xs = np.nonzero(a[:, :, 3] > thresh)
    return a[ys.min():ys.max()+1, xs.min():xs.max()+1], int(xs.min()), int(ys.min())


def cropped(a, name):
    c, x0, y0 = crop_of(a)
    return dict(c=c, x0=x0, y0=y0, w=c.shape[1], h=c.shape[0], name=name)


def mirror(p):
    """绕 x=CX 镜像一块裁剪，返回新块与新 x0。"""
    return dict(c=p["c"][:, ::-1], x0=int(round(2*CX - p["x0"] - p["w"] + 1)),
                y0=p["y0"], w=p["w"], h=p["h"], name=p["name"] + "_R")


def rot(p, pivot, theta_deg):
    """绕全局点 pivot 旋转，theta_deg 正 = 视觉顺时针（图像坐标 y 向下）。"""
    if theta_deg == 0:
        return p["c"]
    t = math.radians(theta_deg)
    co, si = math.cos(t), math.sin(t)
    px, py = pivot[0] - p["x0"], pivot[1] - p["y0"]
    m = (co, si, px - co*px - si*py,
         -si, co, py + si*px - co*py)
    img = Image.fromarray(p["c"].astype(np.uint8), "RGBA").transform(
        (p["w"], p["h"]), Image.AFFINE, m, resample=Image.BICUBIC)
    return np.array(img, dtype=np.float32)


def over(canvas, sub, x0, y0):
    h, w = sub.shape[:2]
    x0c, y0c = max(0, x0), max(0, y0)
    x1c, y1c = min(W, x0+w), min(H, y0+h)
    if x1c <= x0c or y1c <= y0c:
        return
    s = sub[y0c-y0:y1c-y0, x0c-x0:x1c-x0]
    d = canvas[y0c:y1c, x0c:x1c]
    a = s[:, :, 3:4] / 255.0
    d[:, :, :3] = s[:, :, :3]*a + d[:, :, :3]*(1-a)
    d[:, :, 3:4] = np.clip(d[:, :, 3:4] + s[:, :, 3:4] - d[:, :, 3:4]*a, 0, 255)
    canvas[y0c:y1c, x0c:x1c] = d


def root_point(a, side="L"):
    ys, xs = np.nonzero(a[:, :, 3] > 8)
    xm = xs.max() if side == "L" else xs.min()
    return (float(xm), float(np.median(ys[xs >= xm-2] if side == "L" else ys[xs <= xm+2])))


# ---------- 拆件 ----------
P = {}
P["headL"] = cropped(head, "headL"); P["headR"] = mirror(P["headL"])
P["wingL"] = cropped(wing, "wingL"); P["wingR"] = mirror(P["wingL"])
abdL = cropped(abd, "abdL"); abdR = mirror(abdL)


def feather_root(p, at_right, frac=0.16):
    """翅根那截是平口（作者原来把它压在身体底下，所以没画收边）。翅现在画在身体之上，
    平口会露在胸背上，所以让翅根一段 alpha 渐隐，读起来像翅膀从胸背里长出来。"""
    c = p["c"].copy()
    w = c.shape[1]
    n = max(1, int(w * frac))
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32) ** 0.75
    if at_right:
        c[:, w-n:, 3] *= ramp[None, :]
    else:
        c[:, :n, 3] *= ramp[::-1][None, :]
    p["c"] = c
    return p


feather_root(P["wingL"], at_right=True)
feather_root(P["wingR"], at_right=False)

LEGS = {}
for nm, a in zip(("front", "mid", "hind"), legsL):
    p = cropped(a, "leg" + nm + "L"); p["pivot"] = root_point(a)
    q = mirror(p); q["pivot"] = (2*CX - p["pivot"][0], p["pivot"][1])
    LEGS[(nm, "L")] = p
    LEGS[(nm, "R")] = q

wYs, wXs = np.nonzero(wing[:, :, 3] > 8)
WPIV_L = (float(wXs.max()), float(np.median(wYs[wXs >= wXs.max()-2])))
WPIV_R = (2*CX - WPIV_L[0], WPIV_L[1])
HEAD_PIV_L = (2*CX - CX, 742.0)          # 颈部，落在腹部上沿内
HEAD_PIV_R = (2*CX - HEAD_PIV_L[0], 742.0)


# ---------- 合成 ----------
def compose(head_deg=0.0, leg_deg=None, wing_deg=0.0):
    cv = np.zeros((H, W, 4), np.float32)

    # 头：**只用原图层，不叠镜像**。头层是整块的、自带左右两根触角；再叠一份镜像，
    # 一转头两半就错位，屏幕上会冒出 4 根触角（the owner, 2026-11-19 发现）。
    over(cv, rot(P["headL"], HEAD_PIV_L, head_deg), P["headL"]["x0"], P["headL"]["y0"])

    # 腹（盖住头部下沿，转动接缝藏在这条边里）。腹部本身左右对称，同样只画一次
    over(cv, abdL["c"], abdL["x0"], abdL["y0"])

    # 翅在身体**之上**（the owner, 2026-11-19：收翅时不能钻到身体后面去，否则收翅看起来
    # 像翅膀凭空消失）。翅根那截平口靠 alpha 渐隐融进胸背，不会像贴片一样浮在身上。
    for side, piv in (("L", WPIV_L), ("R", WPIV_R)):
        p = P["wingL" if side == "L" else "wingR"]
        over(cv, rot(p, piv, wing_deg * (1 if side == "L" else -1)), p["x0"], p["y0"])

    # 六条腿（最上层）；角度已含左右镜像符号
    for (nm, side), p in LEGS.items():
        d = 0.0 if not leg_deg else leg_deg.get((nm, side), 0.0)
        over(cv, rot(p, p["pivot"], d), p["x0"], p["y0"])
    return cv


def build_frames():
    fr = []
    for hd in (-HEAD_MAX_DEG, 0.0, HEAD_MAX_DEG):           # IDLE 0..2
        fr.append(("idle", dict(head_deg=hd)))

    group = {("front", "L"): 0.0, ("front", "R"): 0.5,
             ("mid", "L"): 0.5,   ("mid", "R"): 0.0,
             ("hind", "L"): 0.0,  ("hind", "R"): 0.5}
    for k in range(WALK_STEPS):                              # WALK 3..10
        t = k / WALK_STEPS
        ld = {}
        for (nm, side), off in group.items():
            swing = math.sin(2*math.pi*(t + off))            # +1 = 朝头方向伸出
            ld[(nm, side)] = LEG_AMP_DEG[nm] * swing * (1 if side == "L" else -1)
        fr.append(("walk", dict(head_deg=HEAD_MAX_DEG*math.sin(2*math.pi*t), leg_deg=ld)))

    for wd in WING_FOLD_DEG:                                 # BURST 11..13
        fr.append(("burst", dict(wing_deg=wd)))
    return fr


FRAMES = build_frames()


def crop_box(comps):
    x0 = y0 = 10**9; x1 = y1 = -1
    for c in comps:
        ys, xs = np.nonzero(c[:, :, 3] > 8)
        x0 = min(x0, xs.min()); x1 = max(x1, xs.max()+1)
        y0 = min(y0, ys.min()); y1 = max(y1, ys.max()+1)
    return int(x0), int(y0), int(x1), int(y1)


def to_sprite(c, box):
    x0, y0, x1, y1 = box
    sc = CONTENT_H / (y1 - y0)
    wn = max(1, int(round((x1 - x0) * sc)))
    im = Image.fromarray(c[y0:y1, x0:x1].astype(np.uint8), "RGBA").resize((wn, CONTENT_H), Image.LANCZOS)
    sp = Image.new("RGBA", (SPRITE, SPRITE), (0, 0, 0, 0))
    sp.alpha_composite(im, ((SPRITE - wn)//2, SPRITE - 2 - CONTENT_H))
    return sp


comps = [compose(**kw) for _, kw in FRAMES]
box = crop_box(comps)
print("公共裁剪窗口", box, "内容", (box[2]-box[0], box[3]-box[1]))

fdir = os.path.join(outdir, "frames")
os.makedirs(fdir, exist_ok=True)
imgs = []
for i, (tag, kw) in enumerate(FRAMES):
    sp = to_sprite(comps[i], box)
    sp.save(os.path.join(fdir, f"frame_{i:02d}.png"))
    imgs.append(sp)
print(f"写出 {len(imgs)} 帧 -> {fdir}")

tags = [t for t, _ in FRAMES]
json.dump({
    "sprite": SPRITE, "contentH": CONTENT_H,
    "idle": [0, 2], "walk": [3, 10], "burst": [11, 13],
    "headMaxDeg": HEAD_MAX_DEG, "walkSteps": WALK_STEPS,
    "tags": tags,
}, open(os.path.join(fdir, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def strip(idx, path, S=5, cols=None):
    cols = cols or len(idx)
    rows = (len(idx) + cols - 1) // cols
    out = Image.new("RGBA", (cols*(SPRITE*S+8)+8, rows*(SPRITE*S+8)+8), (22, 24, 28, 255))
    for j, i in enumerate(idx):
        r, c = divmod(j, cols)
        out.alpha_composite(imgs[i].resize((SPRITE*S, SPRITE*S), Image.NEAREST),
                            (8+c*(SPRITE*S+8), 8+r*(SPRITE*S+8)))
    out.save(path)


strip(list(range(len(imgs))), os.path.join(outdir, "all_frames.png"), S=4, cols=7)
strip(list(range(3, 11)), os.path.join(outdir, "walk_strip.png"), S=6, cols=4)

for tag, idx in (("idle", 1), ("walk", 5)):
    row = Image.new("RGBA", (300, 80), (28, 31, 38, 255))
    x = 10
    for px in (18, 32, 56):
        row.alpha_composite(imgs[idx].resize((px, px), Image.LANCZOS), (x, 74-px)); x += px + 20
    row.resize((row.width*3, row.height*3), Image.NEAREST).save(os.path.join(outdir, f"tv_{tag}.png"))
print("done")
