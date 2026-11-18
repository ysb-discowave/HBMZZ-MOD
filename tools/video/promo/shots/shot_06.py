#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S06 - Two panels (22.000 s / 528 frames / absolute 01:34.000-01:56.000)

    0.0- 7.0 s   Machine control panel: 14 control icons laid out on a 4x4 grid
                 (two cells left empty); the three tabs 运行 / 控件 / 联动 light up
                 in order and each one highlights its own group of icons.
    7.0-14.0 s   The 联动 tab wins: a cable grows from the panel to a second
                 machine block, labelled 监控数据线; a packet runs down the cable.
    14.0-22.0 s  Machine console: three stacked decks fall in (控件层 / 事件层 /
                 节点图层), connectors grow between them, and the bottom right
                 corner counts 14 -> 26.

Transitions: amber flash on the first 6 frames (alpha 0.6 -> 0), hard cut out.

Run:
    py -3.12 tools/video/promo/shots/shot_06.py --frames 528 --fps 24 \
        --out tools/video/promo/out/build/seg_S06.mp4 \
        --preview tools/video/promo/out/check/shot_06
"""

from __future__ import annotations

import argparse
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

DEFAULT_FRAMES = 528
DEFAULT_FPS = 24
DEFAULT_OUT = "tools/video/promo/out/build/seg_S06.mp4"

FLASH_IN = 6

TEX_CP = "hbmzz/textures/models/control_panel/"
TEX_ADD = TEX_CP + "addon/"

# (group, texture) - group d = display, o = operate, t = terminal (drawn by code)
ICONS = [
    ("d", TEX_CP + "display_7seg.png"),
    ("d", TEX_ADD + "meter_vertical_1.png"),
    ("d", TEX_ADD + "nixie.png"),
    ("d", TEX_ADD + "textindicator.png"),
    ("d", TEX_CP + "display_7seg_gui.png"),
    ("d", TEX_ADD + "meter_vertical_-3.png"),
    ("d", TEX_ADD + "meter_vertical_3.png"),
    ("o", TEX_CP + "switch_rotary_toggle_gui.png"),
    ("o", TEX_CP + "knob_control_gui.png"),
    ("o", TEX_CP + "slider_vertical_gui.png"),
    ("o", TEX_CP + "switch_toggle.png"),
    ("o", TEX_CP + "button_push.png"),
    ("o", TEX_ADD + "lever_vertical.png"),
    ("t", None),
]

GROUP_TICK = {"d": K.SLATE, "o": K.STEEL, "t": K.AMBER}

# some library icons are bright white blobs at small size; hold those back
ICON_ALPHA = {TEX_ADD + "textindicator.png": 0.6}

TABS = ("运行", "控件", "联动")
TAB_T = (36, 66, 96)            # frame at which each tab lights

# panel geometry
P_X0, P_Y0, P_X1, P_Y1 = 88, 100, 604, 456
G_X0, G_Y0 = 108, 156
CELL_W, CELL_H, CELL_GX, CELL_GY = 110, 64, 12, 9
EMPTY = (14, 15)

T_PANEL_END = 168
T_LINK_END = 336

CUES = (
    (0, 164, "机器控制面板：能动手的那一块。"),
    (170, 330, "14 种控件，绑一台机器就成了操作台。"),
    (336, 424, "机器控制台：控件、事件、节点图。"),
    (428, 524, "诅咒版整套移植，26 种控件。"),
)


# ---------------------------------------------------------------------------
# local drawing helpers (thin wrappers over PIL, bbox limited so it stays fast)
# ---------------------------------------------------------------------------
def clamp_box(box):
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    x0 = max(0, min(K.W, x0))
    y0 = max(0, min(K.H, y0))
    x1 = max(0, min(K.W, x1))
    y1 = max(0, min(K.H, y1))
    return x0, y0, x1, y1


def fill(fr, box, color, alpha=1.0, corner=0):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = clamp_box(box)
    w, h = x1 - x0, y1 - y0
    if w <= 0 or h <= 0:
        return
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if corner:
        d.rounded_rectangle([0, 0, w - 1, h - 1],
                            radius=min(int(corner), w // 2, h // 2),
                            fill=color[:3] + (a,))
    else:
        d.rectangle([0, 0, w - 1, h - 1], fill=color[:3] + (a,))
    fr.img.paste(layer, (x0, y0), layer)


def line(fr, p0, p1, color=K.AMBER, alpha=1.0, width=2):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    w = max(1, int(round(width)))
    x0 = min(p0[0], p1[0]) - w - 1
    y0 = min(p0[1], p1[1]) - w - 1
    x1 = max(p0[0], p1[0]) + w + 1
    y1 = max(p0[1], p1[1]) + w + 1
    x0, y0, x1, y1 = clamp_box((x0, y0, x1, y1))
    bw, bh = x1 - x0, y1 - y0
    if bw <= 0 or bh <= 0:
        return
    layer = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    ImageDraw.Draw(layer).line(
        [(p0[0] - x0, p0[1] - y0), (p1[0] - x0, p1[1] - y0)],
        fill=color[:3] + (a,), width=w)
    fr.img.paste(layer, (x0, y0), layer)


def txt(fr, xy, s, size=30, color=K.STEEL, cn=True, anchor="la", shadow=True,
        alpha=1.0):
    """Bbox-limited text: same pixels as Frame.text, without a full-frame layer."""
    if not s:
        return
    a = int(round((color[3] if len(color) == 4 else 255) * K.clamp(alpha)))
    if a <= 0:
        return
    font = K.font_cn(size) if cn else K.font_mono(size)
    x, y = int(round(xy[0])), int(round(xy[1]))
    bx0, by0, bx1, by1 = fr.draw.textbbox((x, y), s, font=font, anchor=anchor)
    lx0 = max(0, int(bx0) - 2)
    ly0 = max(0, int(by0) - 2)
    lx1 = min(K.W, int(bx1) + 4)
    ly1 = min(K.H, int(by1) + 4)
    if lx1 <= lx0 or ly1 <= ly0:
        return
    layer = Image.new("RGBA", (lx1 - lx0, ly1 - ly0), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ox, oy = x - lx0, y - ly0
    if shadow:
        d.text((ox + 1, oy + 2), s, font=font, fill=(0, 0, 0, a), anchor=anchor)
    d.text((ox, oy), s, font=font, fill=color[:3] + (a,), anchor=anchor)
    fr.img.paste(layer, (lx0, ly0), layer)


def rect_stroke(fr, box, color=K.STEEL, alpha=1.0, width=1, corner=0):
    x0, y0, x1, y1 = clamp_box(box)
    if x1 - x0 < 2 or y1 - y0 < 2:
        return
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    w, h = x1 - x0, y1 - y0
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if corner:
        d.rounded_rectangle([0, 0, w - 1, h - 1],
                            radius=min(int(corner), w // 2, h // 2),
                            outline=color[:3] + (a,), width=int(width))
    else:
        d.rectangle([0, 0, w - 1, h - 1], outline=color[:3] + (a,), width=int(width))
    fr.img.paste(layer, (x0, y0), layer)


def poly(fr, pts, color, alpha=1.0):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    pts = [(int(round(x)), int(round(y))) for x, y in pts]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x0, y0, x1, y1 = clamp_box((min(xs) - 1, min(ys) - 1, max(xs) + 2, max(ys) + 2))
    bw, bh = x1 - x0, y1 - y0
    if bw <= 0 or bh <= 0:
        return
    layer = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    ImageDraw.Draw(layer).polygon([(x - x0, y - y0) for x, y in pts],
                                  fill=color[:3] + (a,))
    fr.img.paste(layer, (x0, y0), layer)


def cap_alpha(f, start, end, fin=4, fout=4):
    if f < start or f >= end:
        return 0.0
    a = 1.0
    if fin and f - start < fin:
        a = min(a, (f - start) / float(fin))
    if fout and end - f <= fout:
        a = min(a, (end - f) / float(fout))
    return K.smoothstep(a)



_VIG = {}


def vignette(fr, strength=0.30):
    """Cached dark-corner overlay (one masked paste) instead of a per-frame
    multiply - same look, a fraction of the cost."""
    key = round(float(strength), 3)
    layer = _VIG.get(key)
    if layer is None:
        grad = Image.radial_gradient("L").resize((K.W, K.H), Image.BILINEAR)
        a = grad.point(lambda v: int(round(255 * key * (v / 255.0) ** 1.35)))
        layer = Image.merge("RGBA", (Image.new("L", (K.W, K.H), 0),
                                     Image.new("L", (K.W, K.H), 0),
                                     Image.new("L", (K.W, K.H), 0), a))
        _VIG[key] = layer
    fr.img.paste(layer, (0, 0), layer)


def save_png(img, path):
    p = os.path.abspath(path)
    d = os.path.dirname(p)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    if os.path.exists(p):
        try:
            os.remove(p)
        except OSError:
            pass
    img.save(p)


def cell_box(i):
    c, r = i % 4, i // 4
    x = G_X0 + c * (CELL_W + CELL_GX)
    y = G_Y0 + r * (CELL_H + CELL_GY)
    return x, y, x + CELL_W, y + CELL_H


# ---------------------------------------------------------------------------
# part A: machine control panel
# ---------------------------------------------------------------------------
def active_tab(f):
    idx = 0
    for i, tf in enumerate(TAB_T):
        if f >= tf:
            idx = i
    return idx


def draw_panel_body(fr, f, a, dim=1.0):
    """Panel frame + tab strip + 4x4 icon grid."""
    if a <= 0.01:
        return
    # body + tab strip
    fill(fr, (P_X0, P_Y0, P_X1, P_Y1), K.SLATE, 0.055 * a)
    rect_stroke(fr, (P_X0, P_Y0, P_X1, P_Y1), K.STEEL, 0.42 * a * dim, 2, 4)
    line(fr, (P_X0 + 8, P_Y0 + 40), (P_X1 - 8, P_Y0 + 40), K.SLATE, 0.45 * a, 1)

    act = active_tab(f)
    for i, name in enumerate(TABS):
        tx0 = P_X0 + 12 + i * 104
        tx1 = tx0 + 96
        lit = min(1.0, max(0.0, (f - TAB_T[i] + 1) / 6.0)) if f >= TAB_T[i] else 0.0
        on = 1.0 if i == act else 0.0
        if lit > 0:
            fill(fr, (tx0, P_Y0 + 10, tx1, P_Y0 + 36), K.AMBER, 0.10 * lit * a * (0.45 + 0.55 * on))
            line(fr, (tx0, P_Y0 + 37), (tx1, P_Y0 + 37), K.AMBER,
                 (0.35 + 0.65 * on) * lit * a, 2 if on else 1)
        col = K.AMBER if (lit > 0 and on > 0) else K.STEEL
        txt(fr, (tx0 + 48, P_Y0 + 16), name, size=20, color=col,
                anchor="ma", alpha=a * (0.45 + 0.55 * lit) * dim)

    # group of the active tab, used to tint matching cells
    if act == 0:
        hot = "d"
    elif act == 1:
        hot = "o"
    else:
        hot = "all"

    for i, (grp, rel) in enumerate(ICONS):
        if i in EMPTY:
            continue
        appear = 10 + i * 5
        if f < appear:
            continue
        p = K.ease_out_cubic((f - appear) / 12.0)
        a_cell = a * K.clamp(p * 1.6)
        cx0, cy0, cx1, cy1 = cell_box(i)
        cy0 += int(round((1.0 - p) * 8))
        cy1 += int(round((1.0 - p) * 8))

        fill(fr, (cx0, cy0, cx1, cy1), K.SLATE, 0.15 * a_cell)
        edge = 0.30 * a_cell
        if hot == grp or hot == "all":
            edge = 0.52 * a_cell
        rect_stroke(fr, (cx0, cy0, cx1, cy1), K.STEEL, edge, 1, 2)
        # group tick on the top edge
        tick = GROUP_TICK[grp]
        line(fr, (cx0 + 2, cy0 + 2), (cx0 + 22, cy0 + 2), tick,
             (0.55 if hot in (grp, "all") else 0.30) * a_cell, 2)

        box = (cx0 + 10, cy0 + 8, cx1 - 10, cy1 - 6)
        if rel:
            K.Frame.tex(fr, rel, box, alpha=a_cell * ICON_ALPHA.get(rel, 0.95),
                        fit="contain")
        else:
            # terminal cell: drawn, not textured (no off-palette asset for it)
            tx = cx0 + 26
            txt(fr, (tx, cy0 + 16), ">", size=26, color=K.AMBER, cn=False,
                    anchor="la", alpha=a_cell)
            line(fr, (tx + 18, cy0 + 30), (tx + 44, cy0 + 30), K.AMBER, 0.75 * a_cell, 2)
            line(fr, (tx + 18, cy0 + 40), (tx + 34, cy0 + 40), K.STEEL, 0.5 * a_cell, 2)

        txt(fr, (cx1 - 7, cy1 - 14), "%02d" % (i + 1), size=10, color=K.SLATE,
                cn=False, anchor="ra", alpha=a_cell * 0.85, shadow=False)

    # empty slots stay visibly empty (corner marks only)
    for i in EMPTY:
        if i >= len(ICONS):
            continue
        cx0, cy0, cx1, cy1 = cell_box(i)
        for (px, py, dx, dy) in ((cx0 + 5, cy0 + 5, 1, 1), (cx1 - 5, cy0 + 5, -1, 1),
                                 (cx0 + 5, cy1 - 5, 1, -1), (cx1 - 5, cy1 - 5, -1, -1)):
            line(fr, (px, py), (px + 9 * dx, py), K.SLATE, 0.45 * a, 1)
            line(fr, (px, py), (px, py + 9 * dy), K.SLATE, 0.45 * a, 1)


def draw_panel_side(fr, f, a):
    """Right column for part A: name, big count, three group rows."""
    x = 620
    txt(fr, (x, 98), "机器控制面板", size=26, color=K.STEEL, anchor="la", alpha=a)
    txt(fr, (x, 132), "MACHINE CONTROL PANEL", size=12, color=K.SLATE,
            cn=False, anchor="la", alpha=a * 0.9)
    line(fr, (x, 152), (x + 278, 152), K.SLATE, 0.45 * a, 1)

    cnt = 14 * K.ease_out_cubic(K.clamp((f - 4) / 26.0))
    txt(fr, (x - 4, 162), "%d" % int(round(cnt)), size=86, color=K.AMBER,
            cn=False, anchor="la", alpha=a)
    txt(fr, (x, 258), "种控件", size=24, color=K.STEEL, anchor="la", alpha=a * 0.92)

    rows = (("显示件", 7, "d"), ("操作件", 6, "o"), ("终端", 1, "t"))
    for i, (name, n, grp) in enumerate(rows):
        ry = 302 + i * 44
        ap = K.clamp((f - 20 - i * 8) / 12.0)
        col = GROUP_TICK[grp]
        line(fr, (x, ry + 8), (x + 10, ry + 8), col, 0.9 * a * ap, 2)
        txt(fr, (x + 20, ry), name, size=20, color=K.STEEL, anchor="la", alpha=a * ap * 0.9)
        txt(fr, (x + 246, ry - 2), "%d" % n, size=24, color=col, cn=False,
                anchor="ra", alpha=a * ap)
        # bar shows the share of 14
        bw = 200.0 * (n / 14.0)
        line(fr, (x, ry + 26), (x + 246, ry + 26), K.SLATE, 0.35 * a * ap, 2)
        line(fr, (x, ry + 26), (x + bw, ry + 26), col, 0.85 * a * ap, 2)


# ---------------------------------------------------------------------------
# part B: the 联动 cable to a second machine
# ---------------------------------------------------------------------------
CABLE = ((608, 316), (756, 316))


def cable_pts(upto):
    """Partial polyline by arc length fraction."""
    pts = [CABLE[0]]
    total = 0.0
    segs = []
    for p0, p1 in zip(CABLE, CABLE[1:]):
        d = ((p1[0] - p0[0]) ** 2 + (p1[1] - p0[1]) ** 2) ** 0.5
        segs.append((p0, p1, d))
        total += d
    want = total * K.clamp(upto)
    acc = 0.0
    for p0, p1, d in segs:
        if acc + d <= want:
            pts.append(p1)
            acc += d
        else:
            r = (want - acc) / d if d else 0.0
            pts.append((p0[0] + (p1[0] - p0[0]) * r, p0[1] + (p1[1] - p0[1]) * r))
            break
    return pts, total


def point_at(upto):
    pts, _ = cable_pts(upto)
    return pts[-1]


def draw_machine(fr, a, on=0.0):
    """2.5D machine block on the right, front face textured."""
    fx0, fy0, fx1, fy1 = 744, 268, 840, 364
    dx, dy = 22, -22
    poly(fr, ((fx0, fy0), (fx0 + dx, fy0 + dy), (fx1 + dx, fy0 + dy), (fx1, fy0)),
         K.STEEL, 0.16 * a)
    poly(fr, ((fx1, fy0), (fx1 + dx, fy0 + dy), (fx1 + dx, fy1 + dy), (fx1, fy1)),
         K.SLATE, 0.55 * a)
    K.Frame.tex(fr, "hbmzz/textures/blocks/dfc_monitor_front.png",
                (fx0, fy0, fx1, fy1), alpha=a, fit="fill")
    if on > 0:
        fill(fr, (fx0, fy0, fx1, fy1), K.AMBER, 0.10 * on * a)
        fill(fr, (fx0, fy0 + 42, fx0 + 12, fy0 + 54), K.AMBER, 0.55 * on * a)
    rect_stroke(fr, (fx0, fy0, fx1, fy1), K.STEEL, 0.55 * a, 1, 0)
    line(fr, (fx0, fy0), (fx0 + dx, fy0 + dy), K.STEEL, 0.55 * a, 1)
    line(fr, (fx0 + dx, fy0 + dy), (fx1 + dx, fy0 + dy), K.STEEL, 0.55 * a, 1)
    line(fr, (fx1 + dx, fy0 + dy), (fx1 + dx, fy1 + dy), K.STEEL, 0.55 * a, 1)
    line(fr, (fx1 + dx, fy1 + dy), (fx1, fy1), K.STEEL, 0.55 * a, 1)


def draw_link_part(fr, f, a):
    draw_panel_body(fr, f, a, dim=0.92)

    # right column: replaces the counters with the linked machine
    mb = K.clamp((f - 170) / 18.0)
    if mb > 0:
        txt(fr, (620, 98), "绑一台机器", size=26, color=K.STEEL, anchor="la",
                alpha=a * mb)
        txt(fr, (620, 132), "MONITOR DATA LINK", size=12, color=K.SLATE,
                cn=False, anchor="la", alpha=a * mb * 0.9)
        line(fr, (620, 152), (898, 152), K.SLATE, 0.45 * a * mb, 1)
        draw_machine(fr, a * mb, on=K.clamp((f - 210) / 20.0))

    # cable
    g = K.clamp((f - 180) / 26.0)
    if g > 0:
        pts, _ = cable_pts(K.ease_out_cubic(g))
        for p0, p1 in zip(pts, pts[1:]):
            line(fr, p0, p1, K.AMBER, 0.9 * a, 2)
        # port tick at the panel side
        line(fr, (600, 306), (600, 326), K.AMBER, 0.7 * a * g, 2)

    # running packet
    if f > 206:
        u = ((f - 206) % 54) / 54.0
        px, py = point_at(u)
        fill(fr, (px - 3, py - 3, px + 4, py + 4), K.AMBER, 0.95 * a)

    # label
    lb = K.clamp((f - 200) / 14.0)
    if lb > 0:
        fill(fr, (598, 336, 604, 342), K.AMBER, 0.9 * a * lb)
        txt(fr, (612, 332), "监控数据线", size=20, color=K.AMBER, anchor="la",
                alpha=a * lb)


# ---------------------------------------------------------------------------
# part C: machine console, three decks
# ---------------------------------------------------------------------------
SLAB_X0, SLAB_X1 = 116, 560
SLAB_H = 88
DECKS = (
    (3, "节点图层", 88, 372),
    (2, "事件层", 210, 356),
    (1, "控件层", 332, 340),
)


def deck_content(fr, idx, box, a):
    x0, y0, x1, y1 = box
    if idx == 1:
        # control deck: four control icons + a slider
        for i, rel in enumerate((TEX_CP + "knob_control_gui.png",
                                 TEX_CP + "switch_toggle.png",
                                 TEX_CP + "slider_vertical_gui.png",
                                 TEX_CP + "button_push.png")):
            bx = x0 + 36 + i * 84
            K.Frame.tex(fr, rel, (bx, y0 + 16, bx + 44, y0 + 62), alpha=a * 0.9)
        line(fr, (x0 + 356, y0 + 44), (x0 + 440, y0 + 44), K.SLATE, 0.6 * a, 2)
        fill(fr, (x0 + 388, y0 + 38, x0 + 400, y0 + 50), K.AMBER, 0.9 * a)
    elif idx == 2:
        # event deck: 当 / 若 / 则 chips, output port aligned with the connector
        for i, name in enumerate(("当", "若", "则")):
            cx = x0 + 20 + i * 96
            fill(fr, (cx, y0 + 24, cx + 54, y0 + 64), K.AMBER, (0.10 + 0.06 * i) * a)
            rect_stroke(fr, (cx, y0 + 24, cx + 54, y0 + 64), K.AMBER, 0.55 * a, 1, 2)
            txt(fr, (cx + 27, y0 + 33), name, size=20, color=K.STEEL, anchor="ma",
                    alpha=a)
            if i < 2:
                line(fr, (cx + 60, y0 + 44), (cx + 90, y0 + 44), K.STEEL, 0.5 * a, 2)
        # 则 -> output port (x = 470 absolute, where the connector hangs)
        line(fr, (x0 + 254, y0 + 44), (x0 + 348, y0 + 44), K.AMBER, 0.7 * a, 2)
        fill(fr, (x0 + 348, y0 + 38, x0 + 360, y0 + 50), K.AMBER, 0.85 * a)
    else:
        # node deck: three node chips wired together
        nodes = ((x0 + 24, y0 + 24), (x0 + 160, y0 + 44), (x0 + 300, y0 + 16))
        for i, (nx, ny) in enumerate(nodes):
            fill(fr, (nx, ny, nx + 54, ny + 34), K.SLATE, 0.22 * a)
            rect_stroke(fr, (nx, ny, nx + 54, ny + 34), K.STEEL, 0.5 * a, 1, 2)
            fill(fr, (nx + 6, ny + 6, nx + 12, ny + 12), K.AMBER, 0.85 * a)
            txt(fr, (nx + 16, ny + 8), "N%d" % (i + 1), size=12, color=K.STEEL,
                    cn=False, anchor="la", alpha=a * 0.85)
        for (a0, a1) in ((0, 1), (1, 2)):
            p0 = (nodes[a0][0] + 54, nodes[a0][1] + 17)
            p1 = (nodes[a1][0], nodes[a1][1] + 17)
            line(fr, p0, p1, K.AMBER, 0.55 * a, 1)


def draw_console_part(fr, f, a):
    for idx, name, y, start in DECKS:
        p = K.ease_out_cubic(K.clamp((f - start) / 16.0))
        if p <= 0:
            continue
        y0 = int(round(K.lerp(y - 170, y, p)))
        y1 = y0 + SLAB_H
        al = a * K.clamp(p * 1.8)
        fill(fr, (SLAB_X0, y0, SLAB_X1, y1), K.SLATE, 0.10 * al)
        rect_stroke(fr, (SLAB_X0, y0, SLAB_X1, y1), K.STEEL, 0.45 * al, 2, 3)
        line(fr, (SLAB_X0, y0), (SLAB_X0 + 4, y0), K.AMBER, 0.9 * al, 2)
        # index + name to the right of the deck
        txt(fr, (SLAB_X1 + 18, y0 + 6), "0%d" % idx, size=14, color=K.AMBER,
                cn=False, anchor="la", alpha=al * 0.85)
        txt(fr, (SLAB_X1 + 18, y0 + 24), name, size=22, color=K.STEEL, anchor="la",
                alpha=al)
        ca = al * K.clamp((p - 0.7) / 0.3)
        if ca > 0:
            deck_content(fr, idx, (SLAB_X0, y0, SLAB_X1, y1), ca)

    # connectors between decks grow upward (decks landed at 388)
    g = K.clamp((f - 388) / 16.0)
    if g > 0:
        for x in (196, 470):
            for (ya, yb) in ((176, 210), (298, 332)):
                mid = K.lerp(yb, ya, K.ease_out_cubic(g))
                line(fr, (x, yb), (x, mid), K.AMBER, 0.6 * a, 1)
                if g >= 1.0:
                    fill(fr, (x - 3, ya - 3, x + 4, ya + 4), K.AMBER, 0.9 * a)

    # counter block bottom right
    cb = K.clamp((f - 380) / 16.0)
    if cb > 0:
        x = 688
        line(fr, (x, 92), (x, 296), K.SLATE, 0.45 * a * cb, 1)
        txt(fr, (x + 22, 100), "诅咒版移植", size=22, color=K.STEEL, anchor="la",
                alpha=a * cb)
        txt(fr, (x + 22, 130), "CE PORT", size=12, color=K.SLATE, cn=False,
                anchor="la", alpha=a * cb * 0.9)
        cnt = 26 * K.ease_out_cubic(cb)
        txt(fr, (x + 16, 152), "%d" % int(round(cnt)), size=92, color=K.AMBER,
                cn=False, anchor="la", alpha=a)
        txt(fr, (x + 22, 250), "种控件", size=22, color=K.STEEL, anchor="la",
                alpha=a * cb * 0.92)
        # 真值：ControlRegistry.init() 基础 14 种 + initAddonControls() 附属 11 种
        # + 自研终端 1 种（registry 名 "terminal"，非 leafia_*；docs/reference.html 同口径）
        txt(fr, (x + 22, 288), "CE 14 + 附属 11 + 终端 1", size=14, color=K.STEEL,
                cn=True, anchor="la", alpha=a * cb * 0.75)


# ---------------------------------------------------------------------------
# captions / assembly
# ---------------------------------------------------------------------------
def draw_captions(fr, f):
    for s, e, text in CUES:
        a = cap_alpha(f, s, e)
        if a > 0:
            fr.caption(text, alpha=a, size=30, color=K.STEEL)


def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()
    a = 1.0
    fr.hud_grid(alpha=0.07)

    if f < T_PANEL_END:
        a_in = K.clamp((f + 6) / 18.0)
        draw_panel_body(fr, f, a_in)
        draw_panel_side(fr, f, a_in)
    elif f < T_LINK_END:
        draw_link_part(fr, f, 1.0)
    else:
        draw_console_part(fr, f, 1.0)

    draw_captions(fr, f)
    fr.scanlines(alpha=0.04)
    vignette(fr, 0.30)
    if f < FLASH_IN:
        fr.flash(K.AMBER, 0.6 * (1.0 - f / float(FLASH_IN)))
    return fr


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="render S06 (two panels)")
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    ap.add_argument("--fps", type=int, default=DEFAULT_FPS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--preview", default="")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    n = int(args.frames)
    if n <= 0:
        print("error: --frames must be > 0", file=sys.stderr)
        return 2
    marks = sorted({0, n // 4, n // 2, (3 * n) // 4, n - 1})
    preview = args.preview.strip()

    def gen():
        for f in range(n):
            fr = draw_frame(f, n)
            if preview and f in marks:
                save_png(fr.img, os.path.join(preview, "f%04d.png" % f))
            yield fr.img

    written = K.write_video(gen(), args.out, fps=args.fps, crf=16,
                            progress=120, label="S06")
    if written != n:
        print("error: wrote %d frames, expected %d" % (written, n), file=sys.stderr)
        return 1
    print("S06 ok: %d frames -> %s" % (written, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
