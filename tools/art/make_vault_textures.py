#!/usr/bin/env python3
"""生成金库门（3D 立体模型）用的 16x16 方块贴图。

无 PIL：PNG 手写（每行一个 filter 字节 + zlib）。全部程序化生成
（确定性伪噪声、斜角、铆钉、拉丝），重跑结果一致。

三张图：
  vault_frame.png      门框钢：斜角边（上左亮、下右暗）+ 角铆钉
  vault_body.png       门板装甲面：拉丝 + 四角铆钉（按格平铺）
  vault_body_side.png  门板侧 / 端 / 背面：更暗的钢 + 棱高光

    python tools/art/make_vault_textures.py
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "src/main/resources/assets/hbmzz/textures/blocks")

FRAME = (94, 98, 106)
FRAME_HI = (146, 150, 158)
FRAME_LO = (54, 56, 62)
RIVET = (172, 176, 184)
RIVET_LO = (38, 40, 46)

BODY = (142, 146, 154)
BODY_HI = (170, 174, 182)
BODY_LO = (112, 116, 124)
BODY_LINE = (126, 130, 138)

SIDE = (72, 76, 84)
SIDE_HI = (118, 122, 130)


def noise(seed, x, y, amount):
    """确定性伪噪声，避免 random 让重跑结果漂移。"""
    h = (x * 374761393 + y * 668265263 + seed * 971) & 0xFFFF
    return (h % (2 * amount + 1)) - amount


def shade(base, d):
    return (max(0, min(255, base[0] + d)),
            max(0, min(255, base[1] + d)),
            max(0, min(255, base[2] + d)))


def png(path, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b in row:
            raw += bytes((r, g, b))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    blob = b"\x89PNG\r\n\x1a\n"
    blob += chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
    blob += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    blob += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(blob)
    return os.path.getsize(path)


def frame():
    """门框钢：上、左棱亮，下、右棱暗，四角与四边中点铆钉。"""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            base = FRAME
            if y == 0 or x == 0:
                base = FRAME_HI
            if y == 15 or x == 15:
                base = FRAME_LO
            row.append(shade(base, noise(11, x, y, 5)))
        rows.append(row)
    for (cx, cy) in ((2, 2), (13, 2), (2, 13), (13, 13),
                     (7, 1), (8, 1), (7, 14), (8, 14),
                     (1, 7), (1, 8), (14, 7), (14, 8)):
        rows[cy][cx] = RIVET
        if cy + 1 < 16 and cx + 1 < 16:
            rows[cy + 1][cx + 1] = RIVET_LO
    return rows


def body():
    """门板装甲面：竖向拉丝、上亮下暗的棱、四角铆钉（平铺后成铆钉阵）。"""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            base = BODY
            if y == 0 or x == 0:
                base = BODY_HI
            if y == 15 or x == 15:
                base = BODY_LO
            if x == 7 or x == 8:
                base = BODY_LINE
            d = noise(21, x, y, 4)
            if (x * 3 + y) % 7 == 0:
                d += 2
            row.append(shade(base, d))
        rows.append(row)
    for (cx, cy) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rows[cy][cx] = RIVET
        rows[cy + 1][cx] = RIVET_LO
    return rows


def body_side():
    """侧 / 端 / 背面：暗钢，仅上棱一条高光。"""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            base = SIDE
            if y == 0:
                base = SIDE_HI
            if y == 15:
                base = (44, 46, 52)
            row.append(shade(base, noise(31, x, y, 4)))
        rows.append(row)
    return rows


def main():
    if not os.path.isdir(OUT):
        raise SystemExit("no texture directory: " + OUT)
    files = (("vault_frame.png", frame()),
             ("vault_body.png", body()),
             ("vault_body_side.png", body_side()))
    for name, rows in files:
        print("wrote %-24s %5d bytes" % (name, png(os.path.join(OUT, name), rows)))


if __name__ == "__main__":
    main()
