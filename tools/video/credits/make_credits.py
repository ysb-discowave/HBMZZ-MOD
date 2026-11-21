#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 HBMZZ 片尾致谢动画，并打包成视频广播器可播放的 zip。

用法：
    python tools/video/credits/make_credits.py                      # 原版：The ending of melting
    python tools/video/credits/make_credits.py --theme meltdown     # 核弹重制版：meltdown.ogg

也可单独覆盖任一参数：
    python tools/video/credits/make_credits.py --names names.meltdown.json \
        --bgm <路径> --frames 3456 --out hbmzz-credits-meltdown --theme meltdown

改名单只需要改对应的 names*.json，然后重跑命令。

产物（tools/video/credits/out/）：
    <out>.mp4   带 BGM 的成片，留着自己看
    <out>.zip   视频包，丢进客户端 config/hbmzz/videos/

原理：画面交给 ASS 字幕（libass）渲染。所有行共用同一段 \\move，
各自只差一个固定的 y 偏移，于是整块内容匀速上移。行数、行距、起止 y
全部由名单自动算出来，不用手调。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import zipfile

# ---------------------------------------------------------------- 固定的技术参数

FPS = 12                  # 播放器默认帧率
WIDTH, HEIGHT = 960, 540  # 播放器配置上限 videoMaxWidth/Height

DEFAULT_FRAMES = 2326      # 原版 193.817s * 12 向上取整
DEFAULT_BGM_NAME = "the-ending-of-melting.mp3"
DEFAULT_OUT = "hbmzz-credits"

# 滚动布局：第一行从屏幕下沿外进入，最后一行在结束时刚好离开屏幕上沿。
START_Y = 600             # 第一行的中心 y（屏幕高 540，即刚好在画面外）
END_Y = 200               # 结束时最后一行的中心 y：收在画面里，结尾不留空屏
SPACING = 115             # 行距

# 版式：名字右对齐成一列，职责紧跟其右；跨组标题/结尾卡水平居中。
NAME_X = 440              # Name 样式右对齐的锚点
ROLE_X = 458              # Role 样式左对齐的锚点

# ---------------------------------------------------------------- 路径

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
AUDIO_DIR = os.path.join(ROOT, "docs", "assets", "audio")
DEFAULT_BGM = os.path.join(AUDIO_DIR, DEFAULT_BGM_NAME)
DEFAULT_NAMES = os.path.join(HERE, "names.json")
OUT_DIR = os.path.join(HERE, "out")
BUILD_DIR = os.path.join(HERE, ".build")

STYLE_HEADER = """[Script Info]
ScriptType: v4.00+
PlayResX: 960
PlayResY: 540
WrapStyle: 2
ScaledBorderAndShadow: yes
YCbCr Matrix: TV.601

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,Microsoft YaHei,58,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,1,0,1,0,0,5,0,0,0,1
Style: Head,Microsoft YaHei,46,&H0024A5F5,&H0024A5F5,&H00000000,&H00000000,1,0,0,0,100,100,2,0,1,0,0,5,0,0,0,1
Style: Name,Microsoft YaHei,38,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,6,0,0,0,1
Style: Role,Microsoft YaHei,21,&H00C4B4A8,&H00C4B4A8,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,4,0,0,0,1
Style: Note,Microsoft YaHei,24,&H00C4B4A8,&H00C4B4A8,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5,0,0,0,1
Style: Card,Microsoft YaHei,40,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

# 核弹重制版配色：警报红 / 橙 / 黄。ASS 颜色格式 &HAABBGGRR。
MELTDOWN_REPLACEMENTS = {
    "&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,1,0,1,0,0,5":  # Title 58
        "&H00303BFF,&H00303BFF,&H00000000,&H00000000,1,0,0,0,100,100,1,0,1,0,0,5",
    "&H0024A5F5,&H0024A5F5,&H00000000,&H00000000,1,0,0,0,100,100,2,0,1,0,0,5":  # Head
        "&H001A6AFF,&H001A6AFF,&H00000000,&H00000000,1,0,0,0,100,100,2,0,1,0,0,5",
    "&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,6":  # Name 38
        "&H00D0E8FF,&H00D0E8FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,6",
    "&H00C4B4A8,&H00C4B4A8,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,4":  # Role 21
        "&H0033C7FF,&H0033C7FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,4",
    "&H00C4B4A8,&H00C4B4A8,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5":  # Note 24
        "&H0033C7FF,&H0033C7FF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5",
    "&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5":  # Card 40
        "&H00D0E8FF,&H00D0E8FF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5",
}


def style_header(theme: str) -> str:
    if theme != "meltdown":
        return STYLE_HEADER
    out = STYLE_HEADER
    for old, new in MELTDOWN_REPLACEMENTS.items():
        out = out.replace(old, new)
    return out


def background_filter(theme: str) -> str:
    """视频底色。原版纯黑；核弹版深红黑 + 上下警报条纹。"""
    if theme != "meltdown":
        return "color=black:s=%dx%d:r=%d" % (WIDTH, HEIGHT, FPS)
    return (
        "color=0x100404:s=%dx%d:r=%d,"
        "drawbox=x=0:y=0:w=iw:h=10:color=0x8B0000:t=fill,"
        "drawbox=x=0:y=ih-10:w=iw:h=10:color=0x8B0000:t=fill"
    ) % (WIDTH, HEIGHT, FPS)


def die(message: str) -> None:
    print("错误: " + message, file=sys.stderr)
    raise SystemExit(1)


def find_ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if not found:
        die("PATH 里找不到 ffmpeg。")
    return found


def ass_time(seconds: float) -> str:
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    rest = seconds % 60
    return "%d:%02d:%05.2f" % (hours, minutes, rest)


# ---------------------------------------------------------------- 排版

def build_rows(data: dict) -> list[tuple[str, str, str]]:
    """摊平成 [(kind, text, role)]，kind 决定样式与横向锚点。

    kind=gap 表示一个空行，只占纵向位置，不产生字幕事件。
    """
    rows: list[tuple[str, str, str]] = []

    if data.get("title"):
        rows.append(("title", data["title"], ""))
    if data.get("subtitle"):
        rows.append(("head", data["subtitle"], ""))
    rows.extend([("gap", "", "")] * 2)

    for section in data.get("sections", []):
        rows.append(("head", section.get("header", ""), ""))
        rows.append(("gap", "", ""))
        for person in section.get("people", []):
            rows.append(("name", person.get("name", ""), person.get("role", "")))
        if section.get("note"):
            rows.append(("gap", "", ""))
            rows.append(("note", section["note"], ""))
        rows.extend([("gap", "", "")] * 3)

    outro = data.get("outro") or []
    for index, line in enumerate(outro):
        # 收尾卡：最后一行用大号标题样式，和开场呼应；中间不留空行，
        # 这样最后一帧两行都完整停在画面里，不会有半行被上边缘切掉。
        kind = "title" if index == len(outro) - 1 else "card"
        rows.append((kind, line, ""))
    return rows


def build_ass(rows: list[tuple[str, str, str]], duration: float, duration_ms: int,
              header: str) -> str:
    span = (len(rows) - 1) * SPACING
    delta = END_Y - START_Y - span     # 负值：整体向上滚

    events = []
    for index, (kind, text, role) in enumerate(rows):
        if kind == "gap":
            continue
        y1 = START_Y + index * SPACING
        y2 = y1 + delta
        end = ass_time(duration)
        if kind == "name":
            move = "\\move(%d,%d,%d,%d,0,%d)" % (NAME_X, y1, NAME_X, y2, duration_ms)
            events.append("Dialogue: 0,0:00:00.00,%s,Name,,0,0,0,,{%s}%s"
                          % (end, move, text))
            if role:
                move = "\\move(%d,%d,%d,%d,0,%d)" % (ROLE_X, y1, ROLE_X, y2, duration_ms)
                events.append("Dialogue: 0,0:00:00.00,%s,Role,,0,0,0,,{%s}%s"
                              % (end, move, role))
        else:
            style = {"title": "Title", "head": "Head", "note": "Note", "card": "Card"}[kind]
            x = WIDTH // 2
            move = "\\move(%d,%d,%d,%d,0,%d)" % (x, y1, x, y2, duration_ms)
            events.append("Dialogue: 0,0:00:00.00,%s,%s,,0,0,0,,{%s}%s"
                          % (end, style, move, text))

    return header + "\n".join(events) + "\n"


# ---------------------------------------------------------------- 生成

def run(cmd: list[str], cwd: str | None = None) -> None:
    print("$ " + " ".join(cmd))
    if subprocess.call(cmd, cwd=cwd) != 0:
        die("命令失败: " + " ".join(cmd))


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="生成 HBMZZ 片尾致谢动画")
    p.add_argument("--names", default=DEFAULT_NAMES, help="名单 json 路径")
    p.add_argument("--bgm", default=DEFAULT_BGM, help="BGM 路径")
    p.add_argument("--frames", type=int, default=DEFAULT_FRAMES,
                   help="总帧数（= 时长秒 × %d 向上取整）" % FPS)
    p.add_argument("--out", default=DEFAULT_OUT, help="输出文件名前缀（不含扩展名）")
    p.add_argument("--theme", default="classic", choices=["classic", "meltdown"],
                   help="视觉主题：classic 原版 / meltdown 核弹重制版")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    ffmpeg = find_ffmpeg()
    # ffmpeg 渲染时 cwd 会切到 .build，路径必须先绝对化，否则相对路径失效。
    args.bgm = os.path.abspath(args.bgm)
    args.names = os.path.abspath(args.names)
    if not os.path.isfile(args.bgm):
        die("找不到 BGM: " + args.bgm)
    if not os.path.isfile(args.names):
        die("找不到名单: " + args.names)

    duration = args.frames / FPS
    duration_ms = int(round(duration * 1000))

    with open(args.names, "r", encoding="utf-8") as handle:
        data = json.load(handle)

    rows = build_rows(data)
    content = sum(1 for row in rows if row[0] != "gap")
    print("主题 %s / 内容行 %d 行，总占位 %d 行，时长 %.2fs"
          % (args.theme, content, len(rows), duration))

    shutil.rmtree(BUILD_DIR, ignore_errors=True)
    os.makedirs(os.path.join(BUILD_DIR, "frames"), exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)

    mp4 = os.path.join(OUT_DIR, args.out + ".mp4")
    zip_path = os.path.join(OUT_DIR, args.out + ".zip")

    ass_path = os.path.join(BUILD_DIR, "credits.ass")
    with open(ass_path, "w", encoding="utf-8") as handle:
        handle.write(build_ass(rows, duration, duration_ms, style_header(args.theme)))

    # 1) 渲染成片：底色 + 字幕 + BGM。cwd 切到 .build，避免 ass 滤镜吃盘符冒号。
    run([ffmpeg, "-y", "-loglevel", "error",
         "-f", "lavfi", "-i", background_filter(args.theme),
         "-i", args.bgm,
         "-vf", "ass=credits.ass",
         "-t", "%.4f" % duration,
         "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-b:a", "192k",
         mp4], cwd=BUILD_DIR)

    # 2) 音轨直接从原始音频转，不从 mp4 里二次抽，避免 AAC 二次压缩。
    audio_wav = os.path.join(BUILD_DIR, "audio.wav")
    run([ffmpeg, "-y", "-loglevel", "error", "-i", args.bgm,
         "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", audio_wav])

    # 3) 抽帧
    frames_dir = os.path.join(BUILD_DIR, "frames")
    run([ffmpeg, "-y", "-loglevel", "error", "-i", mp4,
         "-vf", "fps=%d" % FPS, "-q:v", "3", "-start_number", "1",
         os.path.join(frames_dir, "f%06d.jpg")])

    frames = sorted(n for n in os.listdir(frames_dir) if n.lower().endswith(".jpg"))
    if not frames:
        die("ffmpeg 没有抽出任何帧。")

    # 4) 打包。ZIP_STORED：jpeg 已经压过了，deflate 只会白花时间。
    meta = {"fps": FPS, "frames": len(frames),
            "title": "致谢 · 核弹重制版" if args.theme == "meltdown" else "致谢",
            "audio": "audio.wav"}
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
        for index, name in enumerate(frames, start=1):
            zf.write(os.path.join(frames_dir, name), "f%06d.jpg" % index)
        zf.write(audio_wav, "audio.wav")

    shutil.rmtree(BUILD_DIR, ignore_errors=True)

    for path in (mp4, zip_path):
        print("%s  %.1f MB" % (path, os.path.getsize(path) / 1024.0 / 1024.0))
    print("共 %d 帧 / %.2f 秒" % (len(frames), len(frames) / FPS))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
