import 中文.原生.中间.中间代码;
import 中文.原生.中间.类型;
import 中文.原生.后端C.C生成器;
import 中文.原生.后端LLVM.LLVM生成器;

import java.util.List;

/**
 * M19 后端层同名类单元测试（G1 根治能力唯一实证）。
 *
 * 绕过诊断层（G3 端到端仍禁止同名类），直接构造「演示.甲$工具」与
 * 「演示.乙$工具」两个同名类的 MIR 程序喂给 C/LLVM 生成器，断言：
 *   1) 两后端为两个类产出的符号 hex 串不同（全限定类名编码）；
 *   2) 符号里含各自包名编码（真正区分包，而非仅靠简单名）。
 *
 * 用法（在仓库根、已编译 classes 下）：
 *   java -cp 构建/原生/前端/classes 后端同名类测试
 */
public final class 后端同名类测试 {

    private static int 通过 = 0;
    private static int 失败 = 0;

    public static void main(String[] 参数) {
        中间代码.程序 程序 = 构造同名类程序();

        String c = C生成器.生成(程序);
        校验("C 后端", c, "_cjkobj_", "_cjkcls_");

        String llvm = LLVM生成器.生成(程序);
        校验("LLVM 后端", llvm, "%_cjkobj_", "_cjkcls_");

        System.out.println("后端同名类测试：通过 " + 通过 + " / 失败 " + 失败);
        if (失败 > 0) {
            System.exit(1);
        }
    }

    /** 构造两个不同包、同简单名（工具）的类，覆盖入口/静态字段/构造器/实例方法等符号位置。 */
    private static 中间代码.程序 构造同名类程序() {
        中间代码.方法 入口 = new 中间代码.方法("<入口>", true, 类型.无,
                List.of(), new 中间代码.块(List.of()));
        中间代码.方法 构造 = new 中间代码.方法("<构造>", false, 类型.无,
                List.of(), new 中间代码.块(List.of()));
        中间代码.方法 实例方法 = new 中间代码.方法("计算", false, 类型.整数,
                List.of(), new 中间代码.块(List.of(new 中间代码.返回语句(
                        new 中间代码.常量(类型.整数, "1")))));
        中间代码.静态字段定义 甲静态 = new 中间代码.静态字段定义(
                类型.整数, "甲计数", new 中间代码.常量(类型.整数, "1"));
        中间代码.静态字段定义 乙静态 = new 中间代码.静态字段定义(
                类型.整数, "乙计数", new 中间代码.常量(类型.整数, "2"));

        中间代码.类定义 甲工具 = new 中间代码.类定义(
                "演示.甲$工具",
                List.of(new 中间代码.字段定义(类型.整数, "甲标记")),
                List.of(构造), List.of(入口, 实例方法),
                null, List.of(), false, false, List.of(甲静态), List.of());
        中间代码.类定义 乙工具 = new 中间代码.类定义(
                "演示.乙$工具",
                List.of(new 中间代码.字段定义(类型.整数, "乙标记")),
                List.of(构造), List.of(实例方法),
                null, List.of(), false, false, List.of(乙静态), List.of());
        return new 中间代码.程序(List.of(甲工具, 乙工具));
    }

    /** 断言两同名类的结构体/描述符符号不同，且都含完整包名编码。 */
    private static void 校验(String 后端, String 产物, String 结构体前缀, String 描述符前缀) {
        String 甲名 = 全名编码("演示.甲$工具");
        String 乙名 = 全名编码("演示.乙$工具");

        // 1) 两个类的符号都必须出现（含各自包名 hex）
        boolean 甲出现 = 产物.contains(结构体前缀 + 甲名) && 产物.contains(描述符前缀 + 甲名);
        boolean 乙出现 = 产物.contains(结构体前缀 + 乙名) && 产物.contains(描述符前缀 + 乙名);
        断言(后端 + "：甲.工具 全名符号存在", 甲出现);
        断言(后端 + "：乙.工具 全名符号存在", 乙出现);

        // 2) 两个符号必须不同（简单名编码相同，全名编码不同 -> 根治）
        断言(后端 + "：同名类符号不同（根治）", !甲名.equals(乙名));

        // 2b) 每类全名编码在多个符号位置出现（构造/方法/静态字段/描述符等，覆盖 T2.2）
        int 甲次数 = 出现次数(产物, 甲名);
        int 乙次数 = 出现次数(产物, 乙名);
        断言(后端 + "：全名编码覆盖多符号位置（甲 " + 甲次数 + " 处 / 乙 " + 乙次数 + " 处）",
                甲次数 >= 3 && 乙次数 >= 3);

        // 3) 符号必须含包名编码（演示 = 6f14 793a，甲 = 7532 / 乙 = 4e59）
        boolean 甲含包 = 甲名.startsWith("6f14_793a_2e_") && 甲名.contains("_7532_");
        boolean 乙含包 = 乙名.startsWith("6f14_793a_2e_") && 乙名.contains("_4e59_");
        断言(后端 + "：符号含各自包名编码", 甲含包 && 乙含包);
    }

    /** 与后端 编码() 同算法：逐码点 hex + `_` 连接。 */
    private static String 全名编码(String 名) {
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

    private static int 出现次数(String 文本, String 子串) {
        int 次数 = 0;
        int 起 = 0;
        while ((起 = 文本.indexOf(子串, 起)) >= 0) {
            次数++;
            起 += 子串.length();
        }
        return 次数;
    }

    private static void 断言(String 名, boolean 条件) {
        if (条件) {
            通过++;
            System.out.println("  [通过] " + 名);
        } else {
            失败++;
            System.out.println("  [失败] " + 名);
        }
    }
}
