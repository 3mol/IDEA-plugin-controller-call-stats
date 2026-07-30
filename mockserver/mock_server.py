#!/usr/bin/env python3
"""
Prod Call Stats — mock gateway.

Implements the contracts used by the IDEA plugin:
  GET  /api/v1/health
  GET  /api/v1/call-stats?sign=<sign>&env=<prod|pre>
  POST /api/v1/call-stats/batch     {"env": "...", "signs": ["..."]}
  POST /api/v2/call-stats           {"env": "...", "signs": ["..."]}

Stats are random but stable within a 60s window — same sign + minute
always produces the same numbers, matching the plugin's built-in mock
generator. `com.codo.tech.creeks.allocation.api.BookingController#create`
gets numbers that comfortably exercise the green/orange/red thresholds.

Run:
  python3 mock_server.py                 # default port 8088
  PORT=9000 python3 mock_server.py
  TOKEN=secret python3 mock_server.py    # enforce X-Api-Token check
"""

import hashlib
import json
import random
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

SIGN_DEMO = "com.codo.tech.creeks.allocation.api.BookingController#create"


def seed_for(sign: str, salt: int) -> int:
    h = hashlib.md5(f"{sign}|{salt}".encode("utf-8")).hexdigest()
    return int(h[:8], 16)


def stats_for(sign: str, now_ms: int) -> dict:
    minute = now_ms // 60_000
    rng = random.Random(seed_for(sign, minute))

    if sign == SIGN_DEMO:
        today = rng.randint(8_000, 20_000)
        week = today * rng.randint(6, 10)
        p99 = rng.randint(180, 260)         # green (<500)
        err = round(rng.uniform(0.0005, 0.004), 4)
    else:
        today = rng.randint(50, 50_000)
        week = today * rng.randint(6, 12)
        p99 = rng.randint(40, 1_500)
        err = round(rng.uniform(0.0, 0.03), 4)

    p99 = max(p99, 5)
    max_t = p99 + rng.randint(10, 500)
    min_t = rng.randint(2, 60)
    avg = rng.randint(min_t, max(p99, min_t + 1))

    cls, _, mtd = sign.partition("#")
    return {
        "className": cls,
        "methodName": mtd,
        "sign": sign,
        "today": today,
        "week": week,
        "p99Millis": p99,
        "maxExecuteTimeRequired": max_t,
        "minExecuteTimeRequired": min_t,
        "avgExecuteTimeRequired": avg,
        "errorRate": err,
        "fetchedAt": now_ms,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "ProdCallStatsMock/1.0"

    def log_message(self, fmt, *args):
        ts = time.strftime("%H:%M:%S")
        print(f"[{ts}] {self.address_string()} - {fmt % args}")

    def _send(self, status: int, body: dict | list | str):
        data = json.dumps(body).encode("utf-8") if not isinstance(body, bytes) else body
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _check_token(self) -> bool:
        expected = TOKEN
        if not expected:
            return True
        return self.headers.get("X-Api-Token") == expected

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

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
            self._send(200, stats_for(sign, int(time.time() * 1000)))
            return

        self._send(404, {"error": f"unknown path {path}"})

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

        signs = payload.get("signs") or []
        now_ms = int(time.time() * 1000)
        results = [stats_for(s, now_ms) for s in signs]
        missed = [s for s in signs if not s]

        if path == "/api/v2/call-stats":
            self._send(200, {"results": results, "missed": missed})
        else:
            # v1: results is a map {sign: stats}
            self._send(200, {
                "results": {r["sign"]: r for r in results},
                "missed": missed,
            })


TOKEN = ""
PORT = 8088


def main():
    import os
    global TOKEN, PORT
    TOKEN = os.environ.get("TOKEN", "")
    PORT = int(os.environ.get("PORT", "8088"))

    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Prod Call Stats mock server")
    print(f"  listening : http://0.0.0.0:{PORT}")
    print(f"  token     : {'required (X-Api-Token: ' + TOKEN + ')' if TOKEN else 'not enforced'}")
    print(f"  demo sign : {SIGN_DEMO}")
    print()
    print("Plugin settings:")
    print(f"  Use mock data : OFF")
    print(f"  Gateway URL   : http://localhost:{PORT}")
    print(f"  API version   : v2")
    if TOKEN:
        print(f"  API Token     : {TOKEN}")
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")
        server.shutdown()


if __name__ == "__main__":
    main()
