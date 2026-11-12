package 中文.原生.前端;

import 中文.原生.前端.标记.标记类别;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 诊断收集与输出。错误影响退出码，提示不影响。 */
public final class 诊断 {

    public record 项(String 码, int 行, int 列, int 长度, String 消息, String 建议, boolean 错误) {}

    private final List<项> 列表 = new ArrayList<>();

    public void 错误(String 码, int 行, int 列, String 消息, String 建议) {
        错误(码, 行, 列, 1, 消息, 建议);
    }

    public void 错误(String 码, int 行, int 列, int 长度, String 消息, String 建议) {
        列表.add(new 项(码, 行, 列, Math.max(1, 长度), 消息, 建议, true));
    }

    public void 提示(String 码, int 行, int 列, String 消息, String 建议) {
        提示(码, 行, 列, 1, 消息, 建议);
    }

    public void 提示(String 码, int 行, int 列, int 长度, String 消息, String 建议) {
        列表.add(new 项(码, 行, 列, Math.max(1, 长度), 消息, 建议, false));
    }

    public boolean 有错误() {
        for (项 项 : 列表) {
            if (项.错误()) {
                return true;
            }
        }
        return false;
    }

    public int 错误数() {
        int 数 = 0;
        for (项 项 : 列表) {
            if (项.错误()) {
                数++;
            }
        }
        return 数;
    }

    public List<项> 全部() {
        return List.copyOf(列表);
    }

    public void 打印(PrintStream 输出, PrintStream 错误输出) {
        for (项 项 : 列表) {
            PrintStream 流 = 项.错误() ? 错误输出 : 输出;
            流.println();
            if (项.行() > 0) {
                流.println((项.错误() ? "错误 " : "提示 ") + 项.码()
                        + "  第" + 项.行() + "行 第" + 项.列() + "列");
            } else {
                流.println((项.错误() ? "错误 " : "提示 ") + 项.码());
            }
            流.println("  " + 项.消息());
            if (!项.建议().isEmpty()) {
                流.println("  建议：" + 项.建议());
            }
        }
    }

    /** CJK101–105：与 Cjc 同口径的新手易错提示，只看 Token，不做类型检查。 */
    public static final class 新手检查 {

        private 新手检查() {}

        public static void 检查(标记.源文件 文件, 诊断 诊断) {
            List<标记> 标记 = 文件.标记();
            Set<String> 字符串变量 = new HashSet<>();
            Set<String> 整数变量 = new HashSet<>();
            for (int i = 0; i + 1 < 标记.size(); i++) {
                标记 当前 = 标记.get(i);
                标记 下一个 = 标记.get(i + 1);
                if ((当前.原文.equals("字符串") || 当前.原文.equals("String"))
                        && 下一个.类别 == 标记类别.标识符) {
                    字符串变量.add(下一个.原文);
                }
                if ((当前.是关键字("int") || 当前.是关键字("long"))
                        && 下一个.类别 == 标记类别.标识符) {
                    整数变量.add(下一个.原文);
                }
            }

            for (int i = 0; i < 标记.size(); i++) {
                标记 当前 = 标记.get(i);
                if (被忽略(文件.源码(), 当前.行)) {
                    continue;
                }
                if (当前.是符号("==")) {
                    标记 左 = 取(标记, i - 1);
                    标记 右 = 取(标记, i + 1);
                    if (是字符串(左, 字符串变量) && 是字符串(右, 字符串变量)) {
                        诊断.提示("CJK101", 当前.行, 当前.列,
                                "字符串不要用「==」比较，比较的是引用。",
                                "改用「字符串.相等(甲, 乙)」或「甲.相等(乙)」。");
                    }
                }
                if (当前.类别 == 标记类别.标识符 && 当前.原文.equals("整理")
                        && 取(标记, i - 1) != null && 取(标记, i - 1).是符号(".")
                        && 取(标记, i + 1) != null && 取(标记, i + 1).是符号("(")
                        && 取(标记, i + 2) != null && 取(标记, i + 2).是符号(")")) {
                    诊断.提示("CJK102", 当前.行, 当前.列,
                            "「整理()」没有参数，不会按规则排序。",
                            "需要自定义顺序时改用「整理(比较器)」。");
                }
                if ((当前.是关键字("for") || 当前.是关键字("while"))
                        && 取(标记, i + 1) != null && 取(标记, i + 1).是符号("(")) {
                    检查循环上界(文件, 标记, i, 诊断);
                }
                if (当前.是符号("/")) {
                    标记 左 = 取(标记, i - 1);
                    标记 右 = 取(标记, i + 1);
                    if (是整数(左, 整数变量) && 是整数(右, 整数变量)
                            && !左侧有浮点强转(标记, i - 1)) {
                        诊断.提示("CJK105", 当前.行, 当前.列,
                                "两个整数相除会丢掉小数部分。",
                                "需要小数时先把其中一个转成「双浮点」，例如「(双浮点) 甲 / 乙」。");
                    }
                }
            }

            Map<String, String> 上次读 = new HashMap<>();
            for (int i = 0; i < 标记.size(); i++) {
                标记 当前 = 标记.get(i);
                if (当前.类别 != 标记类别.标识符
                        || 取(标记, i + 1) == null || !取(标记, i + 1).是符号("(")) {
                    continue;
                }
                String 接收者 = 接收者(标记, i);
                if (当前.原文.equals("读整数") || 当前.原文.equals("读长整数")
                        || 当前.原文.equals("读双浮点")) {
                    上次读.put(接收者, 当前.原文);
                } else if (当前.原文.equals("读行") && 上次读.remove(接收者) != null
                        && !被忽略(文件.源码(), 当前.行)) {
                    诊断.提示("CJK103", 当前.行, 当前.列,
                            "「读整数()」后面的换行还留在输入里，紧跟「读行()」会读到空串。",
                            "读完数字后再补一次「读行()」，或统一用「读行()」再解析。");
                }
            }
        }

        private static void 检查循环上界(标记.源文件 文件, List<标记> 标记, int 序号, 诊断 诊断) {
            int 左括号 = 序号 + 1;
            int 右括号 = 配对右括号(标记, 左括号);
            if (右括号 < 0) {
                return;
            }
            int 起 = 左括号 + 1;
            int 止 = 右括号;
            if (标记.get(序号).是关键字("for")) {
                int 分号一 = 找符号(标记, 起, 止, ";");
                if (分号一 >= 0) {
                    int 分号二 = 找符号(标记, 分号一 + 1, 止, ";");
                    if (分号二 >= 0) {
                        起 = 分号一 + 1;
                        止 = 分号二;
                    }
                }
            }
            for (int i = 起; i < 止; i++) {
                if (!标记.get(i).是符号("<=")) {
                    continue;
                }
                int 末 = 止 - 1;
                if (末 <= i) {
                    continue;
                }
                String 文本 = 文件.源码().substring(标记.get(i + 1).起, 标记.get(末).止).trim();
                if (文本.matches(".+\\.(长度|元素数\\(\\).*)")) {
                    诊断.提示("CJK104", 标记.get(i).行, 标记.get(i).列,
                            "循环条件用了「<= 长度/元素数」，会多跑一次并越界。",
                            "改成「< 长度」或「< 元素数()」。");
                }
            }
        }

        private static boolean 是字符串(标记 标记, Set<String> 字符串变量) {
            if (标记 == null) {
                return false;
            }
            if (标记.类别 == 标记类别.文本) {
                return true;
            }
            return 标记.类别 == 标记类别.标识符 && 字符串变量.contains(标记.原文);
        }

        private static boolean 是整数(标记 标记, Set<String> 整数变量) {
            if (标记 == null) {
                return false;
            }
            if (标记.类别 == 标记类别.数字) {
                return 标记.原文.matches("\\d+[lL]?");
            }
            return 标记.类别 == 标记类别.标识符 && 整数变量.contains(标记.原文);
        }

        private static boolean 左侧有浮点强转(List<标记> 标记, int 左操作数序号) {
            int 右括号 = 左操作数序号 - 1;
            if (右括号 < 0 || !标记.get(右括号).是符号(")")) {
                return false;
            }
            int 深度 = 0;
            for (int i = 右括号; i >= 0; i--) {
                if (标记.get(i).是符号(")")) {
                    深度++;
                } else if (标记.get(i).是符号("(")) {
                    深度--;
                    if (深度 == 0) {
                        return i + 1 < 左操作数序号
                                && (标记.get(i + 1).是关键字("double")
                                || 标记.get(i + 1).是关键字("float"));
                    }
                }
            }
            return false;
        }

        private static String 接收者(List<标记> 标记, int 方法名序号) {
            if (方法名序号 >= 2 && 标记.get(方法名序号 - 1).是符号(".")) {
                return 标记.get(方法名序号 - 2).原文;
            }
            return "";
        }

        private static int 配对右括号(List<标记> 标记, int 左括号序号) {
            int 深度 = 0;
            for (int i = 左括号序号; i < 标记.size(); i++) {
                if (标记.get(i).是符号("(")) {
                    深度++;
                } else if (标记.get(i).是符号(")")) {
                    深度--;
                    if (深度 == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static int 找符号(List<标记> 标记, int 起, int 止, String 符号) {
            int 深度 = 0;
            for (int i = 起; i < 止; i++) {
                标记 当前 = 标记.get(i);
                if (当前.是符号("(")) {
                    深度++;
                } else if (当前.是符号(")")) {
                    深度--;
                } else if (深度 == 0 && 当前.是符号(符号)) {
                    return i;
                }
            }
            return -1;
        }

        private static 标记 取(List<标记> 标记, int 序号) {
            if (序号 < 0 || 序号 >= 标记.size()) {
                return null;
            }
            return 标记.get(序号);
        }

        private static boolean 被忽略(String 源码, int 行) {
            String[] 行们 = 源码.split("\\R", -1);
            return 行 >= 1 && 行 <= 行们.length && 行们[行 - 1].contains("忽略提示");
        }
    }
}
