#!/usr/bin/env python3
"""Generates the 16x16 block textures the nuclear system needs.

No image library on this machine, so PNG is written by hand: a filter byte per
row, zlib-compressed, RGBA. Everything is procedural - noise, bands, rivets,
diagonal hazard stripes - which keeps the files at a few hundred bytes and means
the look can be reproduced by re-running this instead of hunting for a source
image.

    python tools/alertcheck/make_nuke_textures.py
"""
import os
import random
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "src/main/resources/assets/hbmzz/textures/blocks")


def png(path, rows):
    """rows: list of 16 rows, each 16 (r,g,b,a) tuples."""
    raw = b""
    for row in rows:
        raw += b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(blob)
    return len(blob)


def clamp(value):
    return max(0, min(255, int(value)))


def noise(seed, amount=8):
    rnd = random.Random(seed)
    table = [[rnd.randint(-amount, amount) for _ in range(16)] for _ in range(16)]
    return table


def steel(seed, base=(74, 78, 82), rivets=True, amount=7):
    """A flat machined plate: noise, a darker border, four rivet highlights."""
    n = noise(seed, amount)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = n[y][x]
            edge = x in (0, 15) or y in (0, 15)
            shade = 0.80 if edge else 1.0
            if rivets and (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
                shade = 1.22
            row.append((clamp(base[0] * shade + v), clamp(base[1] * shade + v),
                        clamp(base[2] * shade + v), 255))
        rows.append(row)
    return rows


def hazard(seed):
    """Yellow/black diagonal stripes, the only saturated thing on the bomb."""
    n = noise(seed, 5)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            band = (x + y) % 8
            yellow = band < 4
            base = (208, 168, 36) if yellow else (26, 26, 24)
            v = n[y][x]
            row.append((clamp(base[0] + v), clamp(base[1] + v), clamp(base[2] + v), 255))
        rows.append(row)
    return rows


def lamp(seed, color=(224, 48, 40)):
    n = noise(seed, 6)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            if d > 6.5:
                base = (44, 44, 44)
            elif d > 4.5:
                base = (96, 40, 36)
            else:
                base = color
            v = n[y][x]
            row.append((clamp(base[0] + v), clamp(base[1] + v), clamp(base[2] + v), 255))
        rows.append(row)
    return rows


def panel_front(seed, accent, leds=None, screen=False):
    """A dark instrument face with an accent bar and optional indicator lamps."""
    n = noise(seed, 6)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            base = (34, 38, 42)
            if y in (0, 15) or x in (0, 15):
                base = (24, 26, 29)
            if screen and 2 <= x <= 13 and 3 <= y <= 9:
                base = (18, 26, 22)
            if 10 <= y <= 11 and 3 <= x <= 12:
                base = accent
            v = n[y][x]
            row.append((clamp(base[0] + v), clamp(base[1] + v), clamp(base[2] + v), 255))
        rows.append(row)
    if leds:
        for i, col in enumerate(leds):
            cx = 3 + i * 3
            for dy in (0, 1):
                for dx in (0, 1):
                    rows[6 + dy][cx + dx] = (col[0], col[1], col[2], 255)
    return rows


def main():
    if not os.path.isdir(OUT):
        raise SystemExit("no texture directory: " + OUT)
    files = {
        "nuke_body.png": steel(11, (62, 68, 54)),
        "nuke_nose.png": steel(12, (84, 88, 92)),
        "nuke_stripe.png": hazard(13),
        "nuke_lamp.png": lamp(14),
        "nuke_controller_front.png": panel_front(21, (150, 44, 40), leds=[(230, 60, 50)]),
        "nuke_controller_side.png": steel(22, (66, 70, 74), rivets=False),
        "code_broadcaster_front.png": panel_front(23, (44, 120, 70),
                                                 leds=[(110, 220, 140), (220, 210, 90),
                                                       (230, 150, 60), (230, 80, 70)]),
        "code_broadcaster_side.png": steel(24, (66, 70, 74), rivets=False),
        "nuke_monitor_front.png": panel_front(25, (170, 50, 44), screen=True),
        "alert_display_front.png": panel_front(26, (60, 150, 90), screen=True),
    }
    for name, rows in files.items():
        size = png(os.path.join(OUT, name), rows)
        print("wrote %-28s %5d bytes" % (name, size))


if __name__ == "__main__":
    main()
