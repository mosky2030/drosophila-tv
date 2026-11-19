# -*- coding: utf-8 -*-
"""直接网格采样：自动搜格子尺寸+相位，逐格取多数色，抠背景，收敛调色板，居中64x64
用法: python fly_sample.py <输入图> <x0,y0,x1,y1> <输出64.png> [palette_n]
"""
import sys
import numpy as np
import cv2
from PIL import Image
from collections import Counter


def best_grid(crop):
    g = cv2.cvtColor(crop, cv2.COLOR_RGB2GRAY).astype(np.float32)
    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(g, cv2.CV_32F, 0, 1)
    ex = np.abs(gx).mean(axis=0)  # 竖线梯度
    ey = np.abs(gy).mean(axis=1)  # 横线梯度
    best = None
    for s in range(13, 20):
        for dx in range(s):
            v = ex[dx::s].mean()
            for dy in range(s):
                h = ey[dy::s].mean()
                score = v + h
                if best is None or score > best[0]:
                    best = (score, s, dx, dy)
    return best[1], best[2], best[3]


def sample_cells(crop, s, dx, dy):
    h, w, _ = crop.shape
    ncol = (w - dx) // s
    nrow = (h - dy) // s
    out = np.zeros((nrow, ncol, 3), np.uint8)
    for r in range(nrow):
        for c in range(ncol):
            cell = crop[dy + r * s: dy + (r + 1) * s, dx + c * s: dx + (c + 1) * s]
            flat = cell.reshape(-1, 3)
            med = np.median(flat, axis=0)
            d = np.abs(flat.astype(int) - med).sum(axis=1)
            keep = flat[d < 60]
            if len(keep) == 0:
                keep = flat
            out[r, c] = np.median(keep, axis=0).astype(np.uint8)
    return out


def main():
    src, box, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    pal_n = int(sys.argv[4]) if len(sys.argv) > 4 else 16
    x0, y0, x1, y1 = [int(v) for v in box.split(",")]
    im = np.array(Image.open(src).convert("RGB"))[y0:y1, x0:x1]
    s, dx, dy = best_grid(im)
    print(f"grid: cell={s}px offset=({dx},{dy})")
    cells = sample_cells(im, s, dx, dy)
    print("cells:", cells.shape[1], "x", cells.shape[0])
    # 边框中位数当背景色
    border = np.concatenate([cells[0], cells[-1], cells[:, 0], cells[:, -1]])
    bgc = np.median(border, axis=0).astype(int)
    print("bg color:", bgc)
    near_bg = np.abs(cells.astype(int) - bgc).sum(axis=2) < 90
    # 洪泛填充：只有与边缘连通的近背景区才算背景（翅膀/眼睛内部的浅色格子保留）
    n, lbl = cv2.connectedComponents(near_bg.astype(np.uint8), connectivity=4)
    border_labels = set(lbl[0, :]) | set(lbl[-1, :]) | set(lbl[:, 0]) | set(lbl[:, -1])
    border_labels.discard(0)
    bg_mask = np.isin(lbl, list(border_labels))
    m = ~bg_mask
    ys, xs = np.nonzero(m)
    sub = cells[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    mm = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    cols = Counter(map(tuple, sub[mm].reshape(-1, 3)))
    # 聚类合并近似色（JPG 噪点会产生大量近重复色），再取前 pal_n 个簇心
    centers = []
    for c, n in cols.most_common():
        c = np.array(c, int)
        if all(np.abs(c - np.array(p, int)).sum() >= 60 for p in centers):
            centers.append(tuple(c))
        if len(centers) >= pal_n:
            break
    pal = centers
    print("palette:", len(pal))
    flat = sub.reshape(-1, 3)
    d = ((flat[:, None, :] - np.array(pal)[None, :, :]) ** 2).sum(axis=2)
    mapped = np.array(pal)[d.argmin(axis=1)].reshape(sub.shape)
    h2, w2, _ = sub.shape
    print("fly grid:", w2, "x", h2)
    canvas = np.zeros((64, 64, 4), np.uint8)
    oy, ox = (64 - h2) // 2, (64 - w2) // 2
    canvas[oy:oy + h2, ox:ox + w2, :3] = mapped
    canvas[oy:oy + h2, ox:ox + w2, 3] = np.where(mm, 255, 0)
    Image.fromarray(canvas).save(dst)
    Image.fromarray(canvas).resize((512, 512), Image.NEAREST).save(dst.replace(".png", "_big.png"))
    print("->", dst)


if __name__ == "__main__":
    main()
