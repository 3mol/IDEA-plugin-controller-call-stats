#!/usr/bin/env python3
"""Prod Call Stats — HTTP 服务。

默认从 MySQL（dev.prod_call_stat LEFT JOIN dev.api_name）读取数据；
设置 DATA_SOURCE=csv 可回退到本地 CSV 文件。

接口：

  GET  /                                       → 简易 Web 页面（列表 + 过滤 + 排序）
  GET  /api/v1/health                          → {"status": "ok", "source": "mysql|csv"}
  GET  /api/v1/call-stats?sign=<sign>&env=...  → 单条 stats（IDE 插件用）
  POST /api/v1/call-stats/batch   {"env","signs"} → results = {sign: stats}
  POST /api/v2/call-stats         {"env","signs"} → results = [stats, ...]
  GET  /api/v2/stats?sign=&api=&sort=p99&dir=desc&limit=&offset=
                                        → 列表 + 过滤 + 排序（Web 页面消费）

stats 字段：className, methodName, sign, today, week, p99Millis,
maxExecuteTimeRequired, minExecuteTimeRequired, avgExecuteTimeRequired,
errorRate, fetchedAt, apiOperationValue。

启动（MySQL）：
  DATA_SOURCE=mysql MYSQL_PASSWORD=123456 python3 server.py

启动（CSV，向后兼容）：
  DATA_SOURCE=csv CSV_PATH=/path/to/接口调用次数分析.csv python3 server.py
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# 允许 `python3 server.py` 直接运行（脚本相对路径导入同目录模块）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from datasource import DataSource  # noqa: E402

DEFAULT_CSV_PATH = "/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv"
DEFAULT_PORT = 8089
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")


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
        print(f"[resp] {self.command} {self.path} -> status={status}")

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

        if path == "/":
            self._serve_index_html()
            return

        if path == "/api/v1/health":
            self._send(200, {"status": "ok", "source": self.server.source_kind})  # type: ignore[attr-defined]
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

        if path == "/api/v2/stats":
            if not self._check_token():
                self._send(401, {"error": "invalid token"})
                return
            self._handle_v2_stats(qs)
            return

        self._send(404, {"error": f"unknown path {path}"})

    def _handle_v2_stats(self, qs: dict) -> None:
        """GET /api/v2/stats：列表 + 过滤 + 排序，供 Web 页面消费。

        参数：
          sign   按 p.sign 模糊匹配（LIKE %kw%）
          api    按 a.apiOperationValue 模糊匹配
          sort   排序字段（p99 | today | week | errorRate | fetchedAt | ...，见 db_source._SORT_COLUMNS）
          dir    asc | desc，默认 desc
          limit  默认 200，最大 1000
          offset 默认 0
        """
        sign_kw = (qs.get("sign") or [""])[0].strip()
        api_kw = (qs.get("api") or [""])[0].strip()
        sort = (qs.get("sort") or ["p99"])[0].strip() or "p99"
        direction = (qs.get("dir") or ["desc"])[0].strip().lower() or "desc"
        try:
            limit = max(1, min(1000, int((qs.get("limit") or ["200"])[0])))
            offset = max(0, int((qs.get("offset") or ["0"])[0]))
        except ValueError:
            self._send(400, {"error": "limit/offset must be integers"})
            return

        # DbSource 才有 search；CSV 模式回 501 让前端展示提示
        search_fn = getattr(self._source, "search", None)
        if search_fn is None:
            self._send(501, {"error": "list endpoint requires DATA_SOURCE=mysql"})
            return

        rows = search_fn(
            sign_kw=sign_kw,
            api_kw=api_kw,
            sort=sort,
            direction=direction,
            limit=limit,
            offset=offset,
        )
        self._send(200, {
            "results": rows,
            "count": len(rows),
            "query": {"sign": sign_kw, "api": api_kw, "sort": sort, "dir": direction,
                      "limit": limit, "offset": offset},
        })

    def _serve_index_html(self) -> None:
        index_path = os.path.join(WEB_DIR, "index.html")
        if not os.path.exists(index_path):
            self._send(404, {"error": "index.html missing"})
            return
        try:
            with open(index_path, "rb") as fh:
                data = fh.read()
        except OSError as e:
            self._send(500, {"error": f"read index.html failed: {e}"})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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
    """携带共享状态的服务器：数据源实例 + 可选 token + 数据源类型。"""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, handler, data_source: DataSource, token: str, source_kind: str):
        super().__init__(addr, handler)
        self.data_source = data_source
        self.token = token
        self.source_kind = source_kind


def _build_source() -> tuple[DataSource, str]:
    """根据 DATA_SOURCE 环境变量选择数据源；默认 mysql。"""
    kind = os.environ.get("DATA_SOURCE", "mysql").strip().lower()
    if kind == "mysql":
        from db_source import DbSource
        return DbSource.from_env(), "mysql"
    if kind == "csv":
        from csv_source import CsvSource
        csv_path = os.environ.get("CSV_PATH", DEFAULT_CSV_PATH)
        if not os.path.exists(csv_path):
            print(f"[server] FATAL csv not found: {csv_path}", file=sys.stderr)
            sys.exit(1)
        return CsvSource(csv_path), "csv"
    print(f"[server] FATAL unknown DATA_SOURCE: {kind!r} (expected 'mysql' or 'csv')", file=sys.stderr)
    sys.exit(1)


def main():
    port = int(os.environ.get("PORT", str(DEFAULT_PORT)))
    token = os.environ.get("TOKEN", "")

    source, source_kind = _build_source()

    server = Server(("0.0.0.0", port), Handler, source, token, source_kind)
    print("Prod Call Stats server")
    print(f"  listening : http://0.0.0.0:{port}")
    print(f"  source    : {source_kind}")
    if source_kind == "mysql":
        # 仅打印非敏感连接信息
        print(f"  mysql     : {os.environ.get('MYSQL_HOST', 'localhost')}:{os.environ.get('MYSQL_PORT', '3306')}/{os.environ.get('MYSQL_DB', 'dev')}")
    else:
        print(f"  csv path  : {os.environ.get('CSV_PATH', DEFAULT_CSV_PATH)}")
    print(f"  token     : {'required (X-Api-Token: ' + token + ')' if token else 'not enforced'}")
    print(f"  web ui    : http://localhost:{port}/")
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
