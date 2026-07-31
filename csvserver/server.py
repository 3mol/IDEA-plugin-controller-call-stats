# -*- coding: utf-8 -*-
"""
ProdCallStats CSV Server

读取本地 CSV（默认：接口调用次数分析.csv），按 v1 / v2 协议对外提供调用统计。

对应插件端 GatewayClient 的端点：
    GET  /api/v1/health                                  健康检查
    GET  /api/v1/call-stats?sign=<sign>&env=<env>        单条查询（v1）
    POST /api/v1/call-stats/batch                        批量查询（v1，results 为 map）
    POST /api/v2/call-stats                              批量查询（v2，results 为 array）

数据源可通过 --source 切换：当前支持 csv，后续可在此扩展（如 db）。
"""
from __future__ import annotations

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict
from urllib.parse import urlparse, parse_qs

from data_source import (
    DataSource,
    CsvDataSource,
    build_index,
    empty_stats_for,
)

# 默认 CSV 路径：与 duckle-demo-v2 输出文件保持一致
DEFAULT_CSV_PATH = "/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8089

# v1 map value 仅包含 stats 字段（与 parseStats 字段约束保持一致）
_STATS_ONLY_KEYS = (
    "today", "week",
    "p99Millis", "maxExecuteTimeRequired", "minExecuteTimeRequired", "avgExecuteTimeRequired",
    "errorRate", "fetchedAt",
)


def _stats_only(rec: Dict) -> Dict:
    """v1 map value：仅保留 stats 字段。"""
    return {k: rec.get(k, 0) for k in _STATS_ONLY_KEYS}


def build_data_source(args: argparse.Namespace) -> DataSource:
    """根据命令行参数构造数据源，便于后续扩展。"""
    if args.source == "csv":
        return CsvDataSource(args.csv_path, encoding=args.csv_encoding)
    raise ValueError(f"未知数据源: {args.source}")


class Handler(BaseHTTPRequestHandler):
    # 数据源由 main 在启动时注入；关闭默认访问日志，避免刷屏
    data_source: DataSource = None  # type: ignore[assignment]

    def log_message(self, fmt, *args):
        return

    # ---------- GET ----------
    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path == "/api/v1/health":
            self._send_json(200, {"status": "ok"})
            return

        if parsed.path == "/api/v1/call-stats":
            q = parse_qs(parsed.query)
            sign = (q.get("sign") or [""])[0]
            env = (q.get("env") or [""])[0]
            index = build_index(self.data_source.load())
            entry = index.get(sign)
            hit = entry is not None
            # 未命中不补零（保持 v1 单查语义清晰），如需补零可改为 empty_stats_for(sign)
            stats = _stats_only(entry) if entry else _stats_only(empty_stats_for(sign))
            print(f"[GET v1] sign={sign!r} env={env!r} hit={hit}")
            self._send_json(200, stats)
            return

        self._send_json(404, {"message": "not found"})

    # ---------- POST ----------
    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {}
        signs = body.get("signs") or []
        env = body.get("env", "")

        # v1: results 为 map（sign -> stats）
        if parsed.path == "/api/v1/call-stats/batch":
            print(f"[POST v1] env={env!r} signs_count={len(signs)}")
            index = build_index(self.data_source.load())
            results = {}
            for sign in signs:
                entry = index.get(sign)
                results[sign] = _stats_only(entry) if entry else _stats_only(empty_stats_for(sign))
            self._send_json(200, {"results": results})
            return

        # v2: results 为 array，每项含 className / methodName / sign + stats
        if parsed.path == "/api/v2/call-stats":
            print(f"[POST v2] env={env!r} signs_count={len(signs)}")
            index = build_index(self.data_source.load())
            requested = set(signs)
            results = []
            # 仅返回命中的记录，保持 CSV 中真实的统计值
            for sign, rec in index.items():
                if sign in requested:
                    results.append(rec)
            # 未命中的 sign 补零值项，方便客户端展示空 badge
            hit_signs = {r["sign"] for r in results}
            for sign in signs:
                if sign not in hit_signs:
                    results.append(empty_stats_for(sign))
            self._send_json(200, {"results": results})
            return

        self._send_json(404, {"message": "not found"})

    # ---------- helpers ----------
    def _send_json(self, status: int, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        # 插件端会带 X-Api-Token，此处不做鉴权，仅 echo 便于联调
        token = self.headers.get("X-Api-Token")
        if token:
            self.send_header("X-Echo-Api-Token", token)
        self.end_headers()
        self.wfile.write(data)


def parse_args():
    p = argparse.ArgumentParser(description="ProdCallStats CSV Server")
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument(
        "--source",
        default="csv",
        choices=["csv"],
        help="数据源类型，当前仅支持 csv，后续可扩展 db 等",
    )
    p.add_argument("--csv-path", default=DEFAULT_CSV_PATH, help="CSV 文件路径（方式 1）")
    p.add_argument(
        "--csv-encoding",
        default="utf-8-sig",
        help="CSV 文件编码，默认 utf-8-sig 以兼容 BOM",
    )
    return p.parse_args()


def main():
    args = parse_args()
    Handler.data_source = build_data_source(args)

    # 启动时先加载一次，便于及早暴露文件路径 / 编码问题
    initial = Handler.data_source.load()
    print(f"[PCS csv-server] data source: {args.source}")
    print(f"[PCS csv-server] csv path: {args.csv_path}")
    print(f"[PCS csv-server] initial records: {len(initial)}")

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[PCS csv-server] listening on http://{args.host}:{args.port}")
    print("[PCS csv-server] endpoints:")
    print("  GET  /api/v1/health")
    print("  GET  /api/v1/call-stats?sign=<className>%23<methodName>&env=prod")
    print('  POST /api/v1/call-stats/batch   body={"env":...,"signs":[...]}   (v1, results: map)')
    print('  POST /api/v2/call-stats         body={"env":...,"signs":[...]}   (v2, results: array)')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[PCS csv-server] shutting down")
        server.shutdown()


if __name__ == "__main__":
    main()