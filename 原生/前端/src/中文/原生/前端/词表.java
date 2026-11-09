package 中文.原生.前端;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读加载 工具\cjc 下的 4 份词表。
 * 展开规则与 工具\cjc\词表.psm1 保持一致，不复制、不派生第二份词表。
 */
public final class 词表 {

    private static final String 别名起标记 = "# 类型别名（java.lang 常用类型与基本类型的包装类，不是 Java 关键字）";
    private static final String 别名止标记 = "# 类型与字面量";

    private static final Pattern 组行 = Pattern.compile("^(.+?)=\\[(.*)]$");
    private static final Pattern 英文组行 = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*)=\\[(.*)]$");
    private static final Pattern 单行 = Pattern.compile("^(.+?)=(.+)$");

    public final Map<String, String> 中文到英文;
    public final Map<String, String> 英文到中文;
    public final Set<String> 中文词;
    public final Set<String> 英文关键字;
    public final Set<String> 类型别名;
    public final List<String> 中文词按长度;

    public final int 近义组数;
    public final int 英文关键字数;
    public final int 中文关键字数;
    public final int 类型别名数;
    public final int 方法糖成员;
    public final int 方法糖声明;
    public final int 方法糖静态;
    public final int 方法糖白名单;
    public final int 诊断条数;

    private 词表(Map<String, String> 中文到英文,
                 Map<String, String> 英文到中文,
                 Set<String> 英文关键字,
                 Set<String> 类型别名,
                 int 近义组数,
                 int 类型别名数,
                 int 方法糖成员,
                 int 方法糖声明,
                 int 方法糖静态,
                 int 方法糖白名单,
                 int 诊断条数) {
        this.中文到英文 = Map.copyOf(中文到英文);
        this.英文到中文 = Map.copyOf(英文到中文);
        this.中文词 = Set.copyOf(中文到英文.keySet());
        this.英文关键字 = Set.copyOf(英文关键字);
        this.类型别名 = Set.copyOf(类型别名);
        List<String> 排序 = new ArrayList<>(中文词);
        排序.sort((左, 右) -> {
            int 差 = Integer.compare(右.length(), 左.length());
            return 差 != 0 ? 差 : 左.compareTo(右);
        });
        this.中文词按长度 = List.copyOf(排序);
        this.近义组数 = 近义组数;
        this.英文关键字数 = 英文关键字.size();
        this.中文关键字数 = 中文词.size();
        this.类型别名数 = 类型别名数;
        this.方法糖成员 = 方法糖成员;
        this.方法糖声明 = 方法糖声明;
        this.方法糖静态 = 方法糖静态;
        this.方法糖白名单 = 方法糖白名单;
        this.诊断条数 = 诊断条数;
    }

    public static 词表 读取(Path 根目录) throws IOException {
        Path 目录 = 根目录.resolve("工具").resolve("cjc");
        Path 近义路径 = 目录.resolve("近义词典.txt");
        Path 关键字路径 = 目录.resolve("关键字表.txt");
        Path 方法糖路径 = 目录.resolve("方法糖.txt");
        Path 诊断路径 = 目录.resolve("诊断中文表.txt");
        for (Path 路径 : List.of(近义路径, 关键字路径, 方法糖路径, 诊断路径)) {
            if (!Files.isRegularFile(路径)) {
                throw new IOException("找不到词表：" + 路径);
            }
        }

        List<String> 关键字行 = 读行(关键字路径);
        int 别名起 = 关键字行.indexOf(别名起标记);
        int 别名止 = 关键字行.indexOf(别名止标记);
        if (别名起 < 0 || 别名止 <= 别名起) {
            throw new IOException("关键字表缺少类型别名区段标记：" + 关键字路径);
        }

        Map<String, List<String>> 组 = new LinkedHashMap<>();
        for (String 行 : 读行(近义路径)) {
            String 修剪 = 行.trim();
            if (修剪.isEmpty() || 修剪.startsWith("#")) {
                continue;
            }
            Matcher 匹配 = 组行.matcher(修剪);
            if (!匹配.matches()) {
                continue;
            }
            组.put(匹配.group(1).trim(), 切分词(匹配.group(2)));
        }

        Map<String, String> 中文到英文 = new LinkedHashMap<>();
        Map<String, String> 英文到中文 = new LinkedHashMap<>();
        Set<String> 英文关键字 = new LinkedHashSet<>();

        for (int i = 0; i < 关键字行.size(); i++) {
            if (i >= 别名起 && i < 别名止) {
                continue;
            }
            String 修剪 = 关键字行.get(i).trim();
            if (修剪.isEmpty() || 修剪.startsWith("#")) {
                continue;
            }
            Matcher 组匹配 = 英文组行.matcher(修剪);
            if (组匹配.matches()) {
                String 英文 = 组匹配.group(1);
                List<String> 词 = 切分词(组匹配.group(2));
                String 规范 = 词.isEmpty() ? "" : 词.get(0);
                英文关键字.add(英文);
                英文到中文.putIfAbsent(英文, 规范);
                for (String 单项 : 词) {
                    加入词(中文到英文, 组, 单项, 英文);
                }
                continue;
            }
            Matcher 单匹配 = 单行.matcher(修剪);
            if (单匹配.matches()) {
                String 中文 = 单匹配.group(1).trim();
                String 英文 = 单匹配.group(2).trim();
                英文关键字.add(英文);
                英文到中文.putIfAbsent(英文, 中文);
                加入词(中文到英文, 组, 中文, 英文);
            }
        }

        Set<String> 类型别名 = new LinkedHashSet<>();
        int 类型别名数 = 0;
        for (int i = 别名起 + 1; i < 别名止; i++) {
            String 修剪 = 关键字行.get(i).trim();
            if (修剪.isEmpty() || 修剪.startsWith("#")) {
                continue;
            }
            Matcher 组匹配 = 英文组行.matcher(修剪);
            if (组匹配.matches()) {
                for (String 词 : 切分词(组匹配.group(2))) {
                    类型别名.add(词);
                }
                类型别名数++;
                continue;
            }
            Matcher 单匹配 = 单行.matcher(修剪);
            if (单匹配.matches()) {
                类型别名.add(单匹配.group(1).trim());
                类型别名数++;
            }
        }

        int[] 糖计数 = 读方法糖(方法糖路径);
        int 诊断条数 = 读诊断条数(诊断路径);

        return new 词表(中文到英文, 英文到中文, 英文关键字, 类型别名,
                组.size(), 类型别名数,
                糖计数[0], 糖计数[1], 糖计数[2], 糖计数[3], 诊断条数);
    }

    public String 中文修饰符(String 英文) {
        return 英文到中文.getOrDefault(英文, 英文);
    }

    public boolean 是类型别名(String 词) {
        return 类型别名.contains(词);
    }

    private static void 加入词(Map<String, String> 中文到英文,
                             Map<String, List<String>> 组,
                             String 词,
                             String 英文) {
        if (词.isEmpty()) {
            return;
        }
        中文到英文.putIfAbsent(词, 英文);
        List<String> 近义 = 组.get(词);
        if (近义 != null) {
            for (String 项 : 近义) {
                中文到英文.putIfAbsent(项, 英文);
            }
        }
    }

    private static List<String> 切分词(String 文本) {
        List<String> 结果 = new ArrayList<>();
        for (String 项 : 文本.split("[，,]")) {
            String 修剪 = 项.trim();
            if (!修剪.isEmpty()) {
                结果.add(修剪);
            }
        }
        return 结果;
    }

    private static List<String> 读行(Path 路径) throws IOException {
        String 文本 = Files.readString(路径, StandardCharsets.UTF_8);
        if (!文本.isEmpty() && 文本.charAt(0) == '\uFEFF') {
            文本 = 文本.substring(1);
        }
        return List.of(文本.split("\\R", -1));
    }

    private static int[] 读方法糖(Path 路径) throws IOException {
        Map<String, Integer> 计数 = new LinkedHashMap<>();
        计数.put("成员", 0);
        计数.put("声明", 0);
        计数.put("静态", 0);
        计数.put("白名单", 0);
        String 区 = null;
        for (String 行 : 读行(路径)) {
            String 修剪 = 行.trim();
            if (修剪.isEmpty() || 修剪.startsWith("#")) {
                continue;
            }
            if (修剪.startsWith("[") && 修剪.endsWith("]")) {
                区 = 修剪.substring(1, 修剪.length() - 1);
                continue;
            }
            if (区 != null && 计数.containsKey(区)) {
                计数.put(区, 计数.get(区) + 1);
            }
        }
        return new int[]{
                计数.get("成员"), 计数.get("声明"), 计数.get("静态"), 计数.get("白名单")
        };
    }

    private static int 读诊断条数(Path 路径) throws IOException {
        int 数 = 0;
        for (String 行 : 读行(路径)) {
            String 修剪 = 行.trim();
            if (!修剪.isEmpty() && !修剪.startsWith("#")) {
                数++;
            }
        }
        return 数;
    }
}
