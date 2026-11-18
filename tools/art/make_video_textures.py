#!/usr/bin/env python3
"""生成视频大屏与遥控器的 16x16 贴图。

只依赖 Pillow，纯本地、可重复：同一份脚本跑两次，输出的 PNG 字节完全相同。
色板与 `docs/assets/site.css` 一致（钢板 #e6ecf3 / 琥珀 #f5a524 / 墨 #0a0c0f），
另外加了两级深灰给外壳和面板，免得整块屏幕在暗处糊成一块。

    python tools/art/make_video_textures.py

输出：
    src/main/resources/assets/hbmzz/textures/blocks/video_screen_front.png
    src/main/resources/assets/hbmzz/textures/blocks/video_screen_side.png
    src/main/resources/assets/hbmzz/textures/blocks/video_screen_top.png
    src/main/resources/assets/hbmzz/textures/blocks/video_screen_part_front.png
    src/main/resources/assets/hbmzz/textures/items/video_remote.png

为什么要单独的 part 贴图：大屏只有控制格有方块实体，画面由渲染器画在整面墙前面。
其余格子只是外壳，所以它们的正面不该有"屏幕"那种内凹的玻璃反光，否则每格都能看
出一条分界线，一面 8x8 的墙会被自己的边框切成 64 块。
"""

import os
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BLOCK_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "hbmzz", "textures", "blocks")
ITEM_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "hbmzz", "textures", "items")

STEEL = (230, 236, 243)
AMBER = (245, 165, 36)
INK = (10, 12, 15)
HULL = (35, 39, 43)
HULL_HI = (53, 60, 66)
GLASS = (18, 22, 26)
GLASS_HI = (30, 38, 45)


def new_image():
    return Image.new("RGBA", (16, 16), HULL + (255,))


def put(img, x, y, color):
    if len(color) == 3:
        color = color + (255,)
    img.putpixel((x, y), color)


def frame(img, color=HULL_HI):
    for i in range(16):
        put(img, i, 0, color)
        put(img, i, 15, color)
        put(img, 0, i, color)
        put(img, 15, i, color)


def video_screen_front(with_glass=True):
    """正面：一圈外壳 + 内凹的屏幕。"""
    img = new_image()
    frame(img)
    for y in range(1, 15):
        for x in range(1, 15):
            put(img, x, y, HULL + (255,) if not with_glass else GLASS + (255,))
    if with_glass:
        # 左上角一道弱反光，让"玻璃"在暗处也能读出来
        for i in range(1, 6):
            put(img, i, 1, GLASS_HI)
        for y in range(2, 6):
            put(img, 1, y, GLASS_HI)
        # 四角螺丝
        for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
            put(img, x, y, HULL_HI)
    else:
        # 拼接格：只要一条内缝，不要玻璃反光
        for i in range(1, 15):
            put(img, i, 1, HULL)
            put(img, 1, i, HULL)
    return img


def video_screen_side():
    img = new_image()
    for y in range(16):
        for x in range(16):
            put(img, x, y, HULL + (255,) if (x + y) % 5 else HULL_HI + (255,))
    frame(img)
    return img


def video_screen_top():
    img = new_image()
    frame(img)
    # 散热鳍片
    for x in range(3, 13, 3):
        for y in range(4, 12):
            put(img, x, y, HULL_HI)
    return img


def video_remote():
    """遥控器：竖握的手持机，顶部信号窗、中部方向键、底部两枚按键。"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    body = HULL
    edge = (24, 28, 32)
    for y in range(1, 16):
        for x in range(4, 12):
            put(img, x, y, body)
    for x in range(4, 12):
        put(img, x, 1, edge)
        put(img, x, 15, edge)
    for y in range(1, 16):
        put(img, 4, y, edge)
        put(img, 11, y, edge)
    # 圆角：削掉四个角一格，比直角更像手持机
    img.putpixel((4, 1), (0, 0, 0, 0))
    img.putpixel((11, 1), (0, 0, 0, 0))
    img.putpixel((4, 15), (0, 0, 0, 0))
    img.putpixel((11, 15), (0, 0, 0, 0))
    # 天线/发射窗
    for x in range(5, 11):
        put(img, x, 2, AMBER)
        put(img, x, 3, AMBER)
    # 屏幕
    for y in range(5, 8):
        for x in range(5, 11):
            put(img, x, y, (14, 18, 22))
    for x in range(6, 10):
        put(img, x, 6, (67, 217, 163))
    # 方向键（十字：中间两格宽，左右各伸出一格）
    for y in (9, 10, 11):
        put(img, 7, y, STEEL)
        put(img, 8, y, STEEL)
    put(img, 6, 10, STEEL)
    put(img, 9, 10, STEEL)
    # 两枚功能键
    put(img, 6, 13, AMBER)
    put(img, 7, 13, AMBER)
    put(img, 9, 13, (229, 72, 77))
    put(img, 10, 13, (229, 72, 77))
    return img


def main():
    os.makedirs(BLOCK_DIR, exist_ok=True)
    os.makedirs(ITEM_DIR, exist_ok=True)
    outputs = {
        os.path.join(BLOCK_DIR, "video_screen_front.png"): video_screen_front(True),
        os.path.join(BLOCK_DIR, "video_screen_part_front.png"): video_screen_front(False),
        os.path.join(BLOCK_DIR, "video_screen_side.png"): video_screen_side(),
        os.path.join(BLOCK_DIR, "video_screen_top.png"): video_screen_top(),
        os.path.join(ITEM_DIR, "video_remote.png"): video_remote(),
    }
    for path, image in outputs.items():
        image.save(path, optimize=True)
        print("wrote", os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
