package 中文.原生.中间;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** M2 中间表示：已定型、已脱糖，供打印器与解释器共用。 */
public final class 中间代码 {

    private 中间代码() {}

    public record 程序(List<类定义> 类, 位置信息 位置) {
        /** 兼容 M2：不带行号时位置为空。 */
        public 程序(List<类定义> 类) {
            this(类, new 位置信息(""));
        }
    }

    /** M3 行号旁路表：键为 MIR 语句对象身份，不参与 MIR 文本快照。 */
    public static final class 位置信息 {
        public final String 源路径;
        private final Map<Object, Integer> 行表 = new IdentityHashMap<>();
        private int 最近行;

        public 位置信息(String 源路径) {
            this.源路径 = 源路径 == null ? "" : 源路径;
        }

        public void 记录(Object 节点, int 行) {
            if (节点 == null) {
                return;
            }
            if (行 > 0) {
                最近行 = 行;
                行表.put(节点, 行);
            } else if (最近行 > 0) {
                已记录(节点, 最近行);
            }
        }

        /** 显式写入行号，不推进「最近行」。 */
        public void 已记录(Object 节点, int 行) {
            if (节点 != null && 行 > 0) {
                行表.put(节点, 行);
            }
        }

        public int 行(Object 节点) {
            Integer 行 = 行表.get(节点);
            return 行 == null ? 0 : 行;
        }
    }

    public record 类定义(String 名, List<字段定义> 字段, List<方法> 构造器, List<方法> 方法) {}

    public record 字段定义(类型 类型, String 名) {}

    public record 方法(String 名, boolean 静态, 类型 返回类型, List<参数> 参数, 语句 体) {}

    public record 参数(类型 类型, String 名) {}

    public sealed interface 语句 permits 块, 局部变量语句, 表达式语句, 返回语句,
            若语句, 对于语句, 当语句, 做当语句, 跳出语句, 继续语句 {
    }

    public record 块(List<语句> 语句) implements 语句 {}

    public record 局部变量语句(类型 类型, String 名, 表达式 初值) implements 语句 {}

    public record 表达式语句(表达式 值) implements 语句 {}

    public record 返回语句(表达式 值) implements 语句 {}

    public record 若语句(表达式 条件, 语句 成立, 语句 否则) implements 语句 {}

    public record 对于语句(语句 初始化, 表达式 条件, List<表达式> 更新, 语句 体) implements 语句 {}

    public record 当语句(表达式 条件, 语句 体) implements 语句 {}

    public record 做当语句(语句 体, 表达式 条件) implements 语句 {}

    public record 跳出语句() implements 语句 {}

    public record 继续语句() implements 语句 {}

    public enum 分派 {
        内建, 静态, 实例, 构造器
    }

    public sealed interface 表达式 permits 常量, 变量读, 字段读, 数组读, 数组长度,
            二元, 一元, 三目, 强制转换, 赋值, 调用, 新建对象, 新建数组 {
    }

    public record 常量(类型 类型, String 原文) implements 表达式 {}

    public record 变量读(类型 类型, String 名) implements 表达式 {}

    public record 字段读(类型 类型, 表达式 接收者, String 名) implements 表达式 {}

    public record 数组读(类型 类型, 表达式 数组, 表达式 下标) implements 表达式 {}

    public record 数组长度(类型 类型, 表达式 数组) implements 表达式 {}

    public record 二元(类型 类型, String 运算符, 表达式 左, 表达式 右) implements 表达式 {}

    public record 一元(类型 类型, String 运算符, 表达式 操作数) implements 表达式 {}

    public record 三目(类型 类型, 表达式 条件, 表达式 真, 表达式 假) implements 表达式 {}

    public record 强制转换(类型 类型, 表达式 操作数) implements 表达式 {}

    public record 赋值(类型 类型, 表达式 左, 表达式 右) implements 表达式 {}

    public record 调用(类型 类型, 分派 分派, String 类名, String 名,
                     表达式 接收者, List<表达式> 实参) implements 表达式 {}

    public record 新建对象(类型 类型, String 类名, List<表达式> 实参) implements 表达式 {}

    public record 新建数组(类型 类型, 类型 元素类型, 表达式 长度, List<表达式> 初值) implements 表达式 {}
}
