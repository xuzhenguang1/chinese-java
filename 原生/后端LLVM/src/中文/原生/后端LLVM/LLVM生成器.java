package 中文.原生.后端LLVM;

import 中文.原生.中间.中间代码;
import 中文.原生.中间.类型;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M6 LLVM 后端：把 MIR 直接降级为 LLVM IR（opaque ptr + entry 块 alloca）。
 *
 * 命名、槽位、装箱与内建调用规则与 M4/M5 的 C 后端保持同一套语义；
 * 运行时仍链接 原生\运行时 的 C 实现，DWARF 行号指向中文源文件。
 */
public final class LLVM生成器 {

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

    private static final Set<String> 标准库描述符类名 = Set.of(
            "本地日期", "本地日期时间", "响应", "线程池", "未来", "日志记录器");

    private static final String 泛型值名 = "__值";

    private static final Set<String> 包装类名 = Set.of(
            "整数包装", "长整数包装", "短整数包装", "字节包装",
            "浮点包装", "双浮点包装", "布尔包装", "字符包装");

    private static final String 系统行 = "x86_64-w64-windows-gnu";

    private record 值(类型 类型, String LLVM类型, String 文本) {
        值(类型 类型, String 文本) {
            this(类型, LLVM生成器.LLVM类型(类型), 文本);
        }
    }

    private record 变量槽(类型 类型, String 指针) {}

    private record 字段槽(类型 类型, String 名, int 下标) {}

    private record 循环标签(String 跳出, String 继续) {}

    private record 方法选择(中间代码.方法 方法, String 声明类) {}

    private final 中间代码.程序 程序;
    private final 中间代码.位置信息 位置;
    private final IR输出 IR = new IR输出();
    private final Map<String, 中间代码.类定义> 类表 = new LinkedHashMap<>();
    private final Map<String, Integer> 槽表 = new LinkedHashMap<>();
    private final Map<String, List<字段槽>> 字段布局 = new LinkedHashMap<>();
    private final Set<String> 已声明描述符 = new LinkedHashSet<>();
    private final Map<String, String> 位置缓存 = new LinkedHashMap<>();
    private final Deque<Map<String, 变量槽>> 作用域 = new ArrayDeque<>();
    private final Deque<循环标签> 循环栈 = new ArrayDeque<>();
    private 中间代码.类定义 当前类;
    private 类型 当前返回类型 = 类型.无;
    private String 源路径 = "";
    private String 源文件名 = "";
    private String 源目录 = "";
    private int 当前行;
    private String 当前函数元数据;
    private String 当前函数头;
    private StringBuilder 入口块 = new StringBuilder();
    private StringBuilder 函数体 = new StringBuilder();
    private String 当前块 = "entry";
    private boolean 块已终结;
    private int 临时序号;
    private int 标签序号;

    private LLVM生成器(中间代码.程序 程序) {
        this.程序 = 程序;
        this.位置 = 程序.位置();
        this.源路径 = 位置 == null ? "" : 位置.源路径;
        int 斜杠 = Math.max(源路径.lastIndexOf('/'), 源路径.lastIndexOf('\\'));
        this.源文件名 = 斜杠 >= 0 ? 源路径.substring(斜杠 + 1) : 源路径;
        this.源目录 = 斜杠 >= 0 ? 源路径.substring(0, 斜杠) : "";
        if (源文件名.isEmpty()) {
            this.源文件名 = "程序.java";
        }
        for (中间代码.类定义 类 : 程序.类()) {
            类表.put(类.名(), 类);
        }
        收集槽();
        收集字段();
    }

    public static String 生成(中间代码.程序 程序) {
        LLVM生成器 生成器 = new LLVM生成器(程序);
        生成器.生成程序();
        return 生成器.IR.组装(生成器.模块头());
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
        StringBuilder 文 = new StringBuilder(方法.名()).append('(');
        for (int i = 0; i < 方法.参数().size(); i++) {
            if (i > 0) {
                文.append(',');
            }
            文.append(类型码(方法.参数().get(i).类型()));
        }
        return 文.append(')').toString();
    }

    private int 槽(中间代码.方法 方法) {
        Integer 值 = 槽表.get(槽键(方法));
        if (值 == null) {
            throw new 生成异常("M6 暂不支持：方法「" + 方法.名() + "」没有可用的虚调用槽位");
        }
        return 值;
    }

    private void 收集字段() {
        for (中间代码.类定义 类 : 程序.类()) {
            List<字段槽> 布局 = new ArrayList<>();
            Set<String> 已有 = new LinkedHashSet<>();
            List<中间代码.类定义> 祖先 = new ArrayList<>();
            中间代码.类定义 当前 = 父类定义(类);
            while (当前 != null) {
                祖先.add(0, 当前);
                当前 = 父类定义(当前);
            }
            int 下标 = 1;
            for (中间代码.类定义 项 : 祖先) {
                for (中间代码.字段定义 字段 : 项.字段()) {
                    if (已有.add(字段.名())) {
                        布局.add(new 字段槽(字段.类型(), 字段.名(), 下标++));
                    }
                }
            }
            for (中间代码.字段定义 字段 : 类.字段()) {
                if (已有.add(字段.名())) {
                    布局.add(new 字段槽(字段.类型(), 字段.名(), 下标++));
                }
            }
            字段布局.put(类.名(), 布局);
        }
    }

    private int 字段下标(String 类名, String 字段) {
        List<字段槽> 布局 = 字段布局.get(类名);
        if (布局 != null) {
            for (字段槽 项 : 布局) {
                if (项.名().equals(字段)) {
                    return 项.下标();
                }
            }
        }
        throw new 生成异常("M6 暂不支持：找不到字段「" + 字段 + "」在「" + 类名 + "」中的位置");
    }

    // ================= 模块 =================

    private String 模块头() {
        return "; 由 cjc 原生后端（M6 LLVM）生成，请勿手工修改。\n"
                + "source_filename = \"" + 转义源(源文件名) + "\"\n"
                + "target datalayout = \"e-m:w-p270:32:32-p271:32:32-p272:64:64"
                + "-i64:64-i128:128-f80:128-n8:16:32:64-S128\"\n"
                + "target triple = \"" + 系统行 + "\"\n\n";
    }

    private void 生成程序() {
        IR.初始化调试信息(源文件名, 源目录);
        输出公共类型();
        输出结构体();
        生成静态全局();
        生成槽表();
        生成描述符();
        生成回调包装();
        生成初始化();
        生成入口();
        定义函数();
    }

    private void 输出公共类型() {
        IR.类型定义("%cjk_string = type { i32, ptr }");
        IR.类型定义("%cjk_array = type { i32, i32, ptr }");
        IR.类型定义("%cjk_object = type { ptr }");
        IR.类型定义("%cjk_class = type { i32, ptr, ptr, i32, ptr, ptr, i32, ptr }");
        IR.类型定义("%cjk_exception = type { ptr, ptr }");
        IR.类型定义("%cjk_value = type { i32, i64 }");
        IR.类型定义("%cjk_handler = type { [32 x i64], ptr, ptr }");
        IR.类型定义("");
    }

    private void 输出结构体() {
        for (中间代码.类定义 类 : 程序.类()) {
            StringBuilder 文 = new StringBuilder(结构类型(类.名())).append(" = type { ptr");
            for (字段槽 字段 : 字段布局.get(类.名())) {
                文.append(", ").append(LLVM类型(字段.类型()));
            }
            IR.类型定义(文.append(" }").toString());
        }
        IR.类型定义("");
    }

    private void 生成静态全局() {
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                IR.声明全局("@" + 静态字段名(类.名(), 字段.名()) + " = global "
                        + LLVM类型(字段.类型()) + " " + 全局零值(字段.类型()));
            }
            for (中间代码.枚举常量定义 常量 : 类.枚举常量()) {
                IR.声明全局("@" + 静态字段名(类.名(), 常量.名()) + " = global ptr null");
            }
            if (类.枚举()) {
                IR.声明全局("@" + 枚举数组名(类.名()) + " = global ptr null");
            }
        }
    }

    private void 生成槽表() {
        int 总数 = Math.max(1, 槽表.size());
        for (中间代码.类定义 类 : 程序.类()) {
            StringBuilder 文 = new StringBuilder("@").append(槽表名(类.名()))
                    .append(" = global [").append(总数).append(" x ptr] [");
            for (int i = 0; i < 总数; i++) {
                if (i > 0) {
                    文.append(", ");
                }
                String 函数 = 方法表项(类, i);
                文.append(函数 == null ? "ptr null" : "ptr @" + 函数);
            }
            IR.声明全局(文.append(']').toString());
        }
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
                        return 方法.体() == null ? null : 方法名(当前.名(), 方法);
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
                StringBuilder 文 = new StringBuilder("@").append(接口表名(名))
                        .append(" = global [").append(类.接口().size()).append(" x ptr] [");
                for (int i = 0; i < 类.接口().size(); i++) {
                    if (i > 0) {
                        文.append(", ");
                    }
                    文.append("ptr ").append(描述符引用(类.接口().get(i)));
                }
                IR.声明全局(文.append(']').toString());
            }
            中间代码.方法 转文本 = 本类转文本(类);
            if (转文本 != null) {
                生成钩子(类, 转文本);
            }
            StringBuilder 文 = new StringBuilder("@").append(描述符名(名))
                    .append(" = global %cjk_class { i32 ").append(序号++).append(", ptr ")
                    .append(IR.新字符串(名)).append(", ")
                    .append("ptr ").append(描述符引用(父名(类)))
                    .append(", i32 ").append(槽表.size())
                    .append(", ptr @").append(槽表名(名)).append(", ");
            if (类.接口().isEmpty()) {
                文.append("ptr null, i32 0");
            } else {
                文.append("ptr @").append(接口表名(名)).append(", i32 ")
                        .append(类.接口().size());
            }
            文.append(", ptr ").append(转文本 == null ? "null" : "@" + 转文本钩子名(名));
            IR.声明全局(文.append(" }").toString());
        }
    }

    private void 生成钩子(中间代码.类定义 类, 中间代码.方法 方法) {
        当前类 = 类;
        当前行 = 0;
        开始原始函数(转文本钩子名(类.名()), "ptr", List.of("ptr %self"),
                类.名() + ".转文本钩子");
        String 结果 = 新临时();
        指令(结果 + " = call ptr @" + 方法名(类.名(), 方法) + "(ptr %self)");
        终结("ret ptr " + 结果);
        结束函数(类型.文本);
    }

    private static 中间代码.方法 本类转文本(中间代码.类定义 类) {
        for (中间代码.方法 方法 : 类.方法()) {
            if (!方法.静态() && 方法.体() != null && 方法.名().equals("转文本")
                    && 方法.参数().isEmpty()) {
                return 方法;
            }
        }
        return null;
    }

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
            当前类 = 类;
            当前行 = 0;
            开始原始函数(回调名(类.名()), "void",
                    List.of("ptr sret(%cjk_value) align 8 %ret", "ptr %self",
                            "ptr align 8 %arg1", "ptr align 8 %arg2", "i32 %arg3"),
                    类.名() + ".回调");
            String 甲 = 载入值("%arg1");
            String 乙 = 载入值("%arg2");
            StringBuilder 实参 = new StringBuilder("ptr %self");
            for (int i = 0; i < 调用方法.参数().size(); i++) {
                String 原生 = i == 0 ? 甲 : 乙;
                String 值文本 = 从原生文本(调用方法.参数().get(i).类型(), 原生);
                实参.append(", ").append(LLVM类型(调用方法.参数().get(i).类型()))
                        .append(' ').append(值文本);
            }
            类型 返回 = 调用方法.返回类型();
            if (返回 == null || 返回.同型(类型.无)) {
                指令("call void @" + 方法名(类.名(), 调用方法) + "(" + 实参 + ")");
                指令("store %cjk_value " + 调("cjk_v_null", "%cjk_value").文本()
                        + ", ptr %ret, align 8");
            } else {
                String 结果 = 新临时();
                指令(结果 + " = call " + LLVM类型(返回) + " @"
                        + 方法名(类.名(), 调用方法) + "(" + 实参 + ")");
                指令("store %cjk_value " + 到原生文本(返回, 结果) + ", ptr %ret, align 8");
            }
            终结("ret void");
            结束函数(类型.无);
        }
    }

    private void 生成初始化() {
        IR.声明("void", "cjk_gc_add_root", List.of("ptr", "i64"));
        当前类 = null;
        当前行 = 0;
        开始原始函数("_cjk_init_roots", "void", List.of(), "_cjk_init_roots");
        for (中间代码.类定义 类 : 程序.类()) {
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                指令("call void @cjk_gc_add_root(ptr @" + 静态字段名(类.名(), 字段.名())
                        + ", i64 " + 大小(字段.类型()) + ")");
            }
            for (中间代码.枚举常量定义 常量 : 类.枚举常量()) {
                指令("call void @cjk_gc_add_root(ptr @" + 静态字段名(类.名(), 常量.名())
                        + ", i64 8)");
            }
            if (类.枚举()) {
                指令("call void @cjk_gc_add_root(ptr @" + 枚举数组名(类.名()) + ", i64 8)");
            }
        }
        终结("ret void");
        结束函数(类型.无);

        for (中间代码.类定义 类 : 程序.类()) {
            if (类.枚举()) {
                生成枚举初始化(类);
            }
        }

        当前类 = null;
        当前行 = 0;
        开始原始函数("_cjk_init_statics", "void", List.of(), "_cjk_init_statics");
        for (中间代码.类定义 类 : 程序.类()) {
            if (类.枚举()) {
                指令("call void @_cjk_init_enum_" + 编码(类.名()) + "()");
            }
        }
        for (中间代码.类定义 类 : 程序.类()) {
            当前类 = 类;
            当前行 = 0;
            for (中间代码.静态字段定义 字段 : 类.静态字段()) {
                if (字段.初值() == null) {
                    continue;
                }
                值 初值 = 适配(字段.类型(), 字段.初值());
                指令("store " + LLVM类型(字段.类型()) + " " + 初值.文本() + ", ptr @"
                        + 静态字段名(类.名(), 字段.名()));
            }
        }
        当前类 = null;
        终结("ret void");
        结束函数(类型.无);
    }

    private void 生成枚举初始化(中间代码.类定义 类) {
        String 名 = 类.名();
        当前类 = 类;
        当前行 = 0;
        开始原始函数("_cjk_init_enum_" + 编码(名), "void", List.of(),
                "_cjk_init_enum_" + 名);
        String 数组 = 调("cjk_array_new", "ptr", 整数参(类.枚举常量().size()),
                整数参(9), 整数参(8), 整数参(0)).文本();
        指令("store ptr " + 数组 + ", ptr @" + 枚举数组名(名));
        for (int i = 0; i < 类.枚举常量().size(); i++) {
            中间代码.枚举常量定义 常量 = 类.枚举常量().get(i);
            值 对象 = 新建调用(名, 常量.实参());
            String 字段指针 = 新临时();
            指令(字段指针 + " = getelementptr " + 结构类型(名) + ", ptr " + 对象.文本()
                    + ", i32 0, i32 " + 字段下标(名, "__枚举名"));
            String 名值 = 新临时();
            名值 = 调("cjk_str_from_utf8", "ptr",
                    new 值(类型.空, IR.新字符串(常量.名()))).文本();
            指令("store ptr " + 名值 + ", ptr " + 字段指针);
            指令("store ptr " + 对象.文本() + ", ptr @" + 静态字段名(名, 常量.名()));
            String 数据指针 = 新临时();
            指令(数据指针 + " = getelementptr %cjk_array, ptr " + 数组 + ", i32 0, i32 2");
            String 数据 = 新临时();
            指令(数据 + " = load ptr, ptr " + 数据指针);
            String 元素指针 = 新临时();
            指令(元素指针 + " = getelementptr ptr, ptr " + 数据 + ", i64 " + i);
            指令("store ptr " + 对象.文本() + ", ptr " + 元素指针);
        }
        终结("ret void");
        结束函数(类型.无);
    }

    private void 生成入口() {
        中间代码.类定义 入口类 = null;
        for (中间代码.类定义 类 : 程序.类()) {
            中间代码.方法 方法 = 入口方法(类);
            if (方法 != null) {
                入口类 = 类;
                break;
            }
        }
        if (入口类 == null) {
            throw new 生成异常("M6 暂不支持：程序没有「入口」方法");
        }
        当前类 = 入口类;
        当前行 = 0;
        重置函数状态();
        当前函数元数据 = null;
        当前函数头 = "define i32 @main(i32 %argc, ptr %argv) {\nentry:\n";
        IR.声明("void", "cjk_rt_init", List.of());
        IR.声明("void", "cjk_set_source", List.of("ptr"));
        IR.声明("void", "cjk_set_args", List.of("i32", "ptr"));
        指令("call void @cjk_rt_init()");
        指令("call void @cjk_set_source(ptr " + IR.新字符串(源路径) + ")");
        指令("call void @cjk_set_args(i32 %argc, ptr %argv)");
        指令("call void @_cjk_init_roots()");
        指令("call void @_cjk_init_statics()");
        指令("call void @" + 方法名(入口类.名(), 入口方法(入口类)) + "()");
        终结("ret i32 0");
        结束函数(类型.整数);
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
        当前类 = 类;
        当前行 = 0;
        当前返回类型 = 类型.无;
        开始函数(构造器名(类.名(), 方法), 类型.无, 方法.参数(), true,
                类.名() + "." + 方法.名());
        语句(方法.体());
        结束函数(类型.无);
    }

    private void 生成新建(中间代码.类定义 类, 中间代码.方法 构造器) {
        当前类 = 类;
        当前行 = 0;
        当前返回类型 = 类型.类类型(类.名());
        List<中间代码.参数> 参数 = 构造器 == null ? List.of() : 构造器.参数();
        开始函数(新建名(类.名(), 构造器), 类型.类类型(类.名()), 参数, false,
                类.名() + ".新建");
        IR.声明("ptr", "cjk_new_object", List.of("ptr", "i64"));
        String 对象 = 新临时();
        指令(对象 + " = call ptr @cjk_new_object(ptr " + 描述符引用(类.名())
                + ", i64 " + 结构大小(类.名()) + ")");
        if (构造器 != null) {
            StringBuilder 文 = new StringBuilder("call void @")
                    .append(构造器名(类.名(), 构造器)).append("(ptr ").append(对象);
            for (int i = 0; i < 参数.size(); i++) {
                文.append(", ").append(LLVM类型(参数.get(i).类型())).append(" %arg").append(i);
            }
            指令(文.append(')').toString());
        }
        终结("ret ptr " + 对象);
        结束函数(类型.类类型(类.名()));
    }

    private void 生成方法(中间代码.类定义 类, 中间代码.方法 方法) {
        当前类 = 类;
        当前行 = 0;
        当前返回类型 = 方法.返回类型();
        开始函数(方法名(类.名(), 方法), 方法.返回类型(), 方法.参数(), !方法.静态(),
                类.名() + "." + 方法.名());
        语句(方法.体());
        结束函数(方法.返回类型());
        当前返回类型 = 类型.无;
    }

    private void 生成按名(中间代码.类定义 类) {
        String 名 = 类.名();
        当前类 = 类;
        当前行 = 0;
        开始原始函数(按名名(名), "ptr", List.of("ptr %arg0", "i32 %arg1"), 名 + ".按名取值");
        IR.声明("i32", "cjk_str_equal", List.of("ptr", "ptr"));
        IR.声明("void", "cjk_throw", List.of("ptr", "i32"));
        IR.声明("ptr", "cjk_str_concat", List.of("ptr", "ptr"));
        IR.声明("ptr", "cjk_new_exception", List.of("ptr", "ptr"));

        String 数组 = 新临时();
        指令(数组 + " = load ptr, ptr @" + 枚举数组名(名));
        String 长度指针 = 新临时();
        指令(长度指针 + " = getelementptr %cjk_array, ptr " + 数组 + ", i32 0, i32 0");
        String 长度 = 新临时();
        指令(长度 + " = load i32, ptr " + 长度指针);
        String 数据指针 = 新临时();
        指令(数据指针 + " = getelementptr %cjk_array, ptr " + 数组 + ", i32 0, i32 2");
        String 数据 = 新临时();
        指令(数据 + " = load ptr, ptr " + 数据指针);
        String 下标 = 新临时();
        指令(下标 + " = alloca i32");
        指令("store i32 0, ptr " + 下标);

        String 循环 = 新标签("按名");
        String 体 = 新标签("按名");
        String 命中 = 新标签("按名");
        String 下一个 = 新标签("按名");
        String 未找到 = 新标签("按名");
        终结("br label %" + 循环);
        标签行(循环);
        String 当前 = 新临时();
        指令(当前 + " = load i32, ptr " + 下标);
        String 条件 = 新临时();
        指令(条件 + " = icmp slt i32 " + 当前 + ", " + 长度);
        终结("br i1 " + 条件 + ", label %" + 体 + ", label %" + 未找到);
        标签行(体);
        String 元素指针 = 新临时();
        指令(元素指针 + " = getelementptr ptr, ptr " + 数据 + ", i32 " + 当前);
        String 对象 = 新临时();
        指令(对象 + " = load ptr, ptr " + 元素指针);
        String 字段指针 = 新临时();
        指令(字段指针 + " = getelementptr " + 结构类型(名) + ", ptr " + 对象
                + ", i32 0, i32 " + 字段下标(名, "__枚举名"));
        String 字段值 = 新临时();
        指令(字段值 + " = load ptr, ptr " + 字段指针);
        String 相等 = 新临时();
        指令(相等 + " = call i32 @cjk_str_equal(ptr " + 字段值 + ", ptr %arg0)");
        String 是 = 新临时();
        指令(是 + " = icmp ne i32 " + 相等 + ", 0");
        终结("br i1 " + 是 + ", label %" + 命中 + ", label %" + 下一个);
        标签行(命中);
        终结("ret ptr " + 对象);
        标签行(下一个);
        String 加一 = 新临时();
        指令(加一 + " = add i32 " + 当前 + ", 1");
        指令("store i32 " + 加一 + ", ptr " + 下标);
        终结("br label %" + 循环);
        标签行(未找到);
        String 前缀 = 新临时();
        指令(前缀 + " = call ptr @cjk_str_from_utf8(ptr "
                + IR.新字符串("No enum constant " + 名 + ".") + ")");
        String 消息 = 新临时();
        指令(消息 + " = call ptr @cjk_str_concat(ptr " + 前缀 + ", ptr %arg0)");
        String 异常 = 新临时();
        指令(异常 + " = call ptr @cjk_new_exception(ptr "
                + 描述符引用("非法参数异常") + ", ptr " + 消息 + ")");
        指令("call void @cjk_throw(ptr " + 异常 + ", i32 %arg1)");
        终结("ret ptr null");
        结束函数(类型.文本);
    }

    // ================= 函数骨架 =================

    private void 重置函数状态() {
        临时序号 = 0;
        标签序号 = 0;
        位置缓存.clear();
        入口块 = new StringBuilder();
        函数体 = new StringBuilder();
        当前块 = "entry";
        块已终结 = false;
        作用域.clear();
        作用域.push(new LinkedHashMap<>());
        循环栈.clear();
    }

    private void 开始函数(String 名称, 类型 返回, List<中间代码.参数> 参数, boolean 实例,
                         String 显示名) {
        重置函数状态();
        当前函数元数据 = 子程序元数据(显示名);
        StringBuilder 形参 = new StringBuilder();
        int 序号 = 0;
        if (实例) {
            形参.append("ptr %arg0");
            新形参槽("这", 类型.类类型(当前类.名()), "%arg0");
            序号 = 1;
        }
        for (中间代码.参数 参数项 : 参数) {
            if (序号 > 0) {
                形参.append(", ");
            }
            形参.append(LLVM类型(参数项.类型())).append(" %arg").append(序号);
            新形参槽(参数项.名(), 参数项.类型(), "%arg" + 序号);
            序号++;
        }
        当前函数头 = "define " + LLVM类型(返回) + " @" + 名称 + "(" + 形参
                + ") !dbg " + 当前函数元数据 + " {\nentry:\n";
    }

    private void 开始原始函数(String 名称, String 返回LLVM, List<String> 形参, String 显示名) {
        重置函数状态();
        当前函数元数据 = 子程序元数据(显示名);
        当前函数头 = "define " + 返回LLVM + " @" + 名称 + "(" + String.join(", ", 形参)
                + ") !dbg " + 当前函数元数据 + " {\nentry:\n";
    }

    private String 子程序元数据(String 显示名) {
        int 行 = Math.max(1, 当前行);
        return IR.元数据("distinct !DISubprogram(name: \"" + IR输出.转义元数据(显示名)
                + "\", scope: " + IR.文件元数据() + ", file: " + IR.文件元数据()
                + ", line: " + 行 + ", type: " + IR.子程序类型元数据()
                + ", scopeLine: " + 行 + ", spFlags: DISPFlagDefinition, unit: "
                + IR.编译单元元数据() + ")");
    }

    private void 新形参槽(String 名, 类型 类型, String 形参名) {
        String 槽 = 新临时();
        入口块.append("  ").append(槽).append(" = alloca ").append(LLVM类型(类型)).append('\n');
        入口块.append("  store ").append(LLVM类型(类型)).append(' ').append(形参名)
                .append(", ptr ").append(槽).append('\n');
        当前作用域().put(名, new 变量槽(类型, 槽));
    }

    private void 结束函数(类型 返回) {
        if (!块已终结) {
            终结默认(返回);
        }
        IR.函数(当前函数头 + 入口块 + 函数体 + "}\n\n");
    }

    private void 终结默认(类型 返回) {
        if (返回 == null || 返回.同型(类型.无)) {
            终结("ret void");
        } else {
            终结("ret " + LLVM类型(返回) + " " + 零值(返回).文本());
        }
    }

    private Map<String, 变量槽> 当前作用域() {
        return 作用域.peek();
    }

    private 变量槽 查槽(String 名) {
        for (Map<String, 变量槽> 层 : 作用域) {
            变量槽 槽 = 层.get(名);
            if (槽 != null) {
                return 槽;
            }
        }
        return null;
    }

    // ================= 指令与标签 =================

    private String 新临时() {
        return "%t" + (临时序号++);
    }

    private String 新标签(String 前缀) {
        // LLVM 标签只允许 ASCII 标识符字符；中文前缀统一转成码点十六进制。
        return "b" + 编码(前缀) + "_" + (标签序号++);
    }

    private void 新入口临时(String 文本) {
        入口块.append("  ").append(文本).append('\n');
    }

    private void 指令(String 文本) {
        函数体.append("  ").append(文本).append(调试后缀()).append('\n');
    }

    private void 指令无调试(String 文本) {
        函数体.append("  ").append(文本).append('\n');
    }

    private void 终结(String 文本) {
        指令(文本);
        块已终结 = true;
    }

    private void 标签行(String 名) {
        if (!块已终结) {
            终结("br label %" + 名);
        }
        函数体.append(名).append(":\n");
        当前块 = 名;
        块已终结 = false;
    }

    private String 调试后缀() {
        if (当前函数元数据 == null) {
            return "";
        }
        return ", !dbg " + 位置元数据(当前行 > 0 ? 当前行 : 1);
    }

    private String 位置元数据(int 行) {
        String 键 = 当前函数元数据 + ":" + 行;
        String 已有 = 位置缓存.get(键);
        if (已有 != null) {
            return 已有;
        }
        String 位置 = IR.元数据("!DILocation(line: " + 行 + ", column: 0, scope: "
                + 当前函数元数据 + ")");
        位置缓存.put(键, 位置);
        return 位置;
    }

    private 值 零值(类型 值类型) {
        if (是泛型值(值类型)) {
            return new 值(值类型, "%cjk_value", "%cjk_value zeroinitializer");
        }
        if (值类型.是数组() || 值类型.是类() || 值类型.是空() || 值类型.同型(类型.文本)) {
            return new 值(类型.空, "null");
        }
        if (值类型.同型(类型.浮点)) {
            return new 值(类型.浮点, "0.0");
        }
        if (值类型.同型(类型.双浮点)) {
            return new 值(类型.双浮点, "0.0");
        }
        if (值类型.是基本()) {
            return new 值(值类型, "0");
        }
        return new 值(类型.空, "null");
    }

    private static String 全局零值(类型 值类型) {
        if (值类型.是数组() || 值类型.是类() || 值类型.是空() || 值类型.同型(类型.文本)) {
            return "null";
        }
        if (值类型.同型(类型.浮点) || 值类型.同型(类型.双浮点)) {
            return "0.0";
        }
        return "0";
    }

    // ================= 运行时调用 =================

    /**
     * Win64 下 16 字节的 cjk_value 不能按 IR 结构体直接传参：
     * 返回走 sret 隐藏指针，形参走引用（与 clang 生成的 C ABI 一致）。
     */
    private 值 调(String 函数, String 返回LLVM, 值... 参数) {
        boolean 值返回 = "%cjk_value".equals(返回LLVM);
        List<String> 类型们 = new ArrayList<>();
        StringBuilder 实参 = new StringBuilder();
        if (值返回) {
            类型们.add("ptr sret(%cjk_value) align 8");
        }
        for (int i = 0; i < 参数.length; i++) {
            if (i > 0) {
                实参.append(", ");
            }
            if ("%cjk_value".equals(参数[i].LLVM类型())) {
                类型们.add("ptr align 8");
                String 槽 = 新值槽();
                指令("store %cjk_value " + 参数[i].文本() + ", ptr " + 槽 + ", align 8");
                实参.append("ptr align 8 ").append(槽);
            } else {
                类型们.add(参数[i].LLVM类型());
                实参.append(参数[i].LLVM类型()).append(' ').append(参数[i].文本());
            }
        }
        if (值返回) {
            IR.声明("void", 函数, 类型们);
            String 槽 = 新值槽();
            指令("call void @" + 函数 + "(ptr sret(%cjk_value) align 8 " + 槽
                    + (实参.length() > 0 ? ", " + 实参 : "") + ")");
            String 结果 = 新临时();
            指令(结果 + " = load %cjk_value, ptr " + 槽 + ", align 8");
            return new 值(类型.错误, "%cjk_value", 结果);
        }
        IR.声明(返回LLVM, 函数, 类型们);
        String 调用文本 = "call " + 返回LLVM + " @" + 函数 + "(" + 实参 + ")";
        if ("void".equals(返回LLVM)) {
            指令(调用文本);
            return new 值(类型.无, "void", "0");
        }
        String 结果 = 新临时();
        指令(结果 + " = " + 调用文本);
        return new 值(类型.错误, 返回LLVM, 结果);
    }

    /** 栈上 %cjk_value 槽位（entry 块）。 */
    private String 新值槽() {
        String 槽 = 新临时();
        新入口临时(槽 + " = alloca %cjk_value, align 8");
        return 槽;
    }

    /** 从 cjk_value 指针载入结构体值。 */
    private String 载入值(String 指针) {
        String 结果 = 新临时();
        指令(结果 + " = load %cjk_value, ptr " + 指针 + ", align 8");
        return 结果;
    }

    private 值 调值(String 函数, 值... 参数) {
        return 调(函数, "%cjk_value", 参数);
    }

    private 值 行值() {
        return new 值(类型.整数, String.valueOf(当前行));
    }

    private String 从原生文本(类型 目标, String 值文本) {
        if (目标 == null || 目标.同型(类型.无)) {
            return "0";
        }
        if (是泛型值(目标) || 是包装类(目标)) {
            return 调("cjk_box_value", "ptr", new 值(类型.错误, "%cjk_value", 值文本)).文本();
        }
        if (目标.是类() || 目标.是数组()) {
            return 调("cjk_v_as_ref", "ptr", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.文本)) {
            return 调("cjk_v_as_str", "ptr", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.长整数)) {
            return 调("cjk_v_as_i64", "i64", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.浮点)) {
            return 调("cjk_v_as_f32", "float", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.双浮点)) {
            return 调("cjk_v_as_f64", "double", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.布尔)) {
            return 调("cjk_v_as_bool", "i32", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        if (目标.同型(类型.字符)) {
            return 调("cjk_v_as_char", "i16", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        }
        String 整数 = 调("cjk_v_as_i32", "i32", new 值(类型.错误, "%cjk_value", 值文本), 行值()).文本();
        if (目标.同型(类型.短整数) || 目标.同型(类型.字节)) {
            String 结果 = 新临时();
            指令(结果 + " = trunc i32 " + 整数 + " to " + LLVM类型(目标));
            return 结果;
        }
        return 整数;
    }

    private String 到原生文本(类型 源, String 文本) {
        if (源 == null || 源.同型(类型.无)) {
            return 调("cjk_v_null", "%cjk_value").文本();
        }
        if (是泛型值(源) || 是包装类(源)) {
            return 调("cjk_unbox_value", "%cjk_value",
                    new 值(源, "ptr", 文本), 行值()).文本();
        }
        if (源.是空()) {
            return 调("cjk_v_null", "%cjk_value").文本();
        }
        if (源.是类() || 源.是数组()) {
            return 调("cjk_v_ref", "%cjk_value", new 值(源, "ptr", 文本)).文本();
        }
        if (源.同型(类型.文本)) {
            return 调("cjk_v_str", "%cjk_value", new 值(源, "ptr", 文本)).文本();
        }
        if (源.同型(类型.长整数)) {
            return 调("cjk_v_i64", "%cjk_value", new 值(源, "i64", 文本)).文本();
        }
        if (源.同型(类型.浮点)) {
            return 调("cjk_v_f32", "%cjk_value", new 值(源, "float", 文本)).文本();
        }
        if (源.同型(类型.双浮点)) {
            return 调("cjk_v_f64", "%cjk_value", new 值(源, "double", 文本)).文本();
        }
        if (源.同型(类型.布尔)) {
            return 调("cjk_v_bool", "%cjk_value", new 值(源, "i32", 文本)).文本();
        }
        if (源.同型(类型.字符)) {
            return 调("cjk_v_char", "%cjk_value", new 值(源, "i16", 文本)).文本();
        }
        String 整数 = 文本;
        if (!源.同型(类型.整数)) {
            String 结果 = 新临时();
            指令(结果 + " = " + 整数指令(源, 类型.整数, 文本));
            整数 = 结果;
        }
        return 调("cjk_v_i32", "%cjk_value", new 值(类型.整数, 整数)).文本();
    }

    // ================= 语句 =================

    private record 地址(类型 类型, String 指针) {}

    private boolean 已声明处理器栈;

    private void 语句(中间代码.语句 语句) {
        if (语句 == null) {
            return;
        }
        int 行 = 位置 == null ? 0 : 位置.行(语句);
        if (行 > 0) {
            当前行 = 行;
        }
        if (块已终结) {
            标签行(新标签("不可达"));
        }
        if (语句 instanceof 中间代码.块 块) {
            作用域.push(new LinkedHashMap<>());
            for (中间代码.语句 项 : 块.语句()) {
                语句(项);
            }
            作用域.pop();
            return;
        }
        if (语句 instanceof 中间代码.局部变量语句 局部) {
            变量槽 槽 = 新局部(局部.名(), 局部.类型());
            if (局部.初值() != null) {
                值 初值 = 适配(局部.类型(), 局部.初值());
                指令("store " + LLVM类型(局部.类型()) + " " + 初值.文本()
                        + ", ptr " + 槽.指针());
            } else {
                指令("store " + LLVM类型(局部.类型()) + " " + 零值(局部.类型()).文本()
                        + ", ptr " + 槽.指针());
            }
            return;
        }
        if (语句 instanceof 中间代码.表达式语句 表达式语句) {
            表达式(表达式语句.值());
            return;
        }
        if (语句 instanceof 中间代码.返回语句 返回) {
            if (返回.值() == null) {
                if (当前返回类型 == null || 当前返回类型.同型(类型.无)) {
                    终结("ret void");
                } else {
                    终结("ret " + LLVM类型(当前返回类型) + " "
                            + 零值(当前返回类型).文本());
                }
                return;
            }
            值 值 = 适配(当前返回类型, 返回.值());
            终结("ret " + LLVM类型(当前返回类型) + " " + 值.文本());
            return;
        }
        if (语句 instanceof 中间代码.若语句 若) {
            若语句(若);
            return;
        }
        if (语句 instanceof 中间代码.对于语句 对于) {
            对于语句(对于);
            return;
        }
        if (语句 instanceof 中间代码.当语句 当) {
            当语句(当);
            return;
        }
        if (语句 instanceof 中间代码.做当语句 做当) {
            做当语句(做当);
            return;
        }
        if (语句 instanceof 中间代码.跳出语句) {
            if (循环栈.isEmpty()) {
                throw new 生成异常("M6 暂不支持：循环外出现「跳出」");
            }
            终结("br label %" + 循环栈.peek().跳出());
            return;
        }
        if (语句 instanceof 中间代码.继续语句) {
            if (循环栈.isEmpty()) {
                throw new 生成异常("M6 暂不支持：循环外出现「继续」");
            }
            终结("br label %" + 循环栈.peek().继续());
            return;
        }
        if (语句 instanceof 中间代码.尝试语句 尝试) {
            尝试语句(尝试);
            return;
        }
        if (语句 instanceof 中间代码.抛出语句 抛出) {
            值 异常 = 表达式(抛出.值());
            调("cjk_throw", "void", 转换(异常, 类型.类类型("异常")), 行值());
            return;
        }
        throw new 生成异常("M6 暂不支持：未知语句类型");
    }

    private 变量槽 新局部(String 名, 类型 类型) {
        String 指针 = 新临时();
        新入口临时(指针 + " = alloca " + LLVM类型(类型));
        变量槽 槽 = new 变量槽(类型, 指针);
        当前作用域().put(名, 槽);
        return 槽;
    }

    private void 若语句(中间代码.若语句 若) {
        String 条件 = 布尔值(表达式(若.条件()));
        String 真块 = 新标签("若");
        String 假块 = 新标签("若");
        String 汇合 = 新标签("若");
        终结("br i1 " + 条件 + ", label %" + 真块 + ", label %" + 假块);
        标签行(真块);
        语句(若.成立());
        if (!块已终结) {
            终结("br label %" + 汇合);
        }
        标签行(假块);
        if (若.否则() != null) {
            语句(若.否则());
        }
        if (!块已终结) {
            终结("br label %" + 汇合);
        }
        标签行(汇合);
    }

    private void 对于语句(中间代码.对于语句 对于) {
        作用域.push(new LinkedHashMap<>());
        // 与 C 后端一致：初始化块里的局部变量要留在 for 作用域内，供条件/更新/体使用。
        if (对于.初始化() instanceof 中间代码.块 块) {
            for (中间代码.语句 子 : 块.语句()) {
                语句(子);
            }
        } else if (对于.初始化() != null) {
            语句(对于.初始化());
        }
        String 头 = 新标签("对于");
        String 体 = 新标签("对于");
        String 更新 = 新标签("对于");
        String 尾 = 新标签("对于");
        终结("br label %" + 头);
        标签行(头);
        if (对于.条件() != null) {
            String 条件 = 布尔值(表达式(对于.条件()));
            终结("br i1 " + 条件 + ", label %" + 体 + ", label %" + 尾);
        } else {
            终结("br label %" + 体);
        }
        标签行(体);
        循环栈.push(new 循环标签(尾, 更新));
        语句(对于.体());
        循环栈.pop();
        if (!块已终结) {
            终结("br label %" + 更新);
        }
        标签行(更新);
        for (中间代码.表达式 项 : 对于.更新()) {
            表达式(项);
        }
        终结("br label %" + 头);
        标签行(尾);
        作用域.pop();
    }

    private void 当语句(中间代码.当语句 当) {
        String 头 = 新标签("当");
        String 体 = 新标签("当");
        String 尾 = 新标签("当");
        终结("br label %" + 头);
        标签行(头);
        String 条件 = 布尔值(表达式(当.条件()));
        终结("br i1 " + 条件 + ", label %" + 体 + ", label %" + 尾);
        标签行(体);
        循环栈.push(new 循环标签(尾, 头));
        语句(当.体());
        循环栈.pop();
        if (!块已终结) {
            终结("br label %" + 头);
        }
        标签行(尾);
    }

    private void 做当语句(中间代码.做当语句 做当) {
        String 体 = 新标签("做当");
        String 条件块 = 新标签("做当");
        String 尾 = 新标签("做当");
        终结("br label %" + 体);
        标签行(体);
        循环栈.push(new 循环标签(尾, 条件块));
        语句(做当.体());
        循环栈.pop();
        if (!块已终结) {
            终结("br label %" + 条件块);
        }
        标签行(条件块);
        String 条件 = 布尔值(表达式(做当.条件()));
        终结("br i1 " + 条件 + ", label %" + 体 + ", label %" + 尾);
        标签行(尾);
    }

    private void 尝试语句(中间代码.尝试语句 尝试) {
        确保处理器栈();
        IR.声明("i32", "_setjmp", List.of("ptr", "ptr"), "returns_twice");
        IR.声明("ptr", "llvm.frameaddress.p0", List.of("i32"));
        String 处理器 = 新临时();
        // MinGW 的 jmp_buf 要求 16 字节对齐（_setjmp 用 movaps 保存 XMM）。
        新入口临时(处理器 + " = alloca %cjk_handler, align 16");
        String 捕获标志 = 新临时();
        新入口临时(捕获标志 + " = alloca i32");
        String 前一个 = 新临时();
        指令(前一个 + " = load ptr, ptr @cjk_handler_top");
        String 前指针 = 新临时();
        指令(前指针 + " = getelementptr %cjk_handler, ptr " + 处理器 + ", i32 0, i32 1");
        指令("store ptr " + 前一个 + ", ptr " + 前指针);
        String 异常指针 = 新临时();
        指令(异常指针 + " = getelementptr %cjk_handler, ptr " + 处理器 + ", i32 0, i32 2");
        指令("store ptr null, ptr " + 异常指针);
        指令("store ptr " + 处理器 + ", ptr @cjk_handler_top");
        String 帧 = 新临时();
        指令(帧 + " = call ptr @llvm.frameaddress.p0(i32 0)");
        String 返回码 = 新临时();
        指令(返回码 + " = call i32 @_setjmp(ptr " + 处理器 + ", ptr " + 帧 + ")");
        String 正常 = 新临时();
        指令(正常 + " = icmp eq i32 " + 返回码 + ", 0");
        String 体块 = 新标签("尝试");
        String 捕获块 = 新标签("尝试");
        String 结束块 = 新标签("尝试");
        终结("br i1 " + 正常 + ", label %" + 体块 + ", label %" + 捕获块);
        标签行(体块);
        作用域.push(new LinkedHashMap<>());
        语句(尝试.体());
        作用域.pop();
        指令("store ptr " + 前一个 + ", ptr @cjk_handler_top");
        if (尝试.兜底() != null) {
            语句(尝试.兜底());
        }
        if (!块已终结) {
            终结("br label %" + 结束块);
        }
        标签行(捕获块);
        String 异常 = 新临时();
        指令(异常 + " = load ptr, ptr " + 异常指针);
        指令("store ptr " + 前一个 + ", ptr @cjk_handler_top");
        指令("store i32 0, ptr " + 捕获标志);
        for (中间代码.捕获子句 子句 : 尝试.捕获()) {
            String 子块 = 新标签("捕获");
            String 下一 = 新标签("捕获");
            String 判定 = 调("cjk_is_instance", "i32",
                    new 值(类型.空, "ptr", 异常),
                    new 值(类型.空, 描述符引用(子句.类名()))).文本();
            String 已捕 = 新临时();
            指令(已捕 + " = load i32, ptr " + 捕获标志);
            String 未捕 = 新临时();
            指令(未捕 + " = icmp eq i32 " + 已捕 + ", 0");
            String 命中 = 新临时();
            指令(命中 + " = icmp ne i32 " + 判定 + ", 0");
            String 两者 = 新临时();
            指令(两者 + " = and i1 " + 未捕 + ", " + 命中);
            终结("br i1 " + 两者 + ", label %" + 子块 + ", label %" + 下一);
            标签行(子块);
            指令("store i32 1, ptr " + 捕获标志);
            作用域.push(new LinkedHashMap<>());
            变量槽 槽 = 新局部(子句.变量名(), 类型.类类型(子句.类名()));
            指令("store ptr " + 异常 + ", ptr " + 槽.指针());
            语句(子句.体());
            作用域.pop();
            if (!块已终结) {
                终结("br label %" + 下一);
            }
            标签行(下一);
        }
        if (尝试.兜底() != null) {
            语句(尝试.兜底());
        }
        String 已捕 = 新临时();
        指令(已捕 + " = load i32, ptr " + 捕获标志);
        String 未捕获 = 新临时();
        指令(未捕获 + " = icmp eq i32 " + 已捕 + ", 0");
        String 重抛 = 新标签("捕获");
        终结("br i1 " + 未捕获 + ", label %" + 重抛 + ", label %" + 结束块);
        标签行(重抛);
        调("cjk_throw", "void", new 值(类型.空, "ptr", 异常), 行值());
        终结("br label %" + 结束块);
        标签行(结束块);
    }

    private void 确保处理器栈() {
        if (已声明处理器栈) {
            return;
        }
        已声明处理器栈 = true;
        IR.声明全局("@cjk_handler_top = external global ptr");
    }

    // ================= 表达式 =================

    private 值 表达式(中间代码.表达式 表达式) {
        if (表达式 == null) {
            return new 值(类型.整数, "0");
        }
        if (表达式 instanceof 中间代码.常量 常量) {
            return 常量值(常量);
        }
        if (表达式 instanceof 中间代码.变量读 读) {
            变量槽 槽 = 查槽(读.名());
            if (槽 == null) {
                throw new 生成异常("M6 暂不支持：未找到变量「" + 读.名() + "」");
            }
            String 结果 = 新临时();
            指令(结果 + " = load " + LLVM类型(槽.类型()) + ", ptr " + 槽.指针());
            return new 值(读.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.字段读 读) {
            地址 地址 = 字段地址(读);
            String 结果 = 新临时();
            指令(结果 + " = load " + LLVM类型(读.类型()) + ", ptr " + 地址.指针());
            return new 值(读.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.数组读 读) {
            地址 地址 = 数组地址(读);
            String 结果 = 新临时();
            指令(结果 + " = load " + LLVM类型(读.类型()) + ", ptr " + 地址.指针());
            return new 值(读.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.数组长度 长度) {
            值 数组 = 表达式(长度.数组());
            return new 值(长度.类型(), 调("cjk_array_len", "i32", 数组, 行值()).文本());
        }
        if (表达式 instanceof 中间代码.二元 二元) {
            return 二元值(二元);
        }
        if (表达式 instanceof 中间代码.一元 一元) {
            return 一元值(一元);
        }
        if (表达式 instanceof 中间代码.三目 三目) {
            return 三目值(三目);
        }
        if (表达式 instanceof 中间代码.强制转换 转换) {
            return 强制转换值(转换);
        }
        if (表达式 instanceof 中间代码.赋值 赋值) {
            return 赋值值(赋值);
        }
        if (表达式 instanceof 中间代码.调用 调用) {
            return 调用值(调用);
        }
        if (表达式 instanceof 中间代码.新建对象 新建) {
            return 新建对象值(新建);
        }
        if (表达式 instanceof 中间代码.新建数组 新建) {
            return 新建数组值(新建);
        }
        if (表达式 instanceof 中间代码.实例判断 判断) {
            值 目标 = 表达式(判断.值());
            String 结果 = 调("cjk_is_instance", "i32", 目标,
                    new 值(类型.空, 描述符引用(判断.目标类()))).文本();
            return new 值(判断.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.静态字段读 读) {
            String 结果 = 新临时();
            指令(结果 + " = load " + LLVM类型(读.类型()) + ", ptr @"
                    + 静态字段名(读.类名(), 读.名()));
            return new 值(读.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.枚举常量读 读) {
            String 结果 = 新临时();
            指令(结果 + " = load ptr, ptr @" + 静态字段名(读.枚举类(), 读.常量名()));
            return new 值(读.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.枚举全部值 全部) {
            String 结果 = 新临时();
            指令(结果 + " = load ptr, ptr @" + 枚举数组名(全部.枚举类()));
            return new 值(全部.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.枚举按名取值 按名) {
            值 名 = 表达式(按名.名());
            String 结果 = 新临时();
            // 本模块内定义的函数，不能再补 declare，否则构成重复定义。
            指令(结果 + " = call ptr @" + 按名名(按名.枚举类()) + "(ptr " + 名.文本()
                    + ", i32 " + 当前行 + ")" + 调试后缀());
            return new 值(按名.类型(), 结果);
        }
        if (表达式 instanceof 中间代码.顺序表达式 顺序) {
            for (中间代码.语句 项 : 顺序.前奏()) {
                语句(项);
            }
            return 表达式(顺序.结果());
        }
        throw new 生成异常("M6 暂不支持：未知表达式类型");
    }

    private 地址 字段地址(中间代码.字段读 读) {
        类型 接收类型 = 取类型(读.接收者());
        String 类名 = 接收类型 != null && 接收类型.是类() ? 接收类型.类名 : null;
        if (类名 == null) {
            throw new 生成异常("M6 暂不支持：在非类值上读取字段「" + 读.名() + "」");
        }
        中间代码.类定义 声明类 = 查找字段所在类(类名, 读.名());
        if (声明类 == null) {
            throw new 生成异常("M6 暂不支持：找不到字段「" + 读.名() + "」");
        }
        值 接收 = 表达式(读.接收者());
        String 检查 = 调("cjk_null_check", "ptr",
                new 值(接收类型, 接收.文本()), 行值()).文本();
        String 指针 = 新临时();
        指令(指针 + " = getelementptr " + 结构类型(声明类.名()) + ", ptr " + 检查
                + ", i32 0, i32 " + 字段下标(声明类.名(), 读.名()));
        return new 地址(读.类型(), 指针);
    }

    private 地址 数组地址(中间代码.数组读 读) {
        值 数组 = 表达式(读.数组());
        值 下标 = 转换(表达式(读.下标()), 类型.整数);
        值 指针 = 调("cjk_array_at", "ptr", 数组, 下标,
                new 值(类型.整数, String.valueOf(大小(读.类型()))), 行值());
        return new 地址(读.类型(), 指针.文本());
    }

    private 值 二元值(中间代码.二元 二元) {
        String 符 = 二元.运算符();
        类型 结果类型 = 二元.类型();
        if ("+".equals(符) && 结果类型.同型(类型.文本)) {
            值 左 = 表达式(二元.左());
            值 右 = 表达式(二元.右());
            String 拼接 = 调("cjk_str_concat", "ptr",
                    new 值(类型.文本, 转文本(二元.左(), 左)),
                    new 值(类型.文本, 转文本(二元.右(), 右))).文本();
            return new 值(类型.文本, 拼接);
        }
        if ("&&".equals(符) || "||".equals(符)) {
            return 短路(二元);
        }
        值 左 = 表达式(二元.左());
        值 右 = 表达式(二元.右());
        类型 左类型 = 取类型(二元.左());
        类型 运算 = 提升类型(左类型, 取类型(二元.右()));
        if ("==".equals(符) || "!=".equals(符)) {
            if (左类型.同型(类型.文本)) {
                String 相等 = 调("cjk_str_equal", "i32", 左, 右).文本();
                return new 值(结果类型, 相等);
            }
            if (左类型.是类() || 左类型.是数组() || 左类型.是空()) {
                String 比较 = 新临时();
                指令(比较 + " = icmp " + ("==".equals(符) ? "eq" : "ne")
                        + " ptr " + 左.文本() + ", " + 右.文本());
                return 布转整数(比较);
            }
        }
        左 = 转换(左, 运算);
        值 右转 = 转换(右, 运算);
        if (运算.是浮点族()) {
            if (是比较(符)) {
                String 比较 = 新临时();
                指令(比较 + " = fcmp " + 浮点比较(符) + " " + LLVM类型(运算) + " "
                        + 左.文本() + ", " + 右转.文本());
                return 布转整数(比较);
            }
            String 结果 = 新临时();
            指令(结果 + " = " + 浮点算术(符) + " " + LLVM类型(运算) + " "
                    + 左.文本() + ", " + 右转.文本());
            return new 值(结果类型, 结果);
        }
        if ("%".equals(符)) {
            String 函数 = 运算.同型(类型.长整数) ? "cjk_rem_i64" : "cjk_rem_i32";
            String 返回 = 运算.同型(类型.长整数) ? "i64" : "i32";
            return new 值(结果类型, 调(函数, 返回, 左, 右转, 行值()).文本());
        }
        if ("/".equals(符)) {
            String 函数 = 运算.同型(类型.长整数) ? "cjk_div_i64" : "cjk_div_i32";
            String 返回 = 运算.同型(类型.长整数) ? "i64" : "i32";
            return new 值(结果类型, 调(函数, 返回, 左, 右转, 行值()).文本());
        }
        if (是比较(符)) {
            String 比较 = 新临时();
            指令(比较 + " = icmp " + 整数比较(符) + " " + LLVM类型(运算) + " "
                    + 左.文本() + ", " + 右转.文本());
            return 布转整数(比较);
        }
        String 结果 = 新临时();
        String 指令名 = switch (符) {
            case "+" -> "add";
            case "-" -> "sub";
            case "*" -> "mul";
            case "&" -> "and";
            case "|" -> "or";
            case "^" -> "xor";
            case "<<" -> "shl";
            case ">>" -> "ashr";
            case ">>>" -> "lshr";
            default -> throw new 生成异常("M6 暂不支持：运算符「" + 符 + "」");
        };
        指令(结果 + " = " + 指令名 + " " + LLVM类型(运算) + " " + 左.文本()
                + ", " + 右转.文本());
        return new 值(结果类型, 结果);
    }

    private static 类型 提升类型(类型 甲, 类型 乙) {
        if (甲 == null || 乙 == null) {
            return 类型.整数;
        }
        if (甲.同型(类型.双浮点) || 乙.同型(类型.双浮点)) {
            return 类型.双浮点;
        }
        if (甲.同型(类型.浮点) || 乙.同型(类型.浮点)) {
            return 类型.浮点;
        }
        if (甲.同型(类型.长整数) || 乙.同型(类型.长整数)) {
            return 类型.长整数;
        }
        return 类型.整数;
    }

    private 值 短路(中间代码.二元 二元) {
        boolean 与 = "&&".equals(二元.运算符());
        值 左 = 表达式(二元.左());
        String 左真 = 布尔值(左);
        String 左整数 = 布转整数(左真).文本();
        String 左块 = 当前块;
        String 右块 = 新标签("短路");
        String 汇合 = 新标签("短路");
        if (与) {
            终结("br i1 " + 左真 + ", label %" + 右块 + ", label %" + 汇合);
        } else {
            终结("br i1 " + 左真 + ", label %" + 汇合 + ", label %" + 右块);
        }
        标签行(右块);
        值 右 = 表达式(二元.右());
        String 右整数 = 布转整数(布尔值(右)).文本();
        String 右块名 = 当前块;
        终结("br label %" + 汇合);
        标签行(汇合);
        String 结果 = 新临时();
        指令无调试(结果 + " = phi i32 [ " + 左整数 + ", %" + 左块 + " ], [ "
                + 右整数 + ", %" + 右块名 + " ]");
        return new 值(二元.类型(), 结果);
    }

    private 值 一元值(中间代码.一元 一元) {
        值 操作数 = 表达式(一元.操作数());
        String 符 = 一元.运算符();
        类型 操作类型 = 取类型(一元.操作数());
        if ("!".equals(符)) {
            String 条件 = 布尔值(操作数);
            String 结果 = 新临时();
            指令(结果 + " = xor i1 " + 条件 + ", true");
            return 布转整数(结果);
        }
        if ("-".equals(符)) {
            String 结果 = 新临时();
            if (操作类型.是浮点族()) {
                指令(结果 + " = fneg " + LLVM类型(操作类型) + " " + 操作数.文本());
            } else {
                指令(结果 + " = sub " + LLVM类型(操作类型) + " " + 零值(操作类型).文本()
                        + ", " + 操作数.文本());
            }
            return new 值(一元.类型(), 结果);
        }
        if ("~".equals(符)) {
            String 结果 = 新临时();
            指令(结果 + " = xor " + LLVM类型(操作类型) + " " + 操作数.文本() + ", -1");
            return new 值(一元.类型(), 结果);
        }
        return 操作数;
    }

    private 值 三目值(中间代码.三目 三目) {
        String 条件 = 布尔值(表达式(三目.条件()));
        String 真块 = 新标签("三目");
        String 假块 = 新标签("三目");
        String 汇合 = 新标签("三目");
        终结("br i1 " + 条件 + ", label %" + 真块 + ", label %" + 假块);
        标签行(真块);
        值 真 = 适配(三目.类型(), 三目.真());
        String 真尾 = 当前块;
        终结("br label %" + 汇合);
        标签行(假块);
        值 假 = 适配(三目.类型(), 三目.假());
        String 假尾 = 当前块;
        终结("br label %" + 汇合);
        标签行(汇合);
        if (三目.类型() == null || 三目.类型().同型(类型.无)) {
            return new 值(类型.无, "0");
        }
        String 结果 = 新临时();
        指令无调试(结果 + " = phi " + LLVM类型(三目.类型()) + " [ " + 真.文本()
                + ", %" + 真尾 + " ], [ " + 假.文本() + ", %" + 假尾 + " ]");
        return new 值(三目.类型(), 结果);
    }

    private 值 强制转换值(中间代码.强制转换 转换) {
        值 操作数 = 表达式(转换.操作数());
        类型 源 = 取类型(转换.操作数());
        if (是泛型值(源) && !是泛型值(转换.类型())) {
            return 从值(转换.类型(), 操作数);
        }
        if (转换.类型() == null) {
            return 操作数;
        }
        return 转换(操作数, 转换.类型());
    }

    private 值 赋值值(中间代码.赋值 赋值) {
        if (赋值.左() instanceof 中间代码.变量读 读) {
            变量槽 槽 = 查槽(读.名());
            if (槽 == null) {
                throw new 生成异常("M6 暂不支持：未找到赋值变量「" + 读.名() + "」");
            }
            值 值 = 适配(槽.类型(), 赋值.右());
            指令("store " + LLVM类型(槽.类型()) + " " + 值.文本() + ", ptr " + 槽.指针());
            return new 值(赋值.类型(), 值.文本());
        }
        if (赋值.左() instanceof 中间代码.静态字段读 读) {
            值 值 = 适配(读.类型(), 赋值.右());
            指令("store " + LLVM类型(读.类型()) + " " + 值.文本() + ", ptr @"
                    + 静态字段名(读.类名(), 读.名()));
            return new 值(赋值.类型(), 值.文本());
        }
        地址 地址;
        if (赋值.左() instanceof 中间代码.字段读 读) {
            地址 = 字段地址(读);
        } else if (赋值.左() instanceof 中间代码.数组读 读) {
            地址 = 数组地址(读);
        } else {
            throw new 生成异常("M6 暂不支持：该赋值目标");
        }
        值 值 = 适配(地址.类型(), 赋值.右());
        指令("store " + LLVM类型(地址.类型()) + " " + 值.文本() + ", ptr " + 地址.指针());
        return new 值(赋值.类型(), 值.文本());
    }

    private 值 新建对象值(中间代码.新建对象 新建) {
        if (内建类名.contains(新建.类名())) {
            值 消息 = 新建.实参().isEmpty()
                    ? new 值(类型.空, "null")
                    : 转换(表达式(新建.实参().get(0)), 类型.文本);
            return new 值(新建.类型(), 调("cjk_new_exception", "ptr",
                    new 值(类型.空, 描述符引用(新建.类名())), 消息).文本());
        }
        return 新建调用(新建.类名(), 新建.实参());
    }

    private 值 新建调用(String 类名, List<中间代码.表达式> 实参) {
        List<类型> 实参类型 = 实参类型(实参);
        中间代码.方法 构造器 = 选构造器(类名, 实参类型);
        中间代码.类定义 类 = 类定义(类名);
        if (类 != null && !类.构造器().isEmpty() && 构造器 == null) {
            throw new 生成异常("M6 暂不支持：找不到「" + 类名 + "」的匹配构造器");
        }
        StringBuilder 文 = new StringBuilder("call ptr @")
                .append(新建名(类名, 构造器)).append('(');
        for (int i = 0; i < 实参.size(); i++) {
            if (i > 0) {
                文.append(", ");
            }
            值 值;
            if (构造器 != null && i < 构造器.参数().size()) {
                值 = 适配(构造器.参数().get(i).类型(), 实参.get(i));
            } else {
                值 = 表达式(实参.get(i));
            }
            文.append(值.LLVM类型()).append(' ').append(值.文本());
        }
        String 结果 = 新临时();
        指令(结果 + " = " + 文.append(')'));
        return new 值(类型.类类型(类名), 结果);
    }

    private 值 新建数组值(中间代码.新建数组 新建) {
        类型 元素 = 新建.元素类型();
        String 种类 = 种类码(元素);
        int 元素大小 = 大小(元素);
        if (!新建.初值().isEmpty()) {
            int 个数 = 新建.初值().size();
            String 数组 = 新临时();
            新入口临时(数组 + " = alloca [" + 个数 + " x " + LLVM类型(元素) + "]");
            for (int i = 0; i < 个数; i++) {
                值 值 = 适配(元素, 新建.初值().get(i));
                String 指针 = 新临时();
                指令(指针 + " = getelementptr [" + 个数 + " x " + LLVM类型(元素)
                        + "], ptr " + 数组 + ", i64 0, i64 " + i);
                指令("store " + LLVM类型(元素) + " " + 值.文本() + ", ptr " + 指针);
            }
            return new 值(新建.类型(), 调("cjk_array_from", "ptr",
                    new 值(类型.整数, String.valueOf(种类)),
                    new 值(类型.整数, String.valueOf(元素大小)),
                    new 值(类型.整数, String.valueOf(个数)),
                    new 值(类型.空, 数组)).文本());
        }
        return new 值(新建.类型(), 调("cjk_array_new", "ptr",
                转换(表达式(新建.长度()), 类型.整数),
                new 值(类型.整数, String.valueOf(种类)),
                new 值(类型.整数, String.valueOf(元素大小)),
                行值()).文本());
    }

    // ================= 条件与转换 =================

    private String 布尔值(值 值) {
        String 类型名 = 值.LLVM类型();
        String 结果 = 新临时();
        if ("i1".equals(类型名)) {
            return 值.文本();
        }
        if ("ptr".equals(类型名)) {
            指令(结果 + " = icmp ne ptr " + 值.文本() + ", null");
            return 结果;
        }
        if ("double".equals(类型名) || "float".equals(类型名)) {
            指令(结果 + " = fcmp une " + 类型名 + " " + 值.文本() + ", 0.0");
            return 结果;
        }
        指令(结果 + " = icmp ne " + 类型名 + " " + 值.文本() + ", 0");
        return 结果;
    }

    private 值 布转整数(String 布尔) {
        String 结果 = 新临时();
        指令(结果 + " = zext i1 " + 布尔 + " to i32");
        return new 值(类型.布尔, "i32", 结果);
    }

    private 值 转换(值 源, 类型 目标) {
        if (目标 == null) {
            return 源;
        }
        String 目标LLVM = LLVM类型(目标);
        if (源.LLVM类型().equals(目标LLVM)) {
            return new 值(目标, 源.文本());
        }
        if ("ptr".equals(源.LLVM类型()) || "ptr".equals(目标LLVM)
                || "void".equals(目标LLVM) || "void".equals(源.LLVM类型())) {
            return new 值(目标, 源.文本());
        }
        String 结果 = 新临时();
        指令(结果 + " = " + 转换指令(源.LLVM类型(), 目标LLVM, 源.文本()));
        return new 值(目标, 结果);
    }

    private static String 转换指令(String 源, String 目标, String 值) {
        if (源.equals(目标)) {
            return "bitcast " + 源 + " " + 值 + " to " + 目标;
        }
        boolean 源浮 = "float".equals(源) || "double".equals(源);
        boolean 目标浮 = "float".equals(目标) || "double".equals(目标);
        if (源浮 && 目标浮) {
            return 位宽(源) > 位宽(目标)
                    ? "fptrunc " + 源 + " " + 值 + " to " + 目标
                    : "fpext " + 源 + " " + 值 + " to " + 目标;
        }
        if (源浮) {
            return "fptosi " + 源 + " " + 值 + " to " + 目标;
        }
        if (目标浮) {
            return "sitofp " + 源 + " " + 值 + " to " + 目标;
        }
        return 位宽(源) > 位宽(目标)
                ? "trunc " + 源 + " " + 值 + " to " + 目标
                : "sext " + 源 + " " + 值 + " to " + 目标;
    }

    private static int 位宽(String 类型名) {
        return switch (类型名) {
            case "i1" -> 1;
            case "i8" -> 8;
            case "i16" -> 16;
            case "i32" -> 32;
            case "i64" -> 64;
            case "float" -> 32;
            case "double" -> 64;
            default -> 64;
        };
    }

    private String 整数指令(类型 源, 类型 目标, String 文本) {
        return 转换指令(LLVM类型(源), LLVM类型(目标), 文本);
    }

    private 值 适配(类型 目标, 中间代码.表达式 表达式) {
        值 值 = 表达式(表达式);
        类型 源 = 取类型(表达式);
        if (目标 == null || 源 == null) {
            return 值;
        }
        if (是泛型值(目标) && !是泛型值(源)) {
            return 到值(源, 值);
        }
        if (!是泛型值(目标) && 是泛型值(源)) {
            return 从值(目标, 值);
        }
        if (是包装类(目标) && 源.是基本()) {
            return 到值(源, 值);
        }
        if (是包装类(源) && !目标.是类() && !目标.是数组()) {
            return 从值(目标, 值);
        }
        return 转换(值, 目标);
    }

    private 值 到值(类型 源, 值 值) {
        类型 泛型 = 类型.类类型(泛型值名);
        if (源 == null || 源.同型(类型.无)) {
            return new 值(泛型, "null");
        }
        if (是泛型值(源) || 源.是类()) {
            return new 值(泛型, 值.文本());
        }
        if (源.是空()) {
            return new 值(泛型, "null");
        }
        if (源.是数组()) {
            值 原生 = 调("cjk_v_ref", "%cjk_value", new 值(源, "ptr", 值.文本()));
            return new 值(泛型, 调("cjk_box_value", "ptr", 原生).文本());
        }
        if (源.同型(类型.文本)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_str", "%cjk_value", new 值(源, "ptr", 值.文本()))).文本());
        }
        if (源.同型(类型.长整数)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_i64", "%cjk_value", new 值(源, "i64", 值.文本()))).文本());
        }
        if (源.同型(类型.浮点)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_f32", "%cjk_value", new 值(源, "float", 值.文本()))).文本());
        }
        if (源.同型(类型.双浮点)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_f64", "%cjk_value", new 值(源, "double", 值.文本()))).文本());
        }
        if (源.同型(类型.布尔)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_bool", "%cjk_value", new 值(源, "i32", 值.文本()))).文本());
        }
        if (源.同型(类型.字符)) {
            return new 值(泛型, 调("cjk_box_value", "ptr",
                    调("cjk_v_char", "%cjk_value", new 值(源, "i16", 值.文本()))).文本());
        }
        值 整数 = 转换(值, 类型.整数);
        return new 值(泛型, 调("cjk_box_value", "ptr",
                调("cjk_v_i32", "%cjk_value", 整数)).文本());
    }

    private 值 从值(类型 目标, 值 值) {
        值 原生 = 调("cjk_unbox_value", "%cjk_value",
                new 值(值.类型(), "ptr", 值.文本()), 行值());
        return 从原生值(目标, 原生);
    }

    private 值 从原生值(类型 目标, 值 原生) {
        String 文本 = 从原生文本(目标, 原生.文本());
        return new 值(目标, 文本);
    }

    private String 转文本(中间代码.表达式 表达式, 值 值) {
        类型 值类型 = 取类型(表达式);
        if (是泛型值(值类型) || 是包装类(值类型)) {
            return 调("cjk_value_to_string", "ptr",
                    调("cjk_unbox_value", "%cjk_value",
                            new 值(值类型, "ptr", 值.文本()), 行值())).文本();
        }
        if (值类型.同型(类型.文本)) {
            return 值.文本();
        }
        if (值类型.是空()) {
            return 值.文本();
        }
        if (值类型.是类()) {
            中间代码.方法 转文本 = 查找方法(值类型.类名, "转文本", List.of(), false);
            if (转文本 != null) {
                return 调("cjk_virtual_to_string", "ptr",
                        new 值(值类型, "ptr", 值.文本()),
                        new 值(类型.整数, String.valueOf(槽(转文本)))).文本();
            }
            return 调("cjk_object_to_string", "ptr",
                    new 值(值类型, "ptr", 值.文本())).文本();
        }
        if (值类型.是基本()) {
            return switch (值类型.基本值) {
                case 整数 -> 调("cjk_str_from_i32", "ptr",
                        new 值(类型.整数, 值.文本())).文本();
                case 短整数, 字节 -> 调("cjk_str_from_i32", "ptr",
                        转换(值, 类型.整数)).文本();
                case 长整数 -> 调("cjk_str_from_i64", "ptr",
                        new 值(类型.长整数, 值.文本())).文本();
                case 浮点 -> 调("cjk_str_from_f32", "ptr",
                        new 值(类型.浮点, 值.文本())).文本();
                case 双浮点 -> 调("cjk_str_from_f64", "ptr",
                        new 值(类型.双浮点, 值.文本())).文本();
                case 字符 -> 调("cjk_str_from_char", "ptr",
                        new 值(类型.字符, 值.文本())).文本();
                case 布尔 -> 调("cjk_str_from_bool", "ptr",
                        new 值(类型.布尔, 值.文本())).文本();
                case 文本 -> 值.文本();
                case 无 -> throw new 生成异常("M6 暂不支持：把「无」拼接到字符串");
            };
        }
        throw new 生成异常("M6 暂不支持：把 " + 值类型.文本() + " 拼接到字符串");
    }

    private String 打印参数(List<中间代码.表达式> 实参) {
        if (实参.isEmpty()) {
            return 调("cjk_str_from_utf8", "ptr",
                    new 值(类型.空, IR.新字符串(""))).文本();
        }
        String 结果 = null;
        for (中间代码.表达式 项 : 实参) {
            值 值 = 表达式(项);
            String 文本 = 转文本(项, 值);
            结果 = 结果 == null ? 文本
                    : 调("cjk_str_concat", "ptr",
                            new 值(类型.文本, 结果), new 值(类型.文本, 文本)).文本();
        }
        return 结果;
    }

    private static boolean 是比较(String 符) {
        return switch (符) {
            case "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    private static String 整数比较(String 符) {
        return switch (符) {
            case "<" -> "slt";
            case "<=" -> "sle";
            case ">" -> "sgt";
            case ">=" -> "sge";
            case "==" -> "eq";
            case "!=" -> "ne";
            default -> "eq";
        };
    }

    private static String 浮点比较(String 符) {
        return switch (符) {
            case "<" -> "olt";
            case "<=" -> "ole";
            case ">" -> "ogt";
            case ">=" -> "oge";
            case "==" -> "oeq";
            case "!=" -> "une";
            default -> "oeq";
        };
    }

    private static String 浮点算术(String 符) {
        return switch (符) {
            case "+" -> "fadd";
            case "-" -> "fsub";
            case "*" -> "fmul";
            case "/" -> "fdiv";
            default -> throw new 生成异常("M6 暂不支持：浮点运算符「" + 符 + "」");
        };
    }

    // ================= 调用 =================

    private 值 调用值(中间代码.调用 调用) {
        switch (调用.分派()) {
            case 内建:
                return 内建值(调用);
            case 静态: {
                String 起始 = 调用.类名().isEmpty() ? 当前类.名() : 调用.类名();
                方法选择 选择 = 最佳方法(起始, 调用.名(), 实参类型(调用.实参()), true);
                if (选择.方法() == null) {
                    throw new 生成异常("M6 暂不支持：找不到静态方法「" + 调用.名() + "」");
                }
                return 函数调用(选择.方法(), 选择.声明类(), null, 调用.实参());
            }
            case 实例: {
                类型 接收类型 = 取类型(调用.接收者());
                String 起始 = 接收类型 != null && 接收类型.是类()
                        ? 接收类型.类名
                        : (调用.类名().isEmpty() ? 当前类.名() : 调用.类名());
                中间代码.方法 目标 = 查找方法(起始, 调用.名(), 实参类型(调用.实参()), false);
                if (目标 == null) {
                    throw new 生成异常("M6 暂不支持：找不到实例方法「" + 调用.名() + "」");
                }
                return 虚调用(调用, 目标);
            }
            case 构造器: {
                中间代码.方法 构造器 = 选构造器(调用.类名(), 实参类型(调用.实参()));
                if (构造器 == null) {
                    throw new 生成异常("M6 暂不支持：找不到构造器「" + 调用.类名() + "」");
                }
                StringBuilder 实参 = new StringBuilder("ptr ").append(表达式(调用.接收者()).文本());
                for (int i = 0; i < 调用.实参().size(); i++) {
                    值 参数 = 适配(构造器.参数().get(i).类型(), 调用.实参().get(i));
                    实参.append(", ").append(参数.LLVM类型()).append(' ').append(参数.文本());
                }
                指令("call void @" + 构造器名(调用.类名(), 构造器) + "(" + 实参 + ")");
                return new 值(类型.无, "0");
            }
            case 父方法: {
                方法选择 选择 = 最佳方法(调用.类名(), 调用.名(), 实参类型(调用.实参()), false);
                if (选择.方法() == null) {
                    throw new 生成异常("M6 暂不支持：找不到父类方法「" + 调用.名() + "」");
                }
                return 函数调用(选择.方法(), 选择.声明类(), 表达式(调用.接收者()), 调用.实参());
            }
            default:
                throw new 生成异常("M6 暂不支持：调用分派 " + 调用.分派());
        }
    }

    private 值 函数调用(中间代码.方法 目标, String 声明类, 值 接收, List<中间代码.表达式> 实参) {
        StringBuilder 文本 = new StringBuilder();
        if (接收 != null) {
            文本.append("ptr ").append(接收.文本());
        }
        for (int i = 0; i < 实参.size(); i++) {
            值 参数 = 适配(目标.参数().get(i).类型(), 实参.get(i));
            if (文本.length() > 0) {
                文本.append(", ");
            }
            文本.append(参数.LLVM类型()).append(' ').append(参数.文本());
        }
        String 返回 = LLVM类型(目标.返回类型());
        String 调用文本 = "call " + 返回 + " @" + 方法名(声明类, 目标) + "(" + 文本 + ")";
        if ("void".equals(返回)) {
            指令(调用文本);
            return new 值(类型.无, "0");
        }
        String 结果 = 新临时();
        指令(结果 + " = " + 调用文本);
        return new 值(目标.返回类型(), 结果);
    }

    private 值 虚调用(中间代码.调用 调用, 中间代码.方法 目标) {
        值 接收 = 表达式(调用.接收者());
        String 检查 = 调("cjk_null_check", "ptr", 接收, 行值()).文本();
        String 类指针 = 新临时();
        指令(类指针 + " = getelementptr %cjk_object, ptr " + 检查 + ", i32 0, i32 0");
        String 类 = 新临时();
        指令(类 + " = load ptr, ptr " + 类指针);
        String 方法表指针 = 新临时();
        指令(方法表指针 + " = getelementptr %cjk_class, ptr " + 类 + ", i32 0, i32 4");
        String 方法表 = 新临时();
        指令(方法表 + " = load ptr, ptr " + 方法表指针);
        String 槽指针 = 新临时();
        指令(槽指针 + " = getelementptr ptr, ptr " + 方法表 + ", i64 " + 槽(目标));
        String 函数 = 新临时();
        指令(函数 + " = load ptr, ptr " + 槽指针);
        StringBuilder 实参 = new StringBuilder("ptr ").append(检查);
        for (int i = 0; i < 调用.实参().size(); i++) {
            值 参数 = 适配(目标.参数().get(i).类型(), 调用.实参().get(i));
            实参.append(", ").append(参数.LLVM类型()).append(' ').append(参数.文本());
        }
        String 返回 = LLVM类型(目标.返回类型());
        String 调用文本 = "call " + 返回 + " " + 函数 + "(" + 实参 + ")";
        if ("void".equals(返回)) {
            指令(调用文本);
            return new 值(类型.无, "0");
        }
        String 结果 = 新临时();
        指令(结果 + " = " + 调用文本);
        return new 值(目标.返回类型(), 结果);
    }

    // ================= 内建 =================

    private 值 空值() {
        return new 值(类型.无, "void", "0");
    }

    private 值 原生(String 文本) {
        return new 值(类型.错误, "%cjk_value", 文本);
    }

    private 值 值参(中间代码.表达式 实参) {
        return 原生(到原生文本(取类型(实参), 表达式(实参).文本()));
    }

    private 值 整数参(long 值) {
        return new 值(类型.整数, String.valueOf(值));
    }

    private 值 空指针() {
        return new 值(类型.空, "null");
    }

    private 值 定型(中间代码.调用 调用, 值 结果) {
        类型 目标类型 = 调用.类型();
        if (目标类型 == null || 目标类型.同型(类型.无)) {
            return 空值();
        }
        if ("%cjk_value".equals(结果.LLVM类型())) {
            return 从原生值(目标类型, 结果);
        }
        return new 值(目标类型, 结果.文本());
    }

    /** 实参区间 → 栈上 cjk_value 数组，返回指向首元素的 ptr。 */
    private 值 值数组值(List<中间代码.表达式> 实参, int 起点) {
        if (起点 >= 实参.size()) {
            return 空指针();
        }
        int 个数 = 实参.size() - 起点;
        String 数组 = 新临时();
        新入口临时(数组 + " = alloca [" + 个数 + " x %cjk_value]");
        for (int i = 起点; i < 实参.size(); i++) {
            String 元素 = 到原生文本(取类型(实参.get(i)), 表达式(实参.get(i)).文本());
            String 指针 = 新临时();
            指令(指针 + " = getelementptr [" + 个数 + " x %cjk_value], ptr " + 数组
                    + ", i64 0, i64 " + (i - 起点));
            指令("store %cjk_value " + 元素 + ", ptr " + 指针);
        }
        return new 值(类型.空, 数组);
    }

    /** 实参 → 栈上 ptr 数组（字符串形参列表）。 */
    private 值 字符串数组值(List<中间代码.表达式> 实参) {
        if (实参.isEmpty()) {
            return 空指针();
        }
        int 个数 = 实参.size();
        String 数组 = 新临时();
        新入口临时(数组 + " = alloca [" + 个数 + " x ptr]");
        for (int i = 0; i < 个数; i++) {
            值 元素 = 表达式(实参.get(i));
            String 指针 = 新临时();
            指令(指针 + " = getelementptr [" + 个数 + " x ptr], ptr " + 数组 + ", i64 0, i64 " + i);
            指令("store ptr " + 元素.文本() + ", ptr " + 指针);
        }
        return new 值(类型.空, 数组);
    }

    private 值 内建值(中间代码.调用 调用) {
        String 名 = 调用.名();
        List<中间代码.表达式> 实参 = 调用.实参();
        if ("打印行".equals(名) || "打印".equals(名)) {
            调("cjk_print_str", "void", new 值(类型.文本, 打印参数(实参)));
            if ("打印行".equals(名)) {
                调("cjk_newline", "void");
            }
            return 空值();
        }
        if ("数组复制".equals(名)) {
            类型 源类型 = 取类型(实参.get(0));
            类型 元素 = 源类型 != null && 源类型.是数组() ? 源类型.元素 : 类型.整数;
            调("cjk_array_copy", "void", 表达式(实参.get(0)),
                    转换(表达式(实参.get(1)), 类型.整数), 表达式(实参.get(2)),
                    转换(表达式(实参.get(3)), 类型.整数),
                    转换(表达式(实参.get(4)), 类型.整数),
                    整数参(大小(元素)), 行值());
            return 空值();
        }
        if ("修剪".equals(名)) {
            return 定型(调用, 调("cjk_str_trim", "ptr", 表达式(文本接收者(调用))));
        }
        if ("长度".equals(名)) {
            值 接 = 表达式(文本接收者(调用));
            return switch (接收名(调用)) {
                case "文本" -> 定型(调用, 调("cjk_str_len", "i32", 接, 行值()));
                case "文本构建器" -> 定型(调用, 调("cjk_builder_len", "i32", 接));
                case "集" -> 定型(调用, 调("cjk_set_size", "i32", 接));
                case "键值表" -> 定型(调用, 调("cjk_map_size", "i32", 接));
                case "流" -> 定型(调用, 调("cjk_stream_count", "i32", 接));
                default -> 定型(调用, 调("cjk_list_size", "i32", 接));
            };
        }
        if ("是空".equals(名)) {
            值 接 = 表达式(文本接收者(调用));
            switch (接收名(调用)) {
                case "文本":
                    return 定型(调用, 调("cjk_str_is_empty", "i32", 接, 行值()));
                case "可选值":
                    return 定型(调用, 调("cjk_optional_is_empty", "i32", 接));
                case "键值表":
                    return 定型(调用, 调("cjk_map_is_empty", "i32", 接));
                case "集":
                    return 定型(调用, 调("cjk_set_is_empty", "i32", 接));
                case "文本构建器": {
                    值 长度 = 调("cjk_builder_len", "i32", 接);
                    String 比较 = 新临时();
                    指令(比较 + " = icmp eq i32 " + 长度.文本() + ", 0");
                    return 定型(调用, 布转整数(比较));
                }
                default:
                    return 定型(调用, 调("cjk_list_is_empty", "i32", 接));
            }
        }
        if ("相等".equals(名) || "等于".equals(名)) {
            值 甲 = 调用.接收者() != null ? 表达式(调用.接收者()) : 表达式(实参.get(0));
            值 乙 = 调用.接收者() != null ? 表达式(实参.get(0)) : 表达式(实参.get(1));
            return 定型(调用, 调("cjk_str_equal", "i32", 甲, 乙));
        }
        if ("平方根".equals(名)) {
            值 双 = 转换(表达式(实参.get(0)), 类型.双浮点);
            值 结果 = 调("sqrt", "double", 双);
            return 转换(new 值(类型.双浮点, "double", 结果.文本()), 调用.类型());
        }
        if ("绝对值".equals(名)) {
            类型 值类型 = 取类型(实参.get(0));
            值 源 = 表达式(实参.get(0));
            if (值类型 != null && (值类型.同型(类型.双浮点) || 值类型.同型(类型.浮点))) {
                值 双 = 转换(源, 类型.双浮点);
                值 结果 = 调("fabs", "double", 双);
                return 转换(new 值(类型.双浮点, "double", 结果.文本()), 调用.类型());
            }
            类型 整数类型 = 值类型 != null && 值类型.同型(类型.长整数) ? 类型.长整数 : 类型.整数;
            值 整 = 转换(源, 整数类型);
            String llvm = LLVM类型(整数类型);
            String 负 = 新临时();
            指令(负 + " = icmp slt " + llvm + " " + 整.文本() + ", 0");
            String 取负 = 新临时();
            指令(取负 + " = sub " + llvm + " 0, " + 整.文本());
            String 结果 = 新临时();
            指令(结果 + " = select i1 " + 负 + ", " + llvm + " " + 取负 + ", " + llvm
                    + " " + 整.文本());
            return 定型(调用, new 值(整数类型, 结果));
        }
        if ("最大值".equals(名) || "最小值".equals(名)) {
            类型 值类型 = 调用.类型();
            值 甲 = 转换(表达式(实参.get(0)), 值类型);
            值 乙 = 转换(表达式(实参.get(1)), 值类型);
            String llvm = LLVM类型(值类型);
            boolean 浮点 = 值类型.同型(类型.浮点) || 值类型.同型(类型.双浮点);
            String 比较符 = "最大值".equals(名) ? ">" : "<";
            String 比较 = 新临时();
            指令(比较 + " = " + (浮点 ? "fcmp " + 浮点比较(比较符) : "icmp " + 整数比较(比较符))
                    + " " + llvm + " " + 甲.文本() + ", " + 乙.文本());
            String 结果 = 新临时();
            指令(结果 + " = select i1 " + 比较 + ", " + llvm + " " + 甲.文本() + ", " + llvm
                    + " " + 乙.文本());
            return new 值(值类型, 结果);
        }
        if ("幂".equals(名)) {
            值 结果 = 调("pow", "double",
                    转换(表达式(实参.get(0)), 类型.双浮点),
                    转换(表达式(实参.get(1)), 类型.双浮点));
            return 转换(new 值(类型.双浮点, "double", 结果.文本()), 调用.类型());
        }
        if ("随机".equals(名)) {
            值 随机 = 调("rand", "i32");
            String 双 = 新临时();
            指令(双 + " = sitofp i32 " + 随机.文本() + " to double");
            String 结果 = 新临时();
            指令(结果 + " = fdiv double " + 双 + ", 2.147483648e+09");
            return 定型(调用, new 值(类型.双浮点, "double", 结果));
        }
        if ("向下取整".equals(名) || "向上取整".equals(名)) {
            值 结果 = 调("向下取整".equals(名) ? "floor" : "ceil", "double",
                    转换(表达式(实参.get(0)), 类型.双浮点));
            return 转换(new 值(类型.双浮点, "double", 结果.文本()), 调用.类型());
        }
        if ("四舍五入".equals(名)) {
            值 结果 = 调("llround", "i64", 转换(表达式(实参.get(0)), 类型.双浮点));
            return 定型(调用, new 值(类型.长整数, 结果.文本()));
        }
        if ("圆周率".equals(名)) {
            return new 值(调用.类型(), "3.14159265358979323846");
        }
        if ("自然常数".equals(名)) {
            return new 值(调用.类型(), "2.71828182845904523536");
        }
        if ("整数解析".equals(名)) {
            return 定型(调用, 调("cjk_parse_int", "i32", 表达式(实参.get(0)), 行值()));
        }
        if ("长整数解析".equals(名)) {
            return 定型(调用, 调("cjk_parse_i64", "i64", 表达式(实参.get(0)), 行值()));
        }
        if ("双浮点解析".equals(名)) {
            return 定型(调用, 调("cjk_parse_f64", "double", 表达式(实参.get(0)), 行值()));
        }
        if ("整数值".equals(名)) {
            类型 值类型 = 取类型(实参.get(0));
            值 源 = 表达式(实参.get(0));
            值 整 = 值类型 != null && 值类型.是类() ? 从值(类型.整数, 源) : 转换(源, 类型.整数);
            return 定型(调用, 调("cjk_box_value", "ptr",
                    调("cjk_v_i32", "%cjk_value", 整)));
        }
        if ("包装取值".equals(名)) {
            return 从值(调用.类型(), 表达式(调用.接收者()));
        }
        if ("包装比较".equals(名)) {
            return 定型(调用, 调("cjk_value_compare", "i32",
                    值参(调用.接收者()), 值参(实参.get(0))));
        }
        if ("转大写".equals(名)) {
            return 定型(调用, 调("cjk_str_upper", "ptr", 表达式(文本接收者(调用))));
        }
        if ("转小写".equals(名)) {
            return 定型(调用, 调("cjk_str_lower", "ptr", 表达式(文本接收者(调用))));
        }
        if ("是空白".equals(名)) {
            return 定型(调用, 调("cjk_str_is_blank", "i32",
                    表达式(文本接收者(调用)), 行值()));
        }
        if ("取字符".equals(名)) {
            return 定型(调用, 调("cjk_str_char_at", "i16",
                    表达式(文本接收者(调用)), 转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("含有".equals(名)) {
            值 接 = 表达式(文本接收者(调用));
            String 名2 = 接收名(调用);
            if ("集".equals(名2)) {
                return 定型(调用, 调("cjk_set_contains", "i32", 接, 值参(实参.get(0))));
            }
            if ("文本".equals(名2)) {
                return 定型(调用, 调("cjk_str_contains", "i32", 接, 表达式(实参.get(0))));
            }
            return 定型(调用, 调("cjk_list_contains", "i32", 接, 值参(实参.get(0))));
        }
        if ("以开头".equals(名)) {
            return 定型(调用, 调("cjk_str_starts_with", "i32",
                    表达式(文本接收者(调用)), 表达式(实参.get(0))));
        }
        if ("以结尾".equals(名)) {
            return 定型(调用, 调("cjk_str_ends_with", "i32",
                    表达式(文本接收者(调用)), 表达式(实参.get(0))));
        }
        if ("替换".equals(名)) {
            return 定型(调用, 调("cjk_str_replace", "ptr", 表达式(文本接收者(调用)),
                    表达式(实参.get(0)), 表达式(实参.get(1))));
        }
        if ("取子串".equals(名)) {
            return 定型(调用, 调("cjk_str_substring", "ptr", 表达式(文本接收者(调用)),
                    转换(表达式(实参.get(0)), 类型.整数),
                    转换(表达式(实参.get(1)), 类型.整数), 行值()));
        }
        if ("拆分".equals(名)) {
            return 定型(调用, 调("cjk_str_split", "ptr", 表达式(文本接收者(调用)),
                    表达式(实参.get(0)), 行值()));
        }
        if ("复读".equals(名)) {
            return 定型(调用, 调("cjk_str_repeat", "ptr", 表达式(文本接收者(调用)),
                    转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("字符串拼接".equals(名)) {
            return 定型(调用, 调("cjk_str_join", "ptr",
                    表达式(实参.get(0)), 表达式(实参.get(1))));
        }
        if ("字符串比较".equals(名)) {
            if (调用.接收者() != null) {
                return 定型(调用, 调("cjk_str_compare", "i32",
                        表达式(调用.接收者()), 表达式(实参.get(0))));
            }
            return 定型(调用, 调("cjk_str_compare", "i32",
                    表达式(实参.get(0)), 表达式(实参.get(1))));
        }
        if ("异常取消息".equals(名)) {
            值 异常 = 表达式(调用.接收者());
            String 指针 = 新临时();
            指令(指针 + " = getelementptr %cjk_exception, ptr " + 异常.文本()
                    + ", i32 0, i32 1");
            String 消息 = 新临时();
            指令(消息 + " = load ptr, ptr " + 指针);
            return new 值(类型.文本, 消息);
        }
        if ("空操作".equals(名)) {
            return 空值();
        }
        if ("程序参数".equals(名)) {
            return 定型(调用, 调("cjk_program_args", "ptr"));
        }
        if ("值转文本".equals(名)) {
            return 定型(调用, 调("cjk_value_to_string", "ptr", 值参(实参.get(0))));
        }
        if ("值相等".equals(名)) {
            return 定型(调用, 调("cjk_value_equal", "i32",
                    值参(实参.get(0)), 值参(实参.get(1))));
        }
        if ("值哈希码".equals(名)) {
            return 定型(调用, 调("cjk_value_hash", "i32", 值参(实参.get(0))));
        }
        if ("新建清单".equals(名)) {
            return 定型(调用, 调("cjk_list_new", "ptr"));
        }
        if ("新建集".equals(名)) {
            return 定型(调用, 调("cjk_set_new", "ptr"));
        }
        if ("新建字典".equals(名)) {
            if (实参.isEmpty()) {
                return 定型(调用, 调("cjk_map_new", "ptr"));
            }
            return 定型(调用, 调("cjk_map_from", "ptr",
                    整数参(实参.size() / 2), 值数组值(实参, 0)));
        }
        if ("新建构建器".equals(名)) {
            return 定型(调用, 调("cjk_builder_new", "ptr"));
        }
        if ("清单从".equals(名) || "集从".equals(名)) {
            String 工厂 = "清单从".equals(名) ? "cjk_list_from" : "cjk_set_from";
            if (实参.isEmpty()) {
                return 定型(调用, 调(工厂, "ptr", 整数参(0), 空指针()));
            }
            return 定型(调用, 调(工厂, "ptr", 整数参(实参.size()), 值数组值(实参, 0)));
        }
        if ("字典从".equals(名)) {
            return 定型(调用, 调("cjk_map_from", "ptr",
                    整数参(实参.size() / 2), 值数组值(实参, 0)));
        }
        if ("清单加入".equals(名)) {
            return 定型(调用, 调("cjk_list_add", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("清单取出".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_list_get", "%cjk_value",
                    表达式(调用.接收者()), 转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("清单设定".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_list_set", "%cjk_value",
                    表达式(调用.接收者()), 转换(表达式(实参.get(0)), 类型.整数),
                    值参(实参.get(1)), 行值()));
        }
        if ("清单移除下标".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_list_remove_at", "%cjk_value",
                    表达式(调用.接收者()), 转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("清单移除值".equals(名)) {
            return 定型(调用, 调("cjk_list_remove_value", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("清单找下标".equals(名)) {
            return 定型(调用, 调("cjk_list_index_of", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("清单元素数".equals(名)) {
            return 定型(调用, 调("cjk_list_size", "i32", 表达式(调用.接收者())));
        }
        if ("清单是空".equals(名)) {
            return 定型(调用, 调("cjk_list_is_empty", "i32", 表达式(调用.接收者())));
        }
        if ("清单清空".equals(名)) {
            调("cjk_list_clear", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("清单含有".equals(名)) {
            return 定型(调用, 调("cjk_list_contains", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("清单整理".equals(名)) {
            值 清单 = 表达式(调用.接收者());
            if (实参.isEmpty()) {
                调("cjk_list_sort", "void", 清单);
            } else {
                调("cjk_list_sort_by", "void", 清单, 表达式(实参.get(0)));
            }
            return 空值();
        }
        if ("清单流".equals(名)) {
            return 定型(调用, 调("cjk_list_stream", "ptr", 表达式(调用.接收者())));
        }
        if ("清单值集合".equals(名)) {
            return 定型(调用, 调("cjk_list_to_set", "ptr", 表达式(调用.接收者())));
        }
        if ("集加入".equals(名)) {
            return 定型(调用, 调("cjk_set_add", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("集移除".equals(名)) {
            return 定型(调用, 调("cjk_set_remove", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("集含有".equals(名)) {
            return 定型(调用, 调("cjk_set_contains", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("集元素数".equals(名)) {
            return 定型(调用, 调("cjk_set_size", "i32", 表达式(调用.接收者())));
        }
        if ("集是空".equals(名)) {
            return 定型(调用, 调("cjk_set_is_empty", "i32", 表达式(调用.接收者())));
        }
        if ("集清空".equals(名)) {
            调("cjk_set_clear", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("集取出".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_set_get", "%cjk_value",
                    表达式(调用.接收者()), 转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("集流".equals(名)) {
            return 定型(调用, 调("cjk_set_stream", "ptr", 表达式(调用.接收者())));
        }
        if ("字典放入".equals(名)) {
            return 调("cjk_map_put", "%cjk_value",
                    表达式(调用.接收者()), 值参(实参.get(0)), 值参(实参.get(1)));
        }
        if ("字典取出".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_map_get", "%cjk_value",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("字典取出或".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_map_get_or", "%cjk_value",
                    表达式(调用.接收者()), 值参(实参.get(0)), 值参(实参.get(1))));
        }
        if ("字典移除".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_map_remove", "%cjk_value",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("字典键集合".equals(名)) {
            return 定型(调用, 调("cjk_map_keys", "ptr", 表达式(调用.接收者())));
        }
        if ("字典值集合".equals(名)) {
            return 定型(调用, 调("cjk_map_values", "ptr", 表达式(调用.接收者())));
        }
        if ("字典含键".equals(名)) {
            return 定型(调用, 调("cjk_map_contains_key", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("字典含值".equals(名)) {
            return 定型(调用, 调("cjk_map_contains_value", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("字典元素数".equals(名)) {
            return 定型(调用, 调("cjk_map_size", "i32", 表达式(调用.接收者())));
        }
        if ("字典是空".equals(名)) {
            return 定型(调用, 调("cjk_map_is_empty", "i32", 表达式(调用.接收者())));
        }
        if ("字典清空".equals(名)) {
            调("cjk_map_clear", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("字典流".equals(名)) {
            return 定型(调用, 调("cjk_map_stream", "ptr", 表达式(调用.接收者())));
        }
        if ("字典遍历".equals(名)) {
            调("cjk_map_for_each", "void", 表达式(调用.接收者()),
                    表达式(实参.get(0)), 回调引用(实参.get(0)));
            return 空值();
        }
        if ("构建器追加".equals(名)) {
            return 定型(调用, 调("cjk_builder_append", "ptr",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("构建器转文本".equals(名)) {
            return 定型(调用, 调("cjk_builder_to_string", "ptr", 表达式(调用.接收者())));
        }
        if ("构建器长度".equals(名)) {
            return 定型(调用, 调("cjk_builder_len", "i32", 表达式(调用.接收者())));
        }
        if ("构建器清空".equals(名)) {
            调("cjk_builder_clear", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("路径取".equals(名)) {
            return 定型(调用, 调("cjk_path_of", "ptr",
                    整数参(实参.size()), 字符串数组值(实参)));
        }
        if ("路径上级".equals(名)) {
            return 定型(调用, 调("cjk_path_parent", "ptr", 表达式(调用.接收者())));
        }
        if ("路径绝对".equals(名)) {
            return 定型(调用, 调("cjk_path_absolute", "ptr", 表达式(调用.接收者())));
        }
        if ("路径文件名".equals(名)) {
            return 定型(调用, 调("cjk_path_file_name", "ptr", 表达式(调用.接收者())));
        }
        if ("路径转文本".equals(名)) {
            return 定型(调用, 调("cjk_path_to_string", "ptr", 表达式(调用.接收者())));
        }
        if ("文件读文本".equals(名)) {
            return 定型(调用, 调("cjk_files_read_text", "ptr",
                    表达式(实参.get(0)), 行值()));
        }
        if ("文件写文本".equals(名)) {
            调("cjk_files_write_text", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 行值());
            return 空值();
        }
        if ("文件建目录".equals(名)) {
            调("cjk_files_create_directories", "void", 表达式(实参.get(0)), 行值());
            return 空值();
        }
        if ("文件读行".equals(名)) {
            return 定型(调用, 调("cjk_files_read_all_lines", "ptr",
                    表达式(实参.get(0)), 行值()));
        }
        if ("文件存在".equals(名)) {
            return 定型(调用, 调("cjk_files_exists", "i32", 表达式(实参.get(0))));
        }
        if ("文件大小".equals(名)) {
            return 定型(调用, 调("cjk_files_size", "i64", 表达式(实参.get(0)), 行值()));
        }
        if ("文件删除".equals(名)) {
            调("cjk_files_delete", "void", 表达式(实参.get(0)), 行值());
            return 空值();
        }
        if ("标准文件删除".equals(名)) {
            调("cjk_files_delete_if_exists", "void", 表达式(实参.get(0)), 行值());
            return 空值();
        }
        if ("文件列举".equals(名)) {
            return 定型(调用, 调("cjk_files_list", "ptr", 表达式(实参.get(0)), 行值()));
        }
        if ("文件复制".equals(名)) {
            调("cjk_files_copy", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 行值());
            return 空值();
        }
        if ("文件移动".equals(名)) {
            调("cjk_files_move", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 行值());
            return 空值();
        }
        if ("可选值空".equals(名)) {
            return 定型(调用, 调("cjk_optional_empty", "ptr"));
        }
        if ("可选值装入".equals(名)) {
            return 定型(调用, 调("cjk_optional_of", "ptr", 值参(实参.get(0))));
        }
        if ("可选值可空".equals(名)) {
            return 定型(调用, 调("cjk_optional_of_nullable", "ptr", 值参(实参.get(0))));
        }
        if ("可选值或".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_optional_or", "%cjk_value",
                    表达式(调用.接收者()), 值参(实参.get(0))));
        }
        if ("可选值是空".equals(名)) {
            return 定型(调用, 调("cjk_optional_is_empty", "i32", 表达式(调用.接收者())));
        }
        if ("可选值存在".equals(名)) {
            return 定型(调用, 调("cjk_optional_is_present", "i32", 表达式(调用.接收者())));
        }
        if ("可选值取出".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_optional_get", "%cjk_value",
                    表达式(调用.接收者()), 行值()));
        }
        if ("可选值存在时".equals(名)) {
            调("cjk_optional_if_present", "void", 表达式(调用.接收者()),
                    表达式(实参.get(0)), 回调引用(实参.get(0)));
            return 空值();
        }
        if ("流过滤".equals(名)) {
            return 定型(调用, 调("cjk_stream_filter", "ptr", 表达式(调用.接收者()),
                    表达式(实参.get(0)), 回调引用(实参.get(0))));
        }
        if ("流变换".equals(名)) {
            return 定型(调用, 调("cjk_stream_map", "ptr", 表达式(调用.接收者()),
                    表达式(实参.get(0)), 回调引用(实参.get(0))));
        }
        if ("流遍历".equals(名)) {
            调("cjk_stream_for_each", "void", 表达式(调用.接收者()),
                    表达式(实参.get(0)), 回调引用(实参.get(0)));
            return 空值();
        }
        if ("流收集清单".equals(名)) {
            return 定型(调用, 调("cjk_stream_collect_list", "ptr", 表达式(调用.接收者())));
        }
        if ("流收集集".equals(名)) {
            return 定型(调用, 调("cjk_stream_collect_set", "ptr", 表达式(调用.接收者())));
        }
        if ("流收集拼接".equals(名)) {
            return 定型(调用, 调("cjk_stream_collect_join", "ptr",
                    表达式(调用.接收者()), 表达式(实参.get(0))));
        }
        if ("流找首个".equals(名)) {
            return 定型(调用, 调("cjk_stream_find_first", "ptr", 表达式(调用.接收者())));
        }
        if ("流元素数".equals(名)) {
            return 定型(调用, 调("cjk_stream_count", "i32", 表达式(调用.接收者())));
        }
        if ("流去重".equals(名)) {
            return 定型(调用, 调("cjk_stream_distinct", "ptr", 表达式(调用.接收者())));
        }
        if ("流排序".equals(名)) {
            return 定型(调用, 调("cjk_stream_sorted", "ptr", 表达式(调用.接收者())));
        }
        if ("收集器清单".equals(名)) {
            return 定型(调用, 调("cjk_collector_list", "ptr"));
        }
        if ("收集器集".equals(名)) {
            return 定型(调用, 调("cjk_collector_set", "ptr"));
        }
        if ("收集器拼接".equals(名)) {
            return 定型(调用, 调("cjk_collector_join", "ptr", 表达式(实参.get(0))));
        }
        if ("比较器按整数".equals(名)) {
            return 定型(调用, 调("cjk_comparator_by_int", "ptr",
                    表达式(实参.get(0)), 回调引用(实参.get(0))));
        }
        if ("比较器反序".equals(名)) {
            return 定型(调用, 调("cjk_comparator_reversed", "ptr", 表达式(调用.接收者())));
        }
        if ("比较器比较".equals(名)) {
            return 定型(调用, 调("cjk_comparator_compare", "i32",
                    表达式(调用.接收者()), 值参(实参.get(0)), 值参(实参.get(1))));
        }
        if ("集合排序".equals(名)) {
            调("cjk_list_sort", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("集合反序".equals(名)) {
            调("cjk_list_reverse", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("集合打乱".equals(名)) {
            调("cjk_list_shuffle", "void", 表达式(调用.接收者()));
            return 空值();
        }
        if ("集合最大".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_list_max", "%cjk_value",
                    表达式(调用.接收者()), 行值()));
        }
        if ("集合最小".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_list_min", "%cjk_value",
                    表达式(调用.接收者()), 行值()));
        }
        // ---------- M5：中文.标准库 ----------
        if ("输出打印".equals(名) || "输出打印多".equals(名)) {
            调("cjk_print_values", "void", 整数参(实参.size()), 值数组值(实参, 0));
            return 空值();
        }
        if ("输出打印行".equals(名) || "输出打印行多".equals(名)) {
            调("cjk_println_values", "void", 整数参(实参.size()), 值数组值(实参, 0));
            return 空值();
        }
        if ("输出换行".equals(名)) {
            调("cjk_print_newline", "void");
            return 空值();
        }
        if ("输出错误".equals(名) || "输出错误多".equals(名)) {
            调("cjk_eprintln_values", "void", 整数参(实参.size()), 值数组值(实参, 0));
            return 空值();
        }
        if ("输出格式".equals(名)) {
            调("cjk_output_format", "void", 表达式(实参.get(0)),
                    整数参(实参.size() - 1), 值数组值(实参, 1), 行值());
            return 空值();
        }
        if ("文本是否为空".equals(名)) {
            return 定型(调用, 调("cjk_str_is_null_or_empty", "i32", 表达式(实参.get(0))));
        }
        if ("文本是否空白".equals(名)) {
            return 定型(调用, 调("cjk_str_is_null_or_blank", "i32", 表达式(实参.get(0))));
        }
        if ("文本去空白".equals(名)) {
            return 定型(调用, 调("cjk_str_strip", "ptr", 表达式(实参.get(0)), 行值()));
        }
        if ("文本连接".equals(名)) {
            return 定型(调用, 调("cjk_str_join_values", "ptr", 表达式(实参.get(0)),
                    整数参(实参.size() - 1), 值数组值(实参, 1)));
        }
        if ("数值是整数".equals(名)) {
            return 定型(调用, 调("cjk_str_is_integer", "i32", 表达式(实参.get(0))));
        }
        if ("数值随机整数1".equals(名)) {
            return 定型(调用, 调("cjk_random_int", "i32", 表达式(实参.get(0)), 行值()));
        }
        if ("数值随机整数2".equals(名)) {
            return 定型(调用, 调("cjk_random_range", "i32",
                    表达式(实参.get(0)), 表达式(实参.get(1)), 行值()));
        }
        if ("集合填充".equals(名)) {
            调("cjk_list_fill", "void", 表达式(实参.get(0)), 值参(实参.get(1)));
            return 空值();
        }
        if ("时间今天".equals(名)) {
            return 定型(调用, 调("cjk_date_today", "ptr"));
        }
        if ("时间此刻".equals(名)) {
            return 定型(调用, 调("cjk_datetime_now", "ptr"));
        }
        if ("时间戳".equals(名)) {
            return 定型(调用, 调("cjk_now_millis", "i64"));
        }
        if ("时间格式化".equals(名)) {
            return 定型(调用, 调("cjk_datetime_format", "ptr",
                    表达式(实参.get(0)), 表达式(实参.get(1)), 行值()));
        }
        if ("时间相差天数".equals(名)) {
            return 定型(调用, 调("cjk_date_between", "i64",
                    表达式(实参.get(0)), 表达式(实参.get(1))));
        }
        if ("时间加天数".equals(名)) {
            return 定型(调用, 调("cjk_date_plus_days", "ptr",
                    表达式(实参.get(0)), 转换(表达式(实参.get(1)), 类型.长整数)));
        }
        if ("标准文件写文本".equals(名)) {
            调("cjk_files_write_text_parents", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 行值());
            return 空值();
        }
        if ("并发固定线程池".equals(名)) {
            return 定型(调用, 调("cjk_pool_new", "ptr",
                    转换(表达式(实参.get(0)), 类型.整数), 行值()));
        }
        if ("并发单线程池".equals(名)) {
            return 定型(调用, 调("cjk_pool_new", "ptr", 整数参(1), 行值()));
        }
        if ("并发执行".equals(名)) {
            调("cjk_pool_execute", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 回调引用(实参.get(1)));
            return 空值();
        }
        if ("并发提交".equals(名)) {
            return 定型(调用, 调("cjk_pool_submit", "ptr", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 回调引用(实参.get(1))));
        }
        if ("并发取结果".equals(名)) {
            return 从原生值(调用.类型(), 调("cjk_future_get", "%cjk_value",
                    表达式(实参.get(0)), 行值()));
        }
        if ("并发关闭".equals(名)) {
            调("cjk_pool_shutdown", "void", 表达式(实参.get(0)));
            return 空值();
        }
        if ("并发休眠".equals(名)) {
            调("cjk_sleep", "void", 转换(表达式(实参.get(0)), 类型.长整数));
            return 空值();
        }
        if ("并发当前线程名".equals(名)) {
            return 定型(调用, 调("cjk_thread_name", "ptr"));
        }
        if ("网络取文本".equals(名)) {
            return 定型(调用, 调("cjk_http_get_text", "ptr", 表达式(实参.get(0)), 行值()));
        }
        if ("网络取状态码".equals(名)) {
            return 定型(调用, 调("cjk_http_status", "i32", 表达式(实参.get(0)), 行值()));
        }
        if ("网络取".equals(名)) {
            return 定型(调用, 调("cjk_http_get", "ptr", 表达式(实参.get(0)), 行值()));
        }
        if ("网络下载到".equals(名)) {
            调("cjk_http_download", "void", 表达式(实参.get(0)),
                    表达式(实参.get(1)), 行值());
            return 空值();
        }
        if ("网络响应状态码".equals(名)) {
            return 定型(调用, 调("cjk_http_response_status", "i32", 表达式(调用.接收者())));
        }
        if ("网络响应正文".equals(名)) {
            return 定型(调用, 调("cjk_http_response_body", "ptr", 表达式(调用.接收者())));
        }
        if ("日志取".equals(名)) {
            return 定型(调用, 调("cjk_logger_get", "ptr", 表达式(实参.get(0))));
        }
        if ("日志控制台".equals(名)) {
            return 定型(调用, 调("cjk_logger_console", "ptr", 表达式(实参.get(0))));
        }
        if ("日志信息".equals(名) || "日志警告".equals(名)
                || "日志错误".equals(名) || "日志调试".equals(名)) {
            int 级别 = switch (名) {
                case "日志调试" -> 1;
                case "日志信息" -> 2;
                case "日志警告" -> 3;
                default -> 4;
            };
            调("cjk_logger_log", "void", 表达式(实参.get(0)), 整数参(级别),
                    值参(实参.get(1)), 行值());
            return 空值();
        }
        if ("日志设置级别".equals(名)) {
            if (取类型(实参.get(1)).同型(类型.文本)) {
                调("cjk_logger_set_level_name", "void", 表达式(实参.get(0)),
                        表达式(实参.get(1)), 行值());
            } else {
                调("cjk_logger_set_level", "void", 表达式(实参.get(0)),
                        表达式(实参.get(1)));
            }
            return 空值();
        }
        if ("数据转JSON".equals(名) || "数据转JSON美化".equals(名)) {
            值 美化 = "数据转JSON美化".equals(名) && 实参.size() >= 2
                    ? 转换(表达式(实参.get(1)), 类型.整数)
                    : 整数参(0);
            类型 参数类型 = 取类型(实参.get(0));
            if (参数类型 != null && 参数类型.是数组()) {
                类型 元素 = 参数类型.元素;
                if (元素.是数组()) {
                    throw new 生成异常("M6 暂不支持：数据.转JSON 的多维数组，请先转为清单");
                }
                boolean 文本元素 = 元素.同型(类型.文本);
                return 定型(调用, 调("cjk_json_encode_array", "ptr",
                        表达式(实参.get(0)), 整数参(Long.parseLong(种类码(元素))),
                        整数参(大小(元素)), 整数参(文本元素 ? 1 : 0), 美化, 行值()));
            }
            return 定型(调用, 调("cjk_json_encode", "ptr", 值参(实参.get(0)), 美化, 行值()));
        }
        throw new 生成异常("M6 暂不支持：内建「" + 名 + "」");
    }

    // ================= 类型与查找 =================

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
        throw new 生成异常("M6 暂不支持：未知表达式类型");
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
            类型 形参类型 = 方法.参数().get(i).类型();
            类型 实参 = 实参类型.get(i);
            if (是泛型值(形参类型) || 是包装类(形参类型)) {
                分 += 2;
                continue;
            }
            if (形参类型.同型(实参)) {
                分 += 3;
            } else if (可赋值(形参类型, 实参)) {
                分 += 1;
            } else if (形参类型.是数值() && 实参.是数值()) {
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

    private 中间代码.表达式 文本接收者(中间代码.调用 调用) {
        if (调用.接收者() != null) {
            return 调用.接收者();
        }
        return 调用.实参().get(0);
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
        类型 接收类型 = 取类型(接收);
        if (接收类型 != null && 接收类型.是类()) {
            return 规范接收名(接收类型.类名);
        }
        if (接收类型 != null && 接收类型.同型(类型.文本)) {
            return "文本";
        }
        return 接收类型 == null ? "" : 接收类型.文本();
    }

    private 值 回调引用(中间代码.表达式 实参) {
        类型 声明类型 = 取类型(实参);
        if (声明类型 == null || !声明类型.是类()) {
            throw new 生成异常("M6 暂不支持：该内建需要 λ/方法引用参数");
        }
        return new 值(类型.空, "@" + 回调名(声明类型.类名));
    }

    // ================= 名字 =================

    private static String 方法名(String 类名, 中间代码.方法 方法) {
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

    private static String 构造器名(String 类名, 中间代码.方法 方法) {
        return "_cjkctor_" + 编码(类名) + "_" + 参数签名(参数类型(方法));
    }

    private static String 新建名(String 类名, 中间代码.方法 方法) {
        return "_cjknew_" + 编码(类名) + "_"
                + (方法 == null ? "void" : 参数签名(参数类型(方法)));
    }

    private static String 按名名(String 类名) {
        return "_cjkenumval_" + 编码(类名);
    }

    private static String 描述符名(String 类名) {
        return "_cjkcls_" + 编码(类名);
    }

    private String 描述符引用(String 类名) {
        if (内建类名.contains(类名) || 标准库描述符类名.contains(类名)) {
            String 名 = "CJK_CLS_" + 类名;
            if (已声明描述符.add(名)) {
                IR.声明全局("@\"" + 名 + "\" = external global %cjk_class");
            }
            return "@\"" + 名 + "\"";
        }
        return "@" + 描述符名(类名);
    }

    private static String 结构类型(String 类名) {
        return "%_cjkobj_" + 编码(类名);
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

    private static String 回调名(String 类名) {
        return "_cjkcb_" + 编码(类名);
    }

    private static String 转文本钩子名(String 类名) {
        return "_cjkhook_" + 编码(类名);
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

    // ================= 类型映射 =================

    private static String LLVM类型(类型 类型) {
        if (类型 == null) {
            return "ptr";
        }
        if (类型.是数组()) {
            return "ptr";
        }
        if (类型.是类() || 类型.是空()) {
            return "ptr";
        }
        if (类型.是错误()) {
            return "%cjk_value";
        }
        return switch (类型.基本值) {
            case 整数 -> "i32";
            case 长整数 -> "i64";
            case 短整数 -> "i16";
            case 字节 -> "i8";
            case 浮点 -> "float";
            case 双浮点 -> "double";
            case 字符 -> "i16";
            case 布尔 -> "i32";
            case 文本 -> "ptr";
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
        if (类型.是错误()) {
            return "val";
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
            return "9";
        }
        if (类型.是错误()) {
            return "9";
        }
        return switch (类型.基本值) {
            case 整数 -> "3";
            case 长整数 -> "4";
            case 短整数 -> "2";
            case 字节 -> "1";
            case 浮点 -> "5";
            case 双浮点 -> "6";
            case 字符 -> "7";
            case 布尔 -> "8";
            case 文本 -> "9";
            case 无 -> "9";
        };
    }

    private static int 大小(类型 值类型) {
        if (值类型 == null) {
            return 8;
        }
        if (值类型.是数组() || 值类型.是类() || 值类型.是空()
                || 值类型.同型(类型.文本)) {
            return 8;
        }
        if (值类型.是错误()) {
            return 16;
        }
        return switch (值类型.基本值) {
            case 整数 -> 4;
            case 长整数 -> 8;
            case 短整数 -> 2;
            case 字节 -> 1;
            case 浮点 -> 4;
            case 双浮点 -> 8;
            case 字符 -> 2;
            case 布尔 -> 4;
            case 文本 -> 8;
            case 无 -> 0;
        };
    }

    /** 按 LLVM 结构体自然布局计算对象大小（含 8 字节对象头）。 */
    private int 结构大小(String 类名) {
        int 偏移 = 8;
        int 对齐 = 8;
        List<字段槽> 布局 = 字段布局.get(类名);
        if (布局 != null) {
            for (字段槽 字段 : 布局) {
                int 尺寸 = 大小(字段.类型());
                int 字段对齐 = Math.min(Math.max(尺寸, 1), 8);
                偏移 = (偏移 + 字段对齐 - 1) / 字段对齐 * 字段对齐;
                偏移 += 尺寸;
                对齐 = Math.max(对齐, 字段对齐);
            }
        }
        return (偏移 + 对齐 - 1) / 对齐 * 对齐;
    }

    private static boolean 是泛型值(类型 类型) {
        return 类型 != null && 类型.是类() && 泛型值名.equals(类型.类名);
    }

    private static boolean 是包装类(类型 类型) {
        return 类型 != null && 类型.是类() && 包装类名.contains(类型.类名);
    }

    // ================= 字面量 =================

    private 值 常量值(中间代码.常量 常量) {
        类型 值类型 = 常量.类型();
        String 原文 = 常量.原文();
        if (值类型.同型(类型.文本)) {
            return new 值(类型.文本, 调("cjk_str_from_utf8", "ptr",
                    new 值(类型.空, IR.新字符串(解Java文本(原文)))).文本());
        }
        if (值类型.是空()) {
            return new 值(类型.空, "null");
        }
        if (值类型.是基本()) {
            switch (值类型.基本值) {
                case 整数:
                    return new 值(类型.整数, 整数字面量(原文, false));
                case 长整数:
                    return new 值(类型.长整数, 整数字面量(原文, true));
                case 短整数:
                    return new 值(类型.短整数, Short.toString((short) 解析整数(原文)));
                case 字节:
                    return new 值(类型.字节, Byte.toString((byte) 解析整数(原文)));
                case 浮点:
                    return new 值(类型.浮点, 浮点字面量(原文));
                case 双浮点:
                    return new 值(类型.双浮点, 浮点字面量(原文));
                case 字符:
                    return new 值(类型.字符, 字符字面量(原文));
                case 布尔:
                    return new 值(类型.布尔,
                            ("true".equals(原文) || "真".equals(原文)) ? "1" : "0");
                case 无:
                    break;
                case 文本:
                    return new 值(类型.文本, 调("cjk_str_from_utf8", "ptr",
                            new 值(类型.空, IR.新字符串(解Java文本(原文)))).文本());
            }
        }
        throw new 生成异常("M6 暂不支持：常量 " + 值类型.文本() + " " + 原文);
    }

    private static long 解析整数(String 原文) {
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
        return new BigInteger(数字.isEmpty() ? "0" : 数字, 基数).longValue();
    }

    private static String 整数字面量(String 原文, boolean 长) {
        long 值 = 解析整数(原文);
        return 长 ? Long.toString(值) : Integer.toString((int) 值);
    }

    private static String 浮点字面量(String 原文) {
        String s = 原文.replace("_", "");
        if (s.endsWith("f") || s.endsWith("F") || s.endsWith("d") || s.endsWith("D")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.contains(".") && !s.contains("e") && !s.contains("E")
                && !s.contains("p") && !s.contains("P")) {
            s += ".0";
        }
        return s;
    }

    private static String 字符字面量(String 原文) {
        String 内容 = 解Java文本(原文);
        int 码点 = 内容.isEmpty() ? 0 : 内容.codePointAt(0);
        if (码点 > 0xFFFF) {
            码点 = 0x3F;
        }
        return Integer.toString(码点);
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

    private static String 转义源(String 文本) {
        return 文本.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
