# -*- coding: utf-8 -*-
"""检测 PSD 还原图的像素栅格尺寸（cell size）与相位（向量化版）。"""
import sys, numpy as np
from PIL import Image

path = sys.argv[1]
arr = np.array(Image.open(path).convert("RGBA"))
a = arr[:, :, 3]
H, W = a.shape

# alpha 阶跃所在的列/行
col_edge = np.where(np.any(a[:, 1:] != a[:, :-1], axis=0))[0] + 1
row_edge = np.where(np.any(a[1:, :] != a[:-1, :], axis=1))[0] + 1
print("edge columns:", len(col_edge), "edge rows:", len(row_edge))

best = None
for s in range(8, 81):
    cs = (col_edge[:, None] % s)                  # (n,1)
    rs = (row_edge[:, None] % s)
    for dx in range(s):
        hx = np.count_nonzero(cs == dx) / len(col_edge)
        for dy in range(s):
            v = hx + np.count_nonzero(rs == dy) / len(row_edge)
            if best is None or v > best[0]:
                best = (v, s, dx, dy)
v, s, dx, dy = best
print(f"best cell={s}px offset=({dx},{dy}) score={v:.3f}")
ys, xs = np.nonzero(a)
x0, y0, x1, y1 = xs.min(), ys.min(), xs.max()+1, ys.max()+1
print("content", (x0, y0, x1, y1), "=>", ((x1-x0)/s, (y1-y0)/s), "cells")
