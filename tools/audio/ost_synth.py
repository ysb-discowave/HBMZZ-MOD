#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HBMZZ OST 合成器 —— 两条片子的配乐，全部在这里算出来。

为什么不用 DAW / 采样库：这个仓库的要求是「离线、可重复、能进版本库」。
一条 3 分 14 秒的曲子如果靠外部软件，下次改一个和弦就得重开工程；写成代码之后，
`python tools/audio/ost_synth.py` 就是唯一的真相，谁都能重跑出同一个文件。

用法：
    python tools/audio/ost_synth.py --song intro   --out tools/video/intro/out/intro.wav
    python tools/audio/ost_synth.py --song credits --out tools/video/credits/out/credits.wav

两条曲子的长度是被画面钉死的：
    intro    8.000 s = 192 帧 @ 24 fps（SPEC.md 第 1 节）
    credits  193.833 s = 2326 帧 @ 12 fps（make_credits.py 的 FRAME_COUNT）

音乐上的约束也一样来自画面：
    片头  2.25 / 4.00 / 6.50 / 6.80 s 是逐帧量出来的动作点，和弦与织体就换在这几处
    片尾  是匀速滚动的名单，不能有大起大落抢字，所以动态压得比片头平，靠层次推进

这两条曲子不读、不采样、不参照任何现成音频：全部从正弦/锯齿 + 包络 + 混响算出来，
仓库里那几首 mp3 只挂在网站上供试听，与这里无关。

音色全部是加减法合成：
    垫底 pad    三支失谐锯齿 + FIR 低通，慢起慢落
    钟   bell   正弦基频 + 2.01x / 3.02x 泛音，指数衰减（金属感，不是铃铛）
    贝   bass   正弦 + 少量锯齿，短促
    鼓   drums  扫频正弦（底鼓）/ 带通噪声（军鼓、踩镲）
混响是四梳状 + 两全通的 Schroeder，左右用不同的送量与 8ms 延迟做宽度。
"""

from __future__ import annotations

import argparse
import math
import os
import sys
import wave

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:  # 只为了给调用方一句人话，真正计算时仍然必须有 numpy
    np = None
    HAS_NUMPY = False

SR = 44100


# ---------------------------------------------------------------- 基础工具

def hz(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))


def seconds(count: int) -> np.ndarray:
    return np.arange(count, dtype=np.float64) / SR


def fir_lowpass(x: np.ndarray, cutoff_hz: float, taps: int = 63) -> np.ndarray:
    """窗函数 sinc 低通。ttaps 奇数保证线性相位；这里只要音色，不要精度。"""
    cutoff = max(80.0, min(cutoff_hz, SR * 0.45))
    n = np.arange(taps) - (taps - 1) / 2.0
    kernel = np.sinc(2.0 * cutoff / SR * n) * np.hamming(taps)
    kernel /= kernel.sum()
    return np.convolve(x, kernel, mode="same")


def fir_highpass(x: np.ndarray, cutoff_hz: float, taps: int = 63) -> np.ndarray:
    return x - fir_lowpass(x, cutoff_hz, taps)


def adsr(length: int, attack: float, decay: float, sustain: float, release: float) -> np.ndarray:
    """时间单位是秒；attack+decay+release 超出长度时按比例压缩，保证不出现负段。"""
    a = int(max(0.0, attack) * SR)
    d = int(max(0.0, decay) * SR)
    r = int(max(0.0, release) * SR)
    if a + d + r > length:
        scale = length / float(a + d + r + 1)
        a, d, r = int(a * scale), int(d * scale), int(r * scale)
    body = max(0, length - a - d - r)
    env = np.concatenate([
        np.linspace(0.0, 1.0, a, endpoint=False) if a else np.zeros(0),
        np.linspace(1.0, sustain, d, endpoint=False) if d else np.zeros(0),
        np.full(body, sustain),
        np.linspace(sustain, 0.0, r) if r else np.zeros(0),
    ])
    if len(env) < length:
        env = np.pad(env, (0, length - len(env)))
    return env[:length]


def saw(freq: float, length: int, detune_cents: float = 0.0) -> np.ndarray:
    f = freq * (2.0 ** (detune_cents / 1200.0))
    phase = (seconds(length) * f) % 1.0
    return 2.0 * phase - 1.0


def sine(freq: float, length: int, phase: float = 0.0) -> np.ndarray:
    return np.sin(2.0 * np.pi * freq * seconds(length) + phase)


def noise(length: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return rng.standard_normal(length)


# ---------------------------------------------------------------- 音色

def pad_voice(freqs, dur: float, amp: float, cutoff: float, attack: float,
              release: float, seed: int) -> np.ndarray:
    length = int(dur * SR)
    out = np.zeros(length)
    for f in freqs:
        for detune in (-7.0, 0.0, 6.0):
            out += saw(f, length, detune) * (0.33 if detune else 0.44)
    out = fir_lowpass(out / max(1, len(freqs)), cutoff)
    out *= adsr(length, attack, 0.25, 0.85, release)
    return out * amp


def bell_voice(freq: float, dur: float, amp: float, bright: float = 1.0) -> np.ndarray:
    length = int(dur * SR)
    t = seconds(length)
    env = np.exp(-t * (2.6 / max(0.2, dur)))
    body = (np.sin(2 * np.pi * freq * t)
            + 0.42 * bright * np.sin(2 * np.pi * freq * 2.01 * t + 0.3)
            + 0.22 * bright * np.sin(2 * np.pi * freq * 3.02 * t + 1.1)
            + 0.09 * bright * np.sin(2 * np.pi * freq * 4.97 * t + 2.0))
    attack = int(min(0.006 * SR, length))
    if attack > 1:
        env[:attack] *= np.linspace(0.0, 1.0, attack)
    return body * env * amp


def bass_voice(freq: float, dur: float, amp: float, cutoff: float = 420.0) -> np.ndarray:
    length = int(dur * SR)
    wave_ = 0.82 * sine(freq, length) + 0.35 * saw(freq, length, 3.0)
    wave_ = fir_lowpass(wave_, cutoff)
    return wave_ * adsr(length, 0.012, 0.09, 0.62, min(0.18, dur * 0.5)) * amp


def kick_voice(dur: float, amp: float) -> np.ndarray:
    length = int(dur * SR)
    t = seconds(length)
    sweep = 118.0 * np.exp(-t * 26.0) + 42.0
    body = np.sin(2 * np.pi * np.cumsum(sweep) / SR) * np.exp(-t * 11.0)
    click = fir_highpass(noise(length, 7), 1800.0) * np.exp(-t * 240.0) * 0.28
    return (body + click) * amp


def snare_voice(dur: float, amp: float, seed: int = 11) -> np.ndarray:
    length = int(dur * SR)
    t = seconds(length)
    body = np.sin(2 * np.pi * 186.0 * t) * np.exp(-t * 24.0) * 0.5
    rattle = fir_highpass(noise(length, seed), 1300.0) * np.exp(-t * 15.0)
    return (body + rattle) * amp


def hat_voice(dur: float, amp: float, seed: int = 13) -> np.ndarray:
    length = int(dur * SR)
    t = seconds(length)
    return fir_highpass(noise(length, seed), 7200.0) * np.exp(-t * 55.0) * amp


def riser_voice(dur: float, amp: float, seed: int = 17) -> np.ndarray:
    """上扬的噪声：越接近结尾越亮越响，用来把画面推到下一个落点。"""
    length = int(dur * SR)
    t = seconds(length)
    ramp = (t / max(1e-6, dur)) ** 2.2
    air = fir_highpass(noise(length, seed), 900.0)
    tone = sine(220.0 * (1.0 + 3.2 * t / max(1e-6, dur)), length) * 0.18
    return (air * 0.9 + tone) * ramp * amp


def impact_voice(dur: float, amp: float) -> np.ndarray:
    """落点：低频撞 + 一层短促的金属。"""
    length = int(dur * SR)
    t = seconds(length)
    sub = np.sin(2 * np.pi * (58.0 * np.exp(-t * 3.0) + 28.0) * t) * np.exp(-t * 3.4)
    metal = bell_voice(hz(45), dur, 0.28, bright=1.4)
    return (sub + metal) * amp


# ---------------------------------------------------------------- 混音

def reverb(x: np.ndarray, wet: float = 0.28, decay: float = 0.79) -> np.ndarray:
    """Schroeder：四个并联梳状 + 两个串联全通。足够的空间感，够便宜。"""
    out = np.zeros_like(x)
    for delay_ms, gain in ((29.7, decay), (37.1, decay * 0.98),
                           (41.1, decay * 0.96), (43.7, decay * 0.94)):
        d = int(delay_ms * SR / 1000.0)
        buf = np.zeros_like(x)
        for start in range(0, len(x), d):
            end = min(len(x), start + d)
            block = x[start:end]
            # 上一块的尾巴可能有长有短（最后一块不满 d），按当前块长截取
            prev = buf[start - d:start - d + (end - start)] if start >= d else 0.0
            buf[start:end] = block + gain * prev
        out += buf
    out /= 4.0
    for delay_ms, gain in ((5.0, 0.7), (1.7, 0.7)):
        d = int(delay_ms * SR / 1000.0)
        buf = np.zeros_like(out)
        for start in range(0, len(out), d):
            end = min(len(out), start + d)
            block = out[start:end]
            prev = buf[start - d:start - d + (end - start)] if start >= d else 0.0
            buf[start:end] = block + gain * prev
        out = out - gain * buf
    return x * (1.0 - wet) + out * wet


def stereo(mono: np.ndarray, width_ms: float = 8.0) -> np.ndarray:
    """Haas 展宽：右声道晚 8ms，低频仍然居中，不至于听起来跑偏。"""
    shift = int(width_ms * SR / 1000.0)
    right = np.concatenate([np.zeros(shift), mono[:len(mono) - shift]])
    left = mono
    low = fir_lowpass(mono, 220.0)
    left = left + low * 0.25
    right = right + low * 0.25
    return np.stack([left, right], axis=1)


def master(mono: np.ndarray, seconds_total: float, peak: float = 0.92) -> np.ndarray:
    """压到目标峰值，两端各留一点淡入淡出，避免开头结尾的爆音。"""
    want = int(seconds_total * SR)
    if len(mono) < want:
        mono = np.pad(mono, ((0, want - len(mono)), (0, 0)) if mono.ndim == 2
                      else (0, want - len(mono)))
    mono = mono[:want]
    fade = int(0.004 * SR)
    ramp = np.linspace(0.0, 1.0, fade)
    if mono.ndim == 2:
        ramp = ramp[:, None]
    mono[:fade] *= ramp
    mono[-fade:] *= ramp[::-1]
    mono = np.tanh(mono * 1.35) / np.tanh(1.35)
    top = float(np.max(np.abs(mono))) or 1.0
    return mono * (peak / top)


def write_mono_wav(path: str, data: np.ndarray) -> None:
    """给中间步骤用：单声道 16bit。"""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    pcm = np.clip(np.asarray(data, dtype=np.float64), -1.0, 1.0)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes((pcm * 32767.0).astype("<i2").tobytes())


def write_wav(path: str, data: np.ndarray) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(SR)
        handle.writeframes(pcm.tobytes())


# ---------------------------------------------------------------- 片头（8.000 s）

def render_intro() -> np.ndarray:
    """A 小调，120 BPM（一拍 0.5 s，八分 0.25 s），四小节正好 8.000 s。

    落点不是按小节猜的，是拿 mp4 逐帧算出来的（帧间差峰值）：
        2.25 s  已装好的小标开始散开，碎片向外飘
        4.00 s  碎片开始合拢
        6.50 s  部件咬合、亮一下，这一帧是全片最亮的一下
        6.80 s  静止的最终标，之后不再动
    所以：破 2.25、合 4.00、锁 6.50 —— 6.50 是重拍 13，不用凑。
    收尾不再给鼓，只让和弦与钟自己衰减，8.000 s 正好落到静默。
    """
    total = 8.0
    length = int(total * SR)
    mix = np.zeros(length)

    def put(wave_: np.ndarray, at: float, gain: float = 1.0) -> None:
        start = int(at * SR)
        if start >= length:
            return
        stop = min(length, start + len(wave_))
        mix[start:stop] += wave_[:stop - start] * gain

    # 铺底：Am -> F -> Dm -> A。前两小节是「静止」，第三小节跟着碎片收紧，
    # 6.5 锁上时换成大三和弦，亮一下但不甜。
    put(pad_voice([hz(57), hz(60), hz(64)], 2.55, 0.20, 820.0, 0.70, 0.55, 1), 0.0)
    put(pad_voice([hz(53), hz(57), hz(60)], 2.10, 0.21, 1150.0, 0.18, 0.55, 2), 2.25)
    put(pad_voice([hz(50), hz(53), hz(57)], 2.80, 0.22, 1500.0, 0.12, 0.70, 3), 4.00)
    put(pad_voice([hz(57), hz(61), hz(64), hz(69)], 1.90, 0.24, 2100.0, 0.04, 0.90, 4), 6.50)

    # 低频芯：0~2.25 只有一条长音，散开之后才有脉动，锁上后回到长音
    put(bass_voice(hz(33), 2.45, 0.34, 300.0), 0.0)
    step = 0.25
    t = 2.25
    while t < 6.50 - 1e-6:
        root = 41 if t < 4.00 else (38 if t < 5.00 else 33)
        put(bass_voice(hz(root), 0.22, 0.25, 380.0), t)
        t += step
    put(bass_voice(hz(33), 1.45, 0.30, 280.0), 6.50)

    # 钟：开场一颗定调；散开时一串高音（碎片），合拢时一颗低音落回来，
    # 锁上时给最亮的一颗，6.8 再补一颗弱的高八度让它有余音。
    put(bell_voice(hz(81), 1.7, 0.13), 0.05)
    put(bell_voice(hz(88), 1.1, 0.07), 0.62)
    for offset, note, gain in ((0.00, 81, 0.10), (0.12, 88, 0.08), (0.26, 84, 0.07),
                               (0.38, 91, 0.05)):
        put(bell_voice(hz(note), 1.1, gain), 2.25 + offset)
    put(bell_voice(hz(72), 1.5, 0.11), 4.00)
    put(bell_voice(hz(76), 1.0, 0.06), 4.50)
    put(bell_voice(hz(88), 2.0, 0.20), 6.50)
    put(bell_voice(hz(76), 1.4, 0.10), 6.80)

    # 鼓：0~2.25 只给两下心跳（小标还在，别催）；散开起进四拍，
    # 6.5 之后就停手，把最后一拍留给落点本身。
    put(kick_voice(0.55, 0.23), 0.0)
    put(kick_voice(0.55, 0.15), 1.12)
    beat = 0.5
    t = 2.25
    while t < 6.50 - 1e-6:
        put(kick_voice(0.50, 0.32 if abs((t / beat) % 1.0) < 1e-6 else 0.26), t)
        put(hat_voice(0.09, 0.085, seed=31 + int(t * 100)), t + step)
        t += beat

    # 推进与落点：5.0 起上行，正好在 6.50 收；落点之后不再加任何打击。
    put(riser_voice(1.50, 0.20), 5.00)
    put(impact_voice(1.50, 0.80), 6.50)

    wet = reverb(mix, wet=0.30, decay=0.80)
    return master(stereo(wet), total)


# ---------------------------------------------------------------- 片尾（193.833 s）

# 和弦：D 小调走 i - VI - III - VII，是这类工业感片子最稳的底色。
# 每个音是 MIDI 音高；第一小节是小节内四个重音位置的和弦，这里按小节给。
CREDITS_CHORDS = {
    "Dm": [50, 53, 57],
    "Bb": [46, 50, 53],
    "F":  [53, 57, 60],
    "C":  [48, 52, 55],
    "Gm": [43, 50, 55],
    "A":  [45, 52, 57],
}
CREDITS_SECONDS = 2326 / 12.0   # 与 make_credits.py 的 FRAME_COUNT / FPS 一致
CREDITS_PROGRESSION = ["Dm", "Dm", "Bb", "F", "C", "Dm", "Bb", "C"]


def credits_chord_at(bar: int) -> str:
    return CREDITS_PROGRESSION[bar % len(CREDITS_PROGRESSION)]


def render_credits(bars: int = 71, bpm: float = 88.0) -> np.ndarray:
    """名单是匀速滚动的，所以这条曲子只在层次上推进，不抢字。

    71 小节 @ 88 BPM = 193.64 s，比名单的 2326 帧（193.833 s）短 0.2 s。
    这 0.2 s 不是凑数：留出来正好让最后的和弦衰减完，名单滑到顶时画面是静下来的，
    而不是"音乐先没了、字还在走"。
    """
    beat = 60.0 / bpm
    bar_len = beat * 4.0
    total = max(bars * bar_len, CREDITS_SECONDS)
    length = int(total * SR)
    mix = np.zeros(length)

    def put(wave_: np.ndarray, at: float, gain: float = 1.0) -> None:
        start = int(at * SR)
        if start >= length:
            return
        stop = min(length, start + len(wave_))
        mix[start:stop] += wave_[:stop - start] * gain

    def section(start_bar: int) -> str:
        if start_bar < 4:
            return "intro"
        if start_bar < 12:
            return "lead"
        if start_bar < 28:
            return "a"
        if start_bar < 44:
            return "b"
        if start_bar < 56:
            return "a2"
        if start_bar < 60:
            return "bridge"
        return "out"

    # 旋律动机：D 小调五声（D F G A C）。四个小节一句，前后句是问与答。
    motif_a = [(0.0, 1.0, 69), (1.0, 0.5, 72), (1.5, 0.5, 74), (2.0, 1.5, 77), (3.5, 0.5, 74)]
    motif_b = [(0.0, 1.0, 72), (1.0, 1.0, 69), (2.0, 1.5, 67), (3.5, 0.5, 65)]
    motif_c = [(0.0, 0.5, 74), (0.5, 0.5, 72), (1.0, 1.0, 69), (2.0, 2.0, 65)]
    motif_d = [(0.0, 1.0, 77), (1.0, 0.5, 76), (1.5, 0.5, 74), (2.0, 2.0, 69)]
    motifs = [motif_a, motif_b, motif_c, motif_d]

    for bar in range(bars):
        at_bar = bar * bar_len
        chord = credits_chord_at(bar)
        tones = CREDITS_CHORDS[chord]
        phase = section(bar)

        # 垫底：每两小节换一次，避免每次小节线都重新起振
        if bar % 2 == 0:
            gain = {"intro": 0.20, "lead": 0.20, "a": 0.24, "b": 0.26,
                    "a2": 0.24, "bridge": 0.21, "out": 0.21}[phase]
            cutoff = {"intro": 780.0, "lead": 950.0, "a": 1400.0, "b": 1650.0,
                      "a2": 1450.0, "bridge": 1050.0, "out": 760.0}[phase]
            put(pad_voice([hz(n) for n in tones], bar_len * 2 + 0.6, gain, cutoff,
                          0.55, 1.1, 30 + bar), at_bar)

        # 低音：前半段只给根音长音，进入 A 段后改成每拍一动
        root = tones[0] - 12
        if phase in ("a", "b", "a2"):
            for b in range(4):
                put(bass_voice(hz(root + (7 if b == 3 and phase == "b" else 0)),
                               0.55, 0.30, 360.0), at_bar + b * beat)
        elif phase == "out":
            if bar % 2 == 0:
                put(bass_voice(hz(root), bar_len * 1.8, 0.26, 300.0), at_bar)
        else:
            put(bass_voice(hz(root), bar_len * 0.95, 0.20, 320.0), at_bar)

        # 鼓：只在 A / B 段，且越往后越密；名单区（bridge）退到只有心跳
        if phase in ("a", "a2", "b"):
            for b in range(4):
                put(kick_voice(0.5, 0.30 if b % 2 == 0 else 0.24), at_bar + b * beat)
            if phase == "b":
                put(snare_voice(0.35, 0.22, seed=40 + bar), at_bar + 2 * beat)
            for b in range(0, 4):
                put(hat_voice(0.09, 0.09, seed=60 + bar * 4 + b), at_bar + b * beat + beat / 2)
        elif phase in ("lead", "bridge"):
            if bar % 2 == 0:
                put(kick_voice(0.5, 0.18), at_bar)

        # 旋律：A 段给前两句，B 段给后两句，A' 段重复并加一层高八度的弱声部
        if phase in ("a", "a2", "b", "out"):
            motif = motifs[bar % len(motifs)]
            for offset, dur, note in motif:
                gain = 0.20 if phase != "out" else 0.16
                put(bell_voice(hz(note), max(0.35, dur * beat * 1.6), gain), at_bar + offset * beat)
                if phase == "a2" and offset == 0.0:
                    put(bell_voice(hz(note + 12), 1.2, 0.06), at_bar + offset * beat)
        if phase == "lead" and bar % 4 == 2:
            put(bell_voice(hz(tones[2] + 12), 1.6, 0.07), at_bar)

        # 桥段不能空着：名单还在滚，音乐只是退后一步。每小节两颗长钟，
        # 取和弦的五音与九音，间隔一拍半，像有人在远处跟着念。
        if phase == "bridge":
            for offset, note, gain in ((0.0, tones[2] + 12, 0.10), (1.5, tones[1] + 12, 0.075)):
                put(bell_voice(hz(note), 2.6, gain), at_bar + offset * beat)

        # 换段：给一个低频撞，让耳朵知道翻页了
        if bar in (4, 12, 28, 44, 56, 60):
            put(impact_voice(1.4, 0.30 if bar != 60 else 0.38), at_bar)

    # 收尾：最后两小节把和弦散开，留一条长音落到静默
    end_bar = bars - 2
    put(pad_voice([hz(50), hz(57), hz(62), hz(65)], bar_len * 2.2, 0.22, 900.0,
                  0.4, 1.6, 77), end_bar * bar_len)

    wet = reverb(mix, wet=0.26, decay=0.78)
    return master(stereo(wet), total)


# ---------------------------------------------------------------- 入口

def main() -> int:
    parser = argparse.ArgumentParser(description="生成 HBMZZ 的片头 / 片尾配乐")
    parser.add_argument("--song", choices=("intro", "credits"), required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--bars", type=int, default=71, help="片尾小节数，默认 71（193.6 s）")
    args = parser.parse_args()

    data = render_intro() if args.song == "intro" else render_credits(args.bars)
    write_wav(args.out, data)
    dur = data.shape[0] / float(SR)
    peak = float(np.max(np.abs(data)))
    rms = float(np.sqrt(np.mean(data ** 2)))
    print("%s  %s" % (args.song, os.path.abspath(args.out)))
    print("时长 %.3f s / %.1f dBFS 峰值 / RMS %.1f dBFS"
          % (dur, 20 * math.log10(max(peak, 1e-9)), 20 * math.log10(max(rms, 1e-9))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
