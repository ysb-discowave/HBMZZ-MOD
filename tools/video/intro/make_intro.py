#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HBMZZ 8 秒片头生成器（纯本地 / 离线 / 可重复运行）。

几何与颜色全部派生自 docs/assets/brand/logo-mark.svg（64 网格）与
logo-lockup.svg（横排版）；不联网、不依赖系统字体安装（字体随脚本附带）。

用法：
    py -3.12 tools/video/intro/make_intro.py
    py -3.12 tools/video/intro/make_intro.py --out /path/to/x.mp4 --fps 24 --frames 192

设计详见同目录 SPEC.md；导出参数与重生成说明见同目录 README.md。
"""

import argparse
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------------
# 运行环境引导：优先用当前解释器；若无 Pillow，尝试用 py -3.12 重启一次。
# ----------------------------------------------------------------------------
_REEXEC_FLAG = "HBMZZ_INTRO_REEXEC"


def _bootstrap_pillow():
    try:
        from PIL import Image, ImageDraw, ImageFilter, ImageFont  # noqa: F401

        return Image, ImageDraw, ImageFilter, ImageFont
    except ImportError:
        pass

    if os.environ.get(_REEXEC_FLAG) == "1":
        raise SystemExit(
            "错误：当前 Python 缺少 Pillow，且自动切换的解释器同样不可用。\n"
            "请用一个已安装 Pillow 的 Python 运行本脚本，例如：\n"
            "    py -3.12 tools/video/intro/make_intro.py"
        )

    candidates = []
    if shutil.which("py"):
        candidates.append(["py", "-3.12"])
        candidates.append(["py", "-3"])
    if shutil.which("python3.12"):
        candidates.append(["python3.12"])

    for cmd in candidates:
        try:
            code = subprocess.run(
                cmd + ["-c", "import PIL"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            ).returncode
        except OSError:
            continue
        if code == 0:
            env = dict(os.environ)
            env[_REEXEC_FLAG] = "1"
            raise SystemExit(
                subprocess.run(cmd + [os.path.abspath(__file__)] + sys.argv[1:], env=env).returncode
            )

    raise SystemExit(
        "错误：找不到带 Pillow 的 Python。请安装 Pillow 或改用：\n"
        "    py -3.12 tools/video/intro/make_intro.py"
    )


Image, ImageDraw, ImageFilter, ImageFont = _bootstrap_pillow()

# ----------------------------------------------------------------------------
# 常量：颜色严格只用四种品牌色；背景恒定深底。
# ----------------------------------------------------------------------------
BG = (10, 12, 15)       # #0a0c0f 墨（底）
STEEL = (230, 236, 243)  # #e6ecf3 钢板
AMBER = (245, 165, 36)   # #f5a524 琥珀
SCREW = (72, 82, 95)     # #48525f 螺钉

W, H = 960, 540
SS = 2                   # 超采样倍率（渲染于 2× 分辨率再 LANCZOS 缩回）
BASE = 150.0             # 64 网格主标在 scale=1.0 时的边长（px）
SCALE_END = 1.18         # 缩放推进终点

TXT = "HBMZZ"
TXT_SIZE = 84            # 字号（等宽粗体，实测 cap height ≈ 61px ≥ 54px）
TXT_TRACK = 8            # letter-spacing，沿用 logo-lockup.svg
GAP = 44                 # 主标与文字间距
TXT_BASELINE = 300       # 文字基线（final px）
UNDERLINE_Y = 318
UNDERLINE_H = 4

# 阶段二子阶段在 24fps 下的秒数基准（来自 SPEC.md 第 4 节）
T_A0, T_A1 = 2.208, 3.375   # 2a 拆解
T_B0, T_B1 = 3.417, 4.792   # 2b 重组
T_C0, T_C1 = 4.833, 6.000   # 2c 缩放
T_D0, T_D1 = 6.042, 6.792   # 2d 发光

FONT_CANDIDATES = [
    Path(__file__).resolve().parent / "fonts" / "DejaVuSansMono-Bold.ttf",
    Path(__file__).resolve().parent / "fonts" / "DejaVuSansMono.ttf",
]


# ----------------------------------------------------------------------------
# 缓动与工具
# ----------------------------------------------------------------------------
def clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def ease_in_out_cubic(x):
    x = clamp01(x)
    return 4 * x * x * x if x < 0.5 else 1 - pow(-2 * x + 2, 3) / 2


def ease_out_cubic(x):
    x = clamp01(x)
    return 1 - pow(1 - x, 3)


def lerp(a, b, t):
    return a + (b - a) * t


def load_font(size_px, bold_preferred=True):
    """加载等宽字体；优先随脚本附带的粗体，找不到才退到 PIL 自带字体。

    任何情况下都不使用系统字体安装；若连 PIL 自带字体都不可用则明确报错，
    不做静默的字体替换/变形。
    """
    for p in FONT_CANDIDATES:
        if p.is_file():
            try:
                return ImageFont.truetype(str(p), size_px)
            except OSError as exc:
                raise SystemExit("错误：随附等宽字体无法加载 %s：%s" % (p, exc))
    try:
        return ImageFont.load_default(size=size_px)
    except TypeError:
        try:
            return ImageFont.load_default()
        except Exception as exc:
            raise SystemExit("错误：无法获得可用字体（%s）。请确认 fonts/ 下的等宽字体存在。" % exc)
    except Exception as exc:
        raise SystemExit("错误：无法加载字体（%s）。请确认 fonts/ 下的等宽字体存在。" % exc)


# ----------------------------------------------------------------------------
# 11 个原子部件（坐标来自 logo-mark.svg 的 64 网格）
# ----------------------------------------------------------------------------
PARTS = [
    # 屏框四边（stroke-width 3.5，先用直线段近似，round cap 由绘制端补）
    dict(name="frame_top", kind="line", p0=(3.5, 3.5), p1=(60.5, 3.5),
         color=STEEL, dir=(0.0, -1.0), dist=14.0, rot=-6.0, frame=True,
         reveal=(0.00, 0.30)),
    dict(name="frame_right", kind="line", p0=(60.5, 3.5), p1=(60.5, 60.5),
         color=STEEL, dir=(1.0, 0.0), dist=14.0, rot=-6.0, frame=True,
         reveal=(0.30, 0.50)),
    dict(name="frame_bottom", kind="line", p0=(60.5, 60.5), p1=(3.5, 60.5),
         color=STEEL, dir=(0.0, 1.0), dist=14.0, rot=6.0, frame=True,
         reveal=(0.50, 0.80)),
    dict(name="frame_left", kind="line", p0=(3.5, 60.5), p1=(3.5, 3.5),
         color=STEEL, dir=(-1.0, 0.0), dist=14.0, rot=6.0, frame=True,
         reveal=(0.80, 1.00)),
    # 四角螺钉 3×3
    dict(name="screw_tl", kind="rect", p=(9.5, 9.5), sz=(3.0, 3.0),
         color=SCREW, dir=(-1.0, -1.0), dist=18.0, rot=180.0),
    dict(name="screw_tr", kind="rect", p=(51.5, 9.5), sz=(3.0, 3.0),
         color=SCREW, dir=(1.0, -1.0), dist=18.0, rot=180.0),
    dict(name="screw_bl", kind="rect", p=(9.5, 51.5), sz=(3.0, 3.0),
         color=SCREW, dir=(-1.0, 1.0), dist=18.0, rot=180.0),
    dict(name="screw_br", kind="rect", p=(51.5, 51.5), sz=(3.0, 3.0),
         color=SCREW, dir=(1.0, 1.0), dist=18.0, rot=180.0),
    # H 两根立柱
    dict(name="h_left", kind="rect", p=(19.0, 17.0), sz=(7.0, 30.0),
         color=STEEL, dir=(-1.0, 0.0), dist=10.0, rot=-12.0),
    dict(name="h_right", kind="rect", p=(38.0, 17.0), sz=(7.0, 30.0),
         color=STEEL, dir=(1.0, 0.0), dist=10.0, rot=12.0),
    # 琥珀横杠（x=19..49，右端探出右立柱 4 单位）
    dict(name="amber_bar", kind="rect", p=(19.0, 28.5), sz=(30.0, 7.0),
         color=AMBER, dir=(1.0, -1.0), dist=16.0, rot=-4.0, amber=True),
]


def _unit(v):
    n = math.hypot(v[0], v[1])
    return (v[0] / n, v[1] / n) if n else (0.0, 0.0)


for _p in PARTS:
    _ux, _uy = _unit(_p["dir"])
    _p["dir_u"] = (_ux, _uy)
    if _p["kind"] == "line":
        _p["center"] = ((_p["p0"][0] + _p["p1"][0]) / 2.0,
                        (_p["p0"][1] + _p["p1"][1]) / 2.0)
    else:
        _p["center"] = (_p["p"][0] + _p["sz"][0] / 2.0,
                        _p["p"][1] + _p["sz"][1] / 2.0)


def part_points(part):
    """返回部件在 64 网格中的几何点：矩形给 4 角，线段给两端。"""
    if part["kind"] == "line":
        return [part["p0"], part["p1"]]
    x, y = part["p"]
    w, h = part["sz"]
    return [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]


# ----------------------------------------------------------------------------
# 帧状态：把帧号映射为一组渲染参数（全部连续，无跳变）
# ----------------------------------------------------------------------------
def compute_boundaries(fps):
    """根据 fps 换算阶段边界帧（1-based）；默认 24fps 时与 SPEC.md 完全一致。"""
    b = {}
    b["p1_end"] = max(1, round(2.2 * fps))              # 53
    b["p2_end"] = max(b["p1_end"] + 1, math.ceil(6.8 * fps))  # 164
    b["p3_start"] = b["p2_end"] + 1                      # 165

    def fb(t, lo, hi):
        return min(hi, max(lo, round(t * fps) + 1))

    b["a_start"] = fb(T_A0, b["p1_end"] + 1, b["p2_end"])
    b["a_end"] = fb(T_A1, b["a_start"], b["p2_end"])
    b["b_start"] = fb(T_B0, b["a_end"], b["p2_end"])
    b["b_end"] = fb(T_B1, b["b_start"], b["p2_end"])
    b["c_start"] = fb(T_C0, b["b_end"], b["p2_end"])
    b["c_end"] = fb(T_C1, b["c_start"], b["p2_end"])
    b["d_start"] = fb(T_D0, b["c_end"], b["p2_end"])
    b["d_end"] = b["p2_end"]
    return b


def compute_layout():
    font_final = load_font(TXT_SIZE)
    txt_w = sum(font_final.getlength(c) for c in TXT) + TXT_TRACK * (len(TXT) - 1)
    mark_px = BASE * SCALE_END
    total = mark_px + GAP + txt_w
    x0 = (W - total) / 2.0
    return {
        "txt_w": txt_w,
        "mark_cx": x0 + mark_px / 2.0,
        "txt_x": x0 + mark_px + GAP,
        "underline_w": txt_w,
        "left": x0,
        "right": x0 + total,
    }


def render_state(f, fps, b, layout):
    """返回本帧全部动画参数。"""
    st = dict(
        center=(W / 2.0, H / 2.0), scale=1.0,
        scatter=0.0, rot=0.0, reveal=1.0, content_op=1.0,
        glow_a=0.0, glow_s=0.0, text_op=0.0,
        text_x=layout["txt_x"],
    )

    if f <= b["p1_end"]:
        # 阶段一：0.0–2.2s 开场引入
        denom = max(1, min(48, b["p1_end"]) - 1)
        st["reveal"] = 0.04 + 0.96 * ease_in_out_cubic((f - 1) / denom)
        st["content_op"] = ease_out_cubic((f - 30) / 22.0) if f >= 30 else 0.0
        st["center"] = (W / 2.0, H / 2.0)
        st["scale"] = 1.0
        return st

    if f <= b["p2_end"]:
        # 阶段二：2.2–6.8s 动态变形
        st["reveal"] = 1.0
        st["content_op"] = 1.0
        if f <= b["a_end"]:
            p = ease_out_cubic((f - b["a_start"]) / max(1, b["a_end"] - b["a_start"]))
            st["scatter"] = p
            st["rot"] = p
        elif f <= b["b_end"]:
            p = ease_in_out_cubic((f - b["b_start"]) / max(1, b["b_end"] - b["b_start"]))
            st["scatter"] = 1.0 - p
            st["rot"] = 1.0 - p
        elif f <= b["c_end"]:
            p = ease_in_out_cubic((f - b["c_start"]) / max(1, b["c_end"] - b["c_start"]))
            st["scale"] = 1.0 + (SCALE_END - 1.0) * p
        else:
            # 2d：琥珀发光渐入 + 主标平移到横排位 + HBMZZ 文字淡入
            t = clamp01((f - b["d_start"]) / max(1, b["d_end"] - b["d_start"]))
            st["scale"] = SCALE_END
            st["center"] = (lerp(W / 2.0, layout["mark_cx"], ease_in_out_cubic(t)), H / 2.0)
            # 文字与主标同步平移，始终保持 GAP 间距，避免过渡中重叠。
            st["text_x"] = st["center"][0] + (BASE * SCALE_END) / 2.0 + GAP
            gp = ease_out_cubic(t)
            st["glow_a"] = 0.28 * gp
            st["glow_s"] = 0.10 * gp
            st["text_op"] = gp
        return st

    # 阶段三：6.8–8.0s 最终定格（完全静止）
    st.update(
        center=(layout["mark_cx"], H / 2.0), scale=SCALE_END,
        glow_a=0.28, glow_s=0.10, text_op=1.0,
    )
    return st


# ----------------------------------------------------------------------------
# 绘制
# ----------------------------------------------------------------------------
def xform_point(gp, part, scatter, rot_factor):
    """把网格点按部件的位移+旋转（绕自身中心）变换，仍在 64 网格坐标系。"""
    cx, cy = part["center"]
    ang = math.radians(part["rot"] * rot_factor)
    dx = part["dir_u"][0] * part["dist"] * scatter
    dy = part["dir_u"][1] * part["dist"] * scatter
    rx, ry = gp[0] - cx, gp[1] - cy
    ca, sa = math.cos(ang), math.sin(ang)
    return (cx + dx + rx * ca - ry * sa, cy + dy + rx * sa + ry * ca)


def grid_to_px(gp, center, scale):
    k = BASE * scale / 64.0
    return (center[0] + (gp[0] - 32.0) * k, center[1] + (gp[1] - 32.0) * k)


def to_ss(pt):
    return (int(round(pt[0] * SS)), int(round(pt[1] * SS)))


def draw_part(d, part, st):
    """把一个部件画到 SS 分辨率的 RGBA ImageDraw 上。"""
    op = st["content_op"]
    if part.get("frame"):
        a, bnd = part["reveal"]
        local = clamp01((st["reveal"] - a) / max(1e-6, bnd - a))
        if local <= 0:
            return
    elif op <= 0:
        return

    # 屏框靠 reveal 显现；螺钉/H/横杠仅在阶段一淡入用 content_op，其余阶段恒不透明。
    alpha = int(round(255 * st["content_op"])) if not part.get("frame") else 255
    fill = part["color"] + (alpha,)

    pts_grid = part_points(part)
    if part.get("frame") and local < 1:
        p0, p1 = pts_grid
        pts_grid = [p0, (lerp(p0[0], p1[0], local), lerp(p0[1], p1[1], local))]

    pts = [to_ss(grid_to_px(xform_point(g, part, st["scatter"], st["rot"]),
                            st["center"], st["scale"])) for g in pts_grid]

    if part["kind"] == "line":
        width = max(1, int(round(3.5 * (BASE * st["scale"] / 64.0) * SS)))
        d.line(pts, fill=fill, width=width, joint="curve")
        # 圆头端点（让四边看起来是同一圆角矩形的四段）
        r = width / 2.0
        for (px, py) in pts:
            d.ellipse([px - r, py - r, px + r, py + r], fill=fill)
    else:
        d.polygon(pts, fill=fill)


def draw_glow(base_rgba, part, st, kind):
    """在清晰图层之下画柔光（只作用于琥珀 / 极弱钢色）。"""
    pts_grid = part_points(part)
    pts = [to_ss(grid_to_px(xform_point(g, part, st["scatter"], st["rot"]),
                            st["center"], st["scale"])) for g in pts_grid]
    layer = Image.new("RGBA", base_rgba.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if kind == "amber":
        alpha = int(round(255 * st["glow_a"]))
        d.polygon(pts, fill=AMBER + (alpha,))
        layer = layer.filter(ImageFilter.GaussianBlur(radius=5.5 * SS))
    else:
        alpha = int(round(255 * st["glow_s"]))
        width = max(1, int(round(3.5 * (BASE * st["scale"] / 64.0) * SS)))
        d.line(pts, fill=STEEL + (alpha,), width=width, joint="curve")
        r = width / 2.0
        for (px, py) in pts:
            d.ellipse([px - r, py - r, px + r, py + r], fill=STEEL + (alpha,))
        layer = layer.filter(ImageFilter.GaussianBlur(radius=3.0 * SS))
    return Image.alpha_composite(base_rgba, layer)


def draw_tracked(d, x_left, baseline, text, font, fill, tracking_px):
    """逐字绘制带 letter-spacing 的等宽文字，anchor 为左基线。"""
    x = x_left
    for ch in text:
        d.text((x, baseline), ch, font=font, fill=fill, anchor="ls")
        x += font.getlength(ch) + tracking_px * SS


def render_frame(f, fps, b, layout, font_ss):
    st = render_state(f, fps, b, layout)

    base = Image.new("RGBA", (W * SS, H * SS), BG + (255,))
    # 柔光层在清晰原图之下（8.5 防糊关键）
    if st["glow_a"] > 0:
        for part in PARTS:
            if part.get("amber"):
                base = draw_glow(base, part, st, "amber")
                break
    if st["glow_s"] > 0:
        for part in PARTS:
            if part.get("frame"):
                base = draw_glow(base, part, st, "steel")

    sharp = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sharp)
    for part in PARTS:
        draw_part(sd, part, st)

    # 阶段 2d / 3：HBMZZ 字样 + 琥珀下划线（构图沿用 logo-lockup.svg）
    if st["text_op"] > 0:
        a = int(round(255 * st["text_op"]))
        draw_tracked(sd, int(round(st["text_x"] * SS)), int(round(TXT_BASELINE * SS)),
                     TXT, font_ss, STEEL + (a,), TXT_TRACK)
        sd.rectangle([int(round(st["text_x"] * SS)), int(round(UNDERLINE_Y * SS)),
                      int(round((st["text_x"] + layout["underline_w"]) * SS)),
                      int(round((UNDERLINE_Y + UNDERLINE_H) * SS))],
                     fill=AMBER + (a,))

    base = Image.alpha_composite(base, sharp)
    return base.convert("RGB").resize((W, H), Image.LANCZOS).tobytes()


# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="生成 HBMZZ 8 秒片头 mp4（离线、可重复）")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "out" / "hbmzz-intro.mp4"),
                    help="输出 mp4 路径（默认 tools/video/intro/out/hbmzz-intro.mp4）")
    ap.add_argument("--fps", type=int, default=24, help="帧率（默认 24）")
    ap.add_argument("--frames", type=int, default=192, help="总帧数（默认 192）")
    args = ap.parse_args()

    if args.fps <= 0 or args.frames <= 0:
        raise SystemExit("错误：--fps 与 --frames 必须为正整数。")
    if shutil.which("ffmpeg") is None:
        raise SystemExit("错误：PATH 中找不到 ffmpeg，请先安装并加入 PATH。")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    b = compute_boundaries(args.fps)
    layout = compute_layout()
    font_ss = load_font(TXT_SIZE * SS)

    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s:v", "%dx%d" % (W, H), "-r", str(args.fps), "-i", "pipe:0",
        "-an",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18",
        "-pix_fmt", "yuv420p",
        "-frames:v", str(args.frames),
        "-movflags", "+faststart",
        str(out),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    static_bytes = None
    try:
        for f in range(1, args.frames + 1):
            if f >= b["p3_start"] and static_bytes is not None:
                data = static_bytes
            else:
                data = render_frame(f, args.fps, b, layout, font_ss)
                if f == b["p3_start"]:
                    static_bytes = data
            proc.stdin.write(data)
            if f % 24 == 0 or f == 1 or f == args.frames:
                print("frame %d/%d" % (f, args.frames), flush=True)
    finally:
        if proc.stdin:
            proc.stdin.close()
    rc = proc.wait()
    if rc != 0:
        raise SystemExit("错误：ffmpeg 退出码 %d" % rc)

    print("完成：%s (%d bytes, %d 帧 @ %dfps)" % (out, out.stat().st_size, args.frames, args.fps))


if __name__ == "__main__":
    main()
