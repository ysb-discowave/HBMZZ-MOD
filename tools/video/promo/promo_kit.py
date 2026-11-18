#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HBMZZ 宣传片渲染库（段渲染器唯一允许使用的绘图 / 编码层）。

用法（段渲染器）::

    import sys, pathlib
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
    import promo_kit as K

    def frames(n, fps):
        for f in range(n):
            fr = K.Frame()
            fr.clear()
            fr.hud_grid()
            fr.caption("一个核电站，六块屏。", alpha=K.fade_alpha(f, n))
            yield fr.img

    K.write_video(frames(144, 24), "out/build/seg_S01.mp4")

契约：CONTRACT.md §7.3 列出的常量与函数是全部公开能力。本库**只**服务于这支宣传片：
画面固定 960x540 / 24fps / 品牌四色。需要新能力就改这里，不要在段渲染器里另写一套。

几条硬约定（段渲染器必须遵守，否则合成会出问题）：

1. ``write_video`` 逐帧 pipe 给 ffmpeg，输出 h264 / yuv420p / 无音轨。返回实际帧数，
   与输入帧数不符时抛异常。
2. 段内首尾淡变用 ``fade_alpha(local_frame, frames, head, tail)`` 算 alpha，自己乘到
   所有绘制上；端点精确（第 0 帧 = 0，最后 1 帧 = 0），接缝处不会留灰底。
3. ``Frame.text/caption/tex/poster`` 的透明度都是「整层 alpha 合成」，不是调色。

依赖 Pillow。当前解释器没有 Pillow 时，会自动用 ``py -3.12`` 重启一次自己
（与 tools/video/intro/make_intro.py 的做法一致）。
"""

from __future__ import annotations

import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------------
# 运行环境引导：优先当前解释器；无 Pillow 时用 py -3.12 重启一次。
# ----------------------------------------------------------------------------
_REEXEC_FLAG = "HBMZZ_PROMO_KIT_REEXEC"


def _bootstrap_pillow():
    try:
        from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont  # noqa: F401

        return Image, ImageChops, ImageDraw, ImageFilter, ImageFont
    except ImportError:
        pass

    if os.environ.get(_REEXEC_FLAG) == "1":
        raise SystemExit(
            "error: current Python has no Pillow and the fallback interpreter failed too.\n"
            "Run with a Python that has Pillow, e.g.:\n"
            "    py -3.12 tools/video/promo/<script>.py"
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
                subprocess.run(cmd + [os.path.abspath(sys.argv[0])] + sys.argv[1:], env=env).returncode
            )

    raise SystemExit(
        "error: no Python with Pillow found. Install Pillow or run:\n"
        "    py -3.12 tools/video/promo/<script>.py"
    )


Image, ImageChops, ImageDraw, ImageFilter, ImageFont = _bootstrap_pillow()

# ----------------------------------------------------------------------------
# 画布与品牌色（全片只用这四个颜色，不许引入第五种）
# ----------------------------------------------------------------------------
W, H, FPS = 960, 540, 24

BG = (10, 12, 15)        # #0a0c0f 墨（底）
STEEL = (230, 236, 243)  # #e6ecf3 钢板
AMBER = (245, 165, 36)   # #f5a524 琥珀
SLATE = (72, 82, 95)     # #48525f 石板（结构线 / 螺钉）

SCREW = SLATE            # 别名：片头脚本里叫 SCREW，同一个色
SHADOW = (0, 0, 0)       # 文字投影：比 BG 更黑一档，压在任何画面上都读得出来

# 字幕排版（CONTRACT §5）
CAPTION_BASELINE = 486
CAPTION_MARGIN = 40          # 左右安全边距各 40px
CAPTION_MAX_W = W - 2 * CAPTION_MARGIN      # 880
CAPTION_LINE_GAP = 1.28       # 行距 = size * 该系数

_ROOT = Path(__file__).resolve().parents[3]          # 仓库根
_ASSETS = _ROOT / "src" / "main" / "resources" / "assets"
_POSTER = _ROOT / "docs" / "assets" / "promo" / "poster.jpg"
_FONT_CN_PATH = Path("C:/Windows/Fonts/msyhbd.ttc")  # 微软雅黑 Bold
_FONT_CN_FALLBACKS = [
    Path("C:/Windows/Fonts/msyh.ttc"),
    Path("C:/Windows/Fonts/simhei.ttf"),
]
_FONT_MONO_PATH = _ROOT / "tools" / "video" / "intro" / "fonts" / "DejaVuSansMono-Bold.ttf"


# ----------------------------------------------------------------------------
# 缓动
# ----------------------------------------------------------------------------
def clamp(x, lo=0.0, hi=1.0):
    return lo if x < lo else (hi if x > hi else x)


def lerp(a, b, t):
    return a + (b - a) * t


def smoothstep(t):
    t = clamp(t)
    return t * t * (3.0 - 2.0 * t)


def ease_in_out_cubic(t):
    t = clamp(t)
    return 4.0 * t * t * t if t < 0.5 else 1.0 - pow(-2.0 * t + 2.0, 3) / 2.0


def ease_out_cubic(t):
    t = clamp(t)
    return 1.0 - pow(1.0 - t, 3)


def ease_in_cubic(t):
    t = clamp(t)
    return t * t * t


def fade_alpha(local_frame, frames, head=12, tail=12):
    """段内首尾淡变因子，0.0–1.0。

    head=12 → 第 0..11 帧从 0 升到 1（smoothstep）；tail=12 → 最后 12 帧从 1 降到 0。
    端点精确：``fade_alpha(0, n) == 0.0``，``fade_alpha(n-1, n) == 0.0``。
    head/tail 为 0 时不淡。
    """
    a = 1.0
    if head and head > 0:
        if local_frame < head:
            denom = max(1, head - 1)
            a = min(a, smoothstep(local_frame / float(denom)))
    if tail and tail > 0 and frames > 0:
        from_end = frames - 1 - local_frame
        if from_end < tail:
            denom = max(1, tail - 1)
            a = min(a, smoothstep(from_end / float(denom)))
    return clamp(a)


# ----------------------------------------------------------------------------
# 字体（缓存：同一字号只 truetype 一次）
# ----------------------------------------------------------------------------
_font_cache = {}


def font_cn(size):
    """中文字体（微软雅黑 Bold）。缺失时按 msyh → simhei 退让并告警，不静默换形。"""
    key = ("cn", int(size))
    f = _font_cache.get(key)
    if f is not None:
        return f
    for path in [_FONT_CN_PATH] + _FONT_CN_FALLBACKS:
        if not path.is_file():
            continue
        try:
            f = ImageFont.truetype(str(path), int(size), index=0)
        except OSError:
            continue
        if path != _FONT_CN_PATH:
            print("warning: font %s missing, using %s" % (_FONT_CN_PATH, path), file=sys.stderr)
        _font_cache[key] = f
        return f
    raise SystemExit("error: no Chinese font found (expected %s)" % _FONT_CN_PATH)


def font_mono(size):
    """等宽字体（数字 / 英文），无中文字形。"""
    key = ("mono", int(size))
    f = _font_cache.get(key)
    if f is not None:
        return f
    if not _FONT_MONO_PATH.is_file():
        raise SystemExit("error: mono font missing: %s" % _FONT_MONO_PATH)
    f = ImageFont.truetype(str(_FONT_MONO_PATH), int(size))
    _font_cache[key] = f
    return f


def assets_root():
    """src/main/resources/assets 的绝对路径；贴图 relpath 相对于它。"""
    return str(_ASSETS)


# ----------------------------------------------------------------------------
# 内部工具
# ----------------------------------------------------------------------------
def _rgba(color, alpha=1.0):
    """(r,g,b) 或 (r,g,b,a) → 带 0–255 alpha 的四元组。"""
    if len(color) == 4:
        a = color[3]
    else:
        a = int(round(255 * clamp(alpha)))
    return (color[0], color[1], color[2], a)


def _norm_box(box):
    """box → (x0, y0, x1, y1)。

    约定 box=(x0, y0, x1, y1)（左上角 + 右下角，与 PIL 一致）。为减少误用，
    若 x1 < x0 或 y1 < y0，则按 (x, y, w, h) 解释。
    """
    if box is None:
        return (0, 0, W, H)
    if len(box) != 4:
        raise ValueError("box must be a 4-tuple, got %r" % (box,))
    x0, y0, x1, y1 = (float(v) for v in box)
    if x1 < x0 or y1 < y0:
        x1, y1 = x0 + x1, y0 + y1
    return (int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1)))


_tex_cache = {}
_poster_cache = {}


def _load_rgba(path, cache):
    key = str(path)
    img = cache.get(key)
    if img is None:
        if not Path(path).is_file():
            raise FileNotFoundError("asset not found: %s" % path)
        img = Image.open(path)
        img.load()
        img = img.convert("RGBA")
        cache[key] = img
    return img


def _scale_alpha(img, alpha):
    if alpha >= 1.0:
        return img
    if alpha <= 0.0:
        return None
    out = img.copy()
    a = out.getchannel("A").point(lambda v: int(v * alpha))
    out.putalpha(a)
    return out


# ----------------------------------------------------------------------------
# 画布
# ----------------------------------------------------------------------------
class Frame:
    """一帧 960x540 RGB 画布。所有 draw 都是原地修改，``img`` 与 ``draw`` 始终有效。"""

    def __init__(self, color=BG):
        self.img = Image.new("RGB", (W, H), color)
        self.draw = ImageDraw.Draw(self.img)

    # -- 基础 -----------------------------------------------------------
    def clear(self):
        self.img.paste(BG, (0, 0, W, H))
        self.draw = ImageDraw.Draw(self.img)

    def _replace(self, img):
        self.img = img
        self.draw = ImageDraw.Draw(self.img)

    def _composite(self, layer):
        """RGBA 层按 alpha 合成到当前画面（原地，保持 draw 绑定）。"""
        if layer is None:
            return
        self.img.paste(layer, (0, 0), layer)

    def save(self, path):
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.exists():
            # D 盘被识别为可移动驱动器：覆盖已存在文件会被拒，先删后写。
            try:
                p.unlink()
            except OSError:
                pass
        self.img.save(str(p))

    # -- 文字 -----------------------------------------------------------
    def text(self, xy, s, size=30, color=STEEL, cn=True, anchor="la",
             shadow=True, bold=True, alpha=1.0):
        """画一行文字。cn=True 用中文字体，cn=False 用等宽字体。

        bold 参数只为对齐契约签名：随附两种字体都只有粗体字重，恒为粗体。
        alpha 是这一行文字的整层不透明度（段内淡变就靠它）；color 也可给
        (r,g,b,a) 直接带透明度，两者相乘。
        """
        if not s:
            return
        font = font_cn(size) if cn else font_mono(size)
        x, y = int(round(xy[0])), int(round(xy[1]))
        base_a = color[3] if len(color) == 4 else 255
        a = int(round(base_a * clamp(alpha)))
        if a <= 0:
            return
        rgb = color[:3]
        if a >= 255:
            if shadow:
                self.draw.text((x + 1, y + 2), s, font=font, fill=SHADOW, anchor=anchor)
            self.draw.text((x, y), s, font=font, fill=rgb, anchor=anchor)
            return
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if shadow:
            d.text((x + 1, y + 2), s, font=font, fill=SHADOW + (a,), anchor=anchor)
        d.text((x, y), s, font=font, fill=rgb + (a,), anchor=anchor)
        self._composite(layer)

    # -- 字幕 -----------------------------------------------------------
    def caption(self, s, alpha=1.0, size=30, color=STEEL, y=CAPTION_BASELINE, outline=2):
        """底部居中字幕：自动换行 ≤2 行、40px 安全边距、深色描边、整条 alpha。

        超出 2 行时截断并加省略号，同时打印 warning（不静默丢字）。
        """
        alpha = clamp(alpha)
        if not s or alpha <= 0:
            return
        font = font_cn(size)
        lines = _wrap_caption(s, font, CAPTION_MAX_W, 2, warn_text=s)
        line_h = int(round(size * CAPTION_LINE_GAP))
        top_baseline = y - line_h * (len(lines) - 1)

        stroke = max(0, int(outline))
        stroke_rgb = BG
        if alpha >= 1.0:
            for i, line in enumerate(lines):
                by = top_baseline + i * line_h
                if stroke:
                    self.draw.text((W // 2, by), line, font=font, fill=color[:3],
                                   anchor="ms", stroke_width=stroke, stroke_fill=stroke_rgb)
                else:
                    self.draw.text((W // 2, by), line, font=font, fill=color[:3], anchor="ms")
            return

        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        for i, line in enumerate(lines):
            by = top_baseline + i * line_h
            if stroke:
                d.text((W // 2, by), line, font=font, fill=color[:3] + (255,),
                       anchor="ms", stroke_width=stroke, stroke_fill=stroke_rgb + (255,))
            else:
                d.text((W // 2, by), line, font=font, fill=color[:3] + (255,), anchor="ms")
        a = layer.getchannel("A").point(lambda v: int(v * alpha))
        layer.putalpha(a)
        self._composite(layer)

    # -- 结构 -----------------------------------------------------------
    def box(self, box, color=STEEL, alpha=1.0, width=2, corner=10):
        """圆角描边矩形。"""
        x0, y0, x1, y1 = _norm_box(box)
        if x1 - x0 < 2 or y1 - y0 < 2:
            return
        a = color[3] if len(color) == 4 else int(round(255 * clamp(alpha)))
        if a <= 0:
            return
        r = max(0, min(int(corner), (x1 - x0) // 2, (y1 - y0) // 2))
        if a >= 255:
            self.draw.rounded_rectangle([x0, y0, x1 - 1, y1 - 1], radius=r,
                                        outline=color[:3], width=int(width))
            return
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        d.rounded_rectangle([x0, y0, x1 - 1, y1 - 1], radius=r,
                            outline=color[:3] + (a,), width=int(width))
        self._composite(layer)

    def hairline(self, p0, p1, color=AMBER, alpha=1.0, width=2):
        """两点直线（结构线 / 数据通道线）。"""
        a = color[3] if len(color) == 4 else int(round(255 * clamp(alpha)))
        if a <= 0:
            return
        pts = [(int(round(p0[0])), int(round(p0[1]))), (int(round(p1[0])), int(round(p1[1])))]
        if a >= 255:
            self.draw.line(pts, fill=color[:3], width=int(width))
            return
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(layer).line(pts, fill=color[:3] + (a,), width=int(width))
        self._composite(layer)

    # -- 素材 -----------------------------------------------------------
    def tex(self, relpath, box, alpha=1.0, fit="contain", rot=0.0):
        """贴图。relpath 相对 src/main/resources/assets/。

        MC 贴图是像素画：放大一律 NEAREST，不插值。fit="contain" 等比放入 box 并居中；
        rot 为角度（度，逆时针），先旋转再居中粘贴，扩边不裁切。
        """
        alpha = clamp(alpha)
        if alpha <= 0:
            return
        src = _load_rgba(_ASSETS / relpath.replace("\\", "/"), _tex_cache)
        x0, y0, x1, y1 = _norm_box(box)
        bw, bh = x1 - x0, y1 - y0
        if bw <= 0 or bh <= 0:
            return
        iw, ih = src.size
        if fit == "fill":
            nw, nh = bw, bh
        elif fit == "cover":
            k = max(bw / float(iw), bh / float(ih))
            nw, nh = max(1, int(round(iw * k))), max(1, int(round(ih * k)))
        else:  # contain
            k = min(bw / float(iw), bh / float(ih))
            nw, nh = max(1, int(round(iw * k))), max(1, int(round(ih * k)))

        img = src.resize((nw, nh), Image.NEAREST) if (nw, nh) != src.size else src
        if rot:
            img = img.rotate(float(rot), resample=Image.NEAREST, expand=True)
        img = _scale_alpha(img, alpha)
        if img is None:
            return
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
        px, py = cx - img.width // 2, cy - img.height // 2
        if fit == "cover":
            # cover 允许溢出 box：先贴到临时层再裁回 box，避免盖住邻近内容。
            layer = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
            layer.paste(img, (px - x0, py - y0), img)
            self.img.paste(layer, (x0, y0), layer)
            return
        self.img.paste(img, (px, py), img)

    def poster(self, box=None, zoom=1.0, alpha=1.0):
        """官方主视觉海报：按 box 居中裁切（保比例）后 LANCZOS 缩放，zoom 推近。"""
        alpha = clamp(alpha)
        if alpha <= 0:
            return
        x0, y0, x1, y1 = _norm_box(box)
        bw, bh = x1 - x0, y1 - y0
        if bw <= 0 or bh <= 0:
            return
        src = _load_rgba(_POSTER, _poster_cache)
        iw, ih = src.size
        # 放大 = 裁得更小再拉回 box，围绕中心。
        k = clamp(zoom, 0.1, 4.0)
        target_ratio = bw / float(bh)
        crop_w, crop_h = iw / k, ih / k
        if crop_w / crop_h > target_ratio:
            crop_w = crop_h * target_ratio
        else:
            crop_h = crop_w / target_ratio
        cx, cy = iw / 2.0, ih / 2.0
        left = int(round(clamp(cx - crop_w / 2.0, 0, iw)))
        top = int(round(clamp(cy - crop_h / 2.0, 0, ih)))
        right = int(round(clamp(cx + crop_w / 2.0, 0, iw)))
        bottom = int(round(clamp(cy + crop_h / 2.0, 0, ih)))
        if right <= left or bottom <= top:
            return
        img = src.crop((left, top, right, bottom)).resize((bw, bh), Image.LANCZOS)
        img = _scale_alpha(img, alpha)
        if img is None:
            return
        self.img.paste(img, (x0, y0), img)

    def glow(self, box=None, color=AMBER, radius=8, alpha=0.4):
        """对已有画面做柔光叠加：高斯模糊副本按色相着色后 screen 回画面。

        screen 只提亮不压暗，因此不会把深底洗成灰；alpha 控制强度。
        """
        alpha = clamp(alpha)
        if alpha <= 0:
            return
        x0, y0, x1, y1 = _norm_box(box)
        if x1 <= x0 or y1 <= y0:
            return
        region = self.img.crop((x0, y0, x1, y1))
        blur = region.filter(ImageFilter.GaussianBlur(float(radius)))
        strength = blur.convert("L")
        tint = ImageChops.multiply(
            Image.new("RGB", region.size, color[:3]),
            Image.merge("RGB", (strength, strength, strength)),
        )
        lit = ImageChops.screen(region, tint)
        if alpha >= 1.0:
            out = lit
        else:
            out = Image.blend(region, lit, alpha)
        self.img.paste(out, (x0, y0))

    # -- 质感 -----------------------------------------------------------
    def hud_grid(self, alpha=0.12, step=40):
        """工程 HUD 网格（缓存，逐帧零成本）。"""
        overlay = _grid_overlay(int(step), round(clamp(alpha), 3))
        self._composite(overlay)

    def scanlines(self, alpha=0.06):
        """CRT 扫描线（缓存）。"""
        overlay = _scanline_overlay(round(clamp(alpha), 3))
        self._composite(overlay)

    def vignette(self, alpha=0.35):
        """四角压暗（缓存乘数图）。"""
        mult = _vignette_mult(round(clamp(alpha), 3))
        self._replace(ImageChops.multiply(self.img, mult))

    def flash(self, color=AMBER, alpha=0.0):
        """整屏色闪。alpha=0 时不动画面。"""
        alpha = clamp(alpha)
        if alpha <= 0:
            return
        if alpha >= 1.0:
            self._replace(Image.new("RGB", (W, H), color[:3]))
            return
        self._replace(Image.blend(self.img, Image.new("RGB", (W, H), color[:3]), alpha))

    def dim(self, alpha=0.5, color=BG):
        """整体压暗 / 压向底色（S02 的海报偏暗、段间压低对比都用它）。"""
        self.flash(color, alpha)


# ----------------------------------------------------------------------------
# 排版与缓存图层
# ----------------------------------------------------------------------------
def _tokens(s):
    """中英混排切词：ASCII 单词整体成块，其余逐字，空格保留为独立 token。"""
    tokens = []
    buf = ""
    for ch in s:
        if ch.isspace():
            if buf:
                tokens.append(buf)
                buf = ""
            tokens.append(" ")
        elif ch.isascii() and (ch.isalnum() or ch in "._/+-·%:,"):
            buf += ch
        else:
            if buf:
                tokens.append(buf)
                buf = ""
            tokens.append(ch)
    if buf:
        tokens.append(buf)
    return tokens


def _wrap_caption(s, font, max_w, max_lines, warn_text=None):
    """按像素宽度换行，最多 max_lines 行；超出的内容截断加「…」并告警。"""
    lines = []
    cur = ""
    for tok in _tokens(s):
        cand = cur + tok
        if font.getlength(cand) <= max_w:
            cur = cand
            continue
        if tok == " ":
            lines.append(cur)
            cur = ""
            continue
        if cur.strip():
            lines.append(cur)
            cur = ""
        # 单个 token 本身就超宽（长英文串），硬切
        for ch in tok:
            if font.getlength(cur + ch) > max_w and cur:
                lines.append(cur)
                cur = ""
            cur += ch
    if cur.strip():
        lines.append(cur)
    lines = [ln.strip() for ln in lines if ln.strip()]

    if len(lines) > max_lines:
        kept = lines[:max_lines]
        dropped = "".join(lines[max_lines:])
        last = kept[-1]
        while last and font.getlength(last + "…") > max_w:
            last = last[:-1]
        kept[-1] = last + "…"
        print(
            "warning: caption truncated (%d lines > %d): %r (dropped %r)"
            % (len(lines), max_lines, (warn_text or s), dropped),
            file=sys.stderr,
        )
        return kept
    if not lines:
        return [""]
    return lines


_grid_cache = {}


def _grid_overlay(step, alpha):
    key = (step, alpha)
    img = _grid_cache.get(key)
    if img is not None:
        return img
    step = max(8, step)
    a_minor = int(round(255 * alpha))
    a_major = min(255, int(round(255 * alpha * 1.9)))
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i, x in enumerate(range(0, W + 1, step)):
        major = (i % 4 == 0)
        if not major and a_minor <= 0:
            continue
        d.line([(x, 0), (x, H)], fill=SLATE + (a_major if major else a_minor,), width=1)
    for i, y in enumerate(range(0, H + 1, step)):
        major = (i % 4 == 0)
        if not major and a_minor <= 0:
            continue
        d.line([(0, y), (W, y)], fill=SLATE + (a_major if major else a_minor,), width=1)
    _grid_cache[key] = layer
    return layer


_scan_cache = {}


def _scanline_overlay(alpha, period=3):
    key = (alpha, period)
    img = _scan_cache.get(key)
    if img is not None:
        return img
    a = int(round(255 * alpha))
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if a > 0:
        for y in range(0, H, period):
            d.line([(0, y), (W, y)], fill=SHADOW + (a,), width=1)
    _scan_cache[key] = layer
    return layer


_vignette_cache = {}


def _vignette_mult(alpha):
    img = _vignette_cache.get(alpha)
    if img is not None:
        return img
    grad = Image.radial_gradient("L").resize((W, H), Image.BILINEAR)   # 中心 0 → 边缘 255
    mult = grad.point(lambda v: int(round(255 * (1.0 - alpha * (v / 255.0)))))
    img = Image.merge("RGB", (mult, mult, mult))
    _vignette_cache[alpha] = img
    return img


# ----------------------------------------------------------------------------
# 编码
# ----------------------------------------------------------------------------
def write_video(frames_iter, out_path, fps=FPS, crf=16, progress=0, label=""):
    """逐帧 pipe 给 ffmpeg 编码 h264 / yuv420p / 无音轨，返回写出的帧数。

    frames_iter 的每一项可以是 PIL Image 或长度 W*H*3 的 bytes。
    progress>0 时每 N 帧打印一次进度；label 只进日志。
    """
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg not found in PATH")

    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        # D 盘：覆盖已存在文件会被拒；先删后写。
        try:
            out.unlink()
        except OSError as exc:
            raise RuntimeError("cannot remove existing output %s: %s" % (out, exc))

    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", "%dx%d" % (W, H), "-r", str(fps), "-i", "pipe:0",
        "-c:v", "libx264", "-crf", str(crf), "-preset", "medium",
        "-pix_fmt", "yuv420p", "-an",
        "-movflags", "+faststart",
        str(out),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    count = 0
    try:
        for frame in frames_iter:
            if isinstance(frame, (bytes, bytearray)):
                data = bytes(frame)
            else:
                if frame.size != (W, H):
                    raise ValueError("frame size %r != %dx%d" % (frame.size, W, H))
                data = frame.tobytes()
            if len(data) != W * H * 3:
                raise ValueError("frame byte length %d != %d" % (len(data), W * H * 3))
            proc.stdin.write(data)
            count += 1
            if progress and count % progress == 0:
                print("%s frame %d" % (label or "write", count), flush=True)
    finally:
        if proc.stdin:
            try:
                proc.stdin.close()
            except (BrokenPipeError, OSError):
                pass
    err = proc.stderr.read() if proc.stderr else b""
    rc = proc.wait()
    if rc != 0:
        tail = err.decode("utf-8", "replace").strip().splitlines()[-20:]
        raise RuntimeError(
            "ffmpeg failed (exit %d) writing %s\n%s" % (rc, out, "\n".join(tail))
        )
    if count == 0:
        raise RuntimeError("no frame was written to %s" % out)
    return count


# ----------------------------------------------------------------------------
# 自测：24 帧（HUD 网格 + 贴图 + 中文字幕 + 首尾淡变）
# ----------------------------------------------------------------------------
def _selftest():
    import json

    here = Path(__file__).resolve().parent
    out_dir = here / "out" / "kitcheck"
    out_dir.mkdir(parents=True, exist_ok=True)
    n = 24
    sample = {}

    def frames():
        for f in range(n):
            a = fade_alpha(f, n, head=12, tail=12)
            fr = Frame()
            fr.clear()
            fr.flash(AMBER, 0.06 * a)
            fr.hud_grid(alpha=0.16 * a)
            fr.box((80, 96, 880, 452), color=SLATE, alpha=a, width=2, corner=12)
            fr.tex("hbmzz/textures/blocks/dfc_monitor_front.png", (150, 130, 330, 300), alpha=a)
            fr.tex("hbmzz/textures/blocks/nuke_monitor_front.png", (350, 130, 530, 300), alpha=a)
            fr.text((620, 168), "3412", size=54, color=AMBER, cn=False, anchor="la", alpha=a)
            fr.text((620, 240), "FLUX 0.00", size=26, color=STEEL, cn=False, anchor="la", alpha=a)
            fr.glow((600, 150, 860, 210), radius=10, alpha=0.35 * a)
            fr.scanlines(alpha=0.05 * a)
            fr.vignette(alpha=0.30)
            fr.caption("一个核电站，六块屏，都该说同一件事。", alpha=a)
            if f in (0, 12, 23):
                sample[f] = fr.img.copy()
            yield fr.img

    out_mp4 = out_dir / "kitcheck.mp4"
    written = write_video(frames(), out_mp4, fps=FPS, crf=16, progress=12, label="kit")
    for f, img in sample.items():
        p = out_dir / ("frame_%03d.png" % f)
        if p.exists():
            p.unlink()
        img.save(str(p))

    info = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "stream=codec_name,width,height,r_frame_rate,nb_frames,pix_fmt,codec_type",
         "-of", "json", str(out_mp4)],
        capture_output=True, text=True,
    ).stdout
    print("written frames: %d" % written)
    print(json.dumps(json.loads(info), indent=2))
    print("out: %s" % out_mp4)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        _selftest()
    else:
        print(__doc__)
