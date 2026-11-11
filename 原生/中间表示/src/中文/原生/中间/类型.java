package 中文.原生.中间;

import java.util.List;

/** M2 类型表示：基本类型、数组、类、空与错误占位；M5 增加泛型实参。 */
public final class 类型 {

    public enum 基本 {
        整数, 长整数, 短整数, 字节, 浮点, 双浮点, 字符, 布尔, 无, 文本
    }

    public enum 类别 {
        基本, 数组, 类, 空, 错误
    }

    public final 类别 种类;
    public final 基本 基本值;
    public final 类型 元素;
    public final String 类名;
    public final List<类型> 实参;

    private 类型(类别 种类, 基本 基本值, 类型 元素, String 类名, List<类型> 实参) {
        this.种类 = 种类;
        this.基本值 = 基本值;
        this.元素 = 元素;
        this.类名 = 类名;
        this.实参 = 实参;
    }

    public static final 类型 整数 = 基本类型(基本.整数);
    public static final 类型 长整数 = 基本类型(基本.长整数);
    public static final 类型 短整数 = 基本类型(基本.短整数);
    public static final 类型 字节 = 基本类型(基本.字节);
    public static final 类型 浮点 = 基本类型(基本.浮点);
    public static final 类型 双浮点 = 基本类型(基本.双浮点);
    public static final 类型 字符 = 基本类型(基本.字符);
    public static final 类型 布尔 = 基本类型(基本.布尔);
    public static final 类型 无 = 基本类型(基本.无);
    public static final 类型 文本 = 基本类型(基本.文本);
    public static final 类型 空 = new 类型(类别.空, null, null, null, List.of());
    public static final 类型 错误 = new 类型(类别.错误, null, null, null, List.of());

    public static 类型 基本类型(基本 值) {
        return new 类型(类别.基本, 值, null, null, List.of());
    }

    public static 类型 数组(类型 元素) {
        return new 类型(类别.数组, null, 元素, null, List.of());
    }

    public static 类型 类类型(String 名) {
        return 类类型(名, List.of());
    }

    public static 类型 类类型(String 名, List<类型> 实参) {
        return new 类型(类别.类, null, null, 名, 实参 == null ? List.of() : List.copyOf(实参));
    }

    public boolean 是基本() {
        return 种类 == 类别.基本;
    }

    public boolean 是整数族() {
        return 是基本() && (基本值 == 基本.整数 || 基本值 == 基本.长整数
                || 基本值 == 基本.短整数 || 基本值 == 基本.字节 || 基本值 == 基本.字符);
    }

    public boolean 是浮点族() {
        return 是基本() && (基本值 == 基本.浮点 || 基本值 == 基本.双浮点);
    }

    public boolean 是数值() {
        return 是整数族() || 是浮点族();
    }

    public boolean 是数组() {
        return 种类 == 类别.数组;
    }

    public boolean 是类() {
        return 种类 == 类别.类;
    }

    public boolean 是错误() {
        return 种类 == 类别.错误;
    }

    public boolean 是空() {
        return 种类 == 类别.空;
    }

    public String 文本() {
        return switch (种类) {
            case 基本 -> switch (基本值) {
                case 整数 -> "整数";
                case 长整数 -> "长整数";
                case 短整数 -> "短整数";
                case 字节 -> "字节";
                case 浮点 -> "浮点";
                case 双浮点 -> "双浮点";
                case 字符 -> "字符";
                case 布尔 -> "布尔";
                case 无 -> "无";
                case 文本 -> "文本";
            };
            case 数组 -> 元素.文本() + "[]";
            case 类 -> 实参.isEmpty() ? 类名
                    : 类名 + "<" + String.join(",", 实参.stream().map(类型::文本).toList()) + ">";
            case 空 -> "空";
            case 错误 -> "?";
        };
    }

    public boolean 同型(类型 另一) {
        if (另一 == null || 种类 != 另一.种类) {
            return false;
        }
        return switch (种类) {
            case 基本 -> 基本值 == 另一.基本值;
            case 数组 -> 元素.同型(另一.元素);
            case 类 -> 类名.equals(另一.类名) && 实参同型(另一.实参);
            case 空, 错误 -> true;
        };
    }

    private boolean 实参同型(List<类型> 另一) {
        if (实参.size() != 另一.size()) {
            return false;
        }
        for (int i = 0; i < 实参.size(); i++) {
            if (!实参.get(i).同型(另一.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 原始类名相同（泛型擦除后视为同一类）。 */
    public boolean 原始同类(类型 另一) {
        return 种类 == 类别.类 && 另一 != null && 另一.种类 == 类别.类
                && 类名.equals(另一.类名);
    }

    private int 秩() {
        if (!是基本()) {
            return 0;
        }
        return switch (基本值) {
            case 字节 -> 1;
            case 短整数 -> 2;
            case 字符 -> 2;
            case 整数 -> 3;
            case 长整数 -> 4;
            case 浮点 -> 5;
            case 双浮点 -> 6;
            default -> 0;
        };
    }

    public boolean 可赋值自(类型 源) {
        if (源 == null) {
            return false;
        }
        if (是错误() || 源.是错误()) {
            return true;
        }
        if (同型(源)) {
            return true;
        }
        if (种类 == 类别.空) {
            return 源.种类 == 类别.空;
        }
        if (源.种类 == 类别.空) {
            return 种类 == 类别.类 || 种类 == 类别.数组
                    || (种类 == 类别.基本 && 基本值 == 基本.文本);
        }
        if (是数值() && 源.是数值()) {
            if (源.基本值 == 基本.字符) {
                return 秩() >= 3;
            }
            if (源.基本值 == 基本.整数
                    && (基本值 == 基本.短整数 || 基本值 == 基本.字节 || 基本值 == 基本.字符)) {
                return true;
            }
            return 源.秩() <= 秩();
        }
        if (种类 == 类别.数组 && 源.种类 == 类别.数组) {
            return 元素.可赋值自(源.元素);
        }
        return 原始同类(源);
    }

    public 类型 提升(类型 另一) {
        if (另一 == null || !是数值() || !另一.是数值()) {
            return 错误;
        }
        if (基本值 == 基本.双浮点 || 另一.基本值 == 基本.双浮点) {
            return 双浮点;
        }
        if (基本值 == 基本.浮点 || 另一.基本值 == 基本.浮点) {
            return 浮点;
        }
        if (基本值 == 基本.长整数 || 另一.基本值 == 基本.长整数) {
            return 长整数;
        }
        return 整数;
    }

    @Override
    public boolean equals(Object 另一) {
        return 另一 instanceof 类型 类型 && 同型(类型);
    }

    @Override
    public int hashCode() {
        return 文本().hashCode();
    }

    @Override
    public String toString() {
        return 文本();
    }
}
