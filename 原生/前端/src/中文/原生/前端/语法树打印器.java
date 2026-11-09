package 中文.原生.前端;

import 中文.原生.前端.语法树.*;

import java.util.List;
import java.util.StringJoiner;

/** 确定性 AST 文本打印：只含结构，不含路径、时间与行号。 */
public final class 语法树打印器 {

    private final 词表 词表;
    private final StringBuilder 输出 = new StringBuilder();

    public 语法树打印器(词表 词表) {
        this.词表 = 词表;
    }

    public String 打印(编译单元 单元) {
        输出.setLength(0);
        行(0, "编译单元");
        for (导入声明 项 : 单元.导入()) {
            行(1, "导入 " + 项.名称() + (项.通配() ? ".*" : ""));
        }
        for (类型声明 项 : 单元.类型()) {
            打印节点(项, 1);
        }
        for (语句 项 : 单元.语句()) {
            打印节点(项, 1);
        }
        return 输出.toString();
    }

    public String 打印(节点 节点) {
        输出.setLength(0);
        打印节点(节点, 0);
        return 输出.toString();
    }

    private void 打印节点(节点 节点, int 缩进) {
        if (节点 instanceof 类型声明 x) {
            打印类型(x, 缩进);
        } else if (节点 instanceof 字段声明 x) {
            打印字段(x, 缩进);
        } else if (节点 instanceof 方法声明 x) {
            打印方法(x, 缩进);
        } else if (节点 instanceof 构造器声明 x) {
            打印构造器(x, 缩进);
        } else if (节点 instanceof 入口声明 x) {
            行(缩进, "入口");
            打印抛出(x.抛出(), 缩进 + 1);
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 空成员) {
            行(缩进, "空成员");
        } else if (节点 instanceof 枚举常量 x) {
            行(缩进, "枚举常量 " + x.名());
            for (表达式 实参 : x.实参()) {
                打印节点(实参, 缩进 + 1);
            }
        } else if (节点 instanceof 块 x) {
            行(缩进, "块");
            for (语句 语句 : x.语句()) {
                打印节点(语句, 缩进 + 1);
            }
        } else if (节点 instanceof 若语句 x) {
            行(缩进, "若");
            行(缩进 + 1, "条件");
            打印节点(x.条件(), 缩进 + 2);
            行(缩进 + 1, "成立");
            打印节点(x.成立(), 缩进 + 2);
            if (x.否则() != null) {
                行(缩进 + 1, "否则");
                打印节点(x.否则(), 缩进 + 2);
            }
        } else if (节点 instanceof 对于语句 x) {
            行(缩进, "对于");
            if (x.初始化() != null) {
                行(缩进 + 1, "初始化");
                打印节点(x.初始化(), 缩进 + 2);
            }
            if (x.条件() != null) {
                行(缩进 + 1, "条件");
                打印节点(x.条件(), 缩进 + 2);
            }
            for (表达式 更新 : x.更新()) {
                行(缩进 + 1, "更新");
                打印节点(更新, 缩进 + 2);
            }
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 增强对于 x) {
            行(缩进, "增强对于");
            行(缩进 + 1, "变量 " + 类型文本(x.变量().类型()) + " " + x.变量().名());
            行(缩进 + 1, "可迭代");
            打印节点(x.可迭代(), 缩进 + 2);
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 当语句 x) {
            行(缩进, "当");
            打印节点(x.条件(), 缩进 + 1);
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 做当语句 x) {
            行(缩进, "做当");
            打印节点(x.体(), 缩进 + 1);
            行(缩进 + 1, "条件");
            打印节点(x.条件(), 缩进 + 2);
        } else if (节点 instanceof 重复语句 x) {
            行(缩进, "重复 " + x.变量名());
            行(缩进 + 1, "次数");
            打印节点(x.次数(), 缩进 + 2);
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 切换语句 x) {
            行(缩进, "切换");
            行(缩进 + 1, "选择子");
            打印节点(x.选择子(), 缩进 + 2);
            打印分支(x.分支(), 缩进 + 1);
        } else if (节点 instanceof 尝试语句 x) {
            行(缩进, "尝试");
            打印节点(x.尝试块(), 缩进 + 1);
            for (捕获子句 捕获 : x.捕获()) {
                行(缩进 + 1, "捕获 " + 类型文本(捕获.类型()) + " " + 捕获.名());
                打印节点(捕获.块(), 缩进 + 2);
            }
            if (x.兜底() != null) {
                行(缩进 + 1, "兜底");
                打印节点(x.兜底(), 缩进 + 2);
            }
        } else if (节点 instanceof 返回语句 x) {
            行(缩进, "返回");
            if (x.值() != null) {
                打印节点(x.值(), 缩进 + 1);
            }
        } else if (节点 instanceof 抛出语句 x) {
            行(缩进, "抛出");
            打印节点(x.值(), 缩进 + 1);
        } else if (节点 instanceof 产出语句 x) {
            行(缩进, "产出");
            打印节点(x.值(), 缩进 + 1);
        } else if (节点 instanceof 跳出语句 x) {
            行(缩进, "跳出" + (x.标签() == null ? "" : " " + x.标签()));
        } else if (节点 instanceof 继续语句 x) {
            行(缩进, "继续" + (x.标签() == null ? "" : " " + x.标签()));
        } else if (节点 instanceof 局部变量语句 x) {
            行(缩进, "局部变量 " + 类型文本(x.声明().类型()) + " " + x.声明().名());
            if (x.声明().初值() != null) {
                打印节点(x.声明().初值(), 缩进 + 1);
            }
        } else if (节点 instanceof 表达式语句 x) {
            行(缩进, "表达式语句");
            打印节点(x.值(), 缩进 + 1);
        } else if (节点 instanceof 空语句) {
            行(缩进, "空语句");
        } else if (节点 instanceof 切换分支 x) {
            打印单个分支(x, 缩进);
        } else if (节点 instanceof 捕获子句 x) {
            行(缩进, "捕获 " + 类型文本(x.类型()) + " " + x.名());
            打印节点(x.块(), 缩进 + 1);
        } else if (节点 instanceof 字面量 x) {
            行(缩进, "字面量 " + x.原文());
        } else if (节点 instanceof 标识符 x) {
            行(缩进, "标识符 " + x.名());
        } else if (节点 instanceof 二元 x) {
            行(缩进, "二元 " + x.运算符());
            打印节点(x.左(), 缩进 + 1);
            打印节点(x.右(), 缩进 + 1);
        } else if (节点 instanceof 一元 x) {
            行(缩进, "一元 " + x.运算符());
            打印节点(x.操作数(), 缩进 + 1);
        } else if (节点 instanceof 赋值表达式 x) {
            行(缩进, "赋值 " + x.运算符());
            打印节点(x.左(), 缩进 + 1);
            打印节点(x.右(), 缩进 + 1);
        } else if (节点 instanceof 三目表达式 x) {
            行(缩进, "三目");
            打印节点(x.条件(), 缩进 + 1);
            打印节点(x.真(), 缩进 + 1);
            打印节点(x.假(), 缩进 + 1);
        } else if (节点 instanceof 强制转换 x) {
            行(缩进, "强制转换 " + 类型文本(x.类型()));
            打印节点(x.操作数(), 缩进 + 1);
        } else if (节点 instanceof 实例判断 x) {
            行(缩进, "实例判断 " + 类型文本(x.类型())
                    + (x.绑定名() == null ? "" : " " + x.绑定名()));
            打印节点(x.左(), 缩进 + 1);
        } else if (节点 instanceof 调用 x) {
            行(缩进, "调用" + (x.名() == null ? "" : " " + x.名()));
            if (x.接收者() != null) {
                打印节点(x.接收者(), 缩进 + 1);
            }
            for (表达式 实参 : x.实参()) {
                打印节点(实参, 缩进 + 1);
            }
        } else if (节点 instanceof 字段访问 x) {
            行(缩进, "字段访问 ." + x.名());
            打印节点(x.接收者(), 缩进 + 1);
        } else if (节点 instanceof 数组访问 x) {
            行(缩进, "数组访问");
            打印节点(x.数组(), 缩进 + 1);
            打印节点(x.下标(), 缩进 + 1);
        } else if (节点 instanceof 新建对象 x) {
            行(缩进, "新建 " + 类型文本(x.类型()));
            for (表达式 实参 : x.实参()) {
                打印节点(实参, 缩进 + 1);
            }
        } else if (节点 instanceof 新建数组 x) {
            行(缩进, "新建数组 " + 类型文本(x.元素类型()));
            if (x.长度() != null) {
                行(缩进 + 1, "长度");
                打印节点(x.长度(), 缩进 + 2);
            }
            for (表达式 初值 : x.初值()) {
                打印节点(初值, 缩进 + 1);
            }
        } else if (节点 instanceof 数组初始化 x) {
            行(缩进, "数组初始化");
            for (表达式 元素 : x.元素()) {
                打印节点(元素, 缩进 + 1);
            }
        } else if (节点 instanceof λ表达式 x) {
            行(缩进, "λ");
            for (参数 参数 : x.参数()) {
                行(缩进 + 1, "参数 " + (参数.类型() == null ? "" : 类型文本(参数.类型()) + " ") + 参数.名());
            }
            打印节点(x.体(), 缩进 + 1);
        } else if (节点 instanceof 方法引用 x) {
            行(缩进, "方法引用 ::" + x.名());
            打印节点(x.接收者(), 缩进 + 1);
        } else if (节点 instanceof 后缀自增 x) {
            行(缩进, (x.前缀() ? "前缀 " : "后缀 ") + x.运算符());
            打印节点(x.操作数(), 缩进 + 1);
        } else if (节点 instanceof 切换表达式 x) {
            行(缩进, "切换表达式");
            行(缩进 + 1, "选择子");
            打印节点(x.选择子(), 缩进 + 2);
            打印分支(x.分支(), 缩进 + 1);
        } else {
            行(缩进, "未知节点 " + 节点.getClass().getSimpleName());
        }
    }

    private void 打印类型(类型声明 x, int 缩进) {
        StringBuilder 行 = new StringBuilder("类型 ");
        加修饰(行, x.修饰符());
        行.append(x.种类()).append(' ').append(x.名());
        行.append(类型参数文本(x.类型参数()));
        行(缩进, 行.toString().trim());
        if (x.父类() != null) {
            行(缩进 + 1, "继承 " + 类型文本(x.父类()));
        }
        if (!x.接口().isEmpty()) {
            行(缩进 + 1, "实现 " + 类型列表文本(x.接口()));
        }
        if (!x.许可().isEmpty()) {
            行(缩进 + 1, "许可 " + 类型列表文本(x.许可()));
        }
        for (参数 分量 : x.分量()) {
            行(缩进 + 1, "分量 " + 类型文本(分量.类型()) + " " + 分量.名());
        }
        for (枚举常量 常量 : x.枚举常量()) {
            打印节点(常量, 缩进 + 1);
        }
        for (成员 成员 : x.成员()) {
            打印节点(成员, 缩进 + 1);
        }
    }

    private void 打印字段(字段声明 x, int 缩进) {
        for (变量声明 变量 : x.变量()) {
            StringBuilder 行 = new StringBuilder("字段 ");
            加修饰(行, x.修饰符());
            行.append(类型文本(x.类型())).append(' ').append(变量.名());
            行(缩进, 行.toString().trim());
            if (变量.初值() != null) {
                打印节点(变量.初值(), 缩进 + 1);
            }
        }
    }

    private void 打印方法(方法声明 x, int 缩进) {
        StringBuilder 行 = new StringBuilder("方法 ");
        加修饰(行, x.修饰符());
        行.append(类型参数文本(x.类型参数()));
        if (行.charAt(行.length() - 1) != ' ') {
            行.append(' ');
        }
        行.append(类型文本(x.返回类型())).append(' ').append(x.名());
        行(缩进, 行.toString().trim());
        for (参数 参数 : x.参数()) {
            行(缩进 + 1, "参数 " + 类型文本(参数.类型()) + (参数.可变参数() ? "..." : "") + " " + 参数.名());
        }
        打印抛出(x.抛出(), 缩进 + 1);
        if (x.体() != null) {
            打印节点(x.体(), 缩进 + 1);
        }
    }

    private void 打印构造器(构造器声明 x, int 缩进) {
        StringBuilder 行 = new StringBuilder("构造器 ");
        加修饰(行, x.修饰符());
        行.append(x.名());
        行(缩进, 行.toString().trim());
        for (参数 参数 : x.参数()) {
            行(缩进 + 1, "参数 " + 类型文本(参数.类型()) + " " + 参数.名());
        }
        打印抛出(x.抛出(), 缩进 + 1);
        打印节点(x.体(), 缩进 + 1);
    }

    private void 打印抛出(List<类型引用> 抛出, int 缩进) {
        if (!抛出.isEmpty()) {
            行(缩进, "声明抛出 " + 类型列表文本(抛出));
        }
    }

    private void 打印分支(List<切换分支> 分支, int 缩进) {
        for (切换分支 项 : 分支) {
            打印单个分支(项, 缩进);
        }
    }

    private void 打印单个分支(切换分支 x, int 缩进) {
        if (x.是默认()) {
            行(缩进, "默认");
        } else {
            StringJoiner 连接 = new StringJoiner(", ");
            for (表达式 情形 : x.情形()) {
                连接.add(表达式文本(情形));
            }
            行(缩进, "情形 " + 连接);
        }
        打印节点(x.结果(), 缩进 + 1);
    }

    private String 表达式文本(表达式 表达式) {
        if (表达式 instanceof 字面量 x) {
            return x.原文();
        }
        if (表达式 instanceof 标识符 x) {
            return x.名();
        }
        return 表达式.getClass().getSimpleName();
    }

    private void 加修饰(StringBuilder 行, List<String> 修饰符) {
        for (String 修饰 : 修饰符) {
            行.append(修饰.startsWith("@") ? 修饰 : 词表.中文修饰符(修饰)).append(' ');
        }
    }

    private String 类型参数文本(List<类型参数> 参数) {
        if (参数.isEmpty()) {
            return "";
        }
        StringJoiner 连接 = new StringJoiner(", ", "<", ">");
        for (类型参数 项 : 参数) {
            连接.add(项.名() + (项.上界() == null ? "" : " 继承 " + 类型文本(项.上界())));
        }
        return 连接.toString();
    }

    private String 类型列表文本(List<类型引用> 列表) {
        StringJoiner 连接 = new StringJoiner(", ");
        for (类型引用 项 : 列表) {
            连接.add(类型文本(项));
        }
        return 连接.toString();
    }

    private String 类型文本(类型引用 类型) {
        if (类型 == null) {
            return "?";
        }
        StringBuilder 文本 = new StringBuilder(类型.名());
        if (!类型.实参().isEmpty()) {
            文本.append('<');
            StringJoiner 连接 = new StringJoiner(", ");
            for (类型引用 实参 : 类型.实参()) {
                连接.add(类型文本(实参));
            }
            文本.append(连接).append('>');
        }
        文本.append("[]".repeat(类型.数组维数()));
        return 文本.toString();
    }

    private void 行(int 缩进, String 文本) {
        输出.append("  ".repeat(缩进)).append(文本).append('\n');
    }
}
