package 中文.标准库;

/**
 * 文本：String 常用操作的中文别名。
 */
public final class 文本 {

    private 文本() {
    }

    public static boolean 是否为空(String 文本) {
        return 文本 == null || 文本.isEmpty();
    }

    public static boolean 是否空白(String 文本) {
        return 文本 == null || 文本.isBlank();
    }

    public static String 去空白(String 文本) {
        return 文本 == null ? null : 文本.strip();
    }

    public static String[] 分割(String 文本, String 分隔) {
        return 文本.split(分隔);
    }

    public static String 重复(String 文本, int 次数) {
        return 文本.repeat(次数);
    }

    public static String 连接(String 分隔, Object... 值) {
        StringBuilder 构建器 = new StringBuilder();
        for (int i = 0; i < 值.length; i++) {
            if (i > 0) {
                构建器.append(分隔);
            }
            构建器.append(值[i]);
        }
        return 构建器.toString();
    }
}
