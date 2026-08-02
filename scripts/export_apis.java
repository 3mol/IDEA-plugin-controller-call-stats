///usr/bin/env jbang
//DEPS com.github.javaparser:javaparser-core:3.26.2
//JAVA 17

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ParserConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 扫描项目源码里所有 Spring MVC controller，导出
 *   className, methodName, sign, apiOperationValue
 * 四列 CSV。
 *
 * 注解识别（与 IDEA 插件里的 SpringControllerScanner 保持一致）：
 *   - 类：@Controller / @RestController
 *   - 方法：@RequestMapping / @GetMapping / @PostMapping / @PutMapping
 *           / @DeleteMapping / @PatchMapping
 *   - 描述：@ApiOperation(value) (Swagger 2) → @Operation(summary) (OpenAPI 3)
 *
 * 用法：
 *   jbang scripts/export_apis.java <源码根目录> [<输出 csv 路径>]
 *
 *   默认输出到当前目录的 controllers.csv。
 *
 * 设计要点：
 *   - 用 JavaParser AST（不是正则），能正确处理多行注解、字符串拼接、
 *     注释干扰。
 *   - 解析语言级别设为 JAVA_21，支持现代 Java 语法。
 *   - 内部类的 sign 用 '$' 分隔（与 PSI 的 PsiClass.getQualifiedName()
 *     行为一致），保证 CSV 里的 sign 能命中 gateway 数据库。
 */
public class export_apis {

    private static final Set<String> CONTROLLER_ANN = Set.of("Controller", "RestController");
    private static final Set<String> MAPPING_ANN = Set.of(
        "RequestMapping", "GetMapping", "PostMapping",
        "PutMapping", "DeleteMapping", "PatchMapping"
    );

    public static void main(String[] args) throws IOException {
        List<Path> projects = new ArrayList<>();
        Path output = Paths.get("controllers.csv");
        boolean wantHelp = args.length == 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> wantHelp = true;
                case "-o", "--output" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("missing value for " + a);
                        System.exit(2);
                    }
                    output = Paths.get(args[++i]);
                }
                case "-p", "--project" -> {
                    // 收集到下一个 - 开头的 token 或结尾为止；支持
                    //   -p a b c
                    //   -p a -p b
                    int before = projects.size();
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        projects.add(Paths.get(args[++i]));
                    }
                    if (projects.size() == before) {
                        System.err.println("missing value for " + a);
                        System.exit(2);
                    }
                }
                default -> {
                    System.err.println("unknown argument: " + a);
                    System.err.println("run with --help to see usage");
                    System.exit(2);
                }
            }
        }

        if (wantHelp) {
            System.err.println("Usage: jbang export_apis.java -p <dir>... [-o <csv>] [-h]");
            System.err.println("");
            System.err.println("Options:");
            System.err.println("  -p, --project <dir>...   one or more project roots (recursed for .java)");
            System.err.println("                           can repeat: -p a -p b -p c");
            System.err.println("                           or batch:   -p a b c");
            System.err.println("  -o, --output <csv>       output csv path (default ./controllers.csv)");
            System.err.println("  -h, --help               show this help");
            System.err.println("");
            System.err.println("Examples:");
            System.err.println("  jbang export_apis.java -p ./src");
            System.err.println("  jbang export_apis.java -p ./svc-a ./svc-b -o apis.csv");
            System.err.println("  jbang export_apis.java --project ~/code/x --project ~/code/y -o out.csv");
            System.exit(args.length == 0 ? 1 : 0);
        }

        for (Path p : projects) {
            if (!Files.isDirectory(p)) {
                System.err.println("project root not a directory: " + p);
                System.exit(2);
            }
        }

        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        // 注释保留不影响 AST，但关掉能减少内存；保留更稳，避免某些字符串解析歧义。
        config.setAttributeComments(false);
        StaticJavaParser.setConfiguration(config);

        List<Row> rows = new ArrayList<>();
        Set<Path> seenFiles = new LinkedHashSet<>();
        for (Path sourceRoot : projects) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .forEach(p -> {
                        seenFiles.add(p);
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(p);
                            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                                if (cls.isInterface()) continue;
                                if (!hasControllerAnnotation(cls)) continue;
                                String fqn = fqnOf(cls);
                                for (MethodDeclaration m : cls.getMethods()) {
                                    if (!hasMappingAnnotation(m)) continue;
                                    String apiValue = readApiDescription(m);
                                    rows.add(new Row(fqn, m.getNameAsString(), fqn + "#" + m.getNameAsString(), apiValue));
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("parse failed: " + p + " — " + e.getMessage());
                        } catch (RuntimeException e) {
                            // 解析失败不该中断整个流程；记录后继续。
                            System.err.println("skipped: " + p + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    });
            }
        }

        writeCsv(output, rows);
        System.out.println("scanned " + seenFiles.size() + " java files across " + projects.size() + " project(s)");
        System.out.println("found " + rows.size() + " handler methods");
        System.out.println("written to " + output.toAbsolutePath());
    }

    private static boolean hasControllerAnnotation(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
            .anyMatch(a -> CONTROLLER_ANN.contains(a.getNameAsString()));
    }

    private static boolean hasMappingAnnotation(MethodDeclaration m) {
        return m.getAnnotations().stream()
            .anyMatch(a -> MAPPING_ANN.contains(a.getNameAsString()));
    }

    /**
     * Reads the description from @ApiOperation(value) (Swagger 2) first,
     * falls back to @Operation(summary) (OpenAPI 3). Returns empty Optional
     * if neither is present or the value is blank.
     */
    private static String readApiDescription(MethodDeclaration m) {
        Optional<AnnotationExpr> apiOp = m.getAnnotationByName("ApiOperation");
        if (apiOp.isPresent()) {
            Optional<String> v = annotationString(apiOp.get(), "value");
            if (v.isPresent() && !v.get().isBlank()) return v.get();
        }
        Optional<AnnotationExpr> op = m.getAnnotationByName("Operation");
        if (op.isPresent()) {
            Optional<String> v = annotationString(op.get(), "summary");
            if (v.isPresent() && !v.get().isBlank()) return v.get();
        }
        return "";
    }

    /**
     * Extracts the string value of an attribute from a JavaParser annotation.
     * Handles three forms:
     *   @Foo("bar")                       — single-member
     *   @Foo(value = "bar")               — normal with default key
     *   @Foo(value = "bar", other = ...)  — normal with explicit key
     *
     * For non-literal values (string concatenation, field refs), falls back
     * to the raw expression text so the CSV cell isn't silently empty.
     */
    private static Optional<String> annotationString(AnnotationExpr ann, String defaultKey) {
        if (ann instanceof SingleMemberAnnotationExpr s) {
            return Optional.of(stringOf(s.getMemberValue()));
        }
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if (pair.getNameAsString().equals(defaultKey)) {
                    return Optional.of(stringOf(pair.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    private static String stringOf(Expression expr) {
        if (expr instanceof StringLiteralExpr s) return s.asString();
        // Non-literal (e.g. `"a" + "b"`, or `Constant.DESC`). Return raw text
        // so reviewer can spot the case and resolve manually.
        return expr.toString();
    }

    /**
     * Fully qualified name of a class. JavaParser joins nested classes with '.',
     * but PSI's PsiClass.getQualifiedName() uses '$' for nested classes — and
     * the gateway sign follows PSI. We rewrite the nested separators here so
     * the CSV matches what the plugin reports.
     */
    private static String fqnOf(ClassOrInterfaceDeclaration cls) {
        String fqn = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
        Optional<String> pkg = cls.findCompilationUnit()
            .flatMap(cu -> cu.getPackageDeclaration())
            .map(PackageDeclaration::getNameAsString);
        if (pkg.isPresent()) {
            String p = pkg.get();
            if (fqn.startsWith(p + ".")) {
                String inner = fqn.substring(p.length() + 1);
                return p + "." + inner.replace(".", "$");
            }
        }
        return fqn;
    }

    private static void writeCsv(Path output, List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("className,methodName,sign,apiOperationValue\n");
        for (Row r : rows) {
            sb.append(csv(r.className)).append(',')
              .append(csv(r.methodName)).append(',')
              .append(csv(r.sign)).append(',')
              .append(csv(r.apiValue == null ? "" : r.apiValue)).append('\n');
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting: wrap in quotes if it contains , " \n \r; double internal quotes. */
    private static String csv(String s) {
        if (s == null || s.isEmpty()) return "";
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private record Row(String className, String methodName, String sign, String apiValue) {}
}
