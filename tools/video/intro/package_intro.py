#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 HBMZZ 片头 mp4 打成视频广播器可播放的 zip（离线脚本）。

用法：
    python tools/video/intro/package_intro.py
    python tools/video/intro/package_intro.py --mp4 path/to/intro.mp4 --out path/to/out.zip

产物（默认 tools/video/intro/out/hbmzz-intro.zip）包内结构，
即 VideoClip.java 读取的格式：

    meta.json    {"fps":24,"frames":192,"title":"HBMZZ 片头"}
    f000001.jpg
    f000002.jpg
    ...
    f000192.jpg

约定见同目录 SPEC.md：960x540 / 24fps / 192 帧 / 8 秒 / 无音轨。
片头默认静音，不生成 audio.wav，meta.json 里也没有 audio 字段。
JPEG 已经压过，所以用 ZIP_STORED（deflate 无益）。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

# ---------------------------------------------------------------- 固定的技术参数
FPS = 24                       # 恒定 24 fps
FRAME_COUNT = 192              # 8.000 s * 24
TITLE = "HBMZZ 片头"
WIDTH = 960                    # 与片尾及播放器 videoMaxWidth 一致，高按比例 -> 540

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MP4 = os.path.join(HERE, "out", "hbmzz-intro.mp4")
DEFAULT_OUT = os.path.join(HERE, "out", "hbmzz-intro.zip")


def die(message: str) -> None:
    print("错误: " + message, file=sys.stderr)
    raise SystemExit(1)


def find_ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if found:
        return found
    candidates = [
        r"C:\ffmpeg\bin\ffmpeg.exe",
        r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
        os.path.expanduser(r"~\scoop\apps\ffmpeg\current\bin\ffmpeg.exe"),
        "/usr/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    die("找不到 ffmpeg。请先安装（winget install Gyan.FFmpeg 或 scoop install ffmpeg）。")


def extract_frames(mp4: str, workdir: str) -> list[str]:
    """按 24fps 抽帧，最多取 192 张，返回按文件名排序的 jpg 路径。"""
    frames_dir = os.path.join(workdir, "frames")
    os.makedirs(frames_dir, exist_ok=True)
    ffmpeg = find_ffmpeg()
    vf = "fps=%d,scale=%d:-2:flags=lanczos" % (FPS, WIDTH)
    cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", mp4,
           "-vf", vf, "-q:v", "3", "-start_number", "1",
           "-frames:v", str(FRAME_COUNT),
           os.path.join(frames_dir, "f%06d.jpg")]
    print("抽帧: " + " ".join(cmd))
    if subprocess.call(cmd) != 0:
        die("ffmpeg 抽帧失败，确认输入 mp4 真的能解码。")

    frames = sorted(
        os.path.join(frames_dir, name)
        for name in os.listdir(frames_dir) if name.lower().endswith(".jpg")
    )
    if len(frames) < FRAME_COUNT:
        die("只抽到 %d 帧，片头需要 %d 帧（24fps x 8 秒）。请检查 mp4 时长或帧率。"
            % (len(frames), FRAME_COUNT))
    if len(frames) > FRAME_COUNT:
        print("提示: 输入比 %d 帧长，只取前 %d 帧。" % (FRAME_COUNT, FRAME_COUNT))
        frames = frames[:FRAME_COUNT]
    return frames


def build_zip(frames: list[str], out: str) -> str:
    """先写临时文件、成功后再原子替换，避免失败时留下半成品。"""
    out_dir = os.path.dirname(os.path.abspath(out))
    os.makedirs(out_dir, exist_ok=True)
    tmp = out + ".tmp"
    meta = {"fps": FPS, "frames": FRAME_COUNT, "title": TITLE}  # 无 audio 字段
    try:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zf:
            zf.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
            for index, path in enumerate(frames, start=1):
                zf.write(path, "f%06d.jpg" % index)
        os.replace(tmp, out)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="把 HBMZZ 片头 mp4 打成视频广播器用的 zip")
    parser.add_argument("--mp4", default=DEFAULT_MP4,
                        help="输入片头 mp4，默认 tools/video/intro/out/hbmzz-intro.mp4")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="输出 zip 路径，默认 tools/video/intro/out/hbmzz-intro.zip")
    args = parser.parse_args()

    # 在动任何输出之前先确认输入存在，缺片头时不生成半成品。
    if not os.path.isfile(args.mp4):
        die("找不到片头 mp4: %s\n片头尚未生成时不会产出任何 zip；"
            "请先生成 mp4，或用 --mp4 显式指定一个已存在的视频。" % args.mp4)

    workdir = tempfile.mkdtemp(prefix="hbmzz-intro-")
    try:
        frames = extract_frames(args.mp4, workdir)
        target = build_zip(frames, args.out)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    size = os.path.getsize(target) / 1024.0 / 1024.0
    print("完成: %s  (%d 帧 / %d fps / %.1f MB)" % (target, FRAME_COUNT, FPS, size))
    print("把这个 zip 放到【每一台需要观看的客户端】的 config/hbmzz/videos/ 下，"
          "服务端只负责触发。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
