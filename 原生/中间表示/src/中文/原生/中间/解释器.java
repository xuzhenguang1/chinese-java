package 中文.原生.中间;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MIR 解释器：直接执行已定型、已脱糖的中间代码。 */
public final class 解释器 {

    private final 中间代码.程序 程序;
    private final Map<String, 中间代码.类定义> 类表 = new LinkedHashMap<>();
    private final PrintStream 输出;
    private final Deque<帧> 帧栈 = new ArrayDeque<>();

    public 解释器(中间代码.程序 程序, PrintStream 输出) {
        this.程序 = 程序;
        this.输出 = 输出;
        for (中间代码.类定义 类 : 程序.类()) {
            类表.put(类.名(), 类);
        }
    }

    /** 执行入口，返回退出码。 */
    public int 运行() {
        中间代码.方法 入口 = null;
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.方法 方法 : 类.方法()) {
                if (方法.名().equals("<入口>")) {
                    入口 = 方法;
                    break;
                }
            }
            if (入口 != null) {
                break;
            }
        }
        if (入口 == null) {
            throw new 解释器异常("找不到「入口」。");
        }
        调用方法(入口, null, List.of());
        输出.flush();
        return 0;
    }

    // ---------- 运行时值 ----------

    private static final class 实例 {
        final 中间代码.类定义 类;
        final Map<String, Object> 字段 = new LinkedHashMap<>();

        实例(中间代码.类定义 类) {
            this.类 = 类;
            for (中间代码.字段定义 字段 : 类.字段()) {
                this.字段.put(字段.名(), 默认值(字段.类型()));
            }
        }
    }

    private static final class 数组值 {
        final 类型 元素类型;
        final Object[] 值;

        数组值(类型 元素类型, int 长度) {
            this.元素类型 = 元素类型;
            this.值 = new Object[长度];
            for (int i = 0; i < 长度; i++) {
                值[i] = 默认值(元素类型);
            }
        }
    }

    private static final class 帧 {
        final 中间代码.方法 方法;
        final 实例 这;
        final Deque<Map<String, Object>> 作用域 = new ArrayDeque<>();

        帧(中间代码.方法 方法, 实例 这) {
            this.方法 = 方法;
            this.这 = 这;
        }
    }

    private static final class 返回信号 extends RuntimeException {
        final Object 值;

        返回信号(Object 值) {
            super(null, null, false, false);
            this.值 = 值;
        }
    }

    private static final class 跳出信号 extends RuntimeException {
        跳出信号() {
            super(null, null, false, false);
        }
    }

    private static final class 继续信号 extends RuntimeException {
        继续信号() {
            super(null, null, false, false);
        }
    }

    private static Object 默认值(类型 类型) {
        if (类型.是数组() || 类型.是类() || 类型.同型(类型.文本)) {
            return null;
        }
        if (!类型.是基本()) {
            return null;
        }
        return switch (类型.基本值) {
            case 整数 -> 0;
            case 长整数 -> 0L;
            case 短整数 -> (short) 0;
            case 字节 -> (byte) 0;
            case 浮点 -> 0.0f;
            case 双浮点 -> 0.0d;
            case 字符 -> '\0';
            case 布尔 -> false;
            case 文本, 无 -> null;
        };
    }

    // ---------- 语句 ----------

    private void 执行(中间代码.语句 语句) {
        if (语句 == null) {
            return;
        }
        if (语句 instanceof 中间代码.块 x) {
            推入();
            try {
                for (中间代码.语句 项 : x.语句()) {
                    执行(项);
                }
            } finally {
                弹出();
            }
            return;
        }
        if (语句 instanceof 中间代码.局部变量语句 x) {
            Object 值 = 转换(x.类型(), 求值(x.初值()));
            当前().作用域.peek().put(x.名(), 值);
            return;
        }
        if (语句 instanceof 中间代码.表达式语句 x) {
            求值(x.值());
            return;
        }
        if (语句 instanceof 中间代码.返回语句 x) {
            Object 值 = x.值() == null ? null : 转换(当前().方法.返回类型(), 求值(x.值()));
            throw new 返回信号(值);
        }
        if (语句 instanceof 中间代码.若语句 x) {
            if (真值(求值(x.条件()))) {
                执行(x.成立());
            } else if (x.否则() != null) {
                执行(x.否则());
            }
            return;
        }
        if (语句 instanceof 中间代码.对于语句 x) {
            推入();
            try {
                if (x.初始化() != null) {
                    // 脱糖生成的初始化可能是「块」，其局部变量必须留在循环作用域里。
                    if (x.初始化() instanceof 中间代码.块 初始化块) {
                        for (中间代码.语句 项 : 初始化块.语句()) {
                            执行(项);
                        }
                    } else {
                        执行(x.初始化());
                    }
                }
                while (x.条件() == null || 真值(求值(x.条件()))) {
                    try {
                        执行(x.体());
                    } catch (继续信号 忽略) {
                        // 继续：直接走更新表达式
                    } catch (跳出信号 忽略) {
                        break;
                    }
                    for (中间代码.表达式 项 : x.更新()) {
                        求值(项);
                    }
                }
            } finally {
                弹出();
            }
            return;
        }
        if (语句 instanceof 中间代码.当语句 x) {
            while (真值(求值(x.条件()))) {
                try {
                    执行(x.体());
                } catch (继续信号 忽略) {
                    // 继续下一轮
                } catch (跳出信号 忽略) {
                    break;
                }
            }
            return;
        }
        if (语句 instanceof 中间代码.做当语句 x) {
            while (true) {
                try {
                    执行(x.体());
                } catch (继续信号 忽略) {
                    // 继续判断条件
                } catch (跳出信号 忽略) {
                    break;
                }
                if (!真值(求值(x.条件()))) {
                    break;
                }
            }
            return;
        }
        if (语句 instanceof 中间代码.跳出语句) {
            throw new 跳出信号();
        }
        if (语句 instanceof 中间代码.继续语句) {
            throw new 继续信号();
        }
        throw new 解释器异常("未知语句：" + 语句.getClass().getSimpleName());
    }

    // ---------- 表达式 ----------

    private Object 求值(中间代码.表达式 表达式) {
        if (表达式 instanceof 中间代码.常量 x) {
            return 常量值(x);
        }
        if (表达式 instanceof 中间代码.变量读 x) {
            return 读变量(x.名());
        }
        if (表达式 instanceof 中间代码.字段读 x) {
            Object 接收 = 求值(x.接收者());
            实例 对象 = 是实例(接收, x.名());
            if (!对象.字段.containsKey(x.名())) {
                throw new 解释器异常("类「" + 对象.类.名() + "」没有字段「" + x.名() + "」。");
            }
            return 对象.字段.get(x.名());
        }
        if (表达式 instanceof 中间代码.数组读 x) {
            Object 数组 = 求值(x.数组());
            数组值 数组对象 = 是数组(数组);
            int 下标 = 到整数(求值(x.下标()));
            if (下标 < 0 || 下标 >= 数组对象.值.length) {
                throw new 解释器异常("数组下标越界：" + 下标 + "，长度：" + 数组对象.值.length + "。");
            }
            return 数组对象.值[下标];
        }
        if (表达式 instanceof 中间代码.数组长度 x) {
            Object 数组 = 求值(x.数组());
            数组值 数组对象 = 是数组(数组);
            return 数组对象.值.length;
        }
        if (表达式 instanceof 中间代码.二元 x) {
            return 二元(x);
        }
        if (表达式 instanceof 中间代码.一元 x) {
            return 一元(x);
        }
        if (表达式 instanceof 中间代码.三目 x) {
            return 真值(求值(x.条件())) ? 转换(x.类型(), 求值(x.真())) : 转换(x.类型(), 求值(x.假()));
        }
        if (表达式 instanceof 中间代码.强制转换 x) {
            return 转换(x.类型(), 求值(x.操作数()));
        }
        if (表达式 instanceof 中间代码.赋值 x) {
            Object 值 = 转换(x.类型(), 求值(x.右()));
            写入(x.左(), 值);
            return 值;
        }
        if (表达式 instanceof 中间代码.调用 x) {
            return 调用(x);
        }
        if (表达式 instanceof 中间代码.新建对象 x) {
            return 新建对象(x);
        }
        if (表达式 instanceof 中间代码.新建数组 x) {
            return 新建数组(x);
        }
        throw new 解释器异常("未知表达式：" + 表达式.getClass().getSimpleName());
    }

    private Object 常量值(中间代码.常量 x) {
        类型 类型 = x.类型();
        String 原文 = x.原文();
        if (类型.同型(类型.文本)) {
            return 反转义文本(原文);
        }
        if (类型.同型(类型.字符)) {
            String 内容 = 反转义文本(原文.replace('\'', '"'));
            return 内容.isEmpty() ? '\0' : 内容.charAt(0);
        }
        if (类型.同型(类型.布尔)) {
            return "真".equals(原文) || "true".equals(原文);
        }
        if (类型.同型(类型.空)) {
            return null;
        }
        if (类型.同型(类型.长整数)) {
            return 解析整数(去后缀(原文, "lL"));
        }
        if (类型.同型(类型.浮点)) {
            return Float.parseFloat(去后缀(原文, "fF").replace("_", ""));
        }
        if (类型.同型(类型.双浮点)) {
            return Double.parseDouble(去后缀(原文, "dD").replace("_", ""));
        }
        if (类型.同型(类型.整数)) {
            return (int) 解析整数(原文);
        }
        throw new 解释器异常("不支持的常量类型：" + 类型.文本());
    }

    private static long 解析整数(String 原文) {
        String 文本 = 原文.replace("_", "");
        if (文本.startsWith("0x") || 文本.startsWith("0X")) {
            return Long.parseLong(文本.substring(2), 16);
        }
        if (文本.startsWith("0b") || 文本.startsWith("0B")) {
            return Long.parseLong(文本.substring(2), 2);
        }
        if (文本.startsWith("0") && 文本.length() > 1) {
            return Long.parseLong(文本.substring(1), 8);
        }
        return Long.parseLong(文本);
    }

    private static String 去后缀(String 原文, String 后缀) {
        if (!原文.isEmpty() && 后缀.indexOf(原文.charAt(原文.length() - 1)) >= 0) {
            return 原文.substring(0, 原文.length() - 1);
        }
        return 原文;
    }

    private static String 反转义文本(String 原文) {
        if (原文.length() < 2) {
            return 原文;
        }
        char 引号 = 原文.charAt(0);
        int 起 = 引号 == '"' || 引号 == '\'' ? 1 : 0;
        int 止 = 原文.length();
        if (止 > 起 && (原文.charAt(止 - 1) == '"' || 原文.charAt(止 - 1) == '\'')) {
            止--;
        }
        StringBuilder 结果 = new StringBuilder();
        for (int i = 起; i < 止; i++) {
            char 字符 = 原文.charAt(i);
            if (字符 != '\\' || i + 1 >= 止) {
                结果.append(字符);
                continue;
            }
            char 转义 = 原文.charAt(++i);
            switch (转义) {
                case 'n' -> 结果.append('\n');
                case 't' -> 结果.append('\t');
                case 'r' -> 结果.append('\r');
                case 'b' -> 结果.append('\b');
                case 'f' -> 结果.append('\f');
                case '0' -> 结果.append('\0');
                case '\\' -> 结果.append('\\');
                case '\'' -> 结果.append('\'');
                case '"' -> 结果.append('"');
                case 'u' -> {
                    if (i + 4 < 止) {
                        String 十六 = 原文.substring(i + 1, i + 5);
                        try {
                            结果.append((char) Integer.parseInt(十六, 16));
                            i += 4;
                        } catch (NumberFormatException 异常) {
                            结果.append("\\u");
                        }
                    } else {
                        结果.append("\\u");
                    }
                }
                default -> 结果.append(转义);
            }
        }
        return 结果.toString();
    }

    private Object 二元(中间代码.二元 x) {
        String 运算符 = x.运算符();
        if (运算符.equals("&&")) {
            return 真值(求值(x.左())) && 真值(求值(x.右()));
        }
        if (运算符.equals("||")) {
            return 真值(求值(x.左())) || 真值(求值(x.右()));
        }
        Object 左 = 求值(x.左());
        Object 右 = 求值(x.右());
        if (x.类型().同型(类型.文本) && 运算符.equals("+")) {
            return 值转文本(左) + 值转文本(右);
        }
        return switch (运算符) {
            case "+", "-", "*", "/", "%" -> 算术(运算符, x.类型(), 左, 右);
            case "<", ">", "<=", ">=" -> 比较(运算符, 左, 右);
            case "==", "!=" -> 相等(运算符, 左, 右);
            case "&", "|", "^" -> 位运算(运算符, x.类型(), 左, 右);
            case "<<", ">>", ">>>" -> 移位(运算符, x.类型(), 左, 右);
            default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
        };
    }

    private Object 一元(中间代码.一元 x) {
        Object 值 = 求值(x.操作数());
        return switch (x.运算符()) {
            case "+" -> 数值(x.类型(), 值);
            case "-" -> 取负(x.类型(), 值);
            case "!" -> !真值(值);
            case "~" -> x.类型().同型(类型.长整数) ? ~到长整数(值) : ~到整数(值);
            default -> throw new 解释器异常("未知一元运算符「" + x.运算符() + "」。");
        };
    }

    private Object 算术(String 运算符, 类型 类型, Object 左, Object 右) {
        if (类型.同型(类型.双浮点)) {
            double a = 到双浮点(左);
            double b = 到双浮点(右);
            return switch (运算符) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                case "%" -> a % b;
                default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
            };
        }
        if (类型.同型(类型.浮点)) {
            float a = 到浮点(左);
            float b = 到浮点(右);
            return switch (运算符) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                case "%" -> a % b;
                default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
            };
        }
        if (类型.同型(类型.长整数)) {
            long a = 到长整数(左);
            long b = 到长整数(右);
            return switch (运算符) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> 除零检查(b, 左, 右) ? 0L : a / b;
                case "%" -> 除零检查(b, 左, 右) ? 0L : a % b;
                default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
            };
        }
        int a = 到整数(左);
        int b = 到整数(右);
        return switch (运算符) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> 除零检查(b, 左, 右) ? 0 : a / b;
            case "%" -> 除零检查(b, 左, 右) ? 0 : a % b;
            default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
        };
    }

    private static boolean 除零检查(long 除数, Object 左, Object 右) {
        if (除数 == 0) {
            throw new 解释器异常("整数除法的除数为 0。");
        }
        return false;
    }

    private Object 比较(String 运算符, Object 左, Object 右) {
        int 比;
        if (左 instanceof Double || 左 instanceof Float
                || 右 instanceof Double || 右 instanceof Float) {
            比 = Double.compare(到双浮点(左), 到双浮点(右));
        } else {
            比 = Long.compare(到长整数(左), 到长整数(右));
        }
        return switch (运算符) {
            case "<" -> 比 < 0;
            case ">" -> 比 > 0;
            case "<=" -> 比 <= 0;
            case ">=" -> 比 >= 0;
            default -> throw new 解释器异常("未知比较运算符「" + 运算符 + "」。");
        };
    }

    private Object 相等(String 运算符, Object 左, Object 右) {
        boolean 同;
        if (左 == null || 右 == null) {
            同 = 左 == 右;
        } else if (左 instanceof String || 右 instanceof String) {
            同 = 左.equals(右);
        } else if (左 instanceof Boolean && 右 instanceof Boolean) {
            同 = 左.equals(右);
        } else if (左 instanceof Object[] || 右 instanceof Object[]
                || 左 instanceof 实例 || 右 instanceof 实例) {
            同 = 左 == 右;
        } else {
            同 = 到长整数(左) == 到长整数(右);
        }
        return 运算符.equals("==") ? 同 : !同;
    }

    private Object 位运算(String 运算符, 类型 类型, Object 左, Object 右) {
        if (类型.同型(类型.布尔)) {
            boolean a = 真值(左);
            boolean b = 真值(右);
            return switch (运算符) {
                case "&" -> a & b;
                case "|" -> a | b;
                case "^" -> a ^ b;
                default -> throw new 解释器异常("未知位运算符「" + 运算符 + "」。");
            };
        }
        if (类型.同型(类型.长整数)) {
            long a = 到长整数(左);
            long b = 到长整数(右);
            return switch (运算符) {
                case "&" -> a & b;
                case "|" -> a | b;
                case "^" -> a ^ b;
                default -> throw new 解释器异常("未知位运算符「" + 运算符 + "」。");
            };
        }
        int a = 到整数(左);
        int b = 到整数(右);
        return switch (运算符) {
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            default -> throw new 解释器异常("未知位运算符「" + 运算符 + "」。");
        };
    }

    private Object 移位(String 运算符, 类型 类型, Object 左, Object 右) {
        if (类型.同型(类型.长整数)) {
            long a = 到长整数(左);
            int b = 到整数(右) & 63;
            return switch (运算符) {
                case "<<" -> a << b;
                case ">>" -> a >> b;
                case ">>>" -> a >>> b;
                default -> throw new 解释器异常("未知移位运算符「" + 运算符 + "」。");
            };
        }
        int a = 到整数(左);
        int b = 到整数(右) & 31;
        return switch (运算符) {
            case "<<" -> a << b;
            case ">>" -> a >> b;
            case ">>>" -> a >>> b;
            default -> throw new 解释器异常("未知移位运算符「" + 运算符 + "」。");
        };
    }

    // ---------- 调用 ----------

    private Object 调用(中间代码.调用 x) {
        if (x.分派() == 中间代码.分派.内建) {
            return 内建(x);
        }
        List<Object> 实参 = 求值实参(x.实参());
        if (x.分派() == 中间代码.分派.构造器) {
            Object 接收 = x.接收者() == null ? null : 求值(x.接收者());
            实例 对象 = 是实例(接收, "这");
            中间代码.方法 构造器 = 选方法(对象.类, x.名(), 实参, true);
            if (构造器 == null) {
                throw new 解释器异常("类「" + 对象.类.名() + "」没有匹配的构造器。");
            }
            调用方法(构造器, 对象, 实参);
            return null;
        }
        if (x.分派() == 中间代码.分派.静态) {
            中间代码.类定义 类 = 类表.get(x.类名());
            if (类 == null) {
                throw new 解释器异常("未知类「" + x.类名() + "」。");
            }
            中间代码.方法 方法 = 选方法(类, x.名(), 实参, false);
            if (方法 == null) {
                throw new 解释器异常("类「" + 类.名() + "」没有匹配的方法「" + x.名() + "」。");
            }
            return 调用方法(方法, null, 实参);
        }
        Object 接收 = 求值(x.接收者());
        实例 对象 = 是实例(接收, x.名());
        中间代码.方法 方法 = 选方法(对象.类, x.名(), 实参, false);
        if (方法 == null) {
            throw new 解释器异常("类「" + 对象.类.名() + "」没有匹配的方法「" + x.名() + "」。");
        }
        return 调用方法(方法, 对象, 实参);
    }

    private List<Object> 求值实参(List<中间代码.表达式> 表达式们) {
        List<Object> 值 = new ArrayList<>();
        for (中间代码.表达式 项 : 表达式们) {
            值.add(求值(项));
        }
        return 值;
    }

    private Object 内建(中间代码.调用 x) {
        switch (x.名()) {
            case "打印行", "打印" -> {
                StringBuilder 文本 = new StringBuilder();
                for (中间代码.表达式 项 : x.实参()) {
                    文本.append(值转文本(求值(项)));
                }
                if (x.名().equals("打印行")) {
                    输出.println(文本);
                } else {
                    输出.print(文本);
                }
                return null;
            }
            case "数组复制" -> {
                List<Object> 实参 = 求值实参(x.实参());
                数组值 源 = 是数组(实参.get(0));
                数组值 目标 = 是数组(实参.get(2));
                int 源起 = 到整数(实参.get(1));
                int 目标起 = 到整数(实参.get(3));
                int 个数 = 到整数(实参.get(4));
                if (源起 < 0 || 目标起 < 0 || 个数 < 0
                        || 源起 + 个数 > 源.值.length || 目标起 + 个数 > 目标.值.length) {
                    throw new 解释器异常("「系统.数组复制」下标越界。");
                }
                System.arraycopy(源.值, 源起, 目标.值, 目标起, 个数);
                return null;
            }
            case "修剪" -> {
                return 到文本(内建接收者(x, 实参求值(x, 0))).trim();
            }
            case "长度" -> {
                return 到文本(内建接收者(x, 实参求值(x, 0))).length();
            }
            case "相等" -> {
                if (x.接收者() != null) {
                    return 到文本(求值(x.接收者())).equals(到文本(求值(x.实参().get(0))));
                }
                return 到文本(求值(x.实参().get(0))).equals(到文本(求值(x.实参().get(1))));
            }
            default -> throw new 解释器异常("M2 暂不支持：内建「" + x.名() + "」。");
        }
    }

    private Object 实参求值(中间代码.调用 x, int 下标) {
        return x.实参().isEmpty() ? null : 求值(x.实参().get(下标));
    }

    private Object 内建接收者(中间代码.调用 x, Object 兜底) {
        if (x.接收者() != null) {
            return 求值(x.接收者());
        }
        return 兜底;
    }

    private Object 新建对象(中间代码.新建对象 x) {
        中间代码.类定义 类 = 类表.get(x.类名());
        if (类 == null) {
            throw new 解释器异常("未知类「" + x.类名() + "」。");
        }
        实例 对象 = new 实例(类);
        List<Object> 实参 = 求值实参(x.实参());
        if (!类.构造器().isEmpty()) {
            中间代码.方法 构造器 = 选方法(类, x.类名(), 实参, true);
            if (构造器 == null) {
                throw new 解释器异常("类「" + 类.名() + "」没有匹配的构造器。");
            }
            调用方法(构造器, 对象, 实参);
        }
        return 对象;
    }

    private Object 新建数组(中间代码.新建数组 x) {
        int 长度 = 到整数(求值(x.长度()));
        if (长度 < 0) {
            throw new 解释器异常("数组长度不能为负数：" + 长度 + "。");
        }
        数组值 数组 = new 数组值(x.元素类型(), 长度);
        List<中间代码.表达式> 初值 = x.初值();
        for (int i = 0; i < 初值.size() && i < 长度; i++) {
            数组.值[i] = 转换(x.元素类型(), 求值(初值.get(i)));
        }
        return 数组;
    }

    private Object 调用方法(中间代码.方法 方法, 实例 这, List<Object> 实参) {
        帧 帧 = new 帧(方法, 方法.静态() ? null : 这);
        帧.作用域.push(new LinkedHashMap<>());
        帧栈.push(帧);
        try {
            if (!方法.静态()) {
                帧.作用域.peek().put("这", 这);
            }
            for (int i = 0; i < 方法.参数().size(); i++) {
                中间代码.参数 参数 = 方法.参数().get(i);
                帧.作用域.peek().put(参数.名(), 转换(参数.类型(), 实参.get(i)));
            }
            try {
                执行(方法.体());
            } catch (返回信号 信号) {
                return 信号.值;
            }
            return 默认值(方法.返回类型());
        } finally {
            帧栈.pop();
        }
    }

    private 中间代码.方法 选方法(中间代码.类定义 类, String 名, List<Object> 实参, boolean 构造器) {
        List<中间代码.方法> 候选 = 构造器 ? 类.构造器() : 类.方法();
        中间代码.方法 最佳 = null;
        int 最佳分 = Integer.MAX_VALUE;
        for (中间代码.方法 方法 : 候选) {
            if (!构造器 && !方法.名().equals(名)) {
                continue;
            }
            if (方法.参数().size() != 实参.size()) {
                continue;
            }
            int 分 = 匹配分(方法.参数(), 实参);
            if (分 >= 0 && 分 < 最佳分) {
                最佳 = 方法;
                最佳分 = 分;
            }
        }
        return 最佳;
    }

    private int 匹配分(List<中间代码.参数> 形参, List<Object> 实参) {
        int 总分 = 0;
        for (int i = 0; i < 形参.size(); i++) {
            类型 形 = 形参.get(i).类型();
            类型 实 = 值类型(实参.get(i));
            if (形.同型(实)) {
                continue;
            }
            if (形.可赋值自(实)) {
                总分++;
                continue;
            }
            return -1;
        }
        return 总分;
    }

    private static 类型 值类型(Object 值) {
        if (值 == null) {
            return 类型.空;
        }
        if (值 instanceof Integer) {
            return 类型.整数;
        }
        if (值 instanceof Long) {
            return 类型.长整数;
        }
        if (值 instanceof Short) {
            return 类型.短整数;
        }
        if (值 instanceof Byte) {
            return 类型.字节;
        }
        if (值 instanceof Float) {
            return 类型.浮点;
        }
        if (值 instanceof Double) {
            return 类型.双浮点;
        }
        if (值 instanceof Character) {
            return 类型.字符;
        }
        if (值 instanceof Boolean) {
            return 类型.布尔;
        }
        if (值 instanceof String) {
            return 类型.文本;
        }
        if (值 instanceof 实例 对象) {
            return 类型.类类型(对象.类.名());
        }
        if (值 instanceof 数组值 数组) {
            return 类型.数组(数组.元素类型);
        }
        return 类型.错误;
    }

    // ---------- 变量与赋值 ----------

    private 帧 当前() {
        return 帧栈.peek();
    }

    private void 推入() {
        当前().作用域.push(new LinkedHashMap<>());
    }

    private void 弹出() {
        当前().作用域.pop();
    }

    private Object 读变量(String 名) {
        for (Map<String, Object> 层 : 当前().作用域) {
            if (层.containsKey(名)) {
                return 层.get(名);
            }
        }
        throw new 解释器异常("找不到变量「" + 名 + "」。");
    }

    private void 写入(中间代码.表达式 左, Object 值) {
        if (左 instanceof 中间代码.变量读 x) {
            for (Map<String, Object> 层 : 当前().作用域) {
                if (层.containsKey(x.名())) {
                    层.put(x.名(), 值);
                    return;
                }
            }
            throw new 解释器异常("找不到变量「" + x.名() + "」。");
        }
        if (左 instanceof 中间代码.字段读 x) {
            Object 接收 = 求值(x.接收者());
            实例 对象 = 是实例(接收, x.名());
            if (!对象.字段.containsKey(x.名())) {
                throw new 解释器异常("类「" + 对象.类.名() + "」没有字段「" + x.名() + "」。");
            }
            对象.字段.put(x.名(), 值);
            return;
        }
        if (左 instanceof 中间代码.数组读 x) {
            Object 数组 = 求值(x.数组());
            数组值 数组对象 = 是数组(数组);
            int 下标 = 到整数(求值(x.下标()));
            if (下标 < 0 || 下标 >= 数组对象.值.length) {
                throw new 解释器异常("数组下标越界：" + 下标 + "，长度：" + 数组对象.值.length + "。");
            }
            数组对象.值[下标] = 值;
            return;
        }
        throw new 解释器异常("赋值左侧不是可写位置。");
    }

    // ---------- 类型转换 ----------

    private static Object 转换(类型 目标, Object 值) {
        if (目标 == null) {
            return 值;
        }
        if (目标.是数组() || 目标.是类()) {
            return 值;
        }
        if (目标.是空()) {
            return null;
        }
        if (目标.同型(类型.文本)) {
            return 值 instanceof String ? 值 : 值转文本(值);
        }
        if (!目标.是基本() || 值 == null) {
            return 值;
        }
        return switch (目标.基本值) {
            case 整数 -> 到整数(值);
            case 长整数 -> 到长整数(值);
            case 短整数 -> (short) 到整数(值);
            case 字节 -> (byte) 到整数(值);
            case 字符 -> (char) 到整数(值);
            case 浮点 -> 到浮点(值);
            case 双浮点 -> 到双浮点(值);
            case 布尔 -> 真值(值);
            case 无 -> null;
            case 文本 -> 值转文本(值);
        };
    }

    private static int 到整数(Object 值) {
        if (值 instanceof Integer x) {
            return x;
        }
        if (值 instanceof Long x) {
            return (int) (long) x;
        }
        if (值 instanceof Short x) {
            return x;
        }
        if (值 instanceof Byte x) {
            return x;
        }
        if (值 instanceof Character x) {
            return x;
        }
        if (值 instanceof Float x) {
            return (int) (float) x;
        }
        if (值 instanceof Double x) {
            return (int) (double) x;
        }
        if (值 instanceof Boolean x) {
            return x ? 1 : 0;
        }
        throw new 解释器异常("不能把「" + 值转文本(值) + "」转换为整数。");
    }

    private static long 到长整数(Object 值) {
        if (值 instanceof Long x) {
            return x;
        }
        if (值 instanceof Integer x) {
            return x;
        }
        if (值 instanceof Short x) {
            return x;
        }
        if (值 instanceof Byte x) {
            return x;
        }
        if (值 instanceof Character x) {
            return x;
        }
        if (值 instanceof Float x) {
            return (long) (float) x;
        }
        if (值 instanceof Double x) {
            return (long) (double) x;
        }
        throw new 解释器异常("不能把「" + 值转文本(值) + "」转换为长整数。");
    }

    private static float 到浮点(Object 值) {
        if (值 instanceof Float x) {
            return x;
        }
        if (值 instanceof Double x) {
            return (float) (double) x;
        }
        return (float) 到长整数(值);
    }

    private static double 到双浮点(Object 值) {
        if (值 instanceof Double x) {
            return x;
        }
        if (值 instanceof Float x) {
            return x;
        }
        return (double) 到长整数(值);
    }

    private static String 到文本(Object 值) {
        if (值 instanceof String x) {
            return x;
        }
        throw new 解释器异常("需要字符串，实际是「" + 值转文本(值) + "」。");
    }

    private static String 值转文本(Object 值) {
        if (值 == null) {
            return "null";
        }
        if (值 instanceof Character x) {
            return String.valueOf(x);
        }
        if (值 instanceof String x) {
            return x;
        }
        if (值 instanceof Boolean x) {
            return x ? "true" : "false";
        }
        if (值 instanceof Float x) {
            return Float.toString(x);
        }
        if (值 instanceof Double x) {
            return Double.toString(x);
        }
        if (值 instanceof 实例 对象) {
            return 对象.类.名() + "实例";
        }
        if (值 instanceof 数组值) {
            return "数组";
        }
        return String.valueOf(值);
    }

    private static Object 取负(类型 类型, Object 值) {
        if (类型.同型(类型.双浮点)) {
            return -到双浮点(值);
        }
        if (类型.同型(类型.浮点)) {
            return -到浮点(值);
        }
        if (类型.同型(类型.长整数)) {
            return -到长整数(值);
        }
        return -到整数(值);
    }

    private static Object 数值(类型 类型, Object 值) {
        if (类型.同型(类型.双浮点)) {
            return 到双浮点(值);
        }
        if (类型.同型(类型.浮点)) {
            return 到浮点(值);
        }
        if (类型.同型(类型.长整数)) {
            return 到长整数(值);
        }
        return 到整数(值);
    }

    private static boolean 真值(Object 值) {
        if (值 instanceof Boolean x) {
            return x;
        }
        throw new 解释器异常("需要布尔值，实际是「" + 值转文本(值) + "」。");
    }

    private static void 抛空指针(String 位置) {
        throw new 解释器异常("空指针：访问「" + 位置 + "」时对象为 null。");
    }

    private static 实例 是实例(Object 值, String 位置) {
        if (值 instanceof 实例 对象) {
            return 对象;
        }
        抛空指针(位置);
        return null;
    }

    private static 数组值 是数组(Object 值) {
        if (值 instanceof 数组值 数组) {
            return 数组;
        }
        throw new 解释器异常("需要数组，实际是「" + 值转文本(值) + "」。");
    }
}
