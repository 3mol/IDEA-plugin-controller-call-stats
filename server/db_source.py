"""MySQL 数据源：dev.prod_call_stat LEFT JOIN dev.api_name。

prod_call_stat 字段：
  className, methodName, sign, today, week, p99Millis,
  maxExecuteTimeRequired, minExecuteTimeRequired, avgExecuteTimeRequired,
  errorRate, fetchedAt
api_name 字段：
  className, methodName, sign, apiOperationValue

两表通过 sign 关联；LEFT JOIN 保证未配置中文名的接口也能被检索到。
注意：api_name 中同一 sign 可能存在多行（历史配置残留 / 多版本接口共用 sign），
直接 JOIN 会让 prod_call_stat 一行被复制成多行。因此在子查询里按 sign 分组、
取 MAX(apiOperationValue)，保证每个 sign 至多一条，避免结果集重复。

连接参数通过环境变量配置（见 from_env），实现使用线程局部连接，
遇到断连自动重连重试一次。
"""

import os
import threading

import pymysql

from datasource import DataSource

# 列表查询允许的排序字段白名单；value 是 SQL 中的列引用，
# 用户传入的 sort 参数必须命中 key，绝不直接拼到 SQL 里。
_SORT_COLUMNS = {
    "sign": "p.sign",
    "apiName": "a.apiOperationValue",
    "today": "p.today",
    "week": "p.week",
    "p99": "p.p99Millis",
    "max": "p.maxExecuteTimeRequired",
    "min": "p.minExecuteTimeRequired",
    "avg": "p.avgExecuteTimeRequired",
    "errorRate": "p.errorRate",
    "fetchedAt": "p.fetchedAt",
}

_DEFAULT_SORT = "p99"


class DbSource(DataSource):
    def __init__(
        self,
        host: str = "localhost",
        port: int = 3306,
        user: str = "root",
        password: str = "",
        db: str = "dev",
    ):
        self._cfg = dict(
            host=host,
            port=port,
            user=user,
            password=password,
            db=db,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=True,
        )
        self._tls = threading.local()

    @classmethod
    def from_env(cls) -> "DbSource":
        return cls(
            host=os.environ.get("MYSQL_HOST", "localhost"),
            port=int(os.environ.get("MYSQL_PORT", "3306")),
            user=os.environ.get("MYSQL_USER", "root"),
            password=os.environ.get("MYSQL_PASSWORD", ""),
            db=os.environ.get("MYSQL_DB", "dev"),
        )

    # ---------- connection ----------
    def _conn(self):
        conn = getattr(self._tls, "conn", None)
        if conn is None or not conn.open:
            conn = pymysql.connect(**self._cfg)
            self._tls.conn = conn
        return conn

    def _reset_conn(self):
        conn = getattr(self._tls, "conn", None)
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass
        self._tls.conn = None

    def _query(self, sql: str, args: tuple) -> list[dict]:
        # 第一次失败若是连接类错误，重置连接重试一次；仍失败再抛。
        for attempt in range(2):
            try:
                with self._conn().cursor() as cur:
                    cur.execute(sql, args)
                    return list(cur.fetchall())
            except (pymysql.OperationalError, pymysql.InterfaceError) as e:
                if attempt == 0:
                    self._reset_conn()
                    continue
                raise

    # ---------- DataSource 接口 ----------
    def get(self, sign: str) -> dict | None:
        rows = self._query(
            """SELECT p.className, p.methodName, p.sign,
                      p.today, p.week, p.p99Millis,
                      p.maxExecuteTimeRequired, p.minExecuteTimeRequired,
                      p.avgExecuteTimeRequired, p.errorRate, p.fetchedAt,
                      a.apiOperationValue
               FROM prod_call_stat p
               LEFT JOIN (
                   SELECT sign, MAX(apiOperationValue) AS apiOperationValue
                   FROM api_name
                   GROUP BY sign
               ) a ON a.sign = p.sign
               WHERE p.sign = %s
               LIMIT 1""",
            (sign,),
        )
        return rows[0] if rows else None

    def get_batch(self, signs: list[str]) -> tuple[list[dict], list[str]]:
        if not signs:
            return [], []
        placeholders = ",".join(["%s"] * len(signs))
        rows = self._query(
            f"""SELECT p.className, p.methodName, p.sign,
                       p.today, p.week, p.p99Millis,
                       p.maxExecuteTimeRequired, p.minExecuteTimeRequired,
                       p.avgExecuteTimeRequired, p.errorRate, p.fetchedAt,
                       a.apiOperationValue
                FROM prod_call_stat p
                LEFT JOIN (
                    SELECT sign, MAX(apiOperationValue) AS apiOperationValue
                    FROM api_name
                    GROUP BY sign
                ) a ON a.sign = p.sign
                WHERE p.sign IN ({placeholders})""",
            tuple(signs),
        )
        # 保留输入顺序；未命中的 sign 进 missed
        hit_by_sign = {r["sign"]: r for r in rows}
        hits = [hit_by_sign[s] for s in signs if s in hit_by_sign]
        missed = [s for s in signs if s not in hit_by_sign]
        return hits, missed

    # ---------- web 列表查询 ----------
    def search(
        self,
        sign_kw: str = "",
        api_kw: str = "",
        sort: str = _DEFAULT_SORT,
        direction: str = "desc",
        limit: int = 200,
        offset: int = 0,
    ) -> list[dict]:
        sort_col = _SORT_COLUMNS.get(sort, _SORT_COLUMNS[_DEFAULT_SORT])
        direction = "asc" if (direction or "").lower() == "asc" else "desc"

        where_parts: list[str] = []
        args: list = []
        if sign_kw:
            where_parts.append("p.sign LIKE %s")
            args.append(f"%{sign_kw}%")
        if api_kw:
            where_parts.append("a.apiOperationValue LIKE %s")
            args.append(f"%{api_kw}%")
        where_sql = ("WHERE " + " AND ".join(where_parts)) if where_parts else ""

        sql = f"""SELECT p.className, p.methodName, p.sign,
                         p.today, p.week, p.p99Millis,
                         p.maxExecuteTimeRequired, p.minExecuteTimeRequired,
                         p.avgExecuteTimeRequired, p.errorRate, p.fetchedAt,
                         a.apiOperationValue
                  FROM prod_call_stat p
                  LEFT JOIN (
                      SELECT sign, MAX(apiOperationValue) AS apiOperationValue
                      FROM api_name
                      GROUP BY sign
                  ) a ON a.sign = p.sign
                  {where_sql}
                  ORDER BY {sort_col} {direction}
                  LIMIT %s OFFSET %s"""
        args.extend([limit, offset])
        return self._query(sql, tuple(args))
