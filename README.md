# Prod Call Stats

在 IntelliJ IDEA 里给 Spring MVC Controller 方法**上方**渲染生产调用指标（今日调用量 / 7 日调用量 / P99 / 错误率等），基于 Code Vision 实现。

```
🔥 Prod: 12,345 today ｜ 98.0K 7d ｜ P99 120ms ｜ Err 0.10%
@GetMapping("/users/{id}")
public UserDTO getUser(@PathVariable Long id) { ... }
```

- 兼容 IDEA 2024.3+（`sinceBuild=243`，不限上限）
- 支持 `@Controller` / `@RestController` + `@*Mapping` 系列注解
- 默认 **mock 模式**：不配置网关也能看到 Code Vision 效果（按方法签名稳定的随机数）
- 配上网关后切换为真实数据，支持单查 + 批量查询

---

## 目录

- [安装](#安装)
- [使用方式](#使用方式)
- [配置](#配置)
- [构建与开发](#构建与开发)
- [本地启动 IDE 测试](#本地启动-ide-测试runIde)
- [打包并导入 IDEA](#打包并导入-idea)
- [项目结构](#项目结构)
- [故障排查](#故障排查)

---

## 安装

### 方式一：从磁盘安装（推荐，适用于内部发布）

1. 拿到打包好的 `prod-call-stats-*.zip`（见 [打包并导入 IDEA](#打包并导入-idea)）
2. 打开 IDEA → `Settings/Preferences` → `Plugins`
3. 右上角齿轮图标 → `Install Plugin from Disk...`
4. 选择 zip 文件 → 重启 IDE

### 方式二：开发模式（`runIde`）

见 [本地启动 IDE 测试](#本地启动-ide-测试runIde)。

---

## 使用方式

1. 打开任意包含 Spring MVC Controller 的 Java 项目
2. 在编辑器里打开 Controller 类
3. 每个 `@GetMapping/@PostMapping/@*Mapping` 方法上方会自动出现：

   ```
   🔥 Prod: 1.2K today ｜ 12.3K 7d ｜ P99 320ms ｜ Err 0.20%
   ```

4. 鼠标悬停在文字上 → 弹出 tooltip，显示完整延迟分布（Min / Avg / Max / P99）+ 方法签名
5. 单击 Code Vision 文字 → 强制刷新该方法指标

> **默认是 mock 数据**：没配置网关时，渲染的是按 `className#methodName` 签名 hash 出来的稳定随机数，每分钟刷新一次。这是为了让用户在不连后端的情况下也能看到效果。

---

## 配置

`Settings | Tools | Prod Call Stats`

| 配置项 | 说明 | 默认值 |
|---|---|---|
| **Enable** | 总开关 | ✅ on |
| **Use mock data** | 用随机数据，不发起网络请求 | ✅ on |
| **Environment** | `prod` / `pre` | `prod` |
| **Gateway URL** | 后端网关地址（如 `https://stats.internal`），留空走 mock | 空 |
| **API Token** | 网关 `X-Api-Token` 请求头 | 空 |
| **Refresh interval** | 缓存 TTL（秒，最小 30） | 60 |
| **Verbose** | 主行也显示 Min/Avg/Max | off |
| **P99 warn / error** | 超过该值时主行加 ⚠ 提示 | 500 / 2000 |
| **Error rate warn / error** | 错误率阈值（%） | 0.1 / 1.0 |

**Test Connection** 按钮：调一次 `/api/v1/health` 验证网关 + token 可达。

### 切到真实数据

1. 关掉 `Use mock data`
2. 填入 `Gateway URL` 和 `API Token`
3. 点 `Test Connection` 验证
4. 应用 → 已打开的 Controller 文件会在下一次 Code Vision 重渲染时拉取真实数据

### 网关接口契约

签名格式：`<类全限定名>#<方法名>`，例如：

```
com.codo.tech.creeks.allocation.api.AllocationInnerController#page
```

**单查**

```
GET {gateway}/api/v1/call-stats?sign={URL-encoded sign}&env={prod|pre}
Header: X-Api-Token: {token}

Response 200:
{
  "today": 12345,
  "week": 98000,
  "p99Millis": 120,
  "maxExecuteTimeRequired": 350,
  "minExecuteTimeRequired": 12,
  "avgExecuteTimeRequired": 95,
  "errorRate": 0.0001,
  "fetchedAt": 1722200000000
}
```

**批量查询**（打开一个 Controller 文件时优先走这个，降低 QPS）

```
POST {gateway}/api/v1/call-stats/batch
Header: X-Api-Token: {token}
Body: { "env": "prod", "signs": ["com.x.Y#m1", "com.x.Y#m2"] }

Response 200:
{
  "results": { "com.x.Y#m1": { ...同单查字段... }, ... },
  "missed": [ ... ]
}
```

错误码：`401` token 失效；`404` 该方法无统计数据。

---

## 构建与开发

### 环境要求

- JDK 17+（推荐 JDK 21+，IDEA 自带 JBR 也行）
- Gradle 9.x（仓库已带 wrapper，不用本机装）
- 网络能访问 Maven Central + JetBrains plugin 仓库

### 依赖

构建工具：IntelliJ Platform Gradle Plugin 2.x + Kotlin 2.x。IDE 平台依赖为 `IC-2024.3`，并引入 `bundledPlugin("com.intellij.java")` 以访问 PSI Java API。

### 常用 Gradle 任务

```bash
# 编译 Kotlin 源码（最快的 sanity check）
./gradlew compileKotlin

# 只打 jar（跳过字节码 instrument / verifier / 签名，本地开发够用）
./gradlew jar \
  -x instrumentCode -x instrumentedJar -x verifyPlugin -x signPlugin

# 完整打包 zip（包含 instrumented 字节码；需要能访问 JetBrains artifact 仓库）
./gradlew buildPlugin

# 在 sandbox IDE 里启动插件做测试
./gradlew runIde
```

> 国内网络如果遇到拉 `java-compiler-ant-tasks` / `java-gui-forms-rt` 失败（TLS 握手错误），通常卡在 `instrumentCode` 任务。本地开发可以用 `./gradlew jar -x instrumentCode ...` 跳过，最终对外发布时再切到能联网的环境跑 `buildPlugin`。

---

## 本地启动 IDE 测试（runIde）

`runIde` 会启动一个**独立的 sandbox IDE 实例**，里面已经装好了当前构建的插件。

```bash
./gradlew runIde
```

启动后：

1. 在 sandbox IDE 里 `Open` 任意 Spring 项目（建议用真实业务项目验证 PSI 扫描）
2. 打开任意 `@RestController` 的 Java 文件
3. 方法上方应出现 `🔥 Prod: ...` 渲染

### 看日志

sandbox IDE 的日志在：

```
Help | Show Log in Files (或 Show Log in Explorer)
```

打开 `idea.log`，过滤 `[PCS]` 前缀可看到本插件的所有诊断日志，包括：

- 启动时打印 Code Vision 引擎里注册的所有 provider
- 每次扫描 Controller 时打印类名、注解、识别到的 handler sign
- mock 模式 / HTTP 模式切换、缓存命中、刷新事件

---

## 打包并导入 IDEA

### 步骤 1：生成 zip

```bash
./gradlew buildPlugin
```

产物在：

```
build/distributions/prod-call-stats-<version>.zip
```

> 如果只需要 jar（用于 dev 测试或自定义脚本），运行：
> ```bash
> ./gradlew jar -x instrumentCode -x instrumentedJar -x verifyPlugin -x signPlugin
> ```
> 产物：`build/libs/prod-call-stats-<version>-base.jar`

### 步骤 2：导入到本机 IDEA

1. 打开 IDEA → `Settings/Preferences` → `Plugins`
2. 右上角齿轮 → `Install Plugin from Disk...`
3. 选 `build/distributions/prod-call-stats-<version>.zip`
4. `Restart IDE`

### 步骤 3：验证

- `Plugins` 列表里出现 `Prod Call Stats` 且为已启用
- `Settings | Tools | Prod Call Stats` 配置页存在
- 打开 Controller 文件能看到 `🔥 Prod: ...` Code Vision

### 更新版本

修改 `gradle.properties` 里的 `version` 后重新 `buildPlugin` 即可。

> 注意：插件 ID `cc.miaooo.prod-call-stats` 一旦发布就不能改，只能升级 version。

---

## 项目结构

```
src/main/kotlin/cc/miaooo/prodcallstats/
├── ProdCallStatsBundle.kt              # i18n bundle 包装
├── bootstrap/
│   └── ProdCallStatsStartupActivity.kt # 启动时打印注册状态 / 兜底启用 provider
├── codevision/
│   ├── SpringControllerStatsProvider.kt# CodeVisionProvider 实现，渲染主行
│   ├── ScheduleFetcherTask.kt          # 批量拉取合流 + 完成后触发重渲染
│   └── StatsRenderer.kt                # 文案格式化（主行 + tooltip）
├── gateway/
│   ├── GatewayClient.kt                # HTTP 客户端（单查 / 批量 / ping）+ mock 生成
│   └── GatewayException.kt             # token 失效 / 404 / 网络不可达
├── psi/
│   ├── HandlerMethod.kt                # 数据模型（className + methodName + url + sign）
│   ├── Mapping.kt                      # 单个 mapping 注解的解析结果
│   └── SpringControllerScanner.kt      # 扫描 @RestController / @*Mapping
├── settings/
│   ├── StatsSettingsState.kt           # PersistentStateComponent 持久化
│   ├── StatsSettingsConfigurable.kt    # Settings 面板入口
│   └── StatsSettingsPanel.kt           # Swing UI
├── stats/
│   ├── CallStats.kt                    # 指标数据模型
│   └── StatsCacheService.kt            # TTL + 后台批量调度
└── util/
    └── HumanizeUtil.kt                 # 数字格式化（12345 → 12.3K / 1.2M）

src/main/resources/
├── META-INF/
│   ├── plugin.xml                      # 扩展点声明
│   └── pluginIcon.svg                  # 插件图标
└── messages/
    └── ProdCallStatsBundle.properties  # i18n 文案
```

---

## 故障排查

### Code Vision 文字根本不出现

依次确认：

1. `Settings | Editor | Inlay Hints | Code Vision` → 顶部 `Enable code vision` 主开关已打开
2. 该列表里有 `Prod Call Stats` 且已勾选
3. `Settings | Tools | Prod Call Stats` 的 `Enable` 已勾选
4. 打开的文件确实是 `@RestController`（或 `@Controller`）的 Java 类，且至少一个方法带 `@*Mapping`

### 看日志判断问题

`Help | Show Log in Files` → 搜 `[PCS]`，重点看：

| 日志 | 含义 |
|---|---|
| `=== plugin loaded in project: X ===` | 插件已加载 |
| `CodeVisionHost providers (N):` + 列表 | Code Vision 引擎实际注册了哪些 provider，应有 `prod-call-stats` |
| `*** OUR PROVIDER IS NOT REGISTERED ***` | EP 没注册成功，检查 plugin.xml 是否完整部署 |
| `found controller: X` | 扫描识别到 Controller |
| `Spring class 'X' NOT on classpath` | 项目没引入 Spring 依赖，或索引未就绪 |
| `fetch MOCK sign=X` / `fetchBatch HTTP count=N` | 当前是 mock 还是 HTTP 模式 |

### 已知坑

- **`@RestController` 的 FQN 是 `org.springframework.web.bind.annotation.RestController`**，不在 `stereotype` 包下。早期版本代码里写错过，导致识别失败。
- **Code Vision EP 必须用 `codeInsight.codeVisionProvider`**（在 `defaultExtensionNs="com.intellij"` 下），写成 `<codeVisionProvider>` 会被静默忽略。
- **`computeCodeVision` 在 pooled thread 执行**，PSI 访问必须包 `ReadAction.compute { ... }`。
- **`TextCodeVisionEntry.text` 是纯文本**，不解析 HTML，颜色需要走 tooltip / longPresentation。
- **`buildSearchableOptions` 任务**会在 headless IDE 退出时抛 H2 异常，已在 `build.gradle.kts` 里禁用，发布时可视情况打开。

---

## License

内部使用，未声明开源 license。
