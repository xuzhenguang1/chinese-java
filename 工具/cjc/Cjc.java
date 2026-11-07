// Cjc —— 中文 Java 源码检查、转译与编译工具（M2）
// 目标：把中文关键字与全角标点翻译成等宽英文，交给原生 javac；诊断位置回映射到中文源码。
//
// 用法：
//   java 工具\cjc\Cjc.java 检查 <文件或目录...>
//   java 工具\cjc\Cjc.java 编译 <文件或目录...> -d <输出目录> [-jar <jar路径> -主类 <全限定类名>]
//   java 工具\cjc\Cjc.java 转译 <文件或目录...> -d <输出目录>
//   java 工具\cjc\Cjc.java 反译 <文件或目录...> -d <输出目录>

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Cjc {

    static final int KIND_KEYWORD = 0;
    static final int KIND_IDENTIFIER = 1;
    static final int KIND_OTHER = 2;
    static final int KIND_PUNCT = 3;      // 已规范化的全角标点（半角化）
    static final int KIND_BAD_PUNCT = 4;  // 无法规范化的 CJK 标点（报 CJK002）

    // 关键字表来自 工具\cjc\关键字表.txt（M1 外置）。只追加、不替换英文关键字，中文与英文可混编；
    // 近义词统一维护在 工具\cjc\近义词典.txt，表中只写组名（如 public=[公有]），加载时自动展开。
    static final LinkedHashMap<String, String> KEYWORDS = new LinkedHashMap<>();
    static final List<String> SORTED_KEYWORDS = new ArrayList<>();
    static final LinkedHashMap<String, List<String>> DICT_GROUPS = new LinkedHashMap<>();

    // 合法英文关键字集合，用于校验关键字表的英文侧，避免拼写错误流到 javac
    static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "record", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "sealed", "permits", "non-sealed", "yield");

    // 类型别名：java.lang 常用类型的中文写法。它们不是 Java 关键字，但同样按整词替换，
    // 因此与关键字共用 关键字表.txt，转译与反译都生效。
    // 原生补丁（Tokens.java）只接受 Java 关键字，同步词表.ps1 生成补丁时会过滤掉这些英文侧。
    static final Set<String> JAVA_LANG_TYPES = Set.of(
            "String", "Object",
            "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character",
            "List", "ArrayList", "Map", "LinkedHashMap", "Set", "HashSet", "HashMap", "StringBuilder", "Path", "Comparable",
            "Optional", "Scanner",
            "Override", "ExecutorService", "Future", "Logger",
            "IOException", "InterruptedException", "ExecutionException",
            "IllegalArgumentException", "NumberFormatException", "NullPointerException",
            "ArrayIndexOutOfBoundsException", "ArithmeticException");

    // 基本类型的英文侧，用于识别「中文类型名 + 名称」形式的声明。
    static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "short", "byte", "float", "double", "char", "boolean", "void");

    // 泛型实参不能是基本类型：`清单<整数>` 自动转成 `List<Integer>`。
    // 只在 `<` / `,` 之后、`>` / `,` 之前生效；其余位置仍按基本类型转译。
    static final LinkedHashMap<String, String> PRIMITIVE_WRAPPERS = new LinkedHashMap<>();
    static {
        PRIMITIVE_WRAPPERS.put("int", "Integer");
        PRIMITIVE_WRAPPERS.put("long", "Long");
        PRIMITIVE_WRAPPERS.put("short", "Short");
        PRIMITIVE_WRAPPERS.put("byte", "Byte");
        PRIMITIVE_WRAPPERS.put("float", "Float");
        PRIMITIVE_WRAPPERS.put("double", "Double");
        PRIMITIVE_WRAPPERS.put("boolean", "Boolean");
        PRIMITIVE_WRAPPERS.put("char", "Character");
    }

    // 中文标准库（包 中文.标准库）的类名：这些接收者上的成员调用保持原样，不走成员糖。
    // 标准库类自己就提供中文方法（`文本.去空白`、`并发.关闭`、`集合工具.填充` 等），
    // 若也按方法名全局改写，会变成这些类上不存在的方法名。
    // 白名单内容全部来自 工具\cjc\方法糖.txt 的 [白名单] 分区，这里只声明空集。
    static final Set<String> STDLIB_CLASSES = new LinkedHashSet<>();

    // 全角标点：U+FF01..U+FF5E 统一平移 0xFEE0 得到半角；U+3000 全角空格转半角空格。
    // javac 不接受全角空格与全角标点，必须在这里规范化。
    // 无法映射到半角的 CJK 标点（顿号、句号、书名号、弯引号等）一律报 CJK002。
    static final Set<Integer> CJK_PUNCTUATION = Set.of(
            0x3001, 0x3002, 0x3005,
            0x3008, 0x3009, 0x300A, 0x300B, 0x300C, 0x300D, 0x300E, 0x300F,
            0x3010, 0x3011, 0x3014, 0x3015, 0x3016, 0x3017, 0x3018, 0x3019,
            0x301A, 0x301B, 0x301C,
            0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2026,
            0x00B7, 0x203B);

    static final int KIND_EN_KEYWORD = 5;  // 反译模式下命中的英文关键字

    // A5：`--允许推断` 开关。开启后 `令 名称 = …` → `var 名称 = …`，
    // `常量 名称 = …` → `final var 名称 = …`；关闭时按普通标识符处理（javac 会报错）。
    static boolean allowInference = false;

    // `--严格标点` 开关：默认把 `。` / `、` 自动纠正为半角 `;` / `.` / `,`（只提示 CJK106）；
    // 开启后恢复旧行为，仍按 CJK002 阻断。
    static boolean strictPunctuation = false;

    // 反译用：英文 → 中文 的反向表，以及英文关键字集合（由关键字表加载时构建）
    static final LinkedHashMap<String, String> CHINESE_BY_ENGLISH = new LinkedHashMap<>();
    static final Set<String> ENGLISH_KEYWORDS = new HashSet<>();

    // 方法糖唯一数据源：与关键字表同目录的 方法糖.txt，启动时必须存在；
    // 这里只声明空表，由 loadMethodSugar 按文件顺序填充（声明顺序即匹配优先级）。
    // 命名刻意避开 中文.标准库 已有方法（加、取、放、排序、去空白…），避免把标准库调用改坏。
    static final LinkedHashMap<String, String> MEMBER_CALLS = new LinkedHashMap<>();

    // 中文重写方法声明 → Java 同名方法：不带 `.` 前缀、后接 `(` 时识别，
    // 例如 `公有 字符串 转文本()` → `public String toString()`。
    // 这三个方法由 Object 定义，Java 不允许改名，只能在声明处还原；内容同样来自 方法糖.txt。
    static final LinkedHashMap<String, String> MEMBER_DECLS = new LinkedHashMap<>();

    // 中文类名 + 方法 → Java 静态调用，例如 `算术.平方根(…)` → `Math.sqrt(…)`；
    // 内容全部来自 方法糖.txt，这里只声明空表。
    static final LinkedHashMap<String, String> STATIC_CALLS = new LinkedHashMap<>();

    /** 命令行选项：由 parseOptions 填充，main 只负责按命令分发。 */
    static final class Options {
        String command;
        final List<String> inputs = new ArrayList<>();
        Path outDir;
        Path jarPath;
        String mainClass;
        String tableArg;
        String classpath;
        boolean includeCompile;
    }

    /** 已加载的源文件集合：关键字表路径、文件清单与全部标识符名。 */
    private record LoadedSources(Path tablePath, List<SourceFile> files, Set<String> identifierNames) {}

    /** 四类检查的结果计数：粘连错误、标点错误、新手易错提示、自动纠正提示。 */
    private record CheckCounts(int sticky, int punctuation, int traps, int folded) {}

    /** 解析 `命令 输入… [选项]`；缺值时打印中文错误并退出码 2。 */
    static Options parseOptions(String[] args) {
        Options opt = new Options();
        opt.command = args[0];
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-d")) {
                opt.outDir = Paths.get(optionValue(args, ++i, "-d", "输出目录"));
            } else if (arg.equals("-jar")) {
                opt.jarPath = Paths.get(optionValue(args, ++i, "-jar", "jar 路径"));
            } else if (arg.equals("-主类")) {
                opt.mainClass = optionValue(args, ++i, "-主类", "全限定类名");
            } else if (arg.equals("--表")) {
                opt.tableArg = optionValue(args, ++i, "--表", "关键字表路径");
            } else if (arg.equals("-cp") || arg.equals("--类路径")) {
                opt.classpath = optionValue(args, ++i, arg, "类路径");
            } else if (arg.equals("--允许推断")) {
                allowInference = true;
            } else if (arg.equals("--含编译")) {
                opt.includeCompile = true;
            } else if (arg.equals("--严格标点")) {
                strictPunctuation = true;
            } else {
                opt.inputs.add(arg);
            }
        }
        return opt;
    }

    /** 取选项值：缺少时报「错误：<开关> 后缺少<说明>」并退出码 2。 */
    static String optionValue(String[] args, int index, String flag, String what) {
        if (index >= args.length) {
            fail("错误：" + flag + " 后缺少" + what);
        }
        return args[index];
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Options opt = parseOptions(args);
        validateOptions(opt);
        Path tablePath = resolveTable(opt.tableArg);
        if (opt.command.equals("新建")) {
            createProject(opt.inputs, tablePath);
            return;
        }
        loadKeywords(tablePath);
        loadMethodSugar(tablePath);
        loadDiagnostics(tablePath);
        if (opt.command.equals("反译")) {
            if (opt.outDir == null) {
                fail("错误：反译命令需要指定输出目录，例如 -d 中文源码");
            }
            reverse(opt.inputs, opt.outDir);
            return;
        }
        LoadedSources sources = loadSources(opt.inputs, tablePath);
        CheckCounts counts = runChecks(sources.files(), sources.identifierNames());
        if (opt.command.equals("检查")) {
            reportCheck(opt, sources, counts);
            return;
        }
        if (opt.command.equals("运行")) {
            runExecution(sources.files(), opt.classpath, opt.mainClass);
            return;
        }
        runTranslateOrCompile(opt, sources.files());
    }

    /** 校验命令名与选项组合；不合法时打印用法并以码 2 退出。 */
    private static void validateOptions(Options opt) {
        boolean knownCommand = opt.command.equals("检查") || opt.command.equals("编译")
                || opt.command.equals("转译") || opt.command.equals("反译") || opt.command.equals("运行")
                || opt.command.equals("新建");
        if (!knownCommand || opt.inputs.isEmpty()) {
            usage();
            System.exit(2);
        }
        if (opt.jarPath != null && opt.mainClass == null) {
            fail("错误：-jar 需要同时用 -主类 指定入口类（例如 -主类 demo.app.Main）");
        }
        if (opt.mainClass != null && opt.jarPath == null && !opt.command.equals("运行")) {
            fail("错误：-主类 只能配合 -jar 或 运行 命令使用");
        }
    }

    /** 收集并加载全部源文件，同时记下所有标识符名供空格粘连检查使用。 */
    private static LoadedSources loadSources(List<String> inputs, Path tablePath) throws IOException {
        LinkedHashMap<Path, Path> collected = collect(inputs);
        List<SourceFile> files = new ArrayList<>();
        Set<String> identifierNames = new HashSet<>();
        for (Map.Entry<Path, Path> e : collected.entrySet()) {
            SourceFile sf = load(e.getKey(), e.getValue());
            files.add(sf);
            for (Token t : sf.tokens) {
                if (t.kind == KIND_IDENTIFIER) {
                    identifierNames.add(t.text);
                }
            }
        }
        return new LoadedSources(tablePath, files, identifierNames);
    }

    /** 跑四类检查；有 CJK001/CJK002 错误时打印汇总并以码 1 退出。 */
    private static CheckCounts runChecks(List<SourceFile> files, Set<String> identifierNames) {
        CheckCounts counts = new CheckCounts(checkSticky(files, identifierNames), checkPunctuation(files),
                checkBeginnerTraps(files), checkFoldedPunctuation(files));
        int totalErrors = counts.sticky() + counts.punctuation();
        if (totalErrors > 0) {
            System.out.println();
            System.out.println("检查未通过：" + totalErrors + " 处错误（CJK001 " + counts.sticky()
                    + " 处空格粘连，CJK002 " + counts.punctuation() + " 处全角标点）。");
            System.exit(1);
        }
        return counts;
    }

    /** `检查` 命令：可选含编译，最后输出通过信息与提示。 */
    private static void reportCheck(Options opt, LoadedSources sources, CheckCounts counts) throws IOException {
        List<SourceFile> files = sources.files();
        if (opt.includeCompile) {
            String cp = opt.classpath != null ? opt.classpath : defaultStdlibClasspath(sources.tablePath());
            Path tempOut = Files.createTempDirectory("cjc-check-");
            int compileErrors;
            try {
                compileErrors = compile(files, tempOut, cp);
            } finally {
                deleteRecursively(tempOut);
            }
            if (compileErrors > 0) {
                System.out.println();
                System.out.println("检查未通过：" + compileErrors + " 处编译错误。");
                System.exit(1);
            }
            System.out.println("检查通过（含编译）：" + files.size() + " 个文件（关键字 " + wordCount(false)
                    + " 条 + 类型别名 " + wordCount(true) + " 条，词典 " + DICT_GROUPS.size() + " 组）。");
        } else {
            System.out.println("检查通过：" + files.size() + " 个文件（关键字 " + wordCount(false)
                    + " 条 + 类型别名 " + wordCount(true) + " 条，词典 " + DICT_GROUPS.size() + " 组）。");
        }
        if (counts.folded() > 0) {
            System.out.println("另有 " + counts.folded() + " 处标点已自动纠正（CJK106，仅提示，不影响编译）。");
        }
        if (counts.traps() > 0) {
            System.out.println("另有 " + counts.traps() + " 处新手易错提示（仅建议，不影响编译）。");
        }
    }

    /** `转译` / `编译` 命令：写转译结果，或编译并可选打包 jar。 */
    private static void runTranslateOrCompile(Options opt, List<SourceFile> files) throws IOException {
        if (opt.outDir == null) {
            fail("错误：" + opt.command + "命令需要指定输出目录，例如 -d 输出");
        }
        if (opt.command.equals("转译")) {
            writeTranslated(files, opt.outDir);
            System.out.println("转译完成：" + files.size() + " 个文件 → " + opt.outDir.toAbsolutePath().normalize());
            return;
        }
        int compileErrors = compile(files, opt.outDir, opt.classpath);
        if (compileErrors > 0) {
            System.out.println();
            System.out.println("编译失败：" + compileErrors + " 个错误。");
            System.exit(1);
        }
        System.out.println("编译成功，class 输出目录：" + opt.outDir.toAbsolutePath().normalize());
        if (opt.jarPath != null) {
            if (packageJar(opt.outDir, opt.jarPath, opt.mainClass) != 0) {
                System.out.println();
                System.out.println("打包失败。");
                System.exit(1);
            }
            System.out.println("打包成功，可运行 jar：" + opt.jarPath.toAbsolutePath().normalize());
        }
    }

    /** `新建 项目 <名称>`：在当前目录生成可直接 mvn package 的中文工程骨架。 */
    static void createProject(List<String> inputs, Path tablePath) throws IOException {
        if (inputs.size() != 2 || !inputs.get(0).equals("项目")) {
            fail("错误：用法为 新建 项目 <项目名>，例如 .\\cjc.ps1 新建 项目 我的工具");
        }
        String name = inputs.get(1);
        Path project = Paths.get("").toAbsolutePath().resolve(name);
        if (Files.exists(project)) {
            fail("错误：目录已存在：" + project);
        }
        // 项目里的 pom 需要相对路径指回仓库里的 cjc 与关键字表
        Path repoRoot = tablePath.toAbsolutePath().normalize().getParent().getParent().getParent();
        String relative;
        try {
            relative = project.relativize(repoRoot).toString().replace("\\", "/");
        } catch (IllegalArgumentException crossedDrive) {
            relative = repoRoot.toString().replace("\\", "/");
        }
        Path source = project.resolve("src/main/中文/演示/应用/主程序.java");
        Files.createDirectories(source.getParent());
        String display = name.replace("\\", "/");
        writeUtf8(source, "包 演示.应用;\n\n公有 类 主程序 {\n    入口 {\n        打印行(\"你好，" + display + "！\");\n    }\n}\n");
        writeUtf8(project.resolve("pom.xml"), pomTemplate(name, relative));
        System.out.println("项目已创建：" + project);
        System.out.println("  源码：" + project.relativize(source).toString().replace("\\", "/"));
        System.out.println("  构建：pom.xml（cjc-maven-plugin 0.2.0）");
        System.out.println("下一步：");
        System.out.println("  cd " + name);
        System.out.println("  mvn package");
        System.out.println("  java -jar target\\cjc-app-1.0.jar");
    }

    static void writeUtf8(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    static String pomTemplate(String name, String relative) {
        StringBuilder sb = new StringBuilder(pomHead(name));
        appendCjcPlugin(sb, relative);
        appendJarPlugin(sb);
        sb.append("        </plugins>\n");
        sb.append("    </build>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    /** POM 头部：XML 声明、项目坐标、编码与编译器版本，以及 <plugins> 开始标签。 */
    private static String pomHead(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("    <groupId>demo</groupId>\n");
        sb.append("    <artifactId>cjc-app</artifactId>\n");
        sb.append("    <version>1.0</version>\n");
        sb.append("    <packaging>jar</packaging>\n");
        sb.append("    <name>").append(name).append("</name>\n");
        sb.append("    <properties>\n");
        sb.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        sb.append("        <maven.compiler.release>17</maven.compiler.release>\n");
        sb.append("    </properties>\n");
        sb.append("    <build>\n");
        sb.append("        <plugins>\n");
        return sb.toString();
    }

    /** cjc-maven-plugin：用仓库里的 Cjc.java 与关键字表转译中文源码。 */
    private static void appendCjcPlugin(StringBuilder sb, String relative) {
        sb.append("            <plugin>\n");
        sb.append("                <groupId>cjc</groupId>\n");
        sb.append("                <artifactId>cjc-maven-plugin</artifactId>\n");
        sb.append("                <version>0.2.0</version>\n");
        sb.append("                <executions>\n");
        sb.append("                    <execution>\n");
        sb.append("                        <id>中文转译</id>\n");
        sb.append("                        <goals><goal>translate</goal></goals>\n");
        sb.append("                    </execution>\n");
        sb.append("                </executions>\n");
        sb.append("                <configuration>\n");
        sb.append("                    <cjcJava>${project.basedir}/").append(relative).append("/工具/cjc/Cjc.java</cjcJava>\n");
        sb.append("                    <table>${project.basedir}/").append(relative).append("/工具/cjc/关键字表.txt</table>\n");
        sb.append("                </configuration>\n");
        sb.append("            </plugin>\n");
    }

    /** maven-jar-plugin：可执行 jar 的主类固定为 演示.应用.主程序。 */
    private static void appendJarPlugin(StringBuilder sb) {
        sb.append("            <plugin>\n");
        sb.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        sb.append("                <artifactId>maven-jar-plugin</artifactId>\n");
        sb.append("                <version>3.4.1</version>\n");
        sb.append("                <configuration>\n");
        sb.append("                    <archive>\n");
        sb.append("                        <manifest>\n");
        sb.append("                            <mainClass>演示.应用.主程序</mainClass>\n");
        sb.append("                        </manifest>\n");
        sb.append("                    </archive>\n");
        sb.append("                </configuration>\n");
        sb.append("            </plugin>\n");
    }

    static void usage() {
        System.out.println("Cjc —— 中文 Java 源码检查、转译与编译工具（M2）");
        System.out.println("用法：");
        System.out.println("  java 工具\\cjc\\Cjc.java 检查 <文件或目录...> [--含编译] [--表 <关键字表>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 运行 <文件> [-cp <类路径>] [-主类 <全限定类名>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 编译 <文件或目录...> -d <输出目录> [-cp <类路径>] [-jar <jar路径> -主类 <全限定类名>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 转译 <文件或目录...> -d <输出目录>");
        System.out.println("  java 工具\\cjc\\Cjc.java 反译 <文件或目录...> -d <输出目录>");
        System.out.println("  java 工具\\cjc\\Cjc.java 新建 项目 <项目名>");
        System.out.println("选项：--允许推断 启用「令」/「常量」类型推断糖（默认关闭）。");
        System.out.println("      --含编译   检查通过后再跑一遍 javac，暴露类型错误（默认关闭）。");
        System.out.println("      --严格标点 句号「。」、顿号「、」不再自动纠正，按 CJK002 阻断。");
        System.out.println("规则：中文关键字后必须跟空格或标点，否则会与后面的名称粘连成一个标识符。");
        System.out.println("      全角标点会自动规范化为半角；句号「。」、顿号「、」自动纠正并提示 CJK106；");
        System.out.println("      弯引号、书名号等无法映射的标点仍报 CJK002。");
    }

    /** 致命错误：打印一行中文诊断后以码 2 退出。 */
    private static void fail(String line) {
        System.err.println(line);
        System.exit(2);
    }

    // ------------------------------------------------------------------ 关键字表

    static Path resolveTable(String explicit) {
        if (explicit != null) {
            return Paths.get(explicit);
        }
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("cjc.home");
        if (home != null) {
            candidates.add(Paths.get(home, "工具", "cjc", "关键字表.txt"));
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        candidates.add(cwd.resolve("工具").resolve("cjc").resolve("关键字表.txt"));
        candidates.add(cwd.resolve("关键字表.txt"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        fail("错误：找不到关键字表，请用 --表 <路径> 指定，或在工程根目录运行 cjc.ps1。");
        return null;
    }

    /**
     * `检查 --含编译` 的默认类路径：优先用户 -cp，其次仓库 标准库\target 下最新的
     * cjc-stdlib-*.jar，保证教程/示例里 `导入 中文.标准库.*;` 能编译通过。
     */
    static String defaultStdlibClasspath(Path tablePath) {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("cjc.home");
        if (home != null && !home.isBlank()) {
            roots.add(Paths.get(home));
        }
        Path table = tablePath.toAbsolutePath().normalize();
        if (table.getParent() != null && table.getParent().getParent() != null) {
            roots.add(table.getParent().getParent().getParent());
        }
        for (Path root : roots) {
            Path target = root.resolve("标准库").resolve("target");
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(target)) {
                Path jar = stream
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("cjc-stdlib-") && name.endsWith(".jar");
                        })
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .orElse(null);
                if (jar != null) {
                    return jar.toAbsolutePath().normalize().toString();
                }
            } catch (IOException ignored) {
                // target 目录不可读时按无类路径处理
            }
        }
        return null;
    }

    /** 词表文件的一行：行号（从 1 开始）与剥掉首行 BOM 后的原始文本。 */
    static final class TableLine {
        final int lineNo;
        final String text;

        TableLine(int lineNo, String text) {
            this.lineNo = lineNo;
            this.text = text;
        }
    }

    /**
     * 读词表文件：统一剥掉首行 BOM，跳过空行与 `#` 注释行，返回带真实行号的原始文本。
     * 关键字表、近义词典、方法糖表、诊断中文表四个加载器共用。
     */
    static List<TableLine> readTableLines(Path path) throws IOException {
        List<TableLine> lines = new ArrayList<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw;
            if (lineNo == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.strip().isEmpty() || line.strip().startsWith("#")) {
                continue;
            }
            lines.add(new TableLine(lineNo, line));
        }
        return lines;
    }

    /** 汇总词表问题：逐条打印「<表名>错误：…」，再打印校验未通过并退出码 2。 */
    static void failTable(String label, Path path, List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }
        for (String problem : problems) {
            System.err.println(label + "错误：" + problem);
        }
        fail(label + "校验未通过：" + problems.size() + " 处问题，文件 " + path);
    }

    /**
     * 加载近义词典：与关键字表同目录的 近义词典.txt，格式「组名=[近义词1，近义词2，…]」。
     * 组名即反译规范名；词典可在不改关键字表的情况下随时增删近义词。
     */
    static void loadDictionary(Path tablePath, List<String> problems) throws IOException {
        Path dictPath = tablePath.resolveSibling("近义词典.txt");
        if (!Files.exists(dictPath)) {
            System.err.println("提示：未找到近义词典 " + dictPath + "，按关键字表内写法直接使用。");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (TableLine entry : readTableLines(dictPath)) {
            parseDictionaryLine(entry, seen, problems);
        }
    }

    /** 解析近义词典一行：校验「组名=[近义词1，近义词2]」并写入 DICT_GROUPS。 */
    private static void parseDictionaryLine(TableLine entry, Set<String> seen, List<String> problems) {
        int lineNo = entry.lineNo;
        String line = entry.text.strip();
        int eq = line.indexOf('=');
        if (eq <= 0 || eq == line.length() - 1) {
            problems.add("近义词典第" + lineNo + "行格式错误，应为「组名=[近义词1，近义词2]」：" + line);
            return;
        }
        String name = line.substring(0, eq).strip();
        String right = line.substring(eq + 1).strip();
        if (!right.startsWith("[") || !right.endsWith("]")) {
            problems.add("近义词典第" + lineNo + "行缺少方括号：" + line);
            return;
        }
        if (DICT_GROUPS.containsKey(name)) {
            problems.add("近义词典第" + lineNo + "行组名重复：" + name);
            return;
        }
        List<String> synonyms = new ArrayList<>();
        if (!collectSynonyms(lineNo, line, right, synonyms, seen, problems)) {
            return;
        }
        seen.add(name);
        DICT_GROUPS.put(name, synonyms);
    }

    /** 拆分方括号内的近义词列表并去重；返回 false 表示列表为空（已记入 problems）。 */
    private static boolean collectSynonyms(int lineNo, String line, String right, List<String> synonyms,
                                           Set<String> seen, List<String> problems) {
        boolean any = false;
        for (String part : right.substring(1, right.length() - 1).split("[，,]")) {
            String word = part.strip();
            if (word.isEmpty()) {
                continue;
            }
            any = true;
            if (!seen.add(word)) {
                problems.add("近义词典第" + lineNo + "行近义词重复：" + word);
                continue;
            }
            synonyms.add(word);
        }
        if (!any) {
            problems.add("近义词典第" + lineNo + "行近义词列表为空：" + line);
        }
        return any;
    }

    /** 把关键字表中的中文写法展开为「规范名 + 词典同义词」；不在词典中的词按原样使用。 */
    static List<String> expandChinese(String word) {
        List<String> words = new ArrayList<>();
        words.add(word);
        List<String> synonyms = DICT_GROUPS.get(word);
        if (synonyms != null) {
            words.addAll(synonyms);
        }
        return words;
    }

    /** 英文侧允许是 Java 关键字，也允许是受支持的类型别名（如 String、Object）。 */
    static boolean isTranslatable(String english) {
        return JAVA_KEYWORDS.contains(english) || JAVA_LANG_TYPES.contains(english);
    }

    /** 中文词是否为类型名：基本类型、包装类、常用类或标准库类。 */
    static boolean isTypeWord(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        if (STDLIB_CLASSES.contains(word)) {
            return true;
        }
        String english = KEYWORDS.get(word);
        return english != null && (JAVA_LANG_TYPES.contains(english) || PRIMITIVE_TYPES.contains(english));
    }

    /**
     * 收集文件里被声明为变量/字段/参数的名称（「类型 名称」形式）。
     * 同名变量优先：声明过 `文本` 变量后，`文本.修剪()` 仍按成员糖转译；
     * 只有未被遮蔽的类名才享受「标准库类名不做成员糖」的豁免。
     */
    static Set<String> declaredNames(List<Token> tokens, String source) {
        Set<String> names = new HashSet<>();
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_IDENTIFIER || t.text == null) {
                continue;
            }
            String next = textAt(source, tokens, i + 1);
            if (!(next.equals("=") || next.equals(";") || next.equals(",") || next.equals(")") || next.equals(":"))) {
                continue;
            }
            String prev = textAt(source, tokens, i - 1);
            if (prev.equals(">") || prev.equals("]") || isTypeWord(prev)) {
                names.add(t.text);
            }
        }
        return names;
    }

    /** 接收者是否为未被同名变量遮蔽的标准库类名（`列表`/`映射`/`文本`/`并发`…）。 */
    static boolean isStdlibReceiver(List<Token> tokens, String source, int idx, Set<String> declared) {
        if (idx < 0) {
            return false;
        }
        Token recv = tokens.get(idx);
        String recvText = textAt(source, tokens, idx);
        return recv.kind == KIND_IDENTIFIER && STDLIB_CLASSES.contains(recvText) && !declared.contains(recvText);
    }

    /** 统计关键字表里的中文词条数：typeAlias 为 true 时数类型别名，否则数关键字。 */
    static int wordCount(boolean typeAlias) {
        int n = 0;
        for (String english : KEYWORDS.values()) {
            if (JAVA_LANG_TYPES.contains(english) == typeAlias) {
                n++;
            }
        }
        return n;
    }

    static void loadKeywords(Path tablePath) throws IOException {
        if (!Files.exists(tablePath)) {
            fail("错误：找不到关键字表 " + tablePath);
        }
        List<String> problems = new ArrayList<>();
        loadDictionary(tablePath, problems);
        for (TableLine entry : readTableLines(tablePath)) {
            parseKeywordLine(entry, problems);
        }
        if (KEYWORDS.isEmpty()) {
            problems.add("关键字表为空");
        }
        // 前缀允许：中文关键字之间可以存在前缀关系（如「假」与「假如」、「若」与「若是」）。
        // 翻译按整词匹配（标识符全文等于关键字才命中），因此长短词不会互相干扰，
        // 不必再做前缀无关校验；最长优先只用于 CJK001 粘连启发式的定位。
        if (!problems.isEmpty()) {
            failTable("关键字表", tablePath, problems);
        }
        buildKeywordIndexes();
    }

    /** 解析一行关键字表：支持「中文=英文」与「英文=[中文1，中文2]」两种写法。 */
    private static void parseKeywordLine(TableLine entry, List<String> problems) {
        int lineNo = entry.lineNo;
        String line = entry.text.strip();
        int eq = line.indexOf('=');
        if (eq <= 0 || eq == line.length() - 1) {
            problems.add("第" + lineNo + "行格式错误，应为「中文=英文」或「英文=[中文1，中文2]」：" + line);
            return;
        }
        String left = line.substring(0, eq).strip();
        String right = line.substring(eq + 1).strip();
        if (right.startsWith("[") && right.endsWith("]")) {
            parseKeywordList(lineNo, line, left, right, problems);
            return;
        }
        String chinese = left;
        String english = right;
        if (!isTranslatable(english)) {
            problems.add("第" + lineNo + "行英文关键字或类型不存在：" + english);
            return;
        }
        for (String word : expandChinese(chinese)) {
            if (KEYWORDS.containsKey(word)) {
                problems.add("第" + lineNo + "行中文关键字重复：" + word);
                continue;
            }
            // 英文允许重复：多个中文同义词映射到同一个英文关键字
            KEYWORDS.put(word, english);
        }
    }

    /** 解析「英文=[中文1，中文2]」列表格式；列表第一项为反译规范名。 */
    private static void parseKeywordList(int lineNo, String line, String english, String right, List<String> problems) {
        if (!isTranslatable(english)) {
            problems.add("第" + lineNo + "行英文关键字或类型不存在：" + english);
            return;
        }
        String inner = right.substring(1, right.length() - 1);
        boolean any = false;
        for (String part : inner.split("[，,]")) {
            String chinese = part.strip();
            if (chinese.isEmpty()) {
                continue;
            }
            any = true;
            for (String word : expandChinese(chinese)) {
                if (KEYWORDS.containsKey(word)) {
                    problems.add("第" + lineNo + "行中文关键字重复：" + word);
                    continue;
                }
                KEYWORDS.put(word, english);
            }
        }
        if (!any) {
            problems.add("第" + lineNo + "行同义词列表为空：" + line);
        }
    }

    /** 重建派生索引：最长优先中文表与英文反查表。 */
    private static void buildKeywordIndexes() {
        SORTED_KEYWORDS.clear();
        SORTED_KEYWORDS.addAll(KEYWORDS.keySet());
        // 最长优先，用于 CJK001 粘连启发式：优先按更长的关键字定位缺空格的位置
        SORTED_KEYWORDS.sort((a, b) -> b.length() - a.length());

        CHINESE_BY_ENGLISH.clear();
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            // 同义词时保留表中第一条为反译规范名
            CHINESE_BY_ENGLISH.putIfAbsent(e.getValue(), e.getKey());
        }
        ENGLISH_KEYWORDS.clear();
        ENGLISH_KEYWORDS.addAll(CHINESE_BY_ENGLISH.keySet());
    }

    /**
     * 加载方法糖表：与关键字表同目录的 方法糖.txt，分区 [成员] / [声明] / [静态] / [白名单]。
     * 文件是唯一数据源：缺失或格式错误都报中文错误并退出码 2。
     * 表的声明顺序即匹配优先级，加载时按文件顺序重建。
     */
    static void loadMethodSugar(Path tablePath) throws IOException {
        Path sugarPath = tablePath.resolveSibling("方法糖.txt");
        if (!Files.exists(sugarPath)) {
            fail("方法糖表缺失：" + sugarPath + "（与关键字表必须放在同一目录）");
        }
        SugarTables tables = new SugarTables();
        for (TableLine entry : readTableLines(sugarPath)) {
            parseSugarLine(entry, tables);
        }
        for (int i = 0; i < 4; i++) {
            if (!tables.seen[i]) {
                tables.problems.add("缺少分区 [" + methodSugarSectionName(i) + "]");
            }
        }
        if (tables.sections.get("成员").isEmpty() || tables.sections.get("声明").isEmpty()
                || tables.sections.get("静态").isEmpty() || tables.whitelist.isEmpty()) {
            tables.problems.add("方法糖表存在空分区");
        }
        if (!tables.problems.isEmpty()) {
            failTable("方法糖表", sugarPath, tables.problems);
        }
        MEMBER_CALLS.clear();
        MEMBER_CALLS.putAll(tables.sections.get("成员"));
        MEMBER_DECLS.clear();
        MEMBER_DECLS.putAll(tables.sections.get("声明"));
        STATIC_CALLS.clear();
        STATIC_CALLS.putAll(tables.sections.get("静态"));
        STDLIB_CLASSES.clear();
        STDLIB_CLASSES.addAll(tables.whitelist);
    }

    /** 方法糖表解析状态：四个分区、白名单、已见分区标记、错误列表与当前分区。 */
    private static final class SugarTables {
        final Map<String, LinkedHashMap<String, String>> sections = new LinkedHashMap<>();
        final Set<String> whitelist = new LinkedHashSet<>();
        final boolean[] seen = new boolean[4];
        final List<String> problems = new ArrayList<>();
        String section;

        SugarTables() {
            sections.put("成员", new LinkedHashMap<>());
            sections.put("声明", new LinkedHashMap<>());
            sections.put("静态", new LinkedHashMap<>());
        }

        LinkedHashMap<String, String> current() {
            return sections.get(section);
        }
    }

    /** 解析一行方法糖表：分区头、白名单或「中文=英文」条目。 */
    private static void parseSugarLine(TableLine entry, SugarTables tables) {
        int lineNo = entry.lineNo;
        String line = entry.text.strip();
        if (line.startsWith("[") && line.endsWith("]")) {
            parseSugarSection(lineNo, line, tables);
            return;
        }
        if (tables.section == null) {
            tables.problems.add("第" + lineNo + "行不属于任何分区：" + line);
            return;
        }
        if (tables.section.equals("白名单")) {
            parseWhitelist(line, tables);
            return;
        }
        parseSugarEntry(lineNo, line, tables);
    }

    /** 分区头：`[成员]` / `[声明]` / `[静态]` / `[白名单]`；未知分区记错误并清空当前分区。 */
    private static void parseSugarSection(int lineNo, String line, SugarTables tables) {
        String name = line.substring(1, line.length() - 1).strip();
        int index = methodSugarSectionIndex(name);
        if (index < 0) {
            tables.problems.add("第" + lineNo + "行未知分区：" + line + "（只支持 [成员] / [声明] / [静态] / [白名单]）");
            tables.section = null;
        } else {
            tables.section = name;
            tables.seen[index] = true;
        }
    }

    /** 白名单行：斜杠分隔的标准库类名，逐个加入白名单。 */
    private static void parseWhitelist(String line, SugarTables tables) {
        for (String part : line.split("/")) {
            String name = part.strip();
            if (!name.isEmpty()) {
                tables.whitelist.add(name);
            }
        }
    }

    /** 条目行：`中文=英文`，按当前分区去重并校验英文格式后写入。 */
    private static void parseSugarEntry(int lineNo, String line, SugarTables tables) {
        int eq = line.indexOf('=');
        if (eq <= 0 || eq == line.length() - 1) {
            tables.problems.add("第" + lineNo + "行格式错误，应为「中文=英文」：" + line);
            return;
        }
        String chinese = line.substring(0, eq).strip();
        String english = line.substring(eq + 1).strip();
        LinkedHashMap<String, String> target = tables.current();
        if (target.containsKey(chinese)) {
            tables.problems.add("第" + lineNo + "行中文重复：" + chinese);
            return;
        }
        if (!isMethodSugarEnglish(english, "静态".equals(tables.section))) {
            tables.problems.add("第" + lineNo + "行英文格式不正确：" + english);
            return;
        }
        target.put(chinese, english);
    }

    static int methodSugarSectionIndex(String section) {
        switch (section) {
            case "成员": return 0;
            case "声明": return 1;
            case "静态": return 2;
            case "白名单": return 3;
            default: return -1;
        }
    }

    static String methodSugarSectionName(int index) {
        switch (index) {
            case 0: return "成员";
            case 1: return "声明";
            case 2: return "静态";
            default: return "白名单";
        }
    }

    /** 成员/声明值必须是合法 Java 标识符；静态值允许 `Math.abs` 这类点分形式。 */
    static boolean isMethodSugarEnglish(String name, boolean dotted) {
        if (name.isEmpty()) {
            return false;
        }
        if (dotted) {
            return name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
        }
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }


    // ------------------------------------------------------------------ 文件收集

    static LinkedHashMap<Path, Path> collect(List<String> inputs) throws IOException {
        LinkedHashMap<Path, Path> map = new LinkedHashMap<>();
        Map<Path, Path> owners = new LinkedHashMap<>();
        for (String input : inputs) {
            Path root = Paths.get(input).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                fail("错误：找不到路径 " + input);
            }
            if (Files.isDirectory(root)) {
                List<Path> list = new ArrayList<>();
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".java"))
                            .forEach(list::add);
                }
                list.sort(Comparator.comparing(Path::toString));
                for (Path p : list) {
                    addSource(map, owners, p, root.relativize(p));
                }
            } else {
                addSource(map, owners, root, root.getFileName());
            }
        }
        if (map.isEmpty()) {
            fail("错误：没有找到 .java 文件");
        }
        return map;
    }

    /**
     * 转译与编译都按「相对源根的路径」输出，因此不同源根下出现同名相对路径时必须报错，
     * 否则后写入的文件会静默覆盖前一个。
     */
    static void addSource(LinkedHashMap<Path, Path> map, Map<Path, Path> owners,
                          Path file, Path relative) {
        Path prev = owners.putIfAbsent(relative, file);
        if (prev != null && !prev.equals(file)) {
            System.err.println("错误：不同源根目录下存在相同的相对路径「" + relative + "」：");
            System.err.println("      " + prev);
            System.err.println("      " + file);
            System.err.println("  输出按相对路径落盘，无法同时保留两份，请调整目录结构或分开执行。");
            System.exit(2);
        }
        map.putIfAbsent(file, relative);
    }

    static String read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    static SourceFile load(Path path, Path relative) throws IOException {
        String text = read(path);
        List<Token> tokens = scan(text, false);
        boolean script = looksLikeScript(text, tokens);
        int lineOffset = 0;
        // A3：文件里没有 导入 / 包，或只导入了 中文.标准库.* 时，自动补 6 个 JDK 包。
        // 不自动补 中文.标准库.*：标准库类名（文本、数值、文件）容易与用户变量同名。
        if (!hasImportOrPackage(tokens) || hasOnlyStdlibImports(text, tokens)) {
            text = "导入 java.util.*;\n导入 java.nio.file.*;\n导入 java.io.*;\n导入 java.util.stream.*;\n"
                    + "导入 java.util.concurrent.*;\n导入 java.util.logging.*;\n" + text;
            lineOffset = 6;
            tokens = scan(text, false);
        }
        if (script) {
            // 脚本模式：文件里只写语句，这里补上 类 + main 的外壳。
            // 开头的 导入 语句必须留在类外，先切出来再包裹正文。
            int importsEnd = importsEnd(text, tokens);
            String imports = text.substring(0, importsEnd);
            String body = text.substring(importsEnd);
            if (!imports.isEmpty()) {
                // 导入语句与包裹外壳之间只保留一个换行，避免多出一个空行导致行号错位
                body = stripOneLineBreak(body);
            }
            lineOffset += 2;
            text = (imports.isEmpty() ? "" : imports + "\n") + wrapScript(body, classNameOf(path));
        }
        SourceFile sf = new SourceFile(path, relative, text);
        sf.script = script;
        sf.lineOffset = lineOffset;
        sf.tokens = scan(sf.original, false);
        sf.origLineStarts = lineStarts(sf.original);
        sf.translated = translate(sf);
        return sf;
    }

    /**
     * 脚本模式判定：顶层（花括号深度 0）没有 类 / 接口 / 枚举 / 包 时，
     * 整份文件按「语句」处理（开头的 导入 允许保留）。
     */
    static boolean looksLikeScript(String source, List<Token> tokens) {
        int depth = 0;
        for (Token t : tokens) {
            String text = t.text != null ? t.text : source.substring(t.start, t.end);
            if (text.equals("{")) {
                depth++;
                continue;
            }
            if (text.equals("}")) {
                depth--;
                continue;
            }
            if (depth == 0 && t.kind == KIND_KEYWORD) {
                String english = KEYWORDS.get(t.text);
                if (english == null) {
                    continue;
                }
                if (english.equals("class") || english.equals("interface") || english.equals("enum")
                        || english.equals("record") || english.equals("package")) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 文件是否已有 导入 / 包 语句（只认关键字 token，注释与字符串不参与）。 */
    static boolean hasImportOrPackage(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.kind != KIND_KEYWORD) {
                continue;
            }
            String english = KEYWORDS.get(t.text);
            if (english != null && (english.equals("import") || english.equals("package"))) {
                return true;
            }
        }
        return false;
    }

    /** 是否只导入了 中文.标准库.* / 中文.标准库.Xxx；有 包 声明或其他导入时返回 false。 */
    static boolean hasOnlyStdlibImports(String source, List<Token> tokens) {
        boolean sawImport = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_KEYWORD) {
                continue;
            }
            String english = KEYWORDS.get(t.text);
            if (english == null) {
                continue;
            }
            if (english.equals("package")) {
                return false;
            }
            if (!english.equals("import")) {
                continue;
            }
            sawImport = true;
            StringBuilder name = new StringBuilder();
            for (int j = i + 1; j < tokens.size(); j++) {
                Token next = tokens.get(j);
                String text = textAt(source, tokens, j);
                if (text.equals(";") || text.equals("{")) {
                    break;
                }
                name.append(text);
            }
            if (!name.toString().startsWith("中文.标准库")) {
                return false;
            }
        }
        return sawImport;
    }

    /** 返回脚本开头所有 `导入 …;` 的结束位置（不含其后的换行）；没有导入时返回 0。 */
    static int importsEnd(String source, List<Token> tokens) {
        int depth = 0;
        boolean inImport = false;
        int end = 0;
        for (Token t : tokens) {
            String text = t.text != null ? t.text : source.substring(t.start, t.end);
            if (text.equals("{")) {
                depth++;
            } else if (text.equals("}")) {
                depth--;
            } else if (depth == 0 && t.kind == KIND_KEYWORD) {
                String english = KEYWORDS.get(t.text);
                if (english != null && english.equals("package")) {
                    return 0;  // 带包名的文件不走脚本模式
                }
                if (english != null && english.equals("import")) {
                    inImport = true;
                }
            }
            if (inImport && text.equals(";")) {
                inImport = false;
                end = t.end;
            }
        }
        return end;
    }

    /** 去掉开头的一个换行（\r\n / \n / \r），保持其余行与列不变。 */
    static String stripOneLineBreak(String text) {
        if (text.startsWith("\r\n")) {
            return text.substring(2);
        }
        if (text.startsWith("\n") || text.startsWith("\r")) {
            return text.substring(1);
        }
        return text;
    }

    /** 用中文 Java 外壳包裹脚本正文；正文前固定插入 2 行。 */
    static String wrapScript(String body, String className) {
        return "类 " + className + " {\n"
                + "    公有 静态 无 main(字符串[] 参数) {\n"
                + body + (body.endsWith("\n") ? "" : "\n")
                + "    }\n"
                + "}\n";
    }

    /** 文件名去掉 .java 后的类名；非法字符替换为下划线，数字开头补「类_」。 */
    static String classNameOf(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sb.append(ok ? c : '_');
        }
        if (sb.length() == 0) {
            return "脚本";
        }
        return Character.isJavaIdentifierStart(sb.charAt(0)) ? sb.toString() : "类_" + sb;
    }

    static void runExecution(List<SourceFile> files, String classpath, String mainClass) throws IOException {
        String entry = mainClass != null ? mainClass : entryClass(files);
        if (entry == null) {
            fail("错误：无法确定入口类，请用 -主类 <全限定类名> 指定");
        }
        Path outDir = Files.createTempDirectory("cjc-run-");
        int exitCode = 1;
        try {
            if (compile(files, outDir, classpath) > 0) {
                System.out.println();
                System.out.println("运行失败：编译未通过。");
            } else {
                List<String> cmd = new ArrayList<>();
                cmd.add(javaBinary());
                cmd.add("-Dstdout.encoding=UTF-8");
                cmd.add("-Dstderr.encoding=UTF-8");
                String cp = outDir.toAbsolutePath().normalize().toString();
                if (classpath != null && !classpath.isBlank()) {
                    cp = cp + java.io.File.pathSeparator + classpath;
                }
                cmd.add("-cp");
                cmd.add(cp);
                cmd.add(entry);
                System.out.println();
                Process process = new ProcessBuilder(cmd)
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .start();
                Thread pump = new Thread(() -> pumpStandardError(process), "cjc-stderr");
                pump.setDaemon(true);
                pump.start();
                try {
                    exitCode = process.waitFor();
                    pump.join(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exitCode = 130;
                }
            }
        } finally {
            deleteRecursively(outDir);
        }
        System.exit(exitCode);
    }

    /** 实时把子进程的 stderr 逐行转成中文后输出；不属于异常栈的行原样保留。 */
    static void pumpStandardError(Process process) {
        boolean suggested = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] shown = localizeRuntimeLine(line);
                if (shown == null) {
                    System.err.println(line);
                    continue;
                }
                System.err.println(shown[0]);
                if (shown[1] != null && !suggested) {
                    System.err.println("    建议：" + shown[1]);
                    suggested = true;
                }
            }
        } catch (IOException ignored) {
            // 子进程已退出，stderr 读取中断属正常情况
        }
    }

    static final Pattern RUNTIME_HEADER = Pattern.compile(
            "^(Exception|Error) in thread \"(.+?)\" (.+)$");
    static final Pattern STACK_FRAME = Pattern.compile("^at (\\S+)\\(([^)]+)\\)$");
    static final Pattern STACK_POSITION = Pattern.compile("([^\\\\/:()]+?\\.java):(\\d+)");

    /**
     * 把 JVM 运行时异常栈的一行转成中文。
     * 返回 {消息, 建议}；不是异常栈的行返回 null，由调用方原样输出。
     */
    static String[] localizeRuntimeLine(String raw) {
        String line = raw.trim();
        Matcher header = RUNTIME_HEADER.matcher(line);
        if (header.matches()) {
            String thread = header.group(2);
            String detail = header.group(3).trim();
            int colon = detail.indexOf(':');
            String type = colon >= 0 ? detail.substring(0, colon) : detail;
            String message = colon >= 0 ? detail.substring(colon + 1).trim() : "";
            message = chineseRuntimeMessage(message);
            String simple = type.substring(type.lastIndexOf('.') + 1);
            String chinese = chineseExceptionName(simple);
            StringBuilder sb = new StringBuilder("程序运行出错（线程「").append(thread).append("」）：").append(chinese);
            if (!message.isEmpty()) {
                sb.append("：").append(message);
            }
            if (chinese.equals(simple)) {
                sb.append("（异常类型 ").append(type).append("）");
            }
            return new String[]{sb.toString(), exceptionSuggestion(simple)};
        }
        if (line.startsWith("Caused by:")) {
            String rest = line.substring("Caused by:".length()).trim();
            int colon = rest.indexOf(':');
            String type = colon >= 0 ? rest.substring(0, colon) : rest;
            String message = colon >= 0 ? rest.substring(colon + 1).trim() : "";
            String simple = type.substring(type.lastIndexOf('.') + 1);
            String text = "由以下原因引起：" + chineseExceptionName(simple) + (message.isEmpty() ? "" : "：" + message);
            return new String[]{text, null};
        }
        Matcher frame = STACK_FRAME.matcher(line);
        if (frame.matches()) {
            String where = frame.group(1);
            String source = frame.group(2)
                    .replace("Native Method", "本地方法")
                    .replace("Unknown Source", "未知位置");
            Matcher pos = STACK_POSITION.matcher(source);
            if (pos.matches()) {
                source = pos.group(1) + " 第" + pos.group(2) + "行";
            }
            return new String[]{"    位置：" + where + "（" + source + "）", null};
        }
        return null;
    }

    /** 常见异常消息的英文片段换成中文；无法识别的原样返回。 */
    static String chineseRuntimeMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        Matcher m = Pattern.compile("Index (\\d+) out of bounds for length (\\d+)").matcher(message);
        if (m.matches()) {
            return "下标 " + m.group(1) + " 超出长度 " + m.group(2);
        }
        m = Pattern.compile("String index out of range: (\\d+)").matcher(message);
        if (m.matches()) {
            return "字符串下标越界：" + m.group(1);
        }
        m = Pattern.compile("For input string: \"(.+)\"").matcher(message);
        if (m.matches()) {
            return "输入文本「" + m.group(1) + "」不是合法数字";
        }
        if (message.endsWith("/ by zero")) {
            return "除数为 0";
        }
        return message;
    }

    static String chineseExceptionName(String simple) {
        switch (simple) {
            case "NullPointerException": return "空指针异常";
            case "ArrayIndexOutOfBoundsException": return "数组越界异常";
            case "IndexOutOfBoundsException": return "下标越界异常";
            case "StringIndexOutOfBoundsException": return "字符串下标越界异常";
            case "ArithmeticException": return "算术异常";
            case "NumberFormatException": return "数字格式异常";
            case "IllegalArgumentException": return "非法参数异常";
            case "IllegalStateException": return "非法状态异常";
            case "ClassCastException": return "类型转换异常";
            case "NoSuchElementException": return "没有更多元素";
            case "InputMismatchException": return "输入类型不匹配";
            case "IOException": return "输入输出异常";
            case "FileNotFoundException": return "文件未找到异常";
            case "UnsupportedOperationException": return "不支持的操作异常";
            case "ConcurrentModificationException": return "并发修改异常";
            case "InterruptedException": return "中断异常";
            case "StackOverflowError": return "栈溢出错误";
            case "OutOfMemoryError": return "内存不足错误";
            case "ExceptionInInitializerError": return "初始化错误";
            case "NoClassDefFoundError": return "找不到类定义";
            default: return simple;
        }
    }

    static String exceptionSuggestion(String simple) {
        switch (simple) {
            case "NullPointerException": return "检查对象是否为空，必要时先写「若 (对象 != 空) { … }」。";
            case "ArrayIndexOutOfBoundsException":
            case "IndexOutOfBoundsException":
                return "检查下标范围：0 <= 下标 < 数组.长度（集合用 元素数）。";
            case "StringIndexOutOfBoundsException": return "检查字符串下标范围：0 <= 下标 < 文本.长度()。";
            case "ArithmeticException": return "检查除数是否为 0。";
            case "NumberFormatException": return "输入的文本不是数字，先用「尝试 { 整数包装.解析(…) } 捕获 (数字格式异常 出错) { … }」。";
            case "NoSuchElementException": return "输入已经读完，先判断「输入.有下一行()」再读行。";
            case "InputMismatchException": return "本次输入类型与读取方法不匹配，检查是否该用 读整数 / 读双浮点。";
            case "FileNotFoundException": return "检查文件路径是否存在、是否有读取权限。";
            case "IOException": return "检查路径、权限与磁盘空间；读写代码放进「尝试 / 捕获 (输入输出异常 出错)」。";
            case "UnsupportedOperationException": return "该集合是只读的（例如 数组助手.转清单 的结果），先复制成「数组清单」再增删。";
            case "ConcurrentModificationException": return "遍历时不要直接增删集合，改用 迭代器.移除 或先复制一份。";
            case "ClassCastException": return "向下转型前先判断：「若 (实例 是 目标类型) { … }」。";
            case "StackOverflowError": return "递归缺少终止条件，检查递归方法的退出分支。";
            case "OutOfMemoryError": return "一次性创建的数据过多，分批处理或增加 -Xmx。";
            default: return null;
        }
    }

    /** 运行命令的入口类：只有一个文件时按「文件名 = 类名」推导。 */
    static String entryClass(List<SourceFile> files) {
        if (files.size() != 1) {
            return null;
        }
        return classNameOf(files.get(0).path);
    }

    static String javaBinary() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin", "java");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            bin = Paths.get(bin + ".exe");
        }
        return bin.toString();
    }


    // ------------------------------------------------------------------ 词法扫描

    /**
     * @param english true 表示反译模式：把英文关键字识别为 KIND_EN_KEYWORD；
     *                false 表示正常模式：把中文关键字识别为 KIND_KEYWORD。
     */
    static List<Token> scan(String source, boolean english) {
        List<Token> tokens = new ArrayList<>();
        int n = source.length();
        int i = 0;
        while (i < n) {
            i = scanAt(source, i, english, tokens);
        }
        return tokens;
    }

    /** 扫描从 i 开始的一个词法单元，追加到 tokens，返回下一个扫描位置。 */
    static int scanAt(String source, int i, boolean english, List<Token> tokens) {
        int cp = source.codePointAt(i);
        int width = Character.charCount(cp);
        int opaque = scanOpaqueSpan(source, i, cp, width, english, tokens);
        if (opaque >= 0) {
            return opaque;
        }
        // 全角 ASCII 标点（U+FF01..U+FF5E）：平移 0xFEE0 得到半角
        if (cp >= 0xFF01 && cp <= 0xFF5E) {
            tokens.add(new Token(KIND_PUNCT, i, i + 1, String.valueOf((char) (cp - 0xFEE0))));
            return i + 1;
        }
        // 句号「。」与顿号「、」：默认自动纠正（只提示 CJK106），`--严格标点` 时仍报 CJK002。
        if (cp == 0x3001 || cp == 0x3002) {
            return scanFoldedPunct(source, i, cp, width, tokens);
        }
        // 无法映射到半角的 CJK 标点：保留原文，报 CJK002
        if (CJK_PUNCTUATION.contains(cp)) {
            return addBadPunct(tokens, i, cp, width);
        }
        tokens.add(new Token(KIND_OTHER, i, i + width, null));
        return i + width;
    }

    /** 整段跳过的情形：空白、注释、字面量、标识符、数字；返回 -1 表示不是这类片段。 */
    private static int scanOpaqueSpan(String source, int i, int cp, int width, boolean english, List<Token> tokens) {
        // 全角空格：javac 会报「非法字符」，这里统一转成半角空格
        if (cp == 0x3000) {
            tokens.add(new Token(KIND_PUNCT, i, i + 1, " "));
            return i + 1;
        }
        if (Character.isWhitespace(cp)) {
            return i + width;
        }
        int skipped = skipComment(source, i);
        if (skipped >= 0) {
            return skipped;
        }
        // 文本块
        if (cp == '"' && source.startsWith("\"\"\"", i)) {
            return addRangeToken(tokens, i, skipTextBlock(source, i));
        }
        // 字符串 / 字符字面量
        if (cp == '"' || cp == '\'') {
            return addRangeToken(tokens, i, skipLiteral(source, i, cp));
        }
        // 标识符（含中文标识符）
        if (Character.isJavaIdentifierStart(cp)) {
            return scanIdentifier(source, i, english, tokens);
        }
        // 数字字面量（整体跳过，不参与关键字判定）
        if (Character.isDigit(cp) || (cp == '.' && i + 1 < source.length()
                && Character.isDigit(source.charAt(i + 1)))) {
            return addRangeToken(tokens, i, skipNumber(source, i));
        }
        return -1;
    }

    /** 句号「。」与顿号「、」：默认自动纠正（只提示 CJK106），`--严格标点` 时仍报 CJK002。 */
    static int scanFoldedPunct(String source, int i, int cp, int width, List<Token> tokens) {
        String folded = strictPunctuation ? null : foldSentencePunctuation(cp, source, i);
        tokens.add(new Token(folded == null ? KIND_BAD_PUNCT : KIND_PUNCT,
                i, i + width, folded == null ? new String(Character.toChars(cp)) : folded));
        return i + width;
    }

    /** 追加一个无法映射到半角的 CJK 标点 token（报 CJK002），返回其后位置。 */
    static int addBadPunct(List<Token> tokens, int i, int cp, int width) {
        tokens.add(new Token(KIND_BAD_PUNCT, i, i + width, new String(Character.toChars(cp))));
        return i + width;
    }

    /** 注释：行注释跳到行尾，块注释跳到右界之后；不是注释返回 -1。 */
    static int skipComment(String source, int i) {
        if (i + 1 >= source.length() || source.charAt(i) != '/') {
            return -1;
        }
        char next = source.charAt(i + 1);
        if (next == '/') {
            return skipLineComment(source, i);
        }
        if (next == '*') {
            return skipBlockComment(source, i);
        }
        return -1;
    }

    /** 追加一个原文区间 token（文本块 / 字面量 / 数字），返回区间末尾。 */
    static int addRangeToken(List<Token> tokens, int start, int end) {
        tokens.add(new Token(KIND_OTHER, start, end, null));
        return end;
    }

    /** 标识符（含中文标识符）：登记关键字或普通标识符，返回标识符末尾。 */
    static int scanIdentifier(String source, int i, boolean english, List<Token> tokens) {
        int n = source.length();
        int start = i;
        i += Character.charCount(source.codePointAt(i));
        while (i < n) {
            int next = source.codePointAt(i);
            if (!Character.isJavaIdentifierPart(next)) {
                break;
            }
            i += Character.charCount(next);
        }
        String text = source.substring(start, i);
        tokens.add(new Token(identifierKind(text, english), start, i, text));
        return i;
    }

    /** 标识符分类：反译时认英文关键字，正常转译时认中文关键字，其余为普通标识符。 */
    static int identifierKind(String text, boolean english) {
        if (english) {
            return ENGLISH_KEYWORDS.contains(text) ? KIND_EN_KEYWORD : KIND_IDENTIFIER;
        }
        return KEYWORDS.containsKey(text) ? KIND_KEYWORD : KIND_IDENTIFIER;
    }

    /** 行注释：从 `//` 跳到行尾（不含换行符）。 */
    static int skipLineComment(String source, int i) {
        int n = source.length();
        int j = i + 2;
        while (j < n && source.charAt(j) != '\n' && source.charAt(j) != '\r') {
            j++;
        }
        return j;
    }

    /** 块注释：从 `/*` 跳到 `*` + `/` 之后；未闭合时到文件末尾。 */
    static int skipBlockComment(String source, int i) {
        int j = source.indexOf("*/", i + 2);
        return (j < 0) ? source.length() : j + 2;
    }

    /** 文本块：从 `"""` 跳到收尾 `"""` 之后；被 `\` 转义的引号不算结束。 */
    static int skipTextBlock(String source, int i) {
        int n = source.length();
        int j = i + 3;
        while (j < n) {
            int k = source.indexOf("\"\"\"", j);
            if (k < 0) {
                return n;
            }
            if (k > 0 && source.charAt(k - 1) == '\\') {
                j = k + 1;
                continue;
            }
            return k + 3;
        }
        return n;
    }

    /** 字符串 / 字符字面量：跳到收尾引号（或行尾），`\` 转义整体跳过。 */
    static int skipLiteral(String source, int i, int quoteCp) {
        int n = source.length();
        int j = i + 1;
        while (j < n) {
            char ch = source.charAt(j);
            if (ch == '\\') {
                j += 2;
                continue;
            }
            if (ch == quoteCp || ch == '\n') {
                j++;
                break;
            }
            j++;
        }
        return Math.min(j, n);
    }

    /** 数字字面量：允许 `1.5`、`1e-3` 这类写法，整体跳过。 */
    static int skipNumber(String source, int i) {
        int n = source.length();
        int start = i;
        i += Character.charCount(source.codePointAt(i));
        while (i < n) {
            int next = source.codePointAt(i);
            if (Character.isJavaIdentifierPart(next) || next == '.') {
                i += Character.charCount(next);
                continue;
            }
            if ((next == '+' || next == '-') && i > start) {
                int prev = source.codePointBefore(i);
                if (prev == 'e' || prev == 'E') {
                    i++;
                    continue;
                }
            }
            break;
        }
        return i;
    }

    /**
     * 句号「。」与顿号「、」折叠：顿号固定为 `,`；句号在行末 / `}` 前按语句结束处理成 `;`，
     * 其余位置按成员访问处理成 `.`。
     */
    static String foldSentencePunctuation(int cp, String source, int i) {
        if (cp == 0x3001) {
            return ",";
        }
        int n = source.length();
        int k = i + 1;
        while (k < n) {
            char ch = source.charAt(k);
            if (ch == ' ' || ch == '\t' || ch == 0x3000) {
                k++;
                continue;
            }
            break;
        }
        boolean lineEnd = k >= n || source.charAt(k) == '\n' || source.charAt(k) == '\r'
                || source.charAt(k) == '}'
                || (source.charAt(k) == '/' && k + 1 < n && source.charAt(k + 1) == '/');
        return lineEnd ? ";" : ".";
    }

    // ------------------------------------------------------------------ 空格硬约束检查

    /** 语法糖 `否则若`：紧跟 `(`，且位于语句开头或 `}` / `;` / `{` 之后。 */
    static boolean isElseIfSugar(SourceFile sf, int idx) {
        List<Token> tokens = sf.tokens;
        if (idx < 0 || idx >= tokens.size()) {
            return false;
        }
        Token t = tokens.get(idx);
        if (t.kind != KIND_IDENTIFIER || !"否则若".equals(t.text)) {
            return false;
        }
        if (idx + 1 >= tokens.size() || !textAt(sf.original, tokens, idx + 1).equals("(")) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        String prev = textAt(sf.original, tokens, idx - 1);
        return prev.equals("}") || prev.equals(";") || prev.equals("{");
    }

    static int checkSticky(List<SourceFile> files, Set<String> identifierNames) {
        int count = 0;
        for (SourceFile sf : files) {
            for (int ti = 0; ti < sf.tokens.size(); ti++) {
                Token t = sf.tokens.get(ti);
                if (t.kind != KIND_IDENTIFIER) {
                    continue;
                }
                // `否则若 (` 是合法语法糖，不算关键字粘连
                if (isElseIfSugar(sf, ti)) {
                    continue;
                }
                String keyword = null;
                for (String k : SORTED_KEYWORDS) {
                    if (t.text.length() > k.length() && t.text.startsWith(k)) {
                        keyword = k;
                        break;
                    }
                }
                if (keyword == null) {
                    continue;
                }
                String rest = t.text.substring(keyword.length());
                // 判定：剩余部分是一个已知符号（标识符），或者是一个多字关键字。
                // 这样能抓住「新建列表」「类用户」「返回结果」「返回真」这类真实错误，
                // 又不会因为「当前值」「无效」「字符串」这类正常命名而误报。
                boolean hit = identifierNames.contains(rest)
                        || (keyword.length() >= 2 && KEYWORDS.containsKey(rest));
                if (!hit) {
                    continue;
                }
                count++;
                reportSticky(sf, t, keyword, rest);
            }
        }
        return count;
    }

    static void reportSticky(SourceFile sf, Token token, String keyword, String rest) {
        SourcePos pos = linePosition(sf, token.start);
        int gapCol = pos.col() + keyword.length();

        System.err.println();
        System.err.println("错误 CJK001  第" + pos.line() + "行 第" + (gapCol + 1) + "列");
        System.err.println("  中文关键字「" + keyword + "」后缺少空格，与名称「" + rest
                + "」粘连，被整体识别为标识符「" + token.text + "」。");
        System.err.println("  建议：在「" + keyword + "」后补一个空格，写成「" + keyword + " " + rest + "」。");
        System.err.println("    " + String.format("%4d", pos.line()) + " | " + pos.lineText());
        System.err.println("         | " + spaces(gapCol) + "^ 这里缺一个空格");
        String fixedLine = pos.lineText().substring(0, gapCol) + " " + pos.lineText().substring(gapCol);
        System.err.println("  修正：" + fixedLine.trim());
    }

    // ------------------------------------------------------------------ 全角标点检查

    static int checkPunctuation(List<SourceFile> files) {
        int count = 0;
        for (SourceFile sf : files) {
            for (Token t : sf.tokens) {
                if (t.kind != KIND_BAD_PUNCT) {
                    continue;
                }
                count++;
                SourcePos pos = linePosition(sf, t.start);
                System.err.println();
                System.err.println("错误 CJK002  第" + pos.line() + "行 第" + (pos.col() + 1) + "列");
                System.err.println("  不支持的全角标点「" + t.text + "」，请改用半角标点"
                        + "（, . ? : ; \" ' ( ) 等）。");
                System.err.println("  建议：" + suggestionFor(t.text));
                System.err.println("    " + String.format("%4d", pos.line()) + " | " + pos.lineText());
                System.err.println("         | " + spaces(pos.col()) + "^");
            }
        }
        return count;
    }

    /** CJK106：句号「。」/ 顿号「、」已自动纠正为半角标点，只提示、不阻断。 */
    static int checkFoldedPunctuation(List<SourceFile> files) {
        int count = 0;
        for (SourceFile sf : files) {
            for (Token t : sf.tokens) {
                if (t.kind != KIND_PUNCT || t.text == null) {
                    continue;
                }
                char ch = sf.original.charAt(t.start);
                if (ch != 0x3001 && ch != 0x3002) {
                    continue;
                }
                count++;
                SourcePos pos = linePosition(sf, t.start);
                String original = ch == 0x3001 ? "、" : "。";
                System.out.println();
                System.out.println("提示 CJK106  第" + pos.line() + "行 第" + (pos.col() + 1) + "列");
                System.out.println("  全角标点「" + original + "」已自动纠正为「" + t.text + "」。"
                        + "如需保留旧行为（按 CJK002 阻断），加 --严格标点。");
            }
        }
        return count;
    }

    /** 给无法映射的全角标点一个可直接替换的半角写法；没有对应项时返回通用建议。 */
    static String suggestionFor(String punct) {
        switch (punct) {
            case "、":
                return "改用半角「,」（多用于分隔参数、列表项）。";
            case "。":
                return "改用半角「.」或「;」（句末按语句结束习惯选择）。";
            case "“":
            case "”":
            case "「":
            case "」":
            case "『":
            case "』":
                return "改用半角「\"」（字符串定界符）。";
            case "‘":
            case "’":
                return "改用半角「'」（字符定界符）。";
            case "《":
            case "〈":
                return "改用半角「<」（泛型或比较）。";
            case "》":
            case "〉":
                return "改用半角「>」（泛型或比较）。";
            case "【":
            case "〔":
            case "〖":
                return "改用半角「[」（数组下标）。";
            case "】":
            case "〕":
            case "〗":
                return "改用半角「]」（数组下标）。";
            case "–":
            case "—":
            case "〜":
                return "改用半角「-」（减号或连字符）。";
            case "…":
                return "改用三个半角句点「...」（可变参数或省略）。";
            case "·":
                return "改用半角「.」（成员访问）。";
            case "※":
                return "改用半角「*」（注释或通配）。";
            default:
                return "改用半角标点（, . ? : ; \" ' ( ) 等）。";
        }
    }

    // ------------------------------------------------------------------ 新手易错提示（不阻断编译）

    /** 扫描五类新手常见错误并给出可操作建议；只提示，不计入错误、不影响退出码。 */
    static int checkBeginnerTraps(List<SourceFile> files) {
        int count = 0;
        for (SourceFile sf : files) {
            Set<String> stringVars = stringVariables(sf);
            count += checkStringEquality(sf, stringVars);
            count += checkSortWithoutComparator(sf);
            count += checkReaderNewline(sf);
            count += checkLoopBound(sf);
            count += checkIntegerDivision(sf, intVariables(sf));
        }
        if (count > 0) {
            System.out.println();
            System.out.println("提示：共 " + count + " 处新手易错（不影响编译，建议按上面的建议改写）。");
        }
        return count;
    }

    /** 声明为字符串的变量名（`字符串 文本` / `String 文本`，含数组与参数）。 */
    static Set<String> stringVariables(SourceFile sf) {
        Set<String> names = new HashSet<>();
        List<Token> tokens = sf.tokens;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            String english = t.kind == KIND_KEYWORD ? KEYWORDS.get(t.text) : null;
            boolean isString = (english != null && english.equals("String"))
                    || (t.kind == KIND_IDENTIFIER && "String".equals(t.text));
            if (!isString) {
                continue;
            }
            int j = i + 1;
            while (j + 1 < tokens.size() && textAt(sf.original, tokens, j).equals("[")
                    && textAt(sf.original, tokens, j + 1).equals("]")) {
                j += 2;
            }
            if (j < tokens.size() && tokens.get(j).kind == KIND_IDENTIFIER) {
                names.add(tokens.get(j).text);
            }
        }
        return names;
    }

    static boolean isStringOperand(SourceFile sf, Token t, Set<String> stringVars) {
        if (t.kind == KIND_IDENTIFIER) {
            return stringVars.contains(t.text);
        }
        return t.kind == KIND_OTHER && t.text == null && sf.original.charAt(t.start) == '"';
    }

    static boolean isNullOperand(SourceFile sf, Token t) {
        if (t.kind == KIND_KEYWORD && "null".equals(KEYWORDS.get(t.text))) {
            return true;
        }
        String text = t.text != null ? t.text : sf.original.substring(t.start, t.end);
        return "null".equals(text);
    }

    /** CJK101：字符串用 `==` 比较。 */
    static int checkStringEquality(SourceFile sf, Set<String> stringVars) {
        int count = 0;
        List<Token> tokens = sf.tokens;
        for (int i = 1; i + 2 < tokens.size(); i++) {
            Token t = tokens.get(i);
            // `==` 由两个单字符 token 组成：i 指向第一个 `=`，右操作数在 i+2
            if (t.kind != KIND_OTHER || t.text != null || !sf.original.startsWith("==", t.start)) {
                continue;
            }
            Token left = tokens.get(i - 1);
            Token right = tokens.get(i + 2);
            // `== 空` 判空是正确写法，不提示
            if (isNullOperand(sf, left) || isNullOperand(sf, right)) {
                continue;
            }
            if (!isStringOperand(sf, left, stringVars) || !isStringOperand(sf, right, stringVars)) {
                continue;
            }
            String leftText = left.text != null ? left.text : "字面量";
            String rightText = right.text != null ? right.text : "字面量";
            if (reportTrap(sf, t, "CJK101",
                    "字符串用「==」比较只比较引用，内容相同也可能判为 false。",
                    "改用「" + leftText + ".等于(" + rightText + ")」；只有判空才写「== 空」。")) {
                count++;
            }
        }
        return count;
    }

    /** CJK102：`名单.整理()` 无参数，List.sort 需要比较器。 */
    static int checkSortWithoutComparator(SourceFile sf) {
        int count = 0;
        List<Token> tokens = sf.tokens;
        for (int i = 1; i + 2 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_IDENTIFIER || !"整理".equals(t.text)) {
                continue;
            }
            if (!textAt(sf.original, tokens, i - 1).equals(".")
                    || !textAt(sf.original, tokens, i + 1).equals("(")
                    || !textAt(sf.original, tokens, i + 2).equals(")")) {
                continue;
            }
            String receiver = i >= 2 ? textAt(sf.original, tokens, i - 2) : "名单";
            if (reportTrap(sf, t, "CJK102",
                    "「" + receiver + ".整理()」没有参数，List.sort 必须有比较器，这样写编译会失败。",
                    "改写为「集合助手.排序(" + receiver + ")」（按自然顺序），或「" + receiver + ".整理(比较器)」。")) {
                count++;
            }
        }
        return count;
    }

    /** CJK103：`读整数()` 后紧跟 `读行()`，行尾换行会被当成空行读走。 */
    static int checkReaderNewline(SourceFile sf) {
        int count = 0;
        List<Token> tokens = sf.tokens;
        Set<String> intReaders = Set.of("读整数", "读长整数", "读双浮点");
        Map<String, String> lastIntRead = new LinkedHashMap<>(); // 接收者 → 上一个读数值的方法名
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_IDENTIFIER || t.text == null
                    || !textAt(sf.original, tokens, i + 1).equals("(")) {
                continue;
            }
            boolean isInt = intReaders.contains(t.text);
            boolean isLine = "读行".equals(t.text);
            if (!isInt && !isLine) {
                continue;
            }
            String receiver = textAt(sf.original, tokens, i - 1).equals(".") && i >= 2
                    ? textAt(sf.original, tokens, i - 2) : "";
            if (isInt) {
                lastIntRead.put(receiver, t.text);
                continue;
            }
            String previous = lastIntRead.remove(receiver);
            if (previous != null) {
                String owner = receiver.isEmpty() ? "输入" : receiver;
                if (reportTrap(sf, t, "CJK103",
                        "「" + previous + "()」不会消耗行尾换行，紧随的「读行()」会先读到空行，看起来像跳过了一行输入。",
                        "在「" + previous + "()」之后补一句「" + owner + ".读行();」，或统一用「读行() + 整数包装.解析(...)」。")) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 行尾写了 `// 忽略提示`（可带代码）时，跳过该行提示。 */
    static boolean trapSuppressed(SourceFile sf, Token token) {
        return linePosition(sf, token.start).lineText().contains("忽略提示");
    }

    /** CJK104：`for` / `当` 的条件里 `<= 集合.元素数()` / `<= 数组.长度` 会多走一轮。 */
    static int checkLoopBound(SourceFile sf) {
        int count = 0;
        List<Token> tokens = sf.tokens;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_KEYWORD) {
                continue;
            }
            String english = KEYWORDS.get(t.text);
            boolean isFor = "for".equals(english);
            boolean isWhile = "while".equals(english);
            if (!isFor && !isWhile) {
                continue;
            }
            if (!textAt(sf, tokens, i + 1).equals("(")) {
                continue;
            }
            List<Integer> semicolons = new ArrayList<>();
            int close = findLoopClose(sf, tokens, i + 2, isFor, semicolons);
            if (close < 0) {
                continue;
            }
            int start = i + 2;
            int end = close;
            if (isFor) {
                if (semicolons.size() < 2) {
                    continue;
                }
                start = semicolons.get(0) + 1;
                end = semicolons.get(1);
            }
            int hit = nextComparisonEnd(sf, tokens, start, end);
            if (hit < 0) {
                continue;
            }
            String expr = describeBound(sf, tokens, hit, end);
            if (reportTrap(sf, tokens.get(hit), "CJK104",
                    "循环条件「… <= " + expr + "」会多走一轮，取下标时最后一次越界。",
                    "把「<=」改成「<」（0 起始取下标）；确实要包含末尾时写「<= " + expr + " - 1」。")) {
                count++;
            }
        }
        return count;
    }

    /** 找到循环条件右括号的位置，同时收集 for 头部的分号；找不到返回 -1。 */
    private static int findLoopClose(SourceFile sf, List<Token> tokens, int from, boolean isFor,
                                     List<Integer> semicolons) {
        int depth = 1;
        for (int j = from; j < tokens.size(); j++) {
            String s = textAt(sf, tokens, j);
            if (s.equals("(") || s.equals("[") || s.equals("{")) {
                depth++;
            } else if (s.equals(")") || s.equals("]") || s.equals("}")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            } else if (depth == 1 && s.equals(";") && isFor) {
                semicolons.add(j);
            }
        }
        return -1;
    }

    /** 条件区间内第一处 `<= …长度/元素数` 的位置；没有则返回 -1。 */
    private static int nextComparisonEnd(SourceFile sf, List<Token> tokens, int start, int end) {
        for (int j = start; j + 2 < end; j++) {
            Token lt = tokens.get(j);
            if (textAt(sf, tokens, j).equals("<")
                    && sf.original.startsWith("<=", lt.start)
                    && describeBound(sf, tokens, j, end) != null) {
                return j;
            }
        }
        return -1;
    }

    /** 把 `<` 之后的表达式描述成 `.长度` / `.元素数()` 形式；不是边界表达式则返回 null。 */
    private static String describeBound(SourceFile sf, List<Token> tokens, int ltIndex, int end) {
        String expr = sf.original.substring(tokens.get(ltIndex + 2).start,
                tokens.get(end - 1).end).trim();
        return expr.matches("^\\S+(\\.长度|\\.元素数\\(\\))$") ? expr : null;
    }

    /** 声明为整数 / 长整数的变量名（跳过数组声明）。 */
    static Set<String> intVariables(SourceFile sf) {
        Set<String> names = new HashSet<>();
        List<Token> tokens = sf.tokens;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            String english = t.kind == KIND_KEYWORD ? KEYWORDS.get(t.text) : null;
            boolean isIntType = (english != null && (english.equals("int") || english.equals("long")))
                    || (t.kind == KIND_IDENTIFIER && ("int".equals(t.text) || "long".equals(t.text)));
            if (!isIntType) {
                continue;
            }
            int j = i + 1;
            if (textAt(sf.original, tokens, j).equals("[")) {
                continue;
            }
            if (tokens.get(j).kind == KIND_IDENTIFIER) {
                names.add(tokens.get(j).text);
            }
        }
        return names;
    }

    /** CJK105：`/` 两侧都是整数（字面量或声明为整数 / 长整数的变量）。 */
    static int checkIntegerDivision(SourceFile sf, Set<String> intVars) {
        int count = 0;
        List<Token> tokens = sf.tokens;
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != KIND_OTHER || t.text != null || sf.original.charAt(t.start) != '/') {
                continue;
            }
            if (sf.original.startsWith("/=", t.start)) {
                continue;
            }
            if (!isIntegerOperand(sf, tokens.get(i - 1), intVars)
                    || !isIntegerOperand(sf, tokens.get(i + 1), intVars)) {
                continue;
            }
            // `(双浮点) 甲 / 乙` 已被显式转成浮点，不会截断
            if (isFloatCastBefore(sf, tokens, i - 1)) {
                continue;
            }
            if (reportTrap(sf, t, "CJK105",
                    "整数相除会丢弃小数部分：5 / 2 得 2，而不是 2.5。",
                    "需要小数结果时把其中一个操作数写成小数（5.0 / 2），或先转成双浮点：(双浮点) 被除数 / 除数。")) {
                count++;
            }
        }
        return count;
    }

    /** 操作数前紧跟 `(双浮点)` / `(float)` 之类的强制转换。 */
    static boolean isFloatCastBefore(SourceFile sf, List<Token> tokens, int idx) {
        if (idx < 2 || !textAt(sf.original, tokens, idx - 1).equals(")")) {
            return false;
        }
        int depth = 1;
        for (int j = idx - 2; j >= 0; j--) {
            String s = textAt(sf.original, tokens, j);
            if (s.equals(")")) {
                depth++;
            } else if (s.equals("(")) {
                depth--;
                if (depth == 0) {
                    for (int k = j + 1; k <= idx - 2; k++) {
                        String text = textAt(sf.original, tokens, k);
                        String english = tokens.get(k).kind == KIND_KEYWORD ? KEYWORDS.get(text) : null;
                        if ("双浮点".equals(text) || "浮点".equals(text)
                                || "double".equals(english) || "float".equals(english)
                                || "double".equals(text) || "float".equals(text)) {
                            return true;
                        }
                    }
                    return false;
                }
            } else if (s.equals(";") || s.equals("{") || s.equals("}")) {
                return false;
            }
        }
        return false;
    }

    static boolean isIntegerOperand(SourceFile sf, Token t, Set<String> intVars) {
        if (t.kind == KIND_IDENTIFIER) {
            return intVars.contains(t.text);
        }
        if (t.kind != KIND_OTHER || t.text != null) {
            return false;
        }
        String raw = sf.original.substring(t.start, t.end);
        if (raw.isEmpty() || !Character.isDigit(raw.charAt(0))) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '.' || c == 'e' || c == 'E' || c == 'f' || c == 'F' || c == 'd' || c == 'D') {
                return false;
            }
        }
        return true;
    }

    static boolean reportTrap(SourceFile sf, Token token, String code, String problem, String suggestion) {
        if (trapSuppressed(sf, token)) {
            return false;
        }
        SourcePos pos = linePosition(sf, token.start);
        System.out.println();
        System.out.println("提示 " + code + "  第" + pos.line() + "行 第" + (pos.col() + 1) + "列");
        System.out.println("  " + problem);
        System.out.println("  建议：" + suggestion);
        System.out.println("    " + String.format("%4d", pos.line()) + " | " + pos.lineText());
        return true;
    }

    // ------------------------------------------------------------------ 翻译

    private static final class TranslatePlan {
        List<Token> tokens;
        Set<String> declared;
        boolean[] skip;
        String[] typeAt;
        String[] kindAt;
        boolean[] inTypeBody;
        boolean[] insertVoid;
        boolean[] insertOut;
        boolean[] insertPrint;
        boolean[] inferVar;
        boolean[] inferConst;
        boolean[] insertForVar;
        boolean[] repeatStart;
        int[] repeatEnd;
        String[] repeatHeader;
        boolean[] renameLength;
        boolean[] renameElseIf;
        boolean[] renameSystem;
        boolean[] renameArraycopy;
        String[] staticCall;
        boolean[] callMember;
        boolean[] declMember;
        boolean[] wrapGeneric;
        int[] arrayListFromEnd;
        boolean[] arrayListClose;
        boolean[] printComma;
        boolean[] printParenOpen;
        boolean[] printParenClose;
    }

    static String translate(SourceFile sf) {
        TranslatePlan plan = new TranslatePlan();
        plan.tokens = sf.tokens;
        plan.declared = declaredNames(sf.tokens, sf.original);
        // skip 标记：语法糖插入/替换时用来跳过已被消费的 token。
        plan.skip = new boolean[sf.tokens.size()];
        planVoidAndPrint(sf, plan);
        planSugar(sf, plan);
        planCalls(sf, plan);
        planGenerics(sf, plan);
        planPrintArgs(sf, plan);
        return renderTranslated(sf, plan);
    }

    /** 类型上下文：每个 token 所在类型名、类型种类，以及是否直接位于类型体成员层。 */
    private static final class TypeScope {
        String[] typeAt;
        String[] kindAt;
        boolean[] inTypeBody;
    }

    private static void planVoidAndPrint(SourceFile sf, TranslatePlan plan) {
        TypeScope scope = planTypeScopes(sf, plan.tokens);
        plan.typeAt = scope.typeAt;
        plan.kindAt = scope.kindAt;
        plan.inTypeBody = scope.inTypeBody;
        plan.insertVoid = planVoid(sf, plan.tokens, scope);
        plan.insertOut = new boolean[plan.tokens.size()];
        plan.insertPrint = new boolean[plan.tokens.size()];
        planPrint(sf, plan.tokens, plan.insertOut, plan.insertPrint);
    }

    /** 扫描花括号层级，记录每个 token 所在类型名、类型种类与是否在类型体成员层。 */
    private static TypeScope planTypeScopes(SourceFile sf, List<Token> tokens) {
        String[] typeAt = new String[tokens.size()];
        String[] kindAt = new String[tokens.size()];
        boolean[] inTypeBody = new boolean[tokens.size()];
        ScopeWalk walk = new ScopeWalk();
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            String text = textAt(sf, tokens, idx);
            String declKind = typeDeclarationKind(t);
            if (declKind != null) {
                walk.pendingKind = declKind;
                if (idx + 1 < tokens.size() && tokens.get(idx + 1).kind == KIND_IDENTIFIER) {
                    walk.pendingType = tokens.get(idx + 1).text;
                }
            }
            typeAt[idx] = walk.currentType;
            kindAt[idx] = walk.currentKind;
            inTypeBody[idx] = !walk.bodies.isEmpty() && walk.bodies.get(walk.bodies.size() - 1);
            stepTypeScope(text, walk);
        }
        TypeScope scope = new TypeScope();
        scope.typeAt = typeAt;
        scope.kindAt = kindAt;
        scope.inTypeBody = inTypeBody;
        return scope;
    }

    /** 类型作用域遍历的可变状态：待定的类型声明、当前类型与「所有者/种类/类型体」三层栈。 */
    private static final class ScopeWalk {
        final List<String> owners = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        final List<Boolean> bodies = new ArrayList<>();
        String pendingType;
        String pendingKind;
        String currentType;
        String currentKind;
    }

    /** 遇到 `{` 进入新作用域、`}` 退出；其他 token 不改动作用域。 */
    private static void stepTypeScope(String text, ScopeWalk walk) {
        if (text.equals("{")) {
            boolean typeBody = walk.pendingType != null;
            walk.owners.add(typeBody ? walk.pendingType : walk.currentType);
            walk.kinds.add(typeBody ? walk.pendingKind : walk.currentKind);
            walk.bodies.add(typeBody);
            walk.currentType = walk.owners.get(walk.owners.size() - 1);
            walk.currentKind = walk.kinds.get(walk.kinds.size() - 1);
            walk.pendingType = null;
            walk.pendingKind = null;
        } else if (text.equals("}")) {
            String[] state = popTypeScope(walk.owners, walk.kinds, walk.bodies);
            walk.currentType = state[0];
            walk.currentKind = state[1];
        }
    }

    /** `类` / `接口` / `枚举` 返回对应英文种类；其他 token 返回 null。 */
    private static String typeDeclarationKind(Token t) {
        if (t.kind != KIND_KEYWORD) {
            return null;
        }
        String english = KEYWORDS.get(t.text);
        if (english == null) {
            return null;
        }
        return english.equals("class") || english.equals("interface") || english.equals("enum")
                ? english : null;
    }

    /** 退出一层花括号，返回 {当前类型名, 当前类型种类}。 */
    private static String[] popTypeScope(List<String> ownerStack, List<String> kindStack,
                                         List<Boolean> bodyStack) {
        if (!ownerStack.isEmpty()) {
            ownerStack.remove(ownerStack.size() - 1);
            kindStack.remove(kindStack.size() - 1);
            bodyStack.remove(bodyStack.size() - 1);
        }
        return new String[]{
                ownerStack.isEmpty() ? null : ownerStack.get(ownerStack.size() - 1),
                kindStack.isEmpty() ? null : kindStack.get(kindStack.size() - 1)};
    }

    // 省略返回类型：`公有 加一() {` 等价于 `公有 无 加一() {`，翻译时补上 `void`。
    // 只在修饰符之后、名称后紧跟 `(`、且名称不等于所在类型名（构造器）时补，
    // 避免误伤方法调用、字段声明与构造器。
    // 另：无修饰符的接口方法 `加一();` 也补 `void` —— 方法调用只可能出现在方法体内，
    // 直接位于类型体成员层的 `名称(` 必然是声明。
    private static boolean[] planVoid(SourceFile sf, List<Token> tokens, TypeScope scope) {
        boolean[] insertVoid = new boolean[tokens.size()];
        for (int idx = 1; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            Token prev = tokens.get(idx - 1);
            if (t.kind != KIND_IDENTIFIER) {
                continue;
            }
            String nextText = textAt(sf, tokens, idx + 1);
            if (!nextText.equals("(")) {
                continue;
            }
            boolean declaration = false;
            if (prev.kind == KIND_KEYWORD) {
                String prevEnglish = KEYWORDS.get(prev.text);
                declaration = prevEnglish != null && isModifier(prevEnglish);
            }
            if (!declaration && scope.inTypeBody[idx]) {
                String prevText = textAt(sf, tokens, idx - 1);
                declaration = prevText.equals("{") || prevText.equals("}") || prevText.equals(";");
                // 枚举体以常量开头，`红();` 是枚举常量而不是方法
                if (declaration && "enum".equals(scope.kindAt[idx]) && prevText.equals("{")) {
                    declaration = false;
                }
            }
            if (!declaration) {
                continue;
            }
            if (t.text.equals(scope.typeAt[idx])) {
                continue; // 构造器：名称与所在类型同名
            }
            insertVoid[idx] = true;
        }
        return insertVoid;
    }

    // 简化打印：语句开头的 `println(...)` / `打印行(...)` 自动补成 `System.out.println(...)`，
    // `打印(...)` 自动补成 `System.out.print(...)`。
    // 只认 `{`、`}`、`;`、`->` 之后的位置，带 `.` 前缀的调用与自定义同名方法不会被改写。
    private static void planPrint(SourceFile sf, List<Token> tokens, boolean[] insertOut, boolean[] insertPrint) {
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            boolean isLine = "println".equals(t.text) || "打印行".equals(t.text);
            boolean isPrint = "打印".equals(t.text);
            if (t.kind != KIND_IDENTIFIER || (!isLine && !isPrint)) {
                continue;
            }
            String nextText = textAt(sf, tokens, idx + 1);
            if (!nextText.equals("(")) {
                continue;
            }
            String prevText = "";
            boolean afterArrow = false;
            if (idx > 0) {
                prevText = textAt(sf, tokens, idx - 1);
                if (idx > 1 && ">".equals(prevText)) {
                    String prev2Text = textAt(sf, tokens, idx - 2);
                    afterArrow = "-".equals(prev2Text);
                }
            }
            if (prevText.isEmpty() || prevText.equals("{") || prevText.equals("}") || prevText.equals(";")
                    || afterArrow) {
                if (isPrint) {
                    insertPrint[idx] = true;
                } else {
                    insertOut[idx] = true;
                }
            }
        }
    }

    private static void planSugar(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        Set<String> declared = plan.declared;
        // A5：类型推断 `令 名称 = …` → `var 名称 = …`、`常量 名称 = …` → `final var 名称 = …`。
        // 仅在语句开头、后接「名称 =」时识别；开关关闭时完全不动，`令` / `常量` 按普通标识符处理。
        plan.inferVar = planInference(sf, tokens, "令");
        plan.inferConst = planInference(sf, tokens, "常量");

        // 增强 for 省略类型：`对于 (名 : 名单)` → `for (var 名 : 名单)`。
        // 只认 `for (` 之后是「单个标识符 + 冒号」的形式：带类型的写法首 token 是类型别名
        // （按关键字处理），传统三段式 for 的标识符后是 `=`，都不会命中。
        plan.insertForVar = planForVar(sf, tokens);

        // 计数循环糖：`重复 3 次 { … }` → `for (int 序号 = 1; 序号 <= 3; 序号++) { … }`。
        // 默认循环变量名 `序号`；写成 `重复 3 次 (名) {` 可自选变量名，避免与同名变量冲突。
        // 只认语句开头的 `重复`，且「次数」后必须紧跟 `{`；次数表达式原样交给 translateFragment 转译。
        RepeatPlan repeat = planRepeat(sf, tokens, declared);
        plan.repeatStart = repeat.start;
        plan.repeatEnd = repeat.end;
        plan.repeatHeader = repeat.header;

        // 中文属性 `长度`：`数组.长度` → `数组.length`（方法调用 `字符串.长度()` 同样适用）。
        // 只认 `.` 之后的位置，名为 `长度` 的变量与字段声明不受影响。
        plan.renameLength = planLength(sf, tokens);

        // 语法糖 `否则若 (`：`否则` + `若` 粘连成一个标识符，这里整体还原成 `else if`。
        plan.renameElseIf = planElseIf(sf, tokens);

        // 中文类名 `系统`：`系统.数组复制(...)` → `System.arraycopy(...)`。
        // 只认后接 `.` 的位置，名为 `系统` 的变量不受影响。
        plan.renameSystem = planSystem(sf, tokens);

        // 系统方法 `数组复制`：`系统.数组复制(...)` → `System.arraycopy(...)`。
        plan.renameArraycopy = planArraycopy(sf, tokens);
    }

    /** A5 类型推断：`令 名称 = …` / `常量 名称 = …` 位于语句开头时标记；开关关闭时全为 false。 */
    private static boolean[] planInference(SourceFile sf, List<Token> tokens, String word) {
        boolean[] flag = new boolean[tokens.size()];
        if (!allowInference) {
            return flag;
        }
        for (int idx = 0; idx + 2 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !word.equals(t.text)) {
                continue;
            }
            if (idx > 0) {
                String prev = textAt(sf.original, tokens, idx - 1);
                if (!(prev.equals("{") || prev.equals("}") || prev.equals(";")
                        || prev.equals("(") || prev.equals(":"))) {
                    continue;
                }
            }
            if (tokens.get(idx + 1).kind != KIND_IDENTIFIER) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 2).equals("=")) {
                continue;
            }
            flag[idx] = true;
        }
        return flag;
    }

    /** 增强 for 省略类型：`对于 (名 : 名单)` → `for (var 名 : 名单)`。 */
    private static boolean[] planForVar(SourceFile sf, List<Token> tokens) {
        boolean[] insertForVar = new boolean[tokens.size()];
        for (int idx = 0; idx + 3 < tokens.size(); idx++) {
            Token f = tokens.get(idx);
            if (f.kind != KIND_KEYWORD || !"for".equals(KEYWORDS.get(f.text))) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            if (tokens.get(idx + 2).kind != KIND_IDENTIFIER) {
                continue;
            }
            if (textAt(sf.original, tokens, idx + 3).equals(":")) {
                insertForVar[idx + 2] = true;
            }
        }
        return insertForVar;
    }

    /** 计数循环 `重复 N 次 [(名)] {` 的起止 token 与生成的 for 头部。 */
    static final class RepeatPlan {
        final boolean[] start;
        final int[] end;
        final String[] header;

        RepeatPlan(int size) {
            start = new boolean[size];
            end = new int[size];
            header = new String[size];
        }
    }

    /**
     * 计数循环糖：`重复 3 次 { … }` → `for (int 序号 = 1; 序号 <= 3; 序号++) { … }`。
     * 默认循环变量名 `序号`；写成 `重复 3 次 (名) {` 可自选变量名。只认语句开头的 `重复`，
     * 且「次数」后必须紧跟 `{`；次数表达式原样交给 translateFragment 转译。
     */
    private static RepeatPlan planRepeat(SourceFile sf, List<Token> tokens, Set<String> declared) {
        RepeatPlan repeat = new RepeatPlan(tokens.size());
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"重复".equals(t.text)) {
                continue;
            }
            if (idx > 0 && !isStatementStart(textAt(sf.original, tokens, idx - 1))) {
                continue;
            }
            int ci = findRepeatMarker(sf, tokens, idx);
            if (ci < 0 || ci == idx + 1) {
                continue;
            }
            String expression = sf.original.substring(tokens.get(idx + 1).start, tokens.get(ci).start).trim();
            if (expression.isEmpty()) {
                continue;
            }
            int headerEnd = repeatHeaderEnd(sf, tokens, ci);
            if (headerEnd + 1 >= tokens.size()
                    || !textAt(sf.original, tokens, headerEnd + 1).equals("{")) {
                continue;
            }
            String name = headerEnd > ci ? tokens.get(ci + 2).text : "序号";
            repeat.start[idx] = true;
            repeat.end[idx] = headerEnd;
            repeat.header[idx] = "for (int " + name + " = 1; " + name + " <= "
                    + translateFragment(expression, declared) + "; " + name + "++)";
        }
        return repeat;
    }

    /** 语句边界：开头或 `{` / `}` / `;` / `:` 之后。 */
    private static boolean isStatementStart(String prev) {
        return prev.equals("{") || prev.equals("}") || prev.equals(";") || prev.equals(":");
    }

    /** 找同层的 `次`：括号内的 `次` 不算（次数表达式里可能带括号）；找不到返回 -1。 */
    private static int findRepeatMarker(SourceFile sf, List<Token> tokens, int idx) {
        int depth = 0;
        for (int j = idx + 1; j < tokens.size(); j++) {
            String s = textAt(sf.original, tokens, j);
            if (s.equals("{")) {
                break;
            }
            if (s.equals("(") || s.equals("[")) {
                depth++;
            } else if (s.equals(")") || s.equals("]")) {
                depth--;
                if (depth < 0) {
                    break;
                }
            } else if (depth == 0 && tokens.get(j).kind == KIND_IDENTIFIER && "次".equals(tokens.get(j).text)) {
                return j;
            }
        }
        return -1;
    }

    /** `次` 之后的头部：`(名)` 时返回右括号下标，否则返回 `次` 的下标。 */
    private static int repeatHeaderEnd(SourceFile sf, List<Token> tokens, int ci) {
        if (ci + 1 < tokens.size() && textAt(sf.original, tokens, ci + 1).equals("(")
                && ci + 3 < tokens.size() && tokens.get(ci + 2).kind == KIND_IDENTIFIER
                && textAt(sf.original, tokens, ci + 3).equals(")")) {
            return ci + 3;
        }
        return ci;
    }

    /** 中文属性 `长度`：`数组.长度` → `数组.length`。只认 `.` 之后的位置。 */
    private static boolean[] planLength(SourceFile sf, List<Token> tokens) {
        boolean[] renameLength = new boolean[tokens.size()];
        for (int idx = 1; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"长度".equals(t.text)) {
                continue;
            }
            if (textAt(sf.original, tokens, idx - 1).equals(".")) {
                renameLength[idx] = true;
            }
        }
        return renameLength;
    }

    /** 语法糖 `否则若`：整体还原成 `else if`。 */
    private static boolean[] planElseIf(SourceFile sf, List<Token> tokens) {
        boolean[] renameElseIf = new boolean[tokens.size()];
        for (int idx = 0; idx < tokens.size(); idx++) {
            if (isElseIfSugar(sf, idx)) {
                renameElseIf[idx] = true;
            }
        }
        return renameElseIf;
    }

    /** 中文类名 `系统`：`系统.数组复制(...)` → `System.arraycopy(...)`。只认后接 `.` 的位置。 */
    private static boolean[] planSystem(SourceFile sf, List<Token> tokens) {
        boolean[] renameSystem = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"系统".equals(t.text)) {
                continue;
            }
            if (textAt(sf.original, tokens, idx + 1).equals(".")) {
                renameSystem[idx] = true;
            }
        }
        return renameSystem;
    }

    /** 系统方法 `数组复制`：仅当左侧是 `系统.` 时标记。 */
    private static boolean[] planArraycopy(SourceFile sf, List<Token> tokens) {
        boolean[] renameArraycopy = new boolean[tokens.size()];
        for (int idx = 2; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"数组复制".equals(t.text)) {
                continue;
            }
            String prev1 = textAt(sf.original, tokens, idx - 1);
            String prev2 = textAt(sf.original, tokens, idx - 2);
            if (prev1.equals(".") && "系统".equals(prev2)) {
                renameArraycopy[idx] = true;
            }
        }
        return renameArraycopy;
    }

    private static void planCalls(SourceFile sf, TranslatePlan plan) {
        plan.staticCall = planStaticCalls(sf, plan);
        plan.callMember = planMemberCalls(sf, plan);
        plan.declMember = planMethodDecls(sf, plan);
    }

    /** 中文静态调用：`算术.平方根(…)` → `Math.sqrt(…)`；类名与方法名一起登记，跳过 `.` 与方法名。 */
    private static String[] planStaticCalls(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        boolean[] skip = plan.skip;
        String[] staticCall = new String[tokens.size()];
        for (int idx = 0; idx + 2 < tokens.size(); idx++) {
            String aText = textAt(sf, tokens, idx);
            String dot = textAt(sf.original, tokens, idx + 1);
            String bText = textAt(sf.original, tokens, idx + 2);
            String replacement = dot.equals(".") ? STATIC_CALLS.get(aText + "." + bText) : null;
            if (replacement != null) {
                staticCall[idx] = replacement;
                skip[idx + 1] = true;
                skip[idx + 2] = true;
            }
        }
        return staticCall;
    }

    /**
     * 中文成员方法：`名称.加入(…)` → `名称.add(…)`，只认 `.` 之后且后接 `(`。
     * `.` 之后是成员名上下文，即使该词同时是关键字近义词（如 `遍历` 是 for 的近义词）
     * 也按成员糖处理：Java 不允许用关键字作方法名，这样写唯一合理的解释就是成员糖。
     */
    private static boolean[] planMemberCalls(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        Set<String> declared = plan.declared;
        boolean[] callMember = new boolean[tokens.size()];
        for (int idx = 1; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if ((t.kind != KIND_IDENTIFIER && t.kind != KIND_KEYWORD) || !MEMBER_CALLS.containsKey(t.text)) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx - 1).equals(".")) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            // 中文标准库类名上的调用保持原样：`文本.去空白` / `并发.关闭` / `集合工具.填充`
            // 是这些类自己的方法，若按方法名全局改写会变成不存在的方法名。
            // 但同名变量优先：文件里声明过 `文本` 变量时，`文本.修剪()` 仍要转译。
            if (isStdlibReceiver(tokens, sf.original, idx - 2, declared)) {
                continue;
            }
            callMember[idx] = true;
        }
        return callMember;
    }

    /**
     * 中文重写方法声明：不带 `.` 前缀、后接 `(` 的 `转文本` / `等于` / `哈希码`。
     * 带 `.` 前缀的是调用（由 planMemberCalls 处理），这里只负责声明侧。
     */
    private static boolean[] planMethodDecls(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        boolean[] declMember = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !MEMBER_DECLS.containsKey(t.text)) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            if (idx > 0 && textAt(sf.original, tokens, idx - 1).equals(".")) {
                continue;
            }
            declMember[idx] = true;
        }
        return declMember;
    }

    private static void planGenerics(SourceFile sf, TranslatePlan plan) {
        plan.wrapGeneric = planWrapGenerics(sf, plan.tokens);
        ArrayListPlan from = planArrayListFrom(sf, plan.tokens);
        plan.arrayListFromEnd = from.fromEnd();
        plan.arrayListClose = from.close();
    }

    /** `数组清单.从(...)` 的配对信息：起始下标 → 右括号下标，以及右括号标记。 */
    private record ArrayListPlan(int[] fromEnd, boolean[] close) {}

    /**
     * 泛型实参中的基本类型别名：`清单<整数>` → `List<Integer>`。
     * 基本类型是保留词，只可能出现在类型位置，所以「前 `<`/`,` 且后 `>`/`,`」足够安全；
     * 其他位置（如 `整数 甲 = 1;`、`新建 整数[5]`）行为不变。
     */
    private static boolean[] planWrapGenerics(SourceFile sf, List<Token> tokens) {
        boolean[] wrapGeneric = new boolean[tokens.size()];
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_KEYWORD) {
                continue;
            }
            String english = KEYWORDS.get(t.text);
            if (english == null || !PRIMITIVE_WRAPPERS.containsKey(english)) {
                continue;
            }
            String prev = idx > 0 ? textAt(sf.original, tokens, idx - 1) : "";
            String next = idx + 1 < tokens.size() ? textAt(sf.original, tokens, idx + 1) : "";
            if ((prev.equals("<") || prev.equals(",")) && (next.equals(">") || next.equals(","))) {
                wrapGeneric[idx] = true;
            }
        }
        return wrapGeneric;
    }

    /**
     * 4.5 `数组清单.从(...)` → `new ArrayList<>(List.of(...))`：
     * 记下配对的右括号，输出时在右括号后再补一个 `)`，可变清单也就能一行建成。
     */
    private static ArrayListPlan planArrayListFrom(SourceFile sf, List<Token> tokens) {
        int[] arrayListFromEnd = new int[tokens.size()];
        for (int i = 0; i < arrayListFromEnd.length; i++) {
            arrayListFromEnd[i] = -1;
        }
        boolean[] arrayListClose = new boolean[tokens.size()];
        for (int idx = 0; idx + 3 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (!"数组清单".equals(t.text)) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 1).equals(".")
                    || !textAt(sf.original, tokens, idx + 2).equals("从")
                    || !textAt(sf.original, tokens, idx + 3).equals("(")) {
                continue;
            }
            int close = findMatchingParen(sf, tokens, idx + 3);
            if (close < 0) {
                continue;
            }
            arrayListFromEnd[idx] = close;
            arrayListClose[close] = true;
        }
        return new ArrayListPlan(arrayListFromEnd, arrayListClose);
    }

    /** 从左括号位置找配对的右括号；找不到返回 -1。 */
    private static int findMatchingParen(SourceFile sf, List<Token> tokens, int open) {
        int depth = 0;
        for (int j = open; j < tokens.size(); j++) {
            String txt = textAt(sf.original, tokens, j);
            if (txt.equals("(")) {
                depth++;
            } else if (txt.equals(")")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    private static void planPrintArgs(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        boolean[] insertOut = plan.insertOut;
        boolean[] insertPrint = plan.insertPrint;
        // 4.8 `打印行("姓名：", 名字)` 多实参：顶层逗号换成 `+`，多 token 实参加括号。
        // 只在语句开头、已判定要补 `System.out.` 的调用上处理；嵌套调用里的逗号不算顶层。
        boolean[] printComma = new boolean[tokens.size()];
        boolean[] printParenOpen = new boolean[tokens.size()];
        boolean[] printParenClose = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            if (!insertOut[idx] && !insertPrint[idx]) {
                continue;
            }
            if (!textAt(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            PrintArgs args = scanPrintArgs(sf, tokens, idx);
            if (args != null) {
                markPrintArgs(args, idx, printComma, printParenOpen, printParenClose);
            }
        }
        plan.printComma = printComma;
        plan.printParenOpen = printParenOpen;
        plan.printParenClose = printParenClose;
    }

    /** `打印行(...)` 的实参信息：右括号下标与顶层逗号下标。 */
    private record PrintArgs(int close, List<Integer> commas) {}

    /** 扫描一次打印调用的实参；没有顶层逗号或括号不闭合时返回 null。 */
    private static PrintArgs scanPrintArgs(SourceFile sf, List<Token> tokens, int idx) {
        int depth = 0;
        int close = -1;
        List<Integer> commas = new ArrayList<>();
        for (int j = idx + 1; j < tokens.size(); j++) {
            String txt = textAt(sf.original, tokens, j);
            if (txt.equals("(")) {
                depth++;
            } else if (txt.equals(")")) {
                depth--;
                if (depth == 0) {
                    close = j;
                    break;
                }
            } else if (txt.equals(",") && depth == 1) {
                commas.add(j);
            }
        }
        return close < 0 || commas.isEmpty() ? null : new PrintArgs(close, commas);
    }

    /** 按顶层逗号切分实参，多 token 实参加括号，逗号标记为 `+`。 */
    private static void markPrintArgs(PrintArgs args, int idx, boolean[] printComma,
                                      boolean[] printParenOpen, boolean[] printParenClose) {
        List<int[]> spans = new ArrayList<>();
        int start = idx + 2;
        for (int c : args.commas()) {
            spans.add(new int[]{start, c - 1});
            start = c + 1;
        }
        spans.add(new int[]{start, args.close() - 1});
        for (int[] a : spans) {
            if (a[0] > a[1]) {
                continue;
            }
            if (a[1] > a[0]) {
                printParenOpen[a[0]] = true;
                printParenClose[a[1]] = true;
            }
        }
        for (int c : args.commas()) {
            printComma[c] = true;
        }
    }

    private static String renderTranslated(SourceFile sf, TranslatePlan plan) {
        List<Token> tokens = plan.tokens;
        boolean[] skip = plan.skip;
        StringBuilder sb = new StringBuilder(sf.original.length() * 2);
        List<int[]> pending = new ArrayList<>(); // {origStart, origLen, genStart, genLen}
        int cursor = 0;
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (idx > 0) {
                appendPendingClosers(sb, idx - 1, plan.arrayListClose, plan.printParenClose);
            }
            if (skip[idx]) {
                // 关键字已在前面的名称处输出，连同它与名称之间的空白一起跳过。
                // 用 max 避免把 cursor 往回退（区域整体替换时 cursor 已越过后续 token）。
                cursor = Math.max(cursor, t.end);
                continue;
            }
            if (t.start > cursor) {
                sb.append(sf.original, cursor, t.start);
            }
            if (plan.printParenOpen[idx]) {
                sb.append('(');
            }
            int sugared = renderSugared(sf, plan, sb, pending, t, idx);
            if (sugared >= 0) {
                cursor = sugared;
                continue;
            }
            if (t.kind == KIND_KEYWORD) {
                renderKeyword(plan, sb, pending, idx);
            } else {
                renderLiteral(sf, plan, sb, pending, t);
            }
            cursor = t.end;
        }
        if (cursor < sf.original.length()) {
            sb.append(sf.original, cursor, sf.original.length());
        }
        String translated = sb.toString();
        recordReplacements(sf, pending, translated);
        return translated;
    }

    /** 输出语法糖 token；命中返回新 cursor，未命中返回 -1。 */
    private static int renderSugared(SourceFile sf, TranslatePlan plan, StringBuilder sb,
                                     List<int[]> pending, Token t, int idx) {
        if (plan.printComma[idx]) {
            sb.append('+');
            return t.end;
        }
        if (plan.repeatStart[idx]) {
            return renderRepeat(plan, sb, pending, idx);
        }
        if (plan.staticCall[idx] != null) {
            emit(sb, pending, t.start, t.end - t.start, plan.staticCall[idx]);
            return t.end;
        }
        if (plan.callMember[idx]) {
            return emitToken(sb, pending, t, MEMBER_CALLS.get(t.text));
        }
        if (plan.declMember[idx]) {
            return emitToken(sb, pending, t, MEMBER_DECLS.get(t.text));
        }
        if (isEntrySugar(sf, plan.tokens, t, idx)) {
            // 入口 { … } 语法糖：直接展开成 main 方法声明
            return emitToken(sb, pending, t, "public static void main(String[] 参数)");
        }
        if (plan.inferVar[idx] || plan.inferConst[idx]) {
            // 类型推断：`令` → `var`、`常量` → `final var`，名称与初始化原样保留
            return emitToken(sb, pending, t, plan.inferConst[idx] ? "final var" : "var");
        }
        if (plan.renameArraycopy[idx] || plan.renameSystem[idx]
                || plan.renameLength[idx] || plan.renameElseIf[idx]) {
            return renderRenamed(plan, sb, pending, t, idx);
        }
        int inserted = renderInserted(plan, sb, pending, t, idx);
        if (inserted >= 0) {
            return inserted;
        }
        if (plan.arrayListFromEnd[idx] >= 0) {
            return renderArrayListFrom(plan, sb, pending, idx);
        }
        return -1;
    }

    /** `入口 {` 展开成 main：入口后紧跟 `{`。 */
    private static boolean isEntrySugar(SourceFile sf, List<Token> tokens, Token t, int idx) {
        return t.kind == KIND_IDENTIFIER && "入口".equals(t.text)
                && idx + 1 < tokens.size() && textAt(sf, tokens, idx + 1).equals("{");
    }

    /** 插入式语法糖：`打印行` / `打印` / 省略返回类型 / 增强 for 省略类型；不命中返回 -1。 */
    private static int renderInserted(TranslatePlan plan, StringBuilder sb, List<int[]> pending, Token t, int idx) {
        // 补 `System.out.` 前缀；原长度按 0 登记，列号仍能回映射
        if (plan.insertOut[idx] && "打印行".equals(t.text)) {
            // 中文糖：把 `打印行` 整体换成 `System.out.println`
            return emitToken(sb, pending, t, "System.out.println");
        }
        if (plan.insertOut[idx]) {
            emit(sb, pending, t.start, 0, "System.out.");
        }
        if (plan.insertPrint[idx]) {
            // 中文糖：把 `打印` 整体换成 `System.out.print`
            return emitToken(sb, pending, t, "System.out.print");
        }
        if (plan.insertVoid[idx]) {
            // 省略了返回类型，补一个 `void `
            emit(sb, pending, t.start, 0, "void ");
        }
        if (plan.insertForVar[idx]) {
            // 增强 for 省略了类型，补一个 `var `
            emit(sb, pending, t.start, 0, "var ");
        }
        return -1;
    }

    /** 整词改写：`数组复制` / `系统` / `长度` / `否则若`；不命中返回 -1。 */
    private static int renderRenamed(TranslatePlan plan, StringBuilder sb, List<int[]> pending, Token t, int idx) {
        String replacement;
        if (plan.renameArraycopy[idx]) {
            replacement = "arraycopy";
        } else if (plan.renameSystem[idx]) {
            replacement = "System";
        } else if (plan.renameLength[idx]) {
            replacement = "length";
        } else {
            replacement = "else if";
        }
        return emitToken(sb, pending, t, replacement);
    }

    /** 补上一 token 需要补的右括号：紧贴它的结尾输出，排在后续空白之前。 */
    private static void appendPendingClosers(StringBuilder sb, int idx, boolean[] arrayListClose, boolean[] printParenClose) {
        if (arrayListClose[idx]) {
            sb.append(')');
        }
        if (printParenClose[idx]) {
            sb.append(')');
        }
    }

    /** 计数循环：整段头部换成一个 for 语句，次数表达式已单独转译；返回新的扫描位置。 */
    private static int renderRepeat(TranslatePlan plan, StringBuilder sb, List<int[]> pending, int idx) {
        List<Token> tokens = plan.tokens;
        int end = plan.repeatEnd[idx];
        int genStart = sb.length();
        sb.append(plan.repeatHeader[idx]);
        pending.add(new int[]{tokens.get(idx).start, tokens.get(end).end - tokens.get(idx).start,
                genStart, sb.length() - genStart});
        for (int k = idx + 1; k <= end; k++) {
            plan.skip[k] = true;
        }
        return tokens.get(end).end;
    }

    /** 4.5：`数组清单.从(` 整体换成 `new ArrayList<>(List.of(`，配对右括号后另补 `)`。 */
    private static int renderArrayListFrom(TranslatePlan plan, StringBuilder sb, List<int[]> pending, int idx) {
        Token t = plan.tokens.get(idx);
        emit(sb, pending, t.start, t.text.length(), "new ArrayList<>(List.of(");
        plan.skip[idx + 1] = true;
        plan.skip[idx + 2] = true;
        plan.skip[idx + 3] = true;
        return plan.tokens.get(idx + 3).end;
    }

    /** 关键字 → 英文；泛型实参位置换成包装类，并用空格补齐长度以保持列号。 */
    private static void renderKeyword(TranslatePlan plan, StringBuilder sb, List<int[]> pending, int idx) {
        Token t = plan.tokens.get(idx);
        String english = KEYWORDS.get(t.text);
        if (plan.wrapGeneric[idx]) {
            english = PRIMITIVE_WRAPPERS.get(english);
        }
        int genStart = sb.length();
        sb.append(english);
        int pad = t.text.length() - english.length();
        if (pad > 0) {
            sb.append(" ".repeat(pad));
        }
        pending.add(new int[]{t.start, t.text.length(), genStart, Math.max(t.text.length(), english.length())});
    }

    /** 非关键字：标点规范化（1 换 1，不影响列号）、字符串插值或原样输出。 */
    private static void renderLiteral(SourceFile sf, TranslatePlan plan, StringBuilder sb, List<int[]> pending, Token t) {
        if (t.kind == KIND_PUNCT) {
            sb.append(t.text);
        } else if (t.kind == KIND_OTHER && t.text == null
                && sf.original.charAt(t.start) == '"') {
            // 字符串插值："{名字}" → "" + 名字
            String raw = sf.original.substring(t.start, t.end);
            int genStart = sb.length();
            if (appendInterpolated(sb, raw, plan.declared)) {
                pending.add(new int[]{t.start, raw.length(), genStart, sb.length() - genStart});
            } else {
                sb.append(raw);
            }
        } else {
            sb.append(sf.original, t.start, t.end);
        }
    }

    /** 登记一段生成文本：原起点、原长度、生成起点、生成长度，供诊断位置回映射。 */
    private static void emit(StringBuilder sb, List<int[]> pending, int start, int origLen, String generated) {
        int genStart = sb.length();
        sb.append(generated);
        pending.add(new int[]{start, origLen, genStart, generated.length()});
    }

    /** 整体替换当前 token，返回下一个游标位置。 */
    private static int emitToken(StringBuilder sb, List<int[]> pending, Token t, String generated) {
        emit(sb, pending, t.start, t.text.length(), generated);
        return t.end;
    }

    /** 把生成文本里的位置换算回原源码，登记到 sf.replacements 供诊断回映射。 */
    private static void recordReplacements(SourceFile sf, List<int[]> pending, String translated) {
        int[] genLineStarts = lineStarts(translated);
        for (int[] r : pending) {
            int genLine = lineOf(genLineStarts, r[2]);
            int genCol = r[2] - genLineStarts[genLine];
            int origLine = lineOf(sf.origLineStarts, r[0]);
            int origCol = r[0] - sf.origLineStarts[origLine];
            sf.replacements.add(new Replacement(genLine, genCol, origCol, r[1], r[3]));
        }
    }

    /**
     * 字符串插值：把 "前缀{表达式}后缀" 展开成 "前缀" + 表达式 + "后缀"。
     * 只处理单行双引号字面量；`{{` / `}}` 是转义；表达式含引号、冒号、逗号、换行、
     * 花括号或为空时不展开，保持原样（避免误伤 JSON 之类的文本）。
     */
    static boolean appendInterpolated(StringBuilder sb, String literal, Set<String> declared) {
        if (literal.length() < 2 || !literal.startsWith("\"") || literal.startsWith("\"\"\"")
                || !literal.endsWith("\"")) {
            return false;
        }
        Interpolation in = splitInterpolation(literal.substring(1, literal.length() - 1), declared);
        if (in == null) {
            return false;
        }
        if (!in.expanded()) {
            if (!in.escaped()) {
                return false;
            }
            // 只有转义：{{ → {、}} → }，不产生拼接
            sb.append('"').append(in.tail()).append('"');
            return true;
        }
        appendInterpolationParts(sb, in.parts(), in.tail());
        return true;
    }

    /** 插值拆分结果：parts 是带 L/E 前缀的片段，tail 是末尾字面量。 */
    private record Interpolation(boolean expanded, boolean escaped, List<String> parts, String tail) {}

    /** 拆分 `{表达式}`；返回 null 表示含不支持的表达式，`{{` / `}}` 视为转义。 */
    private static Interpolation splitInterpolation(String content, Set<String> declared) {
        List<String> parts = new ArrayList<>();  // 前缀 L 表示字面量，E 表示表达式
        StringBuilder current = new StringBuilder();
        boolean expanded = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{' && i + 1 < content.length() && content.charAt(i + 1) == '{') {
                current.append('{');
                escaped = true;
                i++;
                continue;
            }
            if (c == '}' && i + 1 < content.length() && content.charAt(i + 1) == '}') {
                current.append('}');
                escaped = true;
                i++;
                continue;
            }
            if (c == '{') {
                int end = content.indexOf('}', i + 1);
                if (end < 0) {
                    return null;
                }
                String expr = content.substring(i + 1, end).trim();
                if (!isInterpolationExpression(expr)) {
                    return null;
                }
                parts.add("L" + current);
                current.setLength(0);
                parts.add("E" + translateFragment(expr, declared));
                expanded = true;
                i = end;
                continue;
            }
            current.append(c);
        }
        return new Interpolation(expanded, escaped, parts, current.toString());
    }

    /** 输出插值片段：表达式间用 ` + `；表达式开头补 `"" + ` 保证字符串拼接。 */
    private static void appendInterpolationParts(StringBuilder sb, List<String> parts, String tail) {
        List<String> all = new ArrayList<>(parts);
        all.add("L" + tail);
        boolean first = true;
        for (String part : all) {
            boolean isExpr = part.charAt(0) == 'E';
            String value = part.substring(1);
            if (!isExpr && value.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(" + ");
            } else if (isExpr) {
                sb.append("\"\" + ");
            }
            if (isExpr) {
                sb.append(value);
            } else {
                sb.append('"').append(value).append('"');
            }
            first = false;
        }
    }

    /** 插值表达式只接受「属性 / 方法调用」这类安全形式，其余保持原样。 */
    static boolean isInterpolationExpression(String expr) {
        if (expr.isEmpty()) {
            return false;
        }
        boolean hasName = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'' || c == '\n' || c == '\r' || c == ':' || c == ','
                    || c == '{' || c == '}' || c == '\\') {
                return false;
            }
            if (Character.isLetter(c) || c == '_') {
                hasName = true;
            }
        }
        return hasName;
    }

    /** 片段转译：做关键字、类型别名与中文 API 糖替换（插值表达式内部使用）。 */
    static String translateFragment(String expr, Set<String> declared) {
        List<Token> tokens = scan(expr, false);
        StringBuilder sb = new StringBuilder(expr.length());
        int cursor = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.start > cursor) {
                sb.append(expr, cursor, t.start);
            }
            FragmentCall call = fragmentStaticCall(expr, tokens, i);
            if (call != null) {
                sb.append(call.replacement());
                cursor = tokens.get(call.consumeTo()).end;
                i = call.consumeTo();
            } else {
                appendFragmentToken(sb, expr, tokens, i, declared);
                cursor = t.end;
            }
        }
        if (cursor < expr.length()) {
            sb.append(expr, cursor, expr.length());
        }
        return sb.toString();
    }

    /** 片段里的中文静态调用：替换文本与消费到的下标。 */
    private record FragmentCall(String replacement, int consumeTo) {}

    /** `算术.平方根` 这类两段式静态糖；不命中返回 null。 */
    private static FragmentCall fragmentStaticCall(String expr, List<Token> tokens, int i) {
        if (i + 2 >= tokens.size() || !textAt(expr, tokens, i + 1).equals(".")) {
            return null;
        }
        String key = textAt(expr, tokens, i) + "." + textAt(expr, tokens, i + 2);
        String replacement = STATIC_CALLS.get(key);
        return replacement == null ? null : new FragmentCall(replacement, i + 2);
    }

    /** 追加单个片段 token：成员糖、关键字、`长度` / `数组复制` / `系统`，原样回退。 */
    private static void appendFragmentToken(StringBuilder sb, String expr, List<Token> tokens,
                                            int i, Set<String> declared) {
        Token t = tokens.get(i);
        if (isFragmentMemberCall(expr, tokens, i, declared)) {
            sb.append(MEMBER_CALLS.get(t.text));
        } else if (t.kind == KIND_KEYWORD) {
            appendKeywordPad(sb, t);
        } else if (t.kind == KIND_IDENTIFIER && "长度".equals(t.text) && isAfterDot(expr, tokens, i)) {
            // 插值表达式里的 `xxx.长度` 也要转成 `xxx.length`
            sb.append("length");
        } else if (t.kind == KIND_IDENTIFIER && "数组复制".equals(t.text) && isAfterDot(expr, tokens, i)) {
            sb.append("arraycopy");
        } else if (t.kind == KIND_IDENTIFIER && "系统".equals(t.text) && isBeforeDot(expr, tokens, i)) {
            // 插值表达式里的 `系统.xxx` 也要转成 `System.xxx`
            sb.append("System");
        } else {
            sb.append(expr, t.start, t.end);
        }
    }

    /** 片段里的成员糖：`.` 之后、`(` 之前，且接收者不是标准库类名。 */
    private static boolean isFragmentMemberCall(String expr, List<Token> tokens, int i, Set<String> declared) {
        Token t = tokens.get(i);
        if ((t.kind != KIND_IDENTIFIER && t.kind != KIND_KEYWORD) || !MEMBER_CALLS.containsKey(t.text)) {
            return false;
        }
        if (!isAfterDot(expr, tokens, i) || i + 1 >= tokens.size()) {
            return false;
        }
        if (!textAt(expr, tokens, i + 1).equals("(")) {
            return false;
        }
        return !isStdlibReceiver(tokens, expr, i - 2, declared);
    }

    private static boolean isAfterDot(String expr, List<Token> tokens, int i) {
        return i > 0 && ".".equals(textAt(expr, tokens, i - 1));
    }

    private static boolean isBeforeDot(String expr, List<Token> tokens, int i) {
        return i + 1 < tokens.size() && ".".equals(textAt(expr, tokens, i + 1));
    }

    /** 关键字替换并按原长度补空格，保证列号不变。 */
    private static void appendKeywordPad(StringBuilder sb, Token t) {
        String english = KEYWORDS.get(t.text);
        sb.append(english);
        int pad = t.text.length() - english.length();
        if (pad > 0) {
            sb.append(" ".repeat(pad));
        }
    }

    /** 取 token 的文本；标点等 token 的 text 为 null，需回退到源串切片。 */
    static String textAt(String source, List<Token> tokens, int idx) {
        Token t = tokens.get(idx);
        return t.text != null ? t.text : source.substring(t.start, t.end);
    }

    /** 同上，直接传 SourceFile；越界时返回空串，供条件判断使用。 */
    static String textAt(SourceFile sf, List<Token> tokens, int idx) {
        if (idx < 0 || idx >= tokens.size()) {
            return "";
        }
        return textAt(sf.original, tokens, idx);
    }

    /** 可作为方法声明前缀的修饰符（用于识别省略返回类型的写法）。 */
    static boolean isModifier(String english) {
        switch (english) {
            case "public":
            case "protected":
            case "private":
            case "static":
            case "final":
            case "abstract":
            case "synchronized":
            case "native":
            case "strictfp":
            case "default":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ 转译落盘 / 反译 / 打包

    /** 转译结果按相对源根的路径写入输出目录，供构建工具（Maven 插件）接管后续 javac。 */
    static void writeTranslated(List<SourceFile> files, Path outDir) throws IOException {
        for (SourceFile sf : files) {
            Path target = outDir.resolve(sf.relative);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, sf.translated, StandardCharsets.UTF_8);
        }
    }

    static void reverse(List<String> inputs, Path outDir) throws IOException {
        LinkedHashMap<Path, Path> collected = collect(inputs);
        for (Map.Entry<Path, Path> e : collected.entrySet()) {
            String chinese = reverseTranslate(read(e.getKey()));
            Path target = outDir.resolve(e.getValue());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, chinese, StandardCharsets.UTF_8);
        }
        System.out.println("反译完成：" + collected.size() + " 个文件 → "
                + outDir.toAbsolutePath().normalize());
    }

    /**
     * 英文关键字 → 中文关键字。原关键字与后一个记号之间必然已有分隔符，
     * 因此替换后不会产生新的粘连，无需补空格。
     */
    static String reverseTranslate(String source) {
        List<Token> tokens = scan(source, true);
        StringBuilder sb = new StringBuilder(source.length());
        int cursor = 0;
        for (Token t : tokens) {
            if (t.start > cursor) {
                sb.append(source, cursor, t.start);
            }
            if (t.kind == KIND_EN_KEYWORD) {
                sb.append(CHINESE_BY_ENGLISH.get(t.text));
            } else {
                sb.append(source, t.start, t.end);
            }
            cursor = t.end;
        }
        if (cursor < source.length()) {
            sb.append(source, cursor, source.length());
        }
        return sb.toString();
    }

    static int packageJar(Path outDir, Path jarPath, String mainClass) throws IOException {
        Path target = jarPath.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        java.util.spi.ToolProvider jar = java.util.spi.ToolProvider.findFirst("jar").orElse(null);
        if (jar == null) {
            System.err.println("错误：当前 JDK 不提供 jar 工具，无法打包");
            return 1;
        }
        return jar.run(System.out, System.err,
                "--create",
                "--file", target.toString(),
                "--main-class", mainClass,
                "-C", outDir.toAbsolutePath().normalize().toString(), ".");
    }

    // ------------------------------------------------------------------ 编译与诊断回映射

    static int compile(List<SourceFile> files, Path outDir, String classpath) throws IOException {
        Path tempDir = Files.createTempDirectory("cjc-");
        Map<Path, SourceFile> byTempPath = new LinkedHashMap<>();
        try {
            for (SourceFile sf : files) {
                Path temp = tempDir.resolve(sf.relative);
                Files.createDirectories(temp.getParent());
                Files.writeString(temp, sf.translated, StandardCharsets.UTF_8);
                sf.tempPath = temp;
                byTempPath.put(temp.toAbsolutePath().normalize(), sf);
            }
            Files.createDirectories(outDir);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                System.err.println("错误：当前运行环境没有 javac 编译器（需要 JDK，而不是 JRE）");
                return 1;
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            int errorCount;
            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, Locale.CHINA, StandardCharsets.UTF_8)) {
                List<String> options = new ArrayList<>(List.of(
                        "-d", outDir.toAbsolutePath().normalize().toString(),
                        "-encoding", "UTF-8",
                        "-proc:none"));
                if (classpath != null) {
                    options.add("-classpath");
                    options.add(classpath);
                }
                Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(
                        files.stream().map(f -> f.tempPath).collect(Collectors.toList()));
                compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            }
            errorCount = printDiagnostics(diagnostics, byTempPath);
            return errorCount;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ---------------------------------------------------------------- javac 诊断中文化

    /** 诊断表的一行：正则 + 中文模板 + 可选建议；`{1}` / `{1:类型}` / `{1:去引号}` 为占位。 */
    static final class DiagRule {
        final Pattern pattern;
        final String message;
        final String hint;

        DiagRule(Pattern pattern, String message, String hint) {
            this.pattern = pattern;
            this.message = message;
            this.hint = hint;
        }
    }

    static final List<DiagRule> DIAG_RULES = new ArrayList<>();

    /**
     * 加载诊断中文表：与关键字表同目录的 诊断中文表.txt，
     * 每行「正则<TAB>中文模板[<TAB>建议模板]」；文件缺失时保持 javac 英文原文。
     */
    static void loadDiagnostics(Path tablePath) throws IOException {
        Path diagPath = tablePath.resolveSibling("诊断中文表.txt");
        if (!Files.exists(diagPath)) {
            System.err.println("提示：未找到诊断中文表 " + diagPath + "，javac 诊断保持英文原文。");
            return;
        }
        List<String> problems = new ArrayList<>();
        List<DiagRule> rules = new ArrayList<>();
        for (TableLine entry : readTableLines(diagPath)) {
            int lineNo = entry.lineNo;
            String line = entry.text;
            String[] cols = line.split("\t", -1);
            if (cols.length < 2 || cols.length > 3) {
                problems.add("第" + lineNo + "行应为「正则<TAB>中文模板[<TAB>建议模板]」：" + line);
                continue;
            }
            String regex = cols[0].strip();
            String message = cols[1].strip();
            String hint = cols.length == 3 && !cols[2].strip().isEmpty() ? cols[2].strip() : null;
            try {
                rules.add(new DiagRule(Pattern.compile(regex), message, hint));
            } catch (PatternSyntaxException e) {
                problems.add("第" + lineNo + "行正则无法编译：" + e.getDescription());
            }
        }
        if (rules.isEmpty()) {
            problems.add("诊断中文表为空");
        }
        if (!problems.isEmpty()) {
            failTable("诊断中文表", diagPath, problems);
        }
        DIAG_RULES.clear();
        DIAG_RULES.addAll(rules);
    }

    static final Pattern DIAG_PLACEHOLDER = Pattern.compile("\\{(\\d+)(?::([^}]+))?}");

    /** 用正则匹配结果渲染模板：`{1}` 原样、`{1:类型}` 走 chineseType、`{1:去引号}` 去掉单引号。 */
    static String renderDiagTemplate(String template, Matcher m) {
        Matcher ph = DIAG_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (ph.find()) {
            String value = m.group(Integer.parseInt(ph.group(1)));
            String modifier = ph.group(2);
            if (modifier != null && value != null) {
                if (modifier.equals("类型")) {
                    value = chineseType(value.trim());
                } else if (modifier.equals("去引号")) {
                    value = value.replace("'", "");
                }
            }
            ph.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        ph.appendTail(sb);
        return sb.toString();
    }

    /**
     * javac 自带的报错文本是英文（JDK 没有中文语言包），这里把常见错误翻成中文。
     * 规则主体在 诊断中文表.txt；这里只保留 4 条无法表驱动的特例：
     * 找不到符号（需要解析 symbol:/符号: 多行细节）、缺标点（分号/花括号/圆括号各给建议）。
     * 命中返回 {中文消息, 建议}；未命中返回 null，保留 javac 原文，避免 JDK 升级后误报。
     */
    static String[] localizeJavacMessage(String message) {
        String raw = message == null ? "" : message.replace("\r", "");
        String first = raw.split("\n", 2)[0].trim();

        // 特例 1/2：找不到符号（英文与中文两套消息格式）
        if (first.startsWith("cannot find symbol")) {
            String symbol = captureAfter(raw, "symbol:");
            if (symbol == null) {
                return rule("找不到符号", "检查拼写；确认变量已声明、类型已导入。");
            }
            String[] parts = symbol.split("\\s+", 2);
            return symbolRule(parts[0], parts.length > 1 ? parts[1] : "");
        }
        if (first.startsWith("找不到符号")) {
            String symbol = captureAfter(raw, "符号:");
            if (symbol == null) {
                symbol = captureAfter(raw, "符号：");
            }
            if (symbol == null) {
                return rule("找不到符号", "检查拼写；确认变量已声明、类型已导入。");
            }
            String[] parts = symbol.split("\\s+", 2);
            return symbolRule(parts[0], parts.length > 1 ? parts[1] : "");
        }
        // 特例 3/4：缺标点（分号 / 花括号 / 圆括号给出不同建议）
        Matcher m = findPattern("^'(.+)' expected$", first);
        if (m != null) {
            return missingToken(m.group(1));
        }
        m = findPattern("需要'(.+)'", first);
        if (m != null) {
            return missingToken(m.group(1));
        }
        for (DiagRule r : DIAG_RULES) {
            Matcher mm = r.pattern.matcher(first);
            if (mm.find()) {
                String hint = r.hint == null ? null : renderDiagTemplate(r.hint, mm);
                return rule(renderDiagTemplate(r.message, mm), hint);
            }
        }
        return null;
    }

    /** 缺标点提示：分号/花括号/圆括号各给一条针对性的建议。 */
    static String[] missingToken(String token) {
        String hint = switch (token) {
            case ";" -> "检查上一行末尾是否漏了分号（必须写半角「;」，不能用「；」）。";
            case "}" -> "检查花括号是否成对：每个「{」都要有对应的「}」。";
            case ")" -> "检查圆括号是否成对。";
            case "]" -> "检查方括号是否成对。";
            case "(" -> "检查圆括号是否成对。";
            default -> "检查这里的标点是否齐全。";
        };
        return rule("这里缺少「" + token + "」", hint);
    }

    static String[] rule(String message, String hint) {
        return new String[]{message, hint};
    }

    /** 「找不到符号」的细分建议；中英文消息共用。 */
    static String[] symbolRule(String kind, String name) {
        return switch (kind) {
            case "variable", "变量" -> rule("找不到符号：变量「" + name + "」",
                    "确认变量已声明或已赋值；若来自其他类，检查是否用「导入」引入。");
            case "class", "类" -> rule("找不到符号：类「" + name + "」",
                    "检查类名拼写；java.util 等包里的类型需要显式「导入」。");
            case "method", "方法" -> rule("找不到符号：方法「" + name + "」",
                    "检查方法名与参数个数；确认接收者对象已经创建。");
            case "package", "包", "程序包" -> rule("找不到符号：包「" + name + "」", "检查包名拼写与目录结构。");
            case "constructor", "构造器" -> rule("找不到符号：构造器「" + name + "」", "检查类名拼写与参数个数。");
            default -> rule("找不到符号：" + name, "检查拼写、声明与导入。");
        };
    }

    /** 把 javac 消息里的英文类型名换成中文别名，便于初学者阅读。 */
    static String chineseType(String type) {
        return switch (type) {
            case "java.lang.String", "String" -> "字符串";
            case "java.lang.Integer", "Integer" -> "整数包装";
            case "java.lang.Long", "Long" -> "长整数包装";
            case "java.lang.Short", "Short" -> "短整数包装";
            case "java.lang.Byte", "Byte" -> "字节包装";
            case "java.lang.Float", "Float" -> "浮点包装";
            case "java.lang.Double", "Double" -> "双浮点包装";
            case "java.lang.Boolean", "Boolean" -> "布尔包装";
            case "java.lang.Character", "Character" -> "字符包装";
            case "java.lang.Object", "Object" -> "对象";
            case "int" -> "整数";
            case "long" -> "长整数";
            case "short" -> "短整数";
            case "byte" -> "字节";
            case "float" -> "浮点";
            case "double" -> "双浮点";
            case "char" -> "字符";
            case "boolean" -> "布尔";
            case "void" -> "无";
            default -> type;
        };
    }

    /** 取标记（如 `symbol:`）所在行的剩余内容；没有该标记返回 null。 */
    static String captureAfter(String raw, String marker) {
        int at = raw.indexOf(marker);
        if (at < 0) {
            return null;
        }
        String rest = raw.substring(at + marker.length());
        int nl = rest.indexOf('\n');
        String line = nl < 0 ? rest : rest.substring(0, nl);
        return line.trim().isEmpty() ? null : line.trim();
    }

    static Matcher findPattern(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher : null;
    }

    static int printDiagnostics(DiagnosticCollector<JavaFileObject> collector,
                                Map<Path, SourceFile> byTempPath) {
        int errors = 0;
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE) {
                continue;
            }
            String label = switch (d.getKind()) {
                case ERROR -> "错误";
                case WARNING, MANDATORY_WARNING -> "警告";
                default -> "提示";
            };
            SourceFile sf = resolveSource(d, byTempPath);
            // 固定取英文原文：JDK 自带的中文消息格式不统一，先拿到稳定英文再自行中文化。
            String message = d.getMessage(Locale.ENGLISH);
            String[] localized = localizeJavacMessage(message);
            String shown = localized == null ? message : localized[0];
            String hint = localized == null ? null : localized[1];
            if (sf == null || d.getLineNumber() <= 0) {
                System.err.println(label + "：" + shown);
                if (hint != null) {
                    System.err.println("    建议：" + hint);
                }
            } else {
                int wrappedLine = (int) d.getLineNumber();
                int line = wrappedLine - sf.lineOffset;  // 脚本模式：换算回正文行号
                int col = remapColumn(sf, wrappedLine, (int) d.getColumnNumber());
                System.err.println();
                System.err.println(label + "：第" + line + "行 第" + col + "列  " + shown);
                if (hint != null) {
                    System.err.println("    建议：" + hint);
                }
                System.err.println("    " + String.format("%4d", line) + " | "
                        + lineText(sf.original, sf.origLineStarts, wrappedLine));
                if (col > 0) {
                    System.err.println("         | " + spaces(col - 1) + "^");
                }
            }
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors++;
            }
        }
        return errors;
    }

    static SourceFile resolveSource(Diagnostic<? extends JavaFileObject> d,
                                    Map<Path, SourceFile> byTempPath) {
        JavaFileObject source = d.getSource();
        if (source == null) {
            return null;
        }
        try {
            Path p = Paths.get(source.toUri()).toAbsolutePath().normalize();
            return byTempPath.get(p);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 把译文中的列号还原成中文源码中的列号。
     * 关键字替换会改变同一行的字符数，替换点之后的列需要平移。
     */
    static int remapColumn(SourceFile sf, int line, int generatedColumn) {
        if (generatedColumn <= 0) {
            return generatedColumn;
        }
        int genCol = generatedColumn - 1;
        Replacement hit = null;
        for (Replacement r : sf.replacements) {
            if (r.line == line - 1 && r.genCol <= genCol) {
                hit = r;
            }
        }
        if (hit == null) {
            return generatedColumn;
        }
        int inside = genCol - hit.genCol;
        if (inside < hit.genLen) {
            return hit.origCol + 1;
        }
        return hit.origCol + hit.origLen + (inside - hit.genLen) + 1;
    }

    // ------------------------------------------------------------------ 工具方法

    static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                starts.add(i + 1);
            } else if (c == '\r' && !(i + 1 < text.length() && text.charAt(i + 1) == '\n')) {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(0, lo - 1);
    }

    static String lineText(String text, int[] lineStarts, int line) {
        int index = line - 1;
        if (index < 0 || index >= lineStarts.length) {
            return "";
        }
        int start = lineStarts[index];
        int end = (index + 1 < lineStarts.length) ? lineStarts[index + 1] : text.length();
        return text.substring(start, end).replace("\r", "").replace("\n", "");
    }

    static String spaces(int count) {
        return " ".repeat(Math.max(0, count));
    }

    /** 诊断位置：行号（脚本模式已换算回正文）、列（0 起始）与该行原文。 */
    private record SourcePos(int line, int col, String lineText) {}

    /** 把源码偏移换算成诊断位置。 */
    static SourcePos linePosition(SourceFile sf, int offset) {
        int lineIndex = lineOf(sf.origLineStarts, offset);
        int wrappedLine = lineIndex + 1;
        return new SourcePos(wrappedLine - sf.lineOffset, offset - sf.origLineStarts[lineIndex],
                lineText(sf.original, sf.origLineStarts, wrappedLine));
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ 数据结构

    static final class Token {
        final int kind;
        final int start;
        final int end;
        final String text;

        Token(int kind, int start, int end, String text) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    static final class Replacement {
        final int line;
        final int genCol;
        final int origCol;
        final int origLen;
        final int genLen;

        Replacement(int line, int genCol, int origCol, int origLen, int genLen) {
            this.line = line;
            this.genCol = genCol;
            this.origCol = origCol;
            this.origLen = origLen;
            this.genLen = genLen;
        }
    }

    static final class SourceFile {
        final Path path;
        final Path relative;
        final String original;
        final List<Replacement> replacements = new ArrayList<>();
        List<Token> tokens;
        int[] origLineStarts;
        String translated;
        Path tempPath;
        boolean script;      // 脚本模式（整份文件只有语句）
        int lineOffset;      // 包裹时正文前插入的行数，诊断行号需要减去它

        SourceFile(Path path, Path relative, String original) {
            this.path = path;
            this.relative = relative;
            this.original = original;
        }
    }
}
