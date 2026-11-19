# -*- coding: utf-8 -*-
"""果蝇的落影：把每帧精灵的 alpha 模糊一下、染成黑色，得到一张同尺寸的投影图。

为什么不用一个椭圆（the owner, 2026-11-20：「上次加的椭圆阴影太难看了，所以去掉了，这次希望
能好看一些」）：
  • 果蝇是 6 条腿 + 2 只翅膀的细碎轮廓，硬边椭圆和它的形状毫无关系，压在底下就像一个脏点；
  • 而且椭圆是「实心」的，果蝇身体又几乎占满 64px 画布，椭圆只能露出边缘一圈，看不出是影子。
用轮廓本身做投影，投出来的就是这只果蝇的形状——腿缝里、翅膀边缘外伸出来的那一小截暗，
才像它真的压在一张纸面上。模糊 σ=1.8（64px 空间）放大到 180px 大约是 5px 的柔边，
不会糊成一团，也不会露出像素锯齿。

α 上限 0.42：比椭圆版本浓，但它只在轮廓外面露出一点点，整体观感反而更轻。

用法: python fly_shadow.py
输出: app/src/main/res/drawable-nodpi/fly_shadow_*.png（与 fly_frame_*.png 一一对应）
"""
import numpy as np
from PIL import Image, ImageFilter

D = "app/src/main/res/drawable-nodpi/"
FRAMES = (
    ["fly_frame_%02d" % i for i in range(14)]
    + ["fly_frame_back", "fly_frame_groom0", "fly_frame_groom1"]
)
SIGMA = 1.8
STRENGTH = 0.42


def main():
    for name in FRAMES:
        src = Image.open(D + name + ".png").convert("RGBA")
        assert src.size == (64, 64), (name, src.size)
        a = src.split()[3].filter(ImageFilter.GaussianBlur(SIGMA))
        a = a.point(lambda v: int(v * STRENGTH))
        out = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
        out.putalpha(a)
        out.save(D + name.replace("fly_frame", "fly_shadow") + ".png")
        print(name, "->", out.filename if hasattr(out, "filename") else name.replace("fly_frame", "fly_shadow"))


if __name__ == "__main__":
    main()
