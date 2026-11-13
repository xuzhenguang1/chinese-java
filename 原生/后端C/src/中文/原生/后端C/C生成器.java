package 中文.原生.后端C;

import 中文.原生.中间.中间代码;
import 中文.原生.中间.类型;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M4 C 后端：把 MIR 降级为可读的 C11 源文件。
 *
 * 覆盖教程 01–21：基本类型、字符串、数组、控制流、对象与字段、继承与重写、
 * 抽象类、接口、多态、枚举、异常与 GC。
 */
public final class C生成器 {

    /** 生成阶段的用户可见错误。 */
    public static final class 生成异常 extends RuntimeException {
        public 生成异常(String 消息) {
            super(消息);
        }
    }

    private static final Set<String> 内建类名 = Set.of(
            "对象", "异常", "运行时异常", "算术异常", "空指针异常",
            "数组越界异常", "非法参数异常", "数字格式异常", "类转换异常",
            "输入输出异常", "可比较");

    /** 标准库 10 类的公开 API 在 C 侧也有运行时描述符（不是用户类）。 */
    private static final Set<String> 标准库描述符类名 = Set.of(
            "本地日期", "本地日期时间", "响应", "线程池", "未来", "日志记录器");

    /** 泛型擦除后的类型变量；C 侧用 cjk_object* 值盒承载。 */
    private static final String 泛型值名 = "__值";

    /** 包装类：C 侧同样以 cjk_object* 承载，内部是值盒。 */
    private static final Set<String> 包装类名 = Set.of(
            "整数包装", "长整数包装", "短整数包装", "字节包装",
            "浮点包装", "双浮点包装", "布尔包装", "字符包装");

    private static boolean 是包装类(类型 类型) {
        return 类型 != null && 类型.是类() && 包装类名.contains(类型.类名);
    }

    private final 中间代码.程序 程序;
    private final 中间代码.位置信息 位置;
    private StringBuilder 输出 = new StringBuilder();
    private final Map<String, 中间代码.类定义> 类表 = new LinkedHashMap<>();
    private final Map<String, Integer> 槽表 = new LinkedHashMap<>();
    private int 缩进;
    private int 当前行 = 1;
    private String 源路径 = "";
    private 中间代码.类定义 当前类;
    private 类型 当前返回类型 = 类型.无;

    private C生成器(中间代码.程序 程序) {
        this.程序 = 程序;
        this.位置 = 程序.位置();
        this.源路径 = 位置 == null ? "" : 位置.源路径;
        for (中间代码.类定义 类 : 程序.类()) {
            类表.put(类.名(), 类);
        }
        收集槽();
    }

    public static String 生成(中间代码.程序 程序) {
        C生成器 生成器 = new C生成器(程序);
        生成器.生成程序();
        return 生成器.输出.toString();
    }

    private void 收集槽() {
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.方法 方法 : 类.方法()) {
                if (方法.静态() || 方法.名().equals("<入口>")) {
                    continue;
                }
                槽表.putIfAbsent(槽键(方法), 槽表.size());
            }
        }
    }

    private static String 槽键(中间代码.方法 方法) {
        StringBuilder sb = new StringBuilder(方法.名()).append('(');
        for (int i = 0; i < 方法.参数().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(类型码(方法.参数().get(i).类型()));
        }
        return sb.append(')').toString();
    }

    private int 槽(中间代码.方法 方法) {
        Integer 值 = 槽表.get(槽键(方法));
        if (值 == null) {
            throw new 生成异常("M4 暂不支持：方法「" + 方法.名() + "」没有可用的虚调用槽位");
        }
        return 值;
    }

    // ================= 程序 =================

    private void 生成程序() {
        输出.append("/* 由 cjc 原生后端（M4）生成，请勿手工修改。 */\n");
        输出.append("#include \"cjkrt.h\"\n");
        输出.append("#include <math.h>\n");
        输出.append("#include <stddef.h>\n");
        输出.append("#include <stdint.h>\n");
        输出.append("#include <stdlib.h>\n\n");

        声明结构体();
        声明函数();
        生成回调包装();
        生成槽表();
        生成描述符();
        定义结构体();
        生成静态全局();
        生成初始化();
        生成入口();
        定义函数();
    }

    private void 声明结构体() {
        for (中间代码.类定义 类 : 程序.类()) {
            输出.append("typedef struct ").append(结构体名(类.名()))
                    .append(' ').append(结构体名(类.名())).append(";\n");
        }
        输出.append('\n');
    }

    private void 声明函数() {
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.方法 方法 : 类.构造器()) {
                输出.append("static void ").append(构造器C名(类.名(), 方法))
                        .append('(').append(参数列表(方法, true)).append(");\n");
            }
            for (中间代码.方法 方法 : 类.方法()) {
                if (方法.体() == null) {
                    continue;
                }
                输出.append("static ").append(C类型(方法.返回类型())).append(' ')
                        .append(方法C名(类.名(), 方法)).append('(')
                        .append(参数列表(方法, !方法.静态())).append(");\n");
            }
            if (类.构造器().isEmpty()) {
                输出.append("static cjk_object* ").append(新建C名(类.名(), null))
                        .append("(void);\n");
            } else {
                for (中间代码.方法 方法 : 类.构造器()) {
                    输出.append("static cjk_object* ").append(新建C名(类.名(), 方法))
                            .append('(').append(参数列表(方法, false)).append(");\n");
                }
            }
            if (类.枚举()) {
                输出.append("static cjk_object* ").append(按名C名(类.名()))
                        .append("(cjk_string* 名, int32_t 行);\n");
            }
        }
        输出.append('\n');
    }

    /**
     * M5：为带「调用」方法的合成 λ/方法引用类生成统一回调包装。
     * 运行时（流的过滤/变换、字典遍历、可选值存在时、比较器）都以 cjk_value 调用它。
     */
    private void 生成回调包装() {
        for (中间代码.类定义 类 : 程序.类()) {
            中间代码.方法 调用方法 = null;
            for (中间代码.方法 方法 : 类.方法()) {
                if (!方法.静态() && 方法.名().equals("调用")) {
                    调用方法 = 方法;
                }
            }
            if (调用方法 == null) {
                continue;
            }
            输出.append("static cjk_value ").append(回调C名(类.名()))
                    .append("(cjk_object* self, cjk_value _a, cjk_value _b, int32_t line) {\n");
            缩进++;
            StringBuilder 调用 = new StringBuilder(方法C名(类.名(), 调用方法)).append("(self");
            for (int i = 0; i < 调用方法.参数().size(); i++) {
                调用.append(", ").append(从原生值(调用方法.参数().get(i).类型(), i == 0 ? "_a" : "_b"));
            }
            调用.append(')');
            if (调用方法.返回类型() == null || 调用方法.返回类型().同型(类型.无)) {
                输出行(调用 + ";");
                输出行("return cjk_v_null();");
            } else {
                输出行(C类型(调用方法.返回类型()) + " _r = " + 调用 + ";");
                输出行("return " + 到原生值(调用方法.返回类型(), "_r") + ";");
            }
            缩进--;
            输出.append("}\n\n");
        }
    }

    private static String 回调C名(String 类名) {
        return "_cjkcb_" + 编码(类名);
    }

    private static boolean 是泛型值(类型 类型) {
        return 类型 != null && 类型.是类() && 泛型值名.equals(类型.类名);
    }

    /** 具体类型 → 泛型擦除值（cjk_object*）。 */
    private String 到值(类型 源, String c) {
        if (源 == null || 源.同型(类型.无)) {
            return "NULL";
        }
        if (是泛型值(源) || 源.是类()) {
            return c;
        }
        if (源.是空()) {
            return "NULL";
        }
        if (源.是数组()) {
            return "cjk_box_value(cjk_v_ref((void*)(" + c + ")))";
        }
        if (源.同型(类型.文本)) {
            return "cjk_box_value(cjk_v_str(" + c + "))";
        }
        if (源.同型(类型.长整数)) {
            return "cjk_box_value(cjk_v_i64(" + c + "))";
        }
        if (源.同型(类型.浮点)) {
            return "cjk_box_value(cjk_v_f32(" + c + "))";
        }
        if (源.同型(类型.双浮点)) {
            return "cjk_box_value(cjk_v_f64(" + c + "))";
        }
        if (源.同型(类型.布尔)) {
            return "cjk_box_value(cjk_v_bool(" + c + "))";
        }
        if (源.同型(类型.字符)) {
            return "cjk_box_value(cjk_v_char(" + c + "))";
        }
        return "cjk_box_value(cjk_v_i32(" + c + "))";
    }

    /** 泛型擦除值（cjk_object*）→ 具体类型。 */
    private String 从值(类型 目标, String c) {
        return 从原生值(目标, "cjk_unbox_value(" + c + ", " + 当前行 + ")");
    }

    /** 原生 cjk_value 表达式 → 具体类型 / 值盒。 */
    private String 从原生值(类型 目标, String 值) {
        if (目标 == null || 目标.同型(类型.无)) {
            return "(void)(" + 值 + ")";
        }
        if (是泛型值(目标)) {
            return "cjk_box_value(" + 值 + ")";
        }
        if (是包装类(目标)) {
            return "cjk_box_value(" + 值 + ")";
        }
        if (目标.是类()) {
            return "((cjk_object*)cjk_v_as_ref(" + 值 + ", " + 当前行 + "))";
        }
        if (目标.是数组()) {
            return "((cjk_array*)cjk_v_as_ref(" + 值 + ", " + 当前行 + "))";
        }
        if (目标.同型(类型.文本)) {
            return "cjk_v_as_str(" + 值 + ", " + 当前行 + ")";
        }
        if (目标.同型(类型.长整数)) {
            return "cjk_v_as_i64(" + 值 + ", " + 当前行 + ")";
        }
        if (目标.同型(类型.浮点)) {
            return "cjk_v_as_f32(" + 值 + ", " + 当前行 + ")";
        }
        if (目标.同型(类型.双浮点)) {
            return "cjk_v_as_f64(" + 值 + ", " + 当前行 + ")";
        }
        if (目标.同型(类型.布尔)) {
            return "cjk_v_as_bool(" + 值 + ", " + 当前行 + ")";
        }
        if (目标.同型(类型.字符)) {
            return "cjk_v_as_char(" + 值 + ", " + 当前行 + ")";
        }
        return "cjk_v_as_i32(" + 值 + ", " + 当前行 + ")";
    }

    /** 表达式适配到目标形参/变量类型。 */
    private String 适配(类型 目标, 中间代码.表达式 实参) {
        String c = 表达式(实参);
        类型 源 = 取类型(实参);
        if (目标 == null || 源 == null) {
            return c;
        }
        if (是泛型值(目标) && !是泛型值(源)) {
            return 到值(源, c);
        }
        if (!是泛型值(目标) && 是泛型值(源)) {
            return 从值(目标, c);
        }
        if (是包装类(目标) && 源.是基本()) {
            return 到值(源, c);
        }
        if (是包装类(源) && !目标.是类() && !目标.是数组()) {
            return 从值(目标, c);
        }
        return c;
    }

    /** 表达式 → cjk_value（集合/流/可选值/字典的入参）。 */
    private String 值参(中间代码.表达式 实参) {
        return 到原生值(取类型(实参), 表达式(实参));
    }

    /** 具体 C 表达式 → 原生 cjk_value。 */
    private String 到原生值(类型 源, String c) {
        if (源 == null) {
            return "cjk_v_null()";
        }
        if (是泛型值(源) || 是包装类(源)) {
            return "cjk_unbox_value(" + c + ", " + 当前行 + ")";
        }
        if (源.是空()) {
            return "cjk_v_null()";
        }
        if (源.是类() || 源.是数组()) {
            return "cjk_v_ref((void*)(" + c + "))";
        }
        if (源.同型(类型.文本)) {
            return "cjk_v_str(" + c + ")";
        }
        if (源.同型(类型.长整数)) {
            return "cjk_v_i64(" + c + ")";
        }
        if (源.同型(类型.浮点)) {
            return "cjk_v_f32(" + c + ")";
        }
        if (源.同型(类型.双浮点)) {
            return "cjk_v_f64(" + c + ")";
        }
        if (源.同型(类型.布尔)) {
            return "cjk_v_bool(" + c + ")";
        }
        if (源.同型(类型.字符)) {
            return "cjk_v_char(" + c + ")";
        }
        return "cjk_v_i32(" + c + ")";
    }

    private static String 转文本钩子C名(String 类名) {
        return "_cjkhook_" + 编码(类名);
    }

    private void 生成槽表() {
        int 总数 = Math.max(1, 槽表.size());
        for (中间代码.类定义 类 : 程序.类()) {
            输出.append("static void* ").append(槽表名(类.名()))
                    .append('[').append(总数).append("] = {");
            for (int i = 0; i < 总数; i++) {
                if (i > 0) {
                    输出.append(", ");
                }
                String 函数 = 方法表项(类, i);
                输出.append(函数 == null ? "NULL" : "(void*)" + 函数);
            }
            输出.append("};\n");
        }
        输出.append('\n');
    }

    /** 在类层次中找槽位对应实现；抽象方法返回 null。 */
    private String 方法表项(中间代码.类定义 类, int 槽位) {
        for (Map.Entry<String, Integer> 项 : 槽表.entrySet()) {
            if (项.getValue() != 槽位) {
                continue;
            }
            String 键 = 项.getKey();
            中间代码.类定义 当前 = 类;
            while (当前 != null) {
                for (中间代码.方法 方法 : 当前.方法()) {
                    if (!方法.静态() && !方法.名().equals("<入口>") && 槽键(方法).equals(键)) {
                        return 方法.体() == null ? null : 方法C名(当前.名(), 方法);
                    }
                }
                当前 = 父类定义(当前);
            }
            return null;
        }
        return null;
    }

    private void 生成描述符() {
        int 序号 = 100;
        for (中间代码.类定义 类 : 程序.类()) {
            String 名 = 类.名();
            if (!类.接口().isEmpty()) {
                输出.append("static cjk_class* ").append(接口表名(名)).append("[] = {");
                for (int i = 0; i < 类.接口().size(); i++) {
                    if (i > 0) {
                        输出.append(", ");
                    }
                    输出.append(描述符(类.接口().get(i)));
                }
                输出.append("};\n");
            }
            中间代码.方法 转文本 = 本类转文本(类);
            String 钩子 = "NULL";
            if (转文本 != null) {
                输出.append("static cjk_string* ").append(转文本钩子C名(名))
                        .append("(cjk_object* self) {\n");
                缩进++;
                输出行("return " + 方法C名(名, 转文本) + "(self);");
                缩进--;
                输出.append("}\n");
                钩子 = 转文本钩子C名(名);
            }
            输出.append("static cjk_class ").append(描述符名(名)).append(" = {")
                    .append(序号++).append(", ").append(C文本(名)).append(", ")
                    .append(描述符(父名(类))).append(", ")
                    .append(槽表.size()).append(", ").append(槽表名(名)).append(", ");
            if (类.接口().isEmpty()) {
                输出.append("NULL, 0");
            } else {
                输出.append(接口表名(名)).append(", ").append(类.接口().size());
            }
            输出.append(", ").append(钩子).append("};\n");
        }
        输出.append('\n');
    }

    /** 本类自己声明的无参「转文本」方法（不含继承）。 */
    private static 中间代码.方法 本类转文本(中间代码.类定义 类) {
        for (中间代码.方法 方法 : 类.方法()) {
            if (!方法.静态() && 方法.体() != null && 方法.名().equals("转文本")
                    && 方法.参数().isEmpty()) {
                return 方法;
            }
        }
        return null;
    }

    private void 定义结构体() {
        for (中间代码.类定义 类 : 程序.类()) {
            String 名 = 结构体名(类.名());
            输出.append("struct ").append(名).append(" {\n");
            输出.append("    cjk_class* cls;\n");
            List<中间代码.类定义> 祖先 = new ArrayList<>();
            中间代码.类定义 当前 = 父类定义(类);
            while (当前 != null) {
                祖先.add(0, 当前);
                当前 = 父类定义(当前);
            }
            Set<String> 已有 = new LinkedHashSet<>();
            for (中间代码.类定义 项 : 祖先) {
                for (中间代码.字段定义 字段 : 项.字段()) {
                    if (已有.add(字段.名())) {
                        输出.append("    ").append(C类型(字段.类型())).append(' ')
                                .append(字段名(字段.名())).append(";\n");
                    }
                }
            }
            for (中间代码.字段定义 字段 : 类.字段()) {
                if (已有.add(字段.名())) {
                    输出.append("    ").append(C类型(字段.类型())).append(' ')
                            .append(字段名(字段.名())).append(";\n");
                }
            }
            输出.append("};\n\n");
        }
    }

    private void 生成静态全局() {
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                输出.append("static ").append(C类型(字段.类型())).append(' ')
                        .append(静态字段名(类.名(), 字段.名())).append(";\n");
            }
            for (中间代码.枚举常量定义 常量 : 类.枚举常量()) {
                输出.append("static cjk_object* ").append(静态字段名(类.名(), 常量.名()))
                        .append(";\n");
            }
            if (类.枚举()) {
                输出.append("static cjk_array* ").append(枚举数组名(类.名())).append(";\n");
            }
        }
        输出.append('\n');
    }

    private void 生成初始化() {
        输出.append("static void _cjk_init_roots(void) {\n");
        缩进++;
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                String 名 = 静态字段名(类.名(), 字段.名());
                输出行("cjk_gc_add_root(&" + 名 + ", sizeof(" + 名 + "));");
            }
            for (中间代码.枚举常量定义 常量 : 类.枚举常量()) {
                String 名 = 静态字段名(类.名(), 常量.名());
                输出行("cjk_gc_add_root(&" + 名 + ", sizeof(" + 名 + "));");
            }
            if (类.枚举()) {
                String 名 = 枚举数组名(类.名());
                输出行("cjk_gc_add_root(&" + 名 + ", sizeof(" + 名 + "));");
            }
        }
        缩进--;
        输出.append("}\n\n");

        for (中间代码.类定义 类 : 程序.类()) {
            if (类.枚举()) {
                生成枚举初始化(类);
            }
        }

        输出.append("static void _cjk_init_statics(void) {\n");
        缩进++;
        for (中间代码.类定义 类 : 程序.类()) {
            if (类.枚举()) {
                输出行("_cjk_init_enum_" + 编码(类.名()) + "();");
            }
        }
        for (中间代码.类定义 类 : 程序.类()) {
            当前类 = 类;
            当前行 = 0;
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                if (字段.初值() == null) {
                    continue;
                }
                输出行(静态字段名(类.名(), 字段.名()) + " = " + 适配(字段.类型(), 字段.初值()) + ";");
            }
        }
        当前类 = null;
        缩进--;
        输出.append("}\n\n");
    }

    private void 生成枚举初始化(中间代码.类定义 类) {
        String 名 = 类.名();
        输出.append("static void _cjk_init_enum_").append(编码(名)).append("(void) {\n");
        缩进++;
        输出行(枚举数组名(名) + " = cjk_array_new(" + 类.枚举常量().size()
                + ", CJK_KIND_REF, (int32_t)sizeof(cjk_object*), 0);");
        当前类 = 类;
        当前行 = 0;
        for (int i = 0; i < 类.枚举常量().size(); i++) {
            中间代码.枚举常量定义 常量 = 类.枚举常量().get(i);
            当前行 = 0;
            String 创建 = 新建调用(名, 常量.实参());
            输出行("{");
            缩进++;
            输出行("cjk_object* 对象 = " + 创建 + ";");
            输出行("((" + 结构体名(名) + "*)对象)->" + 字段名("__枚举名")
                    + " = cjk_str_from_utf8(" + C文本(常量.名()) + ");");
            输出行(静态字段名(名, 常量.名()) + " = 对象;");
            输出行("((cjk_object**)" + 枚举数组名(名) + "->data)[" + i + "] = 对象;");
            缩进--;
            输出行("}");
        }
        当前类 = null;
        缩进--;
        输出.append("}\n\n");
    }

    private void 生成入口() {
        中间代码.类定义 入口类 = null;
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.方法 方法 : 类.方法()) {
                if (方法.名().equals("<入口>")) {
                    入口类 = 类;
                    break;
                }
            }
            if (入口类 != null) {
                break;
            }
        }
        if (入口类 == null) {
            throw new 生成异常("M4 暂不支持：程序没有「入口」方法");
        }
        输出.append("int main(int argc, char** argv) {\n");
        缩进++;
        输出行("cjk_rt_init();");
        输出行("cjk_set_source(" + C文本(源路径) + ");");
        输出行("cjk_set_args(argc, argv);");
        输出行("_cjk_init_roots();");
        输出行("_cjk_init_statics();");
        输出行(方法C名(入口类.名(), 入口方法(入口类)) + "();");
        输出行("return 0;");
        缩进--;
        输出.append("}\n\n");
    }

    private static 中间代码.方法 入口方法(中间代码.类定义 类) {
        for (中间代码.方法 方法 : 类.方法()) {
            if (方法.名().equals("<入口>")) {
                return 方法;
            }
        }
        return null;
    }

    private void 定义函数() {
        for (中间代码.类定义 类 : 程序.类()) {
            当前类 = 类;
            for (中间代码.方法 方法 : 类.构造器()) {
                生成构造器(类, 方法);
            }
            if (类.构造器().isEmpty()) {
                生成新建(类, null);
            } else {
                for (中间代码.方法 方法 : 类.构造器()) {
                    生成新建(类, 方法);
                }
            }
            for (中间代码.方法 方法 : 类.方法()) {
                if (方法.体() != null) {
                    生成方法(类, 方法);
                }
            }
            if (类.枚举()) {
                生成按名(类);
            }
            当前类 = null;
        }
    }

    private void 生成构造器(中间代码.类定义 类, 中间代码.方法 方法) {
        StringBuilder 声明 = new StringBuilder("static void ")
                .append(构造器C名(类.名(), 方法)).append('(').append(参数列表(方法, true)).append(") {\n");
        输出.append(声明);
        缩进++;
        当前类 = 类;
        语句(方法.体());
        缩进--;
        输出.append("}\n\n");
    }

    private void 生成新建(中间代码.类定义 类, 中间代码.方法 构造器) {
        输出.append("static cjk_object* ").append(新建C名(类.名(), 构造器)).append('(');
        if (构造器 == null || 构造器.参数().isEmpty()) {
            输出.append("void");
        } else {
            输出.append(参数列表(构造器, false));
        }
        输出.append(") {\n");
        缩进++;
        输出行("cjk_object* 对象 = cjk_new_object(" + 描述符(类.名())
                + ", sizeof(" + 结构体名(类.名()) + "));");
        if (构造器 != null) {
            StringBuilder 调用 = new StringBuilder(构造器C名(类.名(), 构造器)).append("(对象");
            for (中间代码.参数 参数 : 构造器.参数()) {
                调用.append(", ").append(变量名(参数.名()));
            }
            outputLine(调用.append(");").toString());
        }
        输出行("return 对象;");
        缩进--;
        输出.append("}\n\n");
    }

    private void 生成方法(中间代码.类定义 类, 中间代码.方法 方法) {
        输出.append("/* ").append(类.名()).append('.').append(方法.名()).append(" */\n");
        输出.append("static ").append(C类型(方法.返回类型())).append(' ')
                .append(方法C名(类.名(), 方法)).append('(')
                .append(参数列表(方法, !方法.静态())).append(") {\n");
        缩进++;
        当前类 = 类;
        当前返回类型 = 方法.返回类型();
        语句(方法.体());
        缩进--;
        当前返回类型 = 类型.无;
        输出.append("}\n\n");
    }

    private void 生成按名(中间代码.类定义 类) {
        String 名 = 类.名();
        输出.append("static cjk_object* ").append(按名C名(名))
                .append("(cjk_string* 名值, int32_t 行) {\n");
        缩进++;
        输出行("for (int32_t i = 0; i < " + 枚举数组名(名) + "->len; i++) {");
        缩进++;
        输出行("cjk_object* 项 = ((cjk_object**)" + 枚举数组名(名) + "->data)[i];");
        输出行("if (cjk_str_equal(((" + 结构体名(名) + "*)项)->" + 字段名("__枚举名")
                + ", 名值)) {");
        缩进++;
        输出行("return 项;");
        缩进--;
        输出行("}");
        缩进--;
        输出行("}");
        输出行("cjk_throw(cjk_new_exception(&CJK_CLS_非法参数异常, "
                + "cjk_str_concat(cjk_str_from_utf8(" + C文本("No enum constant " + 名 + ".") + "), 名值)), 行);");
        输出行("return NULL;");
        缩进--;
        输出.append("}\n\n");
    }

    // ================= 语句 =================

    /** 把一段语句渲染成文本（用于 GNU 语句表达式 `({ ... })`）。 */
    private String 语句序列(List<中间代码.语句> 语句们) {
        StringBuilder 旧输出 = 输出;
        int 旧缩进 = 缩进;
        输出 = new StringBuilder();
        缩进 = 0;
        try {
            for (中间代码.语句 项 : 语句们) {
                语句(项);
            }
            return 输出.toString();
        } finally {
            输出 = 旧输出;
            缩进 = 旧缩进;
        }
    }

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
                    + (局部.初值() == null ? 零值(局部.类型()) : 适配(局部.类型(), 局部.初值())) + ";");
        } else if (语句 instanceof 中间代码.表达式语句 表达式语句) {
            输出行(表达式(表达式语句.值()) + ";");
        } else if (语句 instanceof 中间代码.返回语句 返回) {
            if (返回.值() == null) {
                输出行("return;");
            } else {
                输出行("return " + 适配(当前返回类型, 返回.值()) + ";");
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
        } else if (语句 instanceof 中间代码.尝试语句 尝试) {
            生成尝试(尝试);
        } else if (语句 instanceof 中间代码.抛出语句 抛出) {
            输出行("cjk_throw((cjk_exception*)(" + 表达式(抛出.值()) + "), " + 当前行 + ");");
        } else {
            throw new 生成异常("M4 暂不支持：语句 " + 语句.getClass().getSimpleName());
        }
    }

    private void 生成对于(中间代码.对于语句 对于) {
        /* 初始化变量要限制在循环作用域内，避免同函数多个循环重名。 */
        原样行("{");
        缩进++;
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
        缩进--;
        原样行("}");
    }

    private void 生成尝试(中间代码.尝试语句 尝试) {
        原样行("{");
        缩进++;
        输出行("cjk_handler _h;");
        输出行("_h.prev = cjk_handler_top;");
        输出行("_h.exception = NULL;");
        输出行("cjk_handler_top = &_h;");
        输出行("if (setjmp(_h.buf) == 0) {");
        缩进++;
        语句(尝试.体());
        输出行("cjk_handler_top = _h.prev;");
        生成兜底(尝试.兜底());
        缩进--;
        输出行("} else {");
        缩进++;
        输出行("cjk_exception* _e = _h.exception;");
        输出行("cjk_handler_top = _h.prev;");
        输出行("int _caught = 0;");
        for (中间代码.捕获子句 捕获 : 尝试.捕获()) {
            输出行("if (!_caught && cjk_is_instance((cjk_object*)_e, " + 描述符(捕获.类名()) + ")) {");
            缩进++;
            输出行("_caught = 1;");
            原样行("{");
            缩进++;
            输出行("cjk_object* " + 变量名(捕获.变量名()) + " = (cjk_object*)_e;");
            语句(捕获.体());
            缩进--;
            原样行("}");
            缩进--;
            输出行("}");
        }
        生成兜底(尝试.兜底());
        输出行("if (!_caught) {");
        缩进++;
        输出行("cjk_throw(_e, " + 当前行 + ");");
        缩进--;
        输出行("}");
        缩进--;
        输出行("}");
        缩进--;
        原样行("}");
    }

    private void 生成兜底(中间代码.语句 兜底) {
        if (兜底 != null) {
            语句(兜底);
        }
    }

    // ================= 表达式 =================

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
        if (表达式 instanceof 中间代码.字段读 字段读) {
            return 字段读(字段读);
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
            return "(" + 表达式(三目.条件()) + " ? " + 适配(三目.类型(), 三目.真())
                    + " : " + 适配(三目.类型(), 三目.假()) + ")";
        }
        if (表达式 instanceof 中间代码.强制转换 强转) {
            类型 源 = 取类型(强转.操作数());
            if (是泛型值(源) && !是泛型值(强转.类型())) {
                return 从值(强转.类型(), 表达式(强转.操作数()));
            }
            return "((" + C类型(强转.类型()) + ")(" + 表达式(强转.操作数()) + "))";
        }
        if (表达式 instanceof 中间代码.赋值 赋值) {
            return "(" + 表达式(赋值.左()) + " = " + 适配(取类型(赋值.左()), 赋值.右()) + ")";
        }
        if (表达式 instanceof 中间代码.调用 调用) {
            return 调用(调用);
        }
        if (表达式 instanceof 中间代码.新建对象 新建对象) {
            return 新建对象(新建对象);
        }
        if (表达式 instanceof 中间代码.新建数组 新建数组) {
            return 新建数组(新建数组);
        }
        if (表达式 instanceof 中间代码.实例判断 判断) {
            return "cjk_is_instance((cjk_object*)(" + 表达式(判断.值()) + "), "
                    + 描述符(判断.目标类()) + ")";
        }
        if (表达式 instanceof 中间代码.静态字段读 读) {
            return 静态字段名(读.类名(), 读.名());
        }
        if (表达式 instanceof 中间代码.枚举常量读 读) {
            return 静态字段名(读.枚举类(), 读.常量名());
        }
        if (表达式 instanceof 中间代码.枚举全部值 全部) {
            return 枚举数组名(全部.枚举类());
        }
        if (表达式 instanceof 中间代码.枚举按名取值 按名) {
            return 按名C名(按名.枚举类()) + "(" + 表达式(按名.名()) + ", " + 当前行 + ")";
        }
        if (表达式 instanceof 中间代码.顺序表达式 顺序) {
            String 前奏 = 语句序列(顺序.前奏());
            String 结果 = 顺序.结果() == null ? "0" : 表达式(顺序.结果());
            return "({" + (前奏.isEmpty() ? "" : "\n" + 前奏) + 结果 + "; })";
        }
        throw new 生成异常("M4 暂不支持：表达式 " + 表达式.getClass().getSimpleName());
    }

    private String 字段读(中间代码.字段读 读) {
        类型 接收类型 = 取类型(读.接收者());
        String 接收类名 = 接收类型.是类() ? 接收类型.类名 : null;
        中间代码.类定义 声明类 = 接收类名 == null ? null : 查找字段所在类(接收类名, 读.名());
        if (声明类 == null) {
            throw new 生成异常("M4 暂不支持：找不到字段「" + 读.名() + "」的声明类");
        }
        String 接收 = "((" + 结构体名(声明类.名()) + "*)cjk_null_check((cjk_object*)("
                + 表达式(读.接收者()) + "), " + 当前行 + "))";
        return 接收 + "->" + 字段名(读.名());
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
        throw new 生成异常("M4 暂不支持：常量 " + 值类型.文本() + " " + 原文);
    }

    private String 二元(中间代码.二元 二元) {
        String 运算符 = 二元.运算符();
        String 左 = 表达式(二元.左());
        String 右 = 表达式(二元.右());
        if (二元.类型().同型(类型.文本) && "+".equals(运算符)) {
            return "cjk_str_concat(" + 转文本(二元.左(), 左) + ", " + 转文本(二元.右(), 右) + ")";
        }
        if ("==".equals(运算符) || "!=".equals(运算符)) {
            类型 左类型 = 取类型(二元.左());
            类型 右类型 = 取类型(二元.右());
            if (左类型.同型(类型.文本) && !右类型.是空()) {
                String 相等 = "cjk_str_equal(" + 左 + ", " + 右 + ")";
                return "!=".equals(运算符) ? "(!" + 相等 + ")" : 相等;
            }
            if ((左类型.是类() || 左类型.是空()) && (右类型.是类() || 右类型.是空())) {
                String 相等 = "((" + 左 + ") == (" + 右 + "))";
                return "!=".equals(运算符) ? "(!" + 相等 + ")" : 相等;
            }
            if (左类型.同型(类型.文本)) {
                String 相等 = "cjk_str_equal(" + 左 + ", " + 右 + ")";
                return "!=".equals(运算符) ? "(!" + 相等 + ")" : 相等;
            }
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
                String 起始 = 调用.类名().isEmpty() ? 当前类.名() : 调用.类名();
                方法选择 选择 = 最佳方法(起始, 调用.名(), 实参类型(调用.实参()), true);
                if (选择.方法() == null) {
                    throw new 生成异常("M4 暂不支持：找不到静态方法「" + 调用.名() + "」");
                }
                return 函数调用(选择.方法(), 选择.声明类(), null, 调用.实参());
            }
            case 实例: {
                类型 接收类型 = 取类型(调用.接收者());
                String 起始 = 接收类型.是类()
                        ? 接收类型.类名
                        : (调用.类名().isEmpty() ? 当前类.名() : 调用.类名());
                中间代码.方法 目标 = 查找方法(起始, 调用.名(), 实参类型(调用.实参()), false);
                if (目标 == null) {
                    throw new 生成异常("M4 暂不支持：找不到实例方法「" + 调用.名() + "」");
                }
                String 接收 = "((cjk_object*)cjk_null_check((cjk_object*)(" + 表达式(调用.接收者())
                        + "), " + 当前行 + "))";
                StringBuilder 签名 = new StringBuilder("cjk_object*");
                for (中间代码.参数 参数 : 目标.参数()) {
                    签名.append(", ").append(C类型(参数.类型()));
                }
                StringBuilder sb = new StringBuilder("((").append(C类型(目标.返回类型()))
                        .append(" (*)(").append(签名).append("))").append(接收)
                        .append("->cls->methods[").append(槽(目标)).append("])(")
                        .append(接收);
                for (int i = 0; i < 调用.实参().size(); i++) {
                    sb.append(", ").append(适配(目标.参数().get(i).类型(), 调用.实参().get(i)));
                }
                return sb.append(')').toString();
            }
            case 构造器: {
                中间代码.方法 构造器 = 选构造器(调用.类名(), 实参类型(调用.实参()));
                if (构造器 == null) {
                    throw new 生成异常("M4 暂不支持：找不到构造器「" + 调用.类名() + "」");
                }
                StringBuilder sb = new StringBuilder(构造器C名(调用.类名(), 构造器)).append('(')
                        .append(表达式(调用.接收者()));
                for (int i = 0; i < 调用.实参().size(); i++) {
                    sb.append(", ").append(适配(构造器.参数().get(i).类型(), 调用.实参().get(i)));
                }
                return sb.append(')').toString();
            }
            case 父方法: {
                中间代码.方法 目标 = 查找方法(调用.类名(), 调用.名(), 实参类型(调用.实参()), false);
                if (目标 == null) {
                    throw new 生成异常("M4 暂不支持：找不到父类方法「" + 调用.名() + "」");
                }
                方法选择 选择 = 最佳方法(调用.类名(), 调用.名(), 实参类型(调用.实参()), false);
                return 函数调用(目标, 选择.声明类(), 表达式(调用.接收者()), 调用.实参());
            }
            default:
                throw new 生成异常("M4 暂不支持：调用分派 " + 调用.分派());
        }
    }

    private String 函数调用(中间代码.方法 目标, String 声明类, String 接收, List<中间代码.表达式> 实参) {
        StringBuilder sb = new StringBuilder(方法C名(声明类, 目标)).append('(');
        if (接收 != null) {
            sb.append(接收);
            if (!实参.isEmpty()) {
                sb.append(", ");
            }
        }
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(适配(目标.参数().get(i).类型(), 实参.get(i)));
        }
        return sb.append(')').toString();
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
            return "cjk_str_trim(" + 表达式(文本接收者(调用)) + ")";
        }
        if ("长度".equals(名)) {
            String 接 = 表达式(文本接收者(调用));
            switch (接收名(调用)) {
                case "文本": return "cjk_str_len(" + 接 + ", " + 当前行 + ")";
                case "文本构建器": return "cjk_builder_len(" + 接 + ")";
                case "集": return "cjk_set_size(" + 接 + ")";
                case "键值表": return "cjk_map_size(" + 接 + ")";
                case "流": return "cjk_stream_count(" + 接 + ")";
                default: return "cjk_list_size(" + 接 + ")";
            }
        }
        if ("是空".equals(名)) {
            String 接 = 表达式(文本接收者(调用));
            switch (接收名(调用)) {
                case "文本": return "cjk_str_is_empty(" + 接 + ", " + 当前行 + ")";
                case "可选值": return "cjk_optional_is_empty(" + 接 + ")";
                case "键值表": return "cjk_map_is_empty(" + 接 + ")";
                case "集": return "cjk_set_is_empty(" + 接 + ")";
                case "文本构建器": return "(cjk_builder_len(" + 接 + ") == 0)";
                default: return "cjk_list_is_empty(" + 接 + ")";
            }
        }
        if ("相等".equals(名) || "等于".equals(名)) {
            if (调用.接收者() != null) {
                return "cjk_str_equal(" + 表达式(调用.接收者()) + ", "
                        + 表达式(实参.get(0)) + ")";
            }
            return "cjk_str_equal(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ")";
        }
        if ("平方根".equals(名)) {
            return "sqrt((double)(" + 表达式(实参.get(0)) + "))";
        }
        if ("绝对值".equals(名)) {
            中间代码.表达式 值 = 实参.get(0);
            类型 类型 = 取类型(值);
            String c = 表达式(值);
            if (类型.同型(类型.双浮点) || 类型.同型(类型.浮点)) {
                return "fabs((double)(" + c + "))";
            }
            if (类型.同型(类型.长整数)) {
                return "(((int64_t)(" + c + ")) < 0 ? -((int64_t)(" + c + ")) : ((int64_t)(" + c + ")))";
            }
            return "(((int32_t)(" + c + ")) < 0 ? -((int32_t)(" + c + ")) : ((int32_t)(" + c + ")))";
        }
        if ("最大值".equals(名) || "最小值".equals(名)) {
            String 甲 = 表达式(实参.get(0));
            String 乙 = 表达式(实参.get(1));
            String 符 = "最大值".equals(名) ? ">" : "<";
            return "(((" + 甲 + ") " + 符 + " (" + 乙 + ")) ? (" + 甲 + ") : (" + 乙 + "))";
        }
        if ("幂".equals(名)) {
            return "pow((double)(" + 表达式(实参.get(0)) + "), (double)(" + 表达式(实参.get(1)) + "))";
        }
        if ("随机".equals(名)) {
            return "((double)rand() / ((double)RAND_MAX + 1.0))";
        }
        if ("向下取整".equals(名)) {
            return "floor((double)(" + 表达式(实参.get(0)) + "))";
        }
        if ("向上取整".equals(名)) {
            return "ceil((double)(" + 表达式(实参.get(0)) + "))";
        }
        if ("四舍五入".equals(名)) {
            return "((int64_t)llround((double)(" + 表达式(实参.get(0)) + ")))";
        }
        if ("圆周率".equals(名)) {
            return "3.14159265358979323846";
        }
        if ("自然常数".equals(名)) {
            return "2.71828182845904523536";
        }
        if ("整数解析".equals(名)) {
            return "cjk_parse_int(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("长整数解析".equals(名)) {
            return "cjk_parse_i64(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("双浮点解析".equals(名)) {
            return "cjk_parse_f64(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("整数值".equals(名)) {
            类型 类型 = 取类型(实参.get(0));
            String c = 表达式(实参.get(0));
            String 值 = 类型.是类() ? 从值(类型.整数, c) : "((int32_t)(" + c + "))";
            return "cjk_box_value(cjk_v_i32(" + 值 + "))";
        }
        if ("包装取值".equals(名)) {
            return 从值(调用.类型(), 表达式(调用.接收者()));
        }
        if ("包装比较".equals(名)) {
            return "cjk_value_compare(" + 值参(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("转大写".equals(名)) {
            return "cjk_str_upper(" + 表达式(文本接收者(调用)) + ")";
        }
        if ("转小写".equals(名)) {
            return "cjk_str_lower(" + 表达式(文本接收者(调用)) + ")";
        }
        if ("是空白".equals(名)) {
            return "cjk_str_is_blank(" + 表达式(文本接收者(调用)) + ", " + 当前行 + ")";
        }
        if ("取字符".equals(名)) {
            return "cjk_str_char_at(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("含有".equals(名)) {
            String 接 = 表达式(文本接收者(调用));
            String 名2 = 接收名(调用);
            if ("集".equals(名2)) {
                return "cjk_set_contains(" + 接 + ", " + 值参(实参.get(0)) + ")";
            }
            if ("文本".equals(名2)) {
                return "cjk_str_contains(" + 接 + ", " + 表达式(实参.get(0)) + ")";
            }
            return "cjk_list_contains(" + 接 + ", " + 值参(实参.get(0)) + ")";
        }
        if ("以开头".equals(名)) {
            return "cjk_str_starts_with(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ")";
        }
        if ("以结尾".equals(名)) {
            return "cjk_str_ends_with(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ")";
        }
        if ("替换".equals(名)) {
            return "cjk_str_replace(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ")";
        }
        if ("取子串".equals(名)) {
            return "cjk_str_substring(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ", " + 当前行 + ")";
        }
        if ("拆分".equals(名)) {
            return "cjk_str_split(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("复读".equals(名)) {
            return "cjk_str_repeat(" + 表达式(文本接收者(调用)) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("字符串拼接".equals(名)) {
            return "cjk_str_join(" + 表达式(实参.get(0)) + ", (cjk_array*)("
                    + 表达式(实参.get(1)) + "))";
        }
        if ("字符串比较".equals(名)) {
            if (调用.接收者() != null) {
                return "cjk_str_compare(" + 表达式(调用.接收者()) + ", "
                        + 表达式(实参.get(0)) + ")";
            }
            return "cjk_str_compare(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ")";
        }
        if ("异常取消息".equals(名)) {
            return "((cjk_exception*)(" + 表达式(调用.接收者()) + "))->message";
        }
        if ("空操作".equals(名)) {
            return "((void)0)";
        }
        if ("程序参数".equals(名)) {
            return "cjk_program_args()";
        }
        if ("值转文本".equals(名)) {
            return "cjk_value_to_string(" + 值参(实参.get(0)) + ")";
        }
        if ("值相等".equals(名)) {
            return "cjk_value_equal(" + 值参(实参.get(0)) + ", " + 值参(实参.get(1)) + ")";
        }
        if ("值哈希码".equals(名)) {
            return "cjk_value_hash(" + 值参(实参.get(0)) + ")";
        }
        if ("新建清单".equals(名)) {
            return "cjk_list_new()";
        }
        if ("新建集".equals(名)) {
            return "cjk_set_new()";
        }
        if ("新建字典".equals(名)) {
            if (实参.isEmpty()) {
                return "cjk_map_new()";
            }
            return "cjk_map_from(" + (实参.size() / 2) + ", (cjk_value[]){"
                    + 值参列表(实参) + "})";
        }
        if ("新建构建器".equals(名)) {
            if (实参.isEmpty()) {
                return "cjk_builder_new()";
            }
            return "cjk_builder_new()";
        }
        if ("清单从".equals(名) || "集从".equals(名)) {
            String 工厂 = "清单从".equals(名) ? "cjk_list_from" : "cjk_set_from";
            if (实参.isEmpty()) {
                return 工厂 + "(0, NULL)";
            }
            return 工厂 + "(" + 实参.size() + ", (cjk_value[]){" + 值参列表(实参) + "})";
        }
        if ("字典从".equals(名)) {
            return "cjk_map_from(" + (实参.size() / 2) + ", (cjk_value[]){"
                    + 值参列表(实参) + "})";
        }
        if ("清单加入".equals(名)) {
            return "cjk_list_add(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("清单取出".equals(名)) {
            return 从原生值(调用.类型(), "cjk_list_get(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")");
        }
        if ("清单设定".equals(名)) {
            return 从原生值(调用.类型(), "cjk_list_set(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ", " + 值参(实参.get(1)) + ", " + 当前行 + ")");
        }
        if ("清单移除下标".equals(名)) {
            return 从原生值(调用.类型(), "cjk_list_remove_at(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")");
        }
        if ("清单移除值".equals(名)) {
            return "cjk_list_remove_value(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("清单找下标".equals(名)) {
            return "cjk_list_index_of(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("清单元素数".equals(名)) {
            return "cjk_list_size(" + 表达式(调用.接收者()) + ")";
        }
        if ("清单是空".equals(名)) {
            return "cjk_list_is_empty(" + 表达式(调用.接收者()) + ")";
        }
        if ("清单清空".equals(名)) {
            return "(cjk_list_clear(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("清单含有".equals(名)) {
            return "cjk_list_contains(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("清单整理".equals(名)) {
            String 清单 = 表达式(调用.接收者());
            if (实参.isEmpty()) {
                return "(cjk_list_sort(" + 清单 + "), (void)0)";
            }
            return "(cjk_list_sort_by(" + 清单 + ", " + 表达式(实参.get(0)) + "), (void)0)";
        }
        if ("清单流".equals(名)) {
            return "cjk_list_stream(" + 表达式(调用.接收者()) + ")";
        }
        if ("清单值集合".equals(名)) {
            return "cjk_list_to_set(" + 表达式(调用.接收者()) + ")";
        }
        if ("集加入".equals(名)) {
            return "cjk_set_add(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("集移除".equals(名)) {
            return "cjk_set_remove(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("集含有".equals(名)) {
            return "cjk_set_contains(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("集元素数".equals(名)) {
            return "cjk_set_size(" + 表达式(调用.接收者()) + ")";
        }
        if ("集是空".equals(名)) {
            return "cjk_set_is_empty(" + 表达式(调用.接收者()) + ")";
        }
        if ("集清空".equals(名)) {
            return "(cjk_set_clear(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("集取出".equals(名)) {
            return 从原生值(调用.类型(), "cjk_set_get(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ", " + 当前行 + ")");
        }
        if ("集流".equals(名)) {
            return "cjk_set_stream(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典放入".equals(名)) {
            return "cjk_map_put(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0))
                    + ", " + 值参(实参.get(1)) + ")";
        }
        if ("字典取出".equals(名)) {
            return 从原生值(调用.类型(), "cjk_map_get(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ")");
        }
        if ("字典取出或".equals(名)) {
            return 从原生值(调用.类型(), "cjk_map_get_or(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ", " + 值参(实参.get(1)) + ")");
        }
        if ("字典移除".equals(名)) {
            return 从原生值(调用.类型(), "cjk_map_remove(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ")");
        }
        if ("字典键集合".equals(名)) {
            return "cjk_map_keys(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典值集合".equals(名)) {
            return "cjk_map_values(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典含键".equals(名)) {
            return "cjk_map_contains_key(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("字典含值".equals(名)) {
            return "cjk_map_contains_value(" + 表达式(调用.接收者()) + ", " + 值参(实参.get(0)) + ")";
        }
        if ("字典元素数".equals(名)) {
            return "cjk_map_size(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典是空".equals(名)) {
            return "cjk_map_is_empty(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典清空".equals(名)) {
            return "(cjk_map_clear(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("字典流".equals(名)) {
            return "cjk_map_stream(" + 表达式(调用.接收者()) + ")";
        }
        if ("字典遍历".equals(名)) {
            return "(cjk_map_for_each(" + 表达式(调用.接收者()) + ", " + 表达式(实参.get(0))
                    + ", " + 回调引用(实参.get(0)) + "), (void)0)";
        }
        if ("构建器追加".equals(名)) {
            return "cjk_builder_append(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ")";
        }
        if ("构建器转文本".equals(名)) {
            return "cjk_builder_to_string(" + 表达式(调用.接收者()) + ")";
        }
        if ("构建器长度".equals(名)) {
            return "cjk_builder_len(" + 表达式(调用.接收者()) + ")";
        }
        if ("构建器清空".equals(名)) {
            return "(cjk_builder_clear(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("路径取".equals(名)) {
            if (实参.isEmpty()) {
                return "cjk_path_of(0, NULL)";
            }
            return "cjk_path_of(" + 实参.size() + ", (cjk_string*[]){"
                    + 表达式列表(实参) + "})";
        }
        if ("路径上级".equals(名)) {
            return "cjk_path_parent(" + 表达式(调用.接收者()) + ")";
        }
        if ("路径绝对".equals(名)) {
            return "cjk_path_absolute(" + 表达式(调用.接收者()) + ")";
        }
        if ("路径文件名".equals(名)) {
            return "cjk_path_file_name(" + 表达式(调用.接收者()) + ")";
        }
        if ("路径转文本".equals(名)) {
            return "cjk_path_to_string(" + 表达式(调用.接收者()) + ")";
        }
        if ("文件读文本".equals(名)) {
            return "cjk_files_read_text(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("文件写文本".equals(名)) {
            return "(cjk_files_write_text(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + "), (void)0)";
        }
        if ("文件建目录".equals(名)) {
            return "(cjk_files_create_directories(" + 表达式(实参.get(0)) + ", "
                    + 当前行 + "), (void)0)";
        }
        if ("文件读行".equals(名)) {
            return "cjk_files_read_all_lines(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("文件存在".equals(名)) {
            return "cjk_files_exists(" + 表达式(实参.get(0)) + ")";
        }
        if ("文件大小".equals(名)) {
            return "cjk_files_size(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("文件删除".equals(名)) {
            return "(cjk_files_delete(" + 表达式(实参.get(0)) + ", " + 当前行 + "), (void)0)";
        }
        if ("标准文件删除".equals(名)) {
            return "(cjk_files_delete_if_exists(" + 表达式(实参.get(0)) + ", " + 当前行
                    + "), (void)0)";
        }
        if ("文件列举".equals(名)) {
            return "cjk_files_list(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("文件复制".equals(名)) {
            return "(cjk_files_copy(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + "), (void)0)";
        }
        if ("文件移动".equals(名)) {
            return "(cjk_files_move(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + "), (void)0)";
        }
        if ("可选值空".equals(名)) {
            return "cjk_optional_empty()";
        }
        if ("可选值装入".equals(名)) {
            return "cjk_optional_of(" + 值参(实参.get(0)) + ")";
        }
        if ("可选值可空".equals(名)) {
            return "cjk_optional_of_nullable(" + 值参(实参.get(0)) + ")";
        }
        if ("可选值或".equals(名)) {
            return 从原生值(调用.类型(), "cjk_optional_or(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ")");
        }
        if ("可选值是空".equals(名)) {
            return "cjk_optional_is_empty(" + 表达式(调用.接收者()) + ")";
        }
        if ("可选值存在".equals(名)) {
            return "cjk_optional_is_present(" + 表达式(调用.接收者()) + ")";
        }
        if ("可选值取出".equals(名)) {
            return 从原生值(调用.类型(), "cjk_optional_get(" + 表达式(调用.接收者()) + ", "
                    + 当前行 + ")");
        }
        if ("可选值存在时".equals(名)) {
            return "(cjk_optional_if_present(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ", " + 回调引用(实参.get(0)) + "), (void)0)";
        }
        if ("流过滤".equals(名)) {
            return "cjk_stream_filter(" + 表达式(调用.接收者()) + ", " + 表达式(实参.get(0))
                    + ", " + 回调引用(实参.get(0)) + ")";
        }
        if ("流变换".equals(名)) {
            return "cjk_stream_map(" + 表达式(调用.接收者()) + ", " + 表达式(实参.get(0))
                    + ", " + 回调引用(实参.get(0)) + ")";
        }
        if ("流遍历".equals(名)) {
            return "(cjk_stream_for_each(" + 表达式(调用.接收者()) + ", " + 表达式(实参.get(0))
                    + ", " + 回调引用(实参.get(0)) + "), (void)0)";
        }
        if ("流收集清单".equals(名)) {
            return "cjk_stream_collect_list(" + 表达式(调用.接收者()) + ")";
        }
        if ("流收集集".equals(名)) {
            return "cjk_stream_collect_set(" + 表达式(调用.接收者()) + ")";
        }
        if ("流收集拼接".equals(名)) {
            return "cjk_stream_collect_join(" + 表达式(调用.接收者()) + ", "
                    + 表达式(实参.get(0)) + ")";
        }
        if ("流找首个".equals(名)) {
            return "cjk_stream_find_first(" + 表达式(调用.接收者()) + ")";
        }
        if ("流元素数".equals(名)) {
            return "cjk_stream_count(" + 表达式(调用.接收者()) + ")";
        }
        if ("流去重".equals(名)) {
            return "cjk_stream_distinct(" + 表达式(调用.接收者()) + ")";
        }
        if ("流排序".equals(名)) {
            return "cjk_stream_sorted(" + 表达式(调用.接收者()) + ")";
        }
        if ("收集器清单".equals(名)) {
            return "cjk_collector_list()";
        }
        if ("收集器集".equals(名)) {
            return "cjk_collector_set()";
        }
        if ("收集器拼接".equals(名)) {
            return "cjk_collector_join(" + 表达式(实参.get(0)) + ")";
        }
        if ("比较器按整数".equals(名)) {
            return "cjk_comparator_by_int(" + 表达式(实参.get(0)) + ", "
                    + 回调引用(实参.get(0)) + ")";
        }
        if ("比较器反序".equals(名)) {
            return "cjk_comparator_reversed(" + 表达式(调用.接收者()) + ")";
        }
        if ("比较器比较".equals(名)) {
            return "cjk_comparator_compare(" + 表达式(调用.接收者()) + ", "
                    + 值参(实参.get(0)) + ", " + 值参(实参.get(1)) + ")";
        }
        if ("集合排序".equals(名)) {
            return "(cjk_list_sort(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("集合反序".equals(名)) {
            return "(cjk_list_reverse(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("集合打乱".equals(名)) {
            return "(cjk_list_shuffle(" + 表达式(调用.接收者()) + "), (void)0)";
        }
        if ("集合最大".equals(名)) {
            return 从原生值(调用.类型(), "cjk_list_max(" + 表达式(调用.接收者()) + ", " + 当前行 + ")");
        }
        if ("集合最小".equals(名)) {
            return 从原生值(调用.类型(), "cjk_list_min(" + 表达式(调用.接收者()) + ", " + 当前行 + ")");
        }
        // ---------- M5：中文.标准库 ----------
        if ("输出打印".equals(名) || "输出打印多".equals(名)) {
            return "(cjk_print_values(" + 实参.size() + ", " + 值数组(实参, 0) + "), (void)0)";
        }
        if ("输出打印行".equals(名) || "输出打印行多".equals(名)) {
            return "(cjk_println_values(" + 实参.size() + ", " + 值数组(实参, 0) + "), (void)0)";
        }
        if ("输出换行".equals(名)) {
            return "(cjk_print_newline(), (void)0)";
        }
        if ("输出错误".equals(名) || "输出错误多".equals(名)) {
            return "(cjk_eprintln_values(" + 实参.size() + ", " + 值数组(实参, 0) + "), (void)0)";
        }
        if ("输出格式".equals(名)) {
            return "(cjk_output_format(" + 表达式(实参.get(0)) + ", " + (实参.size() - 1) + ", "
                    + 值数组(实参, 1) + ", " + 当前行 + "), (void)0)";
        }
        if ("文本是否为空".equals(名)) {
            return "cjk_str_is_null_or_empty(" + 表达式(实参.get(0)) + ")";
        }
        if ("文本是否空白".equals(名)) {
            return "cjk_str_is_null_or_blank(" + 表达式(实参.get(0)) + ")";
        }
        if ("文本去空白".equals(名)) {
            return "cjk_str_strip(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("文本连接".equals(名)) {
            return "cjk_str_join_values(" + 表达式(实参.get(0)) + ", " + (实参.size() - 1) + ", "
                    + 值数组(实参, 1) + ")";
        }
        if ("数值是整数".equals(名)) {
            return "cjk_str_is_integer(" + 表达式(实参.get(0)) + ")";
        }
        if ("数值随机整数1".equals(名)) {
            return "cjk_random_int(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("数值随机整数2".equals(名)) {
            return "cjk_random_range(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + ")";
        }
        if ("集合填充".equals(名)) {
            return "(cjk_list_fill(" + 表达式(实参.get(0)) + ", " + 值参(实参.get(1))
                    + "), (void)0)";
        }
        if ("时间今天".equals(名)) {
            return "cjk_date_today()";
        }
        if ("时间此刻".equals(名)) {
            return "cjk_datetime_now()";
        }
        if ("时间戳".equals(名)) {
            return "cjk_now_millis()";
        }
        if ("时间格式化".equals(名)) {
            return "cjk_datetime_format(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + ")";
        }
        if ("时间相差天数".equals(名)) {
            return "cjk_date_between(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ")";
        }
        if ("时间加天数".equals(名)) {
            return "cjk_date_plus_days(" + 表达式(实参.get(0)) + ", (int64_t)("
                    + 表达式(实参.get(1)) + "))";
        }
        if ("标准文件写文本".equals(名)) {
            return "(cjk_files_write_text_parents(" + 表达式(实参.get(0)) + ", "
                    + 表达式(实参.get(1)) + ", " + 当前行 + "), (void)0)";
        }
        if ("并发固定线程池".equals(名)) {
            return "cjk_pool_new(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("并发单线程池".equals(名)) {
            return "cjk_pool_new(1, " + 当前行 + ")";
        }
        if ("并发执行".equals(名)) {
            return "(cjk_pool_execute(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ", "
                    + 回调引用(实参.get(1)) + "), (void)0)";
        }
        if ("并发提交".equals(名)) {
            return "cjk_pool_submit(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1)) + ", "
                    + 回调引用(实参.get(1)) + ")";
        }
        if ("并发取结果".equals(名)) {
            return 从原生值(调用.类型(), "cjk_future_get(" + 表达式(实参.get(0)) + ", "
                    + 当前行 + ")");
        }
        if ("并发关闭".equals(名)) {
            return "(cjk_pool_shutdown(" + 表达式(实参.get(0)) + "), (void)0)";
        }
        if ("并发休眠".equals(名)) {
            return "(cjk_sleep((int64_t)(" + 表达式(实参.get(0)) + ")), (void)0)";
        }
        if ("并发当前线程名".equals(名)) {
            return "cjk_thread_name()";
        }
        if ("网络取文本".equals(名)) {
            return "cjk_http_get_text(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("网络取状态码".equals(名)) {
            return "cjk_http_status(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("网络取".equals(名)) {
            return "cjk_http_get(" + 表达式(实参.get(0)) + ", " + 当前行 + ")";
        }
        if ("网络下载到".equals(名)) {
            return "(cjk_http_download(" + 表达式(实参.get(0)) + ", " + 表达式(实参.get(1))
                    + ", " + 当前行 + "), (void)0)";
        }
        if ("网络响应状态码".equals(名)) {
            return "cjk_http_response_status(" + 表达式(调用.接收者()) + ")";
        }
        if ("网络响应正文".equals(名)) {
            return "cjk_http_response_body(" + 表达式(调用.接收者()) + ")";
        }
        if ("日志取".equals(名)) {
            return "cjk_logger_get(" + 表达式(实参.get(0)) + ")";
        }
        if ("日志控制台".equals(名)) {
            return "cjk_logger_console(" + 表达式(实参.get(0)) + ")";
        }
        if ("日志信息".equals(名) || "日志警告".equals(名)
                || "日志错误".equals(名) || "日志调试".equals(名)) {
            int 级别 = switch (名) {
                case "日志调试" -> 1;
                case "日志信息" -> 2;
                case "日志警告" -> 3;
                default -> 4;
            };
            return "(cjk_logger_log(" + 表达式(实参.get(0)) + ", " + 级别 + ", "
                    + 值参(实参.get(1)) + ", " + 当前行 + "), (void)0)";
        }
        if ("日志设置级别".equals(名)) {
            if (取类型(实参.get(1)).同型(类型.文本)) {
                return "(cjk_logger_set_level_name(" + 表达式(实参.get(0)) + ", "
                        + 表达式(实参.get(1)) + ", " + 当前行 + "), (void)0)";
            }
            return "(cjk_logger_set_level(" + 表达式(实参.get(0)) + ", "
                    + 表达式(实参.get(1)) + "), (void)0)";
        }
        if ("数据转JSON".equals(名) || "数据转JSON美化".equals(名)) {
            String 美化 = "数据转JSON美化".equals(名) && 实参.size() >= 2
                    ? 表达式(实参.get(1)) : "0";
            类型 参数类型 = 取类型(实参.get(0));
            if (参数类型.是数组()) {
                类型 元素 = 参数类型.元素;
                if (元素.是数组()) {
                    throw new 生成异常("M5 暂不支持：数据.转JSON 的多维数组，请先转为清单");
                }
                String 文本元素 = 元素.同型(类型.文本) ? "1" : "0";
                return "cjk_json_encode_array((cjk_array*)(" + 表达式(实参.get(0)) + "), "
                        + 种类码(元素) + ", (int32_t)sizeof(" + C类型(元素) + "), "
                        + 文本元素 + ", " + 美化 + ", " + 当前行 + ")";
            }
            return "cjk_json_encode(" + 值参(实参.get(0)) + ", " + 美化 + ", " + 当前行 + ")";
        }
        throw new 生成异常("M5 暂不支持：内建「" + 名 + "」");
    }

    /** 实参区间 → cjk_value 复合字面量数组。 */
    private String 值数组(List<中间代码.表达式> 实参, int 起点) {
        if (起点 >= 实参.size()) {
            return "((cjk_value *)0)";
        }
        StringBuilder sb = new StringBuilder("(cjk_value[]){");
        for (int i = 起点; i < 实参.size(); i++) {
            if (i > 起点) {
                sb.append(", ");
            }
            sb.append(值参(实参.get(i)));
        }
        return sb.append('}').toString();
    }

    private static String 规范接收名(String 名) {
        return switch (名) {
            case "数组清单" -> "清单";
            case "链式键值表", "哈希表" -> "键值表";
            case "散列集" -> "集";
            default -> 名;
        };
    }

    private String 接收名(中间代码.调用 调用) {
        中间代码.表达式 接收 = 调用.接收者();
        if (接收 == null) {
            return "";
        }
        类型 类型 = 取类型(接收);
        if (类型.是类()) {
            return 规范接收名(类型.类名);
        }
        if (类型.同型(类型.文本)) {
            return "文本";
        }
        return 类型.文本();
    }

    private String 回调引用(中间代码.表达式 实参) {
        类型 类型 = 取类型(实参);
        if (类型 == null || !类型.是类()) {
            throw new 生成异常("M5 暂不支持：该内建需要 λ/方法引用参数");
        }
        return 回调C名(类型.类名);
    }

    private String 值参列表(List<中间代码.表达式> 实参) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(值参(实参.get(i)));
        }
        return sb.toString();
    }

    private String 表达式列表(List<中间代码.表达式> 实参) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(表达式(实参.get(i)));
        }
        return sb.toString();
    }

    private 中间代码.表达式 文本接收者(中间代码.调用 调用) {
        if (调用.接收者() != null) {
            return 调用.接收者();
        }
        return 调用.实参().get(0);
    }

    private String 新建对象(中间代码.新建对象 新建对象) {
        if (内建类名.contains(新建对象.类名())) {
            String 消息 = 新建对象.实参().isEmpty()
                    ? "NULL" : 表达式(新建对象.实参().get(0));
            return "((cjk_object*)cjk_new_exception(&CJK_CLS_" + 新建对象.类名() + ", " + 消息 + "))";
        }
        return 新建调用(新建对象.类名(), 新建对象.实参());
    }

    private String 新建调用(String 类名, List<中间代码.表达式> 实参) {
        List<类型> 实参类型 = 实参类型(实参);
        中间代码.方法 构造器 = 选构造器(类名, 实参类型);
        中间代码.类定义 类 = 类定义(类名);
        if (类 != null && !类.构造器().isEmpty() && 构造器 == null) {
            throw new 生成异常("M4 暂不支持：找不到「" + 类名 + "」的匹配构造器");
        }
        StringBuilder sb = new StringBuilder(新建C名(类名, 构造器)).append('(');
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (构造器 != null && i < 构造器.参数().size()) {
                sb.append(适配(构造器.参数().get(i).类型(), 实参.get(i)));
            } else {
                sb.append(表达式(实参.get(i)));
            }
        }
        return sb.append(')').toString();
    }

    private String 实参文本(List<中间代码.表达式> 实参) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(表达式(实参.get(i)));
        }
        return sb.toString();
    }

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
        if (是泛型值(值类型) || 是包装类(值类型)) {
            return "cjk_value_to_string(cjk_unbox_value(" + 已生成 + ", " + 当前行 + "))";
        }
        if (值类型.同型(类型.文本)) {
            return 已生成;
        }
        if (值类型.是空()) {
            return 已生成;
        }
        if (值类型.是类()) {
            中间代码.方法 转文本 = 查找方法(值类型.类名, "转文本", List.of(), false);
            if (转文本 != null) {
                return "cjk_virtual_to_string((cjk_object*)(" + 已生成 + "), " + 槽(转文本) + ")";
            }
            return "cjk_object_to_string((cjk_object*)(" + 已生成 + "))";
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
        throw new 生成异常("M4 暂不支持：把 " + 值类型.文本() + " 拼接到字符串");
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

    // ================= 查找 =================

    private 类型 取类型(中间代码.表达式 表达式) {
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
        if (表达式 instanceof 中间代码.实例判断 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.静态字段读 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.枚举常量读 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.枚举全部值 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.枚举按名取值 值) {
            return 值.类型();
        }
        if (表达式 instanceof 中间代码.顺序表达式 值) {
            return 值.类型();
        }
        throw new 生成异常("M4 暂不支持：未知表达式类型");
    }

    private List<类型> 实参类型(List<中间代码.表达式> 实参) {
        List<类型> 结果 = new ArrayList<>();
        for (中间代码.表达式 项 : 实参) {
            结果.add(取类型(项));
        }
        return 结果;
    }

    private 中间代码.类定义 类定义(String 名) {
        return 类表.get(名);
    }

    private 中间代码.类定义 父类定义(中间代码.类定义 类) {
        if (类 == null) {
            return null;
        }
        String 父 = 父名(类);
        return "对象".equals(父) ? null : 类表.get(父);
    }

    private static String 父名(中间代码.类定义 类) {
        return 类.父类() == null || 类.父类().isEmpty() ? "对象" : 类.父类();
    }

    private 中间代码.类定义 查找字段所在类(String 起始, String 字段) {
        中间代码.类定义 当前 = 类定义(起始);
        while (当前 != null) {
            for (中间代码.字段定义 定义 : 当前.字段()) {
                if (定义.名().equals(字段)) {
                    return 当前;
                }
            }
            当前 = 父类定义(当前);
        }
        return null;
    }

    private 中间代码.方法 查找方法(String 起始, String 名, List<类型> 实参类型, boolean 要静态) {
        return 最佳方法(起始, 名, 实参类型, 要静态).方法();
    }

    /** 重载解析：继承链上所有候选取匹配分最高者，同分时子类优先。 */
    private 方法选择 最佳方法(String 起始, String 名, List<类型> 实参类型, boolean 要静态) {
        中间代码.方法 最佳 = null;
        int 最佳分 = -1;
        中间代码.类定义 当前 = 类定义(起始);
        while (当前 != null) {
            for (中间代码.方法 方法 : 当前.方法()) {
                if (方法.静态() != 要静态 || !方法.名().equals(名)
                        || 方法.参数().size() != 实参类型.size()) {
                    continue;
                }
                int 分 = 匹配(方法, 实参类型);
                if (分 > 最佳分) {
                    最佳 = 方法;
                    最佳分 = 分;
                }
            }
            当前 = 父类定义(当前);
        }
        if (最佳 != null) {
            return new 方法选择(最佳, 声明类名(起始, 最佳, 实参类型, 要静态));
        }
        /* 接口方法：按实现类声明的顺序查找。 */
        当前 = 类定义(起始);
        while (当前 != null) {
            for (String 接口名 : 当前.接口()) {
                方法选择 选择 = 最佳方法(接口名, 名, 实参类型, 要静态);
                if (选择.方法() != null) {
                    return 选择;
                }
            }
            当前 = 父类定义(当前);
        }
        return new 方法选择(null, 起始);
    }

    private record 方法选择(中间代码.方法 方法, String 声明类) {}

    private String 声明类名(String 起始, 中间代码.方法 目标, List<类型> 实参类型, boolean 要静态) {
        中间代码.类定义 当前 = 类定义(起始);
        while (当前 != null) {
            for (中间代码.方法 方法 : 当前.方法()) {
                if (方法.静态() != 要静态 || !方法.名().equals(目标.名())
                        || 方法.参数().size() != 实参类型.size()) {
                    continue;
                }
                if (方法 == 目标 || 同一签名(方法, 目标)) {
                    return 当前.名();
                }
            }
            当前 = 父类定义(当前);
        }
        return 起始;
    }

    private static boolean 同一签名(中间代码.方法 甲, 中间代码.方法 乙) {
        if (!甲.名().equals(乙.名()) || 甲.参数().size() != 乙.参数().size()) {
            return false;
        }
        for (int i = 0; i < 甲.参数().size(); i++) {
            if (!甲.参数().get(i).类型().同型(乙.参数().get(i).类型())) {
                return false;
            }
        }
        return true;
    }

    /** 返回匹配分，负数表示不匹配。 */
    private int 匹配(中间代码.方法 方法, List<类型> 实参类型) {
        int 分 = 0;
        for (int i = 0; i < 实参类型.size(); i++) {
            类型 参数类型 = 方法.参数().get(i).类型();
            类型 实参 = 实参类型.get(i);
            if (是泛型值(参数类型) || 是包装类(参数类型)) {
                分 += 2;
                continue;
            }
            if (参数类型.同型(实参)) {
                分 += 3;
            } else if (可赋值(参数类型, 实参)) {
                分 += 1;
            } else if (参数类型.是数值() && 实参.是数值()) {
                分 += 0;
            } else {
                return -1;
            }
        }
        return 分;
    }

    private boolean 可赋值(类型 目标, 类型 源) {
        if (目标.同型(源) || 源.是空()) {
            return true;
        }
        if (是泛型值(目标) || 是泛型值(源) || 是包装类(目标) || 是包装类(源)) {
            return true;
        }
        if (目标.是类() && "对象".equals(目标.类名)) {
            return 源.是类();
        }
        if (!目标.是类() || !源.是类()) {
            return 目标.可赋值自(源);
        }
        中间代码.类定义 当前 = 类定义(源.类名);
        while (当前 != null) {
            if (当前.名().equals(目标.类名)) {
                return true;
            }
            for (String 接口 : 当前.接口()) {
                if (接口.equals(目标.类名)) {
                    return true;
                }
            }
            当前 = 父类定义(当前);
        }
        return false;
    }

    private 中间代码.方法 选构造器(String 类名, List<类型> 实参类型) {
        中间代码.类定义 类 = 类定义(类名);
        if (类 == null) {
            return null;
        }
        中间代码.方法 最佳 = null;
        int 最佳分 = -1;
        for (中间代码.方法 方法 : 类.构造器()) {
            if (方法.参数().size() != 实参类型.size()) {
                continue;
            }
            int 分 = 匹配(方法, 实参类型);
            if (分 > 最佳分) {
                最佳 = 方法;
                最佳分 = 分;
            }
        }
        return 最佳;
    }

    // ================= 名字 =================

    private String 方法C名(String 类名, 中间代码.方法 方法) {
        StringBuilder sb = new StringBuilder("_cjkf_").append(编码(类名)).append('_');
        if (方法.名().equals("<入口>")) {
            return sb.append("entry").toString();
        }
        sb.append(编码(方法.名()));
        for (中间代码.参数 参数 : 方法.参数()) {
            sb.append('_').append(类型码(参数.类型()));
        }
        return sb.toString();
    }

    private String 构造器C名(String 类名, 中间代码.方法 方法) {
        StringBuilder sb = new StringBuilder("_cjkctor_").append(编码(类名)).append('_')
                .append(参数签名(参数类型(方法)));
        return sb.toString();
    }

    private String 新建C名(String 类名, 中间代码.方法 方法) {
        return "_cjknew_" + 编码(类名) + "_"
                + (方法 == null ? "void" : 参数签名(参数类型(方法)));
    }

    private static String 按名C名(String 类名) {
        return "_cjkenumval_" + 编码(类名);
    }

    private static String 描述符(String 类名) {
        if (内建类名.contains(类名) || 标准库描述符类名.contains(类名)) {
            return "&CJK_CLS_" + 类名;
        }
        return "&_cjkcls_" + 编码(类名);
    }

    private static String 描述符名(String 类名) {
        return "_cjkcls_" + 编码(类名);
    }

    private static String 结构体名(String 类名) {
        return "_cjkobj_" + 编码(类名);
    }

    private static String 槽表名(String 类名) {
        return "_cjkmt_" + 编码(类名);
    }

    private static String 接口表名(String 类名) {
        return "_cjkif_" + 编码(类名);
    }

    private static String 枚举数组名(String 类名) {
        return "_cjkenum_" + 编码(类名);
    }

    private static String 静态字段名(String 类名, String 字段) {
        return "_cjksf_" + 编码(类名) + "_" + 编码(字段);
    }

    private static String 字段名(String 名) {
        return "_f_" + 编码(名);
    }

    private String 参数列表(中间代码.方法 方法, boolean 实例) {
        List<String> 项 = new ArrayList<>();
        if (实例) {
            项.add("cjk_object* self");
        }
        for (中间代码.参数 参数 : 方法.参数()) {
            项.add(C类型(参数.类型()) + " " + 变量名(参数.名()));
        }
        return 项.isEmpty() ? "void" : String.join(", ", 项);
    }

    private static List<类型> 参数类型(中间代码.方法 方法) {
        List<类型> 结果 = new ArrayList<>();
        for (中间代码.参数 参数 : 方法.参数()) {
            结果.add(参数.类型());
        }
        return 结果;
    }

    private static String 参数签名(List<类型> 类型们) {
        if (类型们.isEmpty()) {
            return "void";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 类型们.size(); i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(类型码(类型们.get(i)));
        }
        return sb.toString();
    }

    private static String 变量名(String 名) {
        if ("这".equals(名)) {
            return "self";
        }
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
            return "cjk_object*";
        }
        if (类型.是错误()) {
            throw new 生成异常("M4 暂不支持：错误类型");
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
            return "a_" + 类型码(类型.元素);
        }
        if (类型.是类()) {
            return "o_" + 编码(类型.类名);
        }
        if (类型.是空()) {
            return "o_null";
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

    // ================= 字面量 =================

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

    // ================= 输出 =================

    private void 输出行(String 文本) {
        输出.append("    ".repeat(缩进)).append(文本).append('\n');
    }

    private void 原样行(String 文本) {
        输出.append(文本).append('\n');
    }

    private void outputLine(String 文本) {
        输出行(文本);
    }
}
