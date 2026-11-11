package 中文.原生.中间;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M4 解释器：直接执行已定型、已脱糖的中间代码。 */
public final class 解释器 {

    private final 中间代码.程序 程序;
    private final Map<String, 中间代码.类定义> 类表 = new LinkedHashMap<>();
    private final Map<String, Object> 静态字段 = new LinkedHashMap<>();
    private final Map<String, 数组值> 枚举全部 = new LinkedHashMap<>();
    private final PrintStream 输出;
    private final Deque<帧> 帧栈 = new ArrayDeque<>();
    private int 当前行;

    public 解释器(中间代码.程序 程序, PrintStream 输出) {
        this.程序 = 程序;
        this.输出 = 输出;
        for (中间代码.类定义 类 : 程序.类()) {
            类表.put(类.名(), 类);
        }
        注册内建类();
        初始化静态();
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
        try {
            调用方法(入口, null, List.of());
        } catch (脚本异常 异常) {
            输出.flush();
            String 路径 = 程序.位置() == null ? "" : 程序.位置().源路径;
            StringBuilder 消息 = new StringBuilder();
            if (!路径.isEmpty() || 当前行 > 0) {
                消息.append(路径);
                if (当前行 > 0) {
                    消息.append(':').append(当前行);
                }
                消息.append("：");
            }
            消息.append(异常.值.类.名());
            Object 内容 = 异常.值.字段.get("消息");
            if (内容 != null) {
                消息.append("：").append(内容);
            }
            System.err.println(消息);
            return 1;
        }
        输出.flush();
        return 0;
    }

    // ---------- 运行时值 ----------

    private static final class 实例 {
        final 中间代码.类定义 类;
        final Map<String, Object> 字段 = new LinkedHashMap<>();

        实例(中间代码.类定义 类, Map<String, 中间代码.类定义> 类表) {
            this.类 = 类;
            收集字段(类, 类表);
        }

        private void 收集字段(中间代码.类定义 类, Map<String, 中间代码.类定义> 类表) {
            if (类 == null) {
                return;
            }
            收集字段(父类(类, 类表), 类表);
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

    private static final class 清单值 {
        final List<Object> 项 = new ArrayList<>();
    }

    private static final class 集值 {
        final List<Object> 项 = new ArrayList<>();
    }

    private static final class 字典值 {
        final Map<Object, Object> 项 = new LinkedHashMap<>();
    }

    private static final class 构建器值 {
        final StringBuilder 内容 = new StringBuilder();
    }

    private static final class 路径值 {
        final java.nio.file.Path 路径;

        路径值(java.nio.file.Path 路径) {
            this.路径 = 路径;
        }
    }

    private static final class 可选值容器 {
        final boolean 存在;
        final Object 值;

        可选值容器(boolean 存在, Object 值) {
            this.存在 = 存在;
            this.值 = 值;
        }
    }

    private static final class 流值 {
        final List<Object> 项;

        流值(List<Object> 项) {
            this.项 = 项;
        }
    }

    private static final class 收集器值 {
        final String 种类;
        final String 分隔;

        收集器值(String 种类, String 分隔) {
            this.种类 = 种类;
            this.分隔 = 分隔;
        }
    }

    private static final class 比较器值 {
        final Object 函;
        final boolean 反序;

        比较器值(Object 函, boolean 反序) {
            this.函 = 函;
            this.反序 = 反序;
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

    private static final class 脚本异常 extends RuntimeException {
        final 实例 值;

        脚本异常(实例 值) {
            super(null, null, false, false);
            this.值 = 值;
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

    // ---------- 内建类与静态初始化 ----------

    private void 注册内建类() {
        if (类表.containsKey("对象")) {
            return;
        }
        建类("对象", null);
        建类("异常", "对象", 字段定义("文本", "消息"));
        建类("运行时异常", "异常");
        建类("算术异常", "运行时异常");
        建类("空指针异常", "运行时异常");
        建类("数组越界异常", "运行时异常");
        建类("非法参数异常", "运行时异常");
        建类("数字格式异常", "运行时异常");
        建类("类转换异常", "运行时异常");
    }

    private void 建类(String 名, String 父类, 中间代码.字段定义... 字段) {
        类表.put(名, new 中间代码.类定义(名, List.of(字段), List.of(), List.of(),
                父类 == null || 父类.equals("对象") ? null : 父类,
                List.of(), false, false, List.of(), List.of()));
    }

    private static 中间代码.字段定义 字段定义(String 类型名, String 名) {
        return new 中间代码.字段定义("文本".equals(类型名) ? 类型.文本 : 类型.类类型(类型名), 名);
    }

    private void 初始化静态() {
        中间代码.方法 初始化 = new 中间代码.方法("<静态初始化>", true, 类型.无, List.of(), null);
        帧 帧 = new 帧(初始化, null);
        帧.作用域.push(new LinkedHashMap<>());
        帧栈.push(帧);
        try {
            for (中间代码.类定义 类 : 程序.类()) {
                if (类.枚举()) {
                    初始化枚举(类);
                }
                for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                    Object 值 = 字段.初值() == null
                            ? 默认值(字段.类型())
                            : 转换(字段.类型(), 求值(字段.初值()));
                    静态字段.put(类.名() + "." + 字段.名(), 值);
                }
            }
        } finally {
            帧栈.pop();
        }
    }

    private void 初始化枚举(中间代码.类定义 类) {
        List<Object> 常量 = new ArrayList<>();
        for (中间代码.枚举常量定义 项 : 类.枚举常量()) {
            实例 对象 = new 实例(类, 类表);
            List<Object> 实参 = 求值实参(项.实参());
            if (!类.构造器().isEmpty()) {
                中间代码.方法 构造器 = 选方法(类, 实参, true);
                if (构造器 == null) {
                    throw new 解释器异常("枚举「" + 类.名() + "」没有匹配的构造器。");
                }
                调用方法(构造器, 对象, 实参);
            }
            对象.字段.put("__枚举名", 项.名());
            静态字段.put(类.名() + "." + 项.名(), 对象);
            常量.add(对象);
        }
        数组值 数组 = new 数组值(类型.类类型(类.名()), 常量.size());
        for (int i = 0; i < 常量.size(); i++) {
            数组.值[i] = 常量.get(i);
        }
        枚举全部.put(类.名(), 数组);
    }

    // ---------- 语句 ----------

    private void 执行(中间代码.语句 语句) {
        if (语句 == null) {
            return;
        }
        if (程序.位置() != null) {
            int 行 = 程序.位置().行(语句);
            if (行 > 0) {
                当前行 = 行;
            }
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
        if (语句 instanceof 中间代码.尝试语句 x) {
            脚本异常 待抛出 = null;
            try {
                try {
                    执行(x.体());
                } catch (脚本异常 异常) {
                    boolean 已捕获 = false;
                    for (中间代码.捕获子句 捕获 : x.捕获()) {
                        if (是子类型(异常.值.类.名(), 捕获.类名())) {
                            推入();
                            try {
                                当前().作用域.peek().put(捕获.变量名(), 异常.值);
                                执行(捕获.体());
                            } finally {
                                弹出();
                            }
                            已捕获 = true;
                            break;
                        }
                    }
                    if (!已捕获) {
                        待抛出 = 异常;
                    }
                }
            } finally {
                if (x.兜底() != null) {
                    执行(x.兜底());
                }
            }
            if (待抛出 != null) {
                throw 待抛出;
            }
            return;
        }
        if (语句 instanceof 中间代码.抛出语句 x) {
            Object 值 = 求值(x.值());
            if (值 == null) {
                throw 空指针异常();
            }
            if (!(值 instanceof 实例 对象)) {
                throw new 解释器异常("只能抛出异常对象。");
            }
            throw new 脚本异常(对象);
        }
        throw new 解释器异常("未知语句：" + 语句.getClass().getSimpleName());
    }

    // ---------- 表达式 ----------

    private Object 求值(中间代码.表达式 表达式) {
        if (表达式 instanceof 中间代码.顺序表达式 x) {
            推入();
            try {
                for (中间代码.语句 语句 : x.前奏()) {
                    执行(语句);
                }
                return 求值(x.结果());
            } finally {
                弹出();
            }
        }
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
                throw 数组越界(下标, 数组对象.值.length);
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
            Object 值 = 求值(x.操作数());
            if (x.类型().是类() && 值 instanceof 实例 对象
                    && !是子类型(对象.类.名(), x.类型().类名)) {
                throw new 脚本异常(新建异常("类转换异常",
                        "class " + 对象.类.名() + " cannot be cast to class " + x.类型().类名));
            }
            return 转换(x.类型(), 值);
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
        if (表达式 instanceof 中间代码.实例判断 x) {
            Object 值 = 求值(x.值());
            boolean 结果 = 值 instanceof 实例 对象 && 是子类型(对象.类.名(), x.目标类());
            if (结果 && x.绑定名() != null) {
                当前().作用域.peek().put(x.绑定名(), 值);
            }
            return 结果;
        }
        if (表达式 instanceof 中间代码.静态字段读 x) {
            return 读静态(x.类名() + "." + x.名());
        }
        if (表达式 instanceof 中间代码.枚举常量读 x) {
            return 读静态(x.枚举类() + "." + x.常量名());
        }
        if (表达式 instanceof 中间代码.枚举全部值 x) {
            数组值 数组 = 枚举全部.get(x.枚举类());
            if (数组 == null) {
                throw new 解释器异常("枚举「" + x.枚举类() + "」没有常量。");
            }
            return 数组;
        }
        if (表达式 instanceof 中间代码.枚举按名取值 x) {
            String 名 = 到文本(求值(x.名()));
            Object 值 = 静态字段.get(x.枚举类() + "." + 名);
            if (值 == null) {
                throw new 脚本异常(新建异常("非法参数异常",
                        "No enum constant " + x.枚举类() + "." + 名));
            }
            return 值;
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
                case "/" -> a / 除零检查(b);
                case "%" -> a % 除零检查(b);
                default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
            };
        }
        int a = 到整数(左);
        int b = 到整数(右);
        return switch (运算符) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / 除零检查(b);
            case "%" -> a % 除零检查(b);
            default -> throw new 解释器异常("未知运算符「" + 运算符 + "」。");
        };
    }

    private int 除零检查(int 除数) {
        if (除数 == 0) {
            throw new 脚本异常(新建异常("算术异常", "/ by zero"));
        }
        return 除数;
    }

    private long 除零检查(long 除数) {
        if (除数 == 0) {
            throw new 脚本异常(新建异常("算术异常", "/ by zero"));
        }
        return 除数;
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
            中间代码.类定义 类 = 类表.get(x.类名());
            if (类 == null) {
                throw new 解释器异常("未知类「" + x.类名() + "」。");
            }
            中间代码.方法 构造器 = 选方法(类, 实参, true);
            if (构造器 != null) {
                调用方法(构造器, 对象, 实参);
            }
            return null;
        }
        if (x.分派() == 中间代码.分派.静态) {
            中间代码.类定义 类 = 类表.get(x.类名());
            if (类 == null) {
                throw new 解释器异常("未知类「" + x.类名() + "」。");
            }
            中间代码.方法 方法 = 查找方法(类, x.名(), 实参);
            if (方法 == null) {
                throw new 解释器异常("类「" + 类.名() + "」没有匹配的方法「" + x.名() + "」。");
            }
            return 调用方法(方法, null, 实参);
        }
        if (x.分派() == 中间代码.分派.父方法) {
            Object 接收 = 求值(x.接收者());
            实例 对象 = 是实例(接收, x.名());
            中间代码.类定义 类 = 类表.get(x.类名());
            中间代码.方法 方法 = 类 == null ? null : 选方法(类, x.名(), 实参, false);
            if (方法 == null) {
                throw new 解释器异常("父类「" + x.类名() + "」没有方法「" + x.名() + "」。");
            }
            return 调用方法(方法, 对象, 实参);
        }
        Object 接收 = 求值(x.接收者());
        实例 对象 = 是实例(接收, x.名());
        if (对象.类.名().endsWith("异常") && x.名().equals("取消息")) {
            return 对象.字段.get("消息");
        }
        中间代码.方法 方法 = 查找方法(对象.类, x.名(), 实参);
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
            case "空操作" -> {
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
                return 文本接收者(x, 实参求值(x, 0)).trim();
            }
            case "长度" -> {
                return 文本接收者(x, 实参求值(x, 0)).length();
            }
            case "是空" -> {
                return 文本接收者(x, 实参求值(x, 0)).isEmpty();
            }
            case "相等" -> {
                String 左;
                Object 右值;
                if (x.接收者() != null) {
                    左 = 文本接收者(x, null);
                    右值 = 求值(x.实参().get(0));
                } else {
                    左 = 到文本(求值(x.实参().get(0)));
                    右值 = 求值(x.实参().get(1));
                }
                return 左.equals(右值 instanceof String s ? s : null);
            }
            case "平方根" -> {
                return Math.sqrt(到双浮点(求值(x.实参().get(0))));
            }
            case "整数解析" -> {
                String 文本 = 到文本(求值(x.实参().get(0)));
                try {
                    return Integer.parseInt(文本.trim());
                } catch (NumberFormatException 异常) {
                    throw new 脚本异常(新建异常("数字格式异常",
                            "For input string: \"" + 文本 + "\""));
                }
            }
            case "异常取消息" -> {
                Object 接收 = x.接收者() == null ? 实参求值(x, 0) : 求值(x.接收者());
                实例 对象 = 是实例(接收, "异常");
                return 对象.字段.get("消息");
            }
            case "程序参数" -> {
                return new 数组值(类型.文本, 0);
            }
            case "值转文本" -> {
                return 值转文本(求值(x.实参().get(0)));
            }
            case "值相等" -> {
                return 值相等(求值(x.实参().get(0)), 求值(x.实参().get(1)));
            }
            case "值哈希码" -> {
                return 值哈希码(求值(x.实参().get(0)));
            }
            case "新建清单", "清单从" -> {
                List<Object> 实参 = 求值实参(x.实参());
                清单值 容器 = new 清单值();
                容器.项.addAll(实参);
                return 容器;
            }
            case "清单加入" -> {
                清单值 容器 = 是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者()));
                int 起点 = x.接收者() == null ? 1 : 0;
                容器.项.add(求值(x.实参().get(起点)));
                return true;
            }
            case "清单取出" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                return 容器.项.get(到整数(求值(x.实参().get(0))));
            }
            case "清单设定" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                return 容器.项.set(到整数(求值(x.实参().get(0))),
                        求值(x.实参().get(1)));
            }
            case "清单移除下标" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                return 容器.项.remove(到整数(求值(x.实参().get(0))));
            }
            case "清单移除值" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (int i = 0; i < 容器.项.size(); i++) {
                    if (值相等(容器.项.get(i), 目标)) {
                        容器.项.remove(i);
                        return true;
                    }
                }
                return false;
            }
            case "清单找下标" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (int i = 0; i < 容器.项.size(); i++) {
                    if (值相等(容器.项.get(i), 目标)) {
                        return i;
                    }
                }
                return -1;
            }
            case "清单元素数" -> {
                return 是清单(求值(x.接收者())).项.size();
            }
            case "清单是空" -> {
                return 是清单(求值(x.接收者())).项.isEmpty();
            }
            case "清单清空" -> {
                是清单(求值(x.接收者())).项.clear();
                return null;
            }
            case "清单含有" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (Object 项 : 容器.项) {
                    if (值相等(项, 目标)) {
                        return true;
                    }
                }
                return false;
            }
            case "清单整理" -> {
                清单值 容器 = 是清单(求值(x.接收者()));
                if (x.实参().isEmpty()) {
                    容器.项.sort(this::自然比较);
                } else {
                    Object 比较器 = 求值(x.实参().get(0));
                    容器.项.sort((甲, 乙) -> 比较两个(比较器, 甲, 乙));
                }
                return null;
            }
            case "清单流" -> {
                return new 流值(new ArrayList<>(是清单(求值(x.接收者())).项));
            }
            case "清单值集合" -> {
                集值 结果 = new 集值();
                for (Object 项 : 是清单(求值(x.接收者())).项) {
                    集加入(结果, 项);
                }
                return 结果;
            }
            case "新建集", "集从" -> {
                集值 容器 = new 集值();
                for (Object 项 : 求值实参(x.实参())) {
                    集加入(容器, 项);
                }
                return 容器;
            }
            case "集加入" -> {
                return 集加入(是集(求值(x.接收者())), 求值(x.实参().get(0)));
            }
            case "集移除" -> {
                集值 容器 = 是集(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (int i = 0; i < 容器.项.size(); i++) {
                    if (值相等(容器.项.get(i), 目标)) {
                        容器.项.remove(i);
                        return true;
                    }
                }
                return false;
            }
            case "集含有" -> {
                集值 容器 = 是集(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (Object 项 : 容器.项) {
                    if (值相等(项, 目标)) {
                        return true;
                    }
                }
                return false;
            }
            case "集元素数" -> {
                return 是集(求值(x.接收者())).项.size();
            }
            case "集是空" -> {
                return 是集(求值(x.接收者())).项.isEmpty();
            }
            case "集清空" -> {
                是集(求值(x.接收者())).项.clear();
                return null;
            }
            case "集取出" -> {
                return 是集(求值(x.接收者())).项.get(到整数(求值(x.实参().get(0))));
            }
            case "集流" -> {
                return new 流值(new ArrayList<>(是集(求值(x.接收者())).项));
            }
            case "新建字典", "字典从" -> {
                字典值 容器 = new 字典值();
                List<Object> 实参 = 求值实参(x.实参());
                for (int i = 0; i + 1 < 实参.size(); i += 2) {
                    容器.项.put(实参.get(i), 实参.get(i + 1));
                }
                return 容器;
            }
            case "字典放入" -> {
                字典值 容器 = 是字典(求值(x.接收者()));
                return 容器.项.put(求值(x.实参().get(0)), 求值(x.实参().get(1)));
            }
            case "字典取出" -> {
                return 是字典(求值(x.接收者())).项.get(求值(x.实参().get(0)));
            }
            case "字典取出或" -> {
                字典值 容器 = 是字典(求值(x.接收者()));
                Object 键 = 求值(x.实参().get(0));
                return 容器.项.containsKey(键) ? 容器.项.get(键)
                        : 求值(x.实参().get(1));
            }
            case "字典移除" -> {
                return 是字典(求值(x.接收者())).项.remove(求值(x.实参().get(0)));
            }
            case "字典键集合" -> {
                集值 结果 = new 集值();
                for (Object 键 : 是字典(求值(x.接收者())).项.keySet()) {
                    集加入(结果, 键);
                }
                return 结果;
            }
            case "字典值集合" -> {
                集值 结果 = new 集值();
                for (Object 值 : 是字典(求值(x.接收者())).项.values()) {
                    集加入(结果, 值);
                }
                return 结果;
            }
            case "字典含键" -> {
                return 是字典(求值(x.接收者())).项.containsKey(求值(x.实参().get(0)));
            }
            case "字典含值" -> {
                字典值 容器 = 是字典(求值(x.接收者()));
                Object 目标 = 求值(x.实参().get(0));
                for (Object 值 : 容器.项.values()) {
                    if (值相等(值, 目标)) {
                        return true;
                    }
                }
                return false;
            }
            case "字典元素数" -> {
                return 是字典(求值(x.接收者())).项.size();
            }
            case "字典是空" -> {
                return 是字典(求值(x.接收者())).项.isEmpty();
            }
            case "字典清空" -> {
                是字典(求值(x.接收者())).项.clear();
                return null;
            }
            case "字典流" -> {
                return new 流值(new ArrayList<>(是字典(求值(x.接收者())).项.values()));
            }
            case "字典遍历" -> {
                字典值 容器 = 是字典(求值(x.接收者()));
                Object 函数 = 求值(x.实参().get(0));
                for (Map.Entry<Object, Object> 项 : 容器.项.entrySet()) {
                    调用函数(函数, 双值(项.getKey(), 项.getValue()));
                }
                return null;
            }
            case "新建构建器" -> {
                构建器值 容器 = new 构建器值();
                for (Object 项 : 求值实参(x.实参())) {
                    容器.内容.append(值转文本(项));
                }
                return 容器;
            }
            case "构建器追加" -> {
                构建器值 容器 = 是构建器(求值(x.接收者()));
                for (Object 项 : 求值实参(x.实参())) {
                    容器.内容.append(值转文本(项));
                }
                return 容器;
            }
            case "构建器转文本" -> {
                return 是构建器(求值(x.接收者())).内容.toString();
            }
            case "构建器长度" -> {
                return 是构建器(求值(x.接收者())).内容.length();
            }
            case "构建器清空" -> {
                是构建器(求值(x.接收者())).内容.setLength(0);
                return null;
            }
            case "路径取" -> {
                List<Object> 实参 = 求值实参(x.实参());
                String[] 段 = new String[实参.size()];
                for (int i = 0; i < 实参.size(); i++) {
                    段[i] = 值转文本(实参.get(i));
                }
                return new 路径值(java.nio.file.Path.of(段[0],
                        java.util.Arrays.copyOfRange(段, 1, 段.length)));
            }
            case "路径上级" -> {
                路径值 路径 = 是路径(求值(x.接收者()));
                java.nio.file.Path 上级 = 路径.路径.getParent();
                return 上级 == null ? null : new 路径值(上级);
            }
            case "路径绝对" -> {
                return new 路径值(是路径(求值(x.接收者())).路径.toAbsolutePath());
            }
            case "路径文件名" -> {
                java.nio.file.Path 名 = 是路径(求值(x.接收者())).路径.getFileName();
                return 名 == null ? null : 名.toString();
            }
            case "路径转文本" -> {
                return 是路径(求值(x.接收者())).路径.toString();
            }
            case "文件读文本" -> {
                Object 接收 = x.接收者() == null ? 实参求值(x, 0) : 求值(x.接收者());
                try {
                    return java.nio.file.Files.readString(是路径(接收).路径);
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件写文本" -> {
                List<Object> 实参 = 求值实参(x.实参());
                try {
                    java.nio.file.Files.writeString(是路径(实参.get(0)).路径,
                            值转文本(实参.get(1)));
                    return 实参.get(0);
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件建目录" -> {
                try {
                    return new 路径值(java.nio.file.Files.createDirectories(
                            是路径(实参求值(x, 0)).路径));
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件读行" -> {
                try {
                    清单值 结果 = new 清单值();
                    结果.项.addAll(java.nio.file.Files.readAllLines(
                            是路径(实参求值(x, 0)).路径));
                    return 结果;
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件存在" -> {
                return java.nio.file.Files.exists(是路径(实参求值(x, 0)).路径);
            }
            case "文件大小" -> {
                try {
                    return java.nio.file.Files.size(是路径(实参求值(x, 0)).路径);
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件删除" -> {
                try {
                    return java.nio.file.Files.deleteIfExists(是路径(实参求值(x, 0)).路径);
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件列举" -> {
                try {
                    清单值 结果 = new 清单值();
                    try (java.util.stream.Stream<java.nio.file.Path> 流 =
                                 java.nio.file.Files.list(是路径(实参求值(x, 0)).路径)) {
                        for (java.nio.file.Path 项 : 流.toList()) {
                            结果.项.add(new 路径值(项));
                        }
                    }
                    return 结果;
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件复制" -> {
                List<Object> 实参 = 求值实参(x.实参());
                try {
                    return new 路径值(java.nio.file.Files.copy(是路径(实参.get(0)).路径,
                            是路径(实参.get(1)).路径,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING));
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "文件移动" -> {
                List<Object> 实参 = 求值实参(x.实参());
                try {
                    return new 路径值(java.nio.file.Files.move(是路径(实参.get(0)).路径,
                            是路径(实参.get(1)).路径,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING));
                } catch (java.io.IOException 异常) {
                    throw new 脚本异常(新建异常("输入输出异常", 异常.getMessage()));
                }
            }
            case "可选值空" -> {
                return new 可选值容器(false, null);
            }
            case "可选值装入", "可选值可空" -> {
                Object 值 = 求值(x.实参().get(0));
                return x.名().equals("可选值可空") && 值 == null
                        ? new 可选值容器(false, null) : new 可选值容器(true, 值);
            }
            case "可选值或" -> {
                可选值容器 容器 = 是可选值(求值(x.接收者()));
                return 容器.存在 ? 容器.值 : 求值(x.实参().get(0));
            }
            case "可选值是空" -> {
                return !是可选值(求值(x.接收者())).存在;
            }
            case "可选值存在" -> {
                return 是可选值(求值(x.接收者())).存在;
            }
            case "可选值取出" -> {
                可选值容器 容器 = 是可选值(求值(x.接收者()));
                if (!容器.存在) {
                    throw new 脚本异常(新建异常("非法参数异常", "可选值不存在。"));
                }
                return 容器.值;
            }
            case "可选值存在时" -> {
                可选值容器 容器 = 是可选值(求值(x.接收者()));
                if (!容器.存在) {
                    return null;
                }
                return 调用函数(求值(x.实参().get(0)), 单值(容器.值));
            }
            case "流过滤" -> {
                流值 流 = 是流(求值(x.接收者()));
                Object 函数 = 求值(x.实参().get(0));
                List<Object> 结果 = new ArrayList<>();
                for (Object 项 : 流.项) {
                    if (真值(调用函数(函数, 单值(项)))) {
                        结果.add(项);
                    }
                }
                return new 流值(结果);
            }
            case "流变换" -> {
                流值 流 = 是流(求值(x.接收者()));
                Object 函数 = 求值(x.实参().get(0));
                List<Object> 结果 = new ArrayList<>();
                for (Object 项 : 流.项) {
                    结果.add(调用函数(函数, 单值(项)));
                }
                return new 流值(结果);
            }
            case "流遍历" -> {
                流值 流 = 是流(求值(x.接收者()));
                Object 函数 = 求值(x.实参().get(0));
                for (Object 项 : 流.项) {
                    调用函数(函数, 单值(项));
                }
                return null;
            }
            case "流收集清单" -> {
                流值 流 = 是流(求值(x.接收者()));
                清单值 结果 = new 清单值();
                结果.项.addAll(流.项);
                return 结果;
            }
            case "流收集集" -> {
                流值 流 = 是流(求值(x.接收者()));
                集值 结果 = new 集值();
                for (Object 项 : 流.项) {
                    集加入(结果, 项);
                }
                return 结果;
            }
            case "流收集拼接" -> {
                流值 流 = 是流(求值(x.接收者()));
                Object 收集器 = 求值(x.实参().get(0));
                String 分隔 = 收集器 instanceof 收集器值 c && c.分隔 != null
                        ? c.分隔 : "";
                StringBuilder 结果 = new StringBuilder();
                for (int i = 0; i < 流.项.size(); i++) {
                    if (i > 0) {
                        结果.append(分隔);
                    }
                    结果.append(值转文本(流.项.get(i)));
                }
                return 结果.toString();
            }
            case "流找首个" -> {
                流值 流 = 是流(求值(x.接收者()));
                return 流.项.isEmpty() ? new 可选值容器(false, null)
                        : new 可选值容器(true, 流.项.get(0));
            }
            case "流元素数" -> {
                return 是流(求值(x.接收者())).项.size();
            }
            case "流去重" -> {
                流值 流 = 是流(求值(x.接收者()));
                List<Object> 结果 = new ArrayList<>();
                for (Object 项 : 流.项) {
                    boolean 已有 = false;
                    for (Object 已 : 结果) {
                        if (值相等(已, 项)) {
                            已有 = true;
                            break;
                        }
                    }
                    if (!已有) {
                        结果.add(项);
                    }
                }
                return new 流值(结果);
            }
            case "流排序" -> {
                流值 流 = 是流(求值(x.接收者()));
                List<Object> 结果 = new ArrayList<>(流.项);
                结果.sort(this::自然比较);
                return new 流值(结果);
            }
            case "收集器清单" -> {
                return new 收集器值("清单", null);
            }
            case "收集器集" -> {
                return new 收集器值("集", null);
            }
            case "收集器拼接" -> {
                return new 收集器值("拼接", 值转文本(求值(x.实参().get(0))));
            }
            case "比较器按整数" -> {
                return new 比较器值(求值(x.实参().get(0)), false);
            }
            case "比较器反序" -> {
                比较器值 原 = 是比较器(求值(x.接收者()));
                return new 比较器值(原.函, !原.反序);
            }
            case "比较器比较" -> {
                比较器值 比较器 = 是比较器(求值(x.接收者()));
                return 比较两个(比较器, 求值(x.实参().get(0)), 求值(x.实参().get(1)));
            }
            case "绝对值" -> {
                return 绝对值(求值(x.实参().get(0)));
            }
            case "最大值" -> {
                return 数值较大(求值(x.实参().get(0)), 求值(x.实参().get(1)));
            }
            case "最小值" -> {
                return 数值较小(求值(x.实参().get(0)), 求值(x.实参().get(1)));
            }
            case "幂" -> {
                return Math.pow(到双浮点(求值(x.实参().get(0))),
                        到双浮点(求值(x.实参().get(1))));
            }
            case "随机" -> {
                return Math.random();
            }
            case "向下取整" -> {
                return Math.floor(到双浮点(求值(x.实参().get(0))));
            }
            case "向上取整" -> {
                return Math.ceil(到双浮点(求值(x.实参().get(0))));
            }
            case "四舍五入" -> {
                return Math.round(到双浮点(求值(x.实参().get(0))));
            }
            case "圆周率" -> {
                return Math.PI;
            }
            case "自然常数" -> {
                return Math.E;
            }
            case "集合排序" -> {
                清单值 容器 = 是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者()));
                容器.项.sort(this::自然比较);
                return null;
            }
            case "集合反序" -> {
                清单值 容器 = 是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者()));
                java.util.Collections.reverse(容器.项);
                return null;
            }
            case "集合打乱" -> {
                清单值 容器 = 是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者()));
                java.util.Collections.shuffle(容器.项);
                return null;
            }
            case "集合最大" -> {
                return 集合极值(是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者())), true);
            }
            case "集合最小" -> {
                return 集合极值(是清单(x.接收者() == null ? 实参求值(x, 0)
                        : 求值(x.接收者())), false);
            }
            case "整数值" -> {
                return 到整数(求值(x.实参().get(0)));
            }
            case "包装比较" -> {
                Object 左 = 求值(x.接收者());
                Object 右 = 求值(x.实参().get(0));
                return 自然比较(左, 右);
            }
            case "包装取值" -> {
                return 求值(x.接收者());
            }
            case "长整数解析" -> {
                String 文本 = 到文本(求值(x.实参().get(0)));
                try {
                    return Long.parseLong(文本.trim());
                } catch (NumberFormatException 异常) {
                    throw new 脚本异常(新建异常("数字格式异常",
                            "For input string: \"" + 文本 + "\""));
                }
            }
            case "双浮点解析" -> {
                String 文本 = 到文本(求值(x.实参().get(0)));
                try {
                    return Double.parseDouble(文本.trim());
                } catch (NumberFormatException 异常) {
                    throw new 脚本异常(新建异常("数字格式异常",
                            "For input string: \"" + 文本 + "\""));
                }
            }
            case "是空白" -> {
                return 文本接收者(x, null).isBlank();
            }
            case "转大写" -> {
                return 文本接收者(x, null).toUpperCase(java.util.Locale.ROOT);
            }
            case "转小写" -> {
                return 文本接收者(x, null).toLowerCase(java.util.Locale.ROOT);
            }
            case "取字符" -> {
                return 文本接收者(x, null).charAt(到整数(实参求值(x, 0)));
            }
            case "含有" -> {
                return 文本接收者(x, null).contains(到文本(实参求值(x, 0)));
            }
            case "以开头" -> {
                return 文本接收者(x, null).startsWith(到文本(实参求值(x, 0)));
            }
            case "以结尾" -> {
                return 文本接收者(x, null).endsWith(到文本(实参求值(x, 0)));
            }
            case "替换" -> {
                return 文本接收者(x, null).replace(到文本(求值(x.实参().get(0))),
                        到文本(求值(x.实参().get(1))));
            }
            case "取子串" -> {
                String 源 = 文本接收者(x, null);
                int 起 = 到整数(求值(x.实参().get(0)));
                return x.实参().size() == 1 ? 源.substring(起)
                        : 源.substring(起, 到整数(求值(x.实参().get(1))));
            }
            case "拆分" -> {
                String[] 段 = 文本接收者(x, null).split(
                        java.util.regex.Pattern.quote(到文本(实参求值(x, 0))));
                数组值 结果 = new 数组值(类型.文本, 段.length);
                for (int i = 0; i < 段.length; i++) {
                    结果.值[i] = 段[i];
                }
                return 结果;
            }
            case "复读" -> {
                return 文本接收者(x, null).repeat(到整数(实参求值(x, 0)));
            }
            case "字符串拼接" -> {
                Object 分隔 = 求值(x.实参().get(0));
                数组值 部分 = 是数组(求值(x.实参().get(1)));
                StringBuilder 结果 = new StringBuilder();
                for (int i = 0; i < 部分.值.length; i++) {
                    if (i > 0) {
                        结果.append(值转文本(分隔));
                    }
                    结果.append(值转文本(部分.值[i]));
                }
                return 结果.toString();
            }
            case "字符串比较" -> {
                String 左 = x.接收者() == null ? 到文本(求值(x.实参().get(0)))
                        : 文本接收者(x, null);
                String 右 = x.接收者() == null ? 到文本(求值(x.实参().get(1)))
                        : 到文本(求值(x.实参().get(0)));
                return 左.compareTo(右);
            }
            default -> throw new 解释器异常("M5 暂不支持：内建「" + x.名() + "」。");
        }
    }

    private static 清单值 是清单(Object 值) {
        if (值 instanceof 清单值 容器) {
            return 容器;
        }
        throw new 解释器异常("期望清单，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 集值 是集(Object 值) {
        if (值 instanceof 集值 容器) {
            return 容器;
        }
        throw new 解释器异常("期望集，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 字典值 是字典(Object 值) {
        if (值 instanceof 字典值 容器) {
            return 容器;
        }
        throw new 解释器异常("期望键值表，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 构建器值 是构建器(Object 值) {
        if (值 instanceof 构建器值 容器) {
            return 容器;
        }
        throw new 解释器异常("期望文本构建器，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 路径值 是路径(Object 值) {
        if (值 instanceof 路径值 路径) {
            return 路径;
        }
        throw new 解释器异常("期望文件路径，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 可选值容器 是可选值(Object 值) {
        if (值 instanceof 可选值容器 容器) {
            return 容器;
        }
        throw new 解释器异常("期望可选值，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 流值 是流(Object 值) {
        if (值 instanceof 流值 流) {
            return 流;
        }
        throw new 解释器异常("期望流，实际为「" + 值转文本静态(值) + "」。");
    }

    private static 比较器值 是比较器(Object 值) {
        if (值 instanceof 比较器值 比较器) {
            return 比较器;
        }
        throw new 解释器异常("期望比较器，实际为「" + 值转文本静态(值) + "」。");
    }

    private boolean 集加入(集值 容器, Object 项) {
        for (Object 已 : 容器.项) {
            if (值相等静态(已, 项)) {
                return false;
            }
        }
        容器.项.add(项);
        return true;
    }

    private static List<Object> 单值(Object 值) {
        List<Object> 结果 = new ArrayList<>();
        结果.add(值);
        return 结果;
    }

    private Object 调用函数(Object 函, List<Object> 实参) {
        实例 对象 = 是实例(函, "函数");
        中间代码.方法 方法 = null;
        for (中间代码.方法 候选 : 对象.类.方法()) {
            if (候选.名().equals("调用") && 候选.参数().size() == 实参.size()) {
                方法 = 候选;
                break;
            }
        }
        if (方法 == null) {
            throw new 解释器异常("函数对象「" + 对象.类.名() + "」没有「调用」方法。");
        }
        return 调用方法(方法, 对象, 实参);
    }

    private int 比较两个(Object 比较器, Object 甲, Object 乙) {
        if (比较器 instanceof 比较器值 值) {
            int 左 = 到整数(调用函数(值.函, 单值(甲)));
            int 右 = 到整数(调用函数(值.函, 单值(乙)));
            int 结果 = Integer.compare(左, 右);
            return 值.反序 ? -结果 : 结果;
        }
        return 自然比较(甲, 乙);
    }

    private static List<Object> 双值(Object 甲, Object 乙) {
        List<Object> 结果 = new ArrayList<>();
        结果.add(甲);
        结果.add(乙);
        return 结果;
    }

    private int 自然比较(Object 甲, Object 乙) {
        if (甲 instanceof String 左 && 乙 instanceof String 右) {
            return Integer.compare(左.compareTo(右), 0);
        }
        if (甲 instanceof Boolean 左 && 乙 instanceof Boolean 右) {
            return Boolean.compare(左, 右);
        }
        if (甲 instanceof Character 左 && 乙 instanceof Character 右) {
            return Character.compare(左, 右);
        }
        if (甲 instanceof Double || 甲 instanceof Float
                || 乙 instanceof Double || 乙 instanceof Float) {
            return Double.compare(到双浮点(甲), 到双浮点(乙));
        }
        return Long.compare(到长整数(甲), 到长整数(乙));
    }

    private Object 集合极值(清单值 容器, boolean 取大) {
        if (容器.项.isEmpty()) {
            throw new 脚本异常(新建异常("非法参数异常", "集合为空。"));
        }
        Object 结果 = 容器.项.get(0);
        for (int i = 1; i < 容器.项.size(); i++) {
            int 比较 = 自然比较(容器.项.get(i), 结果);
            if (取大 ? 比较 > 0 : 比较 < 0) {
                结果 = 容器.项.get(i);
            }
        }
        return 结果;
    }

    private Object 绝对值(Object 值) {
        if (值 instanceof Double d) {
            return Math.abs(d);
        }
        if (值 instanceof Float f) {
            return Math.abs(f);
        }
        if (值 instanceof Long l) {
            return Math.abs(l);
        }
        return Math.abs(到整数(值));
    }

    private Object 数值较大(Object 甲, Object 乙) {
        if (甲 instanceof Double || 甲 instanceof Float
                || 乙 instanceof Double || 乙 instanceof Float) {
            return Math.max(到双浮点(甲), 到双浮点(乙));
        }
        if (甲 instanceof Long || 乙 instanceof Long) {
            return Math.max(到长整数(甲), 到长整数(乙));
        }
        return Math.max(到整数(甲), 到整数(乙));
    }

    private Object 数值较小(Object 甲, Object 乙) {
        if (甲 instanceof Double || 甲 instanceof Float
                || 乙 instanceof Double || 乙 instanceof Float) {
            return Math.min(到双浮点(甲), 到双浮点(乙));
        }
        if (甲 instanceof Long || 乙 instanceof Long) {
            return Math.min(到长整数(甲), 到长整数(乙));
        }
        return Math.min(到整数(甲), 到整数(乙));
    }

    private boolean 值相等(Object 甲, Object 乙) {
        if (甲 == 乙) {
            return true;
        }
        if (甲 == null || 乙 == null) {
            return false;
        }
        if (甲 instanceof 实例 对象) {
            中间代码.方法 方法 = 查找方法(对象.类, "等于", 单值(乙));
            if (方法 != null) {
                return 真值(调用方法(方法, 对象, 单值(乙)));
            }
            return false;
        }
        return 值相等静态(甲, 乙);
    }

    private boolean 值相等静态(Object 甲, Object 乙) {
        if (甲 == 乙) {
            return true;
        }
        if (甲 == null || 乙 == null) {
            return false;
        }
        if (甲 instanceof Double || 甲 instanceof Float
                || 乙 instanceof Double || 乙 instanceof Float) {
            return 到双浮点(甲) == 到双浮点(乙);
        }
        if (甲 instanceof Number && 乙 instanceof Number) {
            return 到长整数(甲) == 到长整数(乙);
        }
        return 甲.equals(乙);
    }

    private int 值哈希码(Object 值) {
        if (值 == null) {
            return 0;
        }
        if (值 instanceof 实例 对象) {
            中间代码.方法 方法 = 查找方法(对象.类, "哈希码", List.of());
            if (方法 != null) {
                return 到整数(调用方法(方法, 对象, List.of()));
            }
            return System.identityHashCode(值);
        }
        if (值 instanceof String s) {
            return s.hashCode();
        }
        if (值 instanceof Integer i) {
            return i;
        }
        if (值 instanceof Long l) {
            return Long.hashCode(l);
        }
        if (值 instanceof Short s) {
            return (int) s;
        }
        if (值 instanceof Byte b) {
            return (int) b;
        }
        if (值 instanceof Character c) {
            return (int) c;
        }
        if (值 instanceof Boolean b) {
            return b ? 1231 : 1237;
        }
        if (值 instanceof Double d) {
            return Double.hashCode(d);
        }
        if (值 instanceof Float f) {
            return Float.hashCode(f);
        }
        return 值.hashCode();
    }

    private static String 值转文本静态(Object 值) {
        return 值 == null ? "null" : 值.toString();
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

    /** 文本类内建方法的接收者：为空时按 JVM 语义抛空指针异常。 */
    private String 文本接收者(中间代码.调用 x, Object 兜底) {
        Object 值 = 内建接收者(x, 兜底);
        if (值 == null) {
            throw 空指针异常();
        }
        return 到文本(值);
    }

    private Object 新建对象(中间代码.新建对象 x) {
        中间代码.类定义 类 = 类表.get(x.类名());
        if (类 == null) {
            throw new 解释器异常("未知类「" + x.类名() + "」。");
        }
        实例 对象 = new 实例(类, 类表);
        List<Object> 实参 = 求值实参(x.实参());
        if (类.名().endsWith("异常") && 类.方法().isEmpty() && 类.构造器().isEmpty()) {
            对象.字段.put("消息", 实参.isEmpty() ? null : 到文本(实参.get(0)));
            return 对象;
        }
        if (!类.构造器().isEmpty()) {
            中间代码.方法 构造器 = 选方法(类, 实参, true);
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
            throw new 脚本异常(新建异常("数组越界异常", "数组长度为负数：" + 长度));
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

    private 中间代码.方法 查找方法(中间代码.类定义 类, String 名, List<Object> 实参) {
        for (中间代码.类定义 当前 = 类; 当前 != null; 当前 = 父类(当前, 类表)) {
            中间代码.方法 方法 = 选方法(当前, 名, 实参, false);
            if (方法 != null) {
                return 方法;
            }
        }
        return null;
    }

    private 中间代码.方法 选方法(中间代码.类定义 类, List<Object> 实参, boolean 构造器) {
        if (类 == null) {
            return null;
        }
        List<中间代码.方法> 候选 = 构造器 ? 类.构造器() : 类.方法();
        中间代码.方法 最佳 = null;
        int 最佳分 = Integer.MAX_VALUE;
        for (中间代码.方法 方法 : 候选) {
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

    private 中间代码.方法 选方法(中间代码.类定义 类, String 名, List<Object> 实参, boolean 构造器) {
        中间代码.方法 最佳 = null;
        int 最佳分 = Integer.MAX_VALUE;
        for (中间代码.方法 方法 : 类.方法()) {
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
            if (可赋值(形, 实)) {
                总分++;
                continue;
            }
            return -1;
        }
        return 总分;
    }

    private boolean 可赋值(类型 目标, 类型 源) {
        if (目标.是类() && 目标.类名.equals("__值")) {
            return true;
        }
        if (目标.可赋值自(源)) {
            return true;
        }
        return 目标.是类() && 源.是类() && 是子类型(源.类名, 目标.类名);
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

    // ---------- 类型关系 ----------

    private static 中间代码.类定义 父类(中间代码.类定义 类,
                                     Map<String, 中间代码.类定义> 类表) {
        if (类 == null || 类.名().equals("对象")) {
            return null;
        }
        String 名 = 类.父类() == null ? "对象" : 类.父类();
        return 类表.get(名);
    }

    private boolean 是子类型(String 子, String 父) {
        if (子 == null || 父 == null) {
            return false;
        }
        if (子.equals(父)) {
            return true;
        }
        if (子.equals("对象")) {
            return false;
        }
        中间代码.类定义 类 = 类表.get(子);
        if (类 == null) {
            return false;
        }
        for (String 接口名 : 类.接口()) {
            if (是子类型(接口名, 父)) {
                return true;
            }
        }
        String 上级 = 类.父类() == null ? "对象" : 类.父类();
        if (父.equals(上级)) {
            return true;
        }
        return 是子类型(上级, 父);
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

    private Object 读静态(String 键) {
        if (!静态字段.containsKey(键)) {
            throw new 解释器异常("找不到静态字段「" + 键 + "」。");
        }
        return 静态字段.get(键);
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
        if (左 instanceof 中间代码.静态字段读 x) {
            静态字段.put(x.类名() + "." + x.名(), 值);
            return;
        }
        if (左 instanceof 中间代码.数组读 x) {
            Object 数组 = 求值(x.数组());
            数组值 数组对象 = 是数组(数组);
            int 下标 = 到整数(求值(x.下标()));
            if (下标 < 0 || 下标 >= 数组对象.值.length) {
                throw 数组越界(下标, 数组对象.值.length);
            }
            数组对象.值[下标] = 值;
            return;
        }
        throw new 解释器异常("赋值左侧不是可写位置。");
    }

    // ---------- 异常 ----------

    private 实例 新建异常(String 类名, String 消息) {
        中间代码.类定义 类 = 类表.get(类名);
        if (类 == null) {
            类 = 类表.get("运行时异常");
        }
        实例 对象 = new 实例(类, 类表);
        对象.字段.put("消息", 消息);
        return 对象;
    }

    private 脚本异常 空指针异常() {
        return new 脚本异常(新建异常("空指针异常", null));
    }

    private 脚本异常 数组越界(int 下标, int 长度) {
        return new 脚本异常(新建异常("数组越界异常",
                "Index " + 下标 + " out of bounds for length " + 长度));
    }

    // ---------- 类型转换 ----------

    private Object 转换(类型 目标, Object 值) {
        if (目标 == null) {
            return 值;
        }
        if (目标.是数组() || 目标.是类()) {
            return 值;
        }
        if (目标.是空()) {
            return null;
        }
        if (值 == null) {
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

    private int 到整数(Object 值) {
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

    private long 到长整数(Object 值) {
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

    private float 到浮点(Object 值) {
        if (值 instanceof Float x) {
            return x;
        }
        if (值 instanceof Double x) {
            return (float) (double) x;
        }
        return (float) 到长整数(值);
    }

    private double 到双浮点(Object 值) {
        if (值 instanceof Double x) {
            return x;
        }
        if (值 instanceof Float x) {
            return x;
        }
        return (double) 到长整数(值);
    }

    private String 到文本(Object 值) {
        if (值 instanceof String x) {
            return x;
        }
        if (值 == null) {
            throw 空指针异常();
        }
        throw new 解释器异常("需要字符串，实际是「" + 值转文本(值) + "」。");
    }

    private String 值转文本(Object 值) {
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
        if (值 instanceof 清单值 容器) {
            return 容器.项.stream().map(this::值转文本)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        if (值 instanceof 集值 容器) {
            return 容器.项.stream().map(this::值转文本)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        if (值 instanceof 字典值 容器) {
            StringBuilder 结果 = new StringBuilder("{");
            boolean 首个 = true;
            for (Map.Entry<Object, Object> 项 : 容器.项.entrySet()) {
                if (!首个) {
                    结果.append(", ");
                }
                首个 = false;
                结果.append(值转文本(项.getKey())).append('=')
                        .append(值转文本(项.getValue()));
            }
            return 结果.append('}').toString();
        }
        if (值 instanceof 构建器值 容器) {
            return 容器.内容.toString();
        }
        if (值 instanceof 路径值 路径) {
            return 路径.路径.toString();
        }
        if (值 instanceof 可选值容器 容器) {
            return 容器.存在 ? "Optional[" + 值转文本(容器.值) + "]" : "Optional.empty";
        }
        if (值 instanceof 流值 流) {
            return 流.项.stream().map(this::值转文本)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        if (值 instanceof 实例 对象) {
            Object 枚举名 = 对象.字段.get("__枚举名");
            if (枚举名 != null) {
                return String.valueOf(枚举名);
            }
            中间代码.方法 方法 = 查找方法(对象.类, "转文本", List.of());
            if (方法 != null) {
                Object 结果 = 调用方法(方法, 对象, List.of());
                return 结果 == null ? "null" : 结果.toString();
            }
            return 对象.类.名() + "@" + Integer.toHexString(System.identityHashCode(对象));
        }
        if (值 instanceof 数组值) {
            return "数组";
        }
        return String.valueOf(值);
    }

    private Object 取负(类型 类型, Object 值) {
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

    private Object 数值(类型 类型, Object 值) {
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

    private boolean 真值(Object 值) {
        if (值 instanceof Boolean x) {
            return x;
        }
        throw new 解释器异常("需要布尔值，实际是「" + 值转文本(值) + "」。");
    }

    private 实例 是实例(Object 值, String 位置) {
        if (值 instanceof 实例 对象) {
            return 对象;
        }
        throw 空指针异常();
    }

    private 数组值 是数组(Object 值) {
        if (值 instanceof 数组值 数组) {
            return 数组;
        }
        throw new 解释器异常("需要数组，实际是「" + 值转文本(值) + "」。");
    }
}
