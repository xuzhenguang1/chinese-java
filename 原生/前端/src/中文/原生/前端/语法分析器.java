package 中文.原生.前端;

import 中文.原生.前端.标记.标记类别;
import 中文.原生.前端.语法树.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 手写递归下降语法分析器，覆盖 35 课语法。 */
public final class 语法分析器 {

    public static final class 语法异常 extends RuntimeException {
        public final int 行;
        public final int 列;
        public final String 消息;
        public final String 建议;

        public 语法异常(int 行, int 列, String 消息, String 建议) {
            super(消息);
            this.行 = 行;
            this.列 = 列;
            this.消息 = 消息;
            this.建议 = 建议;
        }
    }

    private static final Set<String> 基本类型 = Set.of(
            "int", "long", "short", "byte", "float", "double", "char", "boolean", "void");

    private static final Set<String> 修饰词 = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "sealed",
            "non-sealed", "default", "native", "synchronized", "volatile", "transient", "strictfp");

    private final List<标记> 标记;
    private final Map<语法树.节点, Integer> 语句行号 = new IdentityHashMap<>();
    private int 位置;

    public 语法分析器(List<标记> 标记) {
        this.标记 = 标记;
        this.位置 = 0;
    }

    /** M3 行号旁路表：语句节点 → 起始行；不参与 AST 文本打印。 */
    public Map<语法树.节点, Integer> 语句行号() {
        return 语句行号;
    }

    public 编译单元 解析() {
        List<导入声明> 导入 = new ArrayList<>();
        List<类型声明> 类型 = new ArrayList<>();
        List<语句> 语句 = new ArrayList<>();
        while (看().类别 != 标记类别.结束) {
            if (是关键字("import")) {
                导入.add(解析导入());
            } else if (是类型声明起点()) {
                类型.add(解析类型声明());
            } else {
                语句.add(解析语句());
            }
        }
        return new 编译单元(导入, 类型, 语句);
    }

    /** 解析单个表达式：供字符串插值等内部复用，不消费文件级结构。 */
    public 表达式 解析单个表达式() {
        return 解析表达式();
    }

    // ---------- 顶层与成员 ----------

    private 导入声明 解析导入() {
        取();
        StringBuilder 名称 = new StringBuilder(期望名称().原文);
        while (是符号(".")) {
            取();
            if (是符号("*")) {
                取();
                期望符号(";");
                return new 导入声明(名称.toString(), true);
            }
            名称.append('.').append(期望名称().原文);
        }
        期望符号(";");
        return new 导入声明(名称.toString(), false);
    }

    private boolean 是类型声明起点() {
        int i = 位置;
        while (i < 标记.size()) {
            标记 当前 = 标记.get(i);
            if (当前.是符号("@")) {
                i += 2;
                continue;
            }
            if (当前.类别 == 标记类别.关键字 && 修饰词.contains(当前.规范)) {
                i++;
                continue;
            }
            break;
        }
        if (i >= 标记.size()) {
            return false;
        }
        标记 当前 = 标记.get(i);
        return 当前.类别 == 标记类别.关键字
                && (当前.规范.equals("class") || 当前.规范.equals("interface")
                || 当前.规范.equals("enum") || 当前.规范.equals("record"));
    }

    private 类型声明 解析类型声明() {
        return 解析类型声明(解析修饰符());
    }

    private 类型声明 解析类型声明(List<String> 修饰符) {
        标记 种 = 取();
        String 种类 = 种.原文;
        标记 名 = 期望名称();
        List<类型参数> 类型参数 = 解析可选类型参数();

        类型引用 父类 = null;
        List<类型引用> 接口 = new ArrayList<>();
        List<类型引用> 许可 = new ArrayList<>();
        List<参数> 分量 = new ArrayList<>();

        if (种类.equals("记录")) {
            分量 = 解析参数列表();
        }
        if (是关键字("extends")) {
            取();
            父类 = 解析类型();
        }
        if (是关键字("implements")) {
            取();
            接口 = 解析类型列表();
        }
        if (是关键字("permits")) {
            取();
            许可 = 解析类型列表();
        }

        期望符号("{");
        List<枚举常量> 常量 = new ArrayList<>();
        if (种类.equals("枚举")) {
            常量 = 解析枚举常量();
            if (是符号(";")) {
                取();
            }
        }
        List<成员> 成员列表 = new ArrayList<>();
        while (!是符号("}")) {
            if (看().类别 == 标记类别.结束) {
                抛("缺少「}」", "检查类型声明的花括号是否成对。");
            }
            成员列表.add(解析成员(名.原文));
        }
        期望符号("}");
        return new 类型声明(种类, 修饰符, 名.原文, 类型参数, 父类, 接口, 许可, 分量, 常量, 成员列表);
    }

    private 成员 解析成员(String 当前类型名) {
        if (是符号(";")) {
            取();
            return new 空成员();
        }
        List<String> 修饰符 = 解析修饰符();
        if (是关键字("class") || 是关键字("interface") || 是关键字("enum") || 是关键字("record")) {
            return 解析类型声明(修饰符);
        }
        if (看().类别 == 标记类别.标识符 && 看().原文.equals("入口")) {
            取();
            List<类型引用> 抛出 = 解析可选声明抛出();
            语句 体 = 解析块();
            return new 入口声明(抛出, 体);
        }

        List<类型参数> 方法类型参数 = List.of();
        if (是符号("<")) {
            方法类型参数 = 解析可选类型参数();
        }

        if (方法类型参数.isEmpty()
                && 看().类别 == 标记类别.标识符
                && 看().原文.equals(当前类型名)
                && 看(1).是符号("(")) {
            标记 名 = 取();
            List<参数> 参数 = 解析参数列表();
            List<类型引用> 抛出 = 解析可选声明抛出();
            语句 体 = 解析块();
            return new 构造器声明(修饰符, 名.原文, 参数, 抛出, 体);
        }

        类型引用 返回类型 = 解析类型();
        标记 名 = 期望名称();
        if (是符号("(")) {
            List<参数> 参数 = 解析参数列表();
            List<类型引用> 抛出 = 解析可选声明抛出();
            语句 体 = null;
            if (是符号(";")) {
                取();
            } else {
                体 = 解析块();
            }
            return new 方法声明(修饰符, 方法类型参数, 返回类型, 名.原文, 参数, 抛出, 体);
        }

        List<变量声明> 变量 = new ArrayList<>();
        变量.add(解析变量声明剩余(返回类型, 名.原文));
        while (是符号(",")) {
            取();
            标记 下一个 = 期望名称();
            变量.add(解析变量声明剩余(返回类型, 下一个.原文));
        }
        期望符号(";");
        return new 字段声明(修饰符, 返回类型, 变量);
    }

    private 变量声明 解析变量声明剩余(类型引用 类型, String 名) {
        表达式 初值 = null;
        if (是符号("=")) {
            取();
            初值 = 解析变量初始化器();
        }
        return new 变量声明(类型, 名, 初值);
    }

    private 表达式 解析变量初始化器() {
        if (是符号("{")) {
            return 解析数组初始化();
        }
        return 解析表达式();
    }

    private List<参数> 解析参数列表() {
        期望符号("(");
        List<参数> 参数 = new ArrayList<>();
        if (!是符号(")")) {
            while (true) {
                类型引用 类型 = 解析类型();
                boolean 可变 = false;
                if (是符号("...")) {
                    取();
                    可变 = true;
                }
                标记 名 = 期望名称();
                参数.add(new 参数(类型, 名.原文, 可变));
                if (是符号(",")) {
                    取();
                    continue;
                }
                break;
            }
        }
        期望符号(")");
        return 参数;
    }

    private List<类型参数> 解析可选类型参数() {
        if (!是符号("<")) {
            return List.of();
        }
        取();
        List<类型参数> 列表 = new ArrayList<>();
        if (!是符号(">")) {
            while (true) {
                标记 名 = 期望名称();
                类型引用 上界 = null;
                if (是关键字("extends")) {
                    取();
                    上界 = 解析类型();
                }
                列表.add(new 类型参数(名.原文, 上界));
                if (是符号(",")) {
                    取();
                    continue;
                }
                break;
            }
        }
        期望符号(">");
        return 列表;
    }

    private List<类型引用> 解析类型列表() {
        List<类型引用> 列表 = new ArrayList<>();
        列表.add(解析类型());
        while (是符号(",")) {
            取();
            列表.add(解析类型());
        }
        return 列表;
    }

    private List<类型引用> 解析可选声明抛出() {
        if (!是关键字("throws")) {
            return List.of();
        }
        取();
        return 解析类型列表();
    }

    private List<枚举常量> 解析枚举常量() {
        List<枚举常量> 列表 = new ArrayList<>();
        while (!是符号(";") && !是符号("}")) {
            标记 名 = 期望名称();
            List<表达式> 实参 = List.of();
            if (是符号("(")) {
                实参 = 解析实参();
            }
            列表.add(new 枚举常量(名.原文, 实参));
            if (是符号(",")) {
                取();
                continue;
            }
            break;
        }
        return 列表;
    }

    // ---------- 语句 ----------

    private 语句 解析语句() {
        int 行 = 看().行;
        语句 结果 = 解析语句核心();
        if (结果 != null && 行 > 0) {
            语句行号.put(结果, 行);
        }
        return 结果;
    }

    private 语句 解析语句核心() {
        if (是符号("{")) {
            return 解析块();
        }
        if (是关键字("if")) {
            return 解析若();
        }
        if (是关键字("for")) {
            return 解析对于();
        }
        if (是关键字("while")) {
            return 解析当();
        }
        if (是关键字("do")) {
            return 解析做当();
        }
        if (是关键字("switch")) {
            return 解析切换语句();
        }
        if (是关键字("try")) {
            return 解析尝试();
        }
        if (是关键字("return")) {
            取();
            表达式 值 = 是符号(";") ? null : 解析表达式();
            期望符号(";");
            return new 返回语句(值);
        }
        if (是关键字("throw")) {
            取();
            表达式 值 = 解析表达式();
            期望符号(";");
            return new 抛出语句(值);
        }
        if (是关键字("yield")) {
            取();
            表达式 值 = 解析表达式();
            期望符号(";");
            return new 产出语句(值);
        }
        if (是关键字("break")) {
            取();
            String 标签 = 看().类别 == 标记类别.标识符 ? 取().原文 : null;
            期望符号(";");
            return new 跳出语句(标签);
        }
        if (是关键字("continue")) {
            取();
            String 标签 = 看().类别 == 标记类别.标识符 ? 取().原文 : null;
            期望符号(";");
            return new 继续语句(标签);
        }
        if (是符号(";")) {
            取();
            return new 空语句();
        }
        if (看().类别 == 标记类别.标识符 && 看().原文.equals("重复")) {
            return 解析重复();
        }

        int 保存 = 位置;
        try {
            类型引用 类型 = 解析类型();
            if (看().类别 == 标记类别.标识符) {
                标记 名 = 取();
                变量声明 声明 = 解析变量声明剩余(类型, 名.原文);
                期望符号(";");
                return new 局部变量语句(声明);
            }
        } catch (语法异常 忽略) {
            // 不是变量声明，回退后按表达式语句解析。
        }
        位置 = 保存;

        表达式 值 = 解析表达式();
        期望符号(";");
        return new 表达式语句(值);
    }

    private 块 解析块() {
        期望符号("{");
        List<语句> 语句 = new ArrayList<>();
        while (!是符号("}")) {
            if (看().类别 == 标记类别.结束) {
                抛("缺少「}」", "检查花括号是否成对。");
            }
            语句.add(解析语句());
        }
        期望符号("}");
        return new 块(语句);
    }

    private 语句 解析若() {
        取();
        期望符号("(");
        表达式 条件 = 解析表达式();
        期望符号(")");
        语句 成立 = 解析语句();
        return new 若语句(条件, 成立, 解析否则剩余());
    }

    private 语句 解析否则剩余() {
        if (是关键字("else")) {
            取();
            return 解析语句();
        }
        if (看().类别 == 标记类别.标识符 && 看().原文.equals("否则若")) {
            取();
            期望符号("(");
            表达式 条件 = 解析表达式();
            期望符号(")");
            语句 成立 = 解析语句();
            return new 若语句(条件, 成立, 解析否则剩余());
        }
        return null;
    }

    private 语句 解析对于() {
        取();
        期望符号("(");
        if (是增强对于头()) {
            变量声明 变量;
            if (看(1).是符号(":")) {
                标记 名 = 取();
                变量 = new 变量声明(null, 名.原文, null);
            } else {
                类型引用 类型 = 解析类型();
                标记 名 = 期望名称();
                变量 = new 变量声明(类型, 名.原文, null);
            }
            期望符号(":");
            表达式 可迭代 = 解析表达式();
            期望符号(")");
            return new 增强对于(变量, 可迭代, 解析语句());
        }

        语句 初始化 = null;
        if (!是符号(";")) {
            int 保存 = 位置;
            try {
                类型引用 类型 = 解析类型();
                if (看().类别 == 标记类别.标识符) {
                    标记 名 = 取();
                    表达式 初值 = null;
                    if (是符号("=")) {
                        取();
                        初值 = 解析变量初始化器();
                    }
                    初始化 = new 局部变量语句(new 变量声明(类型, 名.原文, 初值));
                } else {
                    位置 = 保存;
                }
            } catch (语法异常 忽略) {
                位置 = 保存;
            }
            if (初始化 == null) {
                表达式 首项 = 解析表达式();
                while (是符号(",")) {
                    取();
                    解析表达式();
                }
                初始化 = new 表达式语句(首项);
            }
        }
        期望符号(";");
        表达式 条件 = 是符号(";") ? null : 解析表达式();
        期望符号(";");
        List<表达式> 更新 = new ArrayList<>();
        if (!是符号(")")) {
            更新.add(解析表达式());
            while (是符号(",")) {
                取();
                更新.add(解析表达式());
            }
        }
        期望符号(")");
        return new 对于语句(初始化, 条件, 更新, 解析语句());
    }

    private boolean 是增强对于头() {
        int 深度 = 1;
        for (int i = 位置; i < 标记.size(); i++) {
            标记 当前 = 标记.get(i);
            if (当前.是符号("(")) {
                深度++;
            } else if (当前.是符号(")")) {
                深度--;
                if (深度 == 0) {
                    return false;
                }
            } else if (深度 == 1 && 当前.是符号(":")) {
                return true;
            } else if (深度 == 1 && 当前.是符号(";")) {
                return false;
            }
        }
        return false;
    }

    private 语句 解析当() {
        取();
        期望符号("(");
        表达式 条件 = 解析表达式();
        期望符号(")");
        return new 当语句(条件, 解析语句());
    }

    private 语句 解析做当() {
        取();
        语句 体 = 解析语句();
        if (!是关键字("while")) {
            抛("「做」语句缺少「当」", "写成「做 { … } 当 (条件);」。");
        }
        取();
        期望符号("(");
        表达式 条件 = 解析表达式();
        期望符号(")");
        期望符号(";");
        return new 做当语句(体, 条件);
    }

    private 语句 解析重复() {
        取();
        表达式 次数;
        if (是符号("(")) {
            取();
            次数 = 解析表达式();
            期望符号(")");
        } else {
            次数 = 解析表达式();
        }
        if (!(看().类别 == 标记类别.标识符 && 看().原文.equals("次"))) {
            抛("「重复」语句缺少「次」", "写成「重复 N 次 { … }」。");
        }
        取();
        String 变量 = "序号";
        if (是符号("(")) {
            取();
            变量 = 期望名称().原文;
            期望符号(")");
        }
        return new 重复语句(次数, 变量, 解析语句());
    }

    private 语句 解析切换语句() {
        取();
        期望符号("(");
        表达式 选择子 = 解析表达式();
        期望符号(")");
        return new 切换语句(选择子, 解析切换分支());
    }

    private 切换表达式 解析切换表达式() {
        取();
        期望符号("(");
        表达式 选择子 = 解析表达式();
        期望符号(")");
        return new 切换表达式(选择子, 解析切换分支());
    }

    private List<切换分支> 解析切换分支() {
        期望符号("{");
        List<切换分支> 分支 = new ArrayList<>();
        while (!是符号("}")) {
            if (是关键字("case")) {
                取();
                List<表达式> 情形 = new ArrayList<>();
                情形.add(解析表达式());
                while (是符号(",")) {
                    取();
                    情形.add(解析表达式());
                }
                期望符号("->");
                分支.add(new 切换分支(情形, 解析切换结果(), false));
            } else if (是关键字("default")) {
                取();
                期望符号("->");
                分支.add(new 切换分支(List.of(), 解析切换结果(), true));
            } else {
                抛("切换分支需要「情形」或「默认」", "写成「情形 值 -> 结果;」或「默认 -> 结果;」。");
            }
        }
        期望符号("}");
        return 分支;
    }

    private 语句 解析切换结果() {
        if (是符号("{")) {
            return 解析块();
        }
        表达式 值 = 解析表达式();
        期望符号(";");
        return new 表达式语句(值);
    }

    private 语句 解析尝试() {
        取();
        语句 尝试块 = 解析块();
        List<捕获子句> 捕获 = new ArrayList<>();
        while (是关键字("catch")) {
            取();
            期望符号("(");
            类型引用 类型 = 解析类型();
            标记 名 = 期望名称();
            期望符号(")");
            捕获.add(new 捕获子句(类型, 名.原文, 解析块()));
        }
        语句 兜底 = null;
        if (是关键字("finally")) {
            取();
            兜底 = 解析块();
        }
        return new 尝试语句(尝试块, 捕获, 兜底);
    }

    // ---------- 表达式 ----------

    private 表达式 解析表达式() {
        表达式 左 = 解析三目();
        if (是赋值符号()) {
            String 运算符 = 取().原文;
            return new 赋值表达式(运算符, 左, 解析表达式());
        }
        return 左;
    }

    private boolean 是赋值符号() {
        if (看().类别 != 标记类别.符号) {
            return false;
        }
        return switch (看().原文) {
            case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" -> true;
            default -> false;
        };
    }

    private 表达式 解析三目() {
        表达式 条件 = 解析二元(1);
        if (是符号("?")) {
            取();
            表达式 真 = 解析表达式();
            期望符号(":");
            表达式 假 = 解析三目();
            return new 三目表达式(条件, 真, 假);
        }
        return 条件;
    }

    private 表达式 解析二元(int 最低) {
        表达式 左 = 解析一元();
        while (true) {
            标记 当前 = 看();
            int 优先级 = 优先级(当前);
            if (优先级 < 最低) {
                break;
            }
            取();
            if (当前.类别 == 标记类别.关键字 && 当前.规范.equals("instanceof")) {
                类型引用 类型 = 解析类型();
                String 绑定 = 看().类别 == 标记类别.标识符 ? 取().原文 : null;
                左 = new 实例判断(左, 类型, 绑定);
            } else {
                左 = new 二元(当前.原文, 左, 解析二元(优先级 + 1));
            }
        }
        return 左;
    }

    private static int 优先级(标记 当前) {
        if (当前.类别 == 标记类别.关键字 && 当前.规范.equals("instanceof")) {
            return 7;
        }
        if (当前.类别 != 标记类别.符号) {
            return -1;
        }
        return switch (当前.原文) {
            case "||" -> 1;
            case "&&" -> 2;
            case "|" -> 3;
            case "^" -> 4;
            case "&" -> 5;
            case "==", "!=" -> 6;
            case "<", ">", "<=", ">=" -> 7;
            case "<<", ">>", ">>>" -> 8;
            case "+", "-" -> 9;
            case "*", "/", "%" -> 10;
            default -> -1;
        };
    }

    private 表达式 解析一元() {
        if (是符号("++") || 是符号("--")) {
            String 运算符 = 取().原文;
            return new 后缀自增(解析一元(), 运算符, true);
        }
        if (是符号("+") || 是符号("-") || 是符号("!") || 是符号("~")) {
            String 运算符 = 取().原文;
            return new 一元(运算符, 解析一元(), true);
        }
        if (是符号("(")) {
            int 保存 = 位置;
            try {
                取();
                类型引用 类型 = 解析类型();
                if (是符号(")")) {
                    取();
                    if (可以作为一元起点(看())) {
                        return new 强制转换(类型, 解析一元());
                    }
                }
            } catch (语法异常 忽略) {
                // 不是强制转换，回退到括号表达式。
            }
            位置 = 保存;
        }
        return 解析后缀();
    }

    private static boolean 可以作为一元起点(标记 当前) {
        if (当前.类别 == 标记类别.数字
                || 当前.类别 == 标记类别.文本
                || 当前.类别 == 标记类别.字符
                || 当前.类别 == 标记类别.标识符) {
            return true;
        }
        if (当前.类别 == 标记类别.关键字) {
            return Set.of("this", "super", "true", "false", "null", "new", "switch").contains(当前.规范)
                    || 基本类型.contains(当前.规范);
        }
        if (当前.类别 == 标记类别.符号) {
            return Set.of("(", "!", "-", "+", "~", "++", "--").contains(当前.原文);
        }
        return false;
    }

    private 表达式 解析后缀() {
        表达式 当前 = 解析基本();
        while (true) {
            if (是符号(".")) {
                取();
                标记 名 = 取成员名();
                if (是符号("(")) {
                    当前 = new 调用(当前, 名.原文, 解析实参());
                } else {
                    当前 = new 字段访问(当前, 名.原文);
                }
            } else if (是符号("[")) {
                取();
                表达式 下标 = 解析表达式();
                期望符号("]");
                当前 = new 数组访问(当前, 下标);
            } else if (是符号("(")) {
                当前 = new 调用(当前, null, 解析实参());
            } else if (是符号("::")) {
                取();
                if (是关键字("new")) {
                    取();
                    当前 = new 方法引用(当前, "新建", true);
                } else {
                    标记 名 = 取成员名();
                    当前 = new 方法引用(当前, 名.原文, false);
                }
            } else if (是符号("++") || 是符号("--")) {
                当前 = new 后缀自增(当前, 取().原文, false);
            } else {
                break;
            }
        }
        return 当前;
    }

    private 表达式 解析基本() {
        标记 当前 = 看();
        if (当前.类别 == 标记类别.数字) {
            取();
            return new 字面量(当前.原文, "数字");
        }
        if (当前.类别 == 标记类别.文本) {
            取();
            return new 字面量(当前.原文, "文本");
        }
        if (当前.类别 == 标记类别.字符) {
            取();
            return new 字面量(当前.原文, "字符");
        }
        if (当前.类别 == 标记类别.关键字) {
            return switch (当前.规范) {
                case "new" -> 解析新建();
                case "switch" -> 解析切换表达式();
                case "true", "false" -> {
                    取();
                    yield new 字面量(当前.原文, "布尔");
                }
                case "null" -> {
                    取();
                    yield new 字面量(当前.原文, "空");
                }
                case "this", "super" -> {
                    取();
                    yield new 标识符(当前.原文);
                }
                default -> {
                    if (基本类型.contains(当前.规范)) {
                        取();
                        yield new 标识符(当前.原文);
                    }
                    抛("表达式中不能使用关键字「" + 当前.原文 + "」", "检查此处是否缺少表达式。");
                    yield null;
                }
            };
        }
        if (当前.类别 == 标记类别.标识符) {
            if (看(1).是符号("->")) {
                取();
                取();
                return new λ表达式(List.of(new 参数(null, 当前.原文, false)), 解析λ体());
            }
            取();
            return new 标识符(当前.原文);
        }
        if (是符号("(")) {
            if (是括号λ()) {
                return 解析括号λ();
            }
            取();
            表达式 内部 = 解析表达式();
            期望符号(")");
            return 内部;
        }
        if (是符号("{")) {
            return 解析数组初始化();
        }
        抛("无法识别的表达式", "检查此处是否缺少操作数或写错了符号。");
        return null;
    }

    private 表达式 解析新建() {
        取();
        类型引用 类型 = 解析类型();
        if (是符号("[")) {
            取();
            表达式 长度 = 解析表达式();
            期望符号("]");
            return new 新建数组(类型, 长度, List.of(), 1);
        }
        if (是符号("{")) {
            return new 新建数组(类型, null, 解析数组初始化().元素(), Math.max(1, 类型.数组维数()));
        }
        return new 新建对象(类型, 解析实参());
    }

    private 数组初始化 解析数组初始化() {
        期望符号("{");
        List<表达式> 元素 = new ArrayList<>();
        if (!是符号("}")) {
            while (true) {
                if (是符号("{")) {
                    元素.add(解析数组初始化());
                } else {
                    元素.add(解析表达式());
                }
                if (是符号(",")) {
                    取();
                    if (是符号("}")) {
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        期望符号("}");
        return new 数组初始化(元素);
    }

    private List<表达式> 解析实参() {
        期望符号("(");
        List<表达式> 实参 = new ArrayList<>();
        if (!是符号(")")) {
            while (true) {
                实参.add(解析表达式());
                if (是符号(",")) {
                    取();
                    continue;
                }
                break;
            }
        }
        期望符号(")");
        return 实参;
    }

    private boolean 是括号λ() {
        int 深度 = 0;
        for (int i = 位置; i < 标记.size(); i++) {
            标记 当前 = 标记.get(i);
            if (当前.是符号("(")) {
                深度++;
            } else if (当前.是符号(")")) {
                深度--;
                if (深度 == 0) {
                    return i + 1 < 标记.size() && 标记.get(i + 1).是符号("->");
                }
            }
        }
        return false;
    }

    private 表达式 解析括号λ() {
        期望符号("(");
        List<参数> 参数 = new ArrayList<>();
        if (!是符号(")")) {
            while (true) {
                参数.add(解析λ参数());
                if (是符号(",")) {
                    取();
                    continue;
                }
                break;
            }
        }
        期望符号(")");
        期望符号("->");
        return new λ表达式(参数, 解析λ体());
    }

    private 参数 解析λ参数() {
        if (看().类别 == 标记类别.标识符
                && (看(1).是符号(",") || 看(1).是符号(")"))) {
            标记 名 = 取();
            return new 参数(null, 名.原文, false);
        }
        类型引用 类型 = 解析类型();
        标记 名 = 期望名称();
        return new 参数(类型, 名.原文, false);
    }

    private 语句 解析λ体() {
        if (是符号("{")) {
            return 解析块();
        }
        return new 表达式语句(解析表达式());
    }

    // ---------- 类型与基础 ----------

    private 类型引用 解析类型() {
        标记 当前 = 看();
        String 名;
        boolean 基本 = false;
        if (当前.类别 == 标记类别.关键字 && 基本类型.contains(当前.规范)) {
            名 = 当前.原文;
            基本 = true;
            取();
        } else if (当前.类别 == 标记类别.标识符) {
            名 = 当前.原文;
            取();
        } else {
            抛("此处需要类型", "检查变量、参数或返回值是否漏写类型。");
            return null;
        }

        List<类型引用> 实参 = new ArrayList<>();
        if (是符号("<")) {
            取();
            if (!是符号(">")) {
                while (true) {
                    实参.add(解析类型());
                    if (是符号(",")) {
                        取();
                        continue;
                    }
                    break;
                }
            }
            期望大于号();
        }

        int 维数 = 0;
        while (是符号("[") && 看(1).是符号("]")) {
            取();
            取();
            维数++;
        }
        return new 类型引用(名, 实参, 维数, 基本);
    }

    private List<String> 解析修饰符() {
        List<String> 列表 = new ArrayList<>();
        while (true) {
            标记 当前 = 看();
            if (当前.是符号("@")) {
                取();
                标记 名 = 期望名称();
                列表.add("@" + 名.原文);
                continue;
            }
            if (当前.类别 == 标记类别.关键字 && 修饰词.contains(当前.规范)) {
                列表.add(当前.规范);
                取();
                continue;
            }
            break;
        }
        return 列表;
    }

    private 标记 看() {
        return 看(0);
    }

    private 标记 看(int 偏移) {
        int 序号 = 位置 + 偏移;
        if (序号 < 0 || 序号 >= 标记.size()) {
            return 标记.get(标记.size() - 1);
        }
        return 标记.get(序号);
    }

    private boolean 是关键字(String 英文) {
        return 看().是关键字(英文);
    }

    private boolean 是符号(String 文本) {
        return 看().是符号(文本);
    }

    private 标记 取() {
        标记 当前 = 看();
        if (当前.类别 != 标记类别.结束) {
            位置++;
        }
        return 当前;
    }

    private 标记 期望名称() {
        标记 当前 = 看();
        if (当前.类别 != 标记类别.标识符) {
            抛("此处需要名称", "检查是否漏写类名、方法名或变量名。");
        }
        return 取();
    }

    /** 成员名允许关键字：`分数.遍历(…)`、`可选值.空()` 这类名称在词表里也是关键字。 */
    private 标记 取成员名() {
        标记 当前 = 看();
        if (当前.类别 != 标记类别.标识符 && 当前.类别 != 标记类别.关键字) {
            抛("此处需要名称", "检查是否漏写类名、方法名或变量名。");
        }
        return 取();
    }

    /** 泛型收尾：`>>` / `>>>` 词法上是移位符号，这里拆出第一个 `>`。 */
    private void 期望大于号() {
        标记 当前 = 看();
        if (当前.是符号(">")) {
            取();
            return;
        }
        if (当前.是符号(">>") || 当前.是符号(">>>")) {
            String 剩余 = 当前.原文.substring(1);
            标记.set(位置, new 标记(标记类别.符号, 当前.起 + 1, 当前.止,
                    剩余, 剩余, 当前.行, 当前.列 + 1));
            return;
        }
        抛("缺少「>」", "检查泛型尖括号是否成对。");
    }

    private 标记 期望符号(String 符号) {
        if (!是符号(符号)) {
            抛("缺少「" + 符号 + "」", "检查上一行末尾或此处的括号/分号是否成对。");
        }
        return 取();
    }

    private void 抛(String 消息, String 建议) {
        标记 当前 = 看();
        throw new 语法异常(当前.行, 当前.列, 消息, 建议);
    }
}
