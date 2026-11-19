# -*- coding: utf-8 -*-
"""搓腿（前腿搓动）帧：把 frame_00 的前腿像素绕根部旋转，做出两只前腿一收一放的对搓。

思路和 fly_bake.py 一致——不新画像素，只搬动原有的腿部像素，所以调色板、抗锯齿、
描边粗细天然和原图一致。区别是翅膀 20+ px 宽、转多少度都连成片；前腿只有 1~2 px 宽，
必须用 BILINEAR（NEAREST 会把细腿打断成一串点）。

前腿在原图里的位置（frame_00，左半）：从翅根附近 (15,19) 斜着伸到 (10,16)，
即「左上方」的一小段。绕根部 (15,20) 负角度旋转 = 腿尖向上、向内收，
收到头侧就是搓手的姿势（PIL 的 rotate 正角是屏幕逆时针，负角把点往上带）。

用法: python fly_groom.py
输出: app/src/main/res/drawable-nodpi/fly_frame_groom0.png / _groom1.png
"""
import numpy as np
from PIL import Image

SRC = "app/src/main/res/drawable-nodpi/fly_frame_00.png"
OUT = "app/src/main/res/drawable-nodpi/fly_frame_groom%d.png"

# 前腿的可搬动范围：x 4..15, y 13..19。y 到 19 为止——再往下就碰到第二条腿的根部，
# 一起转会把中腿的起点也拽歪。枢轴放在 (15,20)，离边缘 1px，转过之后仍然贴着身体。
X0, X1, Y0, Y1 = 4, 15, 13, 19
PIVOT = (15.0, 20.0)

# 两相：左右腿收得不一样多，交替播放就是「搓」。差值 14° 在 64px 上大约 1.5px 位移，
# 再大就看出是两条腿在各自摆，不像对搓。
PHASES = [
    (-52.0, -30.0),   # A：左腿先收
    (-30.0, -52.0),   # B：右腿先收
]


def leg_layer(im):
    """左前腿单独一层（64x64 RGBA，其余透明）。"""
    layer = np.zeros_like(im)
    layer[Y0:Y1 + 1, X0:X1 + 1] = im[Y0:Y1 + 1, X0:X1 + 1]
    # alpha≤11 的边角噪声必须连 RGB 一起抹掉，不能只清 alpha：原图透明区残留着
    # (0,255,255) 这种青色，BILINEAR 一转就把它们混进腿边，腿上会多一道青色描边。
    weak = layer[:, :, 3] <= 11
    layer[weak] = 0
    return layer


def rotated(layer, deg, pivot):
    # pivot 必须跟着腿一起镜像：右腿是 layer[:, ::-1]，它的根部在 63-15=48，
    # 还按 (15,20) 转会绕到屏幕另一头去（第一版就这样把腿甩到了右侧翅膀上）。
    img = Image.fromarray(layer)
    out = img.rotate(deg, resample=Image.BILINEAR, center=pivot)
    return np.array(out)


def paste(dst, src):
    """按 alpha 覆盖（而不是直接赋值），转出来的半透明边缘才能和底图混好。"""
    sa = src[:, :, 3].astype(np.float32) / 255.0
    out = dst.astype(np.float32)
    for c in range(3):
        out[:, :, c] = src[:, :, c] * sa + out[:, :, c] * (1 - sa)
    a = src[:, :, 3].astype(np.float32) + dst[:, :, 3].astype(np.float32) * (1 - sa)
    out[:, :, 3] = a
    return np.clip(out, 0, 255).astype(np.uint8)


def main():
    im = np.array(Image.open(SRC).convert("RGBA"))
    layer = leg_layer(im)

    # 底图：先把左右前腿都擦掉（右腿是左腿的镜像，x -> 63-x）
    body = im.copy()
    body[Y0:Y1 + 1, X0:X1 + 1, 3] = 0
    body[Y0:Y1 + 1, 63 - X1:63 - X0 + 1, 3] = 0

    for i, (dl, dr) in enumerate(PHASES):
        left = rotated(layer, dl, PIVOT)
        right = rotated(layer[:, ::-1], dr, (63.0 - PIVOT[0], PIVOT[1]))
        frame = body.copy()
        frame = paste(frame, left)
        frame = paste(frame, right)
        Image.fromarray(frame).save(OUT % i)
        print(f"groom{i}: left={dl} right={dr} -> {OUT % i}")


if __name__ == "__main__":
    main()
