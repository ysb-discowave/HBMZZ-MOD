#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S04 - 实时屏幕 / Live telemetry - 480 frames (20.000 s @ 24 fps).

DFC 监控屏正面居中，屏幕区是深底 HUD。四拍：
    0.0- 5.0 s  标题栏 AHBSSMFI-OS | 01 核心 · 堆芯遥测 逐字打出（POST 自检）
    5.0-11.0 s  双环量表扫到位：温度 3412 / 熔点 6000 C / 稳定度 74%
   11.0-16.0 s  七路结构灯柱逐柱亮起（STA/ABS/PLS/EMT/INJ/EXC/CMT）
   16.0-20.0 s  右侧数据滚动：储能 / 输出 / 增益 / 崩溃风险 + 底部跑马灯

硬切：head_fade=0 / tail_fade=0 / flash_in=0（段内不做首尾淡变）。

Run:
    py -3.12 tools/video/promo/shots/shot_04.py --frames 480 --fps 24 \
        --out tools/video/promo/out/build/seg_S04.mp4 \
        --preview tools/video/promo/out/check/shot_04
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K  # noqa: E402

Image = K.Image
ImageDraw = K.ImageDraw

SEG_ID = "S04"
DEFAULT_FRAMES = 480
DEFAULT_OUT = "tools/video/promo/out/build/seg_S04.mp4"
HEAD_FADE, TAIL_FADE, FLASH_IN = 0, 0, 0

MONITOR_TEX = "hbmzz/textures/blocks/dfc_monitor_front.png"

# 屏体：九宫格取 dfc_monitor_front 的边框（1px 暗线 / 1px 钢灰 / 1px 亮线），
# 中间那圈带绿的屏幕像素一律不用，换成深底 HUD。
BODY = (112, 34, 848, 430)          # 736 x 396
BEZEL = 30
SCR = (BODY[0] + BEZEL, BODY[1] + BEZEL, BODY[2] - BEZEL, BODY[3] - BEZEL)
HUD = (SCR[0] + 14, SCR[1] + 12, SCR[2] - 14, SCR[3] - 10)

# 表头真值：DfcScreen.ribbon() — OS_NAME + 页面标题（pageTitle: "01  核心 · 堆芯遥测"）
TITLE = "AHBSSMFI-OS | 01  核心 \u00b7 堆芯遥测"

GAUGE = (330, 236, 88, 60)           # cx, cy, rOut, rIn
RACK_X0, RACK_X1 = 462, 796
ROW_Y0, ROW_DY = 132, 20

TAGS = ("STA", "ABS", "PLS", "EMT", "INJ", "EXC", "CMT")
# 中文短名取自屏体自身：DfcScreen.java:1020 的 MIMIC 节点名
# {"稳定", "吸收", "脉冲", "发射", "注入", "交换", "中子"}
TAG_CN = ("稳定", "吸收", "脉冲", "发射", "注入", "交换", "中子")
COUNTS = (10, 9, 8, 7, 5, 4, 2)

TEMP_TARGET = 3412
MELT_TARGET = 6000
STAB_TARGET = 74
ENERGY = (("储能", "50.00 kSPK"), ("输出", "82.00 kSPK/s"), ("增益", "1.180"))
RISK_TARGET = 34.0

CUES = (
    (0.20, 5.30, "DFC 监控屏，01 核心。"),
    (5.30, 10.85, "温度、熔点、稳定度，一屏读完。"),
    (11.10, 15.85, "七路结构，一柱一柱地亮。"),
    (16.10, 19.84, "这些数字不是贴图。"),
)

POST_LINES = (
    (1.05, "POST 01  MEM 768K .......... OK"),
    (1.95, "POST 02  SENSOR BUS ........ OK"),
    (2.85, "POST 03  CORE 01 LINK ....... OK"),
)

TICKER = ("STORAGE 50.00 KSPK   OUTPUT 82.00 KSPK/S   GAIN 1.180   "
          "COLLAPSE 34.0%   //   CORE 01   LINK OK   ")


# ---------------------------------------------------------------------------
# 局部绘制辅助（kit 只提供描边，这里补填充 / 圆弧 / 裁剪滚动）
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


def arc_line(fr, cx, cy, r, a0, a1, color, alpha=1.0, width=4):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0 or a1 <= a0:
        return
    pad = width + 2
    left, top = cx - r - pad, cy - r - pad
    size = 2 * (r + pad) + 1
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.arc([pad, pad, size - 1 - pad, size - 1 - pad], a0, a1,
          fill=color[:3] + (a,), width=int(width))
    fr.img.paste(layer, (left, top), layer)


def seg_bar(fr, box, n, ratio, color, alpha=1.0, gap=1):
    x0, y0, x1, y1 = [float(v) for v in box]
    w = (x1 - x0 - gap * (n - 1)) / float(n)
    lit = K.clamp(ratio) * n
    for i in range(n):
        sx = x0 + i * (w + gap)
        fill_rect(fr, (sx, y0, sx + w, y1), K.SLATE, 0.30 * alpha)
        f = K.clamp(lit - i)
        if f > 0:
            fill_rect(fr, (sx, y0, sx + w, y1), color, 0.95 * alpha * f)


def scrolling_line(fr, box, s, offset, size, color, alpha=1.0):
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    w, h = x1 - x0, y1 - y0
    if w <= 0 or h <= 0 or alpha <= 0:
        return
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    font = K.font_mono(size)
    total = font.getlength(s) + 36.0
    x = -float(offset) % total
    while x < w:
        d.text((x, 0), s, font=font, fill=color[:3] + (255,))
        x += total
    a = layer.getchannel("A").point(lambda v: int(v * K.clamp(alpha)))
    layer.putalpha(a)
    fr.img.paste(layer, (x0, y0), layer)


def typed_width(s, size):
    x = 0.0
    for ch in s:
        f = K.font_cn(size) if not ch.isascii() else K.font_mono(size)
        x += f.getlength(ch)
    return x


def typed(fr, xy, s, shown, size, color, alpha=1.0):
    """按字符逐个画（中英各用各的字体），只画前 shown 个字符。"""
    x, y = float(xy[0]), float(xy[1])
    for ch in s[:max(0, int(shown))]:
        cn = not ch.isascii()
        f = K.font_cn(size) if cn else K.font_mono(size)
        fr.text((x, y), ch, size=size, color=color, cn=cn, anchor="la", alpha=alpha)
        x += f.getlength(ch)
    return x


# ---------------------------------------------------------------------------
# 屏体（静态，缓存一次）
# ---------------------------------------------------------------------------
_body_cache = {}


def body_layer():
    layer = _body_cache.get(BODY)
    if layer is not None:
        return layer

    src = Image.open(os.path.join(K.assets_root(), MONITOR_TEX)).convert("RGBA")
    x0, y0, x1, y1 = BODY
    bx = 3
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))
    iw, ih = (x1 - x0) - 2 * BEZEL, (y1 - y0) - 2 * BEZEL

    def put(im, px, py):
        layer.paste(im, (px, py), im)

    put(src.crop((0, 0, bx, bx)).resize((BEZEL, BEZEL), Image.NEAREST), 0, 0)
    put(src.crop((16 - bx, 0, 16, bx)).resize((BEZEL, BEZEL), Image.NEAREST),
        BEZEL + iw, 0)
    put(src.crop((0, 16 - bx, bx, 16)).resize((BEZEL, BEZEL), Image.NEAREST),
        0, BEZEL + ih)
    put(src.crop((16 - bx, 16 - bx, 16, 16)).resize((BEZEL, BEZEL), Image.NEAREST),
        BEZEL + iw, BEZEL + ih)
    put(src.crop((bx, 0, 16 - bx, bx)).resize((iw, BEZEL), Image.NEAREST), BEZEL, 0)
    put(src.crop((bx, 16 - bx, 16 - bx, 16)).resize((iw, BEZEL), Image.NEAREST),
        BEZEL, BEZEL + ih)
    put(src.crop((0, bx, bx, 16 - bx)).resize((BEZEL, ih), Image.NEAREST), 0, BEZEL)
    put(src.crop((16 - bx, bx, 16, 16 - bx)).resize((BEZEL, ih), Image.NEAREST),
        BEZEL + iw, BEZEL)

    d = ImageDraw.Draw(layer)
    d.rectangle([BEZEL, BEZEL, BEZEL + iw - 1, BEZEL + ih - 1], fill=K.BG + (255,))
    _body_cache[BODY] = layer
    return layer


def draw_body(fr, t):
    x0, y0, x1, y1 = BODY
    fill_rect(fr, (x0 + 18, y1 + 4, x1 - 18, y1 + 14), K.BG, 0.55)
    fr.img.paste(body_layer(), (x0, y0), body_layer())
    # 压一档，让钢灰边框退到深底后面，别在暗场上抢眼
    fill_rect(fr, BODY, K.BG, 0.16)
    fr.box((SCR[0], SCR[1], SCR[2], SCR[3]), color=K.SLATE, alpha=0.45,
           width=1, corner=0)

    # 四角琥珀刻度
    ck, a = 12, 0.5
    for (px, py, dx, dy) in ((HUD[0], HUD[1], 1, 1), (HUD[2], HUD[1], -1, 1),
                             (HUD[0], HUD[3], 1, -1), (HUD[2], HUD[3], -1, -1)):
        fr.hairline((px, py), (px + dx * ck, py), color=K.AMBER, alpha=a, width=1)
        fr.hairline((px, py), (px, py + dy * ck), color=K.AMBER, alpha=a, width=1)

    # 屏幕内部的慢速扫描亮带
    band = HUD[1] + int((t * 26.0) % max(1, HUD[3] - HUD[1]))
    fill_rect(fr, (SCR[0] + 2, band, SCR[2] - 2, band + 2), K.STEEL, 0.025)


# ---------------------------------------------------------------------------
# 标题栏
# ---------------------------------------------------------------------------
def draw_header(fr, t):
    n = max(0, min(len(TITLE), int((t - 0.35) * 9.0)))
    typed(fr, (HUD[0] + 4, HUD[1] + 2), TITLE, n, 14, K.STEEL)

    if 0.35 <= t < 4.65:
        x = HUD[0] + 4 + typed_width(TITLE[:n], 14)
        on = (n < len(TITLE)) or ((int(t * 24) // 4) % 2 == 0)
        if on:
            fill_rect(fr, (x + 2, HUD[1] + 3, x + 9, HUD[1] + 18), K.AMBER, 0.9)

    if t >= 2.4:
        a = K.clamp((t - 2.4) / 0.4)
        fr.text((HUD[2] - 4, HUD[1] + 3), "LINK OK", size=9, color=K.SLATE,
                cn=False, anchor="ra", alpha=a, shadow=False)
        fill_rect(fr, (HUD[2] - 58, HUD[1] + 7, HUD[2] - 52, HUD[1] + 13),
                  K.AMBER, 0.85 * a)

    fr.hairline((HUD[0] + 4, HUD[1] + 28), (HUD[2] - 4, HUD[1] + 28),
                color=K.SLATE, alpha=0.55, width=1)


# ---------------------------------------------------------------------------
# 开机自检（0.9-5.0 s）
# ---------------------------------------------------------------------------
def draw_boot(fr, t):
    # 自检页在 4.98 s 硬切到主界面（真机也是换页，不做淡变）
    if t < 0.9 or t >= 4.98:
        return
    a = K.clamp((t - 0.9) / 0.35)
    x = HUD[0] + 14
    y = HUD[1] + 104

    fr.text((x, y), "AHBSSMFI-OS 4.2", size=10, color=K.SLATE, cn=False,
            alpha=0.9 * a, shadow=False)
    fr.text((x + 132, y), "SELF TEST", size=9, color=K.AMBER, cn=False,
            alpha=0.8 * a, shadow=False)

    for i, (t0, line) in enumerate(POST_LINES):
        if t < t0:
            continue
        ly = y + 24 + i * 16
        aa = K.clamp((t - t0) / 0.2) * a
        fr.text((x, ly), line, size=9, color=K.STEEL, cn=False, alpha=0.7 * aa,
                shadow=False)
        mx = x + int(typed_width(line, 9)) + 8
        fill_rect(fr, (mx, ly + 3, mx + 5, ly + 8), K.AMBER, 0.75 * aa)

    # 分段式开机进度条
    prog = K.clamp((t - 1.0) / 3.55)
    seg_bar(fr, (x, y + 84, x + 380, y + 92), 24, prog, K.AMBER, a, gap=3)
    fr.text((x + 392, y + 83), "%d%%" % int(prog * 100), size=9, color=K.SLATE,
            cn=False, alpha=0.9 * a, shadow=False)


# ---------------------------------------------------------------------------
# 双环量表
# ---------------------------------------------------------------------------
def gauge_state(t):
    u_t = K.ease_out_cubic(K.clamp((t - 5.05) / 1.55))
    temp = TEMP_TARGET * u_t
    if u_t < 1.0:
        temp += 6.0 * math.sin(t * 47.0) * (1.0 - u_t)
    u_m = K.ease_out_cubic(K.clamp((t - 5.55) / 1.25))
    melt = MELT_TARGET * u_m
    u_s = K.ease_out_cubic(K.clamp((t - 5.35) / 1.75))
    stab = STAB_TARGET * u_s
    return (int(round(K.clamp(temp, 0, TEMP_TARGET))), int(round(melt)),
            int(round(stab)), K.clamp(temp / float(MELT_TARGET)),
            K.clamp(stab / 100.0))


def draw_gauge(fr, t):
    if t < 4.98:
        return
    a = 1.0
    cx, cy, r_out, r_in = GAUGE
    temp, melt, stab, heat_ratio, stab_ratio = gauge_state(t)
    a0, sweep = 135.0, 270.0

    arc_line(fr, cx, cy, r_out, a0, a0 + sweep, K.SLATE, 0.30 * a, 6)
    arc_line(fr, cx, cy, r_in, a0, a0 + sweep, K.SLATE, 0.30 * a, 4)
    if heat_ratio > 0.001:
        arc_line(fr, cx, cy, r_out, a0, a0 + sweep * heat_ratio, K.AMBER, 0.95 * a, 6)
    if stab_ratio > 0.001:
        arc_line(fr, cx, cy, r_in, a0, a0 + sweep * stab_ratio, K.STEEL, 0.85 * a, 4)

    for i in range(11):
        ang = math.radians(a0 + sweep * i / 10.0)
        r0 = r_out - (11 if i % 5 else 14)
        fr.hairline((cx + math.cos(ang) * r0, cy - math.sin(ang) * r0),
                    (cx + math.cos(ang) * (r_out - 2), cy - math.sin(ang) * (r_out - 2)),
                    color=K.STEEL if i % 5 == 0 else K.SLATE,
                    alpha=(0.65 if i % 5 == 0 else 0.45) * a, width=1)

    if heat_ratio > 0.001:
        ang = math.radians(a0 + sweep * heat_ratio)
        fr.hairline((cx + math.cos(ang) * (r_out - 30), cy - math.sin(ang) * (r_out - 30)),
                    (cx + math.cos(ang) * (r_out + 5), cy - math.sin(ang) * (r_out + 5)),
                    color=K.AMBER, alpha=0.95 * a, width=3)

    fr.text((cx, cy - 44), "温度", size=9, color=K.SLATE, cn=True, anchor="ms",
            alpha=a, shadow=False)
    if t < 5.05:
        fr.text((cx, cy - 30), "----", size=34, color=K.SLATE, cn=False,
                anchor="ma", alpha=a)
        fr.text((cx, cy + 16), "稳定 ---%", size=9, color=K.SLATE, cn=True,
                anchor="ma", alpha=a, shadow=False)
    else:
        fr.text((cx, cy - 30), "%04d" % temp, size=34,
                color=K.AMBER if temp >= TEMP_TARGET else K.STEEL,
                cn=False, anchor="ma", alpha=a)
        fr.glow((cx - 90, cy - 40, cx + 90, cy + 10), color=K.AMBER,
                radius=9, alpha=0.22 * a)
        fr.text((cx, cy + 16), "稳定 %d%%" % stab, size=10, color=K.STEEL,
                cn=True, anchor="ma", alpha=a, shadow=False)

    if t >= 5.55:
        aa = K.clamp((t - 5.55) / 0.45) * a
        fr.text((cx, cy + r_out + 14), "熔点 %d \u2103" % melt, size=11,
                color=K.STEEL, cn=True, anchor="ms", alpha=aa, shadow=False)
    if t >= 6.20:
        aa = K.clamp((t - 6.20) / 0.4) * a
        fr.text((cx, cy + r_out + 28), "TEMP/MELT %.3f" % heat_ratio, size=8,
                color=K.SLATE, cn=False, anchor="ms", alpha=aa, shadow=False)


# ---------------------------------------------------------------------------
# 七路灯柱 / 右侧数据
# ---------------------------------------------------------------------------
def draw_rack(fr, t):
    if t < 4.98:
        return
    a = 1.0
    fr.text((RACK_X0, HUD[1] + 36), "结构灯柱", size=10, color=K.SLATE, cn=True,
            alpha=a, shadow=False)
    fr.text((RACK_X1, HUD[1] + 37), "7 CH", size=9, color=K.SLATE, cn=False,
            anchor="ra", alpha=a, shadow=False)
    fr.hairline((RACK_X0, HUD[1] + 50), (RACK_X1, HUD[1] + 50),
                color=K.SLATE, alpha=0.45 * a, width=1)

    for i in range(7):
        y = ROW_Y0 + i * ROW_DY
        lit = K.clamp((t - (11.05 + i * 0.60)) / 0.42)
        on = lit > 0.02

        fr.text((RACK_X0, y - 1), TAGS[i], size=10,
                color=K.AMBER if on else K.SLATE, cn=False, alpha=a, shadow=False)
        fr.text((RACK_X0 + 28, y + 1), TAG_CN[i], size=10,
                color=K.STEEL if on else K.SLATE, cn=True,
                alpha=(0.9 if on else 0.55) * a, shadow=False)

        ratio = (COUNTS[i] / 10.0) * K.ease_out_cubic(lit)
        seg_bar(fr, (RACK_X0 + 80, y + 1, RACK_X1 - 40, y + 8), 12, ratio,
                K.AMBER, a, gap=2)

        cnt = int(round(COUNTS[i] * K.ease_out_cubic(lit)))
        fr.text((RACK_X1, y - 1), "%02d" % cnt, size=10,
                color=K.STEEL if on else K.SLATE, cn=False, anchor="ra",
                alpha=a, shadow=False)

    fr.hairline((RACK_X0, 266), (RACK_X1, 266),
                color=K.SLATE, alpha=0.45 * a, width=1)


def _roll_value(s, u):
    """'50.00 kSPK' 按 u 滚动数字，u=1 时原样返回。"""
    if u >= 0.999:
        return s
    start = 0
    while start < len(s) and not (s[start].isdigit()):
        start += 1
    end = start
    while end < len(s) and (s[end].isdigit() or s[end] == "."):
        end += 1
    head, num, unit = s[:start], s[start:end], s[end:]
    try:
        target = float(num)
    except ValueError:
        return s
    dec = len(num.split(".")[1]) if "." in num else 0
    val = target * u
    if u < 0.55:
        val = max(0.0, val + target * 0.07 * math.sin(u * 61.0))
    return head + (("%." + str(dec) + "f") % val) + unit


def draw_energy(fr, t):
    """储能 / 输出 / 增益 / 崩溃风险：6 s 起先立暗占位，16 s 起逐行跳数。"""
    if t < 6.0:
        return
    base = 272
    for i, (label, value) in enumerate(ENERGY):
        y = base + i * 18
        t0 = 16.05 + i * 0.16
        a = K.clamp((t - t0) / 0.30)
        if a <= 0:
            fr.text((RACK_X0, y), label, size=10, color=K.SLATE, cn=True,
                    alpha=0.72, shadow=False)
            fr.text((RACK_X1, y - 2), "- -", size=11, color=K.SLATE, cn=False,
                    anchor="ra", alpha=0.55, shadow=False)
            continue
        fr.text((RACK_X0, y), label, size=10, color=K.SLATE, cn=True,
                alpha=a, shadow=False)
        u = K.ease_out_cubic(K.clamp((t - t0) / 0.55))
        fr.text((RACK_X1, y - 2), _roll_value(value, u), size=12, color=K.STEEL,
                cn=False, anchor="ra", alpha=a)

    y = base + 58
    t0 = 16.62
    a = K.clamp((t - t0) / 0.35)
    if a <= 0:
        fr.text((RACK_X0, y), "崩溃风险", size=10, color=K.SLATE, cn=True,
                alpha=0.72, shadow=False)
        seg_bar(fr, (RACK_X0, y + 16, RACK_X1, y + 23), 18, 0.0, K.AMBER,
                0.6, gap=2)
        return
    fr.text((RACK_X0, y), "崩溃风险", size=10, color=K.SLATE, cn=True,
            alpha=a, shadow=False)
    u = K.ease_out_cubic(K.clamp((t - t0) / 0.75))
    fr.text((RACK_X1, y - 2), "%.1f%%" % (RISK_TARGET * u), size=12,
            color=K.STEEL, cn=False, anchor="ra", alpha=a)
    seg_bar(fr, (RACK_X0, y + 16, RACK_X1, y + 23), 18,
            (RISK_TARGET / 100.0) * u, K.AMBER, a, gap=2)


def draw_rule(fr, t):
    """量表与数据列之间一条竖分隔线（结构感，不抢读数）。"""
    a = K.clamp((t - 4.98) / 0.12) * 0.35
    fr.hairline((440, 112), (440, 352), color=K.SLATE, alpha=a, width=1)



def draw_footer(fr, t):
    y = 364
    fr.hairline((HUD[0], y - 2), (HUD[2], y - 2), color=K.SLATE, alpha=0.35,
                width=1)
    if t < 16.0:
        fr.text((HUD[0] + 2, y + 1),
                "CORE 01 \u00b7 SAMPLE 20MS \u00b7 SERVER AUTHORITATIVE",
                size=8, color=K.SLATE, cn=False, alpha=0.55, shadow=False)
        return
    scrolling_line(fr, (HUD[0] + 2, y, HUD[2] - 2, y + 11), TICKER,
                   (t - 16.0) * 42.0, 8, K.SLATE,
                   0.85 * K.clamp((t - 16.0) / 0.4))


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
def draw_frame(f, n, fps):
    t = f / float(fps)
    fr = K.Frame()
    fr.clear()
    fr.hud_grid(alpha=0.07, step=40)

    draw_body(fr, t)
    draw_header(fr, t)
    draw_boot(fr, t)
    draw_gauge(fr, t)
    draw_rule(fr, t)
    draw_rack(fr, t)
    draw_energy(fr, t)
    draw_footer(fr, t)

    fr.scanlines(alpha=0.05)
    fr.vignette(alpha=0.30)
    draw_captions(fr, f, fps)
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n, fps).img


def parse_args():
    ap = argparse.ArgumentParser(description="S04 live telemetry renderer (20.0 s)")
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

    def gen():
        for f in range(n):
            fr = draw_frame(f, n, fps)
            if f in want:
                shots[f] = fr.img.copy()
            yield fr.img

    written = K.write_video(gen(), args.out, fps=fps, crf=16, progress=96,
                            label=SEG_ID)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))

    if args.preview:
        for f in preview_idx:
            p = os.path.join(args.preview, "shot_04_f%03d.png" % f)
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
