#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S02 主视觉 / Key visual - 240 frames (10.000 s @ 24 fps).

分镜（SEGMENTS.md S02）:
    0.0-2.0  从黑淡入，poster.jpg 缓推 1.00 -> 1.06（无字幕）
    2.0-5.0  HBMZZ lockup 从左侧划入（钢色主标 + 琥珀横杠）
    5.0-7.5  三行功能标签依次点亮
    7.5-10.0 左下角版本小字

全片唯一的海报时刻：安静、慢、留白。海报本身左侧印着主标、标语与版本，
这里的处理是——用一层与底色同色的渐变遮罩压掉左侧印刷字，再由我们自己
重新排出同一套信息（主标 / 三个标签 / 版本），文字全部落在左列，
画面中央的屏幕区不被遮盖。主题句全片只出现两次（S01 提出、S12 回答），
所以海报上那行标语必须压掉。

转场: 首 12 帧淡入；末帧偏暗，S03 硬切。
CLI（CONTRACT §7.2）::

    py -3.12 tools/video/promo/shots/shot_02.py --frames 240 --fps 24 \
        --out tools/video/promo/out/build/seg_S02.mp4 \
        --preview tools/video/promo/out/check/shot_02
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K  # noqa: E402

from PIL import Image, ImageDraw, ImageFilter  # noqa: E402

SEG_ID = "S02"
HEAD_FADE, TAIL_FADE = 12, 0

BLACK = (0, 0, 0)

MARK = "logo-mark-512.png"
BRAND_DIR = os.path.join(K.assets_root(), "..", "..", "..", "..",
                         "docs", "assets", "brand")

LABELS = ("DFC 监控屏", "机器控制面板", "视频广播器")
VERSION = "Minecraft 1.12.2 · Forge · GPL-3.0"

ZOOM_FROM, ZOOM_TO = 1.000, 1.060
LOCK_X = 72
LOCK_T = 2.0          # lockup 划入
BAR_T = 3.0           # 琥珀横杠展开
LABEL_T = 5.0         # 第一个标签
VER_T = 7.6           # 版本小字


# ---------------------------------------------------------------- 基础绘制
def mix(color, t, base=K.BG):
    t = K.clamp(t)
    return tuple(int(round(color[i] * (1.0 - t) + base[i] * t)) for i in range(3))


def fill(fr, box, color, alpha=1.0):
    alpha = K.clamp(alpha)
    if alpha <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 <= x0 or y1 <= y0:
        return
    if alpha >= 1.0:
        fr.draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=color[:3])
        return
    layer = Image.new("RGBA", (x1 - x0, y1 - y0),
                      color[:3] + (int(round(255 * alpha)),))
    fr.img.paste(layer, (x0, y0), layer)


_SCRIM = None
_BLUR_MASK = None


def scrim():
    """左列压暗：x<=260 全遮，260->400 平滑收边。"""
    global _SCRIM
    if _SCRIM is not None:
        return _SCRIM
    layer = Image.new("RGBA", (K.W, K.H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for x in range(0, 402):
        t = K.clamp((x - 260) / 140.0)
        a = int(round(255 * 0.85 * (1.0 - K.smoothstep(t))))
        if a > 0:
            d.line([(x, 0), (x, K.H)], fill=K.BG + (a,))
    _SCRIM = layer
    return _SCRIM


def blur_mask():
    """海报左侧印刷字区域的柔化遮罩（羽毛边）：字糊掉，明暗关系留着。"""
    global _BLUR_MASK
    if _BLUR_MASK is not None:
        return _BLUR_MASK
    m = Image.new("L", (K.W, K.H), 0)
    ImageDraw.Draw(m).rectangle([0, 214, 400, 540], fill=255)
    _BLUR_MASK = m.filter(ImageFilter.GaussianBlur(42))
    return _BLUR_MASK


def suppress_poster_text(fr):
    """把海报自带的左侧大字柔化 + 压暗：主题句全片只出现两次，这里必须抹掉。"""
    m = blur_mask()
    soft = fr.img.filter(ImageFilter.GaussianBlur(9))
    fr.img = Image.composite(soft, fr.img, m)
    fr.draw = ImageDraw.Draw(fr.img)
    s = scrim()
    fr.img.paste(s, (0, 0), s)


_PNG_CACHE = {}


def blit_brand(fr, name, box, alpha=1.0):
    """品牌 PNG（矢量导出，不是像素画）：LANCZOS 缩放。"""
    alpha = K.clamp(alpha)
    if alpha <= 0:
        return
    img = _PNG_CACHE.get(name)
    if img is None:
        img = Image.open(os.path.normpath(os.path.join(BRAND_DIR, name))).convert("RGBA")
        _PNG_CACHE[name] = img
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    w, h = max(1, x1 - x0), max(1, y1 - y0)
    im = img.resize((w, h), Image.LANCZOS)
    if alpha < 1.0:
        im = im.copy()
        im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
    fr.img.paste(im, (x0, y0), im)


# ---------------------------------------------------------------- 各层
def draw_poster(fr, f, n):
    t = f / float(max(1, n - 1))
    zoom = K.lerp(ZOOM_FROM, ZOOM_TO, t)
    fr.poster((0, 0, K.W, K.H), zoom=zoom, alpha=1.0)
    # 偏暗：开头更暗，随画面浮出慢慢提亮一点点（不抢主视觉）
    fr.dim(K.lerp(0.30, 0.22, K.clamp(f / 48.0)))
    suppress_poster_text(fr)


def draw_corners(fr, f, fps):
    """四角工业标记：2.0 s 起 8 帧内画出（细节，不抢画面）。"""
    k = K.ease_out_cubic(K.clamp((f - 2.0 * fps) / 8.0))
    if k <= 0:
        return
    inset, ln = 18, int(round(14 * k))
    col = mix(K.SLATE, 0.25)
    pts = (
        ((inset, inset), (inset + ln, inset)),
        ((inset, inset), (inset, inset + ln)),
        ((K.W - inset, inset), (K.W - inset - ln, inset)),
        ((K.W - inset, inset), (K.W - inset, inset + ln)),
        ((inset, K.H - inset), (inset + ln, K.H - inset)),
        ((inset, K.H - inset), (inset, K.H - inset - ln)),
        ((K.W - inset, K.H - inset), (K.W - inset - ln, K.H - inset)),
        ((K.W - inset, K.H - inset), (K.W - inset, K.H - inset - ln)),
    )
    for p0, p1 in pts:
        fr.hairline(p0, p1, color=col, alpha=1.0, width=1)


def draw_lockup(fr, f, fps):
    """2.0 s：主标从左侧划入（34 帧），3.0 s：琥珀横杠从左展开。"""
    t0 = int(round(LOCK_T * fps))
    if f < t0:
        return
    k = K.ease_out_cubic(K.clamp((f - t0) / 34.0))
    x = K.lerp(-300, LOCK_X, k)
    a = K.clamp((f - t0) / 7.0)

    # 主标 PNG（钢色 + 琥珀，官方主标，不重画）
    blit_brand(fr, MARK, (x, 138, x + 66, 204), alpha=a)

    # 字标：等宽，逐字加 5px 字距
    font = K.font_mono(54)
    cx = x + 82
    for ch in "HBMZZ":
        fr.text((cx, 200), ch, size=54, color=mix(K.STEEL, 0.10 * (1.0 - a)),
                cn=False, anchor="ls", shadow=False)
        cx += font.getlength(ch) + 5

    # 琥珀横杠：从左向右展开
    kb = K.ease_out_cubic(K.clamp((f - BAR_T * fps) / 20.0))
    if kb > 0:
        fr.hairline((x, 226), (x + 226 * kb, 226), color=K.AMBER, alpha=1.0, width=3)


def draw_labels(fr, f, fps):
    """5.0 / 5.25 / 5.5 s：三行功能标签依次点亮。"""
    for i, text in enumerate(LABELS):
        t0 = (LABEL_T + 0.25 * i) * fps
        if f < t0:
            continue
        a = K.smoothstep(K.clamp((f - t0) / 10.0))
        live = K.clamp((f - t0) / 12.0)
        y = 300 + i * 40
        # 标记块先亮，文字随之后到位
        fill(fr, (LOCK_X, y + 7, LOCK_X + 7, y + 14), K.AMBER, 0.30 + 0.70 * live)
        fr.text((LOCK_X + 20, y), text, size=20,
                color=mix(K.STEEL, 0.55 * (1.0 - a), base=K.BG),
                cn=True, shadow=False)


def draw_version(fr, f, fps):
    """7.6 s：左下角版本小字。"""
    t0 = int(round(VER_T * fps))
    if f < t0:
        return
    a = K.smoothstep(K.clamp((f - t0) / 12.0))
    fr.text((LOCK_X, 492), VERSION, size=17,
            color=mix(K.STEEL, 0.30 + 0.35 * (1.0 - a)), cn=False, shadow=False)


# ---------------------------------------------------------------- 主流程
def build(n, fps):
    for f in range(n):
        fr = K.Frame()
        draw_poster(fr, f, n)
        fr.vignette(alpha=0.42)
        fr.scanlines(alpha=0.045)
        draw_corners(fr, f, fps)
        draw_lockup(fr, f, fps)
        draw_labels(fr, f, fps)
        draw_version(fr, f, fps)
        a = K.fade_alpha(f, n, head=HEAD_FADE, tail=TAIL_FADE)
        if a < 1.0:
            fr.flash(BLACK, 1.0 - a)
        yield fr


def parse_args():
    ap = argparse.ArgumentParser(description="S02 renderer (key visual, 10.0 s)")
    ap.add_argument("--frames", type=int, default=240)
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--out", default="tools/video/promo/out/build/seg_S02.mp4")
    ap.add_argument("--preview", default=None)
    return ap.parse_args()


def main():
    args = parse_args()
    n, fps = args.frames, args.fps
    if n <= 0:
        raise SystemExit("error: --frames must be positive")

    idxs = sorted({0, n // 4, n // 2, (3 * n) // 4, n - 1}) if args.preview else []
    want = set(idxs)
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)

    def frames():
        for f, fr in enumerate(build(n, fps)):
            if f in want:
                fr.save(os.path.join(args.preview, "shot_02_f%03d.png" % f))
            yield fr.img

    written = K.write_video(frames(), args.out, fps=fps, crf=16, progress=60,
                            label=SEG_ID)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))
    print("%s: %d frames -> %s" % (SEG_ID, written, args.out), flush=True)
    if args.preview:
        print("%s: %d preview frames -> %s" % (SEG_ID, len(idxs), args.preview),
              flush=True)


if __name__ == "__main__":
    main()
