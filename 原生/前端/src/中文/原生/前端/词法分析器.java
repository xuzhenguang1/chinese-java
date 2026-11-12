package 中文.原生.前端;

import 中文.原生.前端.标记.标记类别;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 中文 Java 词法分析器：只读词表，产出 Token 流与词法诊断。 */
public final class 词法分析器 {

    private static final int[] 不可映射标点 = {
            0x3005,
            0x3008, 0x3009, 0x300A, 0x300B, 0x300C, 0x300D, 0x300E, 0x300F,
            0x3010, 0x3011, 0x3014, 0x3015, 0x3016, 0x3017, 0x3018, 0x3019,
            0x301A, 0x301B, 0x301C,
            0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2026,
            0x00B7, 0x203B
    };

    private static final String[] 多字符符号 = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "==", "!=", "<=", ">=",
            "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>"
    };

    private static final String 单字符符号 = "(){}[];,.:?@+-*/%<>=!&|^~";

    private final String 源;
    private final 词表 词表;
    private final 诊断 诊断;
    private final int[] 行起;

    public 词法分析器(String 源, 词表 词表, 诊断 诊断) {
        this.源 = 源;
        this.词表 = 词表;
        this.诊断 = 诊断;
        this.行起 = 计算行起(源);
    }

    public List<标记> 分析() {
        List<标记> 结果 = new ArrayList<>();
        int i = 0;
        int 长度 = 源.length();
        while (i < 长度) {
            int 码点 = 源.codePointAt(i);
            int 宽 = Character.charCount(码点);

            if (码点 == 0x3000 || Character.isWhitespace(码点)) {
                i += 宽;
                continue;
            }
            if (源.startsWith("//", i)) {
                i = 跳过行注释(i);
                continue;
            }
            if (源.startsWith("/*", i)) {
                i = 跳过块注释(i);
                continue;
            }
            if (源.startsWith("\"\"\"", i)) {
                int 止 = 扫描文本块(i);
                结果.add(新标记(标记类别.文本, i, 止, 源.substring(i, 止), 源.substring(i, 止)));
                i = 止;
                continue;
            }
            if (码点 == '"') {
                int 止 = 扫描字符串(i);
                结果.add(新标记(标记类别.文本, i, 止, 源.substring(i, 止), 源.substring(i, 止)));
                i = 止;
                continue;
            }
            if (码点 == '\'') {
                int 止 = 扫描字符(i);
                结果.add(新标记(标记类别.字符, i, 止, 源.substring(i, 止), 源.substring(i, 止)));
                i = 止;
                continue;
            }
            if (Character.isJavaIdentifierStart(码点)) {
                int 止 = i + 宽;
                while (止 < 长度 && Character.isJavaIdentifierPart(源.codePointAt(止))) {
                    止 += Character.charCount(源.codePointAt(止));
                }
                String 原文 = 源.substring(i, 止);
                String 英文 = 词表.中文到英文.get(原文);
                if (英文 != null) {
                    结果.add(新标记(标记类别.关键字, i, 止, 原文, 英文));
                } else if (词表.英文关键字.contains(原文)) {
                    结果.add(新标记(标记类别.关键字, i, 止, 原文, 原文));
                } else {
                    结果.add(新标记(标记类别.标识符, i, 止, 原文, 原文));
                }
                i = 止;
                continue;
            }
            if (Character.isDigit(码点)) {
                int 止 = 扫描数字(i);
                结果.add(新标记(标记类别.数字, i, 止, 源.substring(i, 止), 源.substring(i, 止)));
                i = 止;
                continue;
            }
            if (码点 == 0x3002 || 码点 == 0x3001) {
                String 折叠 = 折叠句读(码点, i);
                标记 标记 = 新标记(标记类别.符号, i, i + 1, 折叠, 折叠);
                结果.add(标记);
                诊断.提示("CJK106", 标记.行, 标记.列,
                        "已将「" + 源.substring(i, i + 1) + "」自动纠正为「" + 折叠 + "」。",
                        "建议直接写半角标点。");
                i++;
                continue;
            }
            Integer 映射 = 全角映射(码点);
            if (映射 != null) {
                String 文本 = new String(Character.toChars(映射));
                标记 标记 = 新标记(标记类别.符号, i, i + 1, 文本, 文本);
                结果.add(标记);
                i++;
                continue;
            }
            if (是坏标点(码点)) {
                String 原文 = 源.substring(i, i + 宽);
                标记 标记 = 新标记(标记类别.符号, i, i + 宽, 原文, 原文);
                结果.add(标记);
                诊断.错误("CJK002", 标记.行, 标记.列, 宽,
                        "无法映射的全角标点「" + 原文 + "」。",
                        "改成半角标点，例如「，」改「,」、「（」改「(」。");
                i += 宽;
                continue;
            }

            String 符号 = null;
            for (String 候选 : 多字符符号) {
                if (源.startsWith(候选, i)) {
                    符号 = 候选;
                    break;
                }
            }
            if (符号 == null && 码点 < 128 && 单字符符号.indexOf((char) 码点) >= 0) {
                符号 = String.valueOf((char) 码点);
            }
            if (符号 != null) {
                结果.add(新标记(标记类别.符号, i, i + 符号.length(), 符号, 符号));
                i += 符号.length();
                continue;
            }

            String 原文 = 源.substring(i, i + 宽);
            标记 标记 = 新标记(标记类别.符号, i, i + 宽, 原文, 原文);
            结果.add(标记);
            诊断.错误("非法字符", 标记.行, 标记.列, 宽,
                    "出现了非法字符「" + 原文 + "」。",
                    "删除该字符，或改用合法的运算符/分隔符。");
            i += 宽;
        }
        结果.add(new 标记(标记类别.结束, 长度, 长度, "", "", 行起.length, 1));
        return 结果;
    }

    /** CJK001：标识符以关键字开头，剩余部分又是已知词/标识符。 */
    public static void 检查粘连(标记.源文件 文件, Set<String> 标识符名, 词表 词表, 诊断 诊断) {
        List<标记> 标记 = 文件.标记();
        for (int i = 0; i < 标记.size(); i++) {
            标记 当前 = 标记.get(i);
            if (当前.类别 != 标记类别.标识符) {
                continue;
            }
            String 文本 = 当前.原文;
            if (文本.equals("否则若") && i + 1 < 标记.size() && 标记.get(i + 1).是符号("(")) {
                continue;
            }
            for (String 关键字 : 词表.中文词按长度) {
                if (文本.length() <= 关键字.length() || !文本.startsWith(关键字)) {
                    continue;
                }
                String 剩余 = 文本.substring(关键字.length());
                boolean 命中 = 标识符名.contains(剩余)
                        || (关键字.length() >= 2 && 词表.中文词.contains(剩余));
                if (命中) {
                    诊断.错误("CJK001", 当前.行, 当前.列, 关键字.length(),
                            "中文关键字「" + 关键字 + "」与后面的名称「" + 剩余 + "」之间缺少空格。",
                            "在「" + 关键字 + "」与「" + 剩余 + "」之间补一个空格，或改用不冲突的名称。");
                    break;
                }
            }
        }
    }

    private 标记 新标记(标记类别 类别, int 起, int 止, String 原文, String 规范) {
        int 行 = 行号(起);
        int 列 = 起 - 行起[行 - 1] + 1;
        return new 标记(类别, 起, 止, 原文, 规范, 行, 列);
    }

    private int 跳过行注释(int 起) {
        int i = 起 + 2;
        while (i < 源.length() && 源.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private int 跳过块注释(int 起) {
        int i = 起 + 2;
        while (i < 源.length()) {
            if (源.startsWith("*/", i)) {
                return i + 2;
            }
            i++;
        }
        int 行 = 行号(起);
        诊断.错误("语法", 行, 起 - 行起[行 - 1] + 1,
                "注释没有闭合。",
                "检查「/*」是否有对应的「*/」。");
        return 源.length();
    }

    private int 扫描字符串(int 起) {
        int i = 起 + 1;
        while (i < 源.length()) {
            char 字符 = 源.charAt(i);
            if (字符 == '\\') {
                i += 2;
                continue;
            }
            if (字符 == '"') {
                return i + 1;
            }
            if (字符 == '\n') {
                break;
            }
            i++;
        }
        int 行 = 行号(起);
        诊断.错误("语法", 行, 起 - 行起[行 - 1] + 1,
                "字符串没有闭合。",
                "检查这一行的引号是否成对。");
        return Math.min(i, 源.length());
    }

    private int 扫描文本块(int 起) {
        int i = 起 + 3;
        while (i < 源.length()) {
            if (源.startsWith("\"\"\"", i)) {
                return i + 3;
            }
            i++;
        }
        int 行 = 行号(起);
        诊断.错误("语法", 行, 起 - 行起[行 - 1] + 1,
                "文本块没有闭合。",
                "检查「\"\"\"」是否成对。");
        return 源.length();
    }

    private int 扫描字符(int 起) {
        int i = 起 + 1;
        while (i < 源.length()) {
            char 字符 = 源.charAt(i);
            if (字符 == '\\') {
                i += 2;
                continue;
            }
            if (字符 == '\'') {
                return i + 1;
            }
            if (字符 == '\n') {
                break;
            }
            i++;
        }
        int 行 = 行号(起);
        诊断.错误("语法", 行, 起 - 行起[行 - 1] + 1,
                "字符字面量没有闭合。",
                "检查单引号是否成对。");
        return Math.min(i, 源.length());
    }

    private int 扫描数字(int 起) {
        int i = 起;
        while (i < 源.length()) {
            int 码点 = 源.codePointAt(i);
            if (Character.isLetterOrDigit(码点) || 码点 == '.' || 码点 == '_') {
                i += Character.charCount(码点);
            } else {
                break;
            }
        }
        return i;
    }

    private String 折叠句读(int 码点, int 位置) {
        if (码点 == 0x3001) {
            return ",";
        }
        int 下一个 = 位置 + 1;
        if (下一个 >= 源.length()) {
            return ";";
        }
        int 码 = 源.codePointAt(下一个);
        if (码 == '\n' || 码 == '\r' || 码 == '}' || Character.isWhitespace(码)) {
            return ";";
        }
        return ".";
    }

    private static Integer 全角映射(int 码点) {
        if (码点 >= 0xFF01 && 码点 <= 0xFF5E) {
            return 码点 - 0xFEE0;
        }
        return null;
    }

    private static boolean 是坏标点(int 码点) {
        for (int 候选 : 不可映射标点) {
            if (候选 == 码点) {
                return true;
            }
        }
        return false;
    }

    private int 行号(int 偏移) {
        int 低 = 0;
        int 高 = 行起.length - 1;
        while (低 <= 高) {
            int 中 = (低 + 高) >>> 1;
            if (行起[中] <= 偏移) {
                低 = 中 + 1;
            } else {
                高 = 中 - 1;
            }
        }
        return Math.max(1, 高 + 1);
    }

    private static int[] 计算行起(String 源) {
        List<Integer> 列表 = new ArrayList<>();
        列表.add(0);
        for (int i = 0; i < 源.length(); i++) {
            if (源.charAt(i) == '\n') {
                列表.add(i + 1);
            }
        }
        int[] 结果 = new int[列表.size()];
        for (int i = 0; i < 列表.size(); i++) {
            结果[i] = 列表.get(i);
        }
        return 结果;
    }
}
