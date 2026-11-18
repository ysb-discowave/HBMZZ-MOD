#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给片头 / 片尾压一层英文口播（离线可复现）。

台词全部英文，位置由画面与配乐钉死：片头的 2.25 / 4.00 / 6.50 是逐帧量出来的动作点，
片尾的 0:32.7 / 1:16.4 / 2:43.6 / 3:06 是 88 BPM 的小节线。口播不是另起一条轨，
而是压进现成的配乐里，并对音乐做轻微侧链闪避（-3.5 dB 量级），保证每句都听得清。

语音用 edge-tts 的 en-US-GuyNeural（微软神经网络音色）。第一次要联网，之后走本地缓存
`tools/audio/.voice-cache/`，重跑不再请求网络。

用法：
    python tools/audio/voice_over.py --song both
    python tools/audio/voice_over.py --song intro --out tools/video/intro/out/intro-vo.wav

产物：
    tools/video/intro/out/intro-vo.wav      8.000 s，带口播
    tools/video/credits/out/credits-vo.wav  193.833 s，带口播
    （原始纯音乐 intro.wav / credits.wav 保留不动，便于对比与回退）
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CACHE = os.path.join(HERE, ".voice-cache")
VOICE = "en-US-GuyNeural"

sys.path.insert(0, HERE)
import ost_synth  # noqa: E402  (复用它的滤波 / 混响 / 写盘)

SR = ost_synth.SR

INTRO_IN = os.path.join(ROOT, "tools", "video", "intro", "out", "intro.wav")
INTRO_OUT = os.path.join(ROOT, "tools", "video", "intro", "out", "intro-vo.wav")
CREDITS_IN = os.path.join(ROOT, "tools", "video", "credits", "out", "credits.wav")
CREDITS_OUT = os.path.join(ROOT, "tools", "video", "credits", "out", "credits-vo.wav")

# 每条：id、台词、入场秒、最晚收尾秒（超了就加速到能塞进去，最多 1.35x）
INTRO_LINES = [
    ("blink", "Don't blink.", 0.00, 2.18, 0.62),
    ("break", "Break.", 2.25, 3.95, 0.66),
    ("hold", "Hold.", 4.00, 6.42, 0.66),
    ("lock", "Locked.", 6.45, 7.92, 0.58),
]

CREDITS_LINES = [
    ("lights", "Lights are still on. Don't leave yet.", 0.60, 5.60, 0.52),
    ("hb", "HBMZZ. One last list, read out loud.", 5.90, 11.00, 0.46),
    ("push", "Push it to the top.", 32.727, 37.00, 0.50),
    ("sides", "Left side. Right side. Don't forget.", 76.364, 82.00, 0.46),
    ("names", "Names roll out. The beat doesn't.", 163.636, 168.50, 0.50),
    ("end", "Lights out. Crowd gone. The beat stays.", 186.00, 192.60, 0.46),
]


def find_ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if found:
        return found
    here = os.path.join(ROOT, "tmp", "bin", "ffmpeg.exe")
    if os.path.isfile(here):
        return here
    for path in (r"C:\ffmpeg\bin\ffmpeg.exe",
                 os.path.expanduser(r"~\scoop\apps\ffmpeg\current\bin\ffmpeg.exe")):
        if os.path.isfile(path):
            return path
    raise SystemExit("找不到 ffmpeg：口播要做时长对齐与转码，必须有它。")


def read_wav(path: str) -> tuple[np.ndarray, int]:
    with wave.open(path, "rb") as handle:
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        rate = handle.getframerate()
        raw = handle.readframes(handle.getnframes())
    if width != 2:
        raise SystemExit("%s 不是 16bit PCM" % path)
    data = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    if channels > 1:
        data = data.reshape(-1, channels)
    else:
        data = data[:, None]
    return data, rate


def synth_line(line_id: str, text: str) -> str:
    """取一条干声 wav；缓存键包含音色与文本，改字才会重下。"""
    os.makedirs(CACHE, exist_ok=True)
    key = hashlib.sha1((VOICE + "\x00" + text).encode("utf-8")).hexdigest()[:16]
    wav = os.path.join(CACHE, "%s-%s.wav" % (line_id, key))
    if os.path.isfile(wav) and os.path.getsize(wav) > 2048:
        return wav

    mp3 = os.path.join(CACHE, "%s-%s.mp3" % (line_id, key))
    subprocess.run([sys.executable, "-m", "edge_tts", "--voice", VOICE,
                    "--text", text, "--write-media", mp3],
                   check=True, capture_output=True)
    ffmpeg = find_ffmpeg()
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", mp3,
                    "-ac", "1", "-ar", str(SR), "-c:a", "pcm_s16le", wav],
                   check=True)
    os.remove(mp3)
    return wav


def trim(x: np.ndarray, floor_db: float = -42.0, pad: float = 0.02) -> np.ndarray:
    """掐掉 TTS 自带的静音头尾，留 20 ms 呼吸，避免"嗯"之前先空半秒。"""
    peak = float(np.max(np.abs(x))) or 1.0
    mask = np.abs(x) > peak * (10 ** (floor_db / 20.0))
    if not mask.any():
        return x
    start = int(np.argmax(mask))
    stop = len(x) - int(np.argmax(mask[::-1]))
    pad_n = int(pad * SR)
    return x[max(0, start - pad_n):min(len(x), stop + pad_n)]


def fit(x: np.ndarray, max_seconds: float) -> np.ndarray:
    """太长就加速（atempo，最多 1.35x）：宁可稍快，也不能压到下一句头上。"""
    dur = len(x) / float(SR)
    if dur <= max_seconds:
        return x
    speed = min(1.35, dur / max(0.05, max_seconds))
    tmp_in = os.path.join(CACHE, "_fit_in.wav")
    tmp_out = os.path.join(CACHE, "_fit_out.wav")
    ost_synth.write_mono_wav(tmp_in, x)
    subprocess.run([find_ffmpeg(), "-y", "-loglevel", "error", "-i", tmp_in,
                    "-filter:a", "atempo=%.4f" % speed, "-ac", "1",
                    "-c:a", "pcm_s16le", tmp_out], check=True)
    out, _ = read_wav(tmp_out)
    for path in (tmp_in, tmp_out):
        if os.path.isfile(path):
            os.remove(path)
    return out[:, 0]


def rms_db(x: np.ndarray) -> float:
    return 20.0 * np.log10(max(1e-9, float(np.sqrt(np.mean(x ** 2)))))


def voice_bus(lines, total_seconds: float, music: np.ndarray) -> np.ndarray:
    """把每句摆到时间轴上；每句按「比那一刻的音乐高 3 dB」定电平。

    固定增益不行：片头第 6.5 秒是全片最响的一下，片尾开场几乎是空的，
    同一条轨里乐句电平差能到 15 dB。按局部音乐电平平齐，才不会有的句子被埋掉。
    """
    bus = np.zeros(int(total_seconds * SR) + SR, dtype=np.float64)
    for line_id, text, at, latest_end, gain in lines:
        raw, _ = read_wav(synth_line(line_id, text))
        mono = trim(raw[:, 0])
        mono = fit(mono, latest_end - at)
        mono = ost_synth.fir_highpass(mono, 110.0)
        peak = float(np.max(np.abs(mono))) or 1.0
        mono = mono / peak * 0.92

        start = int(at * SR)
        stop = min(len(bus), start + len(mono))
        under = music[start:stop]
        target = rms_db(under) + 3.0 if len(under) else -18.0
        scale = 10 ** ((target - rms_db(mono)) / 20.0)
        scale = float(np.clip(scale, 0.05, 2.2)) * gain
        bus[start:stop] += mono[:stop - start] * scale
        print("  %-7s %6.2fs → %6.2fs  %5.2fx  %s"
              % (line_id, at, at + len(mono) / float(SR), scale, text))
    return bus[:int(total_seconds * SR)]


def duck(music: np.ndarray, bus: np.ndarray, depth: float = 0.30) -> np.ndarray:
    """侧链：口播一响，音乐让出一点。缓升快放，听不出泵动。"""
    env = np.abs(bus)
    win = int(0.012 * SR)
    env = np.convolve(env, np.ones(win) / win, mode="same")
    peak = float(np.max(env)) or 1.0
    env = np.clip(env / peak, 0.0, 1.0)
    # 单极点平滑：attack 20 ms、release 260 ms
    out = np.zeros_like(env)
    a_att = 1.0 - np.exp(-1.0 / (0.020 * SR))
    a_rel = 1.0 - np.exp(-1.0 / (0.260 * SR))
    level = 0.0
    for i in range(len(env)):
        target = env[i]
        coeff = a_att if target > level else a_rel
        level += coeff * (target - level)
        out[i] = level
    # 15 ms 前瞻：让音乐在字头之前就让开，否则"L"这种爆破音会撞在满电平上
    look = int(0.015 * SR)
    out = np.concatenate([out[look:], np.repeat(out[-1:], look)])
    gain = 1.0 - depth * out
    if music.ndim == 2:
        gain = gain[:, None]
    return music * gain


def place(mono_bus: np.ndarray) -> np.ndarray:
    """口播变立体声：右耳晚 7 ms，低频仍然居中。"""
    wet = ost_synth.reverb(mono_bus, wet=0.16, decay=0.72)
    shift = int(0.007 * SR)
    right = np.concatenate([np.zeros(shift), wet[:len(wet) - shift]])
    low = ost_synth.fir_lowpass(wet, 200.0)
    return np.stack([wet + low * 0.2, right + low * 0.2], axis=1)


def render(song: str, out: str) -> str:
    src = INTRO_IN if song == "intro" else CREDITS_IN
    if not os.path.isfile(src):
        raise SystemExit("缺 %s：先跑 tools/audio/ost_synth.py 生成纯音乐版" % src)
    music, rate = read_wav(src)
    if rate != SR:
        raise SystemExit("%s 采样率不是 %d" % (src, SR))
    total = len(music) / float(SR)
    lines = INTRO_LINES if song == "intro" else CREDITS_LINES

    print("%s  %.3fs  台词 %d 句" % (song, total, len(lines)))
    bus = voice_bus(lines, total, music.mean(axis=1))
    mixed = duck(music[:, :2], bus, depth=0.42) + place(bus)

    # 只做整体衰减，不做波形整形：片头 6.5 s 那一下是设计里的最响点，
    # 削波会把它糊掉，宁可整条低 1~3 dB。
    peak = float(np.max(np.abs(mixed)))
    if peak > 0.96:
        mixed = mixed * (0.96 / peak)
        print("  限幅：整体 ×%.2f（峰值 %.2f → 0.96）" % (0.96 / peak, peak))

    ost_synth.write_wav(out, mixed)
    print("  写出 %s  %.1f MB" % (out, os.path.getsize(out) / 1048576.0))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="给片头 / 片尾压英文口播")
    parser.add_argument("--song", choices=("intro", "credits", "both"), default="both")
    args = parser.parse_args()

    jobs = ([("intro", INTRO_OUT), ("credits", CREDITS_OUT)] if args.song == "both"
            else [("intro", INTRO_OUT)] if args.song == "intro"
            else [("credits", CREDITS_OUT)])
    for song, out in jobs:
        render(song, out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
