#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S12 · 一致性（22.000 s / 528 帧 / 全片 03:30.000–03:52.000）

    0.0–6.0 s   从纯黑淡入。六块屏轮廓重现，这次全部显示同一组数值，钢色勾边
    6.0–14.0 s  六屏同步刷新一次（数值同时跳变），随后静止，画面缓慢拉远
   14.0–22.0 s  六屏依次淡出，只剩一行琥珀标语（34 px / y=270）

这是全片的主题落点，节奏必须"空"下来：不闪、不抖，一切说服力来自
「六块屏读到的字一模一样」。一致用钢色勾边表达，不用绿色。

运行：
    py -3.12 tools/video/promo/shots/shot_12.py --frames 528 --fps 24 \
        --out tools/video/promo/out/build/seg_S12.mp4 \
        --preview tools/video/promo/out/check/shot_12
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

Image = K.Image
BLACK = (0, 0, 0)

DEFAULT_FRAMES = 528
DEFAULT_OUT = "tools/video/promo/out/build/seg_S12.mp4"

SCREEN = 150
COL_CX = [250.0, 480.0, 710.0]
ROW_CY = [165.0, 375.0]
CX, CY = 480.0, 270.0

REFRESH = 168       # 7.0 s：六屏同一帧刷新
EXIT = 336          # 14.0 s：开始依次淡出
TAG_IN = 386        # 16.1 s：最后一块屏退净之后标语才出来，留到最后一帧（约 6 s）

VALUE_A = ("3412", "74%", "34.0%")
VALUE_B = ("3418", "75%", "33.6%")

CAPS = [
    (6, 156, "所有屏幕，读的是同一份状态。"),
    (162, 330, "不是同步得快，是只有一份。"),
]

SLOGAN = "核电厂的每一块屏，都该说同一件事。"


# --------------------------------------------------------------------------
def cap_alpha(f, start, end, fin=4, fout=4):
    if f < start or f >= end:
        return 0.0
    a = 1.0
    if fin:
        a = min(a, (f - start + 1) / float(fin))
    if fout:
        a = min(a, (end - f) / float(fout))
    return K.clamp(a)


def blend(color, alpha):
    a = K.clamp(alpha)
    return tuple(int(round(K.BG[i] + (color[i] - K.BG[i]) * a)) for i in range(3))


def f_line(fr, p0, p1, color=K.SLATE, alpha=1.0, width=1):
    if alpha <= 0.004:
        return
    fr.draw.line([(int(round(p0[0])), int(round(p0[1]))),
                  (int(round(p1[0])), int(round(p1[1])))],
                 fill=blend(color, alpha), width=int(width))


def f_box(fr, box, color=K.SLATE, alpha=1.0, width=1, corner=0):
    if alpha <= 0.004:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 - x0 < 2 or y1 - y0 < 2:
        return
    fill = blend(color, alpha)
    if corner:
        r = min(int(corner), (x1 - x0) // 2, (y1 - y0) // 2)
        fr.draw.rounded_rectangle([x0, y0, x1 - 1, y1 - 1], radius=r, outline=fill,
                                  width=int(width))
    else:
        fr.draw.rectangle([x0, y0, x1 - 1, y1 - 1], outline=fill, width=int(width))


def f_text(fr, xy, s, size=30, color=K.STEEL, alpha=1.0, cn=True, anchor="la",
           shadow=True):
    if not s or alpha <= 0.004:
        return
    font = K.font_cn(size) if cn else K.font_mono(size)
    x, y = int(round(xy[0])), int(round(xy[1]))
    if shadow:
        fr.draw.text((x + 1, y + 2), s, font=font, fill=blend(BLACK, alpha * 0.85),
                     anchor=anchor)
    fr.draw.text((x, y), s, font=font, fill=blend(color, alpha), anchor=anchor)


def fill_rect(fr, box, color, alpha=1.0):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 - x0 < 1 or y1 - y0 < 1:
        return
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), color[:3] + (a,))
    fr.img.paste(layer, (x0, y0), layer)


def blackout(fr, a):
    a = K.clamp(a)
    if a < 0.999:
        fr.flash(BLACK, 1.0 - a)


_VIG_CACHE = {}


def vig_fast(fr, alpha=0.34):
    """与 kit 的 vignette 同结果（边缘乘 1-a），走缓存的黑色 RGBA 层。"""
    a = round(K.clamp(alpha), 3)
    layer = _VIG_CACHE.get(a)
    if layer is None:
        grad = Image.radial_gradient("L").resize((K.W, K.H), Image.BILINEAR)
        layer = Image.new("RGBA", (K.W, K.H), (0, 0, 0, 0))
        layer.putalpha(grad.point(lambda v: int(round(255 * a * (v / 255.0)))))
        _VIG_CACHE[a] = layer
    fr.img.paste(layer, (0, 0), layer)


def grid_fast(fr, alpha=0.045, step=40):
    """与 kit 的 hud_grid 同结构（每 4 格加粗），改用直线直接画。"""
    a = K.clamp(alpha)
    minor = blend(K.SLATE, a)
    major = blend(K.SLATE, min(1.0, a * 1.9))
    for i, x in enumerate(range(0, K.W + 1, step)):
        fr.draw.line([(x, 0), (x, K.H)], fill=(major if i % 4 == 0 else minor), width=1)
    for i, y in enumerate(range(0, K.H + 1, step)):
        fr.draw.line([(0, y), (K.W, y)], fill=(major if i % 4 == 0 else minor), width=1)


def save_png(img, path):
    p = os.path.abspath(path)
    d = os.path.dirname(p)
    if d:
        os.makedirs(d, exist_ok=True)
    if os.path.exists(p):
        try:
            os.remove(p)
        except OSError:
            pass
    img.save(p)


def mix(c, d, t):
    return tuple(int(round(K.lerp(c[i], d[i], t))) for i in range(3))


# --------------------------------------------------------------------------
def zoom_k(f):
    """缓慢拉远：整段只走 13%，快到看不出来，但半分钟后画面确实"退了一步"。"""
    return K.lerp(1.0, 0.87, K.smoothstep(K.clamp((f - 20) / 380.0)))


def screen_rect(i, k):
    cx = CX + (COL_CX[i % 3] - CX) * k
    cy = CY + (ROW_CY[i // 3] - CY) * k
    h = SCREEN * k / 2.0
    return (cx - h, cy - h, cx + h, cy + h)


def screens_alpha(f, i):
    a_in = K.clamp((f - (4 + i * 7)) / 16.0)
    a_out = 1.0 - K.clamp((f - (EXIT + i * 7)) / 14.0)
    return a_in * a_out


def values_at(f):
    return VALUE_B if f >= REFRESH else VALUE_A


def draw_screen(fr, f, i, k):
    a = screens_alpha(f, i)
    if a <= 0.004:
        return
    x0, y0, x1, y1 = screen_rect(i, k)
    w = x1 - x0
    pad = max(6.0, 10.0 * k)
    bk = max(0.7, k)

    # 屏体：比底色亮一档，看得出是一块"实体"
    fill_rect(fr, (x0, y0, x1, y1), mix(K.BG, K.SLATE, 0.16), a)
    f_box(fr, (x0 + 1, y0 + 1, x1 - 1, y1 - 1), color=K.SLATE, alpha=0.45 * a,
          width=1, corner=3)

    temp, stable, risk = values_at(f)

    f_text(fr, (x0 + pad, y0 + 7 * bk), "CORE-0%d" % (i + 1), size=int(round(9 * bk)),
           color=K.SLATE, cn=False, alpha=0.9 * a)

    # 主读数：六块屏逐字相同
    f_text(fr, ((x0 + x1) / 2.0, y0 + 22 * bk), temp, size=int(round(26 * bk)),
           color=K.AMBER, cn=False, anchor="ma", alpha=a)
    f_text(fr, ((x0 + x1) / 2.0, y0 + 52 * bk), "degC", size=int(round(9 * bk)),
           color=K.SLATE, cn=False, anchor="ma", alpha=0.85 * a)

    f_line(fr, (x0 + pad, y0 + 66 * bk), (x1 - pad, y0 + 66 * bk), color=K.SLATE,
           alpha=0.35 * a)

    rows = [("稳定度", stable), ("崩溃", risk)]
    for r, (lab, val) in enumerate(rows):
        ry = y0 + 72 * bk + r * 20 * bk
        f_text(fr, (x0 + pad, ry), lab, size=int(round(10 * bk)), color=K.SLATE,
               alpha=0.95 * a)
        f_text(fr, (x1 - pad, ry), val, size=int(round(11 * bk)), color=K.STEEL,
               cn=False, anchor="ra", alpha=0.95 * a)

    # 底部状态条：五格格数相同（一致性靠"一样"，不靠颜色语义）
    seg_y = y0 + 116 * bk
    seg_w = (w - 2 * pad) / 5.0
    for s in range(5):
        sx = x0 + pad + s * seg_w
        fill_rect(fr, (sx + 1, seg_y, sx + seg_w - 3, seg_y + 4 * bk), K.AMBER,
                  0.55 * a)

    # 极慢的内扫线：静止画面的呼吸
    sy = y0 + 18 * bk + (y1 - y0 - 30 * bk) * (((f + i * 37) % 150) / 150.0)
    f_line(fr, (x0 + pad, sy), (x1 - pad, sy), color=K.STEEL, alpha=0.07 * a)

    # 钢色勾边（"一致"的表达：不用绿色）
    edge = 0.45
    if f >= REFRESH:
        edge = K.lerp(0.45, 0.85, K.clamp((f - REFRESH) / 10.0))
    f_box(fr, (x0 - 5, y0 - 5, x1 + 5, y1 + 5), color=K.STEEL, alpha=edge * a,
          width=2, corner=4)


def group_rect(k):
    a = screen_rect(0, k)
    b = screen_rect(5, k)
    return (a[0] - 22, a[1] - 22, b[2] + 22, b[3] + 22)


def draw_group(fr, f, k):
    if f < 30:
        return
    a = K.clamp((f - 30) / 18.0)
    if f >= EXIT:
        a *= 1.0 - K.clamp((f - EXIT) / 40.0)
    if a <= 0.005:
        return
    gx0, gy0, gx1, gy1 = group_rect(k)
    f_box(fr, (gx0, gy0, gx1, gy1), color=K.STEEL, alpha=0.30 * a, width=1, corner=6)
    for (px, py, dx, dy) in ((gx0, gy0, 1, 1), (gx1, gy0, -1, 1),
                             (gx0, gy1, 1, -1), (gx1, gy1, -1, -1)):
        f_line(fr, (px, py), (px + dx * 16, py), color=K.STEEL, alpha=0.55 * a, width=2)
        f_line(fr, (px, py), (px, py + dy * 16), color=K.STEEL, alpha=0.55 * a, width=2)
    f_text(fr, (gx0, gy0 - 16), "FACILITY STATE // 6 SINKS", size=10, color=K.SLATE,
           cn=False, alpha=0.85 * a)


def draw_refresh_sweep(fr, f, k):
    if not (REFRESH <= f < REFRESH + 14):
        return
    t = (f - REFRESH) / 14.0
    gx0, gy0, gx1, gy1 = group_rect(k)
    sy = K.lerp(gy0, gy1, t)
    f_line(fr, (gx0, sy), (gx1, sy), color=K.STEEL, alpha=0.40 * (1.0 - t), width=2)


def draw_slogan(fr, f):
    if f < TAG_IN:
        return
    a = K.clamp((f - TAG_IN) / 12.0)
    if f >= TAG_IN + 6:
        w = 240.0 * K.ease_out_cubic(K.clamp((f - TAG_IN - 6) / 20.0))
        fill_rect(fr, (CX - w / 2, 304, CX + w / 2, 306), K.AMBER, 0.85 * a)
    fr.caption(SLOGAN, alpha=a, size=34, color=K.AMBER, y=270)


# --------------------------------------------------------------------------
def draw_captions(fr, f):
    for start, end, text in CAPS:
        a = cap_alpha(f, start, end)
        if a > 0:
            fr.caption(text, alpha=a, size=30, color=K.STEEL, y=486)


def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()

    k = zoom_k(f)
    grid_a = K.lerp(0.045, 0.025, K.clamp((f - EXIT) / 60.0))
    grid_fast(fr, grid_a)

    draw_group(fr, f, k)
    for i in range(6):
        draw_screen(fr, f, i, k)
    draw_refresh_sweep(fr, f, k)

    vig_fast(fr, 0.34)
    draw_captions(fr, f)
    draw_slogan(fr, f)
    blackout(fr, K.fade_alpha(f, n, head=12, tail=0))
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n).img


def parse_args():
    ap = argparse.ArgumentParser(description="S12 一致性 段渲染器")
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    ap.add_argument("--fps", type=int, default=K.FPS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--preview", default=None)
    return ap.parse_args()


def main():
    args = parse_args()
    n = int(args.frames)
    if n <= 0:
        raise SystemExit("error: --frames must be > 0")
    if n < 24:
        raise SystemExit("error: --frames too small for this shot (min 24)")

    preview_idx = sorted({0, n // 4, n // 2, (3 * n) // 4, n - 1})
    shots = {}

    def gen():
        for f in range(n):
            img = draw_frame(f, n).img
            if f in preview_idx:
                shots[f] = img.copy()
            yield img

    written = K.write_video(gen(), args.out, fps=args.fps, crf=16,
                            progress=96, label="S12")
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))

    if args.preview:
        for f in preview_idx:
            save_png(shots[f], os.path.join(args.preview, "shot_12_%03d.png" % f))
    print("S12 done: %d frames -> %s" % (written, args.out))


if __name__ == "__main__":
    main()
