#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S05 - 面板工坊 / Panel workshop - 576 frames (24.000 s @ 24 fps).

    0.0- 5.0 s  7 个真实控件从画面四周飞入，散落在深底上
    5.0-10.0 s  向网格聚拢，逐格吸附（每格落地有 3 帧回弹）
   10.0-16.0 s  面板切成 当 / 若 / 则 三栏，每栏依次点亮一行文本
   16.0-21.0 s  规则命中，琥珀线从“则”输出，点亮输出控件，状态真的翻成 ON
   21.0-24.0 s  拉远，面板缩成方块图标，标注「入口在终端左列」

硬切：head_fade=0 / tail_fade=0 / flash_in=0。

Run:
    py -3.12 tools/video/promo/shots/shot_05.py --frames 576 --fps 24 \
        --out tools/video/promo/out/build/seg_S05.mp4 \
        --preview tools/video/promo/out/check/shot_05
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K  # noqa: E402

Image = K.Image
ImageDraw = K.ImageDraw

SEG_ID = "S05"
DEFAULT_FRAMES = 576
DEFAULT_OUT = "tools/video/promo/out/build/seg_S05.mp4"
HEAD_FADE, TAIL_FADE, FLASH_IN = 0, 0, 0

CP = "hbmzz/textures/models/control_panel/"
BLOCK_ICON = "hbmzz/textures/blocks/control_panel_front.png"
LAMP_TEX = CP + "indicator_lamp_gui.png"

# 七个真实控件（顺序即吸附顺序）
CONTROLS = (
    (CP + "button_push.png", "BUTTON", 8.0),
    (CP + "dial_large.png", "DIAL", -7.0),
    (CP + "slider_vertical.png", "SLIDER", 6.0),
    (CP + "switch_toggle.png", "SWITCH", -8.0),
    (CP + "display_7seg.png", "7SEG", 5.0),
    (CP + "knob_control.png", "KNOB", -6.0),
    (CP + "indicator_lamp_gui.png", "LAMP", 7.0),
)

PANEL = (140, 96, 880, 406)
CELL = 104
CELLS = ((242, 135), (386, 135), (530, 135), (674, 135),
         (242, 263), (386, 263), (530, 263), (674, 263))

SCATTER = ((150, 100), (700, 70), (830, 220), (110, 300),
           (420, 50), (700, 330), (300, 330))
ENTRY = ((-150, 100), (1110, 70), (980, -140), (-140, 300),
         (420, -180), (1120, 350), (300, 660))
# 每个控件落到哪一格：按最近原则分配，飞入路径互不穿越，空出 (530,135)
TARGET = (0, 3, 7, 4, 1, 6, 5)
SPARE_CELL = 2

T_FLY = 0.10          # 第一个控件起飞
T_SNAP = 5.00         # 开始聚拢
T_SPLIT = 10.00       # 切成三栏
T_FIRE = 16.00        # 规则命中
T_OUT = 21.00         # 拉远

COLS = (("当", 156, 356), ("若", 364, 564), ("则", 572, 700))
RAIL = (712, 124, 864, 386)
LAMP = (736, 168, 840, 272)
COL_TOP, COL_BOT = 124, 386

RULE_TEXT = (
    (("周期检测", 18, 208), ("每 20 刻", 15, 240)),
    (("温度 > 6000", 20, 208), ("高于熔点", 15, 240)),
    (("触发报警", 18, 208), ("切回核心页", 15, 240)),
)
RULE_T = ((10.55, 11.15), (11.85, 12.45), (13.15, 13.75))
RULE_SUB = ("WHEN / TICK 20", "IF / TEMP > 6000", "THEN / ALARM")

CUES = (
    (0.25, 5.04, "面板工坊。"),
    (5.04, 10.04, "7 种控件，拖进去就是一块面板。"),
    (10.04, 16.04, "一条规则三个词：当、若、则。"),
    (16.04, 21.04, "条件成立，它真的会动。"),
    (21.04, 23.88, "存进方块，换台机器也能用。"),
)


# ---------------------------------------------------------------------------
# 局部绘制辅助
# ---------------------------------------------------------------------------
def fill_rect(fr, box, color, alpha=1.0):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 <= x0 or y1 <= y0:
        return
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), color[:3] + (a,))
    fr.img.paste(layer, (x0, y0), layer)


def corner_marks(fr, box, alpha=1.0, ck=9, color=K.AMBER, width=1):
    x0, y0, x1, y1 = box
    for (px, py, dx, dy) in ((x0, y0, 1, 1), (x1, y0, -1, 1),
                             (x0, y1, 1, -1), (x1, y1, -1, -1)):
        fr.hairline((px, py), (px + dx * ck, py), color=color, alpha=alpha,
                    width=width)
        fr.hairline((px, py), (px, py + dy * ck), color=color, alpha=alpha,
                    width=width)


# 控件贴图本身带红 / 绿的指示灯像素，本段只允许品牌四色：
# 载入时把所有「有彩度」的像素按亮度映射到琥珀，形状不变，颜色归位。
_tint_cache = {}


def tinted(relpath):
    img = _tint_cache.get(relpath)
    if img is not None:
        return img
    img = Image.open(os.path.join(K.assets_root(), relpath)).convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a <= 0:
                continue
            if max(r, g, b) - min(r, g, b) >= 20:
                lum = 0.299 * r + 0.587 * g + 0.114 * b
                k = K.clamp(lum / 190.0)
                px[x, y] = (int(245 * k), int(165 * k), int(36 * k), a)
    _tint_cache[relpath] = img
    return img


def blit(fr, relpath, box, alpha=1.0, rot=0.0):
    """按 box 等比放入（NEAREST），可旋转；贴图先经四色归位。"""
    alpha = K.clamp(alpha)
    if alpha <= 0:
        return
    img = tinted(relpath)
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    bw, bh = x1 - x0, y1 - y0
    if bw <= 0 or bh <= 0:
        return
    iw, ih = img.size
    k = min(bw / float(iw), bh / float(ih))
    nw, nh = max(1, int(round(iw * k))), max(1, int(round(ih * k)))
    out = img.resize((nw, nh), Image.NEAREST) if (nw, nh) != img.size else img
    if rot:
        out = out.rotate(float(rot), resample=Image.NEAREST, expand=True)
    if alpha < 1.0:
        out = out.copy()
        a = out.getchannel("A").point(lambda v: int(v * alpha))
        out.putalpha(a)
    fr.img.paste(out, ((x0 + x1) // 2 - out.width // 2,
                       (y0 + y1) // 2 - out.height // 2), out)


# ---------------------------------------------------------------------------
# 控件运动
# ---------------------------------------------------------------------------
def ctrl_state(i, t):
    """返回 (cx, cy, alpha, scale, landed) —— 中心坐标。"""
    e0 = T_FLY + i * 0.13
    k_in = K.ease_out_cubic(K.clamp((t - e0) / 0.80))
    sx = K.lerp(ENTRY[i][0], SCATTER[i][0], k_in)
    sy = K.lerp(ENTRY[i][1], SCATTER[i][1], k_in)

    s0 = T_SNAP + i * 0.09
    k_snap = K.ease_in_out_cubic(K.clamp((t - s0) / 1.45))
    tx, ty = CELLS[TARGET[i]]
    x = K.lerp(sx, tx, k_snap)
    y = K.lerp(sy, ty, k_snap)

    # 落格后 3 帧回弹：先往上弹 7 px，再落回
    landed = t >= s0 + 1.45
    if landed:
        v = K.clamp((t - (s0 + 1.45)) / (3.0 / K.FPS))
        if v < 1.0:
            y -= 7.0 * math.sin(math.pi * v)

    # 入场时从 0.86 放大到 1.0，比旋转更像「被拖进来」
    scale = 0.86 + 0.14 * K.clamp(k_in * 1.2)
    a = K.clamp((t - e0) / 0.18)
    if t >= T_SPLIT:
        a *= 1.0 - K.clamp((t - T_SPLIT) / 0.45)
    return x + CELL / 2.0, y + CELL / 2.0, a, scale, landed


def draw_controls(fr, t):
    for i, (tex, label, rot0) in enumerate(CONTROLS):
        cx, cy, a, scale, landed = ctrl_state(i, t)
        if a <= 0.01:
            continue
        half = CELL * scale / 2.0
        box = (cx - half, cy - half, cx + half, cy + half)

        # 控件卡：深底 + 细边；飞行途中半透明，落格后压成实体
        fill_rect(fr, box, K.BG, (0.72 if landed else 0.45) * a)
        fr.box(box, color=K.SLATE, alpha=(0.55 if landed else 0.40) * a,
               width=1, corner=3)

        pad = 8 * scale
        blit(fr, tex, (box[0] + pad, box[1] + pad, box[2] - pad, box[3] - pad),
             alpha=a)

        # 落格瞬间：格子四角闪一下琥珀
        s0 = T_SNAP + i * 0.09 + 1.45
        d = t - s0
        if 0.0 <= d < 0.30:
            k = 1.0 - K.clamp(d / 0.30)
            corner_marks(fr, (box[0] - 4, box[1] - 4, box[2] + 4, box[3] + 4),
                         alpha=0.85 * k, ck=10, width=2)

        if t < T_SNAP + 0.20 and a > 0.45:
            la = 0.8 * a * K.clamp((T_SNAP + 0.20 - t) / 0.20)
            fr.text((cx, box[3] + 7), label, size=9, color=K.SLATE, cn=False,
                    anchor="ma", alpha=la, shadow=False)


# ---------------------------------------------------------------------------
# 面板 / 网格插槽
# ---------------------------------------------------------------------------
def draw_panel(fr, t):
    a = K.clamp((t - 4.35) / 0.55)
    if a <= 0:
        return
    if t >= T_OUT:
        a *= 1.0 - K.clamp((t - T_OUT) / 0.25)
        if a <= 0:
            return
    fill_rect(fr, PANEL, K.SLATE, 0.10 * a)
    fr.box(PANEL, color=K.SLATE, alpha=0.85 * a, width=2, corner=4)
    corner_marks(fr, PANEL, alpha=0.55 * a, ck=14)

    fr.text((PANEL[0] + 14, PANEL[1] + 9), "PANEL / 04", size=9,
            color=K.SLATE, cn=False, alpha=0.85 * a, shadow=False)
    fr.text((PANEL[2] - 14, PANEL[1] + 9), "7 CONTROLS", size=9,
            color=K.SLATE, cn=False, anchor="ra", alpha=0.7 * a, shadow=False)
    fr.hairline((PANEL[0] + 14, PANEL[1] + 24), (PANEL[2] - 14, PANEL[1] + 24),
                color=K.SLATE, alpha=0.4 * a, width=1)

    # 吸附插槽：聚拢期间一直可见，落地由琥珀角标接管
    if t < T_SPLIT:
        aa = K.clamp((t - 4.45) / 0.5)
        if aa > 0:
            for i, (x, y) in enumerate(CELLS):
                fr.box((x, y, x + CELL, y + CELL), color=K.SLATE,
                       alpha=0.28 * aa, width=1, corner=2)
            sx, sy = CELLS[SPARE_CELL]
            fr.text((sx + CELL // 2, sy + CELL // 2), "SPARE",
                    size=8, color=K.SLATE, cn=False, anchor="mm",
                    alpha=0.45 * aa, shadow=False)


# ---------------------------------------------------------------------------
# 三栏规则
# ---------------------------------------------------------------------------
def draw_columns(fr, t):
    if t < T_SPLIT + 0.10:
        return
    # 命中后“则”栏整栏提亮
    hit = K.clamp((t - 14.35) / 0.30)

    for ci, (head, x0, x1) in enumerate(COLS):
        a = K.clamp((t - (T_SPLIT + 0.10 + ci * 0.16)) / 0.35)
        if a <= 0:
            continue
        lit = 1.0 if (ci == 2 and hit > 0) else 0.0
        fill_rect(fr, (x0, COL_TOP, x1, COL_BOT), K.SLATE, (0.05 + 0.06 * lit) * a)
        fr.box((x0, COL_TOP, x1, COL_BOT),
               color=K.AMBER if lit else K.SLATE,
               alpha=(0.45 + 0.45 * lit) * a, width=2, corner=3)

        fr.text(((x0 + x1) // 2, 158), head, size=34,
                color=K.AMBER if (ci == 2 and hit > 0) else K.STEEL,
                cn=True, anchor="ms", alpha=a)
        fr.hairline((x0 + 18, 184), (x1 - 18, 184), color=K.SLATE,
                    alpha=0.45 * a, width=1)

        for li, (text, size, y) in enumerate(RULE_TEXT[ci]):
            t0 = RULE_T[ci][li]
            aa = K.clamp((t - t0) / 0.30) * a
            if aa <= 0:
                continue
            cn = not text.isascii()
            fr.text((x0 + 20, y), text, size=size,
                    color=K.STEEL if li == 0 else K.SLATE,
                    cn=cn, alpha=aa, shadow=False)
            # 行首方点，点亮时从石板色变琥珀
            fill_rect(fr, (x0 + 10, y + 7, x0 + 14, y + 11),
                      K.AMBER if li == 0 else K.SLATE, 0.8 * aa)

        # 栏底注释行（等宽小字，把三栏的语义钉住）
        fr.text((x0 + 20, COL_BOT - 30), RULE_SUB[ci], size=9, color=K.SLATE,
                cn=False, alpha=0.6 * a, shadow=False)


def draw_rail(fr, t):
    """右侧输出轨：指示灯 + 状态字。"""
    a = K.clamp((t - (T_SPLIT + 0.30)) / 0.40)
    if a <= 0:
        return
    x0, y0, x1, y1 = RAIL
    fill_rect(fr, RAIL, K.SLATE, 0.06 * a)
    fr.box(RAIL, color=K.SLATE, alpha=0.55 * a, width=1, corner=3)

    fr.text((x0 + 12, y0 + 12), "输出", size=11, color=K.SLATE, cn=True,
            alpha=a, shadow=False)
    fr.text((x1 - 12, y0 + 13), "OUT 01", size=9, color=K.SLATE, cn=False,
            anchor="ra", alpha=0.8 * a, shadow=False)
    fr.hairline((x0 + 12, y0 + 30), (x1 - 12, y0 + 30), color=K.SLATE,
                alpha=0.35 * a, width=1)

    on = K.clamp((t - T_FIRE - 0.55) / 0.22)
    # 灯座
    fr.box(LAMP, color=K.AMBER if on > 0.5 else K.SLATE,
           alpha=(0.35 + 0.6 * on) * a, width=2, corner=4)
    blit(fr, LAMP_TEX, (LAMP[0] + 8, LAMP[1] + 8, LAMP[2] - 8, LAMP[3] - 8),
         alpha=(0.35 + 0.65 * on) * a)
    if on > 0:
        fill_rect(fr, (LAMP[0] + 14, LAMP[1] + 14, LAMP[2] - 14, LAMP[3] - 14),
                  K.AMBER, 0.55 * on * a)
        fr.glow((LAMP[0] - 16, LAMP[1] - 16, LAMP[2] + 16, LAMP[3] + 16),
                color=K.AMBER, radius=12, alpha=0.55 * on)

    # 状态字：命中瞬间 OFF -> ON（先闪 2 帧再稳住）
    flick = 1.0
    if 0 < on < 1.0:
        flick = 0.5 + 0.5 * (1.0 if int(t * K.FPS) % 2 == 0 else 0.0)
    fr.text(((LAMP[0] + LAMP[2]) // 2, 288), "ON" if on > 0.5 else "OFF",
            size=15, color=K.AMBER if on > 0.5 else K.SLATE, cn=False,
            anchor="ma", alpha=(0.6 + 0.4 * on) * flick * a)
    fr.text((x0 + 12, y1 - 22), "LAMP_01", size=9, color=K.SLATE, cn=False,
            alpha=0.8 * a, shadow=False)
    fr.text((x1 - 12, y1 - 22), "STATE", size=8, color=K.SLATE, cn=False,
            anchor="ra", alpha=0.6 * a, shadow=False)


def draw_fire(fr, t):
    """从“则”栏右缘伸出的三段琥珀线，逐段生长到输出灯座。"""
    if t < T_FIRE:
        return
    p = K.clamp((t - T_FIRE) / 0.95)
    segs = (((700, 330), (726, 330)),
            ((726, 330), (726, 220)),
            ((726, 220), (736, 220)))
    for i, (p0, p1) in enumerate(segs):
        local = K.clamp(p * len(segs) - i)
        if local <= 0:
            break
        x1 = K.lerp(p0[0], p1[0], local)
        y1 = K.lerp(p0[1], p1[1], local)
        fr.hairline(p0, (x1, y1), color=K.AMBER, alpha=0.95, width=2)
        if local >= 1.0:
            fill_rect(fr, (p1[0] - 3, p1[1] - 3, p1[0] + 3, p1[1] + 3),
                      K.AMBER, 0.9)
    # 出口端口
    fill_rect(fr, (696, 326, 706, 334), K.AMBER, 0.85 * K.clamp(p * 3))


# ---------------------------------------------------------------------------
# 收尾：缩成方块图标
# ---------------------------------------------------------------------------
def draw_outro(fr, t, snap):
    if t < T_OUT:
        return
    k = K.ease_in_out_cubic(K.clamp((t - T_OUT) / 1.15))
    w = int(round(K.lerp(PANEL[2] - PANEL[0], 128, k)))
    h = int(round(K.lerp(PANEL[3] - PANEL[1], 128, k)))
    # 中心从面板原位（510, 251）拉到（400, 258），第 0 帧与面板逐像素对齐
    cx = int(round(K.lerp((PANEL[0] + PANEL[2]) / 2.0, 400, k)))
    cy = int(round(K.lerp((PANEL[1] + PANEL[3]) / 2.0, 258, k)))
    ibox = (400 - 64, 258 - 64, 400 + 64, 258 + 64)

    if snap is not None and w > 8:
        a = 1.0 - K.clamp((t - 22.15) / 0.35)
        if a > 0:
            im = snap.resize((w, h), Image.LANCZOS).convert("RGBA")
            al = im.getchannel("A").point(lambda v: int(v * a))
            im.putalpha(al)
            fr.img.paste(im, (cx - w // 2, cy - h // 2), im)

    ai = K.clamp((t - 22.15) / 0.35)
    if ai > 0:
        blit(fr, BLOCK_ICON, ibox, alpha=ai)
        fr.box(ibox, color=K.SLATE, alpha=0.6 * ai, width=2, corner=4)
        aa = K.clamp((t - 22.55) / 0.30) * ai
        fr.text((cx, ibox[3] + 12), "PANEL / SAVED", size=9, color=K.AMBER,
                cn=False, anchor="ma", alpha=0.85 * aa, shadow=False)

    # 标注：入口在终端左列
    ag = K.clamp((t - 22.55) / 0.30)
    if ag > 0:
        fr.hairline((ibox[2] + 6, cy), (ibox[2] + 6 + 26 * ag, cy),
                    color=K.AMBER, alpha=0.8, width=2)
        at = K.clamp((t - 22.80) / 0.30)
        if at > 0:
            fr.text((ibox[2] + 40, cy - 18), "入口在终端左列", size=18,
                    color=K.STEEL, cn=True, alpha=at, shadow=False)
            fr.text((ibox[2] + 40, cy + 6), "TERMINAL / LEFT COLUMN", size=9,
                    color=K.SLATE, cn=False, alpha=0.8 * at, shadow=False)


# ---------------------------------------------------------------------------
# 字幕
# ---------------------------------------------------------------------------
def draw_captions(fr, f, fps):
    for start, end, text in CUES:
        f0 = int(round(start * fps))
        f1 = int(round(end * fps))
        if f < f0 or f >= f1:
            continue
        a = min(K.clamp((f - f0 + 1) / 4.0), K.clamp((f1 - f) / 4.0))
        fr.caption(text, alpha=K.smoothstep(a), size=30, color=K.STEEL, y=486)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def draw_frame(f, n, fps, snap=None):
    t = f / float(fps)
    fr = K.Frame()
    fr.clear()
    fr.hud_grid(alpha=0.09, step=40)

    if t < T_OUT:
        draw_panel(fr, t)
        draw_controls(fr, t)
        draw_columns(fr, t)
        draw_rail(fr, t)
        draw_fire(fr, t)
    else:
        draw_outro(fr, t, snap)

    fr.scanlines(alpha=0.045)
    fr.vignette(alpha=0.30)
    draw_captions(fr, f, fps)
    return fr


def build(n, fps):
    snap = None
    f_out = int(round(T_OUT * fps))
    for f in range(n):
        fr = draw_frame(f, n, fps, snap)
        if f == f_out - 1:
            snap = fr.img.crop(PANEL).copy()
        yield fr.img


def parse_args():
    ap = argparse.ArgumentParser(description="S05 panel workshop renderer (24.0 s)")
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    ap.add_argument("--fps", type=int, default=K.FPS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--preview", default=None)
    return ap.parse_args()


def main():
    args = parse_args()
    n, fps = int(args.frames), int(args.fps)
    if n <= 0:
        raise SystemExit("error: --frames must be positive")

    preview_idx = sorted({0, n // 4, n // 2, (3 * n) // 4, n - 1})
    want = set(preview_idx) if args.preview else set()
    shots = {}
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)

    snap = None
    f_out = int(round(T_OUT * fps))

    def gen():
        nonlocal snap
        for f in range(n):
            fr = draw_frame(f, n, fps, snap)
            if f == f_out - 1:
                snap = fr.img.crop(PANEL).copy()
            if f in want:
                shots[f] = fr.img.copy()
            yield fr.img

    written = K.write_video(gen(), args.out, fps=fps, crf=16, progress=96,
                            label=SEG_ID)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))

    if args.preview:
        for f in preview_idx:
            p = os.path.join(args.preview, "shot_05_f%03d.png" % f)
            if os.path.exists(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
            shots[f].save(p)
        print("%s: %d preview frames -> %s" % (SEG_ID, len(preview_idx),
                                              args.preview), flush=True)
    print("%s: %d frames -> %s" % (SEG_ID, written, args.out), flush=True)


if __name__ == "__main__":
    main()
