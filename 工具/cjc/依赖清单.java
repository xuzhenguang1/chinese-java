// M15 包管理 + M21 G8 registry/semver：中文 DSL 依赖清单解析
// source-file 模式运行：java 工具\cjc\依赖清单.java 解析|哈希 <清单路径> [输出文件]
//   - 解析：stdout 第一行为入口文件，其余为有序源文件列表（均绝对路径）
//   - 哈希：stdout 为闭包内所有清单文本聚合的 SHA256（供原生驱动缓存键使用）
// M15 原格式（零回退）：依赖 <名称> 路径 <相对路径> 版本 <精确版本>
// M21 新增格式：依赖 <名称> 库 <包名> 版本 <semver 范围>（本地 registry + 远程下载 + 缓存）
// 任一步出错：stderr 一行中文诊断，退出码 2。

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class 依赖清单 {
    static final String 清单文件名 = "依赖清单.txt";

    /** 清单错误：携带一行可直接打印的中文诊断。 */
    static final class 清单错误 extends Exception {
        清单错误(String 消息) { super(消息); }
    }

    /** 语义化版本（M21）：数值比较，不按字符串序。 */
    static final class 版本 implements Comparable<版本> {
        final int 主;
        final int 次;
        final int 修;
        final String 原文;
        版本(int 主, int 次, int 修, String 原文) {
            this.主 = 主; this.次 = 次; this.修 = 修; this.原文 = 原文;
        }
        static 版本 解析(String 值) throws 清单错误 {
            if (!值.matches("\\d+(\\.\\d+){0,2}")) {
                throw new 清单错误("版本格式非法「" + 值 + "」，应为 主.次.修订 的数字点分格式");
            }
            String[] 段 = 值.split("\\.");
            int 主 = Integer.parseInt(段[0]);
            int 次 = 段.length > 1 ? Integer.parseInt(段[1]) : 0;
            int 修 = 段.length > 2 ? Integer.parseInt(段[2]) : 0;
            return new 版本(主, 次, 修, 值);
        }
        @Override public int compareTo(版本 其他) {
            if (主 != 其他.主) return Integer.compare(主, 其他.主);
            if (次 != 其他.次) return Integer.compare(次, 其他.次);
            return Integer.compare(修, 其他.修);
        }
        @Override public String toString() { return 原文; }
    }

    /** semver 范围（M21）：^插入 / ~波浪 / 1.x 通配 / 精确。 */
    static final class 范围 {
        enum 类别 { 精确, 插入, 波浪, 通配次, 通配修 }
        final 类别 型;
        final 版本 基准;
        范围(类别 型, 版本 基准) { this.型 = 型; this.基准 = 基准; }
        static 范围 解析(String 值) throws 清单错误 {
            String s = 值.trim();
            if (s.matches("\\^\\d+(\\.\\d+){0,2}")) {
                return new 范围(类别.插入, 版本.解析(s.substring(1)));
            }
            if (s.matches("~\\d+(\\.\\d+){0,2}")) {
                return new 范围(类别.波浪, 版本.解析(s.substring(1)));
            }
            if (s.matches("\\d+\\.[xX*]")) {
                return new 范围(类别.通配次, 版本.解析(s.substring(0, s.indexOf('.'))));
            }
            if (s.matches("\\d+\\.\\d+\\.[xX*]")) {
                return new 范围(类别.通配修, 版本.解析(s.substring(0, s.lastIndexOf('.'))));
            }
            if (s.matches("\\d+(\\.\\d+){0,2}")) {
                return new 范围(类别.精确, 版本.解析(s));
            }
            throw new 清单错误("版本范围格式非法「" + 值 + "」，可用 ^1.2 / ~1.2 / 1.x / 1.2.x / 精确版本");
        }
        boolean 匹配(版本 v) {
            switch (型) {
                case 精确: return v.compareTo(基准) == 0;
                case 插入:
                    // ^1.2.3 → >=1.2.3 且 <2.0.0；^0.2.3 → >=0.2.3 且 <0.3.0
                    if (v.compareTo(基准) < 0) return false;
                    if (基准.主 > 0) return v.主 == 基准.主;
                    return v.主 == 0 && v.次 == 基准.次;
                case 波浪:
                    // ~1.2.3 → >=1.2.3 且 <1.3.0；~1 → >=1.0.0 且 <2.0.0
                    if (v.compareTo(基准) < 0) return false;
                    if (基准.原文.indexOf('.') < 0) return v.主 == 基准.主;
                    return v.主 == 基准.主 && v.次 == 基准.次;
                case 通配次: return v.主 == 基准.主;
                case 通配修: return v.主 == 基准.主 && v.次 == 基准.次;
                default: return false;
            }
        }
        @Override public String toString() { return 基准.原文 + "(" + 型 + ")"; }
    }

    static final class 依赖 {
        final String 名称;
        final String 路径文本;      // 路径模式为相对路径；库模式为包名
        final String 版本文本;      // 路径模式为精确版本；库模式为 semver 范围
        final boolean 是库;
        依赖(String 名称, String 路径文本, String 版本文本, boolean 是库) {
            this.名称 = 名称;
            this.路径文本 = 路径文本;
            this.版本文本 = 版本文本;
            this.是库 = 是库;
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

    /** M15 精确版本格式（路径模式沿用，零回退）。 */
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
                    if (t.length == 6 && t[2].equals("路径") && t[4].equals("版本")) {
                        // M15：依赖 <名称> 路径 <相对路径> 版本 <精确版本>（零回退）
                        m.依赖们.add(new 依赖(t[1], t[3], 校验版本(t[5], 路径, 行号), false));
                    } else if (t.length == 6 && t[2].equals("库") && t[4].equals("版本")) {
                        // M21：依赖 <名称> 库 <包名> 版本 <semver 范围>
                        try {
                            范围.解析(t[5]);
                        } catch (清单错误 e) {
                            throw 错(路径, 行号, e.getMessage());
                        }
                        m.依赖们.add(new 依赖(t[1], t[3], t[5], true));
                    } else {
                        throw 错(路径, 行号, "依赖 格式应为：依赖 <名称> 路径 <相对路径> 版本 <版本> 或 依赖 <名称> 库 <包名> 版本 <范围>");
                    }
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

    // ---------------- M21 G8：registry 根目录 / 远程下载 / semver 求解 ----------------

    /** registry 根目录：CJK_REGISTRY 环境变量优先，否则主清单向上回溯找「仓库/依赖库」。 */
    private static Path registry根(Path 主清单目录) {
        String 显式 = System.getenv("CJK_REGISTRY");
        if (显式 != null && !显式.isBlank()) {
            return Paths.get(显式).toAbsolutePath().normalize();
        }
        Path p = 主清单目录;
        for (int i = 0; i < 6 && p != null; i++) {
            Path 候选 = p.resolve("仓库/依赖库");
            if (Files.isDirectory(候选)) return 候选.toAbsolutePath().normalize();
            p = p.getParent();
        }
        return 主清单目录.resolve("仓库/依赖库").toAbsolutePath().normalize();
    }

    /** 列出包目录下所有合法版本子目录（含依赖清单.txt）。 */
    private static List<版本> 可用版本(Path 包目录) {
        List<版本> 结果 = new ArrayList<>();
        if (!Files.isDirectory(包目录)) return 结果;
        try (Stream<Path> 流 = Files.list(包目录)) {
            for (Path 子 : 流.filter(Files::isDirectory).toList()) {
                String 名 = 子.getFileName().toString();
                if (名.startsWith(".")) continue;
                if (!Files.isRegularFile(子.resolve(清单文件名))) continue;
                try { 结果.add(版本.解析(名)); } catch (清单错误 忽略) { /* 非版本目录跳过 */ }
            }
        } catch (IOException 忽略) { /* 无法列出视为无可用版本 */ }
        return 结果;
    }

    /** 把 URL 中非 ASCII 字符按 UTF-8 做 percent 编码（中文包名/版本路径必需）。 */
    private static URI 编码URI(String url文本) {
        StringBuilder 结果 = new StringBuilder();
        for (char c : url文本.toCharArray()) {
            if (c < 128) {
                结果.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    结果.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return URI.create(结果.toString());
    }

    /** HTTP 取文本（带连接/读取超时）。 */
    private static String http取文本(String url文本) throws 清单错误 {
        try {
            HttpClient 客户端 = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest 请求 = HttpRequest.newBuilder(编码URI(url文本))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> 响应 = 客户端.send(请求, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (响应.statusCode() != 200) {
                throw new 清单错误("下载失败：" + url文本 + "（HTTP " + 响应.statusCode() + "）");
            }
            return 响应.body();
        } catch (清单错误 e) {
            throw e;
        } catch (Exception e) {
            throw new 清单错误("下载失败：" + url文本 + "（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）");
        }
    }

    /** HTTP 取字节（带连接/读取超时）。 */
    private static byte[] http取字节(String url文本) throws 清单错误 {
        try {
            HttpClient 客户端 = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest 请求 = HttpRequest.newBuilder(编码URI(url文本))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<byte[]> 响应 = 客户端.send(请求, HttpResponse.BodyHandlers.ofByteArray());
            if (响应.statusCode() != 200) {
                throw new 清单错误("下载失败：" + url文本 + "（HTTP " + 响应.statusCode() + "）");
            }
            return 响应.body();
        } catch (清单错误 e) {
            throw e;
        } catch (Exception e) {
            throw new 清单错误("下载失败：" + url文本 + "（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）");
        }
    }

    private static String sha256(byte[] 字节) {
        try {
            byte[] 摘要 = MessageDigest.getInstance("SHA-256").digest(字节);
            StringBuilder 十六进制 = new StringBuilder();
            for (byte b : 摘要) 十六进制.append(String.format("%02X", b & 0xFF));
            return 十六进制.toString();
        } catch (NoSuchAlgorithmException 异常) {
            throw new AssertionError("SHA-256 不可用", 异常);
        }
    }

    /** 解压 zip 到目标目录，防目录穿越。 */
    private static void 解压(byte[] 归档, Path 目标目录) throws IOException, 清单错误 {
        Files.createDirectories(目标目录);
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(归档))) {
            ZipEntry 项;
            while ((项 = zis.getNextEntry()) != null) {
                Path 目标 = 目标目录.resolve(项.getName()).normalize();
                if (!目标.startsWith(目标目录)) {
                    throw new 清单错误("下载失败：归档含越界路径「" + 项.getName() + "」");
                }
                if (项.isDirectory()) {
                    Files.createDirectories(目标);
                } else {
                    Path 父 = 目标.getParent();
                    if (父 != null) Files.createDirectories(父);
                    Files.copy(zis, 目标, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** 远程下载满足范围的最高版本到 registry 缓存目录（元数据 + 归档 + SHA256 校验）。 */
    private static void 下载包(String base, String 包名, 范围 req, Path 包目录) throws IOException, 清单错误 {
        String 根 = base.endsWith("/") ? base : base + "/";
        String 元URL = 根 + 包名 + "/versions.txt";
        String 元文本 = http取文本(元URL);
        List<版本> 候选 = new ArrayList<>();
        Map<String, String> 版本到哈希 = new HashMap<>();
        for (String 行 : 元文本.split("\\r?\\n")) {
            String s = 行.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] t = s.split("\\s+");
            if (t.length < 2) continue;
            try {
                版本 v = 版本.解析(t[0]);
                候选.add(v);
                版本到哈希.put(v.原文, t[1].toUpperCase(Locale.ROOT));
            } catch (清单错误 忽略) { /* 跳过非法元数据行 */ }
        }
        List<版本> 匹配 = 候选.stream().filter(req::匹配).sorted(Comparator.reverseOrder()).toList();
        if (匹配.isEmpty()) {
            throw new 清单错误("下载失败：" + 包名 + " 远程无满足 " + req + " 的版本（" + 元URL + "）");
        }
        版本 选 = 匹配.get(0);
        String 归URL = 根 + 包名 + "/" + 选.原文 + ".zip";
        byte[] 归档 = http取字节(归URL);
        String 实际 = sha256(归档);
        String 期望 = 版本到哈希.get(选.原文);
        if (期望 != null && !期望.equals(实际)) {
            throw new 清单错误("校验和不符：" + 归URL + " 期望 " + 期望 + "，实际 " + 实际);
        }
        解压(归档, 包目录.resolve(选.原文));  // 缓存落本地，命中即离线可复现
    }

    /** 库模式：求解本地 registry（命中即不联网），无解时按 CJK_REGISTRY_URL 远程下载。 */
    private static Path 解析库目录(依赖 d, 清单 父) throws IOException, 清单错误 {
        String 包名 = d.路径文本;
        范围 req = 范围.解析(d.版本文本);
        Path 根 = registry根(父.目录);
        Path 包目录 = 根.resolve(包名).toAbsolutePath().normalize();

        // 1) 本地缓存/registry 命中（离线可复现，禁止联网）。
        List<版本> 匹配 = 可用版本(包目录).stream().filter(req::匹配)
                .sorted(Comparator.reverseOrder()).toList();
        if (!匹配.isEmpty()) return 包目录.resolve(匹配.get(0).原文);

        // 2) 本地无匹配：尝试远程下载。
        String base = System.getenv("CJK_REGISTRY_URL");
        if (base == null || base.isBlank()) {
            List<版本> 全部 = 可用版本(包目录).stream().sorted().toList();
            throw new 清单错误("范围无解：依赖「" + d.名称 + "」要求 " + d.版本文本
                    + "，本地可用版本 [" + 全部 + "]，且未配置 CJK_REGISTRY_URL");
        }
        下载包(base, 包名, req, 包目录);
        匹配 = 可用版本(包目录).stream().filter(req::匹配)
                .sorted(Comparator.reverseOrder()).toList();
        if (匹配.isEmpty()) {
            throw new 清单错误("范围无解：依赖「" + d.名称 + "」要求 " + d.版本文本 + "，下载后仍无匹配版本");
        }
        return 包目录.resolve(匹配.get(0).原文);
    }

    /** 递归解析一条依赖：查环、查重、版本比对（M15 精确 / M21 范围）、收集源文件。 */
    private static void 递归依赖(依赖 d, 清单 父, Set<Path> 栈, 闭包 r, Set<Path> 已收,
                                 Map<String, String> 包名到版本, Map<String, Path> 包名到路径)
            throws IOException, 清单错误 {
        Path 目录 = d.是库 ? 解析库目录(d, 父) : 父.目录.resolve(d.路径文本).normalize();
        Path 清单路径 = 目录.resolve(清单文件名);
        if (!Files.exists(清单路径)) {
            throw new 清单错误("依赖清单缺失：" + 清单路径 + "（依赖「" + d.名称 + "」）");
        }
        Path 规范 = 清单路径.toAbsolutePath().normalize();
        if (栈.contains(规范)) {
            throw new 清单错误("循环依赖：" + 环链路(栈, 规范));
        }
        清单 sub = 读清单(清单路径);
        boolean 版本合;
        String 要求说明;
        if (d.是库) {
            版本合 = 范围.解析(d.版本文本).匹配(版本.解析(sub.版本));
            要求说明 = "范围 " + d.版本文本;
        } else {
            版本合 = sub.版本.equals(d.版本文本);
            要求说明 = d.版本文本;
        }
        if (!版本合) {
            throw new 清单错误("版本冲突：依赖「" + d.名称 + "」要求 " + 要求说明
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
        return sha256(拼接.toString().getBytes(StandardCharsets.UTF_8));
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
            String 诊断 = "错误：" + 异常.getMessage();
            // CJC_ERR_FILE：把诊断写 UTF-8 文件，供 CI/PowerShell 5.1 免乱码捕获（同 CJC_ARGS_FILE 协议）。
            String 错误文件 = System.getenv("CJC_ERR_FILE");
            if (错误文件 != null && !错误文件.isEmpty()) {
                try {
                    Files.writeString(Paths.get(错误文件), 诊断, StandardCharsets.UTF_8);
                } catch (IOException 忽略) {
                    System.err.println(诊断); // 写文件失败时回退 stderr
                }
            } else {
                System.err.println(诊断);
            }
            System.exit(2);
        }
    }
}
