#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S09 · 黑客入侵（16.000 s / 384 帧 / 全片 02:30.000–02:46.000）

    0.0– 4.0 s  硬切进来。深底。黑客平板从暗处浮起（alpha 0 → 1，四角支架展开），
                屏幕幽幽亮着，命令一条条打上去
    4.0–11.0 s  平板左移让位，右侧三条状态依次点亮：已连接 / 数据外流 / 致盲，
                每条一个琥珀方点脉冲（本段不许出现红）
    11.0–16.0 s 镜头右移：平板被推出左边缘，监测屏从右侧滑入，读数「检测到入侵写入」，
                底部 18 格恢复进度条走满

真实依据（README「黑客入侵」）：平板只开一个窗口、只发一行文本；三个状态
（DISCONNECT / DEGRADED / CUT）中监测屏「检测得到」靠的是世界级写入记录，
不是温度跳变；断链页面底部是 18 格恢复进度条。

贴图处理：本段只用品牌四色，而 `hacker_tablet.png` 自带绿色屏幕、`nuke_monitor_front.png`
自带红色条。为避免杂色，这里按**亮度**把真实贴图映射到钢色（brand duotone），
形状与像素颗粒仍来自原贴图（NEAREST 放大，不做插值）。

性能：kit 的 alpha<1 绘制会为每一笔分配整幅 960x540 的 RGBA 层（约 6–10 ms/笔）。
本脚本改用 `bl()` 把颜色预混到底色上、以 alpha=1 直接落笔，单帧从 0.9 s 降到 0.1 s 量级。

运行：
    py -3.12 tools/video/promo/shots/shot_09.py --frames 384 --fps 24 \\
        --out tools/video/promo/out/build/seg_S09.mp4 \\
        --preview tools/video/promo/out/check/shot_09
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

from PIL import Image, ImageDraw, ImageOps

BG, STEEL, AMBER, SLATE = K.BG, K.STEEL, K.AMBER, K.SLATE

SEG = "S09"
DEFAULT_FRAMES = 384
DEFAULT_OUT = "tools/video/promo/out/build/seg_S09.mp4"

TABLET = "hbmzz/textures/items/hacker_tablet.png"
MONITOR = "hbmzz/textures/blocks/nuke_monitor_front.png"
CAM_BODY = "rsaudio/textures/blocks/monitor_camera_body.png"
CAM_LENS = "rsaudio/textures/blocks/monitor_camera_lens.png"

# 镜头节拍（帧）
BEAT_B = 96         # 4.0 s  三条状态开始
BEAT_C = 264        # 11.0 s 镜头右移，监测屏进场
PAN0, PAN1 = 258, 276

# 字幕（段内帧）：(起, 止, 文本)
CUES = (
    (8, 101, "黑客平板。"),
    (101, 264, "你的屏幕，也可能是别人的。"),
    (270, 400, "但监测屏，检测得到。"),      # 末尾不淡出：硬切接 S11
)

# 平板屏幕上的命令行（显示帧, 文本, 是否高亮）
LOG = (
    (16, "> target 128 64 -32", True),
    (28, "  LOCK OK", False),
    (42, "> field heat", True),
    (54, "  3412.0", False),
    (68, "> set heat 0.0", True),
    (82, "  WRITE OK", False),
    (108, "> link estab.", True),
    (126, "  HANDSHAKE 0x2F", False),
    (149, "> exfil 12.4kb/s", True),
    (166, "  QUEUE 3 / 12", False),
    (190, "> cut", True),
    (204, "  LINK DOWN", False),
)

# 三条状态（点亮帧, 中文, 等宽注释）
STATUS = (
    (108, "已连接", "LINK ESTABLISHED"),
    (149, "数据外流", "DATA EXFIL 12.4 KB/S"),
    (190, "致盲", "SENSOR BLIND"),
)
ST_X0, ST_X1 = 524, 884
ST_Y = (170, 254, 338)


# ---------------------------------------------------------------- 小工具
def bl(color, alpha):
    """品牌色按 alpha 预混到深底上（不透明色），省掉整层合成。"""
    a = K.clamp(alpha)
    return tuple(int(round(BG[i] + (color[i] - BG[i]) * a)) for i in range(3))


def fill(fr, box, color, alpha=1.0):
    """实心矩形（kit 只提供描边）。"""
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 - x0 < 1 or y1 - y0 < 1:
        return
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), tuple(color[:3]) + (a,))
    fr.img.paste(layer, (x0, y0), layer)


_DUO = {}


def _duo(rel, tint, gamma, auto):
    """真实贴图 → 品牌单色调（按亮度映射），缓存到 RGBA。"""
    key = (rel, tint, gamma, auto)
    img = _DUO.get(key)
    if img is not None:
        return img
    src = Image.open(os.path.join(K.assets_root(), rel.replace("\\", "/"))).convert("RGBA")
    lum = src.convert("L")
    if auto:
        lum = ImageOps.autocontrast(lum, cutoff=0)
    chans = []
    for i in range(3):
        chans.append(lum.point(
            lambda v, i=i: int(round(BG[i] + (tint[i] - BG[i]) * (v / 255.0) ** gamma))))
    out = Image.merge("RGB", chans).convert("RGBA")
    out.putalpha(src.getchannel("A"))
    _DUO[key] = out
    return out


_SCALED = {}


def tex_duo(fr, rel, box, tint=STEEL, alpha=1.0, gamma=0.72, auto=False):
    """把贴图按亮度染成单色后贴进 box（NEAREST，保持像素锐利）。"""
    alpha = K.clamp(alpha)
    if alpha <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    bw, bh = x1 - x0, y1 - y0
    if bw <= 0 or bh <= 0:
        return
    key = (rel, tint, gamma, auto, bw, bh)
    img = _SCALED.get(key)
    if img is None:
        src = _duo(rel, tint, gamma, auto)
        img = src.resize((bw, bh), Image.NEAREST)
        _SCALED[key] = img
    if alpha < 0.999:
        img = img.copy()
        img.putalpha(img.getchannel("A").point(lambda v: int(v * alpha)))
    fr.img.paste(img, (x0, y0), img)


def cap_alpha(f, start, end, fin=4, fout=4):
    if f < start or f >= end:
        return 0.0
    a = 1.0
    if f - start < fin:
        a = min(a, (f - start + 1) / float(fin))
    if end - f <= fout:
        a = min(a, (end - f) / float(fout))
    return K.clamp(a)


_TXT = {}
_PAD = 3


def label(fr, xy, s, size=14, color=STEEL, cn=True, shadow=True):
    """等价于 fr.text(..., anchor="la")，但把栅格化结果缓存下来（省 ~1 ms/行）。"""
    if not s:
        return
    col = tuple(color[:3])
    key = (s, size, col, cn, shadow)
    tile = _TXT.get(key)
    if tile is None:
        font = K.font_cn(size) if cn else K.font_mono(size)
        w = int(font.getlength(s)) + _PAD * 2 + 3
        h = int(size * 1.62) + _PAD * 2 + 3
        tile = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(tile)
        if shadow:
            d.text((_PAD + 1, _PAD + 2), s, font=font, fill=(0, 0, 0, 255))
        d.text((_PAD, _PAD), s, font=font, fill=col + (255,))
        if len(_TXT) > 3000:
            _TXT.clear()
        _TXT[key] = tile
    fr.img.paste(tile, (int(round(xy[0])) - _PAD, int(round(xy[1])) - _PAD), tile)


_BG_CACHE = {}


def base_frame(grid=0.055, vign=0.40, scan=0.035):
    """网格 + 暗角 + 扫描线是静态层，烘一次，逐帧只做一次拷贝。"""
    key = (grid, vign, scan)
    cached = _BG_CACHE.get(key)
    if cached is None:
        tmp = K.Frame()
        tmp.hud_grid(alpha=grid, step=40)
        tmp.vignette(alpha=vign)
        tmp.scanlines(alpha=scan)
        cached = tmp.img
        _BG_CACHE[key] = cached
    fr = K.Frame()
    fr.img = cached.copy()
    fr.draw = ImageDraw.Draw(fr.img)
    return fr


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


# ---------------------------------------------------------------- 运动
def pan_x(f):
    """镜头右移：整场内容向左走 108 px（平板仍完整留在画内）。"""
    return -108.0 * K.ease_in_out_cubic(K.clamp((f - PAN0) / float(PAN1 - PAN0)))


def tablet_state(f):
    """(cx, cy, size, alpha)：从暗处浮起 → 左移让位 → 被推出左边缘。"""
    if f < 78:
        cx, size = 480.0, 240.0
    else:
        k = K.ease_in_out_cubic(K.clamp((f - 78) / 26.0))
        cx = K.lerp(480.0, 258.0, k)
        size = K.lerp(240.0, 214.0, k)
    cx += pan_x(f)
    rise = 1.0 - K.ease_out_cubic(K.clamp(f / 22.0))
    cy = 252.0 + 30.0 * rise
    a = K.clamp((f + 2) / 16.0)
    if f >= BEAT_C - 6:
        a *= K.lerp(1.0, 0.55, K.clamp((f - (BEAT_C - 6)) / 18.0))
    return cx, cy, size, a


def monitor_state(f):
    """(cx, cy, size, alpha)：11 s 从右侧滑入。"""
    if f < 250:
        return None
    k = K.ease_in_out_cubic(K.clamp((f - 250) / 26.0))
    cx = 700.0 + 210.0 * (1.0 - k)
    cy = 236.0
    return cx, cy, 286.0, K.clamp((f - 250) / 12.0)


# ---------------------------------------------------------------- 平板
def draw_tablet(fr, f, cx, cy, size, alpha):
    x0, y0 = cx - size / 2.0, cy - size / 2.0
    x1, y1 = x0 + size, y0 + size

    # 四角支架：从外向内收
    k = K.ease_out_cubic(K.clamp((f - 4) / 22.0))
    if k > 0:
        e = size * (0.06 + 0.10 * (1.0 - k))
        arm = size * 0.16
        c = bl(SLATE, 0.75 * k)
        for ax, ay, sx, sy in ((x0 - e, y0 - e, 1, 1), (x1 + e, y0 - e, -1, 1),
                               (x0 - e, y1 + e, 1, -1), (x1 + e, y1 + e, -1, -1)):
            fr.hairline((ax, ay), (ax + arm * sx, ay), color=c, width=1)
            fr.hairline((ax, ay), (ax, ay + arm * sy), color=c, width=1)

    tex_duo(fr, TABLET, (x0, y0, x1, y1), tint=STEEL, alpha=alpha, gamma=0.80)
    fr.box((x0, y0, x1, y1), color=bl(SLATE, 0.60 * alpha), width=1, corner=2)

    # 屏幕面板：比贴图自带的绿屏更大，铺在机身内框上（内框仍来自原贴图）
    px0, px1 = x0 + size * (2.3 / 16.0), x0 + size * (12.5 / 16.0)
    py0, py1 = y0 + size * (2.6 / 16.0), y0 + size * (12.4 / 16.0)
    fill(fr, (px0, py0, px1, py1), BG, 0.99 * alpha)
    fr.box((px0, py0, px1, py1), color=bl(SLATE, 0.55 * alpha), width=1, corner=0)

    # 幽幽亮着：极暗琥珀屏面 + 柔光
    pulse = 0.86 + 0.14 * abs(((f % 48) / 24.0) - 1.0)
    fill(fr, (px0, py0, px1, py1), AMBER, 0.070 * alpha * pulse)
    fr.hairline((px0, py0), (px1, py0), color=bl(AMBER, 0.55 * alpha), width=2)
    fr.glow((px0 - 20, py0 - 20, px1 + 20, py1 + 20), color=AMBER,
            radius=10, alpha=0.22 * alpha * pulse)

    # 命令滚动：显示最近 8 条
    shown = [(t, hl) for (tf, t, hl) in LOG if f >= tf][-8:]
    lh = max(11.0, (py1 - py0 - 14) / 8.0)
    ty = py0 + 7
    fs = max(8, int(round(size / 25.0)))          # 屏内字号随平板尺寸走
    for text, hl in shown:
        label(fr, (px0 + 8, ty), text, size=fs,
              color=bl(AMBER if hl else STEEL, (0.95 if hl else 0.70) * alpha),
              cn=False, shadow=False)
        ty += lh
    # 光标
    if shown and ((f // 8) % 2 == 0):
        cw = K.font_mono(fs).getlength("0")
        cxc = px0 + 8 + cw * len(shown[-1][0])
        fr.hairline((cxc, ty - lh + 2), (cxc, ty - 2),
                    color=bl(AMBER, 0.9 * alpha), width=2)

    # 屏内缓慢下扫的读线
    sweep = py0 + (py1 - py0) * (((f * 0.9) % 60) / 60.0)
    fr.hairline((px0 + 1, sweep), (px1 - 1, sweep), color=bl(STEEL, 0.12 * alpha), width=1)

    # 机身细节：下巴上的型号与电源指示、右侧电源条
    chin = py1 + size * 0.055
    if chin + 10 < y1:
        label(fr, (px0, chin), "TERMINAL 07", size=max(6, int(size / 34.0)),
              color=bl(SLATE, 0.85 * alpha), cn=False, shadow=False)
        led = 0.45 + 0.55 * abs(((f % 36) / 18.0) - 1.0)
        fill(fr, (px1 - 8, chin + 2, px1 - 3, chin + 7), AMBER, alpha * led)
    fill(fr, (x1 - 6, cy - 24, x1 - 3, cy + 24), STEEL, 0.22 * alpha)


# ---------------------------------------------------------------- 三条状态
def draw_status(fr, f):
    a_all = K.clamp((f - BEAT_B) / 10.0) * (1.0 - K.clamp((f - (PAN0 - 6)) / 12.0))
    if a_all <= 0.004:
        return
    dx = pan_x(f)

    label(fr, (ST_X0 + dx, ST_Y[0] - 44), "TABLET // STATUS", size=10,
          color=bl(SLATE, 0.95 * a_all), cn=False, shadow=False)
    fr.hairline((ST_X0 + dx, ST_Y[0] - 26), (ST_X1 + dx, ST_Y[0] - 26),
                color=bl(SLATE, 0.30 * a_all), width=1)

    for i, (on_f, name, note) in enumerate(STATUS):
        y = ST_Y[i]
        lit = f >= on_f
        k = K.clamp((f - on_f) / 8.0) if lit else 0.0
        a = a_all * (1.0 if lit else 0.32)

        # 方点：点亮后一直呼吸；点亮瞬间有一次外扩脉冲
        if lit:
            breath = 0.72 + 0.28 * abs(((f % 30) / 15.0) - 1.0)
            fill(fr, (ST_X0 + dx, y - 7, ST_X0 + dx + 14, y + 7), AMBER, a * breath)
            ring = 3.0 + 9.0 * K.clamp((f - on_f) / 10.0)
            fr.box((ST_X0 + dx - ring, y - 7 - ring, ST_X0 + dx + 14 + ring, y + 7 + ring),
                   color=bl(AMBER, (1.0 - K.clamp((f - on_f) / 10.0)) * 0.8 * a_all),
                   width=1, corner=1)
        else:
            fr.box((ST_X0 + dx, y - 7, ST_X0 + dx + 14, y + 7),
                   color=bl(SLATE, 0.55 * a_all), width=1, corner=1)

        label(fr, (ST_X0 + dx + 30, y - 22), name, size=26,
              color=bl(AMBER if lit else SLATE, a))
        label(fr, (ST_X0 + dx + 32, y + 16), note, size=10,
              color=bl(SLATE, a * 0.9), cn=False, shadow=False)
        fr.text((ST_X1 + dx, y - 8), "ON   " if lit else "---- ", size=11,
                color=bl(AMBER if lit else SLATE, a * 0.95), cn=False,
                anchor="ra", shadow=False)

        # 底线：未亮时只有暗线，点亮后琥珀从左侧铺满
        fr.hairline((ST_X0 + dx, y + 38), (ST_X1 + dx, y + 38),
                    color=bl(SLATE, 0.22 * a_all), width=1)
        if lit and k > 0:
            fr.hairline((ST_X0 + dx, y + 38),
                        (ST_X0 + dx + (ST_X1 - ST_X0) * K.ease_out_cubic(k), y + 38),
                        color=bl(AMBER, 0.85 * a_all), width=2)


# ---------------------------------------------------------------- 监测屏
def draw_monitor(fr, f, cx, cy, size, alpha):
    x0, y0 = cx - size / 2.0, cy - size / 2.0
    x1, y1 = x0 + size, y0 + size
    tex_duo(fr, MONITOR, (x0, y0, x1, y1), tint=STEEL, alpha=alpha, gamma=1.10)
    fr.box((x0, y0, x1, y1), color=bl(SLATE, 0.75 * alpha), width=2, corner=2)

    # 屏内：盖掉贴图自带的红条，读数由程序画
    px0, px1 = x0 + size * 0.19, x0 + size * 0.81
    py0, py1 = y0 + size * 0.22, y0 + size * 0.80
    fill(fr, (px0, py0, px1, py1), BG, 0.97 * alpha)
    fr.box((px0, py0, px1, py1), color=bl(SLATE, 0.45 * alpha), width=1, corner=0)

    k = K.clamp((f - 276) / 14.0)
    if k <= 0:
        return
    a = alpha * k
    tc = 0.70 + 0.30 * abs(((f % 36) / 18.0) - 1.0)          # 1.5 s 一次呼吸

    label(fr, (px0 + 9, py0 + 8), "INTRUSION // WRITE", size=9,
          color=bl(AMBER, a * tc), cn=False, shadow=False)
    label(fr, (px0 + 9, py0 + 22), "检测到入侵写入", size=17, color=bl(AMBER, a))
    fr.hairline((px0 + 9, py0 + 48), (px1 - 9, py0 + 48), color=bl(SLATE, 0.55 * a), width=1)

    rows = (("TGT", "128 64 -32"), ("FLD", "heat = 0.0"), ("SRC", "UNKNOWN"))
    ty = py0 + 56
    for j, (tag, val) in enumerate(rows):
        fa = a * K.clamp((f - 282 - 6 * j) / 8.0)
        label(fr, (px0 + 9, ty), tag, size=9, color=bl(SLATE, fa), cn=False, shadow=False)
        label(fr, (px0 + 40, ty), val, size=9, color=bl(STEEL, fa), cn=False, shadow=False)
        ty += 14

    # 18 格恢复进度条（README：断链页面底部 18 格）
    cells, gap = 18, 2
    bw = (px1 - px0 - 18) / float(cells)
    bx, by = px0 + 9, py1 - 16
    prog = K.clamp((f - 296) / 62.0)
    n_on = int(round(cells * prog))
    for c in range(cells):
        cx0 = bx + c * bw
        cw = bw - gap
        if cw <= 0:
            continue
        if c < n_on:
            fill(fr, (cx0, by, cx0 + cw, by + 7), AMBER, 0.9 * a)
        else:
            fr.box((cx0, by, cx0 + cw, by + 7), color=bl(SLATE, 0.45 * a),
                   width=1, corner=0)
    fr.text((px1 - 9, by - 15), "RECOVER %3d%%" % int(round(prog * 100)), size=8,
            color=bl(SLATE, a), cn=False, anchor="ra", shadow=False)

    # 四角框：每秒闪一次（README：四角红框每秒闪一次 → 这里用琥珀）
    blink = K.clamp((f % 24) / 12.0)
    blink = 0.35 + 0.65 * (1.0 - abs(blink * 2.0 - 1.0))
    arm = size * 0.10
    c = bl(AMBER, 0.75 * a * blink)
    for ax, ay, sx, sy in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        fr.hairline((ax, ay), (ax + arm * sx, ay), color=c, width=2)
        fr.hairline((ax, ay), (ax, ay + arm * sy), color=c, width=2)


def draw_camera(fr, f, alpha):
    """监测摄像头：见证者。与本段两块屏连成一条视线。"""
    if alpha <= 0.004:
        return
    cx, cy = 306.0, 128.0
    tex_duo(fr, CAM_BODY, (cx - 26, cy - 26, cx + 26, cy + 26), tint=STEEL,
            alpha=alpha, gamma=0.70)
    tex_duo(fr, CAM_LENS, (cx - 11, cy - 11, cx + 11, cy + 11), tint=STEEL,
            alpha=alpha * 0.95, gamma=0.70)
    fr.box((cx - 27, cy - 27, cx + 27, cy + 27), color=bl(SLATE, 0.5 * alpha),
           width=1, corner=2)
    label(fr, (cx - 30, cy + 32), "CAM 02", size=9, color=bl(SLATE, 0.85 * alpha),
          cn=False, shadow=False)
    # 视锥：两条细线指向监测屏
    fr.hairline((cx + 27, cy - 6), (556, 150), color=bl(AMBER, 0.22 * alpha), width=1)
    fr.hairline((cx + 27, cy + 8), (556, 330), color=bl(AMBER, 0.22 * alpha), width=1)


# ---------------------------------------------------------------- 主流程
def draw_frame(f, n):
    # 背景（网格 + 暗角 + 扫描线）已烘焙，第 0 帧就在，硬切进来不闪
    fr = base_frame()
    fr.hairline((40, 96), (920, 96), color=bl(SLATE, 0.16), width=1)
    label(fr, (40, 62), "S09 // OFFSITE LINK", size=11, color=bl(SLATE, 0.9),
          cn=False, shadow=False)
    fr.text((920, 62), "MONITOR NET 06", size=11, color=bl(SLATE, 0.9),
            cn=False, anchor="ra", shadow=False)
    link_a = K.clamp((f - 100) / 12.0)
    if link_a > 0:
        blink = 0.4 + 0.6 * abs(((f % 24) / 12.0) - 1.0)
        fill(fr, (800, 64, 806, 70), AMBER, link_a * blink)

    # 地平线：一条极淡的台面线
    fr.hairline((0, 412), (K.W, 412), color=bl(SLATE, 0.10), width=1)

    # 监测屏（先画，避免压住平板）
    ms = monitor_state(f)
    if ms:
        draw_monitor(fr, f, *ms)

    # 摄像头（11 s 以后出场）
    draw_camera(fr, f, K.clamp((f - 276) / 12.0))

    # 平板
    cx, cy, size, ta = tablet_state(f)
    draw_tablet(fr, f, cx, cy, size, ta)

    # 三条状态
    draw_status(fr, f)

    for start, end, text in CUES:
        a = cap_alpha(f, start, end)
        if a > 0:
            fr.caption(text, alpha=a, size=30, color=STEEL)
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n).img


def parse_args():
    ap = argparse.ArgumentParser(description="S09 hacker intrusion renderer (16.0 s)")
    ap.add_argument("--frames", type=int, default=DEFAULT_FRAMES)
    ap.add_argument("--fps", type=int, default=K.FPS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--preview", default=None)
    return ap.parse_args()


def main():
    args = parse_args()
    n, fps = int(args.frames), int(args.fps)
    if n <= 0:
        raise SystemExit("error: --frames must be > 0")

    idxs = sorted({0, n // 4, n // 2, (3 * n) // 4, n - 1}) if args.preview else []
    want = set(idxs)
    if args.preview:
        os.makedirs(args.preview, exist_ok=True)

    def frames():
        for f in range(n):
            fr = draw_frame(f, n)
            if f in want:
                save_png(fr.img, os.path.join(args.preview, "shot_09_f%03d.png" % f))
            yield fr.img

    written = K.write_video(frames(), args.out, fps=fps, crf=16, progress=96, label=SEG)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))
    print("%s: %d frames -> %s" % (SEG, written, args.out), flush=True)
    if args.preview:
        print("%s: %d preview frames -> %s" % (SEG, len(idxs), args.preview), flush=True)


if __name__ == "__main__":
    main()
