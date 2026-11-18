#!/usr/bin/env python3
"""HBMZZ 宣传片独立验收。

这不是实现者自检——它的职责是拿 timing.json 当尺子，去量成片到底对不对。
凡是能量化的都量化，量不了的抽帧出来让人看。

    py -3.12 tools/video/promo/verify.py [--video PATH] [--json]

退出码 0 = 全部 PASS，1 = 有 FAIL。
"""

import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))               # tools/video/promo
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))  # repo root
TIMING = os.path.join(HERE, "timing.json")

PASS, FAIL, WARN = "PASS", "FAIL", "WARN"
results = []


def add(level, name, detail=""):
    results.append((level, name, detail))
    mark = {PASS: "ok  ", FAIL: "FAIL", WARN: "warn"}[level]
    print(f"[{mark}] {name}" + (f"  {detail}" if detail else ""))


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def ffprobe_streams(path):
    r = run(["ffprobe", "-v", "error", "-show_streams", "-show_format",
             "-of", "json", path])
    if r.returncode != 0:
        return None
    return json.loads(r.stdout)


def probe_num(path, expr):
    r = run(["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-count_frames", "-show_entries", expr, "-of", "csv=p=0", path])
    if r.returncode != 0:
        return None
    return r.stdout.strip().strip(",")


def count_frames_exact(path):
    """用 count_frames 精确数帧（nb_read_frames）。慢但对得上。"""
    r = run(["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-count_frames", "-show_entries", "stream=nb_read_frames",
             "-of", "csv=p=0", path])
    v = r.stdout.strip().strip(",")
    try:
        return int(v)
    except ValueError:
        return None


def psnr_avg_two(a, b, frames_a, frames_b, fps):
    """取较短的那段做对比。"""
    n = min(frames_a, frames_b)
    filt = (f"[0:v]trim=start_frame=0:end_frame={n},setpts=PTS-STARTPTS[a];"
            f"[1:v]trim=start_frame=0:end_frame={n},setpts=PTS-STARTPTS[b];"
            f"[a][b]psnr")
    r = run(["ffmpeg", "-hide_banner", "-v", "info", "-i", a, "-i", b,
             "-lavfi", filt, "-f", "null", "-"])
    m = re.search(r"average:([0-9.]+|inf)", r.stderr + r.stdout)
    if not m:
        return None
    if m.group(1) == "inf":
        return float("inf")
    return float(m.group(1))


def silence_report(path, start, end):
    """在 [start,end] 区间跑 silencedetect，返回 (是否有声, 最大音量dB)。"""
    r = run(["ffmpeg", "-hide_banner", "-v", "info", "-ss", f"{start}",
             "-to", f"{end}", "-i", path, "-af",
             "volumedetect", "-f", "null", "-"])
    text = r.stderr
    mx = re.search(r"max_volume:\s*(-?[0-9.]+) dB", text)
    mean = re.search(r"mean_volume:\s*(-?[0-9.]+) dB", text)
    mx_v = float(mx.group(1)) if mx else None
    mean_v = float(mean.group(1)) if mean else None
    return mean_v, mx_v


def extract(path, frame, out):
    if os.path.exists(out):
        os.remove(out)
    r = run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", path,
             "-vf", f"select='eq(n\\,{frame})'", "-vsync", "0",
             "-frames:v", "1", out])
    return os.path.exists(out) and os.path.getsize(out) > 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", default=os.path.join(HERE, "out", "hbmzz-promo.mp4"))
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--frames-dir", default=os.path.join(HERE, "out", "verify"))
    args = ap.parse_args()

    if not os.path.isfile(args.video):
        print(f"missing video: {args.video}")
        return 1

    t = json.load(open(TIMING, encoding="utf-8"))
    fps = t["canvas"]["fps"]
    exp_frames = t["totals"]["frames"]
    exp_ms = t["totals"]["duration_ms"]
    segs = t["segments"]

    info = ffprobe_streams(args.video)
    if not info:
        print("ffprobe failed")
        return 1
    vs = [s for s in info["streams"] if s["codec_type"] == "video"]
    aus = [s for s in info["streams"] if s["codec_type"] == "audio"]
    if not vs:
        add(FAIL, "video stream exists")
        return 1
    v = vs[0]

    # ---- 结构 ----
    w, h = int(v["width"]), int(v["height"])
    add(PASS if (w, h) == (960, 540) else FAIL, "canvas 960x540", f"{w}x{h}")

    num, den = v["r_frame_rate"].split("/")
    fr = float(num) / float(den)
    add(PASS if abs(fr - fps) < 0.01 else FAIL, f"fps {fps}", f"{fr:.3f}")

    pix = v.get("pix_fmt", "?")
    add(PASS if pix == "yuv420p" else FAIL, "pix_fmt yuv420p", pix)

    add(PASS if v.get("codec_name") == "h264" else FAIL, "codec h264",
        v.get("codec_name", "?"))

    dur = float(info["format"]["duration"])
    exp_s = exp_ms / 1000.0
    add(PASS if abs(dur - exp_s) < 0.15 else FAIL, "duration",
        f"{dur:.3f}s (expect {exp_s:.3f}s)")

    nf = count_frames_exact(args.video)
    add(PASS if nf == exp_frames else FAIL, "frame count",
        f"{nf} (expect {exp_frames})")

    # ---- 音频 ----
    if not aus:
        add(FAIL, "audio stream exists", "no audio track")
        mean_v = mx_v = None
    else:
        a = aus[0]
        sr = int(a.get("sample_rate", 0))
        ch = int(a.get("channels", 0))
        add(PASS if sr == 48000 else FAIL, "sample rate 48k", str(sr))
        add(PASS if ch == 2 else FAIL, "stereo", str(ch))
        add(PASS if a.get("codec_name") == "aac" else WARN, "codec aac",
            a.get("codec_name", "?"))

    # ---- 段边界 ----
    os.makedirs(args.frames_dir, exist_ok=True)
    print("\n-- segment boundaries --")
    for s in segs:
        st = s["start_frame"]
        tag = s["id"]
        for label, fr_i in (("first", st), ("last", st + s["frames"] - 1)):
            out = os.path.join(args.frames_dir, f"b_{tag}_{label}.png")
            ok = extract(args.video, fr_i, out)
            if not ok:
                add(WARN, f"{tag} {label} frame", "extract failed")
    print(f"   boundaries written to {args.frames_dir}")

    # ---- 片头一致性（0-8s 应等于 intro 素材）----
    # 成片在 INTRO 末 12 帧做了淡黑（§4.1 接缝设计），把它排除出比对窗口，
    # 否则 12 帧纯黑对非黑源会把均值拖到 30 dB 以下。
    print("\n-- fixed material fidelity --")
    intro = os.path.join(ROOT, "tools", "video", "intro", "out", "hbmzz-intro.mp4")
    if os.path.isfile(intro):
        p = psnr_avg_two(args.video, intro, 180, 180, fps)
        if p is None:
            add(WARN, "intro fidelity", "psnr unavailable")
        elif p == float("inf"):
            add(PASS, "intro fidelity", "identical")
        else:
            add(PASS if p >= 40 else WARN, "intro fidelity",
                f"PSNR {p:.1f} dB (>=40 ok, minor re-encode loss expected)")
    else:
        add(WARN, "intro fidelity", "intro mp4 missing")

    credits = os.path.join(ROOT, "tools", "video", "credits", "out",
                           "hbmzz-credits.mp4")
    if os.path.isfile(credits) and nf:
        # 取片尾区间做对比：成片从 start_frame 起，素材从 0 起。
        # 素材是 12 fps、成片 24 fps——同一秒的内容素材只占一半帧数，
        # 所以源侧 trim 的帧数必须按 source_fps 折算，否则窗口错配
        # （240 源帧 = 20 s 内容）会把均值拖到 25 dB 的假阴性。
        # 成片在 CREDITS 首 12 帧做了淡入（§4.2），也排除出比对窗口。
        cs = [s for s in segs if s["id"] == "CREDITS"][0]
        st = cs["start_frame"]
        src_fps = cs.get("source_fps", fps)
        skip_out = 12                                  # 设计内淡入帧
        n_out = min(240, cs["frames"]) - skip_out      # 成片侧比对帧数
        n_src = int(round(n_out * src_fps / fps))      # 折回源帧数
        b0 = int(round(skip_out * src_fps / fps))      # 源侧同步跳过淡入
        filt = (f"[0:v]trim=start_frame={st + skip_out}:end_frame={st + skip_out + n_out},"
                f"setpts=PTS-STARTPTS,fps={fps}[a];"
                f"[1:v]trim=start_frame={b0}:end_frame={b0 + n_src},"
                f"setpts=PTS-STARTPTS,fps={fps}[b];[a][b]psnr")
        r = run(["ffmpeg", "-hide_banner", "-v", "info", "-i", args.video,
                 "-i", credits, "-lavfi", filt, "-f", "null", "-"])
        m = re.search(r"average:([0-9.]+|inf)", r.stderr + r.stdout)
        if not m:
            add(WARN, "credits fidelity", "psnr unavailable")
        else:
            val = float("inf") if m.group(1) == "inf" else float(m.group(1))
            add(PASS if val >= 30 else WARN, "credits fidelity",
                f"PSNR {val if val == float('inf') else round(val,1)} dB")

    # ---- 正文静音（无 BGM 模式）----
    print("\n-- body audio --")
    bw = t["audio"]["bgm_window"]
    s0, s1 = bw["start_ms"] / 1000.0, bw["end_ms"] / 1000.0
    mean_v, mx_v = silence_report(args.video, s0 + 0.5, s1 - 0.5)
    # 有 BGM 时 cues.json 的 bgm 字段非空：正文应当有音乐，静音反而是失败。
    has_bgm = False
    cj = os.path.join(os.path.dirname(args.video), "cues.json")
    if os.path.isfile(cj):
        try:
            has_bgm = bool(json.load(open(cj, encoding="utf-8")).get("bgm"))
        except (ValueError, OSError):
            pass
    if mx_v is None:
        add(WARN, "body audio", "volumedetect unavailable")
    elif has_bgm:
        add(PASS if mx_v > -60 else FAIL, "bgm present",
            f"max {mx_v} dB mean {mean_v} dB")
    else:
        add(PASS if mx_v <= -60 else WARN, "body silent",
            f"max {mx_v} dB mean {mean_v} dB")

    # ---- 汇总 ----
    n_pass = sum(1 for l, _, _ in results if l == PASS)
    n_fail = sum(1 for l, _, _ in results if l == FAIL)
    n_warn = sum(1 for l, _, _ in results if l == WARN)
    print(f"\n== {n_pass} pass / {n_fail} fail / {n_warn} warn ==")

    if args.json:
        jp = os.path.join(args.frames_dir, "verify.json")
        with open(jp, "w", encoding="utf-8") as f:
            json.dump({"video": args.video,
                       "results": [{"level": l, "name": n, "detail": d}
                                   for l, n, d in results],
                       "pass": n_pass, "fail": n_fail, "warn": n_warn},
                      f, ensure_ascii=False, indent=2)
        print(f"report: {jp}")

    return 0 if n_fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
