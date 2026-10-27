"""带 Range 支持的极简静态文件服务器。

python -m http.server 不支持 Range（返回 200 + 整个文件），ExoPlayer 因此无法
拖动/续播——测续播时会被误判成"没生效"。这个脚本只做一件事：正确回 206。
"""
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _resolve(self):
        path = self.path.split("?")[0]
        path = os.path.normpath(os.path.join(ROOT, path.lstrip("/")))
        if not path.startswith(ROOT) or not os.path.isfile(path):
            return None
        return path

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only):
        path = self._resolve()
        if path is None:
            self.send_error(404)
            return

        size = os.path.getsize(path)
        start, end = 0, size - 1
        status = 200
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.end_headers()
                    return
                status = 206

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head_only:
            return
        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(256 * 1024, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(chunk)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    # 默认只听本机（配合 adb reverse 给模拟器用）。要让 docker 容器访问，
    # 传 0.0.0.0：python serve-range.py 8899 0.0.0.0
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    print(f"serving {ROOT} on http://{host}:{port}", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()
