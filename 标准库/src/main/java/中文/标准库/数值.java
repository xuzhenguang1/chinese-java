package 中文.标准库;

/**
 * 数值：字符串与数值之间的转换别名。
 * 解析请直接用 `整数包装.解析` / `长整数包装.解析` / `双浮点包装.解析`。
 */
public final class 数值 {

    private 数值() {
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
