package 中文.标准库;

/**
 * 数值：字符串与数值之间的转换别名。
 */
public final class 数值 {

    private 数值() {
    }

    public static int 转整数(String 文本) {
        return Integer.parseInt(文本.strip());
    }

    public static long 转长整数(String 文本) {
        return Long.parseLong(文本.strip());
    }

    public static double 转双浮点(String 文本) {
        return Double.parseDouble(文本.strip());
    }

    public static String 转文本(Object 值) {
        return String.valueOf(值);
    }

    public static boolean 是整数(String 文本) {
        if (文本 == null) {
            return false;
        }
        try {
            Integer.parseInt(文本.strip());
            return true;
        } catch (NumberFormatException 异常) {
            return false;
        }
    }
}
