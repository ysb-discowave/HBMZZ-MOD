"""挑一段最适合做预告片的 60 秒：按 5 秒窗口测响度，找最「满」的一段。

不做音乐判断（听不出副歌），只做数据判断：平均响度最高的连续 12 个窗口，
且不贴着开头与结尾——开头常有淡入，结尾常有淡出。

    python tools/promo/pick_window.py <mp3> [窗口秒数] [需要几秒]
"""

import subprocess
import sys


def mean_vol(path, start, dur):
    p = subprocess.run(
        ["ffmpeg", "-hide_banner", "-ss", str(start), "-t", str(dur), "-i", path,
         "-af", "volumedetect", "-f", "null", "-"],
        capture_output=True)
    for line in p.stderr.decode("utf-8", "replace").splitlines():
        if "mean_volume:" in line:
            return float(line.split("mean_volume:")[1].split("dB")[0].strip())
    return None


def main():
    path = sys.argv[1]
    win = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    need = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    total = 195 if "how-to" in path else 317
    vals = [(s, mean_vol(path, s, win)) for s in range(0, total - win + 1, win)]
    vals = [(s, v) for s, v in vals if v is not None]
    span = max(1, need // win)

    best = None
    for i in range(len(vals) - span + 1):
        chunk = vals[i:i + span]
        if i == 0 or i + span >= len(vals):      # 别贴开头结尾
            continue
        avg = sum(v for _, v in chunk) / span
        spread = max(v for _, v in chunk) - min(v for _, v in chunk)
        score = avg - spread * 0.15              # 宁要稳的满，不要忽大忽小
        if best is None or score > best[0]:
            best = (score, chunk[0][0], avg, spread)

    print("窗口 %ds / 需要 %ds" % (win, need))
    print("  " + " ".join("%d:%s" % (s, v) for s, v in vals))
    if best:
        print("最佳起点 %ds（平均 %.1f dB，起伏 %.1f dB）" % (best[1], best[2], best[3]))


if __name__ == "__main__":
    main()
