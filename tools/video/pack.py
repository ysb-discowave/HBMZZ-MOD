#!/usr/bin/env python3
"""把一段视频打成 HBMZZ 视频广播器能播的包。

用法一（有 ffmpeg）：
    python tools/video/pack.py alarm.mp4 alarm --fps 12

用法二（已经有一堆图，比如手画的几帧）：
    python tools/video/pack.py --frames my_frames alarm --fps 4

产物是一个 <名字>.zip，扔进客户端 config/hbmzz/videos/ 就能在广播器里选到。
包内结构（VideoClip.java 读的就是这个）：

    meta.json    {"fps":12,"frames":240,"title":"alarm","audio":"audio.wav"}
    f000001.jpg
    f000002.jpg
    audio.wav    可选

为什么是图片序列而不是 mp4：1.12.2 的客户端只有一个 JRE，没有 H.264 解码器；
塞原生解码库意味着每个平台各带一份二进制。图片序列 + ImageIO 什么平台都能解。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
IMAGE_EXT = (".jpg", ".jpeg", ".png")


def die(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print("错误: " + message, file=sys.stderr)
    raise SystemExit(1)


def find_ffmpeg() -> str | None:
    found = shutil.which("ffmpeg")
    if found:
        return found
    candidates = [
        r"C:\ffmpeg\bin\ffmpeg.exe",
        r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
        os.path.expanduser(r"~\scoop\apps\ffmpeg\current\bin\ffmpeg.exe"),
        "/usr/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/opt/homebrew/bin/ffmpeg",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


def default_out() -> str:
    # 开发环境里 run/ 就是 .minecraft；否则退回仓库根的 config/。
    run_config = os.path.join("run", "config", "hbmzz", "videos")
    if os.path.isdir(os.path.join("run", "config")):
        return run_config
    return os.path.join("config", "hbmzz", "videos")


def extract(video: str, fps: int, width: int, workdir: str) -> tuple[int, bool]:
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        die("找不到 ffmpeg。请先安装（winget install Gyan.FFmpeg 或 scoop install ffmpeg），"
            "或者用 --frames 指定一个现成的图片目录。")

    frames_dir = os.path.join(workdir, "frames")
    os.makedirs(frames_dir, exist_ok=True)

    vf = f"fps={fps},scale={width}:-2:flags=lanczos"
    cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", video,
           "-vf", vf, "-q:v", "3", "-start_number", "1",
           os.path.join(frames_dir, "f%06d.jpg")]
    print("转帧: " + " ".join(cmd))
    if subprocess.call(cmd) != 0:
        die("ffmpeg 转帧失败，看看输入文件是不是真的能解码。")

    count = len([n for n in os.listdir(frames_dir) if n.lower().endswith(IMAGE_EXT)])
    if count == 0:
        die("ffmpeg 没产出任何帧。")

    has_audio = False
    audio_cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", video,
                 "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le",
                 os.path.join(workdir, "audio.wav")]
    if subprocess.call(audio_cmd) == 0 and os.path.getsize(os.path.join(workdir, "audio.wav")) > 1024:
        has_audio = True
    else:
        print("提示: 这段视频没有音轨（或抽不出来），包将静音播放。")

    return count, has_audio


def collect_frames(source: str) -> list[str]:
    files = sorted(
        os.path.join(source, n) for n in os.listdir(source) if n.lower().endswith(IMAGE_EXT)
    )
    if not files:
        die(f"{source} 里没有 png/jpg。")
    return files


def build(name: str, out_dir: str, fps: int, title: str, frames: list[str],
          audio: str | None) -> str:
    os.makedirs(out_dir, exist_ok=True)
    target = os.path.join(out_dir, name + ".zip")
    meta = {"fps": fps, "frames": len(frames), "title": title}
    if audio:
        meta["audio"] = "audio.wav"
    # ZIP_STORED：jpeg 已经压过了，deflate 只会白花时间。
    with zipfile.ZipFile(target, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
        for index, path in enumerate(frames, start=1):
            zf.write(path, "f%06d%s" % (index, os.path.splitext(path)[1].lower()))
        if audio:
            zf.write(audio, "audio.wav")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description="打包 HBMZZ 视频广播器用的视频包")
    parser.add_argument("source", help="输入视频文件，或 --frames 时的图片目录")
    parser.add_argument("name", help="包名（字母数字和下划线/点/短横），游戏里选的就是它")
    parser.add_argument("--fps", type=int, default=12, help="帧率，默认 12")
    parser.add_argument("--width", type=int, default=854, help="缩放宽度，默认 854（高按比例）")
    parser.add_argument("--title", default=None, help="播放时左下角显示的名字，默认同包名")
    parser.add_argument("--frames", action="store_true", help="source 是图片目录而不是视频")
    parser.add_argument("--out", default=None, help="输出目录，默认 run/config/hbmzz/videos")
    args = parser.parse_args()

    if not NAME_RE.match(args.name):
        die("包名只能以字母数字开头，只含字母数字和 _ . -")
    if not 1 <= args.fps <= 60:
        die("fps 要在 1..60 之间")
    if not os.path.exists(args.source):
        die(f"找不到 {args.source}")

    out_dir = args.out or default_out()
    title = args.title or args.name

    if args.frames:
        frames = collect_frames(args.source)
        target = build(args.name, out_dir, args.fps, title, frames, None)
    else:
        workdir = tempfile.mkdtemp(prefix="hbmzz-video-")
        try:
            count, has_audio = extract(args.source, args.fps, args.width, workdir)
            frames_dir = os.path.join(workdir, "frames")
            frames = [os.path.join(frames_dir, n) for n in sorted(os.listdir(frames_dir))
                      if n.lower().endswith(IMAGE_EXT)]
            audio = os.path.join(workdir, "audio.wav") if has_audio else None
            target = build(args.name, out_dir, args.fps, title, frames, audio)
            print(f"共 {count} 帧，{count / args.fps:.1f} 秒")
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    size = os.path.getsize(target) / 1024.0 / 1024.0
    print(f"完成: {target}  ({size:.1f} MB)")
    print("这个文件要放到【每一台需要观看的客户端】的 config/hbmzz/videos/ 下，"
          "服务端只负责触发，不需要它。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
