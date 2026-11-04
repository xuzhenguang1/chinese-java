package 中文.标准库;

import java.lang.reflect.Array;
import java.util.Map;

/**
 * 数据：JSON 编码（只编码，不解析）。
 * 支持 null、字符串、字符、布尔、数字、Map、Iterable 与数组；未知类型按字符串输出。
 * 该实现刻意保持无第三方依赖，适合日志、调试与简单接口拼装。
 */
public final class 数据 {

    private 数据() {
    }

    /** 紧凑 JSON。 */
    public static String 转JSON(Object 值) {
        return 编码(值, false, 0);
    }

    /** 美化 JSON（两空格缩进，便于阅读与调试）。 */
    public static String 转JSON(Object 值, boolean 美化) {
        return 编码(值, 美化, 0);
    }

    private static String 编码(Object 值, boolean 美化, int 层级) {
        if (值 == null) {
            return "null";
        }
        if (值 instanceof String 文本值) {
            return 引号(文本值);
        }
        if (值 instanceof Character 原字符) {
            return 引号(String.valueOf(原字符));
        }
        if (值 instanceof Boolean) {
            return 值.toString();
        }
        if (值 instanceof Number 数字) {
            return 数字文本(数字);
        }
        if (值 instanceof Map<?, ?> 表) {
            return 编码映射(表, 美化, 层级);
        }
        if (值 instanceof Iterable<?> 可迭代) {
            return 编码数组(可迭代, 美化, 层级);
        }
        if (值.getClass().isArray()) {
            return 编码原生数组(值, 美化, 层级);
        }
        return 引号(String.valueOf(值));
    }

    private static String 编码映射(Map<?, ?> 表, boolean 美化, int 层级) {
        if (表.isEmpty()) {
            return "{}";
        }
        StringBuilder 构建 = new StringBuilder("{");
        boolean 首个 = true;
        for (Map.Entry<?, ?> 条目 : 表.entrySet()) {
            if (!首个) {
                构建.append(',');
            }
            首个 = false;
            if (美化) {
                构建.append('\n').append(缩进(层级 + 1));
            }
            构建.append(引号(String.valueOf(条目.getKey())));
            构建.append(美化 ? ": " : ":");
            构建.append(编码(条目.getValue(), 美化, 层级 + 1));
        }
        if (美化) {
            构建.append('\n').append(缩进(层级));
        }
        return 构建.append('}').toString();
    }

    private static String 编码数组(Iterable<?> 可迭代, boolean 美化, int 层级) {
        StringBuilder 构建 = new StringBuilder("[");
        boolean 首个 = true;
        for (Object 项 : 可迭代) {
            if (!首个) {
                构建.append(',');
            }
            首个 = false;
            if (美化) {
                构建.append('\n').append(缩进(层级 + 1));
            }
            构建.append(编码(项, 美化, 层级 + 1));
        }
        if (美化 && !首个) {
            构建.append('\n').append(缩进(层级));
        }
        return 构建.append(']').toString();
    }

    private static String 编码原生数组(Object 数组, boolean 美化, int 层级) {
        int 长度 = Array.getLength(数组);
        StringBuilder 构建 = new StringBuilder("[");
        for (int 下标 = 0; 下标 < 长度; 下标++) {
            if (下标 > 0) {
                构建.append(',');
            }
            if (美化) {
                构建.append('\n').append(缩进(层级 + 1));
            }
            构建.append(编码(Array.get(数组, 下标), 美化, 层级 + 1));
        }
        if (美化 && 长度 > 0) {
            构建.append('\n').append(缩进(层级));
        }
        return 构建.append(']').toString();
    }

    private static String 数字文本(Number 数字) {
        if (数字 instanceof Double || 数字 instanceof Float) {
            double 双精度 = 数字.doubleValue();
            if (Double.isNaN(双精度) || Double.isInfinite(双精度)) {
                return "null";
            }
        }
        return 数字.toString();
    }

    private static String 引号(String 内容) {
        StringBuilder 构建 = new StringBuilder(内容.length() + 2).append('"');
        for (int 下标 = 0; 下标 < 内容.length(); 下标++) {
            char 原字符 = 内容.charAt(下标);
            switch (原字符) {
                case '"' -> 构建.append("\\\"");
                case '\\' -> 构建.append("\\\\");
                case '\b' -> 构建.append("\\b");
                case '\f' -> 构建.append("\\f");
                case '\n' -> 构建.append("\\n");
                case '\r' -> 构建.append("\\r");
                case '\t' -> 构建.append("\\t");
                default -> {
                    if (原字符 < 0x20) {
                        构建.append(String.format("\\u%04x", (int) 原字符));
                    } else {
                        构建.append(原字符);
                    }
                }
            }
        }
        return 构建.append('"').toString();
    }

    private static String 缩进(int 层级) {
        return "  ".repeat(Math.max(0, 层级));
    }
}
