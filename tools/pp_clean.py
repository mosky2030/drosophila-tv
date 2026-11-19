# -*- coding: utf-8 -*-
"""
perfectPixel 清理管线：AI 像素图 -> 干净的 64x64 透明精灵
用法:
  python pp_clean.py <输入图> <裁剪区 x0,y0,x1,y1> <输出64.png>
  python pp_clean.py fly_pixel_ai_preview.png 419,63,790,419 pp_fly_64.png
流程:
  1. perfectPixel 自动检测网格并重采样(每格取多数色)
  2. 从输出边框实测背景色并抠掉
  3. 调色板收敛到 12 色
  4. 居中放到 64x64 透明画布
"""
import sys
import numpy as np
import cv2
from PIL import Image
from collections import Counter
from perfect_pixel import get_perfect_pixel


def clean(crop_rgb, palette_n=12, bg_tol=90):
    w, h, out = get_perfect_pixel(crop_rgb)
    print("perfectPixel grid:", w, "x", h)
    border = np.concatenate([out[0, :], out[-1, :], out[:, 0], out[:, -1]])
    bgc = np.median(border, axis=0).astype(int)
    m = np.abs(out.astype(int) - bgc).sum(axis=2) >= bg_tol
    ys, xs = np.nonzero(m)
    sub = out[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    mm = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    cols = Counter(map(tuple, sub[mm].reshape(-1, 3)))
    pal = [c for c, _ in cols.most_common(palette_n)]
    flat = sub.reshape(-1, 3)
    d = ((flat[:, None, :] - np.array(pal)[None, :, :]) ** 2).sum(axis=2)
    mapped = np.array(pal)[d.argmin(axis=1)].reshape(sub.shape)
    h2, w2, _ = sub.shape
    canvas = np.zeros((64, 64, 4), np.uint8)
    oy, ox = (64 - h2) // 2, (64 - w2) // 2
    canvas[oy:oy + h2, ox:ox + w2, :3] = mapped
    canvas[oy:oy + h2, ox:ox + w2, 3] = np.where(mm, 255, 0)
    return canvas, (w2, h2), pal


def main():
    src, box, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    x0, y0, x1, y1 = [int(v) for v in box.split(",")]
    im = np.array(Image.open(src).convert("RGB"))[y0:y1, x0:x1]
    canvas, (w2, h2), pal = clean(im)
    Image.fromarray(canvas).save(dst)
    Image.fromarray(canvas).resize((512, 512), Image.NEAREST).save(
        dst.replace(".png", "_big.png"))
    print("fly grid:", w2, "x", h2, " palette:", len(pal), "->", dst)


if __name__ == "__main__":
    main()
