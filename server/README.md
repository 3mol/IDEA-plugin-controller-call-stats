# Prod Call Stats Server

按 v2 契约对外提供 HTTP 接口，供 IntelliJ 插件 `prod-call-stats` 调用，并提供一个简易 Web 页面浏览全表。

默认数据源是 **MySQL**（`dev.prod_call_stat` LEFT JOIN `dev.api_name`）；设置 `DATA_SOURCE=csv` 可回退到本地 CSV（向后兼容旧用法）。

## 依赖

- Python 3.10+
- MySQL 模式：`pip install pymysql`（其它模式仅用标准库）

## 启动

```bash
cd server

# MySQL（默认）
DATA_SOURCE=mysql MYSQL_PASSWORD=123456 PORT=8089 python3 server.py

# CSV（向后兼容）
DATA_SOURCE=csv \
CSV_PATH="/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv" \
PORT=8089 python3 server.py
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DATA_SOURCE` | `mysql` | `mysql` 或 `csv` |
| `MYSQL_HOST` | `localhost` | MySQL 主机 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_USER` | `root` | MySQL 用户 |
| `MYSQL_PASSWORD` | （空） | MySQL 密码 |
| `MYSQL_DB` | `dev` | 数据库名 |
| `CSV_PATH` | `.../接口调用次数分析.csv` | CSV 模式下的文件路径 |
| `PORT` | `8089` | 监听端口 |
| `TOKEN` | （空） | 非空时强制校验 `X-Api-Token` 请求头 |

## 数据库表结构

两张表通过 `sign` LEFT JOIN，未配置中文名的接口仍可被检索到。

```
prod_call_stat:
  className, methodName, sign, today, week, p99Millis,
  maxExecuteTimeRequired, minExecuteTimeRequired, avgExecuteTimeRequired,
  errorRate, fetchedAt

api_name:
  className, methodName, sign, apiOperationValue   -- 接口中文名/描述
```

## 接口

```
GET  /                                       → 简易 Web 页面（列表 + 过滤 + 排序）
GET  /api/v1/health                          → {"status": "ok", "source": "mysql|csv"}
GET  /api/v1/call-stats?sign=<sign>&env=...  → 单条 stats（IDE 插件用）
POST /api/v1/call-stats/batch   {"env","signs"} → {"results": {sign: stats}, "missed": [...]}
POST /api/v2/call-stats         {"env","signs"} → {"results": [stats, ...], "missed": [...]}
GET  /api/v2/stats?sign=&api=&sort=p99&dir=desc&limit=&offset=
                                              → 列表 + 过滤 + 排序（Web 页面消费）
```

`stats` 字段：

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
  "fetchedAt": 1722200000000,
  "apiOperationValue": "查询仓库列表"
}
```

### `/api/v2/stats` 参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `sign` | `p.sign` LIKE 模糊匹配 | 空 |
| `api` | `a.apiOperationValue` LIKE 模糊匹配（即页面上的 apiNameValue） | 空 |
| `sort` | `sign` / `apiName` / `today` / `week` / `p99` / `min` / `avg` / `max` / `errorRate` / `fetchedAt` | `p99` |
| `dir` | `asc` / `desc` | `desc` |
| `limit` | 1-1000 | 200 |
| `offset` | >= 0 | 0 |

`sort` 走白名单映射到实际列名，不会直接拼到 SQL 里。

## Web 页面

访问 `http://localhost:8089/`：

- 顶部输入 `sign`（模糊匹配 className#methodName）和 `apiNameValue`（接口中文名模糊匹配）
- 默认按 P99 列降序排列；点击表头切换排序字段，再次点击切换升降序
- P99 ≥ 2000 红色、≥ 500 橙色，便于一眼看到尾延迟严重的接口

## curl 验证

```bash
# health
curl http://localhost:8089/api/v1/health

# v2 批量查询（IDE 插件用）
curl -X POST http://localhost:8089/api/v2/call-stats \
  -H 'Content-Type: application/json' \
  -d '{"env":"prod","signs":["com.codo.tech.creeks.inventory.remoteapi.job.JobController#pushOutboundOrderWms","nonexistent.X#y"]}'

# 列表查询：模糊匹配 BookingController，按 p99 降序
curl 'http://localhost:8089/api/v2/stats?sign=BookingController&sort=p99&dir=desc&limit=10'

# 列表查询：按接口中文名匹配「调拨单」
curl 'http://localhost:8089/api/v2/stats?api=%E8%B0%83%E6%8B%A8%E5%8D%95'
```

## 接入插件

IntelliJ → Settings → Tools → Prod Call Stats：

- Use mock data: **OFF**
- Gateway URL: `http://localhost:8089`
- API version: `v2`
- API Token: 服务端启用 TOKEN 时填，否则留空

## 文件结构

```
server/
├── server.py        # HTTP 服务主入口
├── datasource.py    # DataSource 抽象基类
├── db_source.py     # MySQL 数据源（prod_call_stat LEFT JOIN api_name）
├── csv_source.py    # CSV 数据源实现（mtime 懒重载，向后兼容）
├── web/
│   └── index.html   # Web 页面（过滤 + 可点击排序）
└── README.md
```

## 扩展：接入其它数据源

`DataSource` 接口：

- `get(sign) -> dict | None`
- `get_batch(signs) -> (hits: list[dict], missed: list[str])`

MySQL 子类额外实现了 `search(...)` 以支持 Web 页面的列表查询；CSV 模式下该端点会返回 501。新增数据源时如需支持 Web 页面，请同步实现 `search`。
