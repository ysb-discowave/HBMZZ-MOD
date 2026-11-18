#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S03 硬件 / Hardware - 624 frames (26.000 s @ 24 fps).

分镜（SEGMENTS.md S03）——全片第一次出现"实体感"，四拍各 6-7 秒:
    0.0-6.0   等距投影的机箱从线框（只有边）渐变为实体（顶面 #48525f、
              正面稍暗、侧面最深），吊架横梁在上方
    6.0-13.0  剖面拆解：吊架 / 2x2 面板 / 30 度倾角 三处引出标注线
    13.0-20.0 视角缓慢旋转（等距角 -20 度 -> +10 度），露出四块面板拼缝
    20.0-26.0 推近到单块面板，面板上浮现控件与屏幕，右侧标注
              与 DFC 监视屏的对应关系

Pillow 画不了高光与反射，这里只用「三块四边形拼出的等距盒体 + 明暗差 + 细线」，
所有面都是真 3D 点投影出来的，旋转与推近因此是同一套参数的连续变化。
转场：首 6 帧琥珀闪入场（0.6 -> 0），末尾硬切。
CLI（CONTRACT §7.2）::

    py -3.12 tools/video/promo/shots/shot_03.py --frames 624 --fps 24 \
        --out tools/video/promo/out/build/seg_S03.mp4 \
        --preview tools/video/promo/out/check/shot_03
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K  # noqa: E402

from PIL import Image, ImageDraw  # noqa: E402

SEG_ID = "S03"
HEAD_FADE, TAIL_FADE = 0, 0
FLASH_IN = 6

AMBER = K.AMBER
STEEL = K.STEEL
SLATE = K.SLATE

CAPTIONS = (
    (5, 140, "吊装式工业显示器。", 30, STEEL),
    (148, 308, "2×2 格，一台顶四块。", 30, STEEL),
    (316, 476, "吊架、面板、三十度倾角，一套外壳。", 30, STEEL),
    (484, 624, "和 DFC 监视屏，同一套倾角。", 30, STEEL),
)

# 机箱（世界坐标，单位=像素，未乘镜头缩放）
BW, BD, BH = 460.0, 200.0, 300.0
PANEL_GAP = 14.0
PANEL_PAD = 16.0

# 三段镜头参数
T_SPLIT = 6.0        # 线框 -> 实体完成 / 开始标注
T_CALL = 6.0
T_ROT0, T_ROT1 = 13.0, 20.0
T_PUSH0, T_PUSH1 = 20.0, 26.0

FIXED_ORIGIN = (480.0, 258.0)
PUSH_TARGET = (392.0, 300.0)


# ---------------------------------------------------------------- 基础绘制
def mix(color, t, base=K.BG):
    t = K.clamp(t)
    return tuple(int(round(color[i] * (1.0 - t) + base[i] * t)) for i in range(3))


def shade(color, k):
    """同色系明暗（不引入新色相）。"""
    return tuple(max(0, min(255, int(round(v * k)))) for v in color[:3])


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


def poly(fr, pts, color):
    if len(pts) < 3:
        return
    fr.draw.polygon([(int(round(x)), int(round(y))) for x, y in pts], fill=color[:3])


def draw_captions(fr, f):
    for f0, f1, text, size, color in CAPTIONS:
        if f < f0 or f >= f1:
            continue
        a = min(K.clamp((f - f0 + 1) / 4.0), K.clamp((f1 - f) / 4.0))
        fr.caption(text, alpha=K.smoothstep(a), size=size, color=color, y=486)


# ---------------------------------------------------------------- 镜头
class Cam:
    """等距投影：yaw 绕 Z 轴，c/d 控制两条轴的压缩（d->0 即压成正面视图）。"""

    def __init__(self, yaw, c, d, s, ox, oy):
        self.yaw = math.radians(yaw)
        self.ca, self.sa = math.cos(self.yaw), math.sin(self.yaw)
        self.c, self.d, self.s = c, d, s
        self.ox, self.oy = ox, oy

    def p(self, x, y, z):
        xp = x * self.ca - y * self.sa
        yp = x * self.sa + y * self.ca
        rx = (xp - yp) * self.c * self.s
        ry = (xp + yp) * self.d * self.s - (z - BH * 0.5) * self.s
        return (self.ox + rx, self.oy + ry)

    def rel(self, x, y, z):
        p = self.p(x, y, z)
        return (p[0] - self.ox, p[1] - self.oy)


PANELS = (
    (-BW / 2 + PANEL_PAD, -PANEL_GAP / 2, PANEL_PAD, BH / 2 - PANEL_GAP / 2),
    (PANEL_GAP / 2, BW / 2 - PANEL_PAD, PANEL_PAD, BH / 2 - PANEL_GAP / 2),
    (-BW / 2 + PANEL_PAD, -PANEL_GAP / 2, BH / 2 + PANEL_GAP / 2, BH - PANEL_PAD),
    (PANEL_GAP / 2, BW / 2 - PANEL_PAD, BH / 2 + PANEL_GAP / 2, BH - PANEL_PAD),
)
FOCUS_PANEL = PANELS[2]          # 推近的那一块（左上）
FOCUS_C = ((FOCUS_PANEL[0] + FOCUS_PANEL[1]) / 2.0,
           BD / 2,
           (FOCUS_PANEL[2] + FOCUS_PANEL[3]) / 2.0)


def cam_at(t):
    # 13-20 s：等距角 -20 -> +10
    yaw = K.lerp(-20.0, 10.0, K.smoothstep(K.clamp((t - T_ROT0) / 6.5)))
    c, d, s = 0.866, 0.5, 0.60
    # 20-26 s：压成正面视图并推近到单块面板
    k = K.smoothstep(K.clamp((t - T_PUSH0) / 4.2))
    if k > 0:
        yaw = K.lerp(yaw, 2.0, k)
        c = K.lerp(c, 1.0, k)
        d = K.lerp(d, 0.07, k)
        s = K.lerp(s, 1.62, k)
    # 全程极慢的呼吸，保证没有一帧是静止的
    yaw += 0.9 * math.sin(t * 0.55)
    cam = Cam(yaw, c, d, s, FIXED_ORIGIN[0], FIXED_ORIGIN[1])
    if k > 0:
        rc = cam.rel(*FOCUS_C)
        tx = K.lerp(FIXED_ORIGIN[0], PUSH_TARGET[0] - rc[0], k)
        ty = K.lerp(FIXED_ORIGIN[1], PUSH_TARGET[1] - rc[1], k)
        cam.ox, cam.oy = tx, ty
    return cam


# ---------------------------------------------------------------- 几何
def box_corners():
    """8 个角：索引 0-3 底面，4-7 顶面（逆时针，从上前左开始）。"""
    x0, x1 = -BW / 2, BW / 2
    y0, y1 = -BD / 2, BD / 2
    return (
        (x0, y0, 0), (x1, y0, 0), (x1, y1, 0), (x0, y1, 0),
        (x0, y0, BH), (x1, y0, BH), (x1, y1, BH), (x0, y1, BH),
    )


VISIBLE_EDGES = ((4, 5), (5, 6), (6, 7), (7, 4),      # 顶面
                 (3, 2), (2, 6), (6, 7), (7, 3),      # 正面
                 (1, 2), (2, 6), (6, 5), (5, 1))      # 右侧
HIDDEN_EDGES = ((0, 1), (1, 2), (2, 3), (3, 0), (0, 4), (1, 5), (0, 3))


def draw_gantry(fr, cam, solid):
    """上方的吊架横梁 + 两根立柱。"""
    bz0, bz1 = BH + 46, BH + 96
    bx0, bx1 = -BW / 2 - 60, BW / 2 + 60
    by0, by1 = -26, 26
    top = [cam.p(bx0, by0, bz1), cam.p(bx1, by0, bz1),
           cam.p(bx1, by1, bz1), cam.p(bx0, by1, bz1)]
    face = [cam.p(bx0, by1, bz1), cam.p(bx1, by1, bz1),
            cam.p(bx1, by1, bz0), cam.p(bx0, by1, bz0)]
    bottom = [cam.p(bx0, by0, bz0), cam.p(bx1, by0, bz0),
              cam.p(bx1, by1, bz0), cam.p(bx0, by1, bz0)]
    poly(fr, top, mix(shade(SLATE, 1.16), 1.0 - solid))
    poly(fr, face, mix(shade(SLATE, 0.92), 1.0 - solid))
    poly(fr, bottom, mix(shade(SLATE, 0.42), 1.0 - solid))

    # 立柱
    for sx in (-BW * 0.34, BW * 0.34):
        a = cam.p(sx - 11, -11, BH)
        b = cam.p(sx + 11, -11, BH)
        cc = cam.p(sx + 11, 11, bz0)
        dd = cam.p(sx - 11, 11, bz0)
        poly(fr, [a, b, cc, dd], mix(shade(SLATE, 0.60), 1.0 - solid))

    # 梁上的琥珀指示灯（每 2 秒一亮，给静态画面一点心跳）
    for i, sx in enumerate((-BW * 0.28, 0.0, BW * 0.28)):
        p = cam.p(sx, by1 + 1, bz1 - 26)
        on = 0.35 + 0.65 * abs(1.0 - ((i * 0.37 + 0.0) % 1.0))
        fill(fr, (p[0] - 3, p[1] - 2, p[0] + 3, p[1] + 2), AMBER,
             solid * (0.35 + 0.65 * on))


def draw_box(fr, cam, solid, seam_light):
    """三块面：顶面 / 正面 / 右侧面，以及正面的 2x2 面板拼缝。"""
    c = box_corners()
    P = [cam.p(*v) for v in c]

    top = mix(shade(SLATE, 1.0), 1.0 - solid)
    front = mix(shade(SLATE, 0.78), 1.0 - solid)
    side = mix(shade(SLATE, 0.50), 1.0 - solid)

    poly(fr, [P[4], P[5], P[6], P[7]], top)
    poly(fr, [P[1], P[2], P[6], P[5]], side)
    poly(fr, [P[3], P[2], P[6], P[7]], front)

    if solid <= 0.02:
        return

    # 2x2 面板：四块略微提亮的四边形，缝里露出正面的暗色
    for (px0, px1, pz0, pz1) in PANELS:
        q = [cam.p(px0, BD / 2, pz0), cam.p(px1, BD / 2, pz0),
             cam.p(px1, BD / 2, pz1), cam.p(px0, BD / 2, pz1)]
        tone = mix(shade(SLATE, 0.88 + 0.10 * seam_light), 1.0 - solid)
        poly(fr, q, tone)
        edge = mix(shade(SLATE, 1.35), 1.0 - 0.45 * solid)
        for i in range(4):
            fr.hairline(q[i], q[(i + 1) % 4], color=edge, alpha=1.0, width=1)

    # 每块面板四角的螺钉
    for (px0, px1, pz0, pz1) in PANELS:
        for sx in (px0 + 9, px1 - 9):
            for sz in (pz0 + 9, pz1 - 9):
                p = cam.p(sx, BD / 2 + 1, sz)
                fill(fr, (p[0] - 1, p[1] - 1, p[0] + 2, p[1] + 2),
                     mix(shade(SLATE, 1.5), 1.0 - 0.7 * solid))


def draw_edges(fr, cam, solid, wire):
    c = box_corners()
    P = [cam.p(*v) for v in c]
    e_vis = mix(STEEL, 0.55 + 0.30 * solid)
    e_hid = mix(STEEL, 0.90 + 0.06 * solid)
    for a, b in VISIBLE_EDGES:
        fr.hairline(P[a], P[b], color=e_vis, alpha=1.0, width=1 + int(solid * 1.2))
    if wire > 0.02:
        for a, b in HIDDEN_EDGES:
            fr.hairline(P[a], P[b], color=e_hid, alpha=1.0, width=1)


# ---------------------------------------------------------------- 标注
def leader(fr, p0, p1, k, color=AMBER):
    """从标注文字到目标的折线，k 控制生长。"""
    if k <= 0:
        return
    ex = K.lerp(p0[0], p1[0], k)
    ey = K.lerp(p0[1], p1[1], k)
    fr.hairline(p0, (ex, ey), color=color, alpha=1.0, width=1)
    if k >= 1.0:
        fr.hairline((p1[0] - 5, p1[1]), (p1[0] + 5, p1[1]), color=color, alpha=1.0, width=1)
        fr.hairline((p1[0], p1[1] - 5), (p1[0], p1[1] + 5), color=color, alpha=1.0, width=1)


def callout(fr, f, fps, anchor, target, text, t0, align="left"):
    """一条标注：琥珀细线生长 -> 终点十字 -> 小字。"""
    f0 = int(round(t0 * fps))
    if f < f0:
        return
    k = K.ease_out_cubic(K.clamp((f - f0) / 16.0))
    a = K.smoothstep(K.clamp((f - f0 - 8) / 10.0))
    leader(fr, anchor, target, k)
    if a <= 0:
        return
    tx = anchor[0] + (10 if align == "left" else -10)
    fr.text((tx, anchor[1] - 11), text, size=20,
            color=mix(STEEL, 0.25 * (1.0 - a)), cn=True,
            anchor="la" if align == "left" else "ra", shadow=False)


def draw_callouts(fr, f, fps, cam):
    """6-13 s：吊架 / 2x2 面板 / 30 度倾角。"""
    t = f / float(fps)
    if t < T_CALL:
        return
    beam = cam.p(0, -26, BH + 96)
    face = cam.p(BW * 0.30, BD / 2, BH * 0.62)
    corner = cam.p(-BW * 0.42, BD / 2, BH * 0.10)

    callout(fr, f, fps, (214, 104), (beam[0], beam[1] + 12), "吊架", 6.2)
    callout(fr, f, fps, (742, 250), (face[0] + 6, face[1]), "2×2 面板", 7.4)
    callout(fr, f, fps, (206, 392), (corner[0] - 8, corner[1]), "30° 倾角", 8.6)

    # 30 度的角标：在正面左下角画一个小扇形
    a0 = int(round(8.6 * fps))
    if f >= a0:
        k = K.smoothstep(K.clamp((f - a0) / 18.0))
        cx, cy = corner[0] + 4, corner[1] - 8
        r = 26 * k
        if r > 2:
            fr.draw.arc([cx - r, cy - r, cx + r, cy + r], 268, 356,
                        fill=mix(AMBER, 0.15), width=1)


def draw_push_annotation(fr, f, fps, cam):
    """20-26 s：右侧标注「和 DFC 监视屏同一套倾角」。"""
    t = f / float(fps)
    if t < 21.0:
        return
    a = K.smoothstep(K.clamp((t - 21.0) / 1.2))
    px, py = FOCUS_C[0], FOCUS_C[2]
    anchor = cam.p(FOCUS_PANEL[1], BD / 2, py)
    mon = (700, 176, 764, 240)
    fr.tex("hbmzz/textures/blocks/dfc_monitor_front.png", mon, alpha=a)
    fr.box(mon, color=mix(SLATE, 0.30), alpha=1.0, width=1, corner=2)
    fr.text((700, 250), "DFC 监视屏", size=20, color=mix(STEEL, 0.30 * (1 - a)),
            cn=True, shadow=False)
    fr.text((700, 276), "SAME ENCLOSURE / 30°", size=11,
            color=mix(SLATE, 0.10), cn=False, shadow=False)
    # 虚线连到面板
    k = K.smoothstep(K.clamp((t - 21.4) / 1.0))
    if k > 0:
        x0, y0 = mon[0] - 6, (mon[1] + mon[3]) / 2
        x1, y1 = anchor[0] + 6, anchor[1]
        steps = 9
        for i in range(int(steps * k)):
            xa = K.lerp(x0, x1, i / float(steps))
            ya = K.lerp(y0, y1, i / float(steps))
            xb = K.lerp(x0, x1, (i + 0.5) / float(steps))
            yb = K.lerp(y0, y1, (i + 0.5) / float(steps))
            fr.hairline((xa, ya), (xb, yb), color=mix(AMBER, 0.25), alpha=1.0, width=1)


def draw_panel_controls(fr, t, cam):
    """24 s 起：推近的那块面板上浮现真实控件与屏幕。"""
    if t < 23.4:
        return
    a = K.smoothstep(K.clamp((t - 23.4) / 1.4))
    if a <= 0:
        return
    px0, px1, pz0, pz1 = FOCUS_PANEL
    q = [cam.p(px0, BD / 2, pz0), cam.p(px1, BD / 2, pz0),
         cam.p(px1, BD / 2, pz1), cam.p(px0, BD / 2, pz1)]
    xs = [p[0] for p in q]
    ys = [p[1] for p in q]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    w, h = x1 - x0, y1 - y0
    if w < 40 or h < 40:
        return
    # 屏区（深底）+ 面板正面的控件
    fr.tex("hbmzz/textures/models/control_panel/control_panel_front.png",
           (x0 + w * 0.06, y0 + h * 0.06, x0 + w * 0.94, y0 + h * 0.52), alpha=a)
    fill(fr, (x0 + w * 0.10, y0 + h * 0.10, x0 + w * 0.90, y0 + h * 0.48),
         mix(K.BG, 1.0 - a))
    fr.tex("hbmzz/textures/models/control_panel/display_7seg.png",
           (x0 + w * 0.16, y0 + h * 0.16, x0 + w * 0.44, y0 + h * 0.38), alpha=a)
    fr.tex("hbmzz/textures/models/control_panel/button_push.png",
           (x0 + w * 0.56, y0 + h * 0.18, x0 + w * 0.72, y0 + h * 0.34), alpha=a)
    fr.tex("hbmzz/textures/models/control_panel/dial_large.png",
           (x0 + w * 0.66, y0 + h * 0.58, x0 + w * 0.90, y0 + h * 0.90), alpha=a)
    fr.tex("hbmzz/textures/models/control_panel/slider_vertical.png",
           (x0 + w * 0.14, y0 + h * 0.56, x0 + w * 0.28, y0 + h * 0.92), alpha=a)
    fr.text((x0 + w * 0.34, y0 + h * 0.80), "3412", size=max(11, int(h * 0.12)),
            color=mix(AMBER, 1.0 - a), cn=False, shadow=False)


# ---------------------------------------------------------------- 背景
def draw_bg(fr, f, n):
    fr.hud_grid(alpha=0.075, step=48)
    # 一条极慢的竖向施工线，从左扫到右
    x = int((f * 0.9) % (K.W + 240)) - 120
    fr.hairline((x, 0), (x, K.H), color=mix(SLATE, 0.35), alpha=1.0, width=1)
    fr.scanlines(alpha=0.045)


def draw_tags(fr, f, t):
    a = K.smoothstep(K.clamp(t / 1.0))
    if a <= 0:
        return
    fr.hairline((40, 40), (48, 40), color=AMBER, alpha=1.0, width=3)
    fr.hairline((52, 40), (86, 40), color=mix(SLATE, 0.2), alpha=1.0, width=1)
    fr.text((40, 48), "S03 // ENCLOSURE", size=12, color=mix(SLATE, 0.1),
            cn=False, shadow=False)
    fr.text((K.W - 40, 48), "2×2 / 30 DEG", size=12, color=mix(SLATE, 0.1),
            cn=False, anchor="ra", shadow=False)


# ---------------------------------------------------------------- 主流程
def build(n, fps):
    for f in range(n):
        t = f / float(fps)
        solid = K.smoothstep(K.clamp((t - 1.0) / 4.2))          # 线框 -> 实体
        wire = K.clamp(1.0 - solid * 1.6)
        seam = 0.5 + 0.5 * math.sin(t * 1.7)
        cam = cam_at(t)

        fr = K.Frame()
        draw_bg(fr, f, n)
        draw_gantry(fr, cam, solid)
        draw_box(fr, cam, solid, seam)
        draw_edges(fr, cam, solid, wire)
        draw_callouts(fr, f, fps, cam)
        draw_panel_controls(fr, t, cam)
        draw_push_annotation(fr, f, fps, cam)
        fr.vignette(alpha=0.36)
        draw_tags(fr, f, t)
        draw_captions(fr, f)
        if f < FLASH_IN:
            fr.flash(AMBER, 0.6 * (1.0 - f / float(FLASH_IN - 1)))
        a = K.fade_alpha(f, n, head=HEAD_FADE, tail=TAIL_FADE)
        if a < 1.0:
            fr.flash((0, 0, 0), 1.0 - a)
        yield fr


def parse_args():
    ap = argparse.ArgumentParser(description="S03 renderer (hardware, 26.0 s)")
    ap.add_argument("--frames", type=int, default=624)
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--out", default="tools/video/promo/out/build/seg_S03.mp4")
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
                fr.save(os.path.join(args.preview, "shot_03_f%03d.png" % f))
            yield fr.img

    written = K.write_video(frames(), args.out, fps=fps, crf=16, progress=120,
                            label=SEG_ID)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))
    print("%s: %d frames -> %s" % (SEG_ID, written, args.out), flush=True)
    if args.preview:
        print("%s: %d preview frames -> %s" % (SEG_ID, len(idxs), args.preview),
              flush=True)


if __name__ == "__main__":
    main()
