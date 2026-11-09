package 中文.标准库;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 数值：字符串与数值之间的转换别名。
 * 解析请直接用 `整数.解析` / `长整数.解析` / `双浮点.解析`。
 * 随机整数的结果范围含两端。
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

    /** 返回 1..上限 之间的随机整数，含两端。 */
    public static int 随机整数(int 上限) {
        if (上限 < 1) {
            throw new IllegalArgumentException("上限必须大于等于 1：" + 上限);
        }
        return 随机整数(1, 上限);
    }

    /** 返回 下限..上限 之间的随机整数，含两端。 */
    public static int 随机整数(int 下限, int 上限) {
        if (下限 > 上限) {
            throw new IllegalArgumentException("下限不能大于上限：" + 下限 + " > " + 上限);
        }
        return (int) ThreadLocalRandom.current().nextLong(下限, (long) 上限 + 1);
    }
}
