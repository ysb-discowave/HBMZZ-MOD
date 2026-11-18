"""带 Range 支持的本地静态服务，只用于本地预览与探针。

`python -m http.server` 不认 Range 请求，`<audio>` 的 seekable 因此是 [0,0]——
进度条拖不动、跨页续播也只能从 0 开始，看着像播放器坏了。GitHub Pages 是支持
Range 的（206），所以本地预览必须用这个，别用 stdlib 那个。

    python tools/site/serve.py [端口] [目录]
"""

import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "docs")
TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".mp3": "audio/mpeg",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".txt": "text/plain; charset=utf-8",
}
RANGE = re.compile(r"bytes=(\d*)-(\d*)")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 安静一点，探针跑起来不刷屏
        pass

    def _resolve(self):
        rel = self.path.split("?")[0].split("#")[0].lstrip("/")
        if not rel or rel.endswith("/"):
            rel += "index.html"
        path = os.path.normpath(os.path.join(ROOT, rel))
        if not path.startswith(ROOT) or not os.path.isfile(path):
            return None
        return path

    def _head(self, path):
        size = os.path.getsize(path)
        ext = os.path.splitext(path)[1].lower()
        self.send_header("Content-Type", TYPES.get(ext, "application/octet-stream"))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")   # 本地预览就是要看见最新文件
        return size

    def do_HEAD(self):
        path = self._resolve()
        if not path:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        size = self._head(path)
        self.send_header("Content-Length", str(size))
        self.end_headers()

    def do_GET(self):
        path = self._resolve()
        if not path:
            body = b"404"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        size = os.path.getsize(path)
        m = RANGE.match(self.headers.get("Range", "") or "")
        if not m:
            self.send_response(200)
            self._head(path)
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return

        start_s, end_s = m.group(1), m.group(2)
        start = int(start_s) if start_s else 0
        end = int(end_s) if end_s else size - 1
        if start >= size or end < start:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % size)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        end = min(end, size - 1)
        length = end - start + 1

        self.send_response(206)
        self._head(path)
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(length))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print("serving %s on http://127.0.0.1:%d/ (Range 支持)" % (ROOT, port))
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
