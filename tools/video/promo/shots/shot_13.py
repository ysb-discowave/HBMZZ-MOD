#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S13 · 收束（12.000 s / 288 帧 / 全片 03:52.000–04:04.000）

    0.0–4.0 s   HBMZZ lockup 居中，琥珀横杠从左点亮到右
    4.0–9.0 s   三行信息依次淡入（两行许可分开写，绝不混成一句）
    9.0–12.0 s  底部信息条 y≈470–500，与片尾升起的第一行字同一水平带

转场：末 12 帧淡到**纯黑**（CONTRACT §4.1：片尾首帧是黑的）。底部信息条的淡出
与整体淡黑同步，不提前消失、也不留到最后（视觉接力靠同一个 y 带）。

运行：
    py -3.12 tools/video/promo/shots/shot_13.py --frames 288 --fps 24 \
        --out tools/video/promo/out/build/seg_S13.mp4 \
        --preview tools/video/promo/out/check/shot_13
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

Image = K.Image
BLACK = (0, 0, 0)

DEFAULT_FRAMES = 288
DEFAULT_OUT = "tools/video/promo/out/build/seg_S13.mp4"

CX = 480.0
WORDMARK = "HBMZZ"
WORD_SIZE = 112
WORD_Y = 138          # 文字顶端
BAR_Y = 254           # 琥珀横杠

LINES = [
    (100, "Minecraft 1.12.2 · Forge · GPL-3.0-only", 20, K.STEEL, 300, 1.0),
    (122, "附属包 PolyForm Shield 1.0.0 · 双向软依赖", 20, K.STEEL, 332, 0.95),
    (144, "DFC 监控屏 · 机器控制面板 · 视频广播器", 18, K.STEEL, 366, 0.55),
]

BAR_IN = 216          # 9.0 s：底部信息条

FOOT_L = "HBMZZ玩家社群 1134138830"
FOOT_R = "hbm-zz.ysbdwz.dpdns.org"
FOOT_Y = 496
FOOT_X0, FOOT_X1 = 160, 800


# --------------------------------------------------------------------------
def blend(color, alpha):
    a = K.clamp(alpha)
    return tuple(int(round(K.BG[i] + (color[i] - K.BG[i]) * a)) for i in range(3))


def f_line(fr, p0, p1, color=K.SLATE, alpha=1.0, width=1):
    if alpha <= 0.004:
        return
    fr.draw.line([(int(round(p0[0])), int(round(p0[1]))),
                  (int(round(p1[0])), int(round(p1[1])))],
                 fill=blend(color, alpha), width=int(width))


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


def vig_fast(fr, alpha=0.30):
    """与 kit 的 vignette 同结果（边缘乘 1-a），走缓存的黑色 RGBA 层。"""
    a = round(K.clamp(alpha), 3)
    layer = _VIG_CACHE.get(a)
    if layer is None:
        grad = Image.radial_gradient("L").resize((K.W, K.H), Image.BILINEAR)
        layer = Image.new("RGBA", (K.W, K.H), (0, 0, 0, 0))
        layer.putalpha(grad.point(lambda v: int(round(255 * a * (v / 255.0)))))
        _VIG_CACHE[a] = layer
    fr.img.paste(layer, (0, 0), layer)


def grid_fast(fr, alpha=0.035, step=40):
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


# --------------------------------------------------------------------------
def draw_lockup(fr, f):
    a = 0.30 + 0.70 * K.clamp(f / 10.0)
    f_text(fr, (CX, WORD_Y), WORDMARK, size=WORD_SIZE, color=K.STEEL, cn=False,
           anchor="ma", alpha=a)

    w = 336.0
    grow = K.ease_out_cubic(K.clamp((f - 6) / 58.0))
    if grow > 0:
        half = w * grow / 2.0
        fill_rect(fr, (CX - half, BAR_Y, CX + half, BAR_Y + 4), K.AMBER, 0.95 * a)
        if grow < 1.0:
            # 正在点亮的那个头，亮一点
            fill_rect(fr, (CX + half - 10, BAR_Y - 1, CX + half, BAR_Y + 5), K.STEEL,
                      0.85 * a)


def draw_lines(fr, f):
    for start, text, size, color, y, dim in LINES:
        a = K.clamp((f - start) / 14.0)
        if a <= 0.004:
            continue
        f_text(fr, (CX, y), text, size=size, color=color, anchor="ma",
               alpha=a * dim)


def draw_footer(fr, f):
    if f < BAR_IN:
        return
    a = K.clamp((f - BAR_IN) / 14.0)
    if a <= 0.004:
        return
    f_line(fr, (FOOT_X0, 462), (FOOT_X1, 462), color=K.SLATE, alpha=0.45 * a)
    f_text(fr, (FOOT_X0, FOOT_Y), FOOT_L, size=19, color=K.STEEL, anchor="ls",
           alpha=a)
    f_text(fr, (FOOT_X1, FOOT_Y), FOOT_R, size=17, color=K.STEEL, cn=False,
           anchor="rs", alpha=0.80 * a)


# --------------------------------------------------------------------------
def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()
    grid_fast(fr, 0.035)

    draw_lockup(fr, f)
    draw_lines(fr, f)
    draw_footer(fr, f)

    vig_fast(fr, 0.30)
    blackout(fr, K.fade_alpha(f, n, head=0, tail=12))
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n).img


def parse_args():
    ap = argparse.ArgumentParser(description="S13 收束 段渲染器")
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
                            progress=72, label="S13")
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))

    if args.preview:
        for f in preview_idx:
            save_png(shots[f], os.path.join(args.preview, "shot_13_%03d.png" % f))
    print("S13 done: %d frames -> %s" % (written, args.out))


if __name__ == "__main__":
    main()
