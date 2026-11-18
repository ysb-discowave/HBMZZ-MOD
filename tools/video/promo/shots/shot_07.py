#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S07 - Terminal window (16.000 s / 384 frames / absolute 01:56.000-02:12.000)

    0.0- 4.0 s   The terminal window unfolds from the middle of the frame; the
                 bottom right resize grip is dragged and the window gets bigger.
    4.0- 8.0 s   The left control column lights up (power + four views); the bottom
                 wall-screen row lights 宽 / 高 / 复位.
    8.0-12.0 s   The preview pane fades in and shows the very same canvas that the
                 wall screen on the left is drawing - same pixels, same frame.
   12.0-16.0 s   A burn-down readout floats up from the bottom edge, counting down.

Window chrome is drawn in code: dark fill + 1px steel hairline. `gui_base.png` is
never scaled up (it carries coloured noise pixels).

Run:
    py -3.12 tools/video/promo/shots/shot_07.py --frames 384 --fps 24 \
        --out tools/video/promo/out/build/seg_S07.mp4 \
        --preview tools/video/promo/out/check/shot_07
"""

from __future__ import annotations

import argparse
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

DEFAULT_FRAMES = 384
DEFAULT_FPS = 24
DEFAULT_OUT = "tools/video/promo/out/build/seg_S07.mp4"

WIN0 = (376.0, 238.0, 584.0, 298.0)
WIN1 = (120.0, 76.0, 720.0, 440.0)
WIN2 = (78.0, 52.0, 792.0, 462.0)

MX = 0.5   # canvas animation speed multiplier

CUES = (
    (0, 96, "终端，一块能拖的窗口。"),
    (100, 192, "左边控制，底边管墙屏尺寸。"),
    (196, 288, "右侧预览，跑的是同一套画布。"),
    (292, 380, "融毁还有多久，它替你算。"),
)

# 七路灯柱真值：DfcScreen.java:954
BARS = ("STA", "ABS", "PLS", "EMT", "INJ", "EXC", "CMT")


# ---------------------------------------------------------------------------
# local drawing helpers
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


# ---------------------------------------------------------------------------
# the shared canvas: drawn once per frame, pasted twice (wall screen + preview)
# ---------------------------------------------------------------------------
def make_canvas(f, w, h):
    img = Image.new("RGB", (w, h), K.BG)
    d = ImageDraw.Draw(img)
    t = f * MX

    # engineering grid (BG lifted a third of the way to SLATE - never full slate)
    grid = tuple(int(K.BG[i] + (K.SLATE[i] - K.BG[i]) * 0.42) for i in range(3))
    step = 16
    for x in range(step, w, step):
        d.line([(x, 0), (x, h)], fill=grid, width=1)
    for y in range(step, h, step):
        d.line([(0, y), (w, y)], fill=grid, width=1)

    # title strip
    d.rectangle([0, 0, w - 1, 21], fill=(18, 22, 28))
    d.line([(0, 21), (w, 21)], fill=K.SLATE, width=1)
    d.text((8, 5), "AHBSSMFI-OS | 01  核心 · 堆芯遥测", font=K.font_cn(11), fill=K.STEEL)
    d.text((w - 62, 6), "ONLINE", font=K.font_mono(10), fill=K.AMBER)

    # temperature
    base = 3412
    d.text((10, 28), "温度", font=K.font_cn(11), fill=K.SLATE)
    d.text((8, 40), "%d" % base, font=K.font_mono(38), fill=K.AMBER)
    d.text((8, 82), "\u2103 \u00b7 熔点 6000\u2103", font=K.font_cn(10), fill=K.STEEL)

    # stability
    stab = 74
    d.text((150, 28), "稳定度", font=K.font_cn(11), fill=K.SLATE)
    d.text((148, 42), "%d%%" % stab, font=K.font_mono(24), fill=K.STEEL)
    bx0, bx1 = 150, 288
    d.line([(bx0, 74), (bx1, 74)], fill=K.SLATE, width=2)
    d.line([(bx0, 74), (bx0 + int((bx1 - bx0) * stab / 100.0), 74)], fill=K.AMBER, width=2)

    # collapse risk
    risk = 34.0
    d.text((150, 104), "崩溃风险", font=K.font_cn(11), fill=K.SLATE)
    d.text((148, 116), "%.1f%%" % risk, font=K.font_mono(20), fill=K.STEEL)
    d.line([(bx0, 148), (bx1, 148)], fill=K.SLATE, width=2)
    d.line([(bx0, 148), (bx0 + int((bx1 - bx0) * risk / 100.0), 148)],
           fill=K.AMBER, width=2)

    # seven structure bars
    area_x0, area_x1 = 310, w - 12
    area_y0, area_y1 = 30, h - 26
    d.text((area_x0, 24), "七路结构", font=K.font_cn(10), fill=K.SLATE)
    n = len(BARS)
    gap = (area_x1 - area_x0) / float(n)
    for i, name in enumerate(BARS):
        cx = int(area_x0 + gap * (i + 0.5))
        v = 0.42 + 0.30 * (0.5 + 0.5 * math.sin(t / 9.0 + i * 0.9))
        bh = int((area_y1 - area_y0 - 18) * v)
        d.line([(cx, area_y1 - 14), (cx, area_y1 - 14 - (area_y1 - area_y0 - 18))],
               fill=grid, width=6)
        d.line([(cx, area_y1 - 14), (cx, area_y1 - 14 - bh)], fill=K.AMBER, width=6)
        d.text((cx - 9, area_y1 - 11), name, font=K.font_mono(7), fill=K.STEEL)

    # bottom data row
    d.line([(0, h - 24), (w, h - 24)], fill=K.SLATE, width=1)
    d.text((8, h - 20), "储能 50.00 kSPK", font=K.font_cn(9), fill=K.STEEL)
    d.text((140, h - 20), "输出 82.00 kSPK/s", font=K.font_cn(9), fill=K.STEEL)
    d.text((w - 118, h - 20), "增益 1.180", font=K.font_cn(9), fill=K.STEEL)

    # sweeping scan line
    sy = int((t * 2.4) % max(1, h - 30))
    d.line([(0, sy + 22), (w, sy + 22)], fill=(38, 43, 50), width=1)
    return img


# ---------------------------------------------------------------------------
# window geometry
# ---------------------------------------------------------------------------
def win_rect(f):
    if f < 6:
        box = WIN0
    elif f < 46:
        p = K.ease_out_cubic((f - 6) / 40.0)
        box = tuple(K.lerp(WIN0[i], WIN1[i], p) for i in range(4))
    elif f < 56:
        box = WIN1
    elif f < 104:
        p = K.smoothstep((f - 56) / 48.0)
        box = tuple(K.lerp(WIN1[i], WIN2[i], p) for i in range(4))
    else:
        box = WIN2
    return tuple(int(round(v)) for v in box)


def chrome(fr, f, box, a):
    wx0, wy0, wx1, wy1 = box
    fill(fr, (wx0, wy0, wx1, wy1), K.SLATE, 0.07 * a)
    fill(fr, (wx0, wy0, wx1, wy0 + 26), K.SLATE, 0.14 * a)
    line(fr, (wx0, wy0 + 26), (wx1, wy0 + 26), K.SLATE, 0.8 * a, 1)
    rect_stroke(fr, (wx0, wy0, wx1, wy1), K.STEEL, 0.55 * a, 1)
    fill(fr, (wx0 + 1, wy0 + 1, wx0 + 4, wy0 + 25), K.AMBER, 0.9 * a)

    # title chrome only once the window is wide enough to hold it
    head = K.clamp(((wx1 - wx0) - 210) / 150.0)
    if head > 0:
        txt(fr, (wx0 + 14, wy0 + 4), "HBMZZ 终端", size=16, color=K.STEEL,
            anchor="la", alpha=a * head)
        if (wx1 - wx0) > 460:
            txt(fr, (wx0 + 130, wy0 + 8), "TERMINAL · SERVER AUTHORITATIVE", size=10,
                color=K.SLATE, cn=False, anchor="la", alpha=a * head * 0.9)
        for i in range(3):
            fill(fr, (wx1 - 18 - i * 14, wy0 + 10, wx1 - 10 - i * 14, wy0 + 18),
                 K.STEEL, 0.45 * a * head)


def resize_grip(fr, f, box, a):
    wx0, wy0, wx1, wy1 = box
    gx, gy = wx1 - 3, wy1 - 3
    dragging = 56 <= f < 104
    for i in range(3):
        o = 4 + i * 6
        line(fr, (gx - o, gy), (gx, gy - o), K.AMBER if dragging else K.STEEL,
             0.9 * a if dragging else 0.5 * a, 2)
    if dragging:
        # the two edges that are moving, traced at the previous size
        line(fr, (WIN1[2], WIN1[1]), (WIN1[2], WIN1[3]), K.AMBER, 0.42 * a, 1)
        line(fr, (WIN1[0], WIN1[3]), (WIN1[2], WIN1[3]), K.AMBER, 0.42 * a, 1)
        wpx = int(round(wx1 - wx0))
        hpx = int(round(wy1 - wy0))
        txt(fr, (wx1 - 6, wy1 + 6), "%d x %d" % (wpx, hpx), size=12, color=K.AMBER,
                cn=False, anchor="ra", alpha=a)
        # drag cursor
        cx, cy = gx - 2, gy - 2
        line(fr, (cx - 8, cy), (cx + 8, cy), K.AMBER, 0.9 * a, 1)
        line(fr, (cx, cy - 8), (cx, cy + 8), K.AMBER, 0.9 * a, 1)


# ---------------------------------------------------------------------------
# panes
# ---------------------------------------------------------------------------
def left_column(fr, f, box, a):
    wx0, wy0, wx1, wy1 = box
    x0 = wx0 + 12
    x1 = wx0 + 144
    y = wy0 + 38

    # power
    on = K.clamp((f - 100) / 8.0)
    fill(fr, (x0, y, x1, y + 32), K.AMBER, 0.10 * on * a)
    rect_stroke(fr, (x0, y, x1, y + 32), K.AMBER if on > 0.5 else K.STEEL,
                (0.65 if on > 0.5 else 0.35) * a, 1, 2)
    fill(fr, (x0 + 10, y + 13, x0 + 16, y + 19), K.AMBER, (0.35 + 0.6 * on) * a)
    txt(fr, (x0 + 26, y + 8), "电源", size=16, color=K.STEEL, anchor="la",
            alpha=a * (0.55 + 0.45 * on))

    # four views
    names = ("视图 1", "视图 2", "视图 3", "视图 4")
    for i in range(4):
        ty = y + 40 + i * 40
        lit = K.clamp((f - (112 + i * 12)) / 7.0)
        fill(fr, (x0, ty, x1, ty + 32), K.SLATE, (0.06 + 0.10 * lit) * a)
        rect_stroke(fr, (x0, ty, x1, ty + 32), K.STEEL, (0.28 + 0.4 * lit) * a, 1, 2)
        fill(fr, (x0 + 10, ty + 13, x0 + 16, ty + 19), K.STEEL,
             (0.30 + 0.6 * lit) * a)
        txt(fr, (x0 + 26, ty + 8), names[i], size=15, color=K.STEEL, anchor="la",
                alpha=a * (0.45 + 0.55 * lit))
        if lit > 0.9:
            line(fr, (x1 - 16, ty + 16), (x1 - 6, ty + 16), K.AMBER, 0.8 * a, 2)

    # wall screen thumbnail slot (same canvas, smaller); label is drawn with it
    return (x0, wy0 + 252, x1, wy0 + 326)


def bottom_row(fr, f, box, a):
    wx0, wy0, wx1, wy1 = box
    y0 = wy1 - 44
    y1 = wy1 - 12
    line(fr, (wx0 + 12, y0 - 6), (wx1 - 12, y0 - 6), K.SLATE, 0.45 * a, 1)
    txt(fr, (wx0 + 12, y0 + 8), "墙屏", size=14, color=K.STEEL, anchor="la",
            alpha=a * 0.75)

    def ctrl(x, label, value, lit):
        txt(fr, (x, y0 + 8), label, size=14, color=K.STEEL, anchor="la",
                alpha=a * (0.5 + 0.5 * lit))
        bx = x + 22
        for i, glyph in enumerate(("-", "+")):
            gx = bx + i * 52
            fill(fr, (gx, y0 + 2, gx + 18, y0 + 20), K.SLATE, (0.10 + 0.16 * lit) * a)
            rect_stroke(fr, (gx, y0 + 2, gx + 18, y0 + 20), K.STEEL,
                        (0.35 + 0.4 * lit) * a, 1, 2)
            txt(fr, (gx + 9, y0 + 4), glyph, size=14, color=K.STEEL, cn=False,
                    anchor="ma", alpha=a * (0.55 + 0.45 * lit))
        fill(fr, (bx + 20, y0 + 2, bx + 50, y0 + 20), K.AMBER, 0.10 * lit * a)
        rect_stroke(fr, (bx + 20, y0 + 2, bx + 50, y0 + 20), K.STEEL,
                    (0.32 + 0.38 * lit) * a, 1, 2)
        txt(fr, (bx + 35, y0 + 3), value, size=16, color=K.AMBER, cn=False,
                anchor="ma", alpha=a * (0.4 + 0.6 * lit))

    lw = K.clamp((f - 156) / 8.0)
    lh = K.clamp((f - 166) / 8.0)
    ctrl(wx0 + 56, "宽", "2", lw)
    ctrl(wx0 + 206, "高", "1", lh)

    lr = K.clamp((f - 176) / 8.0)
    rx0 = wx0 + 356
    fill(fr, (rx0, y0 + 2, rx0 + 66, y0 + 20), K.SLATE, (0.08 + 0.12 * lr) * a)
    rect_stroke(fr, (rx0, y0 + 2, rx0 + 66, y0 + 20), K.AMBER if lr > 0.5 else K.STEEL,
                (0.4 + 0.4 * lr) * a, 1, 2)
    txt(fr, (rx0 + 33, y0 + 4), "复位", size=13, color=K.STEEL, anchor="ma",
            alpha=a * (0.5 + 0.5 * lr))

    txt(fr, (wx1 - 12, y0 + 8), "服务端权威", size=12, color=K.SLATE, anchor="ra",
            alpha=a * 0.85)
    return y0, y1


def countdown(fr, f, box, a):
    wx0, wy0, wx1, wy1 = box
    p = K.clamp((f - 288) / 14.0)
    if p <= 0:
        return
    rise = (1.0 - K.ease_out_cubic(p)) * 34.0
    y0 = wy1 - 78 + rise
    y1 = y0 + 30
    x0, x1 = wx0 + 156, wx0 + 346
    fill(fr, (x0, y0, x1, y1), K.BG, 0.88 * a)
    rect_stroke(fr, (x0, y0, x1, y1), K.AMBER, 0.60 * a, 1, 2)
    fill(fr, (x0 + 8, y0 + 12, x0 + 13, y0 + 17), K.AMBER, 0.9 * a)
    txt(fr, (x0 + 20, y0 + 4), "融毁倒计时", size=12, color=K.STEEL, anchor="la",
            alpha=a * 0.9)
    secs = 42 - int((f - 288) / 12)
    txt(fr, (x1 - 10, y0 + 2), "00:%02d" % max(0, secs), size=20, color=K.AMBER,
            cn=False, anchor="ra", alpha=a)
    # shrinking bar
    frac = K.clamp((secs - 30) / 12.0)
    bar_w = x1 - x0 - 16
    line(fr, (x0 + 8, y1 - 5), (x0 + 8 + int(bar_w * frac), y1 - 5),
         K.AMBER, 0.55 * a, 2)


# ---------------------------------------------------------------------------
# frame
# ---------------------------------------------------------------------------
def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()
    fr.hud_grid(alpha=0.06)

    box = win_rect(f)
    chrome_a = K.clamp((f + 5) / 10.0)
    chrome(fr, f, box, chrome_a)

    content_a = K.clamp((f - 30) / 14.0)
    canvas = None
    if content_a > 0:
        wx0, wy0, wx1, wy1 = box
        thumb = left_column(fr, f, box, content_a)
        bottom_row(fr, f, box, content_a)

        # preview pane
        px0, py0, px1, py1 = wx0 + 156, wy0 + 36, wx1 - 12, wy1 - 84
        pw, ph = px1 - px0, py1 - py0
        cw = int(min(pw - 8, (ph - 8) * 16 / 9.0))
        ch = int(round(cw * 9 / 16.0))
        cx0 = px0 + (pw - cw) // 2
        cy0 = py0 + (ph - ch) // 2

        pa = K.clamp((f - 196) / 16.0)
        if pa <= 0:
            # preview slot, still empty: a dim 16:9 frame waiting for the canvas
            rect_stroke(fr, (cx0, cy0, cx0 + cw, cy0 + ch), K.STEEL, 0.20 * content_a, 1, 2)
            txt(fr, (cx0 + cw // 2, cy0 + ch // 2 - 6), "PREVIEW", size=11, cn=False,
                    color=K.SLATE, anchor="ma", alpha=content_a * 0.8)
        if pa > 0:
            canvas = make_canvas(f, cw, ch)
            layer = canvas.convert("RGBA")
            if pa < 1.0:
                layer.putalpha(layer.getchannel("A").point(lambda v: int(v * pa)))
            fr.img.paste(layer, (cx0, cy0), layer)
            rect_stroke(fr, (cx0, cy0, cx0 + cw, cy0 + ch), K.AMBER, 0.35 * content_a * pa, 1)
            # pane tag sits under the canvas, right aligned (never over the canvas)
            txt(fr, (cx0 + cw, cy0 + ch + 2), "预览 PREVIEW · LIVE", size=9,
                    color=K.SLATE, anchor="ra", alpha=content_a * pa * 0.9)

        # wall screen thumbnail: same canvas, downscaled
        if pa > 0 and canvas is not None:
            tw, th = thumb[2] - thumb[0], thumb[3] - thumb[1]
            small = canvas.resize((tw, th), Image.BILINEAR)
            layer = small.convert("RGBA")
            if pa < 1.0:
                layer.putalpha(layer.getchannel("A").point(lambda v: int(v * pa)))
            fr.img.paste(layer, (thumb[0], thumb[1]), layer)
            rect_stroke(fr, thumb, K.STEEL, 0.45 * content_a * pa, 1)
            txt(fr, (thumb[0], thumb[1] - 14), "现场墙屏", size=12, color=K.SLATE,
                anchor="la", alpha=content_a * pa * 0.95)
            txt(fr, (thumb[2], thumb[1] - 14), "PEER", size=9, color=K.SLATE,
                    cn=False, anchor="ra", alpha=content_a * pa * 0.8)

        countdown(fr, f, box, content_a)

    resize_grip(fr, f, box, chrome_a)

    for s, e, text in CUES:
        alpha = cap_alpha(f, s, e)
        if alpha > 0:
            fr.caption(text, alpha=alpha, size=30, color=K.STEEL)

    fr.scanlines(alpha=0.04)
    vignette(fr, 0.30)
    return fr


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="render S07 (terminal)")
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
                            progress=120, label="S07")
    if written != n:
        print("error: wrote %d frames, expected %d" % (written, n), file=sys.stderr)
        return 1
    print("S07 ok: %d frames -> %s" % (written, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
