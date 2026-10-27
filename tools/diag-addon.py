#!/usr/bin/env python3
"""最小化 Stremio addon，用于隔离诊断。

只返回两条固定的 stream：
  1. Stremio 官方文档里的 Big Buck Bunny MP4（HTTPS，格式确定可用）
  2. 本机 seedstrem 的一条真实片源 URL（HTTP + MKV）

用途：在 Stremio 里装这个 addon 后打开任意电影详情页。
  - 两条都显示  -> addon 机制正常，问题在原 addon 的返回内容或安装方式
  - 只显示第 1 条 -> 本机 HTTP 流被客户端拒绝（需要 notWebReady 或 HTTPS）
  - 一条都不显示 -> addon 安装/加载有问题，与片源无关
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8090

MANIFEST = {
    "id": "org.tvpt.diag",
    "version": "1.0.0",
    "name": "tvpt diag",
    "description": "Diagnostic addon: two known streams, no search.",
    "resources": ["stream"],
    "types": ["movie", "series"],
    "catalogs": [],
    "idPrefixes": ["tt"],
}

BBB = {
    "name": "DIAG 1: 官方示例 MP4 (HTTPS)",
    "title": "Big Buck Bunny, 官方 addon 文档里的示例流",
    "url": "http://distribution.bbb3d.renderfarming.net/video/mp4/bbb_sunflower_1080p_30fps_normal.mp4",
}

LOCAL = {
    "name": "DIAG 2: 本机 seedstrem (HTTP+MKV)",
    "title": "本机服务返回的真实片源, 若这条不显示说明 HTTP 流被拒",
    "url": "http://192.168.1.10:8081/stremio/stream/movie/tt27419466.json",
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/manifest.json":
            self._send(MANIFEST)
        elif p.startswith("/stream/"):
            self._send({"streams": [BBB, LOCAL]})
        else:
            self.send_error(404)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    print("diag addon on :%d" % PORT, flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
