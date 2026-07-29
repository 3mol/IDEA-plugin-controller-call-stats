# IDEA 插件技术设计：Prod Call Stats（生产调用情况 Inlay）

## 一、目标

在用户打开 Spring MVC Controller 源码时，在每个 `@GetMapping/@PostMapping/...` 方法签名**上方**，以「Code Vision」的形式展示该方法对应 URL 在生产环境的实时调用指标：

```
🔥 Prod: 12,345 calls today ｜ 98,000 7d ｜ P99 120ms ｜ Err 0.01%
@GetMapping("/users/{id}")
public UserDTO getUser(@PathVariable Long id) { ... }
```

## 二、功能边界

### 2.1 核心功能
- 识别 Spring MVC Controller 方法（`@RestController` / `@Controller` + `@RequestMapping` 系列注解）
- 拼装完整 URL（类级 `@RequestMapping` 前缀 + 方法级路径）
- 调用自建网关拉取该 URL 的：今日调用量、7 日调用量、P99 延迟、错误率
- 在方法上方展示汇总文案，支持点击展开详情
- 支持按 HTTP method 区分（GET / POST 各自有独立指标）

### 2.2 非功能需求
- **不阻塞 EDT**：所有网络调用在后台线程，结果回写使用异步机制
- **缓存优先**：同一 URL 在 TTL 内不重复请求
- **优雅降级**：网关不可达时仅不显示提示，不影响正常编码
- **可配置**：网关地址、Token、环境（prod/pre）、刷新间隔、阈值（P99 变红等）

### 2.3 非目标（V1 不做）
- 调用链追踪、日志下钻
- 非 Spring MVC（JAX-RS、WebFlux 路由函数）
- 多服务聚合
- 写操作（仅展示）

## 三、整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    IntelliJ IDEA Plugin                     │
│                                                             │
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────────┐   │
│  │ PSI Scanner │──>│ URL Resolver │──>│ CodeVision Prov │   │
│  │ (识别方法)   │   │ (拼装完整URL) │   │ (上方文字渲染)   │   │
│  └─────────────┘   └──────────────┘   └────────┬────────┘   │
│                                                 │            │
│                       ┌─────────────────────────▼──────┐     │
│                       │    Stats Cache (TTL + LRU)     │     │
│                       └────────┬─────────────┬────────┘     │
│                                │ miss        │ refresh       │
│                       ┌────────▼────────┐    │              │
│                       │  Gateway Client │<───┘              │
│                       │   (async HTTP)  │                   │
│                       └────────┬────────┘                   │
└────────────────────────────────┼────────────────────────────┘
                                 │ HTTPS
                                 ▼
                   ┌──────────────────────────────┐
                   │   自建网关 (公司内 HTTP API)   │
                   │  /api/call-stats?url=...      │
                   └──────────────────────────────┘
```

## 四、技术选型

| 维度 | 选择 | 原因 |
|---|---|---|
| 构建 | IntelliJ Platform Gradle Plugin 2.x | 官方推荐，替代老旧 gradle-intellij-plugin |
| 最低支持版本 | **IC-2023.1**（API 231）| Code Vision 已稳定；覆盖大部分用户 |
| 最高支持 | IC-2024.3 / EAP（可通过 `untilBuild` 控制） | 不锁定上限 |
| 渲染 API | **`CodeVisionProvider`**（`com.intellij.codeVision` EP）| 官方为「方法上方浮文字」设计 |
| HTTP | `com.intellij.util.io.HttpRequests` 或 OkHttp | 前者零依赖，后者更灵活 |
| 缓存 | 内存 LRU + TTL（`com.intellij.util.containers.Interner` 不够，自建轻量） | 简单可控 |
| 设置存储 | `PersistentStateComponent` + `Configurable` | 标准 API |
| 后台调度 | `CoroutinesService`（`@RequiresBackgroundThread` + `AppExecutorUtil`） | 官方并发模型 |
| 测试 | `BasePlatformTestCase` + `LightProjectDescriptor` | 标准 PSI 测试基类 |

## 五、核心模块设计

### 5.1 模块拆分

```
com.codo.tech.prodcallstats
├── codevision        // Code Vision 渲染层
├── psi               // PSI 扫描与 URL 解析
├── stats             // 数据拉取、缓存、模型
├── gateway           // HTTP 客户端封装
├── settings          // 配置面板与持久化
├── icons             // 插件图标
└── util              // 通用工具
```

### 5.2 数据流

1. 编辑器打开 Java 文件 → Code Vision 引擎调用 `CodeVisionProvider.computeLenses()`
2. Provider 在 **EDT** 上扫描 PSI（轻量、有缓存，按文件 hash 复用结果）
3. 对每个识别到的 Controller 方法生成 `TextCodeVisionEntry`，**先返回占位**（不阻塞）
4. Provider 异步触发 `StatsFetcher.fetch(url)`，命中缓存直接更新，否则提交到后台
5. 后台拉取完成 → 通过 `CodeVisionContext.refresh()` 触发重渲染
6. 二次渲染时 Provider 从缓存读到真实数据，生成最终文案

## 六、关键类设计

### 6.1 `plugin.xml` 扩展点声明

```xml
<idea-plugin>
  <id>com.codo.tech.prod-call-stats</id>
  <name>Prod Call Stats</name>
  <vendor>codo.tech</vendor>

  <depends>com.intellij.modules.platform</depends>
  <depends>com.intellij.modules.java</depends>

  <extensions defaultExtensionNs="com.intellij">
    <!-- Code Vision 注册 -->
    <codeVisionProvider
        implementation="com.codo.tech.prodcallstats.codevision.SpringControllerStatsProvider"
        order="first"/>
    <codeVisionGroupProvider
        implementation="com.codo.tech.prodcallstats.codevision.ControllerStatsGroupProvider"/>

    <!-- 配置面板 -->
    <applicationConfigurable
        id="prodcallstats.settings"
        groupId="tools"
        bundle="messages.ProdCallStatsBundle"
        key="settings.display.name"
        instance="com.codo.tech.prodcallstats.settings.StatsSettingsConfigurable"/>

    <!-- 持久化 -->
    <applicationService
        serviceImplementation="com.codo.tech.prodcallstats.settings.StatsSettingsState"/>

    <!-- 后台服务 -->
    <applicationService
        serviceImplementation="com.codo.tech.prodcallstats.stats.StatsCacheService"/>

    <!-- 警告/通知 -->
    <notificationGroup id="Prod Call Stats" displayType="STICKY_BALLOON"/>
  </extensions>
</idea-plugin>
```

### 6.2 PSI 扫描器 `SpringControllerScanner`

```java
public final class SpringControllerScanner {
    private static final Set<String> CTRL = Set.of(
        "org.springframework.stereotype.Controller",
        "org.springframework.stereotype.RestController");

    /** 判断该 PsiClass 是否为 Spring Controller */
    public static boolean isController(PsiClass cls) {
        return CTRL.stream().anyMatch(a -> AnnotationUtil.isAnnotated(cls, a, false));
    }

    /** 从 PsiMethod 提取 mapping 信息；非 handler 方法返回 null */
    public static HandlerMethod resolve(PsiMethod m) {
        Mapping methodMapping = readMapping(m);              // @GetMapping 等
        if (methodMapping == null) return null;
        PsiClass cls = PsiTreeUtil.getParentOfType(m, PsiClass.class);
        Mapping classMapping = readClassMapping(cls);        // @RequestMapping 前缀
        return new HandlerMethod(
            cls.getQualifiedName(),
            m.getName(),
            methodMapping.httpMethod(),
            joinPaths(classMapping, methodMapping));
    }
    // ...
}
```

关键点：
- 使用 `JavaPsiFacade.findClass()` + `GlobalSearchScope.allScope()` 解析注解全限定名，避免 import 缩写失效
- `@RequestMapping` 可同时声明 method 和 path，需兼容
- 路径变量 `{id}` 保留原样（与网关侧约定 URL 模板化策略）

### 6.3 Code Vision Provider

```java
public class SpringControllerStatsProvider implements CodeVisionProvider {
    @Override public @NotNull String getId() { return "prod-call-stats"; }

    @Override public @NotNull CodeVisionAnchorKind getDefaultAnchor() {
        return CodeVisionAnchorKind.Top;     // 显示在方法上方
    }

    @Override public @NotNull Computable<Boolean> isAvailable(@NotNull Project project) {
        return () -> StatsSettingsState.getInstance().isEnabled()
            && hasControllerOpen(project);   // 轻量判断
    }

    @Override public @NotNull List<Pair<TextRange, CodeVisionEntry>> computeForFile(
            @NotNull PsiFile file, @NotNull CodeVisionEntry.Context context) {
        ReadAction.assertReadAccessAllowed();
        if (!(file instanceof PsiJavaFile)) return List.of();

        List<Pair<TextRange, CodeVisionEntry>> result = new ArrayList<>();
        for (PsiClass cls : ((PsiJavaFile) file).getClasses()) {
            if (!SpringControllerScanner.isController(cls)) continue;
            for (PsiMethod m : cls.getMethods()) {
                HandlerMethod hm = SpringControllerScanner.resolve(m);
                if (hm == null) continue;

                TextRange range = m.getNameIdentifier().getTextRange();
                String text = StatsRenderer.render(hm);   // 异步更新缓存后会重渲染
                result.add(Pair.create(range,
                    new TextCodeVisionEntry(text, getId(), context,
                        /*tooltip*/ null, /*shortcut*/ null,
                        /*onClick*/ () -> StatsAction.onClicked(hm))));
            }
        }
        return result;
    }
}
```

Code Vision 引擎本身就**异步**：`computeForFile` 在读线程执行，引擎会在后台任务完成后自动重新触发，不需要自己手动 refresh。

### 6.4 数据模型与缓存

```java
public record CallStats(
    long today,        // 今日调用
    long week,         // 7 日调用
    long p99Millis,    // P99 延迟
    long maxExecuteTimeRequired,    // 最大耗时
    long minExecuteTimeRequired,    // 最小耗时
    long avgExecuteTimeRequired,    // 平均耗时
    double errorRate,  // 错误率 [0,1]
    long fetchedAt     // 拉取时间戳
) {}

public final class StatsCacheService {
    private final Cache<HandlerMethod, CompletableFuture<CallStats>> cache =
        Caffeine.newBuilder()                       // 也可手写 ConcurrentHashMap
            .expireAfterWrite(Duration.ofDays(1))
            .maximumSize(2000)
            .build();

    public CompletableFuture<CallStats> get(HandlerMethod hm) {
        return cache.get(hm, k -> GatewayClient.fetch(k).toCompletableFuture());
    }

    public void invalidateAll() { cache.invalidateAll(); }
}
```

### 6.5 网关客户端契约

后端已提供接口，**入参直接使用方法签名**（类全限定名 `#` 方法名），不再需要 `uri` / `app` 等。PSI 侧 `HandlerMethod` 已经持有 `className` 和 `methodName`（见 §6.2），拼装后即可发起请求。

签名格式示例：
```
com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#queryWarehouseById
```

#### 6.5.1 单个查询

```
GET  {gateway}/api/v1/call-stats
     ?sign={全限定类名}#{方法名}
     &env={prod|pre}

示例:
GET {gateway}/api/v1/call-stats
    ?sign=com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController%23queryWarehouseById
    &env=prod

Header:
  X-Api-Token: {token from settings}
```

> 注意：`#` 在 URL query 中需 URL-encode 为 `%23`，避免被识别为 fragment。

```
Response 200:
{
  "today":                  12345,
  "week":                   98000,
  "p99Millis":              120,
  "maxExecuteTimeRequired": 350,
  "minExecuteTimeRequired": 12,
  "avgExecuteTimeRequired": 95,
  "errorRate":              0.0001,
  "fetchedAt":              1722200000000
}
Response 404: 该方法无统计数据（首次上线 / 未采集）
Response 401: token 失效（弹出重新配置提示）
```

#### 6.5.2 批量查询（强烈建议）

打开一个 Controller 文件时一次拉取该类所有方法指标，降低 QPS：

```
POST {gateway}/api/v1/call-stats/batch
Header: X-Api-Token: {token}
Body:
{
  "env": "prod",
  "signs": [
    "com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#queryWarehouseById",
    "com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#page",
    "com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#export"
  ]
}

Response 200:
{
  "results": {
    "com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#queryWarehouseById": { ...同 6.5.1 的字段... },
    "com.codo.tech.creeks.inventory.remoteapi.warehouse.WarehouseController#page": { ... },
    ...
  },
  "missed": [ ...未命中的 sign 列表... ]
}
```

Provider 在打开文件时收集本文件所有 handler 方法的 sign，**一次批量请求**，缓存按 sign 写入。

#### 6.5.3 重载与 `HandlerMethod` 的影响

Java 允许方法重载（同名不同参）。若同一个 Controller 内存在重载的 handler 方法（Spring MVC 不允许同 URL + 同 method 重载，但不同 URL 的同名方法合法），仅靠 `className#methodName` 无法区分。

处理策略（V1 取最简方案，后续按需演进）：
- **V1**：默认同 Controller 内无同名 handler 方法；若存在，对该 `sign` 缓存项标记 `AMBIGUOUS`，渲染 `🔥 Prod: 重载方法需补充参数`，不发起请求
- **V2**：扩展签名为 `className#methodName(参数类型列表)`，由 PSI 提取 `MethodSignature`，请求时一并提供

对应修改点（仅 V2 需要）：
- §6.2 `HandlerMethod` 增加 `parameterTypes` 字段
- `GatewayClient` 构建 sign 时拼上参数类型

### 6.6 配置面板

```
Settings | Tools | Prod Call Stats
├── [✓] Enable
├── Environment:      ( ) prod   ( ) pre
├── Gateway URL:      [https://stats.internal/__________]
├── API Token:        [***************]   [Test Connection]
├── Refresh interval: [60] seconds
├── Display mode:     ( ) Compact (仅 P99)   (•) Verbose (P99 + Min/Avg/Max)
├── P99 warn (ms):    [500]    (超过该值文案变橙)
├── P99 error (ms):   [2000]   (超过该值文案变红)
└── Error rate warn:  [1.0] %
```

`Test Connection` 按钮后台发一次 `/api/v1/health`，结果通过 `Notifications` 反馈。

### 6.7 渲染层

主行保持紧凑（只显示 P99 反映尾延迟），完整延迟分布通过 **tooltip** 鼠标悬停展示；用户可在 Settings 切换到「详细模式」把 Min/Avg/Max 也拼到主行。

```java
public final class StatsRenderer {
    /** 主行：Code Vision 上方文字 */
    public static String render(HandlerMethod hm) {
        CallStats s = StatsCacheService.getInstance().getNow(hm);
        if (s == null)          return "🔥 Prod: loading…";
        if (s == UNAVAILABLE)   return "<html><font color='#888'>🔥 Prod: N/A</font></html>";
        if (s == AMBIGUOUS)     return "<html><font color='#888'>🔥 Prod: 重载方法需补充参数</font></html>";

        String color = colorFor(s);   // 按 §6.6 阈值返回 #FF6B6B / #FFA500 / #66B16B
        String main = String.format(
            "🔥 Prod: %s today ｜ %s 7d ｜ P99 %dms ｜ Err %.2f%%",
            humanize(s.today()), humanize(s.week()),
            s.p99Millis(), s.errorRate() * 100);

        if (StatsSettingsState.getInstance().isVerboseMode()) {
            main += String.format(" ｜ Min %dms ｜ Avg %dms ｜ Max %dms",
                s.minExecuteTimeRequired(), s.avgExecuteTimeRequired(),
                s.maxExecuteTimeRequired());
        }
        return String.format("<html><font color='%s'>%s</font></html>", color, main);
    }

    /** 鼠标悬停 tooltip，展示完整延迟分布 + 方法签名 */
    public static String tooltip(HandlerMethod hm, CallStats s) {
        return String.format(
            "<html><b>%s</b><br>" +
            "今日调用: %s　｜　7 日调用: %s<br>" +
            "Min: %dms　Avg: %dms　Max: %dms　P99: %dms<br>" +
            "错误率: %.3f%%　｜　采集于: %s" +
            "</html>",
            hm.sign(),
            humanize(s.today()), humanize(s.week()),
            s.minExecuteTimeRequired(), s.avgExecuteTimeRequired(),
            s.maxExecuteTimeRequired(), s.p99Millis(),
            s.errorRate() * 100,
            formatTimestamp(s.fetchedAt()));
    }
}
```

> 联动修改 §6.3：`TextCodeVisionEntry` 的 `tooltip` 参数从 `null` 改为 `StatsRenderer.tooltip(hm, s)`，Code Vision 引擎会在鼠标悬停时弹出 HTML tooltip。

颜色阈值（与 §6.6 Settings 联动）：

| 字段 | 默认橙 | 默认红 |
|---|---|---|
| `p99Millis` | ≥ 500 | ≥ 2000 |
| `errorRate` | ≥ 0.1% | ≥ 1% |

任一字段命中红，整体文案红色；命中橙则橙色；否则绿色（正常）。

格式化建议：
- `humanize(12345)` → `12,345`；`humanize(98000)` → `98.0K`；`humanize(1_200_000)` → `1.2M`
- 所有时间字段单位 `ms`，原值即毫秒，无需换算
- 颜色用 `<html><font color='#RRGGBB'>...</font></html>`（Code Vision 支持 HTML）

## 七、性能与线程模型

| 操作 | 线程 | 频率 |
|---|---|---|
| PSI 扫描 | Read Action（EDT 或读线程） | 文件打开 / 编辑时由 Code Vision 控制 |
| 缓存查询 | 任意线程 | 每次渲染 |
| HTTP 调用 | `AppExecutorUtil.getAppExecutorService()`（后台池） | 缓存 miss 时 |
| 缓存失效 | `ModalityState.defaultModalityState()` 切回 EDT | 后台任务完成 |
| 通知 | EDT | 仅错误时 |

避免的反模式：
- ❌ 在 `computeForFile` 里同步 `URL.openConnection()`
- ❌ 每个方法一次 HTTP 请求（用批量接口）
- ❌ 用静态 `Map` 做缓存（多项目泄漏）

## 八、错误处理与降级

| 场景 | 行为 |
|---|---|
| 网关超时 | 显示 `🔥 Prod: timeout`，5 分钟内不再重试同一 URL |
| Token 失效 (401) | 不显示文字，弹一次通知引导去配置 |
| URL 未上线 (404) | 显示 `🔥 Prod: no data`（灰色），不当作错误 |
| 插件异常 | try-catch 在 Provider 外层，记录到 `logger.warn` 但不影响其它方法渲染 |

## 九、测试策略

| 层 | 工具 | 覆盖点 |
|---|---|---|
| PSI 扫描 | `LightJavaCodeInsightFixtureTestCase` | 各种注解组合、继承、元注解、import 缩写 |
| URL 拼装 | 普通单测 | 类级 `/api` + 方法级 `/users/{id}` → `/api/users/{id}` |
| 渲染格式化 | 普通单测 | 千分位、P99 阈值变色 |
| 网关客户端 | `MockWebServer`（OkHttp 自带） | 200/404/401/超时 |
| Code Vision 端到端 | `CodeInsightTestFixture` + 自定义 fixture | 打开测试 Java 文件，断言 `CodeVisionProvider.computeLenses` 返回正确 entry |
| 配置持久化 | `TempDirTestFixture` | 修改 → 重启项目 → 读取 |

CI：GitHub Actions 上用 [setup-intellij](https://github.com/marketplace/actions/setup-intellij-platform) 或直接用 plugin template 自带的 `buildPlugin` + `runPluginVerifier` 任务（覆盖 231~243）。

## 十、项目目录结构

```
prod-call-stats/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/
│   ├── main/
│   │   ├── java/com/codo/tech/prodcallstats/
│   │   │   ├── codevision/
│   │   │   │   ├── SpringControllerStatsProvider.java
│   │   │   │   ├── ControllerStatsGroupProvider.java
│   │   │   │   └── StatsRenderer.java
│   │   │   ├── psi/
│   │   │   │   ├── SpringControllerScanner.java
│   │   │   │   ├── HandlerMethod.java
│   │   │   │   └── Mapping.java
│   │   │   ├── stats/
│   │   │   │   ├── StatsCacheService.java
│   │   │   │   ├── CallStats.java
│   │   │   │   └── StatsFetcher.java
│   │   │   ├── gateway/
│   │   │   │   ├── GatewayClient.java
│   │   │   │   └── GatewayException.java
│   │   │   ├── settings/
│   │   │   │   ├── StatsSettingsState.java
│   │   │   │   ├── StatsSettingsConfigurable.java
│   │   │   │   └── StatsSettingsPanel.java
│   │   │   └── util/
│   │   │       └── HumanizeUtil.java
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml
│   │       │   └── plugin-java.xml      （with-java 模块按需）
│   │       ├── icons/
│   │       │   └── stats.svg
│   │       └── messages/
│   │           └── ProdCallStatsBundle.properties
│   └── test/...（镜像结构）
└── .github/workflows/
    ├── build.yml
    └── release.yml
```

## 十一、关键代码骨架：`build.gradle.kts`

```kotlin
plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    java
}

group = "com.codo.tech"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create("IC", "2023.1")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        instrumentationTools()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

intellijPlatform {
    pluginConfiguration {
        name = "Prod Call Stats"
        ideaVersion {
            sinceBuild = "231"
            untilBuild = "243.*"
        }
    }
    pluginVerification {
        ides { ide(IC, "2023.1"); ide(IC, "2024.3") }
    }
}
```

## 十二、开发里程碑

| 阶段 | 产出 | 预估 |
|---|---|---|
| M1 脚手架 | plugin template clone、`./gradlew runIde` 能起 IDE | 0.5d |
| M2 PSI 识别 | 扫描 Spring Controller + 单测覆盖所有注解组合 | 1.5d |
| M3 Code Vision 接入 | 用假数据让 `🔥 Prod: 12,345 calls today` 出现在 `@GetMapping` 上方 | 1d |
| M4 网关客户端 + 缓存 | 接入真实 HTTP，TTL + LRU，异步刷新 | 1.5d |
| M5 配置面板 | Settings、Token、Test Connection、通知 | 1d |
| M6 渲染优化 | 阈值变色、批量请求、loading 态、错误态 | 1d |
| M7 测试 + Verifier | pluginVerifier 通过 231~243，端到端 fixture | 1d |
| M8 发布 | JetBrains Marketplace（或内部 plugin repository） | 0.5d |

合计约 **8 人日**（不含后端网关开发）。

## 十三、需要确认 / 推进的事项

1. **后端网关接口**：是否已有？路径、字段、认证方式请确认（影响 §6.5 契约）
2. **重载方法处理**：当前 `sign = className#methodName` 无法区分重载（见 §6.5.3）。请确认线上 Controller 是否存在同 URL 同 method 的重载场景；若存在需提前进入 V2 签名方案
3. **数据时效**：是实时（秒级）还是分钟级聚合？决定 TTL 与文案措辞
4. **是否需要 pre 环境切换**：开发期可能想看 pre 数据？暂时不考虑
5. **插件分发渠道**：JetBrains Marketplace 还是公司内部 plugin repo？公司内部使用。
