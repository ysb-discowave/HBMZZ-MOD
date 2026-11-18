#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 HBMZZ 片头 mp4 打成视频广播器可播放的 zip（离线脚本）。

用法：
    python tools/video/intro/package_intro.py
    python tools/video/intro/package_intro.py --mp4 path/to/intro.mp4 --out path/to/out.zip
    python tools/video/intro/package_intro.py --audio tools/video/intro/out/intro.wav

产物（默认 tools/video/intro/out/hbmzz-intro.zip）包内结构，
即 VideoClip.java 读取的格式：

    meta.json    {"fps":24,"frames":192,"title":"HBMZZ 片头","audio":"audio.wav","loop":false}
    f000001.jpg
    ...
    f000192.jpg
    audio.wav    可选：--audio 给了才有

约定见同目录 SPEC.md：960x540 / 24fps / 192 帧 / 8 秒。
默认出带音版：音轨由 tools/audio/ost_synth.py 合成（8.000 s，逐帧对齐 2.25/4.00/6.50 三处
画面动作点），不读取任何现成音频；同时另存一份 hbmzz-intro-voiced.mp4 方便离线看。
要回到 SPEC 的原静音交付，加 --silent：meta.json 里就没有 audio 字段。
带音版把 loop 写成 false —— 片头是一次性的开场，循环重播只会把开场白说第二遍。
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
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DEFAULT_MP4 = os.path.join(HERE, "out", "hbmzz-intro.mp4")
DEFAULT_AUDIO = os.path.join(HERE, "out", "intro-vo.wav")   # 带英文口播的成品
DEFAULT_OUT = os.path.join(HERE, "out", "hbmzz-intro.zip")
VOICED_MP4 = os.path.join(HERE, "out", "hbmzz-intro-voiced.mp4")
AUDIO_TOOL = os.path.join(ROOT, "tools", "audio")
DURATION = FRAME_COUNT / float(FPS)


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


def to_wav(src: str, workdir: str) -> str:
    """把任意音频转成播放器认的 44.1k / 16bit / 立体声 WAV。"""
    out = os.path.join(workdir, "audio.wav")
    cmd = [find_ffmpeg(), "-y", "-loglevel", "error", "-i", src,
           "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", out]
    print("音轨: " + " ".join(cmd))
    if subprocess.call(cmd) != 0:
        die("音轨转换失败，确认 --audio 指向的文件真的能解码。")
    return out


def ensure_audio(path: str) -> str:
    """片头配乐由 tools/audio/ost_synth.py 合成，缺失时现算，不引用任何现成音频。"""
    if os.path.isfile(path) and os.path.getsize(path) > 1024:
        return path
    if os.path.basename(path) == "intro-vo.wav":
        raise SystemExit("缺 intro-vo.wav：先跑 python tools/audio/voice_over.py --song intro")
    sys.path.insert(0, AUDIO_TOOL)
    try:
        import ost_synth
    except ImportError:
        die("找不到 tools/audio/ost_synth.py，无法生成配乐")
    if not ost_synth.HAS_NUMPY:
        die("生成配乐需要 numpy：python -m pip install numpy")
    print("合成配乐: " + path)
    ost_synth.write_wav(path, ost_synth.render_intro())
    return path


def wav_seconds(path: str) -> float:
    import wave
    with wave.open(path, "rb") as handle:
        return handle.getnframes() / float(handle.getframerate())


def prepare_audio(src: str, workdir: str) -> str:
    """已经是 44.1k/16bit/立体声的 wav 就直接用，其它格式交给 ffmpeg 转。"""
    if src.lower().endswith(".wav"):
        import wave
        with wave.open(src, "rb") as handle:
            ok = (handle.getnchannels() == 2 and handle.getsampwidth() == 2
                  and handle.getframerate() == 44100)
        if ok:
            return src
    return to_wav(src, workdir)


def mux_video(mp4: str, audio: str, out: str) -> str:
    """成片也配同一版音乐，方便离线看；视频流不重编码。"""
    cmd = [find_ffmpeg(), "-y", "-loglevel", "error", "-i", mp4, "-i", audio,
           "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
           "-c:a", "aac", "-b:a", "192k", "-shortest", out]
    print("成片: " + " ".join(cmd))
    if subprocess.call(cmd) != 0:
        die("合成带音成片失败")
    return out


def build_zip(frames: list[str], out: str, audio_wav: str | None = None) -> str:
    """先写临时文件、成功后再原子替换，避免失败时留下半成品。"""
    out_dir = os.path.dirname(os.path.abspath(out))
    os.makedirs(out_dir, exist_ok=True)
    tmp = out + ".tmp"
    meta = {"fps": FPS, "frames": FRAME_COUNT, "title": TITLE}
    if audio_wav:
        meta["audio"] = "audio.wav"
        meta["loop"] = False
    try:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zf:
            zf.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
            for index, path in enumerate(frames, start=1):
                zf.write(path, "f%06d.jpg" % index)
            if audio_wav:
                zf.write(audio_wav, "audio.wav")
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
    parser.add_argument("--audio", default=DEFAULT_AUDIO,
                        help="配乐 wav，默认 tools/video/intro/out/intro-vo.wav（缺失时自动合成纯音乐版）")
    parser.add_argument("--silent", action="store_true",
                        help="出静音版（SPEC 的旧交付形态），不带 audio.wav")
    args = parser.parse_args()

    # 在动任何输出之前先确认输入存在，缺片头时不生成半成品。
    if not os.path.isfile(args.mp4):
        die("找不到片头 mp4: %s\n片头尚未生成时不会产出任何 zip；"
            "请先生成 mp4，或用 --mp4 显式指定一个已存在的视频。" % args.mp4)

    workdir = tempfile.mkdtemp(prefix="hbmzz-intro-")
    try:
        frames = extract_frames(args.mp4, workdir)
        audio_wav = None
        if not args.silent:
            audio_wav = prepare_audio(ensure_audio(args.audio), workdir)
            length = wav_seconds(audio_wav)
            if abs(length - DURATION) > 0.02:
                die("配乐长 %.3f s，片头是 %.3f s；差得太多会有一头是静音。"
                    % (length, DURATION))
            mux_video(args.mp4, audio_wav, VOICED_MP4)
        target = build_zip(frames, args.out, audio_wav)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    size = os.path.getsize(target) / 1024.0 / 1024.0
    print("完成: %s  (%d 帧 / %d fps / %s / %.1f MB)"
          % (target, FRAME_COUNT, FPS,
             "带音轨 %.3f s" % wav_seconds(audio_wav) if audio_wav else "静音", size))
    print("把这个 zip 放到【每一台需要观看的客户端】的 config/hbmzz/videos/ 下，"
          "服务端只负责触发。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
