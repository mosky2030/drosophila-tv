# -*- coding: utf-8 -*-
"""把「仰面（翻过来）」姿势烘成一张 64×64 精灵。

要点：和正常姿势**用同一个缩放系数**（都取自正常姿势的内容高度 CONTENT_H/内容高），
否则翻过来的一瞬间整只果蝇会突然变大变小。内容居中放在 (32,32)，和正常姿势一致。

用法: python tools/psd_bake_flip.py <flip.psd> <outdir> [content_h_for_ref]
"""
import sys, os
import numpy as np
from PIL import Image
from psd_tools import PSDImage

psd_path, outdir = sys.argv[1], sys.argv[2]
SPRITE = 64
CONTENT_H = 60
REF_H = 1035.0          # 正常姿势的内容高度（1440 画布上的像素）
SCALE = CONTENT_H / REF_H

psd = PSDImage.open(psd_path)
canvas = np.zeros((psd.height, psd.width, 4), np.float32)
for l in psd:
    if not l.visible:
        continue
    im = l.composite(force=True)
    if im is None:
        continue
    a = np.array(im.convert("RGBA"), dtype=np.float32)
    x0, y0 = l.bbox[0], l.bbox[1]
    h, w = a.shape[:2]
    d = canvas[y0:y0+h, x0:x0+w]
    al = a[:, :, 3:4] / 255.0
    d[:, :, :3] = a[:, :, :3]*al + d[:, :, :3]*(1-al)
    d[:, :, 3:4] = np.clip(d[:, :, 3:4] + a[:, :, 3:4] - d[:, :, 3:4]*al, 0, 255)
    canvas[y0:y0+h, x0:x0+w] = d

ys, xs = np.nonzero(canvas[:, :, 3] > 8)
x0, x1, y0, y1 = xs.min(), xs.max()+1, ys.min(), ys.max()+1
print("内容 bbox", (x0, y0, x1, y1), "尺寸", (x1-x0, y1-y0))

wn = max(1, int(round((x1-x0)*SCALE)))
hn = max(1, int(round((y1-y0)*SCALE)))
im = Image.fromarray(canvas[y0:y1, x0:x1].astype(np.uint8), "RGBA").resize((wn, hn), Image.LANCZOS)
sp = Image.new("RGBA", (SPRITE, SPRITE), (0, 0, 0, 0))
sp.alpha_composite(im, ((SPRITE-wn)//2, (SPRITE-hn)//2))
fdir = os.path.join(outdir, "frames")
os.makedirs(fdir, exist_ok=True)
sp.save(os.path.join(fdir, "frame_back.png"))
print(f"仰面帧 {wn}×{hn} -> {fdir}/frame_back.png  (scale {SCALE:.5f})")

S = 10
bg = Image.new("RGBA", sp.size, (20, 22, 26, 255)); bg.alpha_composite(sp)
bg.resize((SPRITE*S, SPRITE*S), Image.NEAREST).save(os.path.join(outdir, "back_preview.png"))
