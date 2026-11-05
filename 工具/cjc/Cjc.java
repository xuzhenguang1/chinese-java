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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null");

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

    // 中文标准库（包 中文.标准库）的类名：这些接收者上的成员调用保持原样，不走成员糖。
    // 标准库类自己就提供中文方法（`文本.格式化`、`并发.关闭`、`集合工具.排序` 等），
    // 若也按方法名全局改写，会变成这些类上不存在的方法名。
    static final Set<String> STDLIB_CLASSES = Set.of(
            "输出", "列表", "映射", "文本", "数学", "数值", "时间",
            "文件", "集合工具", "并发", "网络", "日志", "数据");

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

    // 反译用：英文 → 中文 的反向表，以及英文关键字集合（由关键字表加载时构建）
    static final LinkedHashMap<String, String> CHINESE_BY_ENGLISH = new LinkedHashMap<>();
    static final Set<String> ENGLISH_KEYWORDS = new HashSet<>();

    // 中文成员方法 → Java 方法：只在 `.` 之后、后接 `(` 时识别，例如 `名称.加入(…)` → `名称.add(…)`。
    // 命名刻意避开 中文.标准库 已有方法（加、取、放、排序、去空白…），避免把标准库调用改坏。
    static final LinkedHashMap<String, String> MEMBER_CALLS = new LinkedHashMap<>();
    static {
        MEMBER_CALLS.put("修剪", "trim");
        MEMBER_CALLS.put("转大写", "toUpperCase");
        MEMBER_CALLS.put("转小写", "toLowerCase");
        MEMBER_CALLS.put("含有", "contains");
        MEMBER_CALLS.put("以开头", "startsWith");
        MEMBER_CALLS.put("以结尾", "endsWith");
        MEMBER_CALLS.put("替换", "replace");
        MEMBER_CALLS.put("取子串", "substring");
        MEMBER_CALLS.put("拆分", "split");
        MEMBER_CALLS.put("复读", "repeat");
        MEMBER_CALLS.put("取字符", "charAt");
        MEMBER_CALLS.put("全空白", "isBlank");
        MEMBER_CALLS.put("替换全部", "replaceAll");
        MEMBER_CALLS.put("转字符数组", "toCharArray");
        MEMBER_CALLS.put("加入", "add");
        MEMBER_CALLS.put("取出", "get");
        MEMBER_CALLS.put("设定", "set");
        MEMBER_CALLS.put("移除", "remove");
        MEMBER_CALLS.put("元素数", "size");
        MEMBER_CALLS.put("是空", "isEmpty");
        MEMBER_CALLS.put("整理", "sort");
        MEMBER_CALLS.put("清空", "clear");
        MEMBER_CALLS.put("放入", "put");
        MEMBER_CALLS.put("取出或", "getOrDefault");
        MEMBER_CALLS.put("值集合", "values");
        MEMBER_CALLS.put("键集合", "keySet");
        MEMBER_CALLS.put("含键", "containsKey");
        MEMBER_CALLS.put("追加", "append");
        MEMBER_CALLS.put("转文本", "toString");
        MEMBER_CALLS.put("比较", "compareTo");
        MEMBER_CALLS.put("等于", "equals");
        MEMBER_CALLS.put("哈希码", "hashCode");
        MEMBER_CALLS.put("值", "valueOf");
        MEMBER_CALLS.put("反序", "reversed");
        MEMBER_CALLS.put("取消息", "getMessage");
        MEMBER_CALLS.put("转绝对路径", "toAbsolutePath");
        MEMBER_CALLS.put("取上级", "getParent");
        MEMBER_CALLS.put("流", "stream");
        // Stream 中间/终端操作：与 `流` 配套，避免学到 Stream 又掉回英文。
        // `变换` 而不是 `映射`：`映射` 已是标准库类名（Map 包装），不再借作方法名。
        MEMBER_CALLS.put("过滤", "filter");
        MEMBER_CALLS.put("变换", "map");
        MEMBER_CALLS.put("遍历", "forEach");
        MEMBER_CALLS.put("去重", "distinct");
        MEMBER_CALLS.put("收集", "collect");
        MEMBER_CALLS.put("找首个", "findFirst");
        MEMBER_CALLS.put("存在时", "ifPresent");
        MEMBER_CALLS.put("或", "orElse");
        // 输入：`扫描器.读行()` → `Scanner.nextLine()`；控制台交互不必再写英文方法名。
        MEMBER_CALLS.put("读行", "nextLine");
        MEMBER_CALLS.put("读整数", "nextInt");
        MEMBER_CALLS.put("读长整数", "nextLong");
        MEMBER_CALLS.put("读双浮点", "nextDouble");
        MEMBER_CALLS.put("有下一行", "hasNextLine");
        MEMBER_CALLS.put("有下一个", "hasNext");
        // 集合补充：按下标查找、映射按值判断。
        MEMBER_CALLS.put("取下标", "indexOf");
        MEMBER_CALLS.put("含值", "containsValue");
    }

    // 中文重写方法声明 → Java 同名方法：不带 `.` 前缀、后接 `(` 时识别，
    // 例如 `公有 字符串 转文本()` → `public String toString()`。
    // 这三个方法由 Object 定义，Java 不允许改名，只能在声明处还原。
    static final LinkedHashMap<String, String> MEMBER_DECLS = new LinkedHashMap<>();
    static {
        MEMBER_DECLS.put("转文本", "toString");
        MEMBER_DECLS.put("等于", "equals");
        MEMBER_DECLS.put("哈希码", "hashCode");
    }

    // 中文类名 + 方法 → Java 静态调用，例如 `算术.平方根(…)` → `Math.sqrt(…)`。
    static final LinkedHashMap<String, String> STATIC_CALLS = new LinkedHashMap<>();
    static {
        STATIC_CALLS.put("算术.绝对值", "Math.abs");
        STATIC_CALLS.put("算术.最大", "Math.max");
        STATIC_CALLS.put("算术.最小", "Math.min");
        STATIC_CALLS.put("算术.平方根", "Math.sqrt");
        STATIC_CALLS.put("算术.幂", "Math.pow");
        STATIC_CALLS.put("算术.圆周率", "Math.PI");
        STATIC_CALLS.put("算术.随机", "Math.random");
        STATIC_CALLS.put("集合助手.排序", "Collections.sort");
        STATIC_CALLS.put("比较器.按整数", "Comparator.comparingInt");
        STATIC_CALLS.put("字符串.拼接", "String.join");
        STATIC_CALLS.put("整数包装.解析", "Integer.parseInt");
        STATIC_CALLS.put("文件路径.取", "Path.of");
        STATIC_CALLS.put("文件助手.建目录", "Files.createDirectories");
        STATIC_CALLS.put("文件助手.写文本", "Files.writeString");
        STATIC_CALLS.put("文件助手.读文本", "Files.readString");
        STATIC_CALLS.put("文件助手.读所有行", "Files.readAllLines");
        STATIC_CALLS.put("文件助手.存在", "Files.exists");
        STATIC_CALLS.put("可选值.空", "Optional.empty");
        STATIC_CALLS.put("可选值.装入", "Optional.of");
        STATIC_CALLS.put("可选值.可空", "Optional.ofNullable");
        STATIC_CALLS.put("数组助手.排序", "Arrays.sort");
        STATIC_CALLS.put("数组助手.转文本", "Arrays.toString");
        STATIC_CALLS.put("数组助手.填充", "Arrays.fill");
        STATIC_CALLS.put("数组助手.复制", "Arrays.copyOf");
        STATIC_CALLS.put("数组助手.转清单", "Arrays.asList");
        STATIC_CALLS.put("集合助手.反转", "Collections.reverse");
        STATIC_CALLS.put("集合助手.打乱", "Collections.shuffle");
        STATIC_CALLS.put("集合助手.最大", "Collections.max");
        STATIC_CALLS.put("集合助手.最小", "Collections.min");
        STATIC_CALLS.put("字符串.格式化", "String.format");
        STATIC_CALLS.put("算术.向下取整", "Math.floor");
        STATIC_CALLS.put("算术.向上取整", "Math.ceil");
        STATIC_CALLS.put("算术.四舍五入", "Math.round");
        STATIC_CALLS.put("双浮点包装.解析", "Double.parseDouble");
        STATIC_CALLS.put("长整数包装.解析", "Long.parseLong");
        STATIC_CALLS.put("布尔包装.解析", "Boolean.parseBoolean");
        STATIC_CALLS.put("系统.标准输入", "System.in");
        STATIC_CALLS.put("系统.标准错误", "System.err");
        STATIC_CALLS.put("系统.当前毫秒", "System.currentTimeMillis");
        STATIC_CALLS.put("系统.纳秒", "System.nanoTime");
        STATIC_CALLS.put("系统.退出", "System.exit");
        // 集合工厂：`清单.从("甲", "乙")` → `List.of(...)`（JDK 9+，不可变集合）。
        STATIC_CALLS.put("清单.从", "List.of");
        STATIC_CALLS.put("键值表.从", "Map.of");
        STATIC_CALLS.put("集.从", "Set.of");
        // Stream 收集器：需要 java.util.stream.*（自动导入已补）。
        STATIC_CALLS.put("收集器.转清单", "Collectors.toList");
        STATIC_CALLS.put("收集器.转集", "Collectors.toSet");
        STATIC_CALLS.put("收集器.拼接", "Collectors.joining");
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        List<String> inputs = new ArrayList<>();
        Path outDir = null;
        Path jarPath = null;
        String mainClass = null;
        String tableArg = null;
        String classpath = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-d")) {
                if (i + 1 >= args.length) {
                    System.err.println("错误：-d 后缺少输出目录");
                    System.exit(2);
                }
                outDir = Paths.get(args[++i]);
            } else if (args[i].equals("-jar")) {
                if (i + 1 >= args.length) {
                    System.err.println("错误：-jar 后缺少 jar 路径");
                    System.exit(2);
                }
                jarPath = Paths.get(args[++i]);
            } else if (args[i].equals("-主类")) {
                if (i + 1 >= args.length) {
                    System.err.println("错误：-主类 后缺少全限定类名");
                    System.exit(2);
                }
                mainClass = args[++i];
            } else if (args[i].equals("--表")) {
                if (i + 1 >= args.length) {
                    System.err.println("错误：--表 后缺少关键字表路径");
                    System.exit(2);
                }
                tableArg = args[++i];
            } else if (args[i].equals("-cp") || args[i].equals("--类路径")) {
                if (i + 1 >= args.length) {
                    System.err.println("错误：" + args[i] + " 后缺少类路径");
                    System.exit(2);
                }
                classpath = args[++i];
            } else if (args[i].equals("--允许推断")) {
                allowInference = true;
            } else {
                inputs.add(args[i]);
            }
        }
        boolean knownCommand = command.equals("检查") || command.equals("编译")
                || command.equals("转译") || command.equals("反译") || command.equals("运行")
                || command.equals("新建");
        if (!knownCommand || inputs.isEmpty()) {
            usage();
            System.exit(2);
        }
        if (jarPath != null && mainClass == null) {
            System.err.println("错误：-jar 需要同时用 -主类 指定入口类（例如 -主类 demo.app.Main）");
            System.exit(2);
        }
        if (mainClass != null && jarPath == null && !command.equals("运行")) {
            System.err.println("错误：-主类 只能配合 -jar 或 运行 命令使用");
            System.exit(2);
        }

        if (command.equals("新建")) {
            createProject(inputs, resolveTable(tableArg));
            return;
        }

        loadKeywords(resolveTable(tableArg));

        if (command.equals("反译")) {
            if (outDir == null) {
                System.err.println("错误：反译命令需要指定输出目录，例如 -d 中文源码");
                System.exit(2);
            }
            reverse(inputs, outDir);
            return;
        }

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

        int stickyErrors = checkSticky(files, identifierNames);
        int punctuationErrors = checkPunctuation(files);
        int trapWarnings = checkBeginnerTraps(files);
        int totalErrors = stickyErrors + punctuationErrors;
        if (totalErrors > 0) {
            System.out.println();
            System.out.println("检查未通过：" + totalErrors + " 处错误（CJK001 " + stickyErrors
                    + " 处空格粘连，CJK002 " + punctuationErrors + " 处全角标点）。");
            System.exit(1);
        }
        if (command.equals("检查")) {
            System.out.println("检查通过：" + files.size() + " 个文件（关键字 " + wordCount(false)
                    + " 条 + 类型别名 " + wordCount(true) + " 条，词典 " + DICT_GROUPS.size() + " 组）。");
            if (trapWarnings > 0) {
                System.out.println("另有 " + trapWarnings + " 处新手易错提示（仅建议，不影响编译）。");
            }
            return;
        }
        if (command.equals("运行")) {
            runExecution(files, classpath, mainClass);
            return;
        }
        if (outDir == null) {
            System.err.println("错误：" + command + "命令需要指定输出目录，例如 -d 输出");
            System.exit(2);
        }
        if (command.equals("转译")) {
            writeTranslated(files, outDir);
            System.out.println("转译完成：" + files.size() + " 个文件 → " + outDir.toAbsolutePath().normalize());
            return;
        }

        int compileErrors = compile(files, outDir, classpath);
        if (compileErrors > 0) {
            System.out.println();
            System.out.println("编译失败：" + compileErrors + " 个错误。");
            System.exit(1);
        }
        System.out.println("编译成功，class 输出目录：" + outDir.toAbsolutePath().normalize());

        if (jarPath != null) {
            if (packageJar(outDir, jarPath, mainClass) != 0) {
                System.out.println();
                System.out.println("打包失败。");
                System.exit(1);
            }
            System.out.println("打包成功，可运行 jar：" + jarPath.toAbsolutePath().normalize());
        }
    }

    /** `新建 项目 <名称>`：在当前目录生成可直接 mvn package 的中文工程骨架。 */
    static void createProject(List<String> inputs, Path tablePath) throws IOException {
        if (inputs.size() != 2 || !inputs.get(0).equals("项目")) {
            System.err.println("错误：用法为 新建 项目 <项目名>，例如 .\\cjc.ps1 新建 项目 我的工具");
            System.exit(2);
        }
        String name = inputs.get(1);
        Path project = Paths.get("").toAbsolutePath().resolve(name);
        if (Files.exists(project)) {
            System.err.println("错误：目录已存在：" + project);
            System.exit(2);
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
        sb.append("        </plugins>\n");
        sb.append("    </build>\n");
        sb.append("</project>\n");
        return sb.toString();
    }

    static void usage() {
        System.out.println("Cjc —— 中文 Java 源码检查、转译与编译工具（M2）");
        System.out.println("用法：");
        System.out.println("  java 工具\\cjc\\Cjc.java 检查 <文件或目录...> [--表 <关键字表>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 运行 <文件> [-cp <类路径>] [-主类 <全限定类名>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 编译 <文件或目录...> -d <输出目录> [-cp <类路径>] [-jar <jar路径> -主类 <全限定类名>]");
        System.out.println("  java 工具\\cjc\\Cjc.java 转译 <文件或目录...> -d <输出目录>");
        System.out.println("  java 工具\\cjc\\Cjc.java 反译 <文件或目录...> -d <输出目录>");
        System.out.println("  java 工具\\cjc\\Cjc.java 新建 项目 <项目名>");
        System.out.println("选项：--允许推断 启用「令」/「常量」类型推断糖（默认关闭）。");
        System.out.println("规则：中文关键字后必须跟空格或标点，否则会与后面的名称粘连成一个标识符。");
        System.out.println("      全角标点会自动规范化为半角；顿号、句号、弯引号等无法映射，报 CJK002。");
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
        System.err.println("错误：找不到关键字表，请用 --表 <路径> 指定，或在工程根目录运行 cjc.ps1。");
        System.exit(2);
        return null;
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
        int lineNo = 0;
        for (String raw : Files.readAllLines(dictPath, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.strip();
            if (lineNo == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                problems.add("近义词典第" + lineNo + "行格式错误，应为「组名=[近义词1，近义词2]」：" + line);
                continue;
            }
            String name = line.substring(0, eq).strip();
            String right = line.substring(eq + 1).strip();
            if (!right.startsWith("[") || !right.endsWith("]")) {
                problems.add("近义词典第" + lineNo + "行缺少方括号：" + line);
                continue;
            }
            if (DICT_GROUPS.containsKey(name)) {
                problems.add("近义词典第" + lineNo + "行组名重复：" + name);
                continue;
            }
            List<String> synonyms = new ArrayList<>();
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
                continue;
            }
            seen.add(name);
            DICT_GROUPS.put(name, synonyms);
        }
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
            String next = prevText(source, tokens, i + 1);
            if (!(next.equals("=") || next.equals(";") || next.equals(",") || next.equals(")") || next.equals(":"))) {
                continue;
            }
            String prev = prevText(source, tokens, i - 1);
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
        String recvText = recv.text != null ? recv.text : source.substring(recv.start, recv.end);
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
            System.err.println("错误：找不到关键字表 " + tablePath);
            System.exit(2);
        }
        List<String> problems = new ArrayList<>();
        loadDictionary(tablePath, problems);
        int lineNo = 0;
        for (String raw : Files.readAllLines(tablePath, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.strip();
            if (lineNo == 1 && line.startsWith("\uFEFF")) {
                // 编辑器可能给首行写入 BOM，先剥离
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                problems.add("第" + lineNo + "行格式错误，应为「中文=英文」或「英文=[中文1，中文2]」：" + line);
                continue;
            }
            String left = line.substring(0, eq).strip();
            String right = line.substring(eq + 1).strip();
            if (right.startsWith("[") && right.endsWith("]")) {
                // 列表格式：英文=[中文1，中文2，…]，列表第一项为反译规范名
                if (!isTranslatable(left)) {
                    problems.add("第" + lineNo + "行英文关键字或类型不存在：" + left);
                    continue;
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
                        KEYWORDS.put(word, left);
                    }
                }
                if (!any) {
                    problems.add("第" + lineNo + "行同义词列表为空：" + line);
                }
                continue;
            }
            String chinese = left;
            String english = right;
            if (!isTranslatable(english)) {
                problems.add("第" + lineNo + "行英文关键字或类型不存在：" + english);
                continue;
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
        if (KEYWORDS.isEmpty()) {
            problems.add("关键字表为空");
        }
        // 前缀允许：中文关键字之间可以存在前缀关系（如「假」与「假如」、「若」与「若是」）。
        // 翻译按整词匹配（标识符全文等于关键字才命中），因此长短词不会互相干扰，
        // 不必再做前缀无关校验；最长优先只用于 CJK001 粘连启发式的定位。
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                System.err.println("关键字表错误：" + problem);
            }
            System.err.println("关键字表校验未通过：" + problems.size() + " 处问题，文件 " + tablePath);
            System.exit(2);
        }
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


    // ------------------------------------------------------------------ 文件收集

    static LinkedHashMap<Path, Path> collect(List<String> inputs) throws IOException {
        LinkedHashMap<Path, Path> map = new LinkedHashMap<>();
        Map<Path, Path> owners = new LinkedHashMap<>();
        for (String input : inputs) {
            Path root = Paths.get(input).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                System.err.println("错误：找不到路径 " + input);
                System.exit(2);
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
            System.err.println("错误：没有找到 .java 文件");
            System.exit(2);
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
        // A3：文件里没有 导入 / 包 时，自动补 java.util.*、java.nio.file.*、java.io.* 与 java.util.stream.*。
        // 不自动补 中文.标准库.*：标准库类名（文本、列表、映射）容易与用户变量同名。
        if (!hasImportOrPackage(tokens)) {
            text = "导入 java.util.*;\n导入 java.nio.file.*;\n导入 java.io.*;\n导入 java.util.stream.*;\n" + text;
            lineOffset = 4;
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
     * 整份文件按「语句」处理（开头的 导入 允许保留）。注意名称前置声明（计数器 类 {）也能被识别。
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
                        || english.equals("package")) {
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
            System.err.println("错误：无法确定入口类，请用 -主类 <全限定类名> 指定");
            System.exit(2);
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
            int cp = source.codePointAt(i);
            int width = Character.charCount(cp);

            // 全角空格：javac 会报「非法字符」，这里统一转成半角空格
            if (cp == 0x3000) {
                tokens.add(new Token(KIND_PUNCT, i, i + 1, " "));
                i++;
                continue;
            }
            if (Character.isWhitespace(cp)) {
                i += width;
                continue;
            }
            // 行注释
            if (cp == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i + 2;
                while (j < n && source.charAt(j) != '\n' && source.charAt(j) != '\r') {
                    j++;
                }
                i = j;
                continue;
            }
            // 块注释
            if (cp == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = source.indexOf("*/", i + 2);
                i = (j < 0) ? n : j + 2;
                continue;
            }
            // 文本块
            if (cp == '"' && source.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n) {
                    int k = source.indexOf("\"\"\"", j);
                    if (k < 0) {
                        j = n;
                        break;
                    }
                    if (k > 0 && source.charAt(k - 1) == '\\') {
                        j = k + 1;
                        continue;
                    }
                    j = k + 3;
                    break;
                }
                tokens.add(new Token(KIND_OTHER, i, j, null));
                i = j;
                continue;
            }
            // 字符串 / 字符字面量
            if (cp == '"' || cp == '\'') {
                int j = i + 1;
                while (j < n) {
                    char ch = source.charAt(j);
                    if (ch == '\\') {
                        j += 2;
                        continue;
                    }
                    if (ch == cp || ch == '\n') {
                        j++;
                        break;
                    }
                    j++;
                }
                if (j > n) {
                    j = n;
                }
                tokens.add(new Token(KIND_OTHER, i, j, null));
                i = j;
                continue;
            }
            // 标识符（含中文标识符）
            if (Character.isJavaIdentifierStart(cp)) {
                int start = i;
                i += width;
                while (i < n) {
                    int next = source.codePointAt(i);
                    if (!Character.isJavaIdentifierPart(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                String text = source.substring(start, i);
                int kind;
                if (english) {
                    kind = ENGLISH_KEYWORDS.contains(text) ? KIND_EN_KEYWORD : KIND_IDENTIFIER;
                } else {
                    kind = KEYWORDS.containsKey(text) ? KIND_KEYWORD : KIND_IDENTIFIER;
                }
                tokens.add(new Token(kind, start, i, text));
                continue;
            }
            // 数字字面量（整体跳过，不参与关键字判定）
            if (Character.isDigit(cp) || (cp == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                i += width;
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
                tokens.add(new Token(KIND_OTHER, start, i, null));
                continue;
            }
            // 全角 ASCII 标点（U+FF01..U+FF5E）：平移 0xFEE0 得到半角
            if (cp >= 0xFF01 && cp <= 0xFF5E) {
                tokens.add(new Token(KIND_PUNCT, i, i + 1, String.valueOf((char) (cp - 0xFEE0))));
                i++;
                continue;
            }
            // 无法映射到半角的 CJK 标点：保留原文，报 CJK002
            if (CJK_PUNCTUATION.contains(cp)) {
                tokens.add(new Token(KIND_BAD_PUNCT, i, i + width, new String(Character.toChars(cp))));
                i += width;
                continue;
            }
            tokens.add(new Token(KIND_OTHER, i, i + width, null));
            i += width;
        }
        return tokens;
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
        if (idx + 1 >= tokens.size() || !prevText(sf.original, tokens, idx + 1).equals("(")) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        String prev = prevText(sf.original, tokens, idx - 1);
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
        int lineIndex = lineOf(sf.origLineStarts, token.start);
        int wrappedLine = lineIndex + 1;
        int line = wrappedLine - sf.lineOffset;  // 脚本模式：换算回正文行号
        int tokenCol = token.start - sf.origLineStarts[lineIndex];
        int gapCol = tokenCol + keyword.length();
        String lineText = lineText(sf.original, sf.origLineStarts, wrappedLine);

        System.err.println();
        System.err.println("错误 CJK001  第" + line + "行 第" + (gapCol + 1) + "列");
        System.err.println("  中文关键字「" + keyword + "」后缺少空格，与名称「" + rest
                + "」粘连，被整体识别为标识符「" + token.text + "」。");
        System.err.println("  建议：在「" + keyword + "」后补一个空格，写成「" + keyword + " " + rest + "」。");
        System.err.println("    " + String.format("%4d", line) + " | " + lineText);
        System.err.println("         | " + spaces(gapCol) + "^ 这里缺一个空格");
        String fixedLine = lineText.substring(0, gapCol) + " " + lineText.substring(gapCol);
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
                int lineIndex = lineOf(sf.origLineStarts, t.start);
                int wrappedLine = lineIndex + 1;
                int line = wrappedLine - sf.lineOffset;  // 脚本模式：换算回正文行号
                int col = t.start - sf.origLineStarts[lineIndex];
                String lineText = lineText(sf.original, sf.origLineStarts, wrappedLine);
                System.err.println();
                System.err.println("错误 CJK002  第" + line + "行 第" + (col + 1) + "列");
                System.err.println("  不支持的全角标点「" + t.text + "」，请改用半角标点"
                        + "（, . ? : ; \" ' ( ) 等）。");
                System.err.println("  建议：" + suggestionFor(t.text));
                System.err.println("    " + String.format("%4d", line) + " | " + lineText);
                System.err.println("         | " + spaces(col) + "^");
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
            while (j + 1 < tokens.size() && prevText(sf.original, tokens, j).equals("[")
                    && prevText(sf.original, tokens, j + 1).equals("]")) {
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
            if (!prevText(sf.original, tokens, i - 1).equals(".")
                    || !prevText(sf.original, tokens, i + 1).equals("(")
                    || !prevText(sf.original, tokens, i + 2).equals(")")) {
                continue;
            }
            String receiver = i >= 2 ? prevText(sf.original, tokens, i - 2) : "名单";
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
                    || !prevText(sf.original, tokens, i + 1).equals("(")) {
                continue;
            }
            boolean isInt = intReaders.contains(t.text);
            boolean isLine = "读行".equals(t.text);
            if (!isInt && !isLine) {
                continue;
            }
            String receiver = prevText(sf.original, tokens, i - 1).equals(".") && i >= 2
                    ? prevText(sf.original, tokens, i - 2) : "";
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
        int lineIndex = lineOf(sf.origLineStarts, token.start);
        int wrappedLine = lineIndex + 1;
        return lineText(sf.original, sf.origLineStarts, wrappedLine).contains("忽略提示");
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
            if (!prevText(sf.original, tokens, i + 1).equals("(")) {
                continue;
            }
            List<Integer> semicolons = new ArrayList<>();
            int depth = 1;
            int close = -1;
            for (int j = i + 2; j < tokens.size(); j++) {
                String s = prevText(sf.original, tokens, j);
                if (s.equals("(") || s.equals("[") || s.equals("{")) {
                    depth++;
                } else if (s.equals(")") || s.equals("]") || s.equals("}")) {
                    depth--;
                    if (depth == 0) {
                        close = j;
                        break;
                    }
                } else if (depth == 1 && s.equals(";") && isFor) {
                    semicolons.add(j);
                }
            }
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
            for (int j = start; j + 2 < end; j++) {
                Token lt = tokens.get(j);
                if (!prevText(sf.original, tokens, j).equals("<")
                        || !sf.original.startsWith("<=", lt.start)) {
                    continue;
                }
                String expr = sf.original.substring(tokens.get(j + 2).start,
                        tokens.get(end - 1).end).trim();
                if (!expr.matches("^\\S+(\\.长度|\\.元素数\\(\\))$")) {
                    continue;
                }
                if (reportTrap(sf, lt, "CJK104",
                        "循环条件「… <= " + expr + "」会多走一轮，取下标时最后一次越界。",
                        "把「<=」改成「<」（0 起始取下标）；确实要包含末尾时写「<= " + expr + " - 1」。")) {
                    count++;
                }
                break;
            }
        }
        return count;
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
            if (prevText(sf.original, tokens, j).equals("[")) {
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
        if (idx < 2 || !prevText(sf.original, tokens, idx - 1).equals(")")) {
            return false;
        }
        int depth = 1;
        for (int j = idx - 2; j >= 0; j--) {
            String s = prevText(sf.original, tokens, j);
            if (s.equals(")")) {
                depth++;
            } else if (s.equals("(")) {
                depth--;
                if (depth == 0) {
                    for (int k = j + 1; k <= idx - 2; k++) {
                        String text = prevText(sf.original, tokens, k);
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
        int lineIndex = lineOf(sf.origLineStarts, token.start);
        int wrappedLine = lineIndex + 1;
        int line = wrappedLine - sf.lineOffset;  // 脚本模式：换算回正文行号
        int col = token.start - sf.origLineStarts[lineIndex];
        System.out.println();
        System.out.println("提示 " + code + "  第" + line + "行 第" + (col + 1) + "列");
        System.out.println("  " + problem);
        System.out.println("  建议：" + suggestion);
        System.out.println("    " + String.format("%4d", line) + " | "
                + lineText(sf.original, sf.origLineStarts, wrappedLine));
        return true;
    }

    // ------------------------------------------------------------------ 翻译

    static String translate(SourceFile sf) {
        List<Token> tokens = sf.tokens;
        Set<String> declared = declaredNames(tokens, sf.original);
        // 名称前置声明：`计数器 类 {` / `计数器 接口 {` / `计数器 枚举 {`
        // 是「类名在前、关键字在后」的写法，翻译时还原成标准的「关键字 类名」。
        // 仅当关键字前是标识符、后接 `{` 或 `继承`/`实现` 时才判定，避免误伤普通标识符。
        Token[] swap = new Token[tokens.size()];
        boolean[] skip = new boolean[tokens.size()];
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token k = tokens.get(i);
            if (k.kind != KIND_KEYWORD) {
                continue;
            }
            String english = KEYWORDS.get(k.text);
            if (english == null || !(english.equals("class") || english.equals("interface") || english.equals("enum"))) {
                continue;
            }
            Token prev = tokens.get(i - 1);
            if (prev.kind != KIND_IDENTIFIER) {
                continue;
            }
            // 名称与关键字必须写在同一行，否则吞掉换行会破坏诊断行号映射
            String gap = sf.original.substring(prev.end, k.start);
            if (gap.indexOf('\n') >= 0 || gap.indexOf('\r') >= 0) {
                continue;
            }
            Token next = tokens.get(i + 1);
            String nextText = next.text != null ? next.text : sf.original.substring(next.start, next.end);
            boolean declaration = nextText.equals("{");
            if (!declaration && next.kind == KIND_KEYWORD) {
                String nextEnglish = KEYWORDS.get(next.text);
                declaration = nextEnglish != null && (nextEnglish.equals("extends") || nextEnglish.equals("implements"));
            }
            if (!declaration) {
                continue;
            }
            swap[i - 1] = k;
            skip[i] = true;
        }

        // 省略返回类型：`公有 加一() {` 等价于 `公有 无 加一() {`，翻译时补上 `void`。
        // 只在修饰符之后、名称后紧跟 `(`、且名称不等于所在类型名（构造器）时补，
        // 避免误伤方法调用、字段声明与构造器。
        // 另：无修饰符的接口方法 `加一();` 也补 `void` —— 方法调用只可能出现在方法体内，
        // 直接位于类型体成员层的 `名称(` 必然是声明。
        String[] typeAt = new String[tokens.size()];
        String[] kindAt = new String[tokens.size()];
        boolean[] inTypeBody = new boolean[tokens.size()];
        List<String> ownerStack = new ArrayList<>();
        List<String> kindStack = new ArrayList<>();
        List<Boolean> bodyStack = new ArrayList<>();
        String pendingType = null;
        String pendingKind = null;
        String currentType = null;
        String currentKind = null;
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            String text = t.text != null ? t.text : sf.original.substring(t.start, t.end);
            if (swap[idx] != null) {
                // 名称前置声明：这个标识符就是类型名
                pendingType = t.text;
                pendingKind = KEYWORDS.get(swap[idx].text);
            } else if (t.kind == KIND_KEYWORD) {
                String english = KEYWORDS.get(t.text);
                if (english != null && (english.equals("class") || english.equals("interface") || english.equals("enum"))) {
                    pendingKind = english;
                    if (idx + 1 < tokens.size() && tokens.get(idx + 1).kind == KIND_IDENTIFIER) {
                        pendingType = tokens.get(idx + 1).text;
                    }
                }
            }
            typeAt[idx] = currentType;
            kindAt[idx] = currentKind;
            inTypeBody[idx] = !bodyStack.isEmpty() && bodyStack.get(bodyStack.size() - 1);
            if (text.equals("{")) {
                boolean typeBody = pendingType != null;
                String owner = typeBody ? pendingType : currentType;
                String kind = typeBody ? pendingKind : currentKind;
                ownerStack.add(owner);
                kindStack.add(kind);
                bodyStack.add(typeBody);
                currentType = owner;
                currentKind = kind;
                pendingType = null;
                pendingKind = null;
            } else if (text.equals("}")) {
                if (!ownerStack.isEmpty()) {
                    ownerStack.remove(ownerStack.size() - 1);
                    kindStack.remove(kindStack.size() - 1);
                    bodyStack.remove(bodyStack.size() - 1);
                }
                currentType = ownerStack.isEmpty() ? null : ownerStack.get(ownerStack.size() - 1);
                currentKind = kindStack.isEmpty() ? null : kindStack.get(kindStack.size() - 1);
            }
        }
        boolean[] insertVoid = new boolean[tokens.size()];
        for (int idx = 1; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            Token prev = tokens.get(idx - 1);
            if (t.kind != KIND_IDENTIFIER) {
                continue;
            }
            Token next = tokens.get(idx + 1);
            String nextText = next.text != null ? next.text : sf.original.substring(next.start, next.end);
            if (!nextText.equals("(")) {
                continue;
            }
            boolean declaration = false;
            if (prev.kind == KIND_KEYWORD) {
                String prevEnglish = KEYWORDS.get(prev.text);
                declaration = prevEnglish != null && isModifier(prevEnglish);
            }
            if (!declaration && inTypeBody[idx]) {
                String prevText = prev.text != null ? prev.text : sf.original.substring(prev.start, prev.end);
                declaration = prevText.equals("{") || prevText.equals("}") || prevText.equals(";");
                // 枚举体以常量开头，`红();` 是枚举常量而不是方法
                if (declaration && "enum".equals(kindAt[idx]) && prevText.equals("{")) {
                    declaration = false;
                }
            }
            if (!declaration) {
                continue;
            }
            if (t.text.equals(typeAt[idx])) {
                continue; // 构造器：名称与所在类型同名
            }
            insertVoid[idx] = true;
        }

        // 简化打印：语句开头的 `println(...)` / `打印行(...)` 自动补成 `System.out.println(...)`，
        // `打印(...)` 自动补成 `System.out.print(...)`。
        // 只认 `{`、`}`、`;`、`->` 之后的位置，带 `.` 前缀的调用与自定义同名方法不会被改写。
        boolean[] insertOut = new boolean[tokens.size()];
        boolean[] insertPrint = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            boolean isLine = "println".equals(t.text) || "打印行".equals(t.text);
            boolean isPrint = "打印".equals(t.text);
            if (t.kind != KIND_IDENTIFIER || (!isLine && !isPrint)) {
                continue;
            }
            Token next = tokens.get(idx + 1);
            String nextText = next.text != null ? next.text : sf.original.substring(next.start, next.end);
            if (!nextText.equals("(")) {
                continue;
            }
            String prevText = "";
            boolean afterArrow = false;
            if (idx > 0) {
                Token prev = tokens.get(idx - 1);
                prevText = prev.text != null ? prev.text : sf.original.substring(prev.start, prev.end);
                if (idx > 1 && ">".equals(prevText)) {
                    Token prev2 = tokens.get(idx - 2);
                    String prev2Text = prev2.text != null ? prev2.text
                            : sf.original.substring(prev2.start, prev2.end);
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

        // A5：类型推断 `令 名称 = …` → `var 名称 = …`、`常量 名称 = …` → `final var 名称 = …`。
        // 仅在语句开头、后接「名称 =」时识别；开关关闭时完全不动，`令` / `常量` 按普通标识符处理。
        boolean[] inferVar = new boolean[tokens.size()];
        boolean[] inferConst = new boolean[tokens.size()];
        if (allowInference) {
            for (int idx = 0; idx + 2 < tokens.size(); idx++) {
                Token t = tokens.get(idx);
                if (t.kind != KIND_IDENTIFIER) {
                    continue;
                }
                boolean isVar = "令".equals(t.text);
                boolean isConst = "常量".equals(t.text);
                if (!isVar && !isConst) {
                    continue;
                }
                if (idx > 0) {
                    Token prev = tokens.get(idx - 1);
                    String prevText = prev.text != null ? prev.text : sf.original.substring(prev.start, prev.end);
                    if (!(prevText.equals("{") || prevText.equals("}") || prevText.equals(";")
                            || prevText.equals("(") || prevText.equals(":"))) {
                        continue;
                    }
                }
                if (tokens.get(idx + 1).kind != KIND_IDENTIFIER) {
                    continue;
                }
                Token eq = tokens.get(idx + 2);
                String eqText = eq.text != null ? eq.text : sf.original.substring(eq.start, eq.end);
                if (!eqText.equals("=")) {
                    continue;
                }
                if (isVar) {
                    inferVar[idx] = true;
                } else {
                    inferConst[idx] = true;
                }
            }
        }

        // 增强 for 省略类型：`对于 (名 : 名单)` → `for (var 名 : 名单)`。
        // 只认 `for (` 之后是「单个标识符 + 冒号」的形式：带类型的写法首 token 是类型别名
        // （按关键字处理），传统三段式 for 的标识符后是 `=`，都不会命中。
        boolean[] insertForVar = new boolean[tokens.size()];
        for (int idx = 0; idx + 3 < tokens.size(); idx++) {
            Token f = tokens.get(idx);
            if (f.kind != KIND_KEYWORD || !"for".equals(KEYWORDS.get(f.text))) {
                continue;
            }
            if (!prevText(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            if (tokens.get(idx + 2).kind != KIND_IDENTIFIER) {
                continue;
            }
            if (prevText(sf.original, tokens, idx + 3).equals(":")) {
                insertForVar[idx + 2] = true;
            }
        }

        // 计数循环糖：`重复 3 次 { … }` → `for (int 序号 = 1; 序号 <= 3; 序号++) { … }`。
        // 默认循环变量名 `序号`；写成 `重复 3 次 (名) {` 可自选变量名，避免与同名变量冲突。
        // 只认语句开头的 `重复`，且「次数」后必须紧跟 `{`；次数表达式原样交给 translateFragment 转译。
        boolean[] repeatStart = new boolean[tokens.size()];
        int[] repeatEnd = new int[tokens.size()];
        String[] repeatHeader = new String[tokens.size()];
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"重复".equals(t.text)) {
                continue;
            }
            if (idx > 0) {
                String prev = prevText(sf.original, tokens, idx - 1);
                if (!(prev.equals("{") || prev.equals("}") || prev.equals(";") || prev.equals(":"))) {
                    continue;
                }
            }
            // 找同层的 `次`：括号内的 `次` 不算（次数表达式里可能带括号）
            int depth = 0;
            int ci = -1;
            for (int j = idx + 1; j < tokens.size(); j++) {
                String s = prevText(sf.original, tokens, j);
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
                    ci = j;
                    break;
                }
            }
            if (ci < 0 || ci == idx + 1) {
                continue;
            }
            String expression = sf.original.substring(tokens.get(idx + 1).start, tokens.get(ci).start).trim();
            if (expression.isEmpty()) {
                continue;
            }
            String name = "序号";
            int headerEnd = ci;
            if (ci + 1 < tokens.size() && prevText(sf.original, tokens, ci + 1).equals("(")
                    && ci + 3 < tokens.size() && tokens.get(ci + 2).kind == KIND_IDENTIFIER
                    && prevText(sf.original, tokens, ci + 3).equals(")")) {
                name = tokens.get(ci + 2).text;
                headerEnd = ci + 3;
            }
            if (headerEnd + 1 >= tokens.size()
                    || !prevText(sf.original, tokens, headerEnd + 1).equals("{")) {
                continue;
            }
            repeatStart[idx] = true;
            repeatEnd[idx] = headerEnd;
            repeatHeader[idx] = "for (int " + name + " = 1; " + name + " <= "
                    + translateFragment(expression, declared) + "; " + name + "++)";
        }

        // 中文属性 `长度`：`数组.长度` → `数组.length`（方法调用 `字符串.长度()` 同样适用）。
        // 只认 `.` 之后的位置，名为 `长度` 的变量与字段声明不受影响。
        boolean[] renameLength = new boolean[tokens.size()];
        for (int idx = 1; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"长度".equals(t.text)) {
                continue;
            }
            Token prev = tokens.get(idx - 1);
            String prevText = prev.text != null ? prev.text : sf.original.substring(prev.start, prev.end);
            if (prevText.equals(".")) {
                renameLength[idx] = true;
            }
        }

        // 语法糖 `否则若 (`：`否则` + `若` 粘连成一个标识符，这里整体还原成 `else if`。
        boolean[] renameElseIf = new boolean[tokens.size()];
        for (int idx = 0; idx < tokens.size(); idx++) {
            if (isElseIfSugar(sf, idx)) {
                renameElseIf[idx] = true;
            }
        }

        // 中文类名 `系统`：`系统.数组复制(...)` → `System.arraycopy(...)`。
        // 只认后接 `.` 的位置，名为 `系统` 的变量不受影响。
        boolean[] renameSystem = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"系统".equals(t.text)) {
                continue;
            }
            Token next = tokens.get(idx + 1);
            String nextText = next.text != null ? next.text : sf.original.substring(next.start, next.end);
            if (nextText.equals(".")) {
                renameSystem[idx] = true;
            }
        }

        // 系统方法 `数组复制`：`系统.数组复制(...)` → `System.arraycopy(...)`。
        boolean[] renameArraycopy = new boolean[tokens.size()];
        for (int idx = 2; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !"数组复制".equals(t.text)) {
                continue;
            }
            String prev1Text = prevText(sf.original, tokens, idx - 1);
            Token prev2 = tokens.get(idx - 2);
            String prev2Text = prev2.text != null ? prev2.text : sf.original.substring(prev2.start, prev2.end);
            if (prev1Text.equals(".") && "系统".equals(prev2Text)) {
                renameArraycopy[idx] = true;
            }
        }

        // 中文静态调用：`算术.平方根(…)` → `Math.sqrt(…)`；类名与方法名一起登记，跳过 `.` 与方法名。
        String[] staticCall = new String[tokens.size()];
        for (int idx = 0; idx + 2 < tokens.size(); idx++) {
            Token a = tokens.get(idx);
            String aText = a.text != null ? a.text : sf.original.substring(a.start, a.end);
            String dot = prevText(sf.original, tokens, idx + 1);
            String bText = prevText(sf.original, tokens, idx + 2);
            String replacement = dot.equals(".") ? STATIC_CALLS.get(aText + "." + bText) : null;
            if (replacement != null) {
                staticCall[idx] = replacement;
                skip[idx + 1] = true;
                skip[idx + 2] = true;
            }
        }

        // 中文成员方法：`名称.加入(…)` → `名称.add(…)`，只认 `.` 之后且后接 `(`。
        // `.` 之后是成员名上下文，即使该词同时是关键字近义词（如 `遍历` 是 for 的近义词）
        // 也按成员糖处理：Java 不允许用关键字作方法名，这样写唯一合理的解释就是成员糖。
        boolean[] callMember = new boolean[tokens.size()];
        for (int idx = 1; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if ((t.kind != KIND_IDENTIFIER && t.kind != KIND_KEYWORD) || !MEMBER_CALLS.containsKey(t.text)) {
                continue;
            }
            if (!prevText(sf.original, tokens, idx - 1).equals(".")) {
                continue;
            }
            if (!prevText(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            // 中文标准库类名上的调用保持原样：`文本.格式化` / `并发.关闭` / `集合工具.排序`
            // 是这些类自己的方法，若按方法名全局改写会变成不存在的方法名。
            // 但同名变量优先：文件里声明过 `文本` 变量时，`文本.修剪()` 仍要转译。
            if (isStdlibReceiver(tokens, sf.original, idx - 2, declared)) {
                continue;
            }
            callMember[idx] = true;
        }

        // 中文重写方法声明：不带 `.` 前缀、后接 `(` 的 `转文本` / `等于` / `哈希码`。
        // 带 `.` 前缀的是调用（由 callMember 处理），这里只负责声明侧。
        boolean[] declMember = new boolean[tokens.size()];
        for (int idx = 0; idx + 1 < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (t.kind != KIND_IDENTIFIER || !MEMBER_DECLS.containsKey(t.text)) {
                continue;
            }
            if (!prevText(sf.original, tokens, idx + 1).equals("(")) {
                continue;
            }
            if (idx > 0 && prevText(sf.original, tokens, idx - 1).equals(".")) {
                continue;
            }
            declMember[idx] = true;
        }

        StringBuilder sb = new StringBuilder(sf.original.length() * 2);
        List<int[]> pending = new ArrayList<>(); // {origStart, origLen, genStart, genLen}
        int cursor = 0;
        for (int idx = 0; idx < tokens.size(); idx++) {
            Token t = tokens.get(idx);
            if (skip[idx]) {
                // 关键字已在前面的名称处输出，连同它与名称之间的空白一起跳过。
                // 用 max 避免把 cursor 往回退（区域整体替换时 cursor 已越过后续 token）。
                cursor = Math.max(cursor, t.end);
                continue;
            }
            if (t.start > cursor) {
                sb.append(sf.original, cursor, t.start);
            }
            if (repeatStart[idx]) {
                // 计数循环：整段头部换成一个 for 语句，次数表达式已单独转译
                int genStart = sb.length();
                sb.append(repeatHeader[idx]);
                pending.add(new int[]{t.start, tokens.get(repeatEnd[idx]).end - t.start,
                        genStart, sb.length() - genStart});
                cursor = tokens.get(repeatEnd[idx]).end;
                for (int k = idx + 1; k <= repeatEnd[idx]; k++) {
                    skip[k] = true;
                }
                continue;
            }
            if (staticCall[idx] != null) {
                int genStart = sb.length();
                sb.append(staticCall[idx]);
                pending.add(new int[]{t.start, t.end - t.start, genStart, sb.length() - genStart});
                cursor = t.end;
                continue;
            }
            if (callMember[idx]) {
                String english = MEMBER_CALLS.get(t.text);
                int genStart = sb.length();
                sb.append(english);
                pending.add(new int[]{t.start, t.text.length(), genStart, english.length()});
                cursor = t.end;
                continue;
            }
            if (declMember[idx]) {
                String english = MEMBER_DECLS.get(t.text);
                int genStart = sb.length();
                sb.append(english);
                pending.add(new int[]{t.start, t.text.length(), genStart, english.length()});
                cursor = t.end;
                continue;
            }
            if (swap[idx] != null) {
                Token k = swap[idx];
                String english = KEYWORDS.get(k.text);
                int genStart = sb.length();
                sb.append(english).append(' ');
                sb.append(sf.original, t.start, t.end);
                int genLen = sb.length() - genStart;
                pending.add(new int[]{t.start, k.end - t.start, genStart, genLen});
                cursor = k.end;
                continue;
            }
            if (t.kind == KIND_IDENTIFIER && "入口".equals(t.text) && idx + 1 < tokens.size()) {
                Token next = tokens.get(idx + 1);
                String nextText = next.text != null ? next.text : sf.original.substring(next.start, next.end);
                if (nextText.equals("{")) {
                    // 入口 { … } 语法糖：直接展开成 main 方法声明
                    int genStart = sb.length();
                    sb.append("public static void main(String[] 参数)");
                    pending.add(new int[]{t.start, t.text.length(), genStart, sb.length() - genStart});
                    cursor = t.end;
                    continue;
                }
            }
            if (inferVar[idx] || inferConst[idx]) {
                // 类型推断：`令` → `var`、`常量` → `final var`，名称与初始化原样保留
                int genStart = sb.length();
                sb.append(inferConst[idx] ? "final var" : "var");
                pending.add(new int[]{t.start, t.text.length(), genStart, sb.length() - genStart});
                cursor = t.end;
                continue;
            }
            if (renameArraycopy[idx]) {
                // `数组复制` → `arraycopy`
                int genStart = sb.length();
                sb.append("arraycopy");
                pending.add(new int[]{t.start, t.text.length(), genStart, 9});
                cursor = t.end;
                continue;
            }
            if (renameSystem[idx]) {
                // `系统` → `System`
                int genStart = sb.length();
                sb.append("System");
                pending.add(new int[]{t.start, t.text.length(), genStart, 6});
                cursor = t.end;
                continue;
            }
            if (renameLength[idx]) {
                // `长度` → `length`
                int genStart = sb.length();
                sb.append("length");
                pending.add(new int[]{t.start, t.text.length(), genStart, 6});
                cursor = t.end;
                continue;
            }
            if (renameElseIf[idx]) {
                // 语法糖 `否则若` → `else if`
                int genStart = sb.length();
                sb.append("else if");
                pending.add(new int[]{t.start, t.text.length(), genStart, 7});
                cursor = t.end;
                continue;
            }
            if (insertOut[idx]) {
                // 补 `System.out.` 前缀；原长度按 0 登记，列号仍能回映射
                int genStart = sb.length();
                if ("打印行".equals(t.text)) {
                    // 中文糖：把 `打印行` 整体换成 `System.out.println`
                    sb.append("System.out.println");
                    pending.add(new int[]{t.start, t.text.length(), genStart, 18});
                    cursor = t.end;
                    continue;
                }
                sb.append("System.out.");
                pending.add(new int[]{t.start, 0, genStart, 11});
            }
            if (insertPrint[idx]) {
                // 中文糖：把 `打印` 整体换成 `System.out.print`
                int genStart = sb.length();
                sb.append("System.out.print");
                pending.add(new int[]{t.start, t.text.length(), genStart, 16});
                cursor = t.end;
                continue;
            }
            if (insertVoid[idx]) {
                // 省略了返回类型，补一个 `void `；原长度按 0 登记，列号仍能回映射
                int genStart = sb.length();
                sb.append("void ");
                pending.add(new int[]{t.start, 0, genStart, 5});
            }
            if (insertForVar[idx]) {
                // 增强 for 省略了类型，补一个 `var `；原长度按 0 登记
                int genStart = sb.length();
                sb.append("var ");
                pending.add(new int[]{t.start, 0, genStart, 4});
            }
            if (t.kind == KIND_KEYWORD) {
                String english = KEYWORDS.get(t.text);
                int genStart = sb.length();
                sb.append(english);
                int pad = t.text.length() - english.length();
                if (pad > 0) {
                    sb.append(" ".repeat(pad));
                }
                pending.add(new int[]{t.start, t.text.length(), genStart, Math.max(t.text.length(), english.length())});
            } else if (t.kind == KIND_PUNCT) {
                // 标点规范化是 1 字符换 1 字符，不影响列号
                sb.append(t.text);
            } else if (t.kind == KIND_OTHER && t.text == null
                    && sf.original.charAt(t.start) == '"') {
                // 字符串插值："{名字}" → "" + 名字
                String raw = sf.original.substring(t.start, t.end);
                int genStart = sb.length();
                if (appendInterpolated(sb, raw, declared)) {
                    pending.add(new int[]{t.start, raw.length(), genStart, sb.length() - genStart});
                } else {
                    sb.append(raw);
                }
            } else {
                sb.append(sf.original, t.start, t.end);
            }
            cursor = t.end;
        }
        if (cursor < sf.original.length()) {
            sb.append(sf.original, cursor, sf.original.length());
        }
        String translated = sb.toString();

        int[] genLineStarts = lineStarts(translated);
        for (int[] r : pending) {
            int genLine = lineOf(genLineStarts, r[2]);
            int genCol = r[2] - genLineStarts[genLine];
            int origLine = lineOf(sf.origLineStarts, r[0]);
            int origCol = r[0] - sf.origLineStarts[origLine];
            sf.replacements.add(new Replacement(genLine, genCol, origCol, r[1], r[3]));
        }
        return translated;
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
        String content = literal.substring(1, literal.length() - 1);
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
                    return false;
                }
                String expr = content.substring(i + 1, end).trim();
                if (!isInterpolationExpression(expr)) {
                    return false;
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
        if (!expanded) {
            if (!escaped) {
                return false;
            }
            // 只有转义：{{ → {、}} → }，不产生拼接
            sb.append('"').append(current).append('"');
            return true;
        }
        parts.add("L" + current);
        boolean first = true;
        for (String part : parts) {
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
        return true;
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
            String aText = t.text != null ? t.text : expr.substring(t.start, t.end);
            String replacement = null;
            int consumeTo = i;
            if (i + 2 < tokens.size() && prevText(expr, tokens, i + 1).equals(".")) {
                String key = aText + "." + prevText(expr, tokens, i + 2);
                if (STATIC_CALLS.containsKey(key)) {
                    replacement = STATIC_CALLS.get(key);
                    consumeTo = i + 2;
                }
            }
            if (replacement != null) {
                sb.append(replacement);
                cursor = tokens.get(consumeTo).end;
                i = consumeTo;
                continue;
            }
            if ((t.kind == KIND_IDENTIFIER || t.kind == KIND_KEYWORD) && MEMBER_CALLS.containsKey(t.text)
                    && i > 0 && prevText(expr, tokens, i - 1).equals(".")
                    && i + 1 < tokens.size() && prevText(expr, tokens, i + 1).equals("(")
                    && !isStdlibReceiver(tokens, expr, i - 2, declared)) {
                sb.append(MEMBER_CALLS.get(t.text));
            } else if (t.kind == KIND_KEYWORD) {
                String english = KEYWORDS.get(t.text);
                sb.append(english);
                int pad = t.text.length() - english.length();
                if (pad > 0) {
                    sb.append(" ".repeat(pad));
                }
            } else if (t.kind == KIND_IDENTIFIER && "长度".equals(t.text) && i > 0
                    && ".".equals(prevText(expr, tokens, i - 1))) {
                // 插值表达式里的 `xxx.长度` 也要转成 `xxx.length`
                sb.append("length");
            } else if (t.kind == KIND_IDENTIFIER && "数组复制".equals(t.text) && i > 0
                    && ".".equals(prevText(expr, tokens, i - 1))) {
                sb.append("arraycopy");
            } else if (t.kind == KIND_IDENTIFIER && "系统".equals(t.text) && i + 1 < tokens.size()
                    && ".".equals(prevText(expr, tokens, i + 1))) {
                // 插值表达式里的 `系统.xxx` 也要转成 `System.xxx`
                sb.append("System");
            } else {
                sb.append(expr, t.start, t.end);
            }
            cursor = t.end;
        }
        if (cursor < expr.length()) {
            sb.append(expr, cursor, expr.length());
        }
        return sb.toString();
    }

    /** 取 token 的文本；标点等 token 的 text 为 null，需回退到源串切片。 */
    static String prevText(String source, List<Token> tokens, int idx) {
        Token t = tokens.get(idx);
        return t.text != null ? t.text : source.substring(t.start, t.end);
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

    /**
     * javac 自带的报错文本是英文（JDK 没有中文语言包），这里把常见错误翻成中文。
     * 命中返回 {中文消息, 建议}；未命中返回 null，保留 javac 原文，避免 JDK 升级后误报。
     */
    static String[] localizeJavacMessage(String message) {
        String raw = message == null ? "" : message.replace("\r", "");
        String first = raw.split("\n", 2)[0].trim();

        if (first.startsWith("cannot find symbol")) {
            String symbol = captureAfter(raw, "symbol:");
            if (symbol == null) {
                return rule("找不到符号", "检查拼写；确认变量已声明、类型已导入。");
            }
            String[] parts = symbol.split("\\s+", 2);
            String kind = parts[0];
            String name = parts.length > 1 ? parts[1] : "";
            return symbolRule(kind, name);
        }
        Matcher m = findPattern("possible lossy conversion from (\\S+) to (\\S+)", first);
        if (m != null) {
            return rule("类型不匹配：从「" + chineseType(m.group(1)) + "」转「" + chineseType(m.group(2))
                    + "」会丢失精度", "需要时写强制转换，例如「(整数) 值」。");
        }
        if (first.startsWith("incompatible types:")) {
            m = findPattern("(.+) cannot be converted to (.+)", first);
            if (m != null) {
                String from = m.group(1).replaceFirst("incompatible types: ", "");
                return rule("类型不匹配：「" + chineseType(from) + "」不能赋给「" + chineseType(m.group(2)) + "」",
                        "检查变量与参数的类型，必要时显式转换。");
            }
            return rule("类型不匹配：两侧类型不一致", "检查变量与参数的类型。");
        }
        if (first.startsWith("class, interface") && first.endsWith("expected")) {
            return rule("类外出现了多余的代码", "检查是否多写了一个「}」，或把语句写在了类的外面。");
        }
        if (first.startsWith("reached end of file while parsing")) {
            return rule("文件在解析中途结束", "检查花括号与圆括号是否成对。");
        }
        if (first.startsWith("unclosed string literal")) {
            return rule("字符串没有闭合", "检查这一行的引号是否成对。");
        }
        if (first.startsWith("unclosed comment")) {
            return rule("注释没有闭合", "检查「/*」是否有对应的「*/」。");
        }
        if (first.startsWith("illegal character")) {
            String ch = captureAfter(first, "illegal character:");
            String tail = ch == null ? "" : "「" + ch.replace("'", "") + "」";
            return rule("出现了非法字符" + tail, "中文标点请改成半角，例如「，」改「,」、「（」改「(」。");
        }
        if (first.startsWith("illegal start of expression")) {
            return rule("表达式开头不合法", "最常见原因是上一行漏了分号，或用了无法映射的全角标点。");
        }
        if (first.startsWith("not a statement")) {
            return rule("这不是一条合法语句", "检查这一行是不是赋值或方法调用。");
        }
        if (first.startsWith("missing return statement")) {
            return rule("方法缺少 return", "所有分支都要返回值；不返回值时把返回类型写成「无」。");
        }
        m = findPattern("unreported exception (.+?); must be caught or declared", first);
        if (m != null) {
            return rule("可能抛出「" + chineseType(m.group(1)) + "」",
                    "把这段代码放进「尝试 { … } 捕获 (… 出错) { … }」，或在方法签名后写「抛出」声明。");
        }
        if (first.startsWith("unreachable statement")) {
            return rule("这行代码永远不会执行", "检查它前面是否有「返回」「抛出」「跳出」或「继续」。");
        }
        if (first.startsWith("identifier expected")) {
            return rule("这里需要一个名称", "检查声明里是否漏了变量名或方法名。");
        }
        if (first.contains("unchecked conversion") || first.contains("[unchecked]")) {
            return rule("未检查的泛型转换（可忽略此警告）", null);
        }
        m = findPattern("variable (\\S+) might not have been initialized", first);
        if (m != null) {
            return rule("变量「" + m.group(1) + "」可能未初始化", "声明时给它一个初始值。");
        }
        m = findPattern("non-static variable (\\S+) cannot be referenced from a static context", first);
        if (m != null) {
            return rule("静态方法里不能直接使用实例字段「" + m.group(1) + "」",
                    "先「新建」对象再访问，或把字段改成「静态」。");
        }
        m = findPattern("non-static method (\\S+) cannot be referenced from a static context", first);
        if (m != null) {
            return rule("静态方法里不能直接调用实例方法「" + m.group(1) + "」",
                    "先创建对象再调用，或把方法改成「静态」。");
        }
        m = findPattern("(?:variable|method|class|constructor) (.+?) is already defined", first);
        if (m != null) {
            return rule("「" + m.group(1) + "」重复定义", "换个名字，或删掉重复的声明。");
        }
        m = findPattern("(\\S+) has private access in", first);
        if (m != null) {
            return rule("「" + m.group(1) + "」是私有的，外部不能访问", "改用公有方法访问。");
        }
        m = findPattern("cannot assign a value to final variable (\\S+)", first);
        if (m != null) {
            return rule("不能给最终变量「" + m.group(1) + "」重新赋值", "去掉「最终」，或改在声明时赋值。");
        }
        if (first.startsWith("cannot return a value from method whose result type is void")) {
            return rule("返回类型是「无」的方法不能返回值", "去掉 return 后面的值，或把返回类型改成实际类型。");
        }
        if (first.startsWith("invalid method declaration; return type required")) {
            return rule("方法声明缺少返回类型", "在方法名前写返回类型，或用「无」；构造器名必须与类名完全一致。");
        }
        m = findPattern("class (\\S+) is public, should be declared in a file named", first);
        if (m != null) {
            return rule("公有类「" + m.group(1) + "」必须写在同名文件里",
                    "文件名要与类名完全一致（含大小写），扩展名 .java。");
        }
        m = findPattern("array required, but (\\S+) found", first);
        if (m != null) {
            return rule("「" + m.group(1) + "」不是数组，不能用「[下标]」取值",
                    "确认它声明成了数组，例如「整数[] 分数」。");
        }
        m = findPattern("package (\\S+) does not exist", first);
        if (m != null) {
            return rule("包「" + m.group(1) + "」不存在", "检查包名拼写与目录结构；Maven 工程需要先加依赖。");
        }
        m = findPattern("class file for (\\S+) not found", first);
        if (m != null) {
            return rule("找不到类文件「" + m.group(1) + "」", "检查 -cp 类路径或依赖是否齐全。");
        }
        m = findPattern("no suitable constructor found for (.+)", first);
        if (m != null) {
            return rule("没有匹配的构造器：" + m.group(1), "检查「新建」后面的参数个数与类型。");
        }
        if (first.contains("cannot be applied to given types") || first.startsWith("no suitable method found")) {
            return rule("方法调用的参数不匹配", "检查参数个数、顺序与类型；构造器参数也要一一对应。");
        }

        m = findPattern("bad operand types for binary operator '(.+)'", first);
        if (m != null) {
            return rule("运算符「" + m.group(1) + "」不能用于这些类型", "检查两侧类型；拼接文本前先转成字符串。");
        }
        if (first.contains("does not override or implement a method from a supertype")) {
            return rule("「覆写」标注的方法没有真正覆盖父类方法", "方法名与参数列表必须与父类完全一致。");
        }
        m = findPattern("class (.+) is not abstract and does not override abstract method (.+)", first);
        if (m != null) {
            return rule("类「" + m.group(1) + "」还有未实现的抽象方法「" + m.group(2) + "」",
                    "实现它，或把类声明成「抽象」。");
        }
        if (first.startsWith("duplicate class")) {
            return rule("类重复定义", "同一个包内类名不能重复。");
        }
        m = findPattern("^'(.+)' expected$", first);
        if (m != null) {
            return missingToken(m.group(1));
        }

        // ---- 中文消息（部分 JDK 自带中文语言包；中英文两套模式都兼容）
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
        m = findPattern("从(\\S+)转换到(\\S+)可能会有损失", first);
        if (m != null) {
            return rule("类型不匹配：从「" + chineseType(m.group(1)) + "」转「" + chineseType(m.group(2))
                    + "」会丢失精度", "需要时写强制转换，例如「(整数) 值」。");
        }
        if (first.startsWith("不兼容的类型")) {
            m = findPattern("不兼容的类型[:：]\\s*(.+?)无法转换为(.+)", first);
            if (m != null) {
                return rule("类型不匹配：「" + chineseType(m.group(1)) + "」不能赋给「" + chineseType(m.group(2)) + "」",
                        "检查变量与参数的类型，必要时显式转换。");
            }
            return rule("类型不匹配：两侧类型不一致", "检查变量与参数的类型。");
        }
        m = findPattern("需要'(.+)'", first);
        if (m != null) {
            return missingToken(m.group(1));
        }
        m = findPattern("需要数组.*?但找到(.+)", first);
        if (m != null) {
            return rule("「" + chineseType(m.group(1)) + "」不是数组，不能用「[下标]」取值",
                    "确认它声明成了数组，例如「整数[] 分数」。");
        }
        m = findPattern("类 (\\S+) 是公共的", first);
        if (m != null) {
            return rule("公有类「" + m.group(1) + "」必须写在同名文件里",
                    "文件名要与类名完全一致（含大小写），扩展名 .java。");
        }
        m = findPattern("无法为 final 变量 (\\S+) 分配值", first);
        if (m != null) {
            return rule("不能给最终变量「" + m.group(1) + "」重新赋值", "去掉「最终」，或改在声明时赋值。");
        }
        m = findPattern("可能尚未初始化变量(.+)", first);
        if (m != null) {
            return rule("变量「" + m.group(1) + "」可能未初始化", "声明时给它一个初始值。");
        }
        m = findPattern("(\\S+) 在 .+ 中是 private 访问控制", first);
        if (m != null) {
            return rule("「" + m.group(1) + "」是私有的，外部不能访问", "改用公有方法访问。");
        }
        if (first.startsWith("缺少返回语句")) {
            return rule("方法缺少 return", "所有分支都要返回值；不返回值时把返回类型写成「无」。");
        }
        m = findPattern("已在.+?中定义了(?:变量|方法|类|构造器) (.+)", first);
        if (m != null) {
            return rule("「" + m.group(1) + "」重复定义", "换个名字，或删掉重复的声明。");
        }
        m = findPattern("无法从静态上下文中引用非静态 (变量|方法) (.+)", first);
        if (m != null) {
            return "变量".equals(m.group(1))
                    ? rule("静态方法里不能直接使用实例字段「" + m.group(2) + "」",
                    "先「新建」对象再访问，或把字段改成「静态」。")
                    : rule("静态方法里不能直接调用实例方法「" + m.group(2) + "」",
                    "先创建对象再调用，或把方法改成「静态」。");
        }
        if (first.startsWith("不可达的语句")) {
            return rule("这行代码永远不会执行", "检查它前面是否有「返回」「抛出」「跳出」或「继续」。");
        }
        if (first.startsWith("不是语句")) {
            return rule("这不是一条合法语句", "检查这一行是不是赋值或方法调用。");
        }
        if (first.startsWith("非法表达式开始")) {
            return rule("表达式开头不合法", "最常见原因是上一行漏了分号，或用了无法映射的全角标点。");
        }
        if (first.startsWith("非法的字符") || first.startsWith("非法字符")) {
            String ch = captureAfter(first, "字符:");
            String tail = ch == null ? "" : "「" + ch.replace("'", "") + "」";
            return rule("出现了非法字符" + tail, "中文标点请改成半角，例如「，」改「,」、「（」改「(」。");
        }
        if (first.startsWith("方法声明无效")) {
            return rule("方法声明缺少返回类型", "在方法名前写返回类型，或用「无」；构造器名必须与类名完全一致。");
        }
        m = findPattern("未报告的异常错误(.+?)[;；]", first);
        if (m != null) {
            return rule("可能抛出「" + chineseType(m.group(1).trim()) + "」",
                    "把这段代码放进「尝试 { … } 捕获 (… 出错) { … }」，或在方法签名后写「抛出」声明。");
        }
        if (first.startsWith("void 方法不能返回值")) {
            return rule("返回类型是「无」的方法不能返回值", "去掉 return 后面的值，或把返回类型改成实际类型。");
        }
        m = findPattern("二元运算符 '(.+)' 的操作数类型错误", first);
        if (m != null) {
            return rule("运算符「" + m.group(1) + "」不能用于这些类型", "检查两侧类型；拼接文本前先转成字符串。");
        }
        if (first.startsWith("类, 接口") || first.startsWith("类，接口")) {
            return rule("类外出现了多余的代码", "检查是否多写了一个「}」，或把语句写在了类的外面。");
        }
        if (first.startsWith("在解析时已到达文件结尾") || first.startsWith("解析时已到达文件结尾")) {
            return rule("文件在解析中途结束", "检查花括号与圆括号是否成对。");
        }
        if (first.startsWith("未结束的字符串字面值")) {
            return rule("字符串没有闭合", "检查这一行的引号是否成对。");
        }
        if (first.startsWith("未结束的注释")) {
            return rule("注释没有闭合", "检查「/*」是否有对应的「*/」。");
        }
        m = findPattern("程序包(.+)不存在", first);
        if (m != null) {
            return rule("包「" + m.group(1) + "」不存在", "检查包名拼写与目录结构；Maven 工程需要先加依赖。");
        }
        if (first.contains("无法应用于给定类型") || first.contains("未找到适合的方法")
                || first.contains("找不到适合的方法")) {
            return rule("方法调用的参数不匹配", "检查参数个数、顺序与类型；构造器参数也要一一对应。");
        }
        m = findPattern("没有找到适合的构造器(.+)", first);
        if (m != null) {
            return rule("没有匹配的构造器：" + m.group(1).trim(), "检查「新建」后面的参数个数与类型。");
        }
        if (first.startsWith("重复的类")) {
            return rule("类重复定义", "同一个包内类名不能重复。");
        }
        m = findPattern("无法访问 (.+)", first);
        if (m != null) {
            return rule("无法访问「" + m.group(1) + "」", "检查访问修饰符（公有 / 私有 / 保护）。");
        }
        m = findPattern("找不到类文件 (.+)", first);
        if (m != null) {
            return rule("找不到类文件「" + m.group(1) + "」", "检查 -cp 类路径或依赖是否齐全。");
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
