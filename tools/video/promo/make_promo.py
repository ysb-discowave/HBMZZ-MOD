#!/usr/bin/env python3
"""HBMZZ 宣传片合成器。

把 INTRO + 13 段正文 + CREDITS 拼成一条，处理片头淡出/片尾淡入、片尾帧率上采样、
音轨（占位静音或正式 BGM），并输出卡点清单。

    # 无 BGM（当前）：正文 8–244 s 为静音占位，244 s 起接片尾自带音乐
    py -3.12 tools/video/promo/make_promo.py

    # BGM 到位后
    py -3.12 tools/video/promo/make_promo.py --bgm bgm.wav --bpm 128 --offset 0.0

    # 只拼片头+片尾（不依赖正文段，用于验证管线）
    py -3.12 tools/video/promo/make_promo.py --only-fixed

设计约束（见 CONTRACT.md §4）：
- 全片零重叠拼接，所有转场在段内实现，所以总帧数严格等于各段之和。
- 只有 INTRO 的淡出与 CREDITS 的淡入由本脚本做。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))          # tools/video/promo
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))  # repo root
TIMING = os.path.join(HERE, "timing.json")
OUT_DIR = os.path.join(HERE, "out")
BUILD_DIR = os.path.join(OUT_DIR, "build")


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def die(msg, code=1):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(code)


def ffprobe_frames(path):
    """精确帧数。优先 nb_read_frames（-count_frames），失败退到 nb_frames。"""
    r = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
             "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", path])
    v = r.stdout.strip().strip(",")
    if v.isdigit():
        return int(v), True
    r = run(["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=nb_frames", "-of", "csv=p=0", path])
    v = r.stdout.strip().strip(",")
    return (int(v) if v.isdigit() else None), False


def ffprobe_meta(path):
    r = run(["ffprobe", "-v", "error", "-show_streams", "-show_format",
             "-of", "json", path])
    if r.returncode != 0:
        return None
    return json.loads(r.stdout)


# ---------------------------------------------------------------- 前置检查

def collect_segments(t, only_fixed=False):
    segs = t["segments"]
    if only_fixed:
        segs = [s for s in segs if s["kind"] == "fixed"]
    fps = t["canvas"]["fps"]
    missing, wrong = [], []
    for s in segs:
        p = os.path.join(ROOT, s["source"])
        if not os.path.isfile(p):
            missing.append((s["id"], s["source"]))
            continue
        # fixed 段可能是别的帧率（片尾是 12 fps），时间轴里声明的是**上采样后**帧数，
        # 所以要拿 source_fps 折回源文件的真实帧数再比。
        want = s["frames"]
        if s.get("source_fps"):
            want = int(round(s["frames"] * s["source_fps"] / float(fps)))
        n, exact = ffprobe_frames(p)
        if n != want:
            wrong.append((s["id"], n, want, exact))
    if missing:
        print("missing segment files:", file=sys.stderr)
        for i, p in missing:
            print(f"  {i}  {p}", file=sys.stderr)
        print("\nrun the shot renderers first, or pass --only-fixed", file=sys.stderr)
        return None
    if wrong:
        print("frame count mismatch:", file=sys.stderr)
        for i, got, want, exact in wrong:
            flag = "" if exact else " (nb_frames, not exact)"
            print(f"  {i}  got {got}  want {want}{flag}", file=sys.stderr)
        print("\nre-render those segments; --allow-mismatch to override", file=sys.stderr)
        if not ARGS.allow_mismatch:
            return None
    return segs


# ---------------------------------------------------------------- 滤镜构建

def build_filter(segs, total_frames, fps):
    """每段归一化 + INTRO 淡出 / CREDITS 淡入 + concat。返回 (filter 文本, 末标签)。"""
    lines = []
    for idx, s in enumerate(segs):
        chains = [f"fps={fps}", "setpts=PTS-STARTPTS",
                  "scale=960:540:flags=lanczos", "format=yuv420p"]
        head = s.get("head_fade") or 0
        tail = s.get("tail_fade") or 0
        # 只对 fixed 段做 fade：generated 段的首尾淡变由段渲染器自己画进帧里，
        # 这里再叠一层会变成"双淡"，画面比设计更暗。
        if s["kind"] == "fixed":
            dur = s["frames"] / float(fps)
            if tail:
                d = tail / float(fps)
                chains.append(f"fade=t=out:st={max(0.0, dur - d):.4f}:d={d:.4f}")
            if head:
                d = head / float(fps)
                chains.append(f"fade=t=in:st=0:d={d:.4f}")
        lines.append(f"[{idx}:v]" + ",".join(chains) + f"[v{idx}]")
    concat_in = "".join(f"[v{i}]" for i in range(len(segs)))
    lines.append(f"{concat_in}concat=n={len(segs)}:v=1:a=0[vout]")
    return ";\n".join(lines)


def build_audio(segs, t, arg, n_video_inputs):
    """返回 (filter 片段, 音频输入额外参数, 输入数量增量, 末标签)。

    音轨按"片头垫静音 → 正文（静音或 BGM）→ 片尾自带音乐"三段拼。
    正文长度只按实际存在的 generated 段算，所以 --only-fixed 时中间段长度为 0，
    否则会多垫 236 s 静音、把片尾音乐挤到视频外面去。
    """
    fps = t["canvas"]["fps"]
    sr = t["audio"]["sample_rate"]
    credits_seg = [s for s in segs if s["id"] == "CREDITS"]
    has_credits = bool(credits_seg)

    intro = [s for s in segs if s["id"] == "INTRO"]
    pre = (intro[0]["duration_ms"] / 1000.0) if intro else 0.0
    gen = [s for s in segs if s["kind"] == "generated"]
    body_len = sum(s["duration_ms"] for s in gen) / 1000.0

    extra_inputs = []
    parts = []
    labels = []
    idx = n_video_inputs
    duck = arg.duck_credits

    # BGM 实际时长：够长（>= 片头+正文-0.5s）就进入全覆盖模式——
    # 作曲者交付的主题曲长度 == 片头 8s + 正文 236s，曲子的动态结构
    # （116s 低谷 / 152-200s 高峰 / 232s 后收束）逐段对齐分镜，
    # 所以从视频 0s 一直铺到正文结束，而不是只铺正文。
    bgm_full = False
    bgm_dur = 0.0
    if arg.bgm:
        m = ffprobe_meta(arg.bgm)
        bgm_dur = float(m["format"]["duration"])
        bgm_full = pre > 0.001 and bgm_dur >= pre + body_len - 0.5

    # 1) 片头垫静音（片头本身无音轨；BGM 全覆盖模式已含片头，不再垫）
    if arg.bgm and not bgm_full:
        pre += arg.offset
    if pre > 0.001 and not bgm_full:
        extra_inputs.append(["-f", "lavfi", "-t", f"{pre:.4f}",
                             "-i", f"anullsrc=r={sr}:cl=stereo"])
        parts.append(f"[{idx}:a]aresample={sr},"
                     f"aformat=sample_fmts=fltp:channel_layouts=stereo[sil{idx}]")
        labels.append(f"[sil{idx}]")
        idx += 1

    # 2) 正文区间
    if body_len > 0.001:
        if arg.bgm:
            # 全覆盖模式：BGM 从视频 0s 起铺满 片头+正文；offset 表示
            # 从曲子的第几秒开始取（默认 0 = 曲头对视频头）。
            take = (pre + body_len) if bgm_full else body_len
            extra_inputs.append(["-ss", f"{arg.offset:.3f}", "-t", f"{take:.4f}",
                                 "-i", arg.bgm])
            fade_out = max(0.05, duck) if not arg.same_track else 0.02
            gain = getattr(arg, "bgm_gain", 0.0) or 0.0
            vol = f"volume={gain:.2f}dB," if abs(gain) > 0.01 else ""
            # 重砖墙母带过 AAC 会振铃出 +2~3 dB 的真峰过冲（实测），纯降增益
            # 会把响度压得太低；用限幅器先把编码前真峰压到天花板，实测
            # -5.5 dB 天花板编出来 TP -1.8 / I -15.4（主题曲实测）。
            bl = getattr(arg, "bgm_limit", 0.0) or 0.0
            blim = (f"alimiter=limit={10 ** (-bl / 20.0):.4f}:level=disabled:"
                    f"attack=5:release=50,") if bl > 0.01 else ""
            parts.append(
                f"[{idx}:a]aresample={sr},"
                f"aformat=sample_fmts=fltp:channel_layouts=stereo,"
                f"{vol}{blim}"
                f"atrim=0:{take:.4f},asetpts=PTS-STARTPTS,"
                f"afade=t=out:st={max(0.0, take - fade_out):.4f}:d={fade_out:.4f}"
                f"[bgm{idx}]")
            labels.append(f"[bgm{idx}]")
        else:
            extra_inputs.append(["-f", "lavfi", "-t", f"{body_len:.4f}",
                                 "-i", f"anullsrc=r={sr}:cl=stereo"])
            parts.append(f"[{idx}:a]aresample={sr},"
                         f"aformat=sample_fmts=fltp:channel_layouts=stereo"
                         f"[body{idx}]")
            labels.append(f"[body{idx}]")
        idx += 1

    # 3) 片尾自带音乐
    if has_credits:
        ci = segs.index(credits_seg[0])
        fade_in = max(0.05, duck)
        # 片尾曲与 BGM 各自归一到同一响度目标；增益后加限幅器把编码前真峰
        # 压到天花板——AAC 编码对重砖墙母带会振铃出 +2~3 dB 过冲（实测），
        # 天花板不够低解码时照样削波、LUFS 虚高。
        cg = getattr(arg, "credits_gain", 0.0) or 0.0
        cl = getattr(arg, "credits_limit", 0.0) or 0.0
        cvol = f"volume={cg:.2f}dB," if abs(cg) > 0.01 else ""
        clim = (f"alimiter=limit={10 ** (-cl / 20.0):.4f}:level=disabled:"
                f"attack=5:release=50,") if abs(cl) > 0.01 else ""
        parts.append(
            f"[{ci}:a]aresample={sr},"
            f"aformat=sample_fmts=fltp:channel_layouts=stereo,"
            f"{cvol}{clim}"
            f"asetpts=PTS-STARTPTS,afade=t=in:st=0:d={fade_in:.4f}[cred{ci}]")
        labels.append(f"[cred{ci}]")

    if len(labels) == 1:
        return [], [], 0, labels[0]
    parts.append("".join(labels) + f"concat=n={len(labels)}:v=0:a=1[aout]")
    return parts, extra_inputs, len(extra_inputs), "[aout]"


# ---------------------------------------------------------------- 主流程

def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--timing", default=TIMING)
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "hbmzz-promo.mp4"))
    ap.add_argument("--bgm", default=None, help="正式 BGM 音频文件")
    ap.add_argument("--bpm", type=float, default=None,
                    help="BGM 的 BPM；改变时按 beats 重算每段帧数（需重渲染段）")
    ap.add_argument("--offset", type=float, default=0.0,
                    help="BGM 第一拍相对正文起点的偏移（秒）")
    ap.add_argument("--duck-credits", type=float, default=0.6,
                    help="正文末尾淡出 / 片尾音乐淡入的时长（秒）")
    ap.add_argument("--bgm-gain", type=float, default=0.0,
                    help="BGM 线性增益 dB（响度归一用，配合预处理后的文件常为 0）")
    ap.add_argument("--bgm-limit", type=float, default=0.0,
                    help="BGM 编码前真峰天花板 dB（>0 时启用限幅器，抗 AAC 过冲）")
    ap.add_argument("--credits-gain", type=float, default=0.0,
                    help="片尾自带音乐增益 dB；非 0 时自动加限幅器")
    ap.add_argument("--credits-limit", type=float, default=0.0,
                    help="片尾编码前真峰天花板 dB（>0 时启用限幅器）")
    ap.add_argument("--same-track", action="store_true",
                    help="正文 BGM 与片尾曲是同一首，接成连续一条不淡化")
    ap.add_argument("--only-fixed", action="store_true",
                    help="只拼 INTRO + CREDITS，用于验证管线")
    ap.add_argument("--allow-mismatch", action="store_true")
    ap.add_argument("--crf", type=int, default=16)
    ap.add_argument("--preset", default="slow")
    ap.add_argument("--no-cues", action="store_true")
    ARGS = ap.parse_args()

    t = json.load(open(ARGS.timing, encoding="utf-8"))
    fps = t["canvas"]["fps"]

    if ARGS.bpm and abs(ARGS.bpm - t["grid"]["bpm"]) > 1e-6:
        print(f"BPM {t['grid']['bpm']} -> {ARGS.bpm}: recomputing frame counts")
        beat_frames = fps * 60.0 / ARGS.bpm
        acc = 0
        for s in t["segments"]:
            if s["beats"] is not None:
                s["frames"] = int(round(s["beats"] * beat_frames))
            s["start_frame"] = acc
            s["start_ms"] = int(round(acc * 1000.0 / fps))
            s["duration_ms"] = int(round(s["frames"] * 1000.0 / fps))
            acc += s["frames"]
        t["totals"]["frames"] = acc
        t["totals"]["duration_ms"] = int(round(acc * 1000.0 / fps))
        die("BPM changed: segment frame counts no longer match the rendered files.\n"
            "       Re-render every segment with the new --frames, then run again.\n"
            "       (this is intentional: we re-time by re-rendering, never by stretching)",
            code=2)

    if ARGS.same_track:
        ARGS.duck_credits = 0.0

    segs = collect_segments(t, ARGS.only_fixed)
    if segs is None:
        return 1

    expect_frames = sum(s["frames"] for s in segs)
    print(f"assembling {len(segs)} segments, {expect_frames} frames "
          f"({expect_frames / fps:.3f} s)")

    video_filter = build_filter(segs, expect_frames, fps)
    audio_filter, audio_inputs, n_audio_in, audio_out = build_audio(
        segs, t, ARGS, len(segs))

    all_filter = video_filter
    if audio_filter:
        all_filter += ";\n" + ";\n".join(audio_filter)
    else:
        audio_out = None

    fscript = os.path.join(BUILD_DIR, "_filter.txt")
    os.makedirs(BUILD_DIR, exist_ok=True)
    if os.path.exists(fscript):
        os.remove(fscript)
    with open(fscript, "w", encoding="utf-8", newline="\n") as f:
        f.write(all_filter)

    cmd = ["ffmpeg", "-hide_banner", "-v", "warning", "-stats", "-y"]
    for s in segs:
        cmd += ["-i", os.path.join(ROOT, s["source"])]
    for a in audio_inputs:
        cmd += a
    cmd += ["-filter_complex_script", fscript,
            "-map", "[vout]"]
    if audio_out:
        cmd += ["-map", audio_out]
    cmd += ["-c:v", "libx264", "-crf", str(ARGS.crf), "-preset", ARGS.preset,
            "-pix_fmt", "yuv420p", "-r", str(fps)]
    if audio_out:
        cmd += ["-c:a", "aac", "-b:a", str(t["audio"]["bitrate"]),
                "-ar", str(t["audio"]["sample_rate"]), "-ac", "2"]
    else:
        cmd += ["-an"]
    # 用 -t 按帧数精确限时，不要用 -shortest：AAC 编码的尾差会把最后一帧截掉。
    cmd += ["-t", f"{expect_frames / float(fps):.6f}",
            "-movflags", "+faststart", ARGS.out]

    if os.path.exists(ARGS.out):
        os.remove(ARGS.out)

    print("running ffmpeg ...")
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stderr[-4000:], file=sys.stderr)
        die("ffmpeg failed")
    if not os.path.isfile(ARGS.out):
        die("ffmpeg reported success but output missing")

    # ---------------- 自检 ----------------
    print("\n-- verify --")
    ok = True
    m = ffprobe_meta(ARGS.out)
    vs = [s for s in m["streams"] if s["codec_type"] == "video"]
    aus = [s for s in m["streams"] if s["codec_type"] == "audio"]
    n, exact = ffprobe_frames(ARGS.out)
    dur = float(m["format"]["duration"])
    v = vs[0]
    checks = [
        ("canvas", (int(v["width"]), int(v["height"])) == (960, 540),
         f"{v['width']}x{v['height']}"),
        ("fps", abs(float(v["r_frame_rate"].split("/")[0]) /
                    float(v["r_frame_rate"].split("/")[1]) - fps) < 0.01,
         v["r_frame_rate"]),
        ("frames", abs(n - expect_frames) <= 1, f"{n} (expect {expect_frames})"),
        ("duration", abs(dur - expect_frames / fps) < 0.2,
         f"{dur:.3f}s (expect {expect_frames / fps:.3f}s)"),
        ("audio", bool(aus) or not audio_out, f"{len(aus)} track(s)"),
    ]
    for name, cond, detail in checks:
        print(f"  [{'ok  ' if cond else 'FAIL'}] {name}: {detail}")
        ok = ok and cond

    print(f"\noutput: {ARGS.out}  ({os.path.getsize(ARGS.out):,} B)")

    if not ARGS.no_cues:
        write_cues(t, segs, ARGS.out)

    print("\n" + ("ALL PASS" if ok else "SOME CHECKS FAILED"))
    return 0 if ok else 1


def write_cues(t, segs, video_path):
    fps = t["canvas"]["fps"]
    hits = t.get("hits", [])
    cues = {
        "video": os.path.relpath(video_path, ROOT).replace("\\", "/"),
        "fps": fps,
        "frames": sum(s["frames"] for s in segs),
        "bgm": ARGS.bgm,
        "bpm": ARGS.bpm or t["grid"]["bpm"],
        "offset": ARGS.offset,
        "segments": [{
            "id": s["id"], "name": s["name"],
            "start_frame": s["start_frame"], "frames": s["frames"],
            "start_ms": s["start_ms"], "end_ms": s["start_ms"] + s["duration_ms"],
            "beats": s["beats"],
            "tc_in": fmt_tc(s["start_ms"]), "tc_out": fmt_tc(s["start_ms"] + s["duration_ms"]),
        } for s in segs],
        "hits": hits,
    }
    jp = os.path.join(OUT_DIR, "cues.json")
    if os.path.exists(jp):
        os.remove(jp)
    with open(jp, "w", encoding="utf-8") as f:
        json.dump(cues, f, ensure_ascii=False, indent=2)

    md = ["# 卡点清单", "",
          f"- 成片：`{cues['video']}`",
          f"- 帧率 {fps} / 总帧 {cues['frames']}",
          f"- BGM：{ARGS.bgm or '（未接入，正文为静音占位）'}",
          f"- BPM {cues['bpm']} / offset {ARGS.offset}s", "",
          "## 段落边界", "",
          "| 段 | 名称 | 入点 | 出点 | 帧数 | 拍数 |",
          "|---|---|---|---|---:|---:|"]
    for s in cues["segments"]:
        md.append(f"| {s['id']} | {s['name']} | {s['tc_in']} | {s['tc_out']} | "
                  f"{s['frames']} | {s['beats'] if s['beats'] is not None else '—'} |")
    md += ["", "## 建议重音点", ""]
    for h in hits:
        stale = ""
        if ARGS.bpm:
            stale = "  ← 时间码基于原 BPM，改 BPM 后需重算"
        md.append(f"- `{fmt_tc(h['at_ms'])}`  {h['label']}{stale}")
    md += ["", "## 用法", "",
           "BGM 到位后重出全片：", "",
           "```bash",
           "py -3.12 tools/video/promo/make_promo.py \\",
           '    --bgm "path/to/bgm.wav" --bpm 128 --offset 0.0',
           "```", ""]
    mp = os.path.join(OUT_DIR, "CUE.md")
    if os.path.exists(mp):
        os.remove(mp)
    with open(mp, "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    print(f"cues: {jp}\n      {mp}")


def fmt_tc(ms):
    ms = int(round(ms))
    m, rem = divmod(ms, 60000)
    s, msec = divmod(rem, 1000)
    return f"{m:02d}:{s:02d}.{msec:03d}"


ARGS = None

if __name__ == "__main__":
    sys.exit(main())
