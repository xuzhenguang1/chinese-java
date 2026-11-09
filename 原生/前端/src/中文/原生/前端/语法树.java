package 中文.原生.前端;

import java.util.List;

/** M1 语法树：sealed 接口 + record，字段只描述结构，不做类型检查。 */
public final class 语法树 {

    private 语法树() {}

    public sealed interface 节点 permits 编译单元, 导入声明, 类型声明, 成员, 语句, 表达式,
            类型引用, 类型参数, 参数, 变量声明, 枚举常量, 切换分支, 捕获子句 {
    }

    public sealed interface 成员 extends 节点 permits 类型声明, 字段声明, 方法声明, 构造器声明, 入口声明, 空成员 {
    }

    public sealed interface 语句 extends 节点 permits 块, 若语句, 对于语句, 增强对于, 当语句, 做当语句,
            重复语句, 切换语句, 尝试语句, 返回语句, 抛出语句, 产出语句, 跳出语句, 继续语句,
            局部变量语句, 表达式语句, 空语句 {
    }

    public sealed interface 表达式 extends 节点 permits 字面量, 标识符, 二元, 一元, 赋值表达式, 三目表达式,
            强制转换, 实例判断, 调用, 字段访问, 数组访问, 新建对象, 新建数组, 数组初始化,
            λ表达式, 方法引用, 后缀自增, 切换表达式 {
    }

    public record 编译单元(List<导入声明> 导入, List<类型声明> 类型, List<语句> 语句) implements 节点 {}

    public record 导入声明(String 名称, boolean 通配) implements 节点 {}

    public record 类型参数(String 名, 类型引用 上界) implements 节点 {}

    public record 类型引用(String 名, List<类型引用> 实参, int 数组维数, boolean 基本类型) implements 节点 {}

    public record 参数(类型引用 类型, String 名, boolean 可变参数) implements 节点 {}

    public record 变量声明(类型引用 类型, String 名, 表达式 初值) implements 节点 {}

    public record 类型声明(String 种类, List<String> 修饰符, String 名,
                        List<类型参数> 类型参数, 类型引用 父类, List<类型引用> 接口,
                        List<类型引用> 许可, List<参数> 分量, List<枚举常量> 枚举常量,
                        List<成员> 成员) implements 节点, 成员 {}

    public record 字段声明(List<String> 修饰符, 类型引用 类型, List<变量声明> 变量) implements 成员 {}

    public record 方法声明(List<String> 修饰符, List<类型参数> 类型参数, 类型引用 返回类型,
                        String 名, List<参数> 参数, List<类型引用> 抛出, 语句 体) implements 成员 {}

    public record 构造器声明(List<String> 修饰符, String 名, List<参数> 参数,
                          List<类型引用> 抛出, 语句 体) implements 成员 {}

    public record 入口声明(List<类型引用> 抛出, 语句 体) implements 成员 {}

    public record 空成员() implements 成员 {}

    public record 枚举常量(String 名, List<表达式> 实参) implements 节点 {}

    public record 块(List<语句> 语句) implements 语句 {}

    public record 若语句(表达式 条件, 语句 成立, 语句 否则) implements 语句 {}

    public record 对于语句(语句 初始化, 表达式 条件, List<表达式> 更新, 语句 体) implements 语句 {}

    public record 增强对于(变量声明 变量, 表达式 可迭代, 语句 体) implements 语句 {}

    public record 当语句(表达式 条件, 语句 体) implements 语句 {}

    public record 做当语句(语句 体, 表达式 条件) implements 语句 {}

    public record 重复语句(表达式 次数, String 变量名, 语句 体) implements 语句 {}

    public record 切换语句(表达式 选择子, List<切换分支> 分支) implements 语句 {}

    public record 尝试语句(语句 尝试块, List<捕获子句> 捕获, 语句 兜底) implements 语句 {}

    public record 返回语句(表达式 值) implements 语句 {}

    public record 抛出语句(表达式 值) implements 语句 {}

    public record 产出语句(表达式 值) implements 语句 {}

    public record 跳出语句(String 标签) implements 语句 {}

    public record 继续语句(String 标签) implements 语句 {}

    public record 局部变量语句(变量声明 声明) implements 语句 {}

    public record 表达式语句(表达式 值) implements 语句 {}

    public record 空语句() implements 语句 {}

    public record 切换分支(List<表达式> 情形, 语句 结果, boolean 是默认) implements 节点 {}

    public record 捕获子句(类型引用 类型, String 名, 语句 块) implements 节点 {}

    public record 字面量(String 原文, String 种类) implements 表达式 {}

    public record 标识符(String 名) implements 表达式 {}

    public record 二元(String 运算符, 表达式 左, 表达式 右) implements 表达式 {}

    public record 一元(String 运算符, 表达式 操作数, boolean 前缀) implements 表达式 {}

    public record 赋值表达式(String 运算符, 表达式 左, 表达式 右) implements 表达式 {}

    public record 三目表达式(表达式 条件, 表达式 真, 表达式 假) implements 表达式 {}

    public record 强制转换(类型引用 类型, 表达式 操作数) implements 表达式 {}

    public record 实例判断(表达式 左, 类型引用 类型, String 绑定名) implements 表达式 {}

    public record 调用(表达式 接收者, String 名, List<表达式> 实参) implements 表达式 {}

    public record 字段访问(表达式 接收者, String 名) implements 表达式 {}

    public record 数组访问(表达式 数组, 表达式 下标) implements 表达式 {}

    public record 新建对象(类型引用 类型, List<表达式> 实参) implements 表达式 {}

    public record 新建数组(类型引用 元素类型, 表达式 长度, List<表达式> 初值, int 维数) implements 表达式 {}

    public record 数组初始化(List<表达式> 元素) implements 表达式 {}

    public record λ表达式(List<参数> 参数, 语句 体) implements 表达式 {}

    public record 方法引用(表达式 接收者, String 名, boolean 构造器) implements 表达式 {}

    public record 后缀自增(表达式 操作数, String 运算符, boolean 前缀) implements 表达式 {}

    public record 切换表达式(表达式 选择子, List<切换分支> 分支) implements 表达式 {}
}
