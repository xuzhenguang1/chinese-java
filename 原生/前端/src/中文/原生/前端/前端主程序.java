package 中文.原生.前端;

import 中文.原生.前端.标记.标记类别;
import 中文.原生.前端.语法树.编译单元;
import 中文.原生.中间.中间代码;
import 中文.原生.中间.中间打印器;
import 中文.原生.中间.解释器;
import 中文.原生.中间.解释器异常;
import 中文.原生.中间.语义分析器;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 原生前端命令行：词表 / 检查 / 语法树 / 快照 / 中间 / 运行 / 编译。 */
public final class 前端主程序 {

    private record 分析结果(标记.源文件 文件, 诊断 诊断) {}

    private record 语义输入(标记.源文件 文件, 诊断 诊断, 编译单元 单元) {}

    private 前端主程序() {}

    public static void main(String[] 参数) {
        切换输出为UTF8();
        if (参数.length < 2) {
            显示用法();
            System.exit(2);
        }
        String 命令 = 参数[0];
        Path 根目录 = Paths.get(参数[1]).toAbsolutePath().normalize();
        try {
            词表 词表数据 = 词表.读取(根目录);
            switch (命令) {
                case "词表" -> 打印词表(词表数据);
                case "检查" -> System.exit(检查(根目录, 词表数据, 取文件参数(参数, 2)));
                case "诊断" -> System.exit(诊断(根目录, 词表数据, 参数));
                case "分词" -> System.exit(分词(根目录, 词表数据, 取文件参数(参数, 2)));
                case "语法" -> System.exit(语法(词表数据, 取文件参数(参数, 2)));
                case "语法树" -> System.exit(打印语法树(词表数据, 取文件参数(参数, 2), null));
                case "语义" -> System.exit(语义(词表数据, 取文件参数(参数, 2)));
                case "语义模块" -> System.exit(语义模块(根目录, 词表数据, 参数));
                case "中间模块" -> System.exit(中间模块(根目录, 词表数据, 参数));
                case "快照" -> {
                    if (参数.length < 3) {
                        显示用法();
                        System.exit(2);
                    }
                    Path 输出目录 = Paths.get(参数[2]).toAbsolutePath().normalize();
                    System.exit(打印语法树(词表数据, 取文件参数(参数, 3), 输出目录));
                }
                case "中间" -> System.exit(打印中间代码(根目录, 词表数据, 取文件参数(参数, 2)));
                case "运行" -> System.exit(运行(根目录, 词表数据, 取文件参数(参数, 2)));
                case "运行模块" -> {
                    // 参数：<入口源文件> <其他源文件...>
                    if (参数.length < 3) {
                        显示用法();
                        System.exit(2);
                    }
                    System.exit(运行模块(根目录, 词表数据, 参数));
                }
                case "编译" -> {
                    if (参数.length < 4) {
                        显示用法();
                        System.exit(2);
                    }
                    System.exit(编译C(根目录, 词表数据, 参数[2], 参数[3]));
                }
                case "编译LLVM" -> {
                    if (参数.length < 4) {
                        显示用法();
                        System.exit(2);
                    }
                    System.exit(编译LLVM(根目录, 词表数据, 参数[2], 参数[3]));
                }
                case "编译模块" -> {
                    // 参数：<输出 C> <入口源文件> <其他源文件...>
                    if (参数.length < 5) {
                        显示用法();
                        System.exit(2);
                    }
                    System.exit(编译模块(根目录, 词表数据, 参数[2], 参数[3],
                            java.util.Arrays.copyOfRange(参数, 4, 参数.length), false));
                }
                case "编译模块LLVM" -> {
                    // 参数：<输出 IR> <入口源文件> <其他源文件...>
                    if (参数.length < 5) {
                        显示用法();
                        System.exit(2);
                    }
                    System.exit(编译模块(根目录, 词表数据, 参数[2], 参数[3],
                            java.util.Arrays.copyOfRange(参数, 4, 参数.length), true));
                }
                default -> {
                    System.out.println("未知前端命令：" + 命令);
                    显示用法();
                    System.exit(2);
                }
            }
        } catch (IOException 异常) {
            System.err.println("错误：" + 异常.getMessage());
            System.exit(2);
        }
    }

    private static void 切换输出为UTF8() {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception 忽略) {
            // 保留默认输出流。
        }
    }

    /** M14：从 CJC_PLATFORM 环境变量读取目标平台，默认 windows（逐字节保留既有 PE 行为）。 */
    private static 中文.原生.后端LLVM.LLVM平台配置 当前LLVM配置() {
        String 平台 = System.getenv("CJC_PLATFORM");
        if ("linux".equalsIgnoreCase(平台)) {
            return 中文.原生.后端LLVM.LLVM平台配置.linux();
        }
        if ("darwin".equalsIgnoreCase(平台)) {
            return 中文.原生.后端LLVM.LLVM平台配置.darwin();
        }
        return 中文.原生.后端LLVM.LLVM平台配置.windows();
    }

    private static List<String> 取文件参数(String[] 参数, int 起) {
        List<String> 结果 = new ArrayList<>();
        for (int i = 起; i < 参数.length; i++) {
            结果.add(参数[i]);
        }
        return 结果;
    }

    private static void 打印词表(词表 词表) {
        System.out.println("词表：读取 4 份词表（唯一数据源）");
        System.out.printf("  关键字表：英文 %d / 中文 %d / 类型别名 %d%n",
                词表.英文关键字数, 词表.中文关键字数, 词表.类型别名数);
        System.out.printf("  近义词典：%d 组%n", 词表.近义组数);
        System.out.printf("  方法糖：成员 %d / 声明 %d / 静态 %d / 白名单 %d%n",
                词表.方法糖成员, 词表.方法糖声明, 词表.方法糖静态, 词表.方法糖白名单);
        System.out.printf("  诊断中文表：%d 条%n", 词表.诊断条数);
    }

    private static int 检查(Path 根目录, 词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(根目录, 文件参数);
        List<分析结果> 结果 = new ArrayList<>();
        Set<String> 标识符名 = new HashSet<>();
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            结果.add(new 分析结果(文件, 诊断));
            for (标记 标记 : 文件.标记()) {
                if (标记.类别 == 标记类别.标识符) {
                    标识符名.add(标记.原文);
                }
            }
        }

        System.out.println("原生前端（M1）：词法与语法检查");
        int 成功 = 0;
        for (分析结果 项 : 结果) {
            词法分析器.检查粘连(项.文件(), 标识符名, 词表, 项.诊断());
            诊断.新手检查.检查(项.文件(), 项.诊断());

            System.out.println();
            System.out.println("文件：" + 项.文件().名称());
            项.诊断().打印(System.out, System.err);
            if (项.诊断().有错误()) {
                System.out.println("  " + 项.文件().名称() + "：解析失败");
                continue;
            }
            try {
                编译单元 单元 = new 语法分析器(项.文件().标记()).解析();
                int 顶层节点 = 单元.类型().size() + 单元.语句().size();
                成功++;
                System.out.println("  " + 项.文件().名称() + "：解析成功（" + 顶层节点 + " 个顶层节点）");
            } catch (语法分析器.语法异常 异常) {
                项.诊断().错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
                项.诊断().打印(System.out, System.err);
                System.out.println("  " + 项.文件().名称() + "：解析失败");
            }
        }

        System.out.println();
        System.out.println("汇总：" + 成功 + "/" + 结果.size() + " 解析成功");
        return 成功 == 结果.size() ? 0 : 1;
    }

    /**
     * 供编辑器调用的诊断命令：词法粘连 + 新手提示 + 语法解析。
     * --json 时每个诊断输出一行 JSON，stdout 不混入其它文本；诊断一律以退出码 0 返回。
     */
    private static int 诊断(Path 根目录, 词表 词表, String[] 参数) throws IOException {
        boolean json = false;
        List<String> 文件参数 = new ArrayList<>();
        for (int i = 2; i < 参数.length; i++) {
            if (参数[i].equals("--json")) {
                json = true;
            } else {
                文件参数.add(参数[i]);
            }
        }
        List<Path> 文件们 = 收集文件(根目录, 文件参数);
        List<分析结果> 结果 = new ArrayList<>();
        Set<String> 标识符名 = new HashSet<>();
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            结果.add(new 分析结果(文件, 诊断));
            for (标记 标记 : 文件.标记()) {
                if (标记.类别 == 标记类别.标识符) {
                    标识符名.add(标记.原文);
                }
            }
        }
        for (分析结果 项 : 结果) {
            词法分析器.检查粘连(项.文件(), 标识符名, 词表, 项.诊断());
            诊断.新手检查.检查(项.文件(), 项.诊断());
            try {
                new 语法分析器(项.文件().标记()).解析();
            } catch (语法分析器.语法异常 异常) {
                项.诊断().错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
            }
            if (json) {
                for (诊断.项 一条 : 项.诊断().全部()) {
                    System.out.println(转JSON(一条));
                }
            } else {
                System.out.println("文件：" + 项.文件().名称());
                项.诊断().打印(System.out, System.err);
            }
        }
        return 0;
    }

    /** 单条诊断转一行 JSON；字段名固定，供 VSCode 扩展解析。 */
    private static String 转JSON(诊断.项 项) {
        return "{\"码\":\"" + 转义JSON(项.码())
                + "\",\"行\":" + 项.行()
                + ",\"列\":" + 项.列()
                + ",\"长度\":" + 项.长度()
                + ",\"消息\":\"" + 转义JSON(项.消息())
                + "\",\"建议\":\"" + 转义JSON(项.建议())
                + "\",\"错误\":" + 项.错误() + "}";
    }

    private static String 转义JSON(String 文本) {
        StringBuilder 结果 = new StringBuilder();
        for (int i = 0; i < 文本.length(); i++) {
            char 字符 = 文本.charAt(i);
            switch (字符) {
                case '"' -> 结果.append("\\\"");
                case '\\' -> 结果.append("\\\\");
                case '\n' -> 结果.append("\\n");
                case '\r' -> 结果.append("\\r");
                case '\t' -> 结果.append("\\t");
                default -> {
                    if (字符 < 0x20) {
                        结果.append(String.format("\\u%04x", (int) 字符));
                    } else {
                        结果.append(字符);
                    }
                }
            }
        }
        return 结果.toString();
    }

    /**
     * 词法自举的参考口径：逐文件输出 Token 与词法诊断（CJK001/002/106）。
     * 输出为单行字段格式，供 原生\自举\自举前端.java 逐字节对拍；诊断是数据，退出码恒为 0。
     */
    private static int 分词(Path 根目录, 词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(根目录, 文件参数);
        List<分析结果> 结果 = new ArrayList<>();
        Set<String> 标识符名 = new HashSet<>();
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            结果.add(new 分析结果(文件, 诊断));
            for (标记 标记 : 文件.标记()) {
                if (标记.类别 == 标记类别.标识符) {
                    标识符名.add(标记.原文);
                }
            }
        }

        int 标记总数 = 0;
        int 错误总数 = 0;
        int 提示总数 = 0;
        for (分析结果 项 : 结果) {
            词法分析器.检查粘连(项.文件(), 标识符名, 词表, 项.诊断());
            System.out.println("文件|" + 转义分词字段(项.文件().名称()));
            for (标记 标记 : 项.文件().标记()) {
                System.out.println("标记|" + 标记.类别 + "|" + 标记.行 + "|" + 标记.列
                        + "|" + 转义分词字段(标记.原文) + "|" + 转义分词字段(标记.规范));
                标记总数++;
            }
            for (诊断.项 一条 : 项.诊断().全部()) {
                System.out.println("诊断|" + (一条.错误() ? "错误" : "提示")
                        + "|" + 转义分词字段(一条.码()) + "|" + 一条.行() + "|" + 一条.列()
                        + "|" + 一条.长度() + "|" + 转义分词字段(一条.消息())
                        + "|" + 转义分词字段(一条.建议()));
                if (一条.错误()) {
                    错误总数++;
                } else {
                    提示总数++;
                }
            }
        }
        System.out.println("汇总|" + 结果.size() + "|" + 标记总数 + "|" + 错误总数 + "|" + 提示总数);
        return 0;
    }

    /**
     * 语法自举的参考口径：逐文件输出词法诊断、语法诊断与 AST 全文。
     * 单行字段格式，供 原生\自举\自举前端.java 逐字节对拍；诊断是数据，退出码恒为 0。
     */
    private static int 语法(词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(Paths.get(".").toAbsolutePath().normalize(), 文件参数);
        int 成功 = 0;
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            String 树 = null;
            if (!诊断.有错误()) {
                try {
                    编译单元 单元 = new 语法分析器(文件.标记()).解析();
                    树 = new 语法树打印器(词表).打印(单元);
                    成功++;
                } catch (语法分析器.语法异常 异常) {
                    诊断.错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
                }
            }
            System.out.println("文件|" + 转义分词字段(文件.名称()));
            for (诊断.项 一条 : 诊断.全部()) {
                System.out.println("诊断|" + (一条.错误() ? "错误" : "提示")
                        + "|" + 转义分词字段(一条.码()) + "|" + 一条.行() + "|" + 一条.列()
                        + "|" + 一条.长度() + "|" + 转义分词字段(一条.消息())
                        + "|" + 转义分词字段(一条.建议()));
            }
            if (树 != null) {
                System.out.println("树|" + 转义分词字段(树));
            }
        }
        System.out.println("汇总|" + 文件们.size() + "|" + 成功 + "|" + (文件们.size() - 成功));
        return 0;
    }

    /**
     * M9 语义自举的参考口径：先词法/语法，再做名称解析与类型检查，输出符号表与语义诊断。
     * 单行字段格式，供 原生\自举\自举前端.java 逐字节对拍；诊断是数据，退出码恒为 0。
     */
    private static int 语义(词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(Paths.get(".").toAbsolutePath().normalize(), 文件参数);
        List<语义输入> 输入们 = new ArrayList<>();
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            if (诊断.有错误()) {
                System.out.println("汇总|0|0|0|0");
                return 0;
            }
            try {
                编译单元 单元 = new 语法分析器(文件.标记()).解析();
                输入们.add(new 语义输入(文件, 诊断, 单元));
            } catch (语法分析器.语法异常 异常) {
                System.out.println("汇总|0|0|0|0");
                return 0;
            }
        }

        List<String> 类行 = new ArrayList<>();
        List<String> 字段行 = new ArrayList<>();
        List<String> 方法行 = new ArrayList<>();
        List<String> 诊断行 = new ArrayList<>();
        for (语义输入 输入 : 输入们) {
            new 语义分析器(词表, 输入.诊断()).语义检查(输入.单元(), 输入.文件().名称());
            for (诊断.项 一条 : 输入.诊断().全部()) {
                if (!"语义".equals(一条.码())) {
                    continue;
                }
                诊断行.add("诊断|" + 一条.行() + "|" + 一条.列() + "|"
                        + 转义分词字段(一条.码()) + "|" + 转义分词字段(一条.消息()));
            }
            for (语法树.类型声明 声明 : 输入.单元().类型()) {
                收集速览(词表, 声明, null, 输入.单元().包名(), 类行, 字段行, 方法行);
            }
            if (输入.单元().类型().isEmpty()) {
                收集脚本速览(输入.文件().名称(), 类行, 方法行);
            }
        }
        类行.sort(Comparator.naturalOrder());
        字段行.sort(Comparator.naturalOrder());
        方法行.sort(Comparator.naturalOrder());
        类行.forEach(System.out::println);
        字段行.forEach(System.out::println);
        方法行.forEach(System.out::println);
        诊断行.forEach(System.out::println);
        System.out.println("汇总|" + 输入们.size() + "|" + 类行.size()
                + "|" + 方法行.size() + "|" + 诊断行.size());
        return 0;
    }

    /** 按 AST 声明输出一个类型的符号速览；嵌套类型名用 `$` 连接。 */
    private static void 收集速览(词表 词表, 语法树.类型声明 声明, String 外部名,
            List<String> 类行, List<String> 字段行, List<String> 方法行) {
        收集速览(词表, 声明, 外部名, "", 类行, 字段行, 方法行);
    }

    /** M19：带包名的速览——顶层类拼 `包名$类名`（默认包不变）。 */
    private static void 收集速览(词表 词表, 语法树.类型声明 声明, String 外部名, String 包名,
            List<String> 类行, List<String> 字段行, List<String> 方法行) {
        String 全名 = 外部名 != null ? 外部名 + "$" + 声明.名()
                : (包名 == null || 包名.isEmpty() ? 声明.名() : 包名 + "$" + 声明.名());
        String 英文种类 = 词表.中文到英文.getOrDefault(声明.种类(), 声明.种类());
        boolean 是接口 = 英文种类.equals("interface");
        String 父 = 声明.父类() == null || "对象".equals(声明.父类().名())
                ? "-" : 类型文本(声明.父类());
        int 字段数 = 声明.分量().size();
        int 方法数 = 0;
        for (语法树.成员 成员 : 声明.成员()) {
            if (成员 instanceof 语法树.类型声明 内嵌) {
                收集速览(词表, 内嵌, 全名, 包名, 类行, 字段行, 方法行);
            } else if (成员 instanceof 语法树.字段声明 字段) {
                boolean 静态 = 是接口 || 字段.修饰符().contains("static");
                boolean 只读 = 是接口 || 字段.修饰符().contains("final");
                for (语法树.变量声明 变量 : 字段.变量()) {
                    字段行.add("字段|" + 全名 + "|" + 变量.名() + "|" + 类型文本(变量.类型())
                            + "|" + 真值(静态) + "|" + 真值(只读));
                    字段数++;
                }
            } else if (成员 instanceof 语法树.方法声明 方法) {
                方法行.add("方法|" + 全名 + "|" + 方法.名() + "|" + 参数文本(方法.参数())
                        + "|" + 类型文本(方法.返回类型()) + "|"
                        + 真值(方法.修饰符().contains("static")) + "|" + 真值(方法.体() == null));
                方法数++;
            } else if (成员 instanceof 语法树.构造器声明 构造器) {
                方法行.add("方法|" + 全名 + "|<构造>|" + 参数文本(构造器.参数()) + "|无|假|假");
                方法数++;
            } else if (成员 instanceof 语法树.入口声明) {
                方法行.add("方法|" + 全名 + "|<入口>||无|真|假");
                方法数++;
            }
        }
        for (语法树.参数 分量 : 声明.分量()) {
            字段行.add("字段|" + 全名 + "|" + 分量.名() + "|" + 类型文本(分量.类型()) + "|假|真");
        }
        类行.add("类|" + 全名 + "|" + 父 + "|" + 声明.接口().size() + "|" + 字段数 + "|" + 方法数);
    }

    /** 纯语句文件与参考实现一致：用去后缀的文件名作为隐式类名，只含一个 `<入口>`。 */
    private static void 收集脚本速览(String 文件名, List<String> 类行, List<String> 方法行) {
        String 名 = 文件名;
        if (名.endsWith(".java")) {
            名 = 名.substring(0, 名.length() - 5);
        }
        类行.add("类|" + 名 + "|-|0|0|1");
        方法行.add("方法|" + 名 + "|<入口>||无|真|假");
    }

    private static String 类型文本(语法树.类型引用 类型) {
        if (类型 == null) {
            return "-";
        }
        StringBuilder 文本 = new StringBuilder(类型.名());
        if (!类型.实参().isEmpty()) {
            StringBuilder 实参 = new StringBuilder();
            for (语法树.类型引用 项 : 类型.实参()) {
                if (实参.length() > 0) {
                    实参.append(',');
                }
                实参.append(类型文本(项));
            }
            文本.append('<').append(实参).append('>');
        }
        for (int i = 0; i < 类型.数组维数(); i++) {
            文本.append("[]");
        }
        return 文本.toString();
    }

    private static String 参数文本(List<语法树.参数> 参数) {
        StringBuilder 文本 = new StringBuilder();
        for (语法树.参数 项 : 参数) {
            if (文本.length() > 0) {
                文本.append(',');
            }
            文本.append(类型文本(项.类型()));
        }
        return 文本.toString();
    }

    private static String 真值(boolean 值) {
        return 值 ? "真" : "假";
    }

    /** 对拍字段转义：反斜杠、竖线与换行/回车/制表符，保证每条记录只占一行。 */
    private static String 转义分词字段(String 文本) {
        StringBuilder 结果 = new StringBuilder();
        for (int i = 0; i < 文本.length(); i++) {
            char 字符 = 文本.charAt(i);
            switch (字符) {
                case '\\' -> 结果.append("\\\\");
                case '|' -> 结果.append("\\|");
                case '\n' -> 结果.append("\\n");
                case '\r' -> 结果.append("\\r");
                case '\t' -> 结果.append("\\t");
                default -> 结果.append(字符);
            }
        }
        return 结果.toString();
    }

    private static int 打印语法树(词表 词表, List<String> 文件参数, Path 输出目录) throws IOException {
        List<Path> 文件们 = 收集文件(Paths.get(".").toAbsolutePath().normalize(), 文件参数);
        if (输出目录 != null) {
            Files.createDirectories(输出目录);
        }
        int 成功 = 0;
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            标记.源文件 文件 = 读取(路径, 词表, 诊断);
            if (诊断.有错误()) {
                诊断.打印(System.out, System.err);
                continue;
            }
            try {
                编译单元 单元 = new 语法分析器(文件.标记()).解析();
                String 文本 = new 语法树打印器(词表).打印(单元);
                if (输出目录 != null) {
                    String 名 = 文件.名称();
                    if (名.endsWith(".java")) {
                        名 = 名.substring(0, 名.length() - 5);
                    }
                    Path 目标 = 输出目录.resolve(名 + ".ast.txt");
                    Files.writeString(目标, 文本, StandardCharsets.UTF_8);
                } else {
                    System.out.println("// " + 文件.名称());
                    System.out.print(文本);
                    System.out.println();
                }
                成功++;
            } catch (语法分析器.语法异常 异常) {
                诊断.错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
                诊断.打印(System.out, System.err);
            }
        }
        if (输出目录 != null) {
            System.out.println("快照：" + 成功 + "/" + 文件们.size() + " 份 -> " + 输出目录);
        }
        return 成功 == 文件们.size() ? 0 : 1;
    }

    private static int 打印中间代码(Path 根目录, 词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(Paths.get(".").toAbsolutePath().normalize(), 文件参数);
        int 成功 = 0;
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            中间代码.程序 程序 = 分析程序(根目录, 路径, 词表, 诊断);
            if (程序 == null) {
                continue;
            }
            if (文件们.size() > 1) {
                System.out.println("// " + 路径.getFileName());
            }
            System.out.print(中间打印器.打印(程序));
            System.out.println();
            成功++;
        }
        return 成功 == 文件们.size() ? 0 : 1;
    }

    private static int 运行(Path 根目录, 词表 词表, List<String> 文件参数) throws IOException {
        List<Path> 文件们 = 收集文件(Paths.get(".").toAbsolutePath().normalize(), 文件参数);
        int 成功 = 0;
        for (Path 路径 : 文件们) {
            诊断 诊断 = new 诊断();
            中间代码.程序 程序 = 分析程序(根目录, 路径, 词表, 诊断);
            if (程序 == null) {
                continue;
            }
            try {
                int 退出码 = new 解释器(程序, System.out).运行();
                if (退出码 != 0) {
                    System.err.println("错误 运行：" + 路径.getFileName() + " 退出码 " + 退出码);
                    continue;
                }
            } catch (解释器异常 异常) {
                System.err.println("错误 运行：" + 异常.getMessage());
                continue;
            }
            成功++;
        }
        if (文件们.size() > 1) {
            System.out.println("运行：" + 成功 + "/" + 文件们.size() + " 成功");
        }
        return 成功 == 文件们.size() ? 0 : 1;
    }

    private static int 编译C(Path 根目录, 词表 词表, String 源文件, String 输出C) {
        Path 路径 = Paths.get(源文件);
        if (!路径.isAbsolute()) {
            路径 = Paths.get(".").toAbsolutePath().normalize().resolve(源文件).normalize();
        }
        诊断 诊断 = new 诊断();
        中间代码.程序 程序;
        try {
            程序 = 分析程序(根目录, 路径, 词表, 诊断);
        } catch (IOException 异常) {
            System.err.println("错误：读取源文件失败 " + 异常.getMessage());
            return 2;
        }
        if (程序 == null) {
            return 1;
        }
        try {
            String 源码 = 中文.原生.后端C.C生成器.生成(程序);
            Path 输出 = Paths.get(输出C).toAbsolutePath().normalize();
            if (输出.getParent() != null) {
                Files.createDirectories(输出.getParent());
            }
            Files.writeString(输出, 源码, StandardCharsets.UTF_8);
            System.out.println("已生成 C：" + 输出);
            return 0;
        } catch (中文.原生.后端C.C生成器.生成异常 异常) {
            System.err.println(异常.getMessage());
            return 1;
        } catch (IOException 异常) {
            System.err.println("错误：写入 C 文件失败 " + 异常.getMessage());
            return 2;
        }
    }

    private static int 编译LLVM(Path 根目录, 词表 词表, String 源文件, String 输出IR) {
        Path 路径 = Paths.get(源文件);
        if (!路径.isAbsolute()) {
            路径 = Paths.get(".").toAbsolutePath().normalize().resolve(源文件).normalize();
        }
        诊断 诊断 = new 诊断();
        中间代码.程序 程序;
        try {
            程序 = 分析程序(根目录, 路径, 词表, 诊断);
        } catch (IOException 异常) {
            System.err.println("错误：读取源文件失败 " + 异常.getMessage());
            return 2;
        }
        if (程序 == null) {
            return 1;
        }
        try {
            String 源码 = 中文.原生.后端LLVM.LLVM生成器.生成(程序, 当前LLVM配置());
            Path 输出 = Paths.get(输出IR).toAbsolutePath().normalize();
            if (输出.getParent() != null) {
                Files.createDirectories(输出.getParent());
            }
            Files.writeString(输出, 源码, StandardCharsets.UTF_8);
            System.out.println("已生成 LLVM IR：" + 输出);
            return 0;
        } catch (中文.原生.后端LLVM.LLVM生成器.生成异常 异常) {
            System.err.println(异常.getMessage());
            return 1;
        } catch (IOException 异常) {
            System.err.println("错误：写入 LLVM IR 文件失败 " + 异常.getMessage());
            return 2;
        }
    }

    private static 中间代码.程序 分析程序(Path 根目录, Path 路径, 词表 词表, 诊断 诊断) throws IOException {
        标记.源文件 文件 = 读取(路径, 词表, 诊断);
        if (诊断.有错误()) {
            诊断.打印(System.out, System.err);
            return null;
        }
        语法分析器 分析器 = new 语法分析器(文件.标记());
        编译单元 单元;
        try {
            单元 = 分析器.解析();
        } catch (语法分析器.语法异常 异常) {
            诊断.错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
            诊断.打印(System.out, System.err);
            return null;
        }
        String 相对路径 = 路径.toAbsolutePath().normalize().toString();
        try {
            相对路径 = 根目录.toAbsolutePath().normalize()
                    .relativize(路径.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException 忽略) {
            // 源文件不在根目录下时保留绝对路径。
        }
        相对路径 = 相对路径.replace('\\', '/');
        中间代码.程序 程序 = new 语义分析器(词表, 诊断, 分析器.语句行号(), 相对路径)
                .分析(单元, 文件.名称());
        if (诊断.有错误()) {
            诊断.打印(System.out, System.err);
            return null;
        }
        return 程序;
    }

    /** M13：多文件解释运行（JVM 参考后端），与原生 C/LLVM 对拍。 */
    private static int 运行模块(Path 根目录, 词表 词表, String[] 参数) {
        java.util.List<Path> 路径列表 = new java.util.ArrayList<>();
        Path 入口 = Paths.get(参数[2]);
        if (!入口.isAbsolute()) {
            入口 = Paths.get(".").toAbsolutePath().normalize().resolve(参数[2]).normalize();
        }
        路径列表.add(入口);
        for (int i = 3; i < 参数.length; i++) {
            Path p = Paths.get(参数[i]);
            if (!p.isAbsolute()) {
                p = Paths.get(".").toAbsolutePath().normalize().resolve(参数[i]).normalize();
            }
            路径列表.add(p);
        }
        诊断 诊断 = new 诊断();
        中间代码.程序 程序;
        try {
            程序 = 分析模块程序(根目录, 路径列表, 词表, 诊断, 入口);
        } catch (IOException 异常) {
            System.err.println("错误：读取源文件失败 " + 异常.getMessage());
            return 2;
        }
        if (程序 == null) {
            return 1;
        }
        try {
            return new 解释器(程序, System.out).运行();
        } catch (解释器异常 异常) {
            System.err.println("运行失败：" + 异常.getMessage());
            return 1;
        }
    }

    /** M13：单个源文件的解析结果（编译单元 + 行号表 + 相对路径）。 */
    private record 模块解析结果(编译单元 单元, Map<语法树.节点, Integer> 行号, String 相对路径) {}

    /** M13：解析单个源文件为模块输入；词法/语法错误写入诊断并返回 null。 */
    private static 模块解析结果 解析模块源(Path 根目录, Path 路径, 词表 词表, 诊断 诊断) throws IOException {
        标记.源文件 文件 = 读取(路径, 词表, 诊断);
        if (诊断.有错误()) {
            return null;
        }
        语法分析器 分析器 = new 语法分析器(文件.标记());
        编译单元 单元;
        try {
            单元 = 分析器.解析();
        } catch (语法分析器.语法异常 异常) {
            诊断.错误("语法", 异常.行, 异常.列, 异常.消息, 异常.建议);
            return null;
        }
        String 相对路径 = 相对化(根目录, 路径);
        return new 模块解析结果(单元, 分析器.语句行号(), 相对路径);
    }

    private static String 相对化(Path 根目录, Path 路径) {
        String 相对 = 路径.toAbsolutePath().normalize().toString();
        try {
            相对 = 根目录.toAbsolutePath().normalize()
                    .relativize(路径.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException 忽略) {
            // 源文件不在根目录下时保留绝对路径。
        }
        return 相对.replace('\\', '/');
    }

    /** M13：多文件共享分析 → 单个 MIR 程序。 */
    private static 中间代码.程序 分析模块程序(Path 根目录, java.util.List<Path> 路径列表,
                                              词表 词表, 诊断 诊断, Path 入口路径) throws IOException {
        Map<语法树.节点, Integer> 合并行号 = new java.util.HashMap<>();
        java.util.List<语义分析器.模块单元> 单元们 = new java.util.ArrayList<>();
        String 入口相对 = 相对化(根目录, 入口路径);
        for (Path 路径 : 路径列表) {
            模块解析结果 解析 = 解析模块源(根目录, 路径, 词表, 诊断);
            if (解析 == null) {
                诊断.打印(System.out, System.err);
                return null;
            }
            合并行号.putAll(解析.行号());
            单元们.add(new 语义分析器.模块单元(解析.单元(), 路径.getFileName().toString()));
        }
        中间代码.程序 程序 = new 语义分析器(词表, 诊断, 合并行号, 入口相对)
                .分析模块(单元们, 入口路径.getFileName().toString());
        if (诊断.有错误()) {
            诊断.打印(System.out, System.err);
            return null;
        }
        return 程序;
    }

    // ---------- M18 F2-A：跨文件语义/MIR 参考命令（复用分析模块，不改语义分析器） ----------

    /** M18：把 <入口> <其他源...> 解析为路径列表（入口在前，不排序）。 */
    private static List<Path> 模块路径列表(Path 根目录, String[] 参数) {
        List<Path> 结果 = new ArrayList<>();
        for (int i = 2; i < 参数.length; i++) {
            Path 路径 = Paths.get(参数[i]);
            if (!路径.isAbsolute()) {
                路径 = 根目录.resolve(路径).normalize();
            }
            结果.add(路径);
        }
        return 结果;
    }

    /** M18 语义模块：共享类表分析后按单文件语义命令同格式输出速览与诊断。 */
    private static int 语义模块(Path 根目录, 词表 词表, String[] 参数) throws IOException {
        if (参数.length < 3) {
            显示用法();
            return 2;
        }
        List<Path> 路径列表 = 模块路径列表(根目录, 参数);
        诊断 诊断 = new 诊断();
        Map<语法树.节点, Integer> 合并行号 = new java.util.HashMap<>();
        java.util.List<语义分析器.模块单元> 单元们 = new java.util.ArrayList<>();
        for (Path 路径 : 路径列表) {
            模块解析结果 解析 = 解析模块源(根目录, 路径, 词表, 诊断);
            if (解析 == null) {
                System.out.println("汇总|0|0|0|0");
                return 0;
            }
            合并行号.putAll(解析.行号());
            单元们.add(new 语义分析器.模块单元(解析.单元(), 路径.getFileName().toString()));
        }
        new 语义分析器(词表, 诊断, 合并行号, 相对化(根目录, 路径列表.get(0)))
                .分析模块(单元们, 路径列表.get(0).getFileName().toString());

        List<String> 类行 = new ArrayList<>();
        List<String> 字段行 = new ArrayList<>();
        List<String> 方法行 = new ArrayList<>();
        List<String> 诊断行 = new ArrayList<>();
        for (语义分析器.模块单元 单元 : 单元们) {
            for (语法树.类型声明 声明 : 单元.单元().类型()) {
                收集速览(词表, 声明, null, 单元.单元().包名(), 类行, 字段行, 方法行);
            }
            if (单元.单元().类型().isEmpty()) {
                收集脚本速览(单元.文件名(), 类行, 方法行);
            }
        }
        for (诊断.项 一条 : 诊断.全部()) {
            if (!"语义".equals(一条.码())) {
                continue;
            }
            诊断行.add("诊断|" + 一条.行() + "|" + 一条.列() + "|"
                    + 转义分词字段(一条.码()) + "|" + 转义分词字段(一条.消息()));
        }
        类行.sort(Comparator.naturalOrder());
        字段行.sort(Comparator.naturalOrder());
        方法行.sort(Comparator.naturalOrder());
        类行.forEach(System.out::println);
        字段行.forEach(System.out::println);
        方法行.forEach(System.out::println);
        诊断行.forEach(System.out::println);
        System.out.println("汇总|" + 单元们.size() + "|" + 类行.size()
                + "|" + 方法行.size() + "|" + 诊断行.size());
        return 0;
    }

    /** M18 中间模块：共享类表分析产出一个 MIR 程序，无 // 文件名分段前缀。 */
    private static int 中间模块(Path 根目录, 词表 词表, String[] 参数) throws IOException {
        if (参数.length < 3) {
            显示用法();
            return 2;
        }
        List<Path> 路径列表 = 模块路径列表(根目录, 参数);
        诊断 诊断 = new 诊断();
        中间代码.程序 程序 = 分析模块程序(根目录, 路径列表, 词表, 诊断, 路径列表.get(0));
        if (程序 == null) {
            return 1;
        }
        System.out.print(中间打印器.打印(程序));
        System.out.println();
        return 0;
    }

    /** M13：多文件 → 一个 C/LLVM 输出（isLLVM 选择后端）。 */
    private static int 编译模块(Path 根目录, 词表 词表, String 输出路径, String 入口源,
                                String[] 其他源, boolean isLLVM) {
        java.util.List<Path> 路径列表 = new java.util.ArrayList<>();
        Path 入口 = Paths.get(入口源);
        if (!入口.isAbsolute()) {
            入口 = Paths.get(".").toAbsolutePath().normalize().resolve(入口源).normalize();
        }
        路径列表.add(入口);
        for (String 源 : 其他源) {
            Path p = Paths.get(源);
            if (!p.isAbsolute()) {
                p = Paths.get(".").toAbsolutePath().normalize().resolve(源).normalize();
            }
            路径列表.add(p);
        }
        诊断 诊断 = new 诊断();
        中间代码.程序 程序;
        try {
            程序 = 分析模块程序(根目录, 路径列表, 词表, 诊断, 入口);
        } catch (IOException 异常) {
            System.err.println("错误：读取源文件失败 " + 异常.getMessage());
            return 2;
        }
        if (程序 == null) {
            return 1;
        }
        try {
            String 源码 = isLLVM
                    ? 中文.原生.后端LLVM.LLVM生成器.生成(程序, 当前LLVM配置())
                    : 中文.原生.后端C.C生成器.生成(程序);
            Path 输出 = Paths.get(输出路径).toAbsolutePath().normalize();
            if (输出.getParent() != null) {
                Files.createDirectories(输出.getParent());
            }
            Files.writeString(输出, 源码, StandardCharsets.UTF_8);
            System.out.println("已生成" + (isLLVM ? " LLVM IR：" : " C：") + 输出);
            return 0;
        } catch (中文.原生.后端C.C生成器.生成异常 | 中文.原生.后端LLVM.LLVM生成器.生成异常 异常) {
            System.err.println(异常.getMessage());
            return 1;
        } catch (IOException 异常) {
            System.err.println("错误：写入输出文件失败 " + 异常.getMessage());
            return 2;
        }
    }

    private static 标记.源文件 读取(Path 路径, 词表 词表, 诊断 诊断) throws IOException {
        String 源码 = Files.readString(路径, StandardCharsets.UTF_8);
        if (!源码.isEmpty() && 源码.charAt(0) == '\uFEFF') {
            源码 = 源码.substring(1);
        }
        List<标记> 标记 = new 词法分析器(源码, 词表, 诊断).分析();
        return new 标记.源文件(路径.getFileName().toString(), 路径.toString(), 源码, 标记);
    }

    private static List<Path> 收集文件(Path 根目录, List<String> 文件参数) throws IOException {
        List<Path> 结果 = new ArrayList<>();
        if (文件参数.isEmpty()) {
            文件参数 = List.of(根目录.resolve("教程").toString());
        }
        for (String 文本 : 文件参数) {
            Path 路径 = Paths.get(文本).toAbsolutePath().normalize();
            if (Files.isDirectory(路径)) {
                try (Stream<Path> 流 = Files.walk(路径)) {
                    流.filter(Files::isRegularFile)
                            .filter(项 -> 项.toString().endsWith(".java"))
                            .forEach(结果::add);
                }
            } else {
                结果.add(路径);
            }
        }
        结果.sort(Comparator.comparing(Path::toString));
        return 结果;
    }

    private static void 显示用法() {
        System.out.println("用法：");
        System.out.println("  前端主程序 词表 <根目录>");
        System.out.println("  前端主程序 检查 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 诊断 <根目录> <文件或目录...> [--json]");
        System.out.println("  前端主程序 分词 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 语法 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 语法树 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 语义 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 快照 <根目录> <输出目录> <文件或目录...>");
        System.out.println("  前端主程序 中间 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 运行 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 运行模块 <根目录> <入口源文件> <其他源文件...>");
        System.out.println("  前端主程序 编译 <根目录> <源文件> <输出 C 文件>");
        System.out.println("  前端主程序 编译LLVM <根目录> <源文件> <输出 LLVM IR 文件>");
        System.out.println("  前端主程序 编译模块 <根目录> <输出 C> <入口源文件> <其他源文件...>");
        System.out.println("  前端主程序 编译模块LLVM <根目录> <输出 IR> <入口源文件> <其他源文件...>");
    }
}
