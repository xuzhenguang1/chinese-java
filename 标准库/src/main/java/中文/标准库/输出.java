package 中文.标准库;

/**
 * 控制台输出的中文别名。
 * 只包装 System.out / System.err，不改变任何行为。
 */
public final class 输出 {

    private 输出() {
    }

    /** System.out.print */
    public static void 打印(Object 值) {
        System.out.print(值);
    }

    /** System.out.println() */
    public static void 打印行() {
        System.out.println();
    }

    /** System.out.println(Object) */
    public static void 打印行(Object 值) {
        System.out.println(值);
    }

    /** 多实参依次拼接后输出，与语法糖 `打印("标签：", 值)` 一致 */
    public static void 打印(Object... 部分) {
        System.out.print(拼接(部分));
    }

    /** 多实参依次拼接后换行输出，与语法糖 `打印行("标签：", 值)` 一致 */
    public static void 打印行(Object... 部分) {
        System.out.println(拼接(部分));
    }

    /** System.err.println(Object) */
    public static void 错误(Object 值) {
        System.err.println(值);
    }

    /** 多实参依次拼接后写入标准错误 */
    public static void 错误(Object... 部分) {
        System.err.println(拼接(部分));
    }

    /** 等价于 System.out.println(String.format(模板, 参数)) */
    public static void 格式(String 模板, Object... 参数) {
        System.out.println(String.format(模板, 参数));
    }

    /** 依次拼接所有部分，null 输出为 "null"，与字符串 `+` 行为一致 */
    private static String 拼接(Object[] 部分) {
        StringBuilder 构建器 = new StringBuilder();
        for (Object 项 : 部分) {
            构建器.append(项);
        }
        return 构建器.toString();
    }
}
