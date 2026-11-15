#!/usr/bin/env python3
"""生成 DFC 爆炸传感器的三张贴图（16x16）。

贴图是代码生成的而不是手绘的：三张图共用同一块钢板底座，只有镜头那几像素不同。
写成脚本之后，"亮起来"这件事就是同一组像素换三个颜色，不会出现三张图边缘对不齐。
"""

import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                   "src/main/resources/assets/hbmzz/textures/blocks")

BASE = (52, 56, 62)
BORDER_LIGHT = (92, 98, 106)
BORDER_DARK = (38, 40, 45)
RIVET = (96, 102, 110)
RIVET_SHADE = (34, 36, 40)
SEAM = (38, 41, 46)
SEAM_LIGHT = (66, 70, 77)
HOUSING = (40, 43, 48)
RING = (98, 104, 112)


def plate(x, y):
    """不带镜头的钢板：斜角边框、铆钉、一道横缝。"""
    if x == 0 or y == 0:
        return BORDER_LIGHT
    if x == 15 or y == 15:
        return BORDER_DARK
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        return RIVET
    if (x, y) in ((3, 3), (14, 3), (3, 14), (14, 14)):
        return RIVET_SHADE
    if y == 12:
        return SEAM
    if y == 13 and 2 <= x <= 13:
        return SEAM_LIGHT
    # 一点点噪声，免得整块板是一整片死色
    n = ((x * 7 + y * 13) % 5) - 2
    return (max(0, BASE[0] + n), max(0, BASE[1] + n), max(0, BASE[2] + n))


def lens(img, cx, cy, radius, colour):
    """在 img 上画一个实心圆（抗锯齿不要，16px 的图上会糊）。"""
    r2 = radius * radius
    for y in range(16):
        for x in range(16):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            if dx * dx + dy * dy <= r2:
                img[y][x] = colour


def sensor(on):
    img = [[plate(x, y) for x in range(16)] for y in range(16)]
    # 镜头基座嵌进钢板：外圈是一道金属环，里面是玻璃
    lens(img, 8.0, 7.0, 4.9, HOUSING)
    lens(img, 8.0, 7.0, 4.2, RING)
    if on:
        lens(img, 8.0, 7.0, 4.1, (150, 68, 38))
        lens(img, 8.0, 7.0, 3.3, (255, 138, 66))
        lens(img, 8.0, 7.0, 2.3, (255, 86, 54))
        lens(img, 7.4, 6.4, 1.2, (255, 214, 158))
        # 亮起来的时候底下那排指示灯也点着
        for x in range(4, 12):
            img[14][x] = (255, 190, 96)
    else:
        lens(img, 8.0, 7.0, 3.3, (78, 42, 42))
        lens(img, 8.0, 7.0, 2.3, (58, 28, 28))
        lens(img, 7.4, 6.4, 1.1, (104, 58, 54))
        for x in range(4, 12):
            img[14][x] = (70, 58, 40)
    return img


def write_png(path, img):
    raw = b""
    for row in img:
        raw += b"\x00" + b"".join(struct.pack("BBB", *px) for px in row)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    side = [[plate(x, y) for x in range(16)] for y in range(16)]
    write_png(os.path.join(OUT, "dfc_blast_sensor_side.png"), side)
    write_png(os.path.join(OUT, "dfc_blast_sensor_front.png"), sensor(False))
    write_png(os.path.join(OUT, "dfc_blast_sensor_front_on.png"), sensor(True))
    for name in ("side", "front", "front_on"):
        path = os.path.join(OUT, "dfc_blast_sensor_%s.png" % name)
        print("%-46s %d B" % (os.path.basename(path), os.path.getsize(path)))


if __name__ == "__main__":
    main()
