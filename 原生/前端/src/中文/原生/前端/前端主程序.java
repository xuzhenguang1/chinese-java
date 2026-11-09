package 中文.原生.前端;

import 中文.原生.前端.标记.标记类别;
import 中文.原生.前端.语法树.编译单元;

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
import java.util.Set;
import java.util.stream.Stream;

/** 原生前端命令行：词表 / 检查 / 语法树 / 快照。 */
public final class 前端主程序 {

    private record 分析结果(标记.源文件 文件, 诊断 诊断) {}

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
                case "语法树" -> System.exit(打印语法树(词表数据, 取文件参数(参数, 2), null));
                case "快照" -> {
                    if (参数.length < 3) {
                        显示用法();
                        System.exit(2);
                    }
                    Path 输出目录 = Paths.get(参数[2]).toAbsolutePath().normalize();
                    System.exit(打印语法树(词表数据, 取文件参数(参数, 3), 输出目录));
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
        System.out.println("  前端主程序 语法树 <根目录> <文件或目录...>");
        System.out.println("  前端主程序 快照 <根目录> <输出目录> <文件或目录...>");
    }
}
