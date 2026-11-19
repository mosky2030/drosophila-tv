# -*- coding: utf-8 -*-
"""烘焙翅膀 4 帧姿势：切左翅 -> 绕根枢轴 NEAREST 旋转 -> 像素级镜像右翅 -> 合成
用法: python fly_bake.py <64精灵.png> <输出前缀如 docs/fly_pixel_frame>
输出: <前缀>0..3.png (64x64)，帧0=原图收翅，帧1..3=逐渐张开
"""
import sys
import numpy as np
from PIL import Image


WING_COLORS = {
    (167, 202, 234),  # 浅蓝翅主色
    (161, 166, 167),  # 灰（翅脉/高光）
    (254, 221, 242),  # 淡粉白高光
    (163, 86, 155),   # 翅上紫斑
}


def wing_mask(im):
    """翅膀掩码：含浅蓝种子的连通分量(仅不透明像素) + 沿翼外轮廓的深色描边。
    背景透明像素虽残留 RGB 但 alpha=0，必须排除。"""
    from scipy import ndimage
    rgb = im[:, :, :3].astype(int)
    alpha = im[:, :, 3] > 0
    cand = np.zeros(rgb.shape[:2], bool)
    for c in WING_COLORS:
        cand |= (rgb == np.array(c)).all(axis=2)
    cand &= alpha
    lbl, n = ndimage.label(cand)
    seed = (rgb == np.array([167, 202, 234])).all(axis=2) & alpha
    keep = set(lbl[seed].tolist())
    wm = np.isin(lbl, list(keep))
    wm[:26, :] = False  # 翅膀在下半区，排除眼睛区同色
    # 翼外轮廓：与翅膀色 8 邻接的不透明深色像素（描边随翅膀一起转）
    adj = ndimage.binary_dilation(wm, structure=np.ones((3, 3))) & alpha & ~wm
    adj[:26, :] = False
    return wm | adj


def rotate_wing(wing_rgba, pivot, deg):
    """绕 pivot 旋转 deg 度。deg>0 = 翅尖向上抬（左翅）。NEAREST 保像素。"""
    img = Image.fromarray(wing_rgba)
    # PIL rotate 正角 = 逆时针（屏幕视觉）。左翅尖在左下，抬起 = 逆时针。
    r = img.rotate(deg, resample=Image.NEAREST, center=pivot)
    return np.array(r)


def bake(src, prefix):
    im = np.array(Image.open(src))
    assert im.shape == (64, 64, 4), im.shape
    wm = wing_mask(im)
    ys, xs = np.nonzero(wm)
    print("wing px:", wm.sum(), " x:", xs.min(), "-", xs.max(), " y:", ys.min(), "-", ys.max())

    cx = 31.5  # 中线
    left = wm & (np.arange(64)[None, :] <= cx)
    right = wm & (np.arange(64)[None, :] > cx)
    lys, lxs = np.nonzero(left)
    # 左翅枢轴 = 最靠内的点（x 最大），并列取最靠上
    order = np.lexsort((lys, -lxs))
    px_, py_ = int(lxs[order[0]]), int(lys[order[0]])
    print("left wing pivot:", px_, py_)

    body = im.copy()
    body[wm] = 0  # 移除双翅，露出透明
    # 修补翅膀原本盖住的腹部：与外界不连通的透明区 = 破洞，用同列最近的身体像素填充
    from scipy import ndimage
    opaque = body[:, :, 3] > 0
    trans = ~opaque
    lbl, n = ndimage.label(trans)
    outside = set(lbl[0, :]) | set(lbl[-1, :]) | set(lbl[:, 0]) | set(lbl[:, -1])
    outside.discard(0)
    holes = trans & ~np.isin(lbl, list(outside))
    print("inpaint holes:", holes.sum())
    ys_h, xs_h = np.nonzero(holes)
    # 按离已修补/身体区域距离排序，逐个用周围已确定像素的中位数色填充
    remaining = set(zip(ys_h.tolist(), xs_h.tolist()))
    filled = opaque.copy()
    while remaining:
        progressed = False
        for (y, x) in sorted(remaining):
            neigh = []
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    yy, xx = y + dy, x + dx
                    if 0 <= yy < 64 and 0 <= xx < 64 and filled[yy, xx] and not holes[yy, xx]:
                        neigh.append(body[yy, xx, :3])
            if len(neigh) >= 3:
                body[y, x, :3] = np.median(np.array(neigh), axis=0).astype(np.uint8)
                body[y, x, 3] = 255
                filled[y, x] = True
                remaining.discard((y, x))
                progressed = True
        if not progressed:  # 兜底：竖直方向最近身体像素
            for (y, x) in list(remaining):
                for dy in list(range(1, 64)) + list(range(-1, -64, -1)):
                    yy = y + dy
                    if 0 <= yy < 64 and opaque[yy, x]:
                        body[y, x, :3] = body[yy, x, :3]
                        body[y, x, 3] = 255
                        filled[y, x] = True
                        remaining.discard((y, x))
                        break
            break

    wing_l = np.zeros((64, 64, 4), np.uint8)
    wing_l[left] = im[left]

    angles = [0, -8, -22, -32]  # 负角 = 左翅尖向上外侧张开（PIL 正角为视觉逆时针）
    for i, a in enumerate(angles):
        if a == 0:
            frame = im.copy()  # 收翅 = 原图
        else:
            rot = rotate_wing(wing_l, (px_, py_), a)
            # 像素级水平镜像 -> 右翅（镜像后旋转方向自动正确）
            mir = rot[:, ::-1]
            frame = body.copy()
            overlap_l = rot[:, :, 3] > 0
            overlap_r = mir[:, :, 3] > 0
            frame[overlap_l] = rot[overlap_l]
            frame[overlap_r] = mir[overlap_r]
        Image.fromarray(frame).save(f"{prefix}{i}.png")
        print(f"frame{i}: angle={a} -> {prefix}{i}.png")


if __name__ == "__main__":
    src, prefix = sys.argv[1], sys.argv[2]
    bake(src, prefix)
