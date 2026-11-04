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

    /** System.err.println(Object) */
    public static void 错误(Object 值) {
        System.err.println(值);
    }

    /** 等价于 System.out.println(String.format(模板, 参数)) */
    public static void 格式(String 模板, Object... 参数) {
        System.out.println(String.format(模板, 参数));
    }
}
