# Prod Call Stats CSV Server

读取 `接口调用次数分析.csv` 并按 v2 契约对外提供 HTTP 接口，供 IntelliJ 插件 `prod-call-stats` 调用。与 `mockserver/mock_server.py` 的区别：本服务读取**真实 CSV**，mock 服务生成**随机模拟数据**。

## 依赖

仅使用 Python 3.10+ 标准库（`http.server` / `csv` / `threading`），无需 `pip install`。

## 启动

```bash
cd csvserver
CSV_PATH="/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv" \
PORT=8089 \
python3 server.py
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `CSV_PATH` | `/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv` | CSV 文件路径 |
| `PORT` | `8089` | 监听端口 |
| `TOKEN` | （空） | 非空时强制校验 `X-Api-Token` 请求头 |

启动时立即全量加载 CSV，请求时检查 `mtime`，CSV 被上游重写后下一次请求会自动重载。

## 接口

```
GET  /api/v1/health                              → {"status": "ok"}
GET  /api/v1/call-stats?sign=<sign>&env=<env>    → 单条 stats 对象（404 表示未命中）
POST /api/v1/call-stats/batch   {"env","signs"}  → {"results": {sign: stats}, "missed": [...]}
POST /api/v2/call-stats         {"env","signs"}  → {"results": [stats, ...],   "missed": [...]}
```

`stats` 字段（与 CSV 表头一致）：

```json
{
  "className": "...",
  "methodName": "...",
  "sign": "...",
  "today": 12345,
  "week": 98000,
  "p99Millis": 120.0,
  "maxExecuteTimeRequired": 350.0,
  "minExecuteTimeRequired": 12.0,
  "avgExecuteTimeRequired": 95.0,
  "errorRate": 0.0001,
  "fetchedAt": 1722200000000
}
```

## curl 验证

```bash
# 1. health
curl http://localhost:8089/api/v1/health

# 2. 单条查询（注意 # 要 URL-encode 为 %23）
curl 'http://localhost:8089/api/v1/call-stats?sign=com.codo.tech.creeks.inventory.remoteapi.job.JobController%23pushOutboundOrderWms&env=prod'

# 3. v2 批量查询
curl -X POST http://localhost:8089/api/v2/call-stats \
  -H 'Content-Type: application/json' \
  -d '{"env":"prod","signs":["com.codo.tech.creeks.inventory.remoteapi.job.JobController#pushOutboundOrderWms","nonexistent.X#y"]}'
```

## 接入插件

IntelliJ → Settings → Tools → Prod Call Stats：

- Use mock data: **OFF**
- Gateway URL: `http://localhost:8089`
- API version: `v2`
- API Token: 服务端启用 TOKEN 时填，否则留空

## 文件结构

```
csvserver/
├── server.py        # HTTP 服务主入口
├── datasource.py    # DataSource 抽象基类
├── csv_source.py    # CSV 数据源实现（mtime 懒重载）
└── README.md
```

## 扩展：接入数据库等其它数据源

后续如需从数据库读取，新增一个继承 `DataSource` 的实现类（如 `db_source.py::DbSource`），在 `server.py:main` 中根据环境变量选择实例化哪个：

```python
if os.environ.get("DATA_SOURCE") == "mysql":
    from db_source import DbSource
    source = DbSource(dsn=os.environ["MYSQL_DSN"])
else:
    source = CsvSource(csv_path)
```

`DataSource` 接口只有两个方法：

- `get(sign) -> dict | None`
- `get_batch(signs) -> (hits: list[dict], missed: list[str])`
