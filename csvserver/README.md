# ProdCallStats CSV Server

读取本地 CSV（默认：`接口调用次数分析.csv`），按 v1 / v2 协议对外提供调用统计，供 IDE 插件 `prod-call-stats` 的 `GatewayClient` 调用。

## 数据源说明

当前实现 **方式 1：读取本地 CSV 文件**。后续可扩展更多数据源（如数据库），只需：

1. 在 `data_source.py` 中新增 `DataSource` 子类并实现 `load()`
2. 在 `server.py` 的 `build_data_source()` 中加入对应 `--source` 分支

HTTP 协议层无需改动。

## 环境要求

- Python 3.8+（仅使用标准库，无需安装任何依赖）

## 目录结构

```
csvserver/
├── server.py         # HTTP 服务 + 端点实现
└── data_source.py    # 数据源抽象与 CSV 实现
```

## 启动方式

### 默认启动

读取默认 CSV 路径并监听 `8089` 端口：

```bash
cd csvserver
python3 server.py
```

默认参数：
- `--host 0.0.0.0`
- `--port 8089`
- `--source csv`
- `--csv-path /home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv`
- `--csv-encoding utf-8-sig`（兼容带 / 不带 BOM 的 UTF-8）

### 自定义启动

```bash
# 自定义端口与 CSV 路径
python3 server.py --port 8088 --csv-path /path/to/接口调用次数分析.csv

# 仅本机访问
python3 server.py --host 127.0.0.1

# 指定编码
python3 server.py --csv-encoding gbk
```

### 完整参数

| 参数             | 默认值                                                                | 说明                          |
| ---------------- | --------------------------------------------------------------------- | ----------------------------- |
| `--host`         | `0.0.0.0`                                                             | 监听地址                      |
| `--port`         | `8089`                                                                | 监听端口                      |
| `--source`       | `csv`                                                                 | 数据源类型，当前仅支持 `csv`  |
| `--csv-path`     | `/home/huyujing/IdeaProjects/duckle-demo-v2/output/接口调用次数分析.csv` | CSV 文件路径                  |
| `--csv-encoding` | `utf-8-sig`                                                           | CSV 文件编码                  |

## HTTP 端点

启动后，服务会打印可用端点。所有端点均接受请求头 `X-Api-Token`（不鉴权，仅回显到 `X-Echo-Api-Token` 便于联调）。

| 方法 | 路径                       | 说明                                              |
| ---- | -------------------------- | ------------------------------------------------- |
| GET  | `/api/v1/health`           | 健康检查                                          |
| GET  | `/api/v1/call-stats`       | 单条查询（v1），`?sign=<sign>&env=<env>`          |
| POST | `/api/v1/call-stats/batch` | 批量查询（v1），`results` 为 map                  |
| POST | `/api/v2/call-stats`       | 批量查询（v2），`results` 为 array，含 className 等 |

### 请求示例

**v1 单条查询**

```bash
# 注意：sign 中的 `#` 需 URL 编码为 `%23`
curl 'http://127.0.0.1:8089/api/v1/call-stats?sign=com.foo.Bar%23hello&env=prod'
```

**v1 批量查询（map 形态）**

```bash
curl -X POST http://127.0.0.1:8089/api/v1/call-stats/batch \
  -H 'Content-Type: application/json' \
  -d '{"env":"prod","signs":["com.foo.Bar#hello","com.foo.Bar#world"]}'
```

**v2 批量查询（array 形态）**

```bash
curl -X POST http://127.0.0.1:8089/api/v2/call-stats \
  -H 'Content-Type: application/json' \
  -d '{"env":"prod","signs":["com.foo.Bar#hello"]}'
```

### 响应结构

**v1 单条 / v1 batch 的 value**

```json
{
  "today": 445,
  "week": 940,
  "p99Millis": 40,
  "maxExecuteTimeRequired": 122,
  "minExecuteTimeRequired": 5,
  "avgExecuteTimeRequired": 10,
  "errorRate": 0.0,
  "fetchedAt": 1785482424340
}
```

**v2 batch 的数组项**（在 v1 字段基础上额外携带 `className` / `methodName` / `sign`）

```json
{
  "className": "com.codo.tech.creeksonapi.remoteapi.product.ProductServiceApiV1",
  "methodName": "listSkuSelective",
  "sign": "com.codo.tech.creeksonapi.remoteapi.product.ProductServiceApiV1#listSkuSelective",
  "today": 445,
  "week": 940,
  "p99Millis": 40,
  "maxExecuteTimeRequired": 122,
  "minExecuteTimeRequired": 5,
  "avgExecuteTimeRequired": 10,
  "errorRate": 0.0,
  "fetchedAt": 1785482424340
}
```

> 未命中的 `sign`：v1 batch 会补零值 stats；v2 batch 会补一项含 `className/methodName/sign` 的零值记录，方便插件侧统一展示。

## CSV 表头约定

固定如下字段顺序，缺字段时以默认值填充：

```
className,methodName,sign,today,week,p99Millis,maxExecuteTimeRequired,minExecuteTimeRequired,avgExecuteTimeRequired,errorRate,fetchedAt
```

- `fetchedAt`：毫秒级 epoch，直接透传到响应中（不会被服务端覆盖）
- 数值字段在 CSV 中若为浮点字符串（如 `40.61`）会被强转为 `int`
- `sign` 为空时用 `className#methodName` 兜底

## 与 IDE 插件对接

在 IDE 插件 `prod-call-stats` 的设置中：

- **API version** 选择 `v2`（推荐，字段更完整）
- **Gateway URL** 填 `http://localhost:8089`
- **API Token** 可留空（本服务不做鉴权）

## 文件热更新

`CsvDataSource` 基于文件 `mtime` 缓存：CSV 被重新生成后，下一次请求会自动重新读取最新内容，无需重启服务。