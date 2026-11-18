#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S10 · 核事故（26.000 s / 624 帧 / 全片 02:46.000–03:12.000）——全片顶点。

四拍：
    0.0– 7.0 s  五个方块排成一行（控制器 / 监视屏 / 核弹 / 事故显示器 / 代码广播器），
                每个下方一行职责小字。核弹居中。
    7.0–13.0 s  中间那颗核弹亮起（倒计时 T-5.0 → 0.0），一条红石沿沿顶部母线从中间
                向左、向右跑过五块方块：每被扫到一块，方块指示灯逐格亮起。
    13.0–21.0 s 八级代码条从底部逐格点亮：
                    GREEN 正常运行 → YELLOW 发生小插曲 → ORANGE 小型暴乱 → RED 重大事故
                 → MED 堆芯融毁事故 → OMEGA 堆芯爆炸事故 → ALPHA 核弹事件
                 → DELTA 设施毁灭级事故
                到 RED 时整屏抖一下（2 帧，2–3 px）。
    21.0–26.0 s DELTA 定格：整屏琥珀全亮 + 6 帧警示红 #b3261e，随后回落。
                末尾**不淡黑**：事故还没结束，硬切接 S11。

真实依据（README「核事故系统」）：
  · 五个方块分工互不重叠——nuke 只管壳体/倒计时/起爆且不读红石；nuke_controller
    每 2 tick 轮询 isBlockPowered、上升沿触发一次全图引爆；nuke_monitor 按间隔采样、
    不存业务状态；alert_display 零字段、纯读客户端缓存；code_broadcaster 红石上升沿
    把预设代码写进设施状态（FacilityState）。
  · 八级代码 GREEN / YELLOW / ORANGE / RED / MED / OMEGA / ALPHA / DELTA，
    RED 及以上视为警报；DELTA 是最高一级。
  · 警示红 #b3261e 全片只允许两处（S01、S10 各一次，≤6 帧），本段用在这里。

贴图处理：五块方块与核弹条纹都是真实 16×16 贴图，按亮度映射到品牌色（brand duotone），
避免贴图自带的红/绿条破坏四色约束；状态灯由程序绘制，不放大带彩色噪点的 UI 底板。

运行：
    py -3.12 tools/video/promo/shots/shot_10.py --frames 624 --fps 24 \\
        --out tools/video/promo/out/build/seg_S10.mp4 \\
        --preview tools/video/promo/out/check/shot_10
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import promo_kit as K

from PIL import Image, ImageDraw, ImageOps

BG, STEEL, AMBER, SLATE = K.BG, K.STEEL, K.AMBER, K.SLATE
RED = (179, 38, 30)          # #b3261e —— 唯一允许的语义红（本段仅 6 帧）

SEG = "S10"
DEFAULT_FRAMES = 624
DEFAULT_OUT = "tools/video/promo/out/build/seg_S10.mp4"

TEX = "hbmzz/textures/blocks/"
STRIPE = TEX + "nuke_stripe.png"

# 段落拍点（帧）
B1_END = 168        # 7.0 s
B2_END = 312        # 13.0 s
B3_END = 504        # 21.0 s
COUNT0 = 168        # 倒计时起
RIPPLE0 = 230       # 起爆 → 红石沿出发
RIPPLE_V = 8.5      # px / frame
BUS_Y = 104         # 顶部母线
BLOCK_Y0, BLOCK_Y1 = 132, 252
BLOCK_S = 120

# 五块方块：核弹居中（index 2），红石沿从中间向两侧跑
BLOCKS = (
    dict(tex=TEX + "nuke_controller_front.png", mono="nuke_controller", cn="信号控制器",
         duty=("轮询红石", "上升沿引爆")),
    dict(tex=TEX + "nuke_monitor_front.png", mono="nuke_monitor", cn="核弹监视屏",
         duty=("采样并推送", "不存业务状态")),
    dict(tex=TEX + "nuke_body.png", mono="nuke", cn="核弹",
         duty=("壳体 · 倒计时", "不读红石"), nuke=True),
    dict(tex=TEX + "alert_display_front.png", mono="alert_display", cn="事故显示器",
         duty=("零字段", "纯读客户端缓存")),
    dict(tex=TEX + "code_broadcaster_front.png", mono="code_broadcaster", cn="代码广播器",
         duty=("红石上升沿", "写 FacilityState")),
)
BX = (120, 290, 460, 630, 800)          # 五列中心 x
CENTER_X = BX[2]

# 八级代码（从上到下：DELTA … GREEN；点亮从最下一行往上走）
LEVELS = (
    ("DELTA", "设施毁灭级事故"),
    ("ALPHA", "核弹事件"),
    ("OMEGA", "堆芯爆炸事故"),
    ("MED", "堆芯融毁事故"),
    ("RED", "重大事故"),
    ("ORANGE", "小型暴乱"),
    ("YELLOW", "发生小插曲"),
    ("GREEN", "正常运行"),
)
ROW_Y0, ROW_PITCH, ROW_H = 84, 39, 36
ROW_X0, ROW_X1 = 146, 814
LIT0, LIT_STEP = 306, 20                # GREEN 在 13.0 s 亮，往上每级 20 帧
SHAKE = {372: (-3, 1), 373: (2, -1)}    # RED 亮起：整屏抖 2 帧

FLASH0, FLASH1 = 504, 546               # 21.0 s 起：琥珀全亮 → 回落
RED0 = 516                              # 6 帧警示红 516–521

CUES = (
    (8, 168, "核事故系统，五个方块。"),
    (172, 312, "一个方块起爆，全厂的屏一起变。"),
    (306, 504, "八级代码，从正常运行到设施毁灭。"),
    (508, 640, "RED 及以上，才算警报。"),
)


# ---------------------------------------------------------------- 小工具
def bl(color, alpha):
    """品牌色按 alpha 预混到深底上（不透明色），省掉整幅图层合成。"""
    a = K.clamp(alpha)
    return tuple(int(round(BG[i] + (color[i] - BG[i]) * a)) for i in range(3))


def fill(fr, box, color, alpha=1.0):
    a = int(round(255 * K.clamp(alpha)))
    if a <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    if x1 - x0 < 1 or y1 - y0 < 1:
        return
    layer = Image.new("RGBA", (x1 - x0, y1 - y0), tuple(color[:3]) + (a,))
    fr.img.paste(layer, (x0, y0), layer)


_DUO = {}
_SCALED = {}


def _duo(rel, tint, gamma, auto):
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


def tex_duo(fr, rel, box, tint=STEEL, alpha=1.0, gamma=0.90, auto=False, fit="contain"):
    alpha = K.clamp(alpha)
    if alpha <= 0:
        return
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    bw, bh = x1 - x0, y1 - y0
    if bw <= 0 or bh <= 0:
        return
    key = (rel, tint, gamma, auto, bw, bh, fit)
    img = _SCALED.get(key)
    if img is None:
        src = _duo(rel, tint, gamma, auto)
        iw, ih = src.size
        if fit == "fill":
            nw, nh = bw, bh
        else:
            k = min(bw / float(iw), bh / float(ih))
            nw, nh = max(1, int(round(iw * k))), max(1, int(round(ih * k)))
        img = src.resize((nw, nh), Image.NEAREST)
        _SCALED[key] = img
    if alpha < 0.999:
        img = img.copy()
        img.putalpha(img.getchannel("A").point(lambda v: int(v * alpha)))
    fr.img.paste(img, (x0 + (bw - img.width) // 2, y0 + (bh - img.height) // 2), img)


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
    """等价于 fr.text(..., anchor="la")，但把栅格化结果缓存下来。

    kit 的 text() 每帧要重新 render 字形（约 1 ms/行）；本段一帧二十多行，
    缓存后整段渲染时间从 4 分钟降到 2 分钟以内，赶在进程时限之前。
    """
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


def base_frame(grid=0.05, vign=0.38, scan=0.035):
    """背景层（网格 + 暗角 + 扫描线）是静态的，烘一次，逐帧只做一次拷贝。"""
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


# ---------------------------------------------------------------- 第一拍：五块方块
def block_alpha(f, i):
    # 硬切进来时五块已在暗处，随后 0.5 s 内提亮（不做整帧淡入）
    a_in = 0.35 + 0.65 * K.clamp((f - i * 3) / 12.0)
    a_out = 1.0 - K.clamp((f - 296) / 14.0)
    return a_in * a_out


def lit_frame(i):
    """红石沿抵达该方块的那一帧。"""
    return RIPPLE0 + abs(BX[i] - CENTER_X) / RIPPLE_V


def arm_frame(i):
    """核弹在 7.0 s 倒计时一开始就上电，其余方块等红石沿。"""
    return COUNT0 if BLOCKS[i].get("nuke") else lit_frame(i)


def draw_bus(fr, f, a_bg):
    """顶部母线：先是一条暗槽，起爆后琥珀从中间向两侧跑。"""
    if a_bg <= 0:
        return
    fr.hairline((BX[0], BUS_Y), (BX[-1], BUS_Y), color=bl(SLATE, 0.45 * a_bg), width=2)
    for i, x in enumerate(BX):
        hit = f >= arm_frame(i)
        fr.hairline((x, BUS_Y), (x, BLOCK_Y0), color=AMBER if hit else bl(SLATE, 0.30 * a_bg),
                    width=2 if hit else 1)

    d = max(0.0, (f - RIPPLE0) * RIPPLE_V)
    if d <= 0:
        return
    lim = (BX[-1] - CENTER_X)
    xl = CENTER_X - min(d, lim)
    xr = CENTER_X + min(d, lim)
    fr.hairline((xl, BUS_Y), (xr, BUS_Y), color=AMBER, width=3)
    if d < lim:
        for x in (xl, xr):
            fr.hairline((x, BUS_Y - 9), (x, BUS_Y + 9), color=STEEL, width=2)


def draw_block(fr, f, i):
    a = block_alpha(f, i)
    if a <= 0.004:
        return
    b = BLOCKS[i]
    cx = BX[i]
    x0, x1 = cx - BLOCK_S // 2, cx + BLOCK_S // 2
    y0, y1 = BLOCK_Y0, BLOCK_Y1
    lit = f >= arm_frame(i)
    k = K.clamp((f - arm_frame(i)) / 8.0) if lit else 0.0

    tex_duo(fr, b["tex"], (x0, y0, x1, y1), tint=STEEL, alpha=a, gamma=0.90)

    # 核弹：一条琥珀警示带（真实 nuke_stripe 贴图，按亮度染成琥珀/墨）
    if b.get("nuke"):
        band = (x0 + 4, y0 + 68, x1 - 4, y0 + 90)
        fill(fr, band, BG, 0.96 * a)
        tex_duo(fr, STRIPE, band, tint=AMBER, alpha=a, gamma=0.85, fit="fill")

    # 边框：未通电 SLATE，被红石沿扫过之后转琥珀
    fr.box((x0, y0, x1, y1), color=bl(AMBER, k * 0.9) if lit else bl(SLATE, 0.55 * a),
           width=2, corner=2)

    # 底部状态灯：四格，随红石沿逐格推亮
    cw, gap = 20, 4
    sx = cx - (4 * cw + 3 * gap) // 2
    for c in range(4):
        cxx = sx + c * (cw + gap)
        on = K.clamp((f - arm_frame(i) - c * 2) / 4.0) if lit else 0.0
        if on > 0:
            fill(fr, (cxx, 242, cxx + cw, 247), AMBER, on * a)
        else:
            fr.box((cxx, 242, cxx + cw, 247), color=bl(SLATE, 0.55 * a), width=1, corner=0)

    # 核弹被点亮时的柔光
    if b.get("nuke") and lit:
        beat = 0.70 + 0.30 * abs(((f % 24) / 12.0) - 1.0)
        fr.glow((x0 - 10, y0 - 10, x1 + 10, y1 + 10), color=AMBER,
                radius=10, alpha=0.26 * k * a * beat)

    # 文字
    label(fr, (cx - 80, 262), "%02d" % (i + 1), size=10, color=bl(SLATE, 0.9 * a),
          cn=False, shadow=False)
    label(fr, (cx - 80, 276), b["mono"], size=11, color=bl(AMBER, 0.55 * a), cn=False)
    label(fr, (cx - 80, 294), b["cn"], size=14, color=bl(STEEL, 0.92 * a))
    label(fr, (cx - 80, 316), b["duty"][0], size=13, color=bl(STEEL, 0.62 * a))
    label(fr, (cx - 80, 334), b["duty"][1], size=13, color=bl(STEEL, 0.62 * a))


def countdown(f):
    """T-05.0 → 0.0，每秒掉 1。"""
    t = 5.0 - K.clamp((f - COUNT0) / 12.0)      # 12 帧 / 单位
    return max(0.0, t)


# ---------------------------------------------------------------- 第三 / 四拍：八级代码
def draw_levels(fr, f):
    a = K.clamp((f - 300) / 10.0)
    if a <= 0.004:
        return
    full = K.clamp((f - FLASH0) / 16.0)          # 21 s 后整屏琥珀全亮

    label(fr, (ROW_X0, 50), "FACILITY STATE // CODE", size=11, color=bl(SLATE, 0.95 * a),
          cn=False, shadow=False)
    fr.text((ROW_X1, 50), "08 LEVELS", size=11, color=bl(SLATE, 0.95 * a), cn=False,
            anchor="ra", shadow=False)
    fr.hairline((ROW_X0, 70), (ROW_X1, 70), color=bl(SLATE, 0.30 * a), width=1)

    for j, (code, cn) in enumerate(LEVELS):
        y = ROW_Y0 + j * ROW_PITCH
        lit_at = LIT0 + (7 - j) * LIT_STEP
        lit = f >= lit_at
        k = K.clamp((f - lit_at) / 6.0) if lit else 0.0
        aa = a

        # 槽
        fill(fr, (ROW_X0, y, ROW_X1, y + ROW_H), BG, 0.62 * aa)
        fr.box((ROW_X0, y, ROW_X1, y + ROW_H),
               color=bl(AMBER, k * (0.62 + 0.30 * full)) if lit else bl(SLATE, 0.28 * aa),
               width=1, corner=2)
        if lit and k > 0:
            fill(fr, (ROW_X0, y, ROW_X1, y + ROW_H), AMBER, (0.075 + 0.055 * full) * k * aa)
            fill(fr, (ROW_X0, y + 1, ROW_X0 + 4, y + ROW_H - 1), AMBER, 0.9 * k * aa)

        label(fr, (ROW_X0 + 18, y + 9), code, size=17,
              color=bl(AMBER, 0.30 + 0.70 * k) if lit else bl(SLATE, 0.80 * aa),
              cn=False)
        label(fr, (ROW_X0 + 184, y + 8), cn, size=18,
              color=bl(STEEL, 0.55 + 0.45 * k) if lit else bl(SLATE, 0.75 * aa))

        # 级数条：越往上越长
        sev_x0, sev_x1 = 594, 700
        frac = (8 - j) / 8.0
        fr.hairline((sev_x0, y + 17), (sev_x1, y + 17), color=bl(SLATE, 0.35 * aa), width=4)
        if lit and k > 0:
            fr.hairline((sev_x0, y + 17), (sev_x0 + (sev_x1 - sev_x0) * frac, y + 17),
                        color=bl(AMBER, 0.85 * k * aa), width=4)

        # RED 及以上才算警报
        if j <= 4 and k > 0:
            fr.box((722, y + 7, 790, y + 29), color=bl(AMBER, 0.55 * k * aa), width=1, corner=1)
            fr.text((756, y + 9), "警报", size=12, color=bl(AMBER, k * aa), anchor="ma")

    # 当前状态读数：随八级逐格提升，第四拍交给 DELTA 定格
    if f >= 312:
        sa = 1.0 - K.clamp((f - 498) / 8.0)
        lvl = None
        for j in range(8):
            if f >= LIT0 + (7 - j) * LIT_STEP:
                lvl = j
                break
        if sa > 0.004 and lvl is not None:
            code, cn = LEVELS[lvl]
            label(fr, (ROW_X0, 412), "CURRENT STATE", size=10, color=bl(SLATE, 0.95 * sa),
                  cn=False, shadow=False)
            label(fr, (ROW_X0 + 150, 402), code, size=22, color=bl(AMBER, sa), cn=False)
            label(fr, (ROW_X0 + 300, 410), cn, size=15, color=bl(STEEL, 0.95 * sa))

    # 第四拍：DELTA 定格
    if f >= FLASH0:
        kb = K.clamp((f - FLASH0) / 12.0)
        fr.hairline((ROW_X0, 442), (ROW_X0 + 668 * K.ease_out_cubic(kb), 442),
                    color=bl(AMBER, 0.9), width=2)
        label(fr, (ROW_X0, 390), "DELTA", size=38, color=bl(AMBER, kb), cn=False)
        label(fr, (ROW_X0 + 196, 404), "设施毁灭级事故 · 最高一级", size=17,
              color=bl(STEEL, 0.95 * kb))


# ---------------------------------------------------------------- 主流程
def draw_frame(f, n):
    fr = base_frame()

    a_top = 1.0 - K.clamp((f - 296) / 14.0)
    if a_top > 0.004:
        label(fr, (40, 30), "NUCLEAR ACCIDENT SYSTEM", size=12,
              color=bl(SLATE, 0.95 * a_top), cn=False, shadow=False)
        fr.text((920, 30), "BLOCKS 05", size=12, color=bl(SLATE, 0.95 * a_top),
                cn=False, anchor="ra", shadow=False)
        fr.hairline((40, 50), (920, 50), color=bl(SLATE, 0.30 * a_top), width=1)

    # 倒计时（顶栏右侧，与方块区互不干扰）
    if COUNT0 <= f < 300:
        ca = K.clamp((f - COUNT0) / 8.0) * (1.0 - K.clamp((f - 280) / 12.0))
        if ca > 0.004:
            t = countdown(f)
            col = AMBER if t > 0 else STEEL
            fr.text((920, 62), "T-%04.1f" % t, size=15, color=bl(col, ca), cn=False,
                    anchor="ra")

    # 第一 / 二拍：五块方块
    a_bg = (0.55 + 0.45 * K.clamp(f / 10.0)) * (1.0 - K.clamp((f - 296) / 14.0))
    if a_bg > 0.004:
        draw_bus(fr, f, a_bg)
        for i in range(5):
            draw_block(fr, f, i)
        fr.hairline((40, 352), (920, 352), color=bl(SLATE, 0.20 * a_bg), width=1)

    # 第三 / 四拍：八级代码
    draw_levels(fr, f)

    # 整屏琥珀全亮 → 6 帧警示红 → 回落
    if f >= FLASH0:
        if f < RED0:
            ka = K.clamp((f - FLASH0) / 12.0)
            fr.flash(AMBER, 0.13 * ka)
        elif f < RED0 + 6:
            k = (RED0 + 5 - f) / 5.0
            fr.flash(RED, 0.07 + 0.23 * k)          # 6 帧警示红 #b3261e
        else:
            ks = K.clamp((f - (RED0 + 6)) / 22.0)
            fr.flash(AMBER, 0.06 * (1.0 - ks) + 0.02)

    for start, end, text in CUES:
        a = cap_alpha(f, start, end)
        if a > 0:
            fr.caption(text, alpha=a, size=30, color=STEEL)

    # RED 亮起时整屏抖 2 帧
    sh = SHAKE.get(f)
    if sh:
        dx, dy = sh
        fr.img = fr.img.transform((K.W, K.H), Image.AFFINE, (1, 0, -dx, 0, 1, -dy),
                                  resample=Image.NEAREST, fillcolor=BG)
    return fr


def build(n, fps):
    for f in range(n):
        yield draw_frame(f, n).img


def parse_args():
    ap = argparse.ArgumentParser(description="S10 nuclear accident renderer (26.0 s)")
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
                save_png(fr.img, os.path.join(args.preview, "shot_10_f%03d.png" % f))
            yield fr.img

    written = K.write_video(frames(), args.out, fps=fps, crf=16, progress=96, label=SEG)
    if written != n:
        raise SystemExit("error: wrote %d frames, expected %d" % (written, n))
    print("%s: %d frames -> %s" % (SEG, written, args.out), flush=True)
    if args.preview:
        print("%s: %d preview frames -> %s" % (SEG, len(idxs), args.preview), flush=True)


if __name__ == "__main__":
    main()
