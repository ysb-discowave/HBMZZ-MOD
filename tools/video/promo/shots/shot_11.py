#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S11 · 爆炸与停机（18.000 s / 432 帧 / 全片 03:12.000–03:30.000）

    0.0–6.0 s   传感器方块（front → _on 切换），冲击波环一圈圈推开，右侧读数板
    6.0–12.0 s  右侧十五个红石等级条，从 15 逐格衰减到 3，每格亮灭一次
   12.0–18.0 s  四个阶段标签横向展开（AURA / RETRACT / HOLD / DONE，README 状态机真名），进度依次推过

转场：首帧直接硬切进入（承接 S10 的硬切），末 12 帧淡到黑（交给 S12 的安静）。

绘制说明：细线与小字走 ``Frame.draw`` 直接绘制，颜色按「品牌色 over 底色」预混
（等价于整层 alpha 合成，省掉每帧一次全画布 RGBA 层），所以单帧成本从 0.37 s
降到 0.07 s。贴图、字幕、柔光、暗角仍走 promo_kit。

运行：
    py -3.12 tools/video/promo/shots/shot_11.py --frames 432 --fps 24 \
        --out tools/video/promo/out/build/seg_S11.mp4 \
        --preview tools/video/promo/out/check/shot_11
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

Image = K.Image
BLACK = (0, 0, 0)

DEFAULT_FRAMES = 432
DEFAULT_OUT = "tools/video/promo/out/build/seg_S11.mp4"

TEX_SENSOR = "hbmzz/textures/blocks/dfc_blast_sensor_front.png"
TEX_SENSOR_ON = "hbmzz/textures/blocks/dfc_blast_sensor_front_on.png"

BLAST = 14          # 0.6 s：方块切到 _on，第一圈冲击波从那一下开始
BAR_IN = 144        # 6.0 s：等级条进场
DECAY_AT = 156      # 6.5 s：开始逐级衰减
DECAY_STEP = 9      # 每 9 帧掉一级（约 0.375 s）
STAGE_IN = 288      # 12.0 s：切到四个阶段
STAGES = ["AURA", "RETRACT", "HOLD", "DONE"]

M = 92              # 左右安全边距（与四个阶段块同宽对齐）
MR = 868            # 右边界

SENSOR = (140.0, 170.0, 360.0, 390.0)
SENSOR_MINI = (120.0, 104.0, 220.0, 204.0)

CAPS = [
    (6, 140, "爆炸传感器：它认得出爆炸。"),
    (150, 284, "炸完输出十五级红石，逐级衰减。"),
    (294, 426, "自动停机，四个阶段。"),
]

CHIP_W = 176
CHIP_GAP = 24
CHIP_Y0 = 250
CHIP_Y1 = 350


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
    """品牌色按 alpha 预混到底色上（等价于整层 alpha 合成的结果）。"""
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
    """真正的 alpha 合成（用于压在贴图上的色层）；区域小，成本可忽略。"""
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


def vig_fast(fr, alpha=0.32):
    """与 kit 的 vignette 同结果（把边缘乘 1-a），但走缓存的黑色 RGBA 层。

    数学上等价：黑色层按 alpha 合成 = 原色 * (1 - alpha)，与 multiply 一致。
    """
    a = round(K.clamp(alpha), 3)
    layer = _VIG_CACHE.get(a)
    if layer is None:
        grad = Image.radial_gradient("L").resize((K.W, K.H), Image.BILINEAR)
        layer = Image.new("RGBA", (K.W, K.H), (0, 0, 0, 0))
        layer.putalpha(grad.point(lambda v: int(round(255 * a * (v / 255.0)))))
        _VIG_CACHE[a] = layer
    fr.img.paste(layer, (0, 0), layer)


def grid_fast(fr, alpha=0.05, step=40):
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
def sensor_box(f):
    """阶段 C 里方块缩到左上角当"设备还在"的锚点。"""
    t = K.ease_in_out_cubic(K.clamp((f - (STAGE_IN - 2)) / 30.0))
    return tuple(K.lerp(SENSOR[i], SENSOR_MINI[i], t) for i in range(4))


def level_at(f):
    if f < DECAY_AT:
        return 15
    return max(3, 15 - (f - DECAY_AT) // DECAY_STEP)


def level_step_frame(lv):
    """第 lv 级被"用掉"、掉到 lv-1 的那一帧。"""
    return DECAY_AT + (15 - lv + 1) * DECAY_STEP


def draw_rings(fr, f, box):
    # 冲击波只属于"认出爆炸"那一段（0–6 s），进等级条之前必须收干净
    if f > BAR_IN + 12:
        return
    fade = 1.0 - K.clamp((f - BAR_IN) / 12.0)
    cx = (box[0] + box[2]) / 2.0
    cy = (box[1] + box[3]) / 2.0
    sw = box[2] - box[0]
    for e in range(-3, 17):
        t0 = BLAST - 4 + 26 * e
        t = (f - t0) / 54.0
        if t < 0.0 or t >= 1.0:
            continue
        k = K.ease_out_cubic(t)
        half = K.lerp(sw * 0.62, sw * 2.35, k) / 2.0
        a = 0.58 * pow(1.0 - t, 1.6) * fade
        if a <= 0.01:
            continue
        f_box(fr, (cx - half, cy - half, cx + half, cy + half), color=K.AMBER,
              alpha=a, width=2, corner=int(6 + 8 * k))


def draw_sensor(fr, f, box):
    a = 1.0
    if f >= STAGE_IN:
        a = 1.0 - 0.25 * K.clamp((f - STAGE_IN) / 30.0)
    tex = TEX_SENSOR_ON if f >= BLAST else TEX_SENSOR
    fr.tex(tex, box, alpha=a)
    if f >= BLAST:
        # 触发态的镜头是暖色的，压一层极淡的琥珀，把整块设备拉回品牌色温
        wash = 0.12 * (1.0 - K.clamp((f - STAGE_IN) / 40.0))
        fill_rect(fr, box, K.AMBER, wash * a)

    # 四角准星：说明这是一台"在看"的设备
    x0, y0, x1, y1 = box
    pad, tick = 8.0, 12.0
    for (px, py, dx, dy) in ((x0 - pad, y0 - pad, 1, 1), (x1 + pad, y0 - pad, -1, 1),
                             (x0 - pad, y1 + pad, 1, -1), (x1 + pad, y1 + pad, -1, -1)):
        f_line(fr, (px, py), (px + dx * tick, py), color=K.STEEL, alpha=0.55 * a)
        f_line(fr, (px, py), (px, py + dy * tick), color=K.STEEL, alpha=0.55 * a)

    # 触发瞬间的琥珀底光
    if BLAST <= f < BLAST + 26:
        k = 1.0 - (f - BLAST) / 26.0
        fr.glow((x0 - 40, y0 - 40, x1 + 40, y1 + 40), color=K.AMBER,
                radius=16, alpha=0.55 * k)

    if f < STAGE_IN:
        f_text(fr, ((x0 + x1) / 2.0, y1 + 10), "dfc_blast_sensor", size=10,
               color=K.SLATE, cn=False, anchor="ma")
    else:
        f_text(fr, (x1 + 10, y1 - 12), "dfc_blast_sensor", size=10, color=K.SLATE,
               cn=False, anchor="la", alpha=0.7)


def draw_status_panel(fr, f):
    """阶段 A 的读数板（右键报读数：强度 / 距离 / 来源）。"""
    a = K.clamp((f - 32) / 14.0) * (1.0 - K.clamp((f - 138) / 14.0))
    if a <= 0.005:
        return
    x0, x1 = 440, MR
    f_text(fr, (x0, 186), "BLAST / DETECTED", size=13, color=K.AMBER, cn=False,
           alpha=a)
    f_line(fr, (x0, 208), (x1, 208), color=K.SLATE, alpha=0.55 * a)

    rows = [("当前强度", "STRENGTH  15"), ("上次距离", "DISTANCE  06 m"),
            ("信号源", "SRC  DFC-CORE")]
    for i, (cn, mono) in enumerate(rows):
        ra = a * K.clamp((f - 40 - i * 7) / 14.0)
        if ra <= 0.005:
            continue
        y = 226 + i * 38
        f_text(fr, (x0, y), cn, size=13, color=K.SLATE, alpha=ra)
        f_text(fr, (x1, y - 2), mono, size=15, color=K.STEEL, cn=False, anchor="ra",
               alpha=ra)
        f_line(fr, (x0, y + 24), (x1, y + 24), color=K.SLATE, alpha=0.30 * ra)


def draw_bars(fr, f):
    """右侧十五个红石等级条，15 → 3 逐格衰减。"""
    if f < BAR_IN - 4:
        return
    out = 1.0 - K.clamp((f - STAGE_IN) / 14.0)
    if out <= 0.005:
        return
    lv = level_at(f)
    top, row_h, bar_h = 96, 22, 10
    bx0, bx1 = 636, MR

    head = K.clamp((f - BAR_IN) / 12.0) * out
    f_text(fr, (bx0, 74), "REDSTONE LEVEL 15..1", size=10, color=K.SLATE, cn=False,
           alpha=0.8 * head)

    f_line(fr, (bx0 - 4, top), (bx0 - 4, top + row_h * 15 - (row_h - bar_h)),
           color=K.SLATE, alpha=0.35 * head)

    for i in range(15):
        row_lv = 15 - i
        a_in = K.clamp((f - BAR_IN - i * 2) / 10.0) * out
        if a_in <= 0.005:
            continue
        y = top + i * row_h

        if row_lv > lv:                      # 已衰减：留一道烧过的痕
            base, fill_a = K.AMBER, 0.13
        elif row_lv == lv:                   # 当前级：满格琥珀
            base, fill_a = K.AMBER, 0.95
        else:                                # 还没轮到：空轨
            base, fill_a = K.SLATE, 0.10

        fill_rect(fr, (bx0 + 16, y, bx1, y + bar_h), K.SLATE, 0.16 * a_in)
        fill_rect(fr, (bx0 + 16, y, bx1, y + bar_h), base, fill_a * a_in)
        if row_lv == lv:
            fr.glow((bx0, y - 4, bx1, y + bar_h + 4), color=K.AMBER, radius=6,
                    alpha=0.28 * a_in)

        f_text(fr, (bx0 + 2, y + bar_h / 2.0 - 6), "%02d" % row_lv, size=11,
               color=K.AMBER if row_lv <= lv else K.SLATE, cn=False, alpha=a_in)

        # 每格"亮灭一次"：掉下去的那一两帧，钢色闪一下
        tf = level_step_frame(row_lv)
        if tf <= f < tf + 4:
            k = 1.0 - (f - tf) / 4.0
            fill_rect(fr, (bx0 + 16, y, bx1, y + bar_h), K.STEEL, 0.85 * k)

    # 左侧大字读数
    a_big = K.clamp((f - 150) / 12.0) * out
    if a_big > 0.005:
        f_text(fr, (440, 206), "CURRENT", size=11, color=K.SLATE, cn=False, alpha=a_big)
        f_text(fr, (436, 220), "%02d" % lv, size=86, color=K.AMBER, cn=False,
               alpha=a_big)
        f_line(fr, (440, 318), (600, 318), color=K.SLATE, alpha=0.45 * a_big)
        f_text(fr, (440, 328), "-1 / 14 tick", size=11, color=K.SLATE, cn=False,
               alpha=a_big)


def stage_progress(f):
    """0..4：四个阶段依次推过。"""
    return K.clamp((f - 300) / 116.0) * 4.0


def draw_stages(fr, f):
    if f < STAGE_IN - 2:
        return
    p = stage_progress(f)
    a_line = K.clamp((f - STAGE_IN) / 14.0)

    # 顶上的连接线 + 走到哪一格的位置标记
    f_line(fr, (M, 232), (MR, 232), color=K.SLATE, alpha=0.35 * a_line)
    centers = [M + CHIP_W / 2.0 + i * (CHIP_W + CHIP_GAP) for i in range(4)]
    for cx in centers:
        f_line(fr, (cx, 232), (cx, CHIP_Y0), color=K.SLATE, alpha=0.28 * a_line)
    mx = K.lerp(centers[0], centers[-1], K.clamp(p / 4.0))
    fill_rect(fr, (mx - 4, 228, mx + 4, 236), K.AMBER, 0.95 * a_line)

    for i, lab in enumerate(STAGES):
        x0 = M + i * (CHIP_W + CHIP_GAP)
        x1 = x0 + CHIP_W
        a = K.clamp((f - (STAGE_IN + 4 + i * 5)) / 10.0) * a_line
        if a <= 0.005:
            continue
        active = p > i + 0.02
        pf = K.clamp(p - i)

        f_box(fr, (x0, CHIP_Y0, x1, CHIP_Y1), color=K.AMBER if active else K.SLATE,
              alpha=(0.80 if active else 0.40) * a, width=2, corner=4)
        f_text(fr, (x0 + 12, CHIP_Y0 + 10), "%02d" % (i + 1), size=10, color=K.SLATE,
               cn=False, alpha=a)
        f_text(fr, ((x0 + x1) / 2.0, CHIP_Y0 + 28), lab, size=27,
               color=K.STEEL if active else K.SLATE, anchor="ma", alpha=a)

        fill_rect(fr, (x0 + 12, 316, x1 - 12, 320), K.SLATE, 0.22 * a)
        if pf > 0:
            fill_rect(fr, (x0 + 12, 316, x0 + 12 + (CHIP_W - 24) * pf, 320), K.AMBER,
                      0.90 * a)

        f_text(fr, (x0 + 12, 330), "ACTIVE" if active else "WAIT", size=10,
               color=K.AMBER if active else K.SLATE, cn=False,
               alpha=a * (0.95 if active else 0.7))

    # 底下一条整段进度轨，把四个阶段串成一次"走完的流程"
    a_rail = K.clamp((f - STAGE_IN - 10) / 14.0)
    if a_rail > 0.005:
        fill_rect(fr, (M, 396, MR, 400), K.SLATE, 0.20 * a_rail)
        if p > 0:
            fill_rect(fr, (M, 396, M + (MR - M) * K.clamp(p / 4.0), 400), K.AMBER,
                      0.85 * a_rail)
        for i in range(5):
            tx = M + (MR - M) * i / 4.0
            f_line(fr, (tx, 392), (tx, 404), color=K.SLATE, alpha=0.45 * a_rail)
        f_text(fr, (M, 410), "SCRAM SEQUENCE", size=10, color=K.SLATE, cn=False,
               alpha=0.8 * a_rail)
        f_text(fr, (MR, 410), "%03d %%" % int(round(25.0 * p)), size=10,
               color=K.AMBER, cn=False, anchor="ra", alpha=0.9 * a_rail)


def draw_temp(fr, f):
    if f < STAGE_IN:
        return
    a = K.clamp((f - STAGE_IN - 2) / 12.0)
    if a <= 0.005:
        return
    p = K.clamp(stage_progress(f) / 4.0)
    temp = int(round(K.lerp(3412, 0, K.ease_in_out_cubic(p))))
    f_text(fr, (MR, 104), "CORE TEMP / degC", size=10, color=K.SLATE, cn=False,
           anchor="ra", alpha=0.9 * a)
    f_text(fr, (MR, 118), "%04d" % temp, size=40, color=K.AMBER, cn=False,
           anchor="ra", alpha=a)
    f_line(fr, (700, 168), (MR, 168), color=K.SLATE, alpha=0.35 * a)


# --------------------------------------------------------------------------
def draw_captions(fr, f):
    for start, end, text in CAPS:
        a = cap_alpha(f, start, end)
        if a > 0:
            fr.caption(text, alpha=a, size=30, color=K.STEEL, y=486)


def draw_frame(f, n):
    fr = K.Frame()
    fr.clear()
    # 上一段刚炸完：头半秒留一层极淡的余温，避免硬切之后画面"掉得太干净"
    if f < 24:
        fr.flash(K.AMBER, 0.05 * (1.0 - f / 24.0))
    if BLAST <= f < BLAST + 8:
        fr.flash(K.AMBER, 0.16 * (1.0 - (f - BLAST) / 8.0))
    grid_fast(fr, 0.05)

    # 页眉
    ha = K.clamp(f / 10.0)
    f_text(fr, (M, 46), "S11 // DFC BLAST SENSOR + AUTO SCRAM", size=11,
           color=K.SLATE, cn=False, alpha=0.85 * ha)
    f_text(fr, (MR, 46), "RS BUS 15 -> 03", size=11, color=K.SLATE, cn=False,
           anchor="ra", alpha=0.85 * ha)
    f_line(fr, (M, 66), (MR, 66), color=K.SLATE, alpha=0.30 * ha)

    sb = sensor_box(f)
    draw_rings(fr, f, sb)
    draw_sensor(fr, f, sb)
    draw_status_panel(fr, f)
    draw_bars(fr, f)
    draw_stages(fr, f)
    draw_temp(fr, f)

    vig_fast(fr, 0.32)
    draw_captions(fr, f)
    blackout(fr, K.fade_alpha(f, n, head=0, tail=12))
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n).img


def parse_args():
    ap = argparse.ArgumentParser(description="S11 爆炸与停机 段渲染器")
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
                            progress=72, label="S11")
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))

    if args.preview:
        for f in preview_idx:
            save_png(shots[f], os.path.join(args.preview, "shot_11_%03d.png" % f))
    print("S11 done: %d frames -> %s" % (written, args.out))


if __name__ == "__main__":
    main()
