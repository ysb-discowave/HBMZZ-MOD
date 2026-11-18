#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S01 命题 / Proposition - 144 frames (6.000 s @ 24 fps).

分镜（SEGMENTS.md S01）:
    0.0-1.5  六块小屏以错乱读数亮起（3412 / 0 / 一切正常 / 灯柱互不一致）
    1.5-3.5  六屏数据互相冲突，琥珀全亮 + 3 帧警示红闪 (#b3261e)
    3.5-4.8  六屏同时黑掉，只剩深底
    4.8-6.0  一行琥珀字亮起

转场: 首 12 帧黑->亮, 末 12 帧淡到纯黑。
CLI（CONTRACT §7.2）::

    py -3.12 tools/video/promo/shots/shot_01.py --frames 144 --fps 24 \
        --out tools/video/promo/out/build/seg_S01.mp4 \
        --preview tools/video/promo/out/check/shot_01

绘制约定: 屏内元素一律用**预混合颜色 + alpha 1.0** 的直绘（kit 的快路径），
整屏的淡入淡出用 ``dim_region`` 把局部画面混回底色，避免每帧分配几十张
960x540 的 RGBA 图层（那样 144 帧要跑几分钟）。
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K  # noqa: E402

from PIL import Image, ImageDraw  # noqa: E402

SEG_ID = "S01"
HEAD_FADE, TAIL_FADE = 12, 12

BLACK = (0, 0, 0)
RED = (179, 38, 30)          # #b3261e，全片仅 S01 这 3 帧允许

# 字幕: (起帧, 止帧, 文本, 字号, 颜色)；淡入淡出各 4 帧
CAPTIONS = (
    (3, 37, "一个核电站，六块屏。", 30, K.STEEL),
    (37, 84, "温度说 3412，稳定度说 0，警报说一切正常。", 30, K.STEEL),
    (85, 115, "它们说的不是同一件事。", 30, K.STEEL),
    (116, 144, "所以，我们让它们说同一件事。", 34, K.AMBER),
)

MONITOR = {
    "dfc": "hbmzz/textures/blocks/dfc_monitor_front.png",
    "nuke": "hbmzz/textures/blocks/nuke_monitor_front.png",
    "alert": "hbmzz/textures/blocks/alert_display_front.png",
}
LAMP = {
    "y": "rsaudio/textures/blocks/alarm_yellow_on.png",
    "o": "rsaudio/textures/blocks/alarm_orange_on.png",
}

SIZE = 160
SX = (180, 400, 620)
SY = (72, 258)

# (贴图, 单位, 常态读数, 常态条带, 冲突读数, 冲突条带, 灯色键)
# 前三条读数在冲突期保持不变 —— 温度 3412 / 稳定度 0 / 警报一切正常，
# 这三者互相矛盾，正是本段要说的事；后三条在冲突期翻面，做"各说各话"的骚动。
SCREENS = (
    ("dfc", "TEMP", "3412", 0.57, "3412", 0.57, "o"),
    ("nuke", "STAB", "0%", 0.00, "0%", 0.00, "y"),
    ("alert", "ALRM", "正常", 0.00, "正常", 0.00, "o"),
    ("nuke", "FLUX", "0.00", 0.00, "ERR", 0.00, "y"),
    ("dfc", "PWR", "82.00", 0.68, "--", 0.00, "o"),
    ("dfc", "STRU", "3/7", 0.43, "7/7", 1.00, "y"),
)
CELLS = (3, 0, 5, 1, 4, 2)                    # 灯柱常态点亮格数（互不一致）

ON_T = (0.00, 0.16, 0.30, 0.46, 0.64, 0.86)   # 各屏上电时刻（秒，错乱）
CONFLICT0, CONFLICT1 = 1.5, 3.5               # 冲突窗口（秒）
DEATH = 3.5                                   # 六屏同时黑掉（秒）
VERDICT = 4.8                                 # 一行琥珀字（秒）
RED_FLASH = (60, 61, 62)                      # 2.50-2.58 s，共 3 帧


# ---------------------------------------------------------------- 基础绘制
def mix(color, t, base=K.BG):
    """把颜色按 t 混向底色，模拟半透明（用来走 kit 的 alpha=1 快路径）。"""
    t = K.clamp(t)
    return tuple(int(round(color[i] * (1.0 - t) + base[i] * t)) for i in range(3))


def fill(fr, box, color, alpha=1.0):
    """实心矩形（kit 的 box 只有描边）。"""
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


def blend_region(fr, box, k):
    """把 box 内的画面按 k 混回底色（整屏淡入 / 淡出，局部运算，很快）。"""
    k = K.clamp(k)
    if k >= 0.999:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 <= x0 or y1 <= y0:
        return
    if k <= 0.001:
        fr.img.paste(K.BG, (x0, y0, x1, y1))
        return
    reg = fr.img.crop((x0, y0, x1, y1))
    out = Image.blend(reg, Image.new("RGB", reg.size, K.BG), 1.0 - k)
    fr.img.paste(out, (x0, y0))


def draw_captions(fr, f):
    for f0, f1, text, size, color in CAPTIONS:
        if f < f0 or f >= f1:
            continue
        a = min(K.clamp((f - f0 + 1) / 4.0), K.clamp((f1 - f) / 4.0))
        fr.caption(text, alpha=K.smoothstep(a), size=size, color=color, y=486)


# ---------------------------------------------------------------- 背景
def draw_bg(fr, f, n):
    fr.hud_grid(alpha=0.085, step=40)
    # 一条自上而下扫过的扫描带（3 条线做衰减），全程缓慢移动
    y = int((f * 3.2) % (K.H + 90)) - 45
    for dy, a in ((0, 0.070), (2, 0.026), (4, 0.010)):
        if -6 <= y + dy <= K.H + 6:
            fr.hairline((-40, y + dy), (K.W + 40, y + dy),
                        color=mix(K.STEEL, 1.0 - a), alpha=1.0, width=1)
    fr.scanlines(alpha=0.050)


# ---------------------------------------------------------------- 单块屏
def screen_alpha(i, f, fps):
    """上电淡入 + 3.5 s 同时熄灭。"""
    on = K.smoothstep(K.clamp((f - ON_T[i] * fps) / 4.0))
    if on <= 0:
        return 0.0
    dead_at = DEATH * fps
    if f >= dead_at:
        k = K.clamp((f - dead_at) / 8.0)
        return 1.0 - K.smoothstep(k)
    return on


def screen_state(i, f, fps):
    """(读数, 条带, 灯柱格数, 读数是否等宽) — 冲突窗口内每 6 帧翻面一次。"""
    _, _, base_val, base_bar, alt_val, alt_bar, _ = SCREENS[i]
    conflict = int(CONFLICT0 * fps) <= f < int(CONFLICT1 * fps)
    value, bar, cells = base_val, base_bar, CELLS[i]
    if conflict:
        flip = ((f - int(CONFLICT0 * fps)) // 6 + i) % 2 == 1
        value = alt_val if flip else base_val
        bar = alt_bar if flip else base_bar
        if i >= 3:
            # 条带自己抖动，与读数对不上；前三条不动，读数才立得住
            bar = K.clamp(bar + 0.18 * ((f * 7 + i * 13) % 11) / 11.0 - 0.09)
        cells = (CELLS[i] + 2 + (f // 3 + i) % 4) % 6
    else:
        # 上电后条带缓慢爬升，像仪器自检
        bar = base_bar * K.smoothstep(K.clamp((f / fps - ON_T[i]) / 0.55))
    ascii_only = all(ord(c) < 128 for c in value)
    return value, K.clamp(bar), cells, ascii_only


def draw_screen(fr, i, f, fps, dx=0, dy=0):
    tex_a = screen_alpha(i, f, fps)
    if tex_a <= 0:
        return
    x, y = SX[i % 3] + dx, SY[i // 3] + dy
    value, bar, cells, ascii_only = screen_state(i, f, fps)
    mon, unit, _, _, _, _, lamp = SCREENS[i]
    conflict = int(CONFLICT0 * fps) <= f < int(CONFLICT1 * fps)

    fr.tex(MONITOR[mon], (x, y, x + SIZE, y + SIZE), alpha=1.0)

    # 内屏深底：盖掉贴图自带的彩色像素，读数才干净
    ix0, iy0, ix1, iy1 = x + 26, y + 26, x + 134, y + 128
    fill(fr, (ix0, iy0, ix1, iy1), K.BG)
    fr.box((ix0, iy0, ix1, iy1), color=mix(K.SLATE, 0.45), alpha=1.0,
           width=1, corner=0)

    # 单位标签
    fr.text((ix0 + 6, iy0 + 4), unit, size=9, color=mix(K.STEEL, 0.38), cn=False,
            shadow=False)

    # 报警灯（每屏相位不同，一直在闪）
    blink = 0.22 + 0.78 * abs((((f + i * 7) % 14) / 7.0) - 1.0)
    fr.tex(LAMP[lamp], (ix1 - 22, iy0 + 3, ix1 - 6, iy0 + 19), alpha=blink)

    # 主读数
    if ascii_only:
        fr.text((ix0 + 6, iy0 + 24), value, size=28, color=K.AMBER, cn=False,
                shadow=False)
    else:
        fr.text((ix0 + 6, iy0 + 30), value, size=22, color=K.AMBER, cn=True,
                shadow=False)

    # 灯柱（5 格；各屏格数不同，冲突时一起乱跳）
    for c in range(5):
        cy0 = iy0 + 26 + c * 12
        on = c < cells
        flick = 0.72 + 0.28 * (((f * 3 + c * 5 + i * 11) % 9) / 8.0)
        col = K.AMBER if on else K.SLATE
        fill(fr, (ix1 - 13, cy0, ix1 - 5, cy0 + 9), col,
             0.88 * flick if on else 0.20)

    # 底部量表
    by = iy1 - 16
    fr.box((ix0 + 6, by, ix1 - 6, by + 8), color=mix(K.SLATE, 0.45), alpha=1.0,
           width=1, corner=0)
    fill(fr, (ix0 + 7, by + 1, ix0 + 7 + int((ix1 - ix0 - 14) * bar), by + 7),
         K.AMBER, 0.80)
    tick = mix(K.SLATE, 0.55)
    for k in range(1, 4):
        tx = ix0 + 6 + int((ix1 - ix0 - 12) * k / 4.0)
        fr.hairline((tx, by - 2), (tx, by), color=tick, alpha=1.0, width=1)

    # 屏框：常态石板，冲突期转琥珀全亮
    frame_col = mix(K.AMBER, 0.12) if conflict else mix(K.SLATE, 0.30)
    fr.box((x, y, x + SIZE, y + SIZE), color=frame_col, alpha=1.0,
           width=2, corner=2)

    # 整屏淡入 / 淡出（局部混合，含贴图与读数）
    blend_region(fr, (x, y, x + SIZE, y + SIZE), tex_a)


def draw_screens(fr, f, fps):
    shake = (-2, 1, 0)[RED_FLASH.index(f)] if f in RED_FLASH else 0
    for i in range(6):
        draw_screen(fr, i, f, fps, dx=shake)
    if f in RED_FLASH:
        fr.flash(RED, (0.36, 0.20, 0.08)[RED_FLASH.index(f)])


def draw_hud_tags(fr, f, fps):
    """左上编号 + 右上在线计数；随六屏一起消失，收尾只剩深底。"""
    a = 1.0
    if f >= int(DEATH * fps):
        a = K.clamp(1.0 - (f - int(DEATH * fps)) / 8.0)
    if a <= 0.02:
        return
    alive = 6 if f < DEATH * fps else 0
    fr.hairline((40, 40), (48, 40), color=K.AMBER, alpha=1.0, width=3)
    fr.hairline((52, 40), (86, 40), color=mix(K.SLATE, 0.2 / a), alpha=1.0, width=1)
    fr.text((40, 48), "SYS // 06 NODES", size=12, color=mix(K.SLATE, 0.1 / a),
            cn=False, shadow=False)
    fr.text((K.W - 40, 48), "ONLINE %d/6" % alive, size=12,
            color=mix(K.AMBER if alive else K.SLATE, 0.1 / a), cn=False,
            anchor="ra", shadow=False)


def draw_verdict(fr, f, fps):
    """4.8 s：一行琥珀字亮起 —— 字是字幕，这里补会生长的细线与一下提亮。"""
    f0 = int(round(VERDICT * fps))
    if f < f0:
        return
    k = K.ease_out_cubic(K.clamp((f - f0) / 10.0))
    half = 210 * k
    fr.hairline((K.W / 2 - half, 438), (K.W / 2 + half, 438),
                color=mix(K.AMBER, 0.45), alpha=1.0, width=1)
    if f < f0 + 6:
        fr.flash(K.AMBER, 0.07 * (1.0 - (f - f0) / 6.0))


# ---------------------------------------------------------------- 主流程
def build(n, fps):
    for f in range(n):
        fr = K.Frame()
        draw_bg(fr, f, n)
        draw_screens(fr, f, fps)
        draw_hud_tags(fr, f, fps)
        draw_verdict(fr, f, fps)
        fr.vignette(alpha=0.34)
        fr.scanlines(alpha=0.035)
        draw_captions(fr, f)
        a = K.fade_alpha(f, n, head=HEAD_FADE, tail=TAIL_FADE)
        if a < 1.0:
            fr.flash(BLACK, 1.0 - a)      # 整帧压黑（含字幕与贴图）
        yield fr


def parse_args():
    ap = argparse.ArgumentParser(description="S01 renderer (proposition, 6.0 s)")
    ap.add_argument("--frames", type=int, default=144)
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--out", default="tools/video/promo/out/build/seg_S01.mp4")
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
                fr.save(os.path.join(args.preview, "shot_01_f%03d.png" % f))
            yield fr.img

    written = K.write_video(frames(), args.out, fps=fps, crf=16, progress=48,
                            label=SEG_ID)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))
    print("%s: %d frames -> %s" % (SEG_ID, written, args.out), flush=True)
    if args.preview:
        print("%s: %d preview frames -> %s" % (SEG_ID, len(idxs), args.preview),
              flush=True)


if __name__ == "__main__":
    main()
