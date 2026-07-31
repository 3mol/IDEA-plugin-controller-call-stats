# -*- coding: utf-8 -*-
"""
数据源抽象层。

设计目的：把 "从哪里拿调用统计" 与 "如何对外提供 HTTP 接口" 解耦。
当前实现方式 1：读取本地 CSV 文件。
后续可新增 DataSource 实现（例如 DbDataSource），在 server.py 中切换即可，
无需改动 HTTP 协议层。

每条记录统一规整为如下字段（与插件端 v2 协议字段保持一致）：
    className, methodName, sign,
    today, week,
    p99Millis, maxExecuteTimeRequired, minExecuteTimeRequired, avgExecuteTimeRequired,
    errorRate, fetchedAt
"""
from __future__ import annotations

import csv
import os
import threading
import time
from abc import ABC, abstractmethod
from typing import Dict, Iterable, List, Optional

# CSV 表头与字段一一对应，固定顺序便于校验
CSV_HEADER = [
    "className", "methodName", "sign",
    "today", "week",
    "p99Millis", "maxExecuteTimeRequired", "minExecuteTimeRequired", "avgExecuteTimeRequired",
    "errorRate", "fetchedAt",
]

# 整数字段：CSV 中可能是浮点字符串，统一强转为 int
_INT_FIELDS = (
    "today", "week",
    "p99Millis", "maxExecuteTimeRequired", "minExecuteTimeRequired", "avgExecuteTimeRequired",
    "fetchedAt",
)
# 浮点字段
_FLOAT_FIELDS = ("errorRate",)


def _to_int(value: str) -> int:
    """容忍 '445' / '445.0' / '' / None 等场景，空值回落 0。"""
    if value is None or value == "":
        return 0
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _to_float(value: str) -> float:
    if value is None or value == "":
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def normalize(raw: Dict[str, str]) -> Dict:
    """把 CSV 读出的原始字符串字典规整为对外的标准记录。"""
    rec = {
        "className": (raw.get("className") or "").strip(),
        "methodName": (raw.get("methodName") or "").strip(),
        "sign": (raw.get("sign") or "").strip(),
    }
    # 兼容缺失 sign 的行：用 className#methodName 兜底
    if not rec["sign"] and rec["className"] and rec["methodName"]:
        rec["sign"] = f"{rec['className']}#{rec['methodName']}"
    for k in _INT_FIELDS:
        rec[k] = _to_int(raw.get(k, ""))
    for k in _FLOAT_FIELDS:
        rec[k] = _to_float(raw.get(k, ""))
    return rec


class DataSource(ABC):
    """数据源接口。实现类负责返回当前最新的全量记录列表。"""

    @abstractmethod
    def load(self) -> List[Dict]:
        """返回全量记录，每条字段结构见模块注释。"""
        raise NotImplementedError


class CsvDataSource(DataSource):
    """
    方式 1：从本地 CSV 文件读取。

    采用 mtime 缓存：同一进程内若文件未修改，直接复用上次解析结果，
    避免每次请求都重读整个文件。线程安全。
    """

    def __init__(self, path: str, encoding: str = "utf-8-sig"):
        # utf-8-sig 可同时兼容带 / 不带 BOM 的 UTF-8 文件
        self._path = path
        self._encoding = encoding
        self._lock = threading.Lock()
        self._cache: Optional[List[Dict]] = None
        self._cache_mtime: Optional[float] = None

    def load(self) -> List[Dict]:
        try:
            mtime = os.path.getmtime(self._path)
        except OSError as e:
            # 文件不存在时清空缓存并返回空列表，避免单次 IO 异常导致进程退出
            print(f"[CsvDataSource] 读取文件状态失败 path={self._path!r} err={e}")
            with self._lock:
                self._cache = None
                self._cache_mtime = None
            return []

        # 未变更则复用缓存
        with self._lock:
            if self._cache is not None and self._cache_mtime == mtime:
                return self._cache

        records = self._read_file()
        with self._lock:
            self._cache = records
            self._cache_mtime = mtime
        return records

    def _read_file(self) -> List[Dict]:
        out: List[Dict] = []
        try:
            with open(self._path, "r", encoding=self._encoding, newline="") as f:
                reader = csv.DictReader(f)
                # 表头完整性校验：缺关键字段时打日志但不抛出，尽量返回可用数据
                if reader.fieldnames:
                    missing = [h for h in CSV_HEADER if h not in reader.fieldnames]
                    if missing:
                        print(f"[CsvDataSource] CSV 缺失字段 {missing}，对应字段将以默认值填充")
                for row in reader:
                    if not row:
                        continue
                    rec = normalize(row)
                    # 完全没有标识信息的行直接丢弃，避免污染 sign 索引
                    if not rec["sign"]:
                        continue
                    out.append(rec)
        except OSError as e:
            print(f"[CsvDataSource] 读取 CSV 失败 path={self._path!r} err={e}")
        return out


def build_index(records: Iterable[Dict]) -> Dict[str, Dict]:
    """以 sign 为 key 建索引；重复 sign 后者覆盖前者（CSV 重新生成时较新行通常在后）。"""
    return {r["sign"]: r for r in records if r.get("sign")}


def empty_stats_for(sign: str, fetched_at: Optional[int] = None) -> Dict:
    """未命中时返回的零值记录，保持与 v2 协议一致的字段集合。"""
    cls, _, mtd = sign.partition("#")
    return {
        "className": cls,
        "methodName": mtd,
        "sign": sign,
        "today": 0,
        "week": 0,
        "p99Millis": 0,
        "maxExecuteTimeRequired": 0,
        "minExecuteTimeRequired": 0,
        "avgExecuteTimeRequired": 0,
        "errorRate": 0.0,
        "fetchedAt": fetched_at if fetched_at is not None else int(time.time() * 1000),
    }