"""把离线渲染图、字幕与自制原声拼成宣传片（1920x1080 / 30fps / 62 秒）。

素材全部来自模组自己的渲染器，不是 AI 生成，也不是别的整合包的截图：

    java -cp build/classes/java/main com.hbmzz.tools.RigPreview      tmp/promo/rig
    java -cp build/classes/java/main com.hbmzz.tools.PreviewAlerts   tmp/promo/alerts

    python tools/promo/make_trailer.py            # 出片 + 封面 + 62 秒音轨
    python tools/promo/make_trailer.py --cover    # 只重出封面

字幕用 ffmpeg 的 drawtext 画，字体走系统里的微软雅黑/Consolas，所以不需要浏览器；
文字一律写进临时文件再用 textfile= 引用，省掉 filtergraph 里那套转义。
"""

import argparse
import io
import os
import shutil
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "tmp", "promo")
OUT = os.path.join(SRC, "out")
TXT = os.path.join(SRC, "text")

W, H, FPS = 1920, 1080, 30

# 站点用的那套颜色，一个不多一个不少
BG = "0x0a0c0f"
PANEL = "0x10151b"
AMBER = "0xf5a524"
AMBER_HI = "0xffc25c"
TEXT = "0xd9e0e8"
DIM = "0x93a0b0"
MUTE = "0x67737f"
RED = "0xe5484d"

BOLD = "C\\:/Windows/Fonts/msyhbd.ttc"
SANS = "C\\:/Windows/Fonts/msyh.ttc"
MONO = "C\\:/Windows/Fonts/consola.ttf"
MONO_B = "C\\:/Windows/Fonts/consolab.ttf"

AUDIO = os.path.join(ROOT, "docs", "assets", "audio", "how-to-survive.mp3")
AUDIO_AT = 30.0       # 由 tools/promo/pick_window.py 挑出来的最满的一段
AUDIO_LEN = 61.0   # 必须等于各段时长之和，否则淡出会被 -shortest 切掉


def scrim(w, h):
    """生成一张 RGBA 压暗层：左侧横向渐深、底部再补一道，压字用。

    用它而不是叠加半透明色块：色块是有硬边的，覆盖在带网格的渲染图上会留下
    一条明显的矩形接缝。ffmpeg 的 blend/gradients 在这台机器的构建上表现不稳
    （乘出来偏色），索性自己写 PNG——只要 zlib，没有依赖。
    """
    path = os.path.join(SRC, "scrim-%dx%d.png" % (w, h))
    if os.path.exists(path):
        return path
    os.makedirs(SRC, exist_ok=True)
    import struct
    import zlib

    raw = bytearray()
    for y in range(h):
        raw.append(0)                       # 每行的 filter byte
        for x in range(w):
            left = 1.0 - x / (w * 0.66)     # 左边三分之二拉满
            if left < 0.0:
                left = 0.0
            bottom = (y - h * 0.52) / (h * 0.48)
            if bottom < 0.0:
                bottom = 0.0
            a = max(left ** 1.15 * 0.94, bottom * 0.86)
            if a > 0.95:
                a = 0.95
            raw += bytes((8, 10, 13, int(a * 255)))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return path


def rel(path):
    """一律用相对仓库根的路径喂 ffmpeg：冒号与反斜杠在 filtergraph 里都是雷。"""
    return os.path.relpath(path, ROOT).replace("\\", "/")


def tfile(name, text):
    """把一段文字写成文件，返回 textfile= 参数值。"""
    os.makedirs(TXT, exist_ok=True)
    path = os.path.join(TXT, name + ".txt")
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    # 必须给相对路径：filtergraph 里冒号是参数分隔符，D:/... 会把 textfile=
    # 这一项从冒号处截断，报的是「No option name near '/DSH/...'」。
    return os.path.relpath(path, ROOT).replace("\\", "/")


def dt(text, **kw):
    """拼一个 drawtext 片段；字体、坐标、颜色都可以覆盖。"""
    name = kw.pop("name")
    src = tfile(name, text)
    parts = ["drawtext=textfile=%s" % src]
    parts.append("fontfile='%s'" % kw.pop("font", BOLD))
    parts.append("fontcolor=%s" % kw.pop("color", TEXT))
    parts.append("fontsize=%d" % kw.pop("size", 40))
    parts.append("x=%s" % kw.pop("x", 140))
    parts.append("y=%s" % kw.pop("y", 900))
    if "alpha" in kw:
        parts.append("alpha='%s'" % kw.pop("alpha"))
    if "spacing" in kw:
        parts.append("line_spacing=%d" % kw.pop("spacing"))
    if kw:
        raise TypeError("多余的 drawtext 参数：%s" % kw)
    return ":".join(parts)


def fade_in(t0, dur=0.6):
    """字幕淡入：t0 之前不可见，之后用 dur 秒升到不透明。"""
    return "if(lt(t,%s),0,min(1,(t-%s)/%s))" % (t0, t0, dur)


def caption(kicker, title, sub, t0=0.7):
    """左下角那组字幕：一条琥珀竖线 + 小标 + 标题 + 说明。"""
    return [
        "drawbox=x=96:y=858:w=4:h=118:color=%s@1:t=fill:enable='gte(t,%s)'" % (AMBER, t0),
        dt(kicker, name="cap_k_" + title, font=MONO_B, color=AMBER, size=22, x=124, y=862,
           alpha=fade_in(t0)),
        dt(title, name="cap_t_" + title, font=BOLD, color=TEXT, size=52, x=124, y=896,
           alpha=fade_in(t0 + 0.12)),
        dt(sub, name="cap_s_" + title, font=SANS, color=DIM, size=26, x=126, y=962,
           alpha=fade_in(t0 + 0.26)),
    ]


def still_clip(img, dur, out, extra=None, zoom=0.0009, start_zoom=1.0, fade=0.4, mode="photo"):
    """定帧 → 一段缓慢推镜的片段。

    mode="photo"：渲染图铺满整幅（再叠一层压暗层）。铺满是因为它的底是带网格
    的深色，和页面底色差一点点——居中留边会露出一条矩形接缝；而铺满顺带把图
    上那行 "4 x 3 blocks tilt 30 deg" 的标注裁掉了。

    mode="screen"：屏幕画面只有 512，铺满会糊。居中放成一张卡片，横竖各留 760。
    """
    fade_out = ("fade=t=out:st=%s:d=%s" % (dur - 0.35, 0.35))
    tail = "vignette=angle=PI/3.6,format=yuv420p"
    if fade:
        tail += ",fade=t=in:st=0:d=%s" % fade
    tail += "," + fade_out
    if extra:
        tail += "," + ",".join(extra)

    zoom_expr = ("zoompan=z='min(%s+%s*on,%.3f)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
                 ":d=1:s=%dx%d:fps=%d" % (start_zoom, zoom, start_zoom + zoom * dur * FPS, W, H, FPS))

    if mode == "photo":
        sc = scrim(W, H)
        graph = (
            "[0:v]scale=%d:%d:force_original_aspect_ratio=increase:flags=lanczos,crop=%d:%d,%s[v0];"
            "[v0][1:v]overlay=0:0,%s[vout]"
            % (W * 2, H * 2, W * 2, H * 2, zoom_expr, tail))
        cmd = ["ffmpeg", "-hide_banner", "-v", "error", "-y",
               "-loop", "1", "-framerate", str(FPS), "-t", str(dur), "-i", rel(img),
               "-loop", "1", "-framerate", str(FPS), "-t", str(dur), "-i", rel(sc),
               "-filter_complex", graph, "-map", "[vout]",
               "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
               out]
    else:
        graph = ("[0:v]scale=880:880:flags=lanczos,pad=2400:1350:(ow-iw)/2:(oh-ih)/2:color=%s,%s,%s[vout]"
                 % (BG, zoom_expr, tail))
        cmd = ["ffmpeg", "-hide_banner", "-v", "error", "-y",
               "-loop", "1", "-framerate", str(FPS), "-t", str(dur), "-i", rel(img),
               "-filter_complex", graph, "-map", "[vout]",
               "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
               out]
    subprocess.run(cmd, check=True, cwd=ROOT)


def color_clip(dur, out, extra, fade=0.5):
    """纯色底 + 字幕（标题卡、结束卡、特性卡都走这里）。"""
    chain = ["format=yuv420p", "fade=t=in:st=0:d=%s" % fade]
    chain.extend(extra)
    cmd = ["ffmpeg", "-hide_banner", "-v", "error", "-y",
           "-f", "lavfi", "-i", "color=c=%s:s=%dx%d:r=%d" % (BG, W, H, FPS),
           "-t", str(dur), "-vf", ",".join(chain),
           "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
           out]
    subprocess.run(cmd, check=True, cwd=ROOT)


def grid():
    """标题卡上那层很淡的网格：横竖各几条，别抢字。"""
    out = []
    for y in range(120, H - 100, 120):
        out.append("drawbox=x=96:y=%d:w=1728:h=1:color=0x232a33@0.35:t=fill" % y)
    for x in range(120, W - 100, 160):
        out.append("drawbox=x=%d:y=96:w=1:h=888:color=0x232a33@0.22:t=fill" % x)
    return out


def build():
    os.makedirs(OUT, exist_ok=True)
    rig = os.path.join(SRC, "rig")
    al = os.path.join(SRC, "alerts")
    segs = []

    # 1) 标题卡
    p = os.path.join(OUT, "01.mp4")
    color_clip(6.0, p, [dt("// HBM 附属 · MINECRAFT 1.12.2", name="t_kick", font=MONO_B,
                           color=AMBER, size=25, x=142, y=372, alpha=fade_in(0.5)),
                        dt("HBMZZ", name="t_big", font=BOLD, color=TEXT, size=172,
                           x=128, y=412, alpha=fade_in(0.8)),
                        dt("核电厂的每一块屏，都该说同一件事", name="t_sub", font=SANS,
                           color=DIM, size=35, x=142, y=642, alpha=fade_in(1.3)),
                        "drawbox=x=142:y=716:w=180:h=3:color=%s@1:t=fill:enable='gte(t,1.6)'" % AMBER,
                        dt("v3.2.0 · FORGE 14.23.5.2847 · GPL-3.0-ONLY", name="t_ver",
                           font=MONO, color=MUTE, size=21, x=142, y=742, alpha=fade_in(1.8))]
               + grid())
    segs.append(p)

    # 2) 吊装面板
    p = os.path.join(OUT, "02.mp4")
    still_clip(os.path.join(rig, "rig-hung.png"), 11.0, p,
               caption("// 01 — HARDWARE", "吊装式工业显示器",
                       "2×2 格 · 与 DFC 监视器同一套外壳、同一套倾角"))
    segs.append(p)

    # 3) 设施代码：两张屏，一红一断线
    p = os.path.join(OUT, "03.mp4")
    still_clip(os.path.join(al, "alert-red.png"), 6.0, p,
               caption("// 02 — FACILITY BUS", "八级设施代码",
                       "一块屏出的事，全厂在同一秒知道"), mode="screen")
    segs.append(p)

    p = os.path.join(OUT, "04.mp4")
    still_clip(os.path.join(al, "alert-delta-nolink.png"), 5.0, p,
               caption("// 02 — FACILITY BUS", "读不到就是读不到",
                       "链路断了画 NO LINK，不拿上一帧的 GREEN 骗人", t0=0.4),
               start_zoom=1.05, zoom=0.0010, mode="screen")
    segs.append(p)

    # 4) 核事故
    p = os.path.join(OUT, "05.mp4")
    still_clip(os.path.join(al, "nuke-countdown.png"), 5.5, p,
               caption("// 03 — NUKE", "倒计时、起爆、广播",
                       "起爆前你可以中止；起爆后全图只有一个记录"), mode="screen")
    segs.append(p)

    p = os.path.join(OUT, "06.mp4")
    still_clip(os.path.join(al, "alert-alpha.png"), 5.0, p,
               caption("// 03 — NUKE", "ALPHA：核弹事件",
                       "最高一级由系统自己升，不靠人按"), mode="screen")
    segs.append(p)

    # 5) DFC 那一侧
    p = os.path.join(OUT, "07.mp4")
    still_clip(os.path.join(rig, "rig-under.png"), 5.0, p,
               caption("// 04 — DFC", "熔毁不再无声",
                       "爆炸传感器：炸完输出十五级红石，逐级衰减"))
    segs.append(p)

    p = os.path.join(OUT, "08.mp4")
    still_clip(os.path.join(rig, "rig-front.png"), 5.0, p,
               caption("// 04 — DFC", "同一台机器，三种挂法",
                       "吊装、贴墙、四种尺寸，碰撞板只有 2/16 格"),
               start_zoom=1.06, zoom=0.0008)
    segs.append(p)

    # 6) 特性卡
    p = os.path.join(OUT, "09.mp4")
    lines = ["实时屏幕 · 终端 · 面板工坊",
             "节点图控制台 · 机器控制",
             "核事故系统 · 全厂同步",
             "1.12.2 FORGE · GPL-3.0"]
    extra = ["drawbox=x=142:y=286:w=64:h=3:color=%s@1:t=fill:enable='gte(t,0.4)'" % AMBER,
             dt("// WHAT'S IN IT", name="f_kick", font=MONO_B, color=AMBER, size=24,
                x=142, y=318, alpha=fade_in(0.3))]
    for i, line in enumerate(lines):
        extra.append(dt(line, name="f_%d" % i, font=BOLD if i < 3 else MONO,
                        color=TEXT if i < 3 else MUTE, size=46 if i < 3 else 26,
                        x=142, y=386 + i * 96, alpha=fade_in(0.5 + i * 0.18)))
    color_clip(7.0, p, extra)
    segs.append(p)

    # 7) 结束卡
    p = os.path.join(OUT, "10.mp4")
    color_clip(5.5, p, [dt("HBMZZ", name="e_big", font=BOLD, color=TEXT, size=96,
                           x=140, y=380, alpha=fade_in(0.4)),
                        "drawbox=x=140:y=520:w=120:h=3:color=%s@1:t=fill:enable='gte(t,0.8)'" % AMBER,
                        dt("hbm-zz.ysbdwz.dpdns.org", name="e_url", font=MONO_B,
                           color=AMBER_HI, size=44, x=140, y=556, alpha=fade_in(0.9)),
                        dt("github.com/ysb-discowave/HBMZZ-MOD", name="e_gh", font=MONO,
                           color=DIM, size=26, x=140, y=626, alpha=fade_in(1.3)),
                        dt("许可 GPL-3.0-only · 与 NTM:CE、Leafia 官方无关，仅社区附属", name="e_lic",
                           font=SANS, color=MUTE, size=22, x=140, y=690, alpha=fade_in(1.7))])
    segs.append(p)

    # 拼接：硬切。每段自己带 0.4 秒淡入，切点反而是干净的。
    lst = os.path.join(OUT, "concat.txt")
    with io.open(lst, "w", encoding="utf-8", newline="\n") as f:
        for s in segs:
            f.write("file '%s'\n" % s.replace("\\", "/"))

    silent = os.path.join(OUT, "silent.mp4")
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", lst,
                    "-c", "copy", silent], check=True, cwd=ROOT)

    # 音轨：挑出来的那 62 秒，两端淡入淡出，整体拉到 -16 LUFS
    audio = os.path.join(OUT, "hbmzz-trailer.mp3")
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y",
                    "-ss", str(AUDIO_AT), "-t", str(AUDIO_LEN), "-i", rel(AUDIO),
                    "-af", "afade=t=in:st=0:d=2,afade=t=out:st=%s:d=4,"
                           "loudnorm=I=-16:TP=-1.5:LRA=11" % (AUDIO_LEN - 4),
                    "-c:a", "libmp3lame", "-b:a", "192k", audio], check=True, cwd=ROOT)

    final = os.path.join(OUT, "hbmzz-trailer.mp4")
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y",
                    "-i", rel(silent), "-i", rel(audio),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-shortest", "-movflags", "+faststart", final], check=True, cwd=ROOT)

    print("video  %s  %.1f MB" % (final, os.path.getsize(final) / 1e6))
    print("audio  %s  %.1f MB" % (audio, os.path.getsize(audio) / 1e6))


def cover(w=1920, h=1080, name="cover-1920x1080.png"):
    """封面：整张渲染图铺满，标题压左下那块压暗过的黑区。

    不做「左边字、右边图」那种拼接——渲染图的底是带网格的深色，和页面底色差
    一点点，留边会露出矩形接缝。铺满之后没有边，也就没有接缝。
    """
    os.makedirs(OUT, exist_ok=True)
    src = os.path.join(SRC, "rig", "rig-hung.png")
    sc = scrim(w, h)
    k = w / 1920.0
    extra = [
        "drawbox=x=%d:y=%d:w=4:h=%d:color=%s@1:t=fill" % (
            int(108 * k), int(600 * k), int(136 * k), AMBER),
        dt("// HBM 附属 · MINECRAFT 1.12.2", name="c_kick%d" % w, font=MONO_B, color=AMBER,
           size=int(24 * k), x=int(140 * k), y=int(596 * k)),
        dt("HBMZZ", name="c_big%d" % w, font=BOLD, color=TEXT, size=int(136 * k),
           x=int(132 * k), y=int(628 * k)),
        dt("核电厂的每一块屏，都该说同一件事", name="c_sub%d" % w, font=BOLD, color=DIM,
           size=int(38 * k), x=int(138 * k), y=int(806 * k)),
        dt("v3.2.0 · FORGE 14.23.5.2847 · GPL-3.0-ONLY", name="c_ver%d" % w, font=MONO,
           color=MUTE, size=int(21 * k), x=int(140 * k), y=int(892 * k)),
        dt("hbm-zz.ysbdwz.dpdns.org", name="c_url%d" % w, font=MONO_B, color=AMBER_HI,
           size=int(26 * k), x=int(140 * k), y=int(938 * k)),
    ]
    graph = ("[0:v]scale=%d:%d:force_original_aspect_ratio=increase:flags=lanczos,crop=%d:%d,"
             "vignette=angle=PI/3.4,eq=brightness=-0.03:saturation=1.06[v0];"
             "[v0][1:v]overlay=0:0[v1];[v1]%s[vout]"
             % (w, h, w, h, ",".join(extra)))
    out = os.path.join(OUT, name)
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y",
                    "-i", rel(src), "-i", rel(sc),
                    "-filter_complex", graph, "-map", "[vout]",
                    "-frames:v", "1", "-update", "1", out], check=True, cwd=ROOT)
    print("cover  %s  %.2f MB" % (out, os.path.getsize(out) / 1e6))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cover", action="store_true", help="只重出封面")
    args = ap.parse_args()

    for need in (SRC, os.path.join(SRC, "rig"), os.path.join(SRC, "alerts")):
        if not os.path.isdir(need):
            raise SystemExit("缺素材：%s —— 先跑 RigPreview / PreviewAlerts，见文件头" % need)

    if not args.cover:
        build()
    cover()
    cover(1280, 720, "cover-1280x720.png")


if __name__ == "__main__":
    main()
