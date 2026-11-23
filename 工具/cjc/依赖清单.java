// M15 包管理：中文 DSL 依赖清单解析（本地路径 + 精确版本，离线闭环）
// source-file 模式运行：java 工具\cjc\依赖清单.java 解析|哈希 <清单路径>
//   - 解析：stdout 第一行为入口文件，其余为有序源文件列表（均绝对路径）
//   - 哈希：stdout 为闭包内所有清单文本聚合的 SHA256（供原生驱动缓存键使用）
// 任一步出错：stderr 一行中文诊断，退出码 2。

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

public class 依赖清单 {
    static final String 清单文件名 = "依赖清单.txt";

    /** 清单错误：携带一行可直接打印的中文诊断。 */
    static final class 清单错误 extends Exception {
        清单错误(String 消息) { super(消息); }
    }

    static final class 依赖 {
        final String 名称;
        final String 路径文本;
        final String 版本文本;
        依赖(String 名称, String 路径文本, String 版本文本) {
            this.名称 = 名称;
            this.路径文本 = 路径文本;
            this.版本文本 = 版本文本;
        }
    }

    static final class 清单 {
        String 包名;
        String 版本;
        String 入口文本;
        final List<String> 源目录文本们 = new ArrayList<>();
        final List<依赖> 依赖们 = new ArrayList<>();
        Path 路径;
        Path 目录;
    }

    static final class 闭包 {
        Path 入口;
        final List<Path> 源们 = new ArrayList<>();     // 去重、有序（入口优先）
        final List<Path> 清单们 = new ArrayList<>();   // 主清单 + 依赖清单（访问序、去重）
    }

    /** 读文件为行列表，去掉首行 UTF-8 BOM。 */
    private static List<String> 读行(Path 路径) throws IOException {
        List<String> 原始 = Files.readAllLines(路径, StandardCharsets.UTF_8);
        if (!原始.isEmpty() && !原始.get(0).isEmpty() && 原始.get(0).charAt(0) == '\uFEFF') {
            原始.set(0, 原始.get(0).substring(1));
        }
        return 原始;
    }

    private static 清单错误 错(Path 文件, int 行, String 消息) {
        return new 清单错误(文件 + ":" + 行 + "：" + 消息);
    }

    /** 版本格式：主.次.修订（每段为数字，至少一段）。 */
    private static String 校验版本(String 值, Path 文件, int 行) throws 清单错误 {
        if (!值.matches("\\d+(\\.\\d+)*")) {
            throw 错(文件, 行, "版本格式非法「" + 值 + "」，应为如 1.0.0 的数字点分格式");
        }
        return 值;
    }

    /** 读单份清单并做基数校验。 */
    private static 清单 读清单(Path 路径) throws IOException, 清单错误 {
        清单 m = new 清单();
        m.路径 = 路径.toAbsolutePath().normalize();
        m.目录 = m.路径.getParent();
        int 行号 = 0;
        for (String 原文 : 读行(路径)) {
            行号++;
            String s = 原文.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] t = s.split("\\s+");
            switch (t[0]) {
                case "包名" -> {
                    if (t.length != 2) throw 错(路径, 行号, "包名 后需跟一个包全名");
                    if (m.包名 != null) throw 错(路径, 行号, "包名 重复");
                    m.包名 = t[1];
                }
                case "版本" -> {
                    if (t.length != 2) throw 错(路径, 行号, "版本 后需跟一个版本号");
                    if (m.版本 != null) throw 错(路径, 行号, "版本 重复");
                    m.版本 = 校验版本(t[1], 路径, 行号);
                }
                case "入口" -> {
                    if (t.length != 2) throw 错(路径, 行号, "入口 后需跟一个相对路径");
                    if (m.入口文本 != null) throw 错(路径, 行号, "入口 重复");
                    m.入口文本 = t[1];
                }
                case "源目录" -> {
                    if (t.length != 2) throw 错(路径, 行号, "源目录 后需跟一个相对路径");
                    m.源目录文本们.add(t[1]);
                }
                case "依赖" -> {
                    // 依赖 名称 路径 <路径> 版本 <版本>
                    if (t.length != 6 || !t[2].equals("路径") || !t[4].equals("版本")) {
                        throw 错(路径, 行号, "依赖 格式应为：依赖 <名称> 路径 <相对路径> 版本 <版本>");
                    }
                    m.依赖们.add(new 依赖(t[1], t[3], 校验版本(t[5], 路径, 行号)));
                }
                default -> throw 错(路径, 行号, "未知指令「" + t[0] + "」，可用：包名/版本/入口/源目录/依赖");
            }
        }
        if (m.包名 == null) throw 错(路径, 0, "缺少 包名 指令");
        if (m.版本 == null) throw 错(路径, 0, "缺少 版本 指令");
        if (m.入口文本 == null) throw 错(路径, 0, "缺少 入口 指令");
        if (m.源目录文本们.isEmpty()) throw 错(路径, 0, "至少需要一个 源目录 指令");
        return m;
    }

    /** 把清单的源目录下所有 .java 按路径排序后加入闭包（去重）。 */
    private static void 收集源(清单 m, 闭包 r, Set<Path> 已收) throws IOException, 清单错误 {
        for (String 目录文本 : m.源目录文本们) {
            Path 目录 = m.目录.resolve(目录文本).normalize();
            if (!Files.isDirectory(目录)) {
                throw new 清单错误("源目录不存在：" + 目录 + "（清单 " + m.路径 + "）");
            }
            try (Stream<Path> 流 = Files.walk(目录)) {
                List<Path> 文件们 = 流.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();
                for (Path p : 文件们) {
                    Path 规范 = p.toAbsolutePath().normalize();
                    if (已收.add(规范)) {
                        r.源们.add(规范);
                    }
                }
            }
        }
    }

    /** 从递归栈当前位置构造「甲 -> 乙 -> 甲」形式的环链路。 */
    private static String 环链路(Set<Path> 栈, Path 当前) {
        List<String> 名字们 = new ArrayList<>();
        boolean 开始 = false;
        for (Path p : 栈) {
            if (!开始 && p.equals(当前)) 开始 = true;
            if (开始) 名字们.add(p.getParent().getFileName().toString());
        }
        名字们.add(当前.getParent().getFileName().toString());
        return String.join(" -> ", 名字们);
    }

    /** 递归解析一条依赖：查环、查重、精确版本比对、收集源文件。 */
    private static void 递归依赖(依赖 d, 清单 父, Set<Path> 栈, 闭包 r, Set<Path> 已收,
                                 Map<String, String> 包名到版本, Map<String, Path> 包名到路径)
            throws IOException, 清单错误 {
        Path 目录 = 父.目录.resolve(d.路径文本).normalize();
        Path 清单路径 = 目录.resolve(清单文件名);
        if (!Files.exists(清单路径)) {
            throw new 清单错误("依赖清单缺失：" + 清单路径 + "（依赖「" + d.名称 + "」）");
        }
        Path 规范 = 清单路径.toAbsolutePath().normalize();
        if (栈.contains(规范)) {
            throw new 清单错误("循环依赖：" + 环链路(栈, 规范));
        }
        清单 sub = 读清单(清单路径);
        if (!sub.版本.equals(d.版本文本)) {
            throw new 清单错误("版本冲突：依赖「" + d.名称 + "」要求 " + d.版本文本
                    + "，实际 " + sub.版本 + "（" + 规范 + "）");
        }
        Path 已有路径 = 包名到路径.get(sub.包名);
        if (已有路径 != null) {
            if (已有路径.equals(规范)) {
                return; // 同一份清单被多条路径引用：去重跳过
            }
            String 已有版本 = 包名到版本.get(sub.包名);
            if (!已有版本.equals(sub.版本)) {
                throw new 清单错误("版本冲突：包「" + sub.包名 + "」被要求 " + 已有版本
                        + " 与 " + sub.版本 + "（" + 规范 + "）");
            }
            throw new 清单错误("重复包：包名「" + sub.包名 + "」出现两次（" + 已有路径 + "、" + 规范 + "）");
        }
        包名到版本.put(sub.包名, sub.版本);
        包名到路径.put(sub.包名, 规范);
        栈.add(规范);
        r.清单们.add(规范);
        收集源(sub, r, 已收);
        for (依赖 子 : sub.依赖们) {
            递归依赖(子, sub, 栈, r, 已收, 包名到版本, 包名到路径);
        }
        栈.remove(规范);
    }

    /** 解析主清单到完整闭包。 */
    private static 闭包 解析闭包(Path 主清单位置) throws IOException, 清单错误 {
        闭包 r = new 闭包();
        清单 主 = 读清单(主清单位置);
        Path 入口 = 主.目录.resolve(主.入口文本).normalize();
        if (!Files.isRegularFile(入口)) {
            throw new 清单错误("入口文件不存在：" + 入口 + "（清单 " + 主.路径 + "）");
        }
        r.入口 = 入口.toAbsolutePath().normalize();
        r.清单们.add(主.路径);

        Set<Path> 已收 = new LinkedHashSet<>();
        收集源(主, r, 已收);

        Map<String, String> 包名到版本 = new LinkedHashMap<>();
        Map<String, Path> 包名到路径 = new LinkedHashMap<>();
        包名到版本.put(主.包名, 主.版本);
        包名到路径.put(主.包名, 主.路径);

        Set<Path> 栈 = new LinkedHashSet<>();
        栈.add(主.路径);
        for (依赖 d : 主.依赖们) {
            递归依赖(d, 主, 栈, r, 已收, 包名到版本, 包名到路径);
        }
        return r;
    }

    /** 闭包内所有清单文本按访问序拼接后做 SHA256（大写十六进制，无连字符）。 */
    private static String 聚合哈希(闭包 r) throws IOException {
        StringBuilder 拼接 = new StringBuilder();
        for (Path 清单路径 : r.清单们) {
            拼接.append(Files.readString(清单路径, StandardCharsets.UTF_8)).append('|');
        }
        try {
            byte[] 摘要 = MessageDigest.getInstance("SHA-256")
                    .digest(拼接.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder 十六进制 = new StringBuilder();
            for (byte b : 摘要) {
                十六进制.append(String.format("%02X", b & 0xFF));
            }
            return 十六进制.toString();
        } catch (NoSuchAlgorithmException 异常) {
            throw new AssertionError("SHA-256 不可用", 异常);
        }
    }

    private static void 用法() {
        System.out.println("用法：依赖 解析 <清单路径> [输出文件] | 依赖 哈希 <清单路径> [输出文件]");
        System.out.println("说明：给出输出文件时结果写入该文件（UTF-8），避免 Windows 控制台中文路径乱码。");
    }

    /** M17 CI 修复④：与 Cjc.java 同源的参数文件协议，绕开命令行中文解码。 */
    private static String[] 读参数文件(String[] args) throws IOException {
        String 路径 = System.getenv("CJC_ARGS_FILE");
        if (路径 == null || 路径.isEmpty()) return args;
        Path p = Paths.get(路径);
        if (!Files.isRegularFile(p)) return args;
        return Files.readAllLines(p, StandardCharsets.UTF_8).toArray(new String[0]);
    }

    public static void main(String[] args) {
        try {
            args = 读参数文件(args);
            if (args.length < 2) {
                用法();
                System.exit(2);
            }
            String 命令 = args[0];
            Path 清单位置 = Paths.get(args[1]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(清单位置)) {
                throw new 清单错误("依赖清单不存在：" + 清单位置);
            }
            闭包 r = 解析闭包(清单位置);
            // 第三参数即输出文件：把 stdout 内容改写到 UTF-8 文件，供 PowerShell 5.1 读取。
            String 输出 = null;
            if (args.length >= 3) {
                输出 = args[2];
            }
            StringBuilder 结果 = new StringBuilder();
            switch (命令) {
                case "解析" -> {
                    结果.append(r.入口).append('\n');
                    for (Path p : r.源们) {
                        if (!p.equals(r.入口)) 结果.append(p).append('\n');
                    }
                }
                case "哈希" -> 结果.append(聚合哈希(r)).append('\n');
                default -> {
                    用法();
                    System.exit(2);
                }
            }
            if (输出 != null) {
                Files.writeString(Paths.get(输出), 结果.toString(), StandardCharsets.UTF_8);
            } else {
                System.out.print(结果);
            }
        } catch (清单错误 | IOException 异常) {
            System.err.println("错误：" + 异常.getMessage());
            System.exit(2);
        }
    }
}
