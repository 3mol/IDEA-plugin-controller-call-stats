# Scripts

脱离 IDEA 的离线辅助脚本，给运维 / CI / 文档导出场景用。和 IDE 插件共享同一套注解识别规则，确保两边输出对得上。

---

## export_apis.java — 全量导出 controller API 到 CSV

扫描项目源码里所有 Spring MVC controller，导出四列 CSV：

| 列 | 说明 |
|---|---|
| `className` | controller 全限定类名（内部类用 `$` 分隔，与 PSI 一致） |
| `methodName` | handler 方法名 |
| `sign` | `className#methodName`，**直接对得上 gateway 数据库的 sign** |
| `apiOperationValue` | `@ApiOperation(value)` 或 `@Operation(summary)` 的值，缺失留空 |

### 前置依赖

- **JDK 17+**（脚本头声明 `//JAVA 17`）
- **[jbang](https://www.jbang.dev/)** — 单文件 Java 脚本运行器，自动下载 JavaParser 依赖

装 jbang：

```bash
# Linux / macOS
curl -Ls https://sh.jbang.dev | bash -s - app setup

# macOS (Homebrew)
brew install jbangdev/tap/jbang

# Arch / Manjaro
pacman -S jbang
```

### 用法

```bash
# 单项目，输出到默认 ./controllers.csv
jbang scripts/export_apis.java -p /path/to/project

# 多项目，批量形式（推荐）
jbang scripts/export_apis.java -p ./svc-a ./svc-b ./svc-c -o apis.csv

# 多项目，重复 flag 形式
jbang scripts/export_apis.java --project ~/code/x --project ~/code/y -o out.csv

# 帮助
jbang scripts/export_apis.java --help
```

支持两种 flag 形式：短 `-p` / `-o` 和长 `--project` / `--output`。`-p` 后可以跟一个或多个目录（直到下一个 `-` 开头的 token），也可以重复出现：

```bash
# 等价的两种写法
-p a b c
-p a -p b -p c
```

### 输出示例

```csv
className,methodName,sign,apiOperationValue
com.example.foo.UserController,getUser,com.example.foo.UserController#getUser,get user by id
com.example.foo.UserController,create,com.example.foo.UserController#create,create user
com.example.foo.BarController$Nested,nested,com.example.foo.BarController$Nested#nested,nested method desc
```

### 注解识别范围

| 角色 | 注解 |
|---|---|
| 类标记 | `@Controller`、`@RestController` |
| 方法映射 | `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping` |
| 描述来源 | `@ApiOperation(value)`（Swagger 2）→ `@Operation(summary)`（OpenAPI 3），缺失留空 |

这套规则和 IDE 插件里的 `psi/SpringControllerScanner.kt` 完全对齐 —— 插件里能渲染出 Code Vision 的方法，CSV 里一定也有。

### 设计要点

- **AST 解析**：用 [JavaParser](https://javaparser.org/) 解析语法树，不是正则。多行注解、字符串拼接、注释干扰都不会误判。
- **Java 21**：解析器配 `JAVA_21`，支持 records、`var`、switch expression、文本块等现代语法。
- **内部类 sign**：`Outer$Inner#method`，与 `PsiClass.getQualifiedName()` 行为一致。
- **自动跳过**：扫到 `/target/` 和 `/build/` 目录的 .java 会跳过（避免编译产物里的拷贝污染结果）。
- **容错**：单个文件解析失败不中断，stderr 报告跳过行。
- **CSV 转义**：RFC 4180 —— 含 `,` `"` `\n` 的字段自动加引号、内部 `"` 加倍。

### CI 集成

GitHub Actions 示例：

```yaml
- uses: jbangdev/jbang-action@v0.129.0
  with:
    script: scripts/export_apis.java
    args: -p ./svc-a ./svc-b ./svc-c -o controllers.csv
- uses: actions/upload-artifact@v4
  with:
    name: controllers-csv
    path: controllers.csv
```

### 与 IDE 插件协同的典型用法

1. CI 里跑这个脚本，每周生成一份 `controllers.csv`，作为 controller 清单的"基线"。
2. gateway 后端用同一份 `sign` 列做统计 key（已经在这么用了）。
3. IDE 插件实时显示当前 controller 的指标，CSV 给文档、API 审计、SRE 巡检批量使用。

### 排错

| 现象 | 排查 |
|---|---|
| 找不到 controller | 确认类上有 `@Controller` 或 `@RestController`（不是 `@Component`） |
| `apiOperationValue` 列全空 | 项目用的是 Swagger 2 还是 OpenAPI 3？两者都识别；如果用其它注解（如 javadoc），脚本不支持 |
| `skipped: <file> — ...` | 该 .java 有语法错误或用了 JavaParser 不支持的语法特性；查看 stderr 信息 |
| sign 跟 gateway 对不上 | 内部类要写成 `Outer$Inner`；如果 gateway 用 `.`，需要在脚本里改 `fqnOf()` 的拼接规则 |
