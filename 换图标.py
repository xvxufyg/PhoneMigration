# -*- coding: utf-8 -*-
r"""
一键换 App 图标
用法：
1. 把你的图片放到本目录下的  图标\input.png  （jpg/png 都行，改成 input.png）
2. 双击运行  换图标.bat
3. 重新编译即可（一键构建.bat）

如果 图标\input.png 不存在，会自动生成一个默认的紫色"搬"字图标。
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BASE, "app", "src", "main", "res")
INPUT = os.path.join(BASE, "图标", "input.png")

# 各密度 -> 尺寸
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def make_default(size):
    """画一个默认图标：紫色圆角背景 + 白色'搬'字（无字体则画箭头箱子）"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = int(size * 0.06)
    d.rounded_rectangle(
        [pad, pad, size - pad, size - pad],
        radius=int(size * 0.22),
        fill=(98, 0, 238, 255),
    )
    # 尝试用微软雅黑画"搬"字
    drawn = False
    for fp in [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc",
               r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(fp):
            try:
                font = ImageFont.truetype(fp, int(size * 0.52))
                bbox = d.textbbox((0, 0), "搬", font=font)
                w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
                d.text(((size - w) / 2 - bbox[0], (size - h) / 2 - bbox[1]),
                       "搬", font=font, fill=(255, 255, 255, 255))
                drawn = True
                break
            except Exception:
                pass
    if not drawn:
        # 画一个向右的白色箭头（搬家方向）
        cy = size // 2
        d.line([int(size*0.28), cy, int(size*0.72), cy], fill=(255,255,255,255), width=max(2,int(size*0.06)))
        d.polygon([(int(size*0.72), cy), (int(size*0.56), int(size*0.36)),
                   (int(size*0.56), int(size*0.64))], fill=(255,255,255,255))
    return img


def crop_square(im):
    im = im.convert("RGBA")
    w, h = im.size
    s = min(w, h)
    left, top = (w - s) // 2, (h - s) // 2
    return im.crop((left, top, left + s, top + s))


def main():
    use_input = os.path.exists(INPUT)
    if use_input:
        print("使用自定义图标:", INPUT)
        src = crop_square(Image.open(INPUT))
    else:
        print("未找到 图标\\input.png，生成默认图标")

    for folder, px in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        img = src.resize((px, px), Image.LANCZOS) if use_input else make_default(px)
        img.convert("RGB").save(os.path.join(d, "ic_launcher.png"))
        # 同时生成圆形图标
        rc = img.copy()
        mask = Image.new("L", (px, px), 0)
        md = ImageDraw.Draw(mask)
        md.ellipse((0, 0, px, px), fill=255)
        rc.putalpha(mask)
        rc.save(os.path.join(d, "ic_launcher_round.png"))
        print("  %-16s %dx%d" % (folder, px, px))

    print("\n图标已更新。重新运行 一键构建.bat 生成新 APK。")


if __name__ == "__main__":
    main()
