"""CSV 数据源：从指定 CSV 文件读取生产调用统计。

CSV 表头约定：
  className,methodName,sign,today,week,p99Millis,
  maxExecuteTimeRequired,minExecuteTimeRequired,avgExecuteTimeRequired,
  errorRate,fetchedAt

CSV 文件可能被上游持续重写（duckle-demo-v2 会定时生成新版本），因此
每次请求前检查 mtime，发生变化时全量重载，重载过程用 Lock 保护。
"""

import csv
import os
import threading
from datetime import datetime, timezone

from datasource import DataSource

# 字段类型映射：未列出的字段（className/methodName/sign）保留为 string。
_INT_FIELDS = ("today", "week", "fetchedAt")
_FLOAT_FIELDS = (
    "p99Millis",
    "maxExecuteTimeRequired",
    "minExecuteTimeRequired",
    "avgExecuteTimeRequired",
    "errorRate",
)


class CsvSource(DataSource):
    def __init__(self, path: str):
        self._path = path
        self._lock = threading.Lock()
        self._index: dict[str, dict] = {}
        self._mtime: float | None = None
        # 启动时立刻加载一次，文件不存在直接抛出，让上层尽快失败。
        self._load_locked()

    def get(self, sign: str) -> dict | None:
        self._maybe_reload()
        with self._lock:
            return self._index.get(sign)

    def get_batch(self, signs: list[str]) -> tuple[list[dict], list[str]]:
        self._maybe_reload()
        hits: list[dict] = []
        missed: list[str] = []
        with self._lock:
            snapshot = self._index
            for s in signs:
                row = snapshot.get(s)
                if row is None:
                    missed.append(s)
                else:
                    hits.append(row)
        return hits, missed

    def _maybe_reload(self) -> None:
        try:
            mtime = os.path.getmtime(self._path)
        except FileNotFoundError:
            print(f"[CsvSource] WARN csv file vanished: {self._path}")
            return
        with self._lock:
            if mtime == self._mtime:
                return
            self._load_locked()

    def _load_locked(self) -> None:
        # 调用方负责持锁；构造时也会调用一次。
        try:
            mtime = os.path.getmtime(self._path)
        except FileNotFoundError as e:
            raise FileNotFoundError(f"csv not found: {self._path}") from e

        index: dict[str, dict] = {}
        with open(self._path, newline="", encoding="utf-8") as fh:
            reader = csv.DictReader(fh)
            for lineno, row in enumerate(reader, start=2):  # +1 header, +1 1-based
                sign = (row.get("sign") or "").strip()
                if not sign:
                    print(f"[CsvSource] WARN line {lineno}: empty sign, skipped")
                    continue
                stats = _coerce(row)
                if stats is None:
                    print(f"[CsvSource] WARN line {lineno}: bad row, skipped: {row}")
                    continue
                index[sign] = stats

        self._index = index
        self._mtime = mtime
        fetched_iso = datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat()
        print(f"[CsvSource] loaded {len(index)} rows from {self._path} (mtime={fetched_iso})")


def _coerce(row: dict) -> dict | None:
    """把 CSV 字符串行转换为对外输出的 stats dict。失败返回 None。"""
    out: dict = {}
    for k, v in row.items():
        if v is None or k is None:
            continue
        v = v.strip() if isinstance(v, str) else v
        if k in _INT_FIELDS:
            try:
                out[k] = int(float(v))
            except (TypeError, ValueError):
                return None
        elif k in _FLOAT_FIELDS:
            try:
                out[k] = float(v)
            except (TypeError, ValueError):
                return None
        else:
            out[k] = v
    # 必须有 sign 才算有效行（前面调用方已校验，这里防御性再判一次）
    if not out.get("sign"):
        return None
    return out
