#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S08 - Video broadcaster (18.000 s / 432 frames / absolute 02:12.000-02:30.000)

    0.0- 6.0 s   The broadcaster block sits in the middle, a redstone run comes up
                 from the bottom and its signal climbs from dark to full.
    6.0-12.0 s   The signal peaks: every screen around the block is taken over at
                 the same frame, a play mark appears on the block.
   12.0-15.0 s   Right-click config panel: 播放 / 循环 / 目标玩家.
   15.0-18.0 s   Three player chips, two of them highlighted.

Transitions: amber flash on the first 6 frames, hard cut out. No red anywhere -
the "redstone" is drawn in amber, the block texture is its unlit face.

Run:
    py -3.12 tools/video/promo/shots/shot_08.py --frames 432 --fps 24 \
        --out tools/video/promo/out/build/seg_S08.mp4 \
        --preview tools/video/promo/out/check/shot_08
"""

from __future__ import annotations

import argparse
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

DEFAULT_FRAMES = 432
DEFAULT_FPS = 24
DEFAULT_OUT = "tools/video/promo/out/build/seg_S08.mp4"

FLASH_IN = 6

TEX_BLOCK = "hbmzz/textures/blocks/video_broadcaster_front.png"

CUES = (
    (0, 140, "视频广播器。"),
    (146, 286, "红石一亮，全场看同一段。"),
    (292, 356, "视频包丢进 config/hbmzz/videos/。"),
    (360, 426, "谁能看，你说了算。"),
)

# block geometry (front face) and the redstone run
BX0, BY0, BX1, BY1 = 408, 180, 552, 324
DX, DY = 26, -26
RS_X = 480
RS_TOP = BY1 + 4
RS_BOT = 436

SCREENS = (
    (40, 86, 192, 172), (40, 206, 192, 292), (40, 326, 192, 412),
    (768, 86, 920, 172), (768, 206, 920, 292), (768, 326, 920, 412),
)


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


def ellipse(fr, box, color=K.AMBER, alpha=1.0, width=2, filled=False):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = clamp_box(box)
    w, h = x1 - x0, y1 - y0
    if w <= 0 or h <= 0:
        return
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if filled:
        d.ellipse([0, 0, w - 1, h - 1], fill=color[:3] + (a,))
    else:
        d.ellipse([0, 0, w - 1, h - 1], outline=color[:3] + (a,), width=int(width))
    fr.img.paste(layer, (x0, y0), layer)


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


def play_glyph(fr, cx, cy, size, color=K.AMBER, alpha=1.0):
    poly(fr, ((cx - size * 0.38, cy - size * 0.5),
              (cx + size * 0.52, cy),
              (cx - size * 0.38, cy + size * 0.5)), color, alpha)


# ---------------------------------------------------------------------------
# broadcast content: identical on the block HUD and on every wall screen
# ---------------------------------------------------------------------------
def broadcast_content(fr, box, a, t):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    fill(fr, (x0, y0, x1, y1), K.BG, 0.94 * a)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    gs = min(w, h) * 0.30
    play_glyph(fr, cx - 2, cy - 4, gs, K.AMBER, a)
    ellipse(fr, (cx - gs * 0.95, cy - 4 - gs * 0.95, cx + gs * 0.95, cy - 4 + gs * 0.95),
            K.AMBER, 0.45 * a, 1)
    # progress bar
    py = y1 - 12
    line(fr, (x0 + 10, py), (x1 - 10, py), K.SLATE, 0.6 * a, 2)
    frac = ((t * 0.11) % 1.0)
    line(fr, (x0 + 10, py), (x0 + 10 + int((w - 20) * frac), py), K.AMBER, 0.9 * a, 2)
    txt(fr, (x0 + 10, y0 + 6), "PLAY", size=9, color=K.AMBER, cn=False, anchor="la",
            alpha=a * 0.95)
    txt(fr, (x1 - 10, y0 + 6), "50.00", size=9, color=K.STEEL, cn=False,
            anchor="ra", alpha=a * 0.7)


def draw_block(fr, a, lit=0.0, play=0.0):
    fx0, fy0, fx1, fy1 = BX0, BY0, BX1, BY1
    poly(fr, ((fx0, fy0), (fx0 + DX, fy0 + DY), (fx1 + DX, fy0 + DY), (fx1, fy0)),
         K.STEEL, 0.14 * a)
    poly(fr, ((fx1, fy0), (fx1 + DX, fy0 + DY), (fx1 + DX, fy1 + DY), (fx1, fy1)),
         K.SLATE, 0.50 * a)
    K.Frame.tex(fr, TEX_BLOCK, (fx0, fy0, fx1, fy1), alpha=a, fit="fill")
    if lit > 0:
        fill(fr, (fx0, fy0, fx1, fy1), K.AMBER, 0.08 * lit * a)
        # lit top rim
        line(fr, (fx0, fy0), (fx0 + DX, fy0 + DY), K.AMBER, 0.7 * lit * a, 2)
        line(fr, (fx0 + DX, fy0 + DY), (fx1 + DX, fy0 + DY), K.AMBER, 0.7 * lit * a, 2)
    rect_stroke(fr, (fx0, fy0, fx1, fy1), K.STEEL, 0.55 * a, 1, 0)
    line(fr, (fx0, fy0), (fx0 + DX, fy0 + DY), K.STEEL, 0.5 * a, 1)
    line(fr, (fx0 + DX, fy0 + DY), (fx1 + DX, fy0 + DY), K.STEEL, 0.5 * a, 1)
    line(fr, (fx1 + DX, fy0 + DY), (fx1 + DX, fy1 + DY), K.STEEL, 0.5 * a, 1)
    line(fr, (fx1 + DX, fy1 + DY), (fx1, fy1), K.STEEL, 0.5 * a, 1)

    if play > 0:
        cx, cy = (fx0 + fx1) // 2, (fy0 + fy1) // 2
        r = 40
        ellipse(fr, (cx - r, cy - r, cx + r, cy + r), K.AMBER, 0.85 * play * a, 2)


def draw_redstone(fr, f, a):
    """Vertical run with eight rungs that light bottom-up, brightness ramp."""
    bright = K.clamp((f - 10) / 112.0)
    level = 15.0 * bright
    # rail
    fill(fr, (RS_X - 5, RS_TOP, RS_X + 6, RS_BOT), K.SLATE, 0.25 * a)
    rect_stroke(fr, (RS_X - 5, RS_TOP, RS_X + 6, RS_BOT), K.SLATE, 0.45 * a, 1)

    rungs = 8
    span = RS_BOT - RS_TOP
    for i in range(rungs):
        y = RS_BOT - (i + 0.5) * span / rungs
        litp = K.clamp((bright - i / float(rungs)) * 4.0)
        line(fr, (RS_X - 12, y), (RS_X + 12, y), K.AMBER,
             (0.12 + 0.78 * litp) * a, 2)
        fill(fr, (RS_X - 3, y - 3, RS_X + 4, y + 4), K.AMBER,
             (0.10 + 0.85 * litp) * a)

    # readout
    txt(fr, (RS_X + 26, RS_TOP + 4), "%d" % int(round(level)), size=20, color=K.AMBER,
            cn=False, anchor="la", alpha=a * 0.95)
    txt(fr, (RS_X + 26, RS_TOP + 30), "红石信号", size=12, color=K.STEEL, anchor="la",
            alpha=a * 0.75)
    line(fr, (RS_X + 14, RS_TOP + 2), (RS_X + 14, RS_BOT), K.SLATE, 0.35 * a, 1)


# ---------------------------------------------------------------------------
# config panel / player chips
# ---------------------------------------------------------------------------
def draw_config(fr, f, a):
    x0, y0, x1, y1 = 592, 96, 920, 322
    fill(fr, (x0, y0, x1, y1), K.BG, 0.93 * a)
    fill(fr, (x0, y0, x1, y0 + 26), K.SLATE, 0.14 * a)
    rect_stroke(fr, (x0, y0, x1, y1), K.STEEL, 0.5 * a, 1)
    fill(fr, (x0 + 1, y0 + 1, x0 + 4, y0 + 25), K.AMBER, 0.9 * a)
    txt(fr, (x0 + 14, y0 + 4), "视频广播器", size=16, color=K.STEEL, anchor="la",
            alpha=a)
    txt(fr, (x0 + 104, y0 + 8), "CONFIG", size=10, color=K.SLATE, cn=False,
            anchor="la", alpha=a * 0.9)
    line(fr, (x0, y0 + 26), (x1, y0 + 26), K.SLATE, 0.7 * a, 1)

    rows = (("播放", "开"), ("循环", "开"), ("目标玩家", "2 / 3"))
    for i, (name, value) in enumerate(rows):
        ry = y0 + 38 + i * 42
        lit = K.clamp((f - 292 - i * 8) / 10.0)
        txt(fr, (x0 + 16, ry + 4), name, size=16, color=K.STEEL, anchor="la",
                alpha=a * (0.5 + 0.5 * lit))
        if i < 2:
            # toggle
            tx1 = x1 - 20
            tx0 = tx1 - 34
            fill(fr, (tx0, ry + 4, tx1, ry + 22), K.AMBER, 0.14 * lit * a)
            rect_stroke(fr, (tx0, ry + 4, tx1, ry + 22), K.AMBER, 0.7 * lit * a, 1, 2)
            fill(fr, (tx1 - 14, ry + 8, tx1 - 4, ry + 18), K.AMBER, 0.9 * lit * a)
            txt(fr, (tx0 - 10, ry + 5), value, size=13, color=K.AMBER, anchor="ra",
                    alpha=a * lit * 0.9)
        else:
            txt(fr, (x1 - 20, ry + 5), value, size=14, color=K.AMBER, cn=False,
                    anchor="ra", alpha=a * lit * 0.95)

    line(fr, (x0 + 12, y0 + 168), (x1 - 12, y0 + 168), K.SLATE, 0.5 * a, 1)
    txt(fr, (x0 + 16, y0 + 178), "视频包", size=12, color=K.SLATE, anchor="la",
            alpha=a * 0.85)
    txt(fr, (x0 + 16, y0 + 196), "config/hbmzz/videos/", size=12, color=K.STEEL,
            cn=False, anchor="la", alpha=a * 0.9)


def draw_cursor(fr, f, a):
    """Small right-click cursor + label, tying the block to the panel."""
    if a <= 0:
        return
    cx, cy = 604, 336
    poly(fr, ((cx, cy), (cx, cy + 15), (cx + 4, cy + 11), (cx + 7, cy + 17),
              (cx + 10, cy + 15), (cx + 7, cy + 9), (cx + 12, cy + 9)),
         K.STEEL, 0.95 * a)
    ring = 8 + 5 * (0.5 + 0.5 * math.sin(f / 6.0))
    ellipse(fr, (cx - ring + 6, cy - ring + 6, cx + ring + 6, cy + ring + 6),
            K.AMBER, 0.45 * a, 1)
    txt(fr, (cx + 20, cy + 2), "右键", size=12, color=K.AMBER, anchor="la",
            alpha=a * 0.95)


def player_chip(fr, cx, cy, name, lit, a):
    w, h = 92, 116
    x0, y0 = cx - w // 2, cy - h // 2
    fill(fr, (x0, y0, x0 + w, y0 + h), K.SLATE, (0.08 + 0.10 * lit) * a)
    rect_stroke(fr, (x0, y0, x0 + w, y0 + h), K.AMBER if lit > 0.5 else K.STEEL,
                (0.25 + 0.55 * lit) * a, 1, 3)
    hx0, hy0 = cx - 19, y0 + 12
    fill(fr, (hx0, hy0, hx0 + 38, hy0 + 38), K.AMBER if lit > 0.5 else K.SLATE,
         (0.14 + 0.55 * lit) * a, corner=4)
    rect_stroke(fr, (hx0, hy0, hx0 + 38, hy0 + 38), K.STEEL,
                (0.35 + 0.4 * lit) * a, 1, 4)
    # shoulders
    ellipse(fr, (cx - 28, hy0 + 42, cx + 28, hy0 + 92),
            K.AMBER if lit > 0.5 else K.SLATE, (0.12 + 0.4 * lit) * a, 1, filled=True)
    txt(fr, (cx, y0 + h - 18), name, size=12, color=K.STEEL, anchor="ma",
            alpha=a * (0.5 + 0.5 * lit))
    if lit > 0.5:
        fill(fr, (x0 + 6, y0 + 6, x0 + 10, y0 + 10), K.AMBER, 0.9 * a)


# ---------------------------------------------------------------------------
# frame
# ---------------------------------------------------------------------------
def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()
    fr.hud_grid(alpha=0.07)
    t = f / float(DEFAULT_FPS)

    a_in = K.clamp((f + 6) / 18.0)
    bright = K.clamp((f - 10) / 112.0)
    on = K.clamp((f - 146) / 8.0)        # screens taken over
    cfg = K.clamp((f - 288) / 14.0)      # config panel
    ply = K.clamp((f - 356) / 14.0)      # player chips

    # wall screens (dimmed once the config panel arrives)
    dim = 1.0 - 0.72 * cfg
    for i, box in enumerate(SCREENS):
        x0, y0, x1, y1 = box
        fill(fr, box, K.BG, 0.82 * a_in * dim)
        rect_stroke(fr, box, K.STEEL, (0.22 + 0.33 * on) * a_in * dim, 1, 2)
        if on > 0:
            broadcast_content(fr, (x0 + 2, y0 + 2, x1 - 2, y1 - 2), on * a_in * dim, t + i * 0.4)
        else:
            # dead screen: a single dark scan tick
            line(fr, (x0 + 8, y1 - 10), (x1 - 8, y1 - 10), K.SLATE,
                 0.30 * a_in * dim, 1)

    draw_redstone(fr, f, a_in * (1.0 - ply))
    draw_block(fr, a_in, lit=K.clamp(bright * 1.2), play=on)

    # play readout next to the block (hidden once the config panel slides in)
    if on > 0 and cfg <= 0.02:
        txt(fr, (592, 224), "正在播放", size=16, color=K.AMBER, anchor="la",
                alpha=a_in * on)
        line(fr, (592, 248), (736, 248), K.SLATE, 0.55 * a_in * on, 2)
        line(fr, (592, 248), (592 + int(144 * ((t * 0.11) % 1.0)), 248), K.AMBER,
             0.9 * a_in * on, 2)
        secs = 12 + int((f - 146) / 24)
        txt(fr, (592, 256), "00:%02d / 03:40" % secs, size=11, color=K.STEEL,
                cn=False, anchor="la", alpha=a_in * on * 0.85)

    if cfg > 0:
        draw_config(fr, f, a_in * K.clamp(cfg * 1.5))
        draw_cursor(fr, f, a_in * K.clamp(cfg * 1.5) * (1.0 - ply))
        # fade the config out when the player chips come in
        if ply > 0:
            fill(fr, (592, 96, 920, 322), K.BG, 0.88 * ply)

    if ply > 0:
        names = ("玩家 1", "玩家 2", "玩家 3")
        for i, cx in enumerate((300, 480, 660)):
            lit = 1.0 if i in (0, 2) else 0.0
            # stagger the chips in
            p = K.clamp((f - 360 - i * 6) / 10.0)
            if p <= 0:
                continue
            off = int(round((1.0 - K.ease_out_cubic(p)) * 18))
            player_chip(fr, cx, 392 + off, names[i], lit * p, a_in * ply)

    for s, e, text in CUES:
        alpha = cap_alpha(f, s, e)
        if alpha > 0:
            fr.caption(text, alpha=alpha, size=30, color=K.STEEL)

    # screen-takeover flash
    if 146 <= f < 154:
        fr.flash(K.AMBER, 0.22 * (1.0 - (f - 146) / 8.0))
    fr.scanlines(alpha=0.04)
    vignette(fr, 0.30)
    if f < FLASH_IN:
        fr.flash(K.AMBER, 0.6 * (1.0 - f / float(FLASH_IN)))
    return fr


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="render S08 (video broadcaster)")
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
                            progress=120, label="S08")
    if written != n:
        print("error: wrote %d frames, expected %d" % (written, n), file=sys.stderr)
        return 1
    print("S08 ok: %d frames -> %s" % (written, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
