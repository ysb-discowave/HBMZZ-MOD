#!/usr/bin/env python3
"""生成红石门（四种）的贴图、方块状态与模型（16x16）。

和 DFC 传感器那套一样：贴图是代码生成的，四种门共用同一块钢板底座，只有正面
那个符号不同；符号本身是手写的点阵，亮/灭只换颜色——这样四张图不会各画各的
边框，拼接时也不会出现一个门比另一个亮半格。

    python tools/art/make_gate_textures.py
"""

import io
import json
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "src/main/resources/assets/hbmzz/textures/blocks")
STATES = os.path.join(ROOT, "src/main/resources/assets/hbmzz/blockstates")
MODELS = os.path.join(ROOT, "src/main/resources/assets/hbmzz/models/block")
ITEMS = os.path.join(ROOT, "src/main/resources/assets/hbmzz/models/item")

# 与站点的配色同源：钢板灰、琥珀磷光
BASE = (52, 56, 62)
BORDER_LIGHT = (92, 98, 106)
BORDER_DARK = (38, 40, 45)
RIVET = (96, 102, 110)
RIVET_SHADE = (34, 36, 40)
INSET = (30, 33, 38)
INSET_LIGHT = (44, 48, 55)
GLYPH_OFF = (74, 66, 52)
GLYPH_ON = (245, 165, 36)
GLYPH_HOT = (255, 194, 92)
DOT_OFF = (48, 42, 34)
DOT_ON = (67, 217, 163)

# 正面的符号：一张 16x16 的点阵，'#' 是笔画
GLYPHS = {
    "and": [
        "................",
        "................",
        "................",
        "...########.....",
        "...#......##....",
        "...#.......#....",
        "...#.......#....",
        "...#.......#....",
        "...#......##....",
        "...########.....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "or": [
        "................",
        "................",
        "....#.......#...",
        "....#........#..",
        ".....#........#.",
        "......#.......#.",
        ".......#.....#..",
        "........#####...",
        ".......#.....#..",
        "......#.......#.",
        ".....#........#.",
        "....#........#..",
        "....#.......#...",
        "................",
        "................",
        "................",
    ],
    "xor": [
        "................",
        "................",
        "....##......##..",
        ".....#.......#..",
        "......#.......#.",
        ".......#......#.",
        "........#....#..",
        ".........#####..",
        "........#....#..",
        ".......#......#.",
        "......#.......#.",
        ".....#.......#..",
        "....##......##..",
        "................",
        "................",
        "................",
    ],
    "not": [
        "................",
        "................",
        "....#...........",
        "....##..........",
        "....###.........",
        "....####....##..",
        "....#####...##..",
        "....#####...##..",
        "....####....##..",
        "....###.........",
        "....##..........",
        "....#...........",
        "................",
        "................",
        "................",
        "................",
    ],
}

TYPES = ("and", "or", "xor", "not")


def plate(x, y):
    """门的外壳：斜角边框、四角铆钉、正面一块内凹的面板。"""
    if x == 0 or y == 0:
        return BORDER_LIGHT
    if x == 15 or y == 15:
        return BORDER_DARK
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        return RIVET
    if (x, y) in ((3, 3), (14, 3), (3, 14), (14, 14)):
        return RIVET_SHADE
    # 内凹面板：上沿暗、下沿亮，看起来是凹进去的
    if 2 <= x <= 13 and 2 <= y <= 13:
        if y == 2:
            return INSET
        if y == 13:
            return INSET_LIGHT
        return INSET
    return BASE


def front(kind, on):
    """正面：外壳 + 符号 + 右下角一颗状态灯。"""
    rows = GLYPHS[kind]
    img = [[plate(x, y) for x in range(16)] for y in range(16)]
    glyph = GLYPH_ON if on else GLYPH_OFF
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                # 亮的时候给笔画加一像素高光，暗的时候保持哑光
                img[y][x] = GLYPH_HOT if (on and (x + y) % 5 == 0) else glyph
    # 状态灯：灭是暗红铜色，亮是薄荷绿——和站点里那台机器的指示灯一个色
    for y in (12, 13):
        for x in (11, 12):
            img[y][x] = DOT_ON if on else DOT_OFF
    return img


def write_png(path, img):
    raw = bytearray()
    for row in img:
        raw.append(0)
        for px in row:
            raw += bytes(px)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def dump(path, obj):
    io.open(path, "w", encoding="utf-8", newline="\n").write(
        json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def blockstate(kind):
    """正面在模型里是 south（和 DFC 传感器那套一致），所以朝向靠 y 旋转。"""
    variants = {}
    for facing, y in (("south", None), ("west", 90), ("north", 180), ("east", 270)):
        for powered in (False, True):
            name = "hbmzz:%s_gate%s" % (kind, "_on" if powered else "")
            variant = {"model": name}
            if y is not None:
                variant["y"] = y
            variants["facing=%s,powered=%s" % (facing, "true" if powered else "false")] = variant
    return {"variants": variants}


def model(kind, on):
    front_tex = "hbmzz:blocks/gate_%s%s" % (kind, "_on" if on else "")
    return {
        "parent": "block/cube",
        "textures": {
            "particle": "hbmzz:blocks/gate_side",
            "down": "hbmzz:blocks/gate_side",
            "up": "hbmzz:blocks/gate_side",
            "north": "hbmzz:blocks/gate_side",
            "east": "hbmzz:blocks/gate_side",
            "west": "hbmzz:blocks/gate_side",
            "south": front_tex,
        },
    }


def main():
    for d in (TEX, STATES, MODELS, ITEMS):
        os.makedirs(d, exist_ok=True)

    side = [[plate(x, y) for x in range(16)] for y in range(16)]
    write_png(os.path.join(TEX, "gate_side.png"), side)
    print("%-34s %d B" % ("gate_side.png", os.path.getsize(os.path.join(TEX, "gate_side.png"))))

    for kind in TYPES:
        for on in (False, True):
            name = "gate_%s%s.png" % (kind, "_on" if on else "")
            path = os.path.join(TEX, name)
            write_png(path, front(kind, on))
            print("%-34s %d B" % (name, os.path.getsize(path)))

        dump(os.path.join(STATES, "%s_gate.json" % kind), blockstate(kind))
        for on in (False, True):
            dump(os.path.join(MODELS, "%s_gate%s.json" % (kind, "_on" if on else "")), model(kind, on))
        dump(os.path.join(ITEMS, "%s_gate.json" % kind), {"parent": "hbmzz:block/%s_gate" % kind})
        print("  %s_gate: 1 blockstate / 2 block models / 1 item model" % kind)


if __name__ == "__main__":
    main()
