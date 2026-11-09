package 中文.原生.后端C;

import 中文.原生.中间.中间代码;
import 中文.原生.中间.类型;

import java.math.BigInteger;
import java.util.List;

/**
 * M3 C 后端：把 MIR 降级为可读的 C11 源文件。
 *
 * 仅覆盖教程 01–08：基本类型、字符串、数组、分支、循环、静态方法与内建打印。
 * 对象、异常、泛型与集合在 M4/M5 接入。
 */
public final class C生成器 {

    /** 生成阶段的用户可见错误。 */
    public static final class 生成异常 extends RuntimeException {
        public 生成异常(String 消息) {
            super(消息);
        }
    }

    private final 中间代码.程序 程序;
    private final 中间代码.位置信息 位置;
    private final 中间代码.类定义 类;
    private final StringBuilder 输出 = new StringBuilder();
    private int 缩进;
    private int 当前行 = 1;
    private String 源路径 = "";

    private C生成器(中间代码.程序 程序) {
        this.程序 = 程序;
        this.位置 = 程序.位置();
        this.源路径 = 位置 == null ? "" : 位置.源路径;
        if (程序.类().size() != 1) {
            throw new 生成异常("M3 暂不支持：多类程序（M4 接入对象模型）");
        }
        this.类 = 程序.类().get(0);
    }

    public static String 生成(中间代码.程序 程序) {
        C生成器 生成器 = new C生成器(程序);
        生成器.生成程序();
        return 生成器.输出.toString();
    }

    private void 生成程序() {
        for (中间代码.字段定义 字段 : 类.字段()) {
            throw new 生成异常("M3 暂不支持：字段「" + 字段.名() + "」（M4 接入对象模型）");
        }
        if (!类.构造器().isEmpty()) {
            throw new 生成异常("M3 暂不支持：构造器（M4 接入对象模型）");
        }
        boolean 有入口 = false;
        for (中间代码.方法 方法 : 类.方法()) {
            if (!方法.静态()) {
                throw new 生成异常("M3 暂不支持：实例方法「" + 方法.名() + "」（M4 接入对象模型）");
            }
            if (方法.名().equals("<入口>")) {
                有入口 = true;
            }
        }
        if (!有入口) {
            throw new 生成异常("M3 暂不支持：类模式编译需要「入口」方法（教程 01–07 为脚本模式）");
        }

        输出.append("/* 由 cjc 原生后端（M3）生成，请勿手工修改。 */\n");
        输出.append("#include \"cjkrt.h\"\n\n");

        for (中间代码.方法 方法 : 类.方法()) {
            输出.append("static ").append(C类型(方法.返回类型())).append(' ')
                    .append(方法C名(方法)).append('(').append(参数列表(方法)).append(");\n");
        }
        输出.append('\n');
        输出.append("int main(void) {\n");
        输出.append("    cjk_rt_init();\n");
        输出.append("    ").append(入口C名()).append("();\n");
        输出.append("    return 0;\n");
        输出.append("}\n\n");

        for (中间代码.方法 方法 : 类.方法()) {
            生成方法(方法);
            输出.append('\n');
        }
    }

    private void 生成方法(中间代码.方法 方法) {
        原样行("/* " + 方法.名() + " */");
        输出.append("static ").append(C类型(方法.返回类型())).append(' ')
                .append(方法C名(方法)).append('(').append(参数列表(方法)).append(") {\n");
        缩进++;
        语句(方法.体());
        缩进--;
        输出.append("}\n");
    }

    // ---------------- 语句 ----------------

    private void 语句(中间代码.语句 语句) {
        if (语句 == null) {
            return;
        }
        int 行 = 位置 == null ? 0 : 位置.行(语句);
        if (行 > 0) {
            当前行 = 行;
            原样行("#line " + 行 + " \"" + 转义C路径(源路径) + "\"");
        }
        if (语句 instanceof 中间代码.块 块) {
            原样行("{");
            缩进++;
            for (中间代码.语句 子 : 块.语句()) {
                语句(子);
            }
            缩进--;
            原样行("}");
        } else if (语句 instanceof 中间代码.局部变量语句 局部) {
            输出行(C类型(局部.类型()) + " " + 变量名(局部.名()) + " = "
                    + (局部.初值() == null ? 零值(局部.类型()) : 表达式(局部.初值())) + ";");
        } else if (语句 instanceof 中间代码.表达式语句 表达式语句) {
            输出行(表达式(表达式语句.值()) + ";");
        } else if (语句 instanceof 中间代码.返回语句 返回) {
            if (返回.值() == null) {
                输出行("return;");
            } else {
                输出行("return " + 表达式(返回.值()) + ";");
            }
        } else if (语句 instanceof 中间代码.若语句 若) {
            输出行("if (" + 表达式(若.条件()) + ")");
            语句(若.成立());
            if (若.否则() != null) {
                输出行("else");
                语句(若.否则());
            }
        } else if (语句 instanceof 中间代码.对于语句 对于) {
            生成对于(对于);
        } else if (语句 instanceof 中间代码.当语句 当) {
            输出行("while (" + 表达式(当.条件()) + ")");
            语句(当.体());
        } else if (语句 instanceof 中间代码.做当语句 做当) {
            输出行("do");
            语句(做当.体());
            输出行("while (" + 表达式(做当.条件()) + ");");
        } else if (语句 instanceof 中间代码.跳出语句) {
            输出行("break;");
        } else if (语句 instanceof 中间代码.继续语句) {
            输出行("continue;");
        } else {
            throw new 生成异常("M3 暂不支持：语句 " + 语句.getClass().getSimpleName());
        }
    }

    private void 生成对于(中间代码.对于语句 对于) {
        // 增强 for 的初始化是块：块内声明要留在循环外层作用域，不能加花括号。
        if (对于.初始化() instanceof 中间代码.块 块) {
            for (中间代码.语句 子 : 块.语句()) {
                语句(子);
            }
        } else if (对于.初始化() != null) {
            语句(对于.初始化());
        }
        StringBuilder 更新 = new StringBuilder();
        for (中间代码.表达式 项 : 对于.更新()) {
            if (更新.length() > 0) {
                更新.append(", ");
            }
            更新.append(表达式(项));
        }
        String 条件 = 对于.条件() == null ? "" : 表达式(对于.条件());
        输出行("for (; " + 条件 + "; " + 更新 + ")");
        语句(对于.体());
    }

    // ---------------- 表达式 ----------------

    private String 表达式(中间代码.表达式 表达式) {
        if (表达式 == null) {
            return "0";
        }
        if (表达式 instanceof 中间代码.常量 常量) {
            return 常量(常量);
        }
        if (表达式 instanceof 中间代码.变量读 变量读) {
            return 变量名(变量读.名());
        }
        if (表达式 instanceof 中间代码.字段读) {
            throw new 生成异常("M3 暂不支持：字段访问（M4 接入对象模型）");
        }
        if (表达式 instanceof 中间代码.数组读 数组读) {
            String 元素C = C类型(数组读.类型());
            return "(*(" + 元素C + "*)cjk_array_at(" + 表达式(数组读.数组()) + ", "
                    + 表达式(数组读.下标()) + ", (int32_t)sizeof(" + 元素C + "), " + 当前行 + "))";
        }
        if (表达式 instanceof 中间代码.数组长度 数组长度) {
            return "cjk_array_len(" + 表达式(数组长度.数组()) + ", " + 当前行 + ")";
        }
        if (表达式 instanceof 中间代码.二元 二元) {
            return 二元(二元);
        }
        if (表达式 instanceof 中间代码.一元 一元) {
            return "(" + 一元.运算符() + 表达式(一元.操作数()) + ")";
        }
        if (表达式 instanceof 中间代码.三目 三目) {
            return "(" + 表达式(三目.条件()) + " ? " + 表达式(三目.真())
                    + " : " + 表达式(三目.假()) + ")";
        }
        if (表达式 instanceof 中间代码.强制转换 强转) {
            return "((" + C类型(强转.类型()) + ")(" + 表达式(强转.操作数()) + "))";
        }
        if (表达式 instanceof 中间代码.赋值 赋值) {
            return "(" + 表达式(赋值.左()) + " = " + 表达式(赋值.右()) + ")";
        }
        if (表达式 instanceof 中间代码.调用 调用) {
            return 调用(调用);
        }
        if (表达式 instanceof 中间代码.新建对象) {
            throw new 生成异常("M3 暂不支持：新建对象（M4 接入对象模型）");
        }
        if (表达式 instanceof 中间代码.新建数组 新建数组) {
            return 新建数组(新建数组);
        }
        throw new 生成异常("M3 暂不支持：表达式 " + 表达式.getClass().getSimpleName());
    }

    private String 常量(中间代码.常量 常量) {
        类型 值类型 = 常量.类型();
        String 原文 = 常量.原文();
        if (值类型.同型(类型.文本)) {
            return "cjk_str_from_utf8(" + C文本(解Java文本(原文)) + ")";
        }
        if (值类型.是空()) {
            return "NULL";
        }
        if (值类型.是基本()) {
            switch (值类型.基本值) {
                case 整数:
                    return 整数字面量(原文, false);
                case 长整数:
                    return 整数字面量(原文, true);
                case 短整数:
                case 字节:
                    return "((int32_t)" + 整数字面量(原文, false) + ")";
                case 浮点:
                    return 浮点字面量(原文, true);
                case 双浮点:
                    return 浮点字面量(原文, false);
                case 字符:
                    return 字符字面量(原文);
                case 布尔:
                    return ("true".equals(原文) || "真".equals(原文)) ? "1" : "0";
                case 无:
                    break;
                case 文本:
                    return "cjk_str_from_utf8(" + C文本(解Java文本(原文)) + ")";
            }
        }
        throw new 生成异常("M3 暂不支持：常量 " + 值类型.文本() + " " + 原文);
    }

    private String 二元(中间代码.二元 二元) {
        String 运算符 = 二元.运算符();
        String 左 = 表达式(二元.左());
        String 右 = 表达式(二元.右());
        if (二元.类型().同型(类型.文本) && "+".equals(运算符)) {
            return "cjk_str_concat(" + 转文本(二元.左(), 左) + ", " + 转文本(二元.右(), 右) + ")";
        }
        if (("==".equals(运算符) || "!=".equals(运算符))
                && 取类型(二元.左()).同型(类型.文本)) {
            String 相等 = "cjk_str_equal(" + 左 + ", " + 右 + ")";
            return "!=".equals(运算符) ? "(!" + 相等 + ")" : 相等;
        }
        if (是整数除法(二元, 运算符)) {
            boolean 长 = 二元.类型().同型(类型.长整数);
            String 函数 = 长
                    ? ("/".equals(运算符) ? "cjk_div_i64" : "cjk_rem_i64")
                    : ("/".equals(运算符) ? "cjk_div_i32" : "cjk_rem_i32");
            return 函数 + "(" + 左 + ", " + 右 + ", " + 当前行 + ")";
        }
        if ("<<".equals(运算符) || ">>".equals(运算符) || ">>>".equals(运算符)) {
            boolean 长 = 二元.类型().同型(类型.长整数);
            int 掩码 = 长 ? 63 : 31;
            String 移位 = "(" + 右 + " & " + 掩码 + ")";
            if (">>>".equals(运算符)) {
                String 无符号 = 长 ? "(uint64_t)" : "(uint32_t)";
                String 回到 = 长 ? "(int64_t)" : "(int32_t)";
                return "(" + 回到 + "(" + 无符号 + 左 + " >> " + 移位 + "))";
            }
            return "(" + 左 + " " + 运算符 + " " + 移位 + ")";
        }
        return "(" + 左 + " " + 运算符 + " " + 右 + ")";
    }

    private static boolean 是整数除法(中间代码.二元 二元, String 运算符) {
        if (!"/".equals(运算符) && !"%".equals(运算符)) {
            return false;
        }
        return 二元.类型().是整数族();
    }

    private String 调用(中间代码.调用 调用) {
        switch (调用.分派()) {
            case 内建:
                return 内建调用(调用);
            case 静态: {
                中间代码.方法 目标 = 选方法(调用.类名(), 调用.名(), 调用.实参());
                StringBuilder sb = new StringBuilder(方法C名(目标)).append('(');
                for (int i = 0; i < 调用.实参().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(表达式(调用.实参().get(i)));
                }
                return sb.append(')').toString();
            }
            case 实例:
                throw new 生成异常("M3 暂不支持：实例方法调用「" + 调用.名() + "」（M4 接入对象模型）");
            case 构造器:
                throw new 生成异常("M3 暂不支持：构造器调用（M4 接入对象模型）");
            default:
                throw new 生成异常("M3 暂不支持：调用分派 " + 调用.分派());
        }
    }

    private String 内建调用(中间代码.调用 调用) {
        String 名 = 调用.名();
        List<中间代码.表达式> 实参 = 调用.实参();
        if ("打印行".equals(名) || "打印".equals(名)) {
            String 打印 = "cjk_print_str(" + 打印参数(实参) + ")";
            return "打印行".equals(名) ? "(" + 打印 + ", cjk_newline())" : 打印;
        }
        if ("数组复制".equals(名)) {
            中间代码.表达式 源 = 实参.get(0);
            类型 源类型 = 取类型(源);
            类型 元素 = 源类型.是数组() ? 源类型.元素 : 类型.整数;
            return "cjk_array_copy(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 表达式(实参.get(2)) + ", " + 表达式(实参.get(3))
                    + ", " + 表达式(实参.get(4))
                    + ", (int32_t)sizeof(" + C类型(元素) + "), " + 当前行 + ")";
        }
        if ("修剪".equals(名)) {
            return "cjk_str_trim(" + 表达式(唯一实参(调用)) + ")";
        }
        if ("长度".equals(名)) {
            return "cjk_str_len(" + 表达式(唯一实参(调用)) + ", " + 当前行 + ")";
        }
        if ("相等".equals(名)) {
            return "cjk_str_equal(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ")";
        }
        throw new 生成异常("M3 暂不支持：内建「" + 名 + "」");
    }

    private static 类型 取类型(中间代码.表达式 表达式) {
        if (表达式 instanceof 中间代码.常量 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.变量读 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.字段读 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.数组读 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.数组长度 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.二元 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.一元 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.三目 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.强制转换 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.赋值 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.调用 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.新建对象 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.新建数组 值) {
            return 值.类型();
        }
        throw new 生成异常("M3 暂不支持：未知表达式类型");
    }

    private static 中间代码.表达式 唯一实参(中间代码.调用 调用) {
        if (调用.接收者() != null) {
            return 调用.接收者();
        }
        return 调用.实参().get(0);
    }

    /** 打印行/打印的多个实参按 Java 语义依次拼成文本。 */
    private String 打印参数(List<中间代码.表达式> 实参) {
        if (实参.isEmpty()) {
            return "cjk_str_from_utf8(\"\")";
        }
        String 结果 = null;
        for (中间代码.表达式 项 : 实参) {
            String 文本 = 转文本(项, 表达式(项));
            结果 = 结果 == null ? 文本 : "cjk_str_concat(" + 结果 + ", " + 文本 + ")";
        }
        return 结果;
    }

    private String 转文本(中间代码.表达式 表达式, String 已生成) {
        类型 值类型 = 取类型(表达式);
        if (值类型.同型(类型.文本)) {
            return 已生成;
        }
        if (值类型.是空()) {
            return 已生成;
        }
        if (值类型.是基本()) {
            switch (值类型.基本值) {
                case 整数:
                    return "cjk_str_from_i32(" + 已生成 + ")";
                case 短整数:
                case 字节:
                    return "cjk_str_from_i32((int32_t)(" + 已生成 + "))";
                case 长整数:
                    return "cjk_str_from_i64(" + 已生成 + ")";
                case 浮点:
                    return "cjk_str_from_f32(" + 已生成 + ")";
                case 双浮点:
                    return "cjk_str_from_f64(" + 已生成 + ")";
                case 字符:
                    return "cjk_str_from_char(" + 已生成 + ")";
                case 布尔:
                    return "cjk_str_from_bool(" + 已生成 + ")";
                case 文本:
                    return 已生成;
                case 无:
                    break;
            }
        }
        throw new 生成异常("M3 暂不支持：把 " + 值类型.文本() + " 拼接到字符串");
    }

    private String 新建数组(中间代码.新建数组 新建数组) {
        类型 元素 = 新建数组.元素类型();
        String 元素C = C类型(元素);
        String 种类 = 种类码(元素);
        if (!新建数组.初值().isEmpty()) {
            StringBuilder 数据 = new StringBuilder("((" + 元素C + "[]){");
            for (int i = 0; i < 新建数组.初值().size(); i++) {
                if (i > 0) {
                    数据.append(", ");
                }
                数据.append(表达式(新建数组.初值().get(i)));
            }
            数据.append("})");
            return "cjk_array_from(" + 种类 + ", (int32_t)sizeof(" + 元素C + "), "
                    + 新建数组.初值().size() + ", (const void*)" + 数据 + ")";
        }
        return "cjk_array_new(" + 表达式(新建数组.长度()) + ", " + 种类 + ", (int32_t)sizeof("
                + 元素C + "), " + 当前行 + ")";
    }

    // ---------------- 名字与类型 ----------------

    private 中间代码.方法 选方法(String 类名, String 名, List<中间代码.表达式> 实参) {
        中间代码.方法 最佳 = null;
        int 最佳分 = -1;
        for (中间代码.类定义 候选类 : 程序.类()) {
            if (类名 != null && !候选类.名().equals(类名)) {
                continue;
            }
            for (中间代码.方法 候选 : 候选类.方法()) {
                if (!候选.名().equals(名) || 候选.参数().size() != 实参.size()) {
                    continue;
                }
                int 分 = 0;
                boolean 可行 = true;
                for (int i = 0; i < 实参.size(); i++) {
                    类型 参数类型 = 候选.参数().get(i).类型();
                    类型 实参类型 = 取类型(实参.get(i));
                    if (参数类型.同型(实参类型)) {
                        分 += 3;
                    } else if (参数类型.可赋值自(实参类型)) {
                        分 += 1;
                    } else if (参数类型.是数值() && 实参类型.是数值()) {
                        分 += 0;
                    } else {
                        可行 = false;
                        break;
                    }
                }
                if (可行 && 分 > 最佳分) {
                    最佳 = 候选;
                    最佳分 = 分;
                }
            }
        }
        if (最佳 == null) {
            throw new 生成异常("M3 暂不支持：找不到方法「" + 名 + "」的重载");
        }
        return 最佳;
    }

    private String 方法C名(中间代码.方法 方法) {
        if (方法.名().equals("<入口>")) {
            return 入口C名();
        }
        StringBuilder sb = new StringBuilder("_cjkf_");
        sb.append(编码(类.名())).append('_').append(编码(方法.名()));
        for (中间代码.参数 参数 : 方法.参数()) {
            sb.append('_').append(类型码(参数.类型()));
        }
        return sb.toString();
    }

    private String 入口C名() {
        return "_cjkf_entry_" + 编码(类.名());
    }

    private String 参数列表(中间代码.方法 方法) {
        if (方法.名().equals("<入口>") || 方法.参数().isEmpty()) {
            return "void";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 方法.参数().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            中间代码.参数 参数 = 方法.参数().get(i);
            sb.append(C类型(参数.类型())).append(' ').append(变量名(参数.名()));
        }
        return sb.toString();
    }

    private static String 变量名(String 名) {
        return "_cjkv_" + 编码(名);
    }

    private static String 编码(String 名) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 名.length(); ) {
            int 码点 = 名.codePointAt(i);
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(Integer.toHexString(码点));
            i += Character.charCount(码点);
        }
        return sb.toString();
    }

    private static String C类型(类型 类型) {
        if (类型.是数组()) {
            return "cjk_array*";
        }
        if (类型.是类() || 类型.是空()) {
            return "void*";
        }
        if (类型.是错误()) {
            throw new 生成异常("M3 暂不支持：错误类型");
        }
        return switch (类型.基本值) {
            case 整数 -> "int32_t";
            case 长整数 -> "int64_t";
            case 短整数 -> "int16_t";
            case 字节 -> "int8_t";
            case 浮点 -> "float";
            case 双浮点 -> "double";
            case 字符 -> "uint16_t";
            case 布尔 -> "int32_t";
            case 文本 -> "cjk_string*";
            case 无 -> "void";
        };
    }

    private static String 类型码(类型 类型) {
        if (类型.是数组()) {
            return "arr";
        }
        if (类型.是类()) {
            return "obj";
        }
        if (类型.是空()) {
            return "obj";
        }
        return switch (类型.基本值) {
            case 整数 -> "i32";
            case 长整数 -> "i64";
            case 短整数 -> "i16";
            case 字节 -> "i8";
            case 浮点 -> "f32";
            case 双浮点 -> "f64";
            case 字符 -> "c16";
            case 布尔 -> "b1";
            case 文本 -> "str";
            case 无 -> "void";
        };
    }

    private static String 种类码(类型 类型) {
        if (类型.是数组() || 类型.是类() || 类型.是空()) {
            return "CJK_KIND_REF";
        }
        return switch (类型.基本值) {
            case 整数 -> "CJK_KIND_I32";
            case 长整数 -> "CJK_KIND_I64";
            case 短整数 -> "CJK_KIND_I16";
            case 字节 -> "CJK_KIND_I8";
            case 浮点 -> "CJK_KIND_F32";
            case 双浮点 -> "CJK_KIND_F64";
            case 字符 -> "CJK_KIND_U16";
            case 布尔 -> "CJK_KIND_BOOL";
            case 文本 -> "CJK_KIND_REF";
            case 无 -> "CJK_KIND_REF";
        };
    }

    private static String 零值(类型 值类型) {
        if (值类型.是数组() || 值类型.是类() || 值类型.是空()) {
            return "NULL";
        }
        if (值类型.同型(类型.文本)) {
            return "NULL";
        }
        if (值类型.是基本()) {
            return switch (值类型.基本值) {
                case 浮点 -> "0.0f";
                case 双浮点 -> "0.0";
                case 文本 -> "NULL";
                default -> "0";
            };
        }
        return "0";
    }

    // ---------------- 字面量 ----------------

    private static String 整数字面量(String 原文, boolean 长) {
        String s = 原文.replace("_", "");
        if (s.endsWith("l") || s.endsWith("L")) {
            s = s.substring(0, s.length() - 1);
        }
        int 基数 = 10;
        String 数字 = s;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            基数 = 16;
            数字 = s.substring(2);
        } else if (s.startsWith("0b") || s.startsWith("0B")) {
            基数 = 2;
            数字 = s.substring(2);
        } else if (s.startsWith("0o") || s.startsWith("0O")) {
            基数 = 8;
            数字 = s.substring(2);
        } else if (s.length() > 1 && s.startsWith("0")) {
            基数 = 8;
            数字 = s.substring(1);
        }
        BigInteger 值 = new BigInteger(数字.isEmpty() ? "0" : 数字, 基数);
        if (长) {
            return "((int64_t)" + 值.longValue() + "LL)";
        }
        return "((int32_t)" + 值.intValue() + ")";
    }

    private static String 浮点字面量(String 原文, boolean 单精度) {
        String s = 原文.replace("_", "");
        if (s.endsWith("f") || s.endsWith("F") || s.endsWith("d") || s.endsWith("D")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.contains(".") && !s.contains("e") && !s.contains("E")
                && !s.contains("p") && !s.contains("P")) {
            s += ".0";
        }
        return 单精度 ? "((float)" + s + ")" : "((double)" + s + ")";
    }

    private static String 字符字面量(String 原文) {
        String 内容 = 解Java文本(原文);
        int 码点 = 内容.isEmpty() ? 0 : 内容.codePointAt(0);
        if (码点 > 0xFFFF) {
            码点 = 0x3F;
        }
        return "((uint16_t)" + 码点 + ")";
    }

    private static String 解Java文本(String 原文) {
        if (原文 == null || 原文.length() < 2) {
            return "";
        }
        String 内容 = 原文.substring(1, 原文.length() - 1);
        StringBuilder sb = new StringBuilder(内容.length());
        for (int i = 0; i < 内容.length(); i++) {
            char 字符 = 内容.charAt(i);
            if (字符 != '\\') {
                sb.append(字符);
                continue;
            }
            i++;
            if (i >= 内容.length()) {
                break;
            }
            char 转义 = 内容.charAt(i);
            switch (转义) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case '\\' -> sb.append('\\');
                case '0' -> sb.append('\0');
                case 'u' -> {
                    if (i + 5 <= 内容.length()) {
                        String 十六进制 = 内容.substring(i + 1, i + 5);
                        try {
                            sb.append((char) Integer.parseInt(十六进制, 16));
                        } catch (NumberFormatException 忽略) {
                            sb.append('u');
                        }
                        i += 4;
                    } else {
                        sb.append('u');
                    }
                }
                default -> sb.append(转义);
            }
        }
        return sb.toString();
    }

    private static String C文本(String 内容) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < 内容.length(); i++) {
            char 字符 = 内容.charAt(i);
            switch (字符) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\000");
                default -> {
                    if (字符 < 0x20 || 字符 == 0x7F) {
                        sb.append(String.format("\\%03o", (int) 字符));
                    } else {
                        sb.append(字符);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String 转义C路径(String 路径) {
        return 路径.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------- 输出 ----------------

    private void 输出行(String 文本) {
        输出.append("    ".repeat(缩进)).append(文本).append('\n');
    }

    private void 原样行(String 文本) {
        输出.append(文本).append('\n');
    }
}
