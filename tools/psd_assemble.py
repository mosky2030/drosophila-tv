# -*- coding: utf-8 -*-
"""把 PSD 各图层按 bbox 还原成一张图，方便确认用户的分层意图。

用法: python tools/psd_assemble.py <psd> <outdir>
"""
import sys, os
from psd_tools import PSDImage
from PIL import Image
import numpy as np

psd_path = sys.argv[1]
outdir = sys.argv[2]
os.makedirs(outdir, exist_ok=True)

psd = PSDImage.open(psd_path)
W, H = psd.width, psd.height
layers = list(psd)          # top -> bottom

info = []
for i, l in enumerate(layers):
    im = l.composite(force=True)
    bbox = l.bbox
    info.append((i, l.name, bbox, im))
    print(f"L{i}\t{l.name!r}\tbbox={bbox}\tsize={im.size if im else None}")

def assemble(include):
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # 自底向上贴：layers 是 top->bottom，所以反转
    for i, name, bbox, im in reversed(info):
        if i not in include or im is None:
            continue
        x0, y0, x1, y1 = bbox
        canvas.alpha_composite(im.convert("RGBA"), (x0, y0))
    return canvas

# 全部（含红色背景）——预计是全红，用来验证层级顺序
allc = assemble(set(range(len(info))))
allc.save(os.path.join(outdir, "assemble_all.png"))

# 去掉背景层（通常是最大面积那层红色）
areas = [(b[2]-b[0])*(b[3]-b[1]) for _, _, b, _ in info]
bg_idx = int(np.argmax(areas))
no_bg = assemble(set(range(len(info))) - {bg_idx})
no_bg.save(os.path.join(outdir, "assemble_nobg.png"))

# 裁到内容并放大 4 倍（棋盘底）
arr = np.array(no_bg)
ys, xs = np.nonzero(arr[:, :, 3])
if len(xs):
    x0, x1, y0, y1 = xs.min(), xs.max()+1, ys.min(), ys.max()+1
    print("content bbox", (x0, y0, x1, y1), "size", (x1-x0, y1-y0))
    crop = no_bg.crop((x0, y0, x1, y1))
    bg = Image.new("RGBA", crop.size, (24, 26, 30, 255))
    bg.alpha_composite(crop)
    S = 4
    bg.resize((crop.width*S, crop.height*S), Image.NEAREST).save(os.path.join(outdir, "assemble_nobg_big.png"))
    # 白底版
    w = Image.new("RGBA", crop.size, (255, 255, 255, 255))
    w.alpha_composite(crop)
    w.resize((crop.width*S, crop.height*S), Image.NEAREST).save(os.path.join(outdir, "assemble_nobg_white.png"))

print("bg layer (max area) =", bg_idx, info[bg_idx][1])
