#!/usr/bin/env python3
"""Prod Call Stats — CSV 数据源 HTTP 服务。

接口契约与 mockserver/mock_server.py 一致，可直接被插件
GatewayClient.kt 调用：

  GET  /api/v1/health
  GET  /api/v1/call-stats?sign=<sign>&env=<prod|pre>
  POST /api/v1/call-stats/batch   {"env": "...", "signs": ["..."]}    results = {sign: stats}
  POST /api/v2/call-stats         {"env": "...", "signs": ["..."]}    results = [stats, ...]

stats 字段：className, methodName, sign, today, week, p99Millis,
maxExecuteTimeRequired, minExecuteTimeRequired, avgExecuteTimeRequired,
errorRate, fetchedAt。

启动：
  CSV_PATH=/path/to/接口调用次数分析.csv python3 server.py
  PORT=8089 TOKEN=secret python3 server.py
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# 允许 `python3 server.py` 直接运行（脚本相对路径导入同目录模块）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from csv_source import CsvSource  # noqa: E402
from datasource import DataSource  # noqa: E402

DEFAULT_CSV_PATH = "/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv"
DEFAULT_PORT = 8089


def _placeholder(sign: str) -> dict:
    """未命中 sign 时返回的占位 stats：sign 填入查询值，className/methodName 从最后一个 # 切分，数值字段统一 -1。"""
    parts = sign.rsplit("#", 1)
    className = parts[0]
    methodName = parts[1] if len(parts) > 1 else ""
    return {
        "className": className,
        "methodName": methodName,
        "sign": sign,
        "today": -1,
        "week": -1,
        "p99Millis": -1,
        "maxExecuteTimeRequired": -1,
        "minExecuteTimeRequired": -1,
        "avgExecuteTimeRequired": -1,
        "errorRate": -1,
        "fetchedAt": 0,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "ProdCallStatsCSV/1.0"

    # ThreadingHTTPServer 把 handler 实例化每次请求，所以 dataSource 通过
    # server 属性共享（见 main 里的 ThreadingHTTPServer 子类）。
    @property
    def _source(self) -> DataSource:
        return self.server.data_source  # type: ignore[attr-defined]

    def log_message(self, fmt, *args):
        ts = time.strftime("%H:%M:%S")
        print(f"[{ts}] {self.address_string()} - {fmt % args}")

    def _send(self, status: int, body: dict | list | str):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print(f"[resp] {self.command} {self.path} -> status={status} body={data.decode('utf-8')}")

    def _check_token(self) -> bool:
        expected = self.server.token  # type: ignore[attr-defined]
        if not expected:
            return True
        return self.headers.get("X-Api-Token") == expected

    # ---------- GET ----------
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        print(f"[req ] GET {path} query={qs}")

        if path == "/api/v1/health":
            self._send(200, {"status": "ok"})
            return

        if path == "/api/v1/call-stats":
            if not self._check_token():
                self._send(401, {"error": "invalid token"})
                return
            sign = (qs.get("sign") or [""])[0]
            if not sign:
                self._send(400, {"error": "missing sign"})
                return
            stats = self._source.get(sign)
            if stats is None:
                self._send(200, _placeholder(sign))
                return
            self._send(200, stats)
            return

        self._send(404, {"error": f"unknown path {path}"})

    # ---------- POST ----------
    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path not in ("/api/v1/call-stats/batch", "/api/v2/call-stats"):
            self._send(404, {"error": f"unknown path {path}"})
            return

        if not self._check_token():
            self._send(401, {"error": "invalid token"})
            return

        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._send(400, {"error": "invalid json"})
            return
        print(f"[req ] POST {path} body={json.dumps(payload, ensure_ascii=False)}")

        signs = payload.get("signs") or []
        if not isinstance(signs, list):
            self._send(400, {"error": "signs must be an array"})
            return

        hits, missed = self._source.get_batch(signs)
        # 未命中的 sign 用占位对象补齐，保留输入顺序
        hit_by_sign = {h["sign"]: h for h in hits}
        ordered = [hit_by_sign.get(s, _placeholder(s)) for s in signs]

        if path == "/api/v2/call-stats":
            # v2: results 是数组
            self._send(200, {"results": ordered, "missed": missed})
        else:
            # v1: results 是 {sign: stats}
            self._send(200, {
                "results": {h["sign"]: h for h in ordered},
                "missed": missed,
            })


class Server(ThreadingHTTPServer):
    """携带共享状态的服务器：数据源实例 + 可选 token。"""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, handler, data_source: DataSource, token: str):
        super().__init__(addr, handler)
        self.data_source = data_source
        self.token = token


def main():
    csv_path = os.environ.get("CSV_PATH", DEFAULT_CSV_PATH)
    port = int(os.environ.get("PORT", str(DEFAULT_PORT)))
    token = os.environ.get("TOKEN", "")

    if not os.path.exists(csv_path):
        print(f"[server] FATAL csv not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    try:
        source = CsvSource(csv_path)
    except FileNotFoundError as e:
        print(f"[server] FATAL {e}", file=sys.stderr)
        sys.exit(1)

    server = Server(("0.0.0.0", port), Handler, source, token)
    print("Prod Call Stats CSV server")
    print(f"  listening : http://0.0.0.0:{port}")
    print(f"  csv path  : {csv_path}")
    print(f"  token     : {'required (X-Api-Token: ' + token + ')' if token else 'not enforced'}")
    print(f"  rows      : {len(source._index)}")  # noqa: SLF001
    print()
    print("Plugin settings:")
    print(f"  Use mock data : OFF")
    print(f"  Gateway URL   : http://localhost:{port}")
    print(f"  API version   : v2")
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
