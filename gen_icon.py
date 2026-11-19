# -*- coding: utf-8 -*-
r"""
按 ui-ux-pro-max 技能规范生成「搬家」App 图标
设计系统：Minimalism & Swiss Style
  - 极简几何、高对比、网格对齐、无 emoji
  - 主色 Navy #1E3A5F，点缀 Accent Green #059669，白色主体（on-primary）
图形语义：白色纸箱 + 绿色向上箭头 = 搬起/迁移，缩小到 48dp 仍清晰
输出：
  - icon_preview.png（大图预览）
  - app/src/main/res/mipmap-*/ic_launcher.png / ic_launcher_round.png
"""
import os
from PIL import Image, ImageDraw

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BASE, "app", "src", "main", "res")

NAVY      = (30, 58, 95, 255)     # --color-primary
NAVY_DK   = (18, 37, 64, 255)     # 深一档，用于胶带
GREEN     = (5, 150, 105, 255)    # --color-accent
WHITE     = (255, 255, 255, 255)  # on-primary
LIGHT     = (226, 234, 244, 255)  # 箱盖浅灰

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def draw_icon(px: int) -> Image.Image:
    """在 px×px 上绘制图标，4 倍超采样抗锯齿，坐标基于 1000×1000 归一化"""
    ss = 4
    S = px * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def c(v):
        return int(v * ss)  # 归一化坐标 -> 超采样像素

    # 背景：navy 圆角方形（Swiss 几何、软圆角）
    d.rounded_rectangle(
        [c(60), c(60), c(940), c(940)], radius=c(210), fill=NAVY
    )
    # 白色纸箱主体
    d.rounded_rectangle(
        [c(285), c(440), c(715), c(770)], radius=c(26), fill=WHITE
    )
    # 箱盖横带（浅灰，增加层次）
    d.rectangle([c(285), c(440), c(715), c(482)], fill=LIGHT)
    # 封箱胶带（深 navy 竖条）
    d.rectangle([c(482), c(440), c(538), c(770)], fill=NAVY_DK)
    # 绿色向上箭头：从箱中向上 = 搬起 / 迁移
    d.rectangle([c(488), c(250), c(532), c(452)], fill=GREEN)
    d.polygon(
        [(c(510), c(175)), (c(425), c(285)), (c(595), c(285))],
        fill=GREEN,
    )
    return img.resize((px, px), Image.LANCZOS)


def round_icon(img: Image.Image) -> Image.Image:
    """圆形版本（ic_launcher_round）"""
    px = img.width
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, px, px), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main():
    # 大图预览
    draw_icon(512).save(os.path.join(BASE, "icon_preview.png"))
    print("预览: icon_preview.png")

    for folder, px in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        ic = draw_icon(px)
        ic.save(os.path.join(d, "ic_launcher.png"))          # 保留 alpha
        round_icon(ic).save(os.path.join(d, "ic_launcher_round.png"))
        print("  %-16s %dx%d" % (folder, px, px))
    print("\n图标已生成（Swiss Minimalism / Navy+Green）。重新构建 APK 生效。")


if __name__ == "__main__":
    main()
