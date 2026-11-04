package 中文.标准库;

/**
 * 数学：java.lang.Math 常用函数的中文别名。
 */
public final class 数学 {

    public static final double 圆周率 = Math.PI;

    private 数学() {
    }

    public static int 绝对值(int 值) {
        return Math.abs(值);
    }

    public static long 绝对值(long 值) {
        return Math.abs(值);
    }

    public static double 绝对值(double 值) {
        return Math.abs(值);
    }

    public static int 最大(int 甲, int 乙) {
        return Math.max(甲, 乙);
    }

    public static long 最大(long 甲, long 乙) {
        return Math.max(甲, 乙);
    }

    public static double 最大(double 甲, double 乙) {
        return Math.max(甲, 乙);
    }

    public static int 最小(int 甲, int 乙) {
        return Math.min(甲, 乙);
    }

    public static double 最小(double 甲, double 乙) {
        return Math.min(甲, 乙);
    }

    public static double 平方根(double 值) {
        return Math.sqrt(值);
    }

    public static double 幂(double 底, double 指数) {
        return Math.pow(底, 指数);
    }

    /** Math.round，返回长整数。 */
    public static long 取整(double 值) {
        return Math.round(值);
    }

    public static double 随机() {
        return Math.random();
    }

    /** 返回 [0, 上界) 的随机整数。 */
    public static int 随机整数(int 上界) {
        return (int) (Math.random() * 上界);
    }
}
