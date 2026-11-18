#!/usr/bin/env python3
"""Render the brand assets: every bitmap here is a compiled artefact of an SVG.

The SVGs in ``docs/assets/brand`` are the source of truth. This script turns them
into PNGs at the sizes the site, the repo page and the OST page need, and it
generates the cover layouts *as SVG too* - so the covers are vector as
well, and the PNG is just one of their two exports.

Why a browser: this machine has no cairosvg, no Pillow and no ImageMagick.
Chrome is the only SVG engine present, and it is also the engine that will draw
these files on the site, so PNG and page agree by construction.

Two traps worth remembering, both measured rather than guessed:

  * ``--window-size`` is the **window**, not the viewport. ``--window-size=512,512``
    gives a 496x417 viewport, and ``1280,640`` gives 1264x545 - the content is
    laid out for a smaller box than the PNG, so every asset came out cropped.
    ``--screenshot`` also fires before an HTML page has painted (a page whose
    DOM measured 100x100 produced a 15-row band of ink).
  * So the screenshots go through CDP instead: one Chrome, exact device metrics,
    ``Page.captureScreenshot`` with a clip. That is the only path here that
    produced pixel-exact files.

    python tools/brand/render.py

Requirements: Chrome. Override with CHROME=/path/to/chrome.
"""
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRAND = os.path.join(ROOT, "docs", "assets", "brand")

CHROME_CANDIDATES = (
    os.environ.get("CHROME", ""),
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/usr/bin/google-chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
)

INK = "#e6ecf3"
DIM = "#93a0b0"
MUTE = "#67737f"
AMBER = "#f5a524"
BG = "#0a0c0f"
GRID = "#232a33"

MONO = "Consolas, 'Cascadia Mono', 'Courier New', monospace"
CJK = "'Microsoft YaHei', 'PingFang SC', 'Hiragino Sans GB', sans-serif"


def chrome():
    for candidate in CHROME_CANDIDATES:
        if candidate and os.path.isfile(candidate):
            return candidate
    raise SystemExit("找不到 Chrome：设 CHROME 环境变量指向可执行文件")


def mark_inner(name):
    """源 SVG 里 <svg> 之间的那一段（标题与 XML 声明都丢掉）。"""
    text = io.open(os.path.join(BRAND, name), encoding="utf-8").read()
    body = text[text.index(">", text.index("<svg")) + 1:text.rindex("</svg>")]
    body = re.sub(r"<title>.*?</title>", "", body, flags=re.S)
    return body.strip()


def doc(width, height, body, vb=None):
    """包成一份 SVG 文档。

    vb 必须显式给：图标的内容是 64 单位的，而画布是 size 像素——如果不把
    viewBox 钉在 64，内容只会占左上角那一小块（第一版就是这样，512 的图里
    标只有 62px）。
    """
    vb = vb or ("0 0 %d %d" % (width, height))
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
            'viewBox="%s">\n%s\n</svg>\n' % (width, height, vb, body))


def grid_pattern(size=40, colour=GRID, width=1280, height=640):
    return ('<defs><pattern id="grid" width="%d" height="%d" patternUnits="userSpaceOnUse">'
            '<path d="M%d 0H0V%d" fill="none" stroke="%s" stroke-width="1"/>'
            '</pattern></defs>\n<rect width="%d" height="%d" fill="url(#grid)"/>'
            % (size, size, size, size, colour, width, height))


class Chrome:
    """一个最小的 CDP 客户端：只为设视口 + 截图两件事。

    没有 websocket 库可用，所以这里直接实现握手与帧。别的地方不要复用它——
    它只处理「一条消息一个帧」这种 Chrome 实际会发的情况。
    """

    def __init__(self, port, profile):
        self.port = port
        self.proc = subprocess.Popen(
            [chrome(), "--headless=new", "--disable-gpu", "--hide-scrollbars",
             "--no-first-run", "--no-default-browser-check",
             "--remote-debugging-port=%d" % port,
             "--user-data-dir=" + profile, "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.sock = None
        self.ident = 0

    def connect(self, timeout=20.0):
        import base64
        import json
        import os as _os
        import socket
        import struct
        import time
        deadline = time.time() + timeout
        target = None
        while time.time() < deadline:
            try:
                import urllib.request
                with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % self.port, timeout=2) as r:
                    for item in json.load(r):
                        if item.get("type") == "page":
                            target = item["webSocketDebuggerUrl"]
                            break
                if target:
                    break
            except Exception:
                pass
            time.sleep(0.2)
        if not target:
            raise SystemExit("连不上 Chrome 的调试端口")

        url = target.split("://", 1)[1]
        hostport, path = url.split("/", 1)
        host, port = hostport.split(":")
        sock = socket.create_connection((host, int(port)), timeout=10)
        key = base64.b64encode(_os.urandom(16)).decode()
        sock.sendall(("GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
                      "Sec-WebSocket-Version: 13\r\n\r\n" % (path, hostport, key)).encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += sock.recv(4096)
        if b"101" not in head.split(b"\r\n")[0]:
            raise SystemExit("WebSocket 握手失败")
        self.sock = sock

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise SystemExit("连接被关闭")
            buf += chunk
        return buf

    def _read_message(self):
        payload = b""
        while True:
            b1, b2 = self._recv_exact(2)
            fin = b1 & 0x80
            opcode = b1 & 0x0F
            length = b2 & 0x7F
            if length == 126:
                length = int.from_bytes(self._recv_exact(2), "big")
            elif length == 127:
                length = int.from_bytes(self._recv_exact(8), "big")
            mask = self._recv_exact(4) if b2 & 0x80 else None
            data = self._recv_exact(length)
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if opcode == 8:
                raise SystemExit("Chrome 关闭了调试连接")
            if opcode == 9:
                self._send_raw(10, data)
                continue
            if opcode == 10:
                continue
            payload += data
            if fin:
                return payload.decode("utf-8", "replace")

    def _send_raw(self, opcode, data):
        import os as _os
        mask = _os.urandom(4)
        length = len(data)
        header = bytes([0x80 | opcode])
        if length < 126:
            header += bytes([0x80 | length])
        elif length < 65536:
            header += bytes([0x80 | 126]) + length.to_bytes(2, "big")
        else:
            header += bytes([0x80 | 127]) + length.to_bytes(8, "big")
        masked = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
        self.sock.sendall(header + mask + masked)

    def call(self, method, params=None):
        import json
        self.ident += 1
        self._send_raw(1, json.dumps({"id": self.ident, "method": method,
                                      "params": params or {}}).encode())
        while True:
            message = json.loads(self._read_message())
            if message.get("id") == self.ident:
                if "error" in message:
                    raise SystemExit("%s 失败：%s" % (method, message["error"]))
                return message.get("result", {})

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        finally:
            self.proc.terminate()


SESSION = None


def shoot(svg_text, out, width, height, transparent):
    """把一份 SVG 文档按精确像素截下来。"""
    import base64
    global SESSION
    if SESSION is None:
        raise SystemExit("会话没有建立")
    tmpfile = tempfile.mkdtemp(prefix="brand-")
    try:
        path = os.path.join(tmpfile, "a.svg")
        io.open(path, "w", encoding="utf-8", newline="\n").write(svg_text)
        SESSION.call("Emulation.setDeviceMetricsOverride",
                     {"width": width, "height": height, "deviceScaleFactor": 1,
                      "mobile": False})
        SESSION.call("Emulation.setDefaultBackgroundColorOverride",
                     {"color": {"r": 0, "g": 0, "b": 0, "a": 0}} if transparent
                     else {"color": {"r": 10, "g": 12, "b": 15, "a": 255}})
        SESSION.call("Page.navigate", {"url": "file:///" + path.replace("\\", "/")})
        import time
        time.sleep(0.35)
        shot = SESSION.call("Page.captureScreenshot",
                            {"format": "png",
                             "clip": {"x": 0, "y": 0, "width": width, "height": height,
                                      "scale": 1}})
        with open(out, "wb") as fh:
            fh.write(base64.b64decode(shot["data"]))
    finally:
        shutil.rmtree(tmpfile, ignore_errors=True)


# --------------------------------------------------------------------- 图标
def icon_svg(mark, size, pad_ratio=0.0, background=None):
    """把一个 64 单位的标铺进 size 像素的方框，可选留白与底色。

    留白用坐标系的缩放做，而不是给外层套 padding：所有东西留在同一个 64 单位
    坐标里，偏移与缩放一次算清，不会出现「盒子对了、内容被裁」。
    """
    scale = 1.0 - 2.0 * pad_ratio
    offset = (64.0 - 64.0 * scale) / 2.0
    body = ""
    if background:
        body += '<rect width="64" height="64" fill="%s"/>\n' % background
    body += ('<g transform="translate(%.4f,%.4f) scale(%.4f)">%s</g>'
             % (offset, offset, scale, mark_inner(mark)))
    return doc(size, size, body, vb="0 0 64 64")


# --------------------------------------------------------------------- 封面
def cover_svg(width, height, *, mark_size, wordsize, rule_w, subsize,
              word, sub, foot, tag="", pad=None, gap=None, r1=None, thick=None,
              centered=False, second="", mark="logo-mark.svg"):
    """左对齐或居中的一张封面。所有坐标按像素算死，不做百分比。

    mark 决定封面顶上是哪个标：站点 / 仓库 / 分享卡用主标，原声那两张用
    logo-ost.svg（同一块屏，屏里是电平柱）。两张标的画布都是 64 单位，所以
    这里换的只是被拼进去的那段几何，缩放与排版一概不动。
    """
    pad = pad if pad is not None else int(width * 0.075)
    gap = gap if gap is not None else int(width * 0.035)
    r1 = r1 if r1 is not None else int(width * 0.025)
    thick = thick if thick is not None else max(3, int(width * 0.005))
    footsize = max(12, int(width * 0.0145))
    second_size = int(wordsize * 0.48)

    # 文字块总高，用来在画布里垂直居中（底部那行参数另算，不让它压上来）
    block = mark_size + gap + wordsize * 0.74
    if second:
        block += second_size * 1.16
    block += r1 + thick + int(width * 0.02) + subsize
    top = (height - block) / 2.0

    x = pad if not centered else width / 2.0
    anchor = "start" if not centered else "middle"

    body = '<rect width="%d" height="%d" fill="%s"/>\n' % (width, height, BG)
    body += grid_pattern(width=width, height=height)
    body += ('<radialGradient id="glow" cx="30%%" cy="40%%" r="60%%">'
             '<stop offset="0" stop-color="%s" stop-opacity=".07"/>'
             '<stop offset="1" stop-color="%s" stop-opacity="0"/></radialGradient>\n'
             '<rect width="%d" height="%d" fill="url(#glow)"/>\n' % (AMBER, AMBER, width, height))

    scale = mark_size / 64.0
    mark_x = x - mark_size / 2.0 if centered else x
    body += ('<g transform="translate(%.2f,%.2f) scale(%.4f)">%s</g>\n'
             % (mark_x, top, scale, mark_inner(mark)))

    y = top + mark_size + gap + wordsize * 0.74
    body += ('<text x="%.1f" y="%.1f" fill="%s" font-family="%s" font-size="%d" '
             'font-weight="700" letter-spacing="%.1f" text-anchor="%s">%s</text>\n'
             % (x, y, INK, MONO, wordsize, wordsize * 0.12, anchor, word))

    if second:
        y += second_size * 1.16
        body += ('<text x="%.1f" y="%.1f" fill="%s" font-family="%s" font-size="%d" '
                 'font-weight="700" letter-spacing="%.1f" text-anchor="%s">%s</text>\n'
                 % (x, y, AMBER, MONO, second_size, second_size * 0.34, anchor, second))

    y += r1
    rule_x = x - rule_w / 2.0 if centered else x
    body += '<rect x="%.1f" y="%.1f" width="%d" height="%d" fill="%s"/>\n' % (
        rule_x, y, rule_w, thick, AMBER)

    y += thick + int(width * 0.02) + subsize
    body += ('<text x="%.1f" y="%.1f" fill="%s" font-family="%s" font-size="%d" '
             'letter-spacing="%.2f" text-anchor="%s">%s</text>\n'
             % (x, y, DIM, CJK, subsize, subsize * 0.06, anchor, sub))

    body += ('<text x="%d" y="%d" fill="%s" font-family="%s" font-size="%d" '
             'letter-spacing="%.2f">%s</text>\n'
             % (pad, height - pad, MUTE, MONO, footsize, footsize * 0.14, foot))
    if tag:
        body += ('<text x="%d" y="%d" fill="%s" font-family="%s" font-size="%d" '
                 'letter-spacing="%.2f" text-anchor="end">%s</text>\n'
                 % (width - pad, height - pad, MUTE, MONO, footsize, footsize * 0.14, tag))
    return doc(width, height, body)


def validate():
    """每个源 SVG 必须是合法 XML。

    XML 注释里不能出现连续两个连字符：第一版在注释里写了 CSS 变量名，五个文件
    全部解析失败，浏览器只画一片空白，PNG 也跟着变成空白图。
    """
    for name in sorted(os.listdir(BRAND)):
        if not name.endswith(".svg"):
            continue
        try:
            root = ET.parse(os.path.join(BRAND, name)).getroot()
        except ET.ParseError as exc:
            raise SystemExit("%s 不是合法 XML：%s" % (name, exc))
        if not root.tag.endswith("svg") or not root.get("viewBox"):
            raise SystemExit("%s 缺 viewBox 或根节点不是 svg" % name)


def main():
    import socket
    import time
    validate()

    # 找一个空端口，起一次 Chrome，全程复用
    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    profile = tempfile.mkdtemp(prefix="brand-profile-")
    global SESSION
    SESSION = Chrome(port, profile)
    SESSION.connect()
    made = []

    def out(name, svg, w, h, transparent=False, keep_svg=False):
        path = os.path.join(BRAND, name)
        shoot(svg, path, w, h, transparent)
        made.append((name, w, h, os.path.getsize(path)))
        if keep_svg:
            svg_name = name.rsplit(".", 1)[0] + ".svg"
            target = os.path.join(BRAND, svg_name)
            io.open(target, "w", encoding="utf-8", newline="\n").write(svg)
            made.append((svg_name, w, h, os.path.getsize(target)))

    # 主标：大尺寸（带螺钉）与浅色背景版
    for size in (512, 1024):
        out("logo-mark-%d.png" % size, icon_svg("logo-mark.svg", size), size, size, True)
    out("logo-mark-light-512.png", icon_svg("logo-mark-light.svg", 512), 512, 512, True)

    # OST 标：同一块屏，屏里换成三根电平柱。原声的封面与专辑头都从这份出。
    for size in (512, 1024):
        out("logo-ost-%d.png" % size, icon_svg("logo-ost.svg", size), size, size, True)
    out("logo-ost-light-512.png", icon_svg("logo-ost-light.svg", 512), 512, 512, True)

    # 小尺寸：带底板的那份。透明版在浅色标签栏上边框看不见，小图标必须自带底色。
    for size in (16, 32, 48):
        out("favicon-%d.png" % size, icon_svg("logo-favicon.svg", size), size, size, True)

    # 系统图标同样用带底板的版本；iOS 会自己裁圆角，所以不再额外留白
    for size in (180, 192, 512):
        name = "apple-touch-180.png" if size == 180 else "icon-%d.png" % size
        out(name, icon_svg("logo-appicon.svg", size), size, size, False)

    # 三张版式：SVG 与 PNG 各出一份
    out("repo-cover-1280x640.png",
        cover_svg(1280, 640, mark_size=150, wordsize=118, rule_w=384, subsize=22,
                  word="HBMZZ", sub="给 HBM 的核科技装一块真的显示器",
                  foot="MINECRAFT 1.12.2 · FORGE · GPL-3.0-ONLY",
                  tag="hbm-zz.ysbdwz.dpdns.org"),
        1280, 640, keep_svg=True)

    out("og-1200x630.png",
        cover_svg(1200, 630, mark_size=140, wordsize=110, rule_w=356, subsize=21,
                  word="HBMZZ", sub="给 HBM 的核科技装一块真的显示器",
                  foot="MINECRAFT 1.12.2 · FORGE · GPL-3.0-ONLY",
                  tag="hbm-zz.ysbdwz.dpdns.org"),
        1200, 630, keep_svg=True)

    # 缩略版：专辑头里只显示 68px，1024 那张的标缩到那个尺寸只剩 15px，
    # 看上去就是「空方块里一个小点」。所以另出一张标占满、去掉小字的构图。
    out("ost-cover-thumb-256.png",
        cover_svg(256, 256, mark_size=176, wordsize=40, rule_w=96, subsize=0,
                  word="HBMZZ", sub="", foot="", pad=24, gap=20, centered=True,
                  mark="logo-ost.svg"),
        256, 256, keep_svg=True)

    out("ost-cover-1024.png",
        cover_svg(1024, 1024, mark_size=232, wordsize=76, rule_w=300, subsize=22,
                  word="HBMZZ", second="OST", sub="THREE TRACKS · 11:47",
                  foot="GPL-3.0-ONLY · hbm-zz.ysbdwz.dpdns.org",
                  pad=96, gap=56, centered=True, mark="logo-ost.svg"),
        1024, 1024, keep_svg=True)

    # 512 版：烧进音频文件的那张（mp3 的 ID3 APIC / ogg 的 METADATA_BLOCK_PICTURE）。
    # 1024 那张 313 KB，三首各嵌一份就是 1 MB；512 的平面构图压出来只有它的三分之一，
    # 而媒体面板（锁屏、播放器小组件）本来也不会显示到 1024。
    out("ost-cover-512.png",
        cover_svg(512, 512, mark_size=116, wordsize=38, rule_w=150, subsize=11,
                  word="HBMZZ", second="OST", sub="THREE TRACKS · 11:47",
                  foot="GPL-3.0-ONLY · hbm-zz.ysbdwz.dpdns.org",
                  pad=48, gap=28, centered=True, mark="logo-ost.svg"),
        512, 512, keep_svg=True)

    SESSION.close()
    shutil.rmtree(profile, ignore_errors=True)

    print("%-30s %11s %11s" % ("文件", "尺寸", "字节"))
    for name, w, h, size in made:
        print("%-30s %5dx%-5d %10s" % (name, w, h, format(size, ",")))
    print("\n%d 个文件" % len(made))
    return 0


if __name__ == "__main__":
    sys.exit(main())
