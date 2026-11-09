package 中文.原生.前端;

import java.util.List;

/** 词法单元：原文、规范名与源码位置。 */
public final class 标记 {

    public enum 标记类别 {
        关键字, 标识符, 数字, 文本, 字符, 符号, 结束
    }

    public final 标记类别 类别;
    public final int 起;
    public final int 止;
    public final String 原文;
    public final String 规范;
    public final int 行;
    public final int 列;

    public 标记(标记类别 类别, int 起, int 止, String 原文, String 规范, int 行, int 列) {
        this.类别 = 类别;
        this.起 = 起;
        this.止 = 止;
        this.原文 = 原文;
        this.规范 = 规范;
        this.行 = 行;
        this.列 = 列;
    }

    public boolean 是关键字(String 英文) {
        return 类别 == 标记类别.关键字 && 英文.equals(规范);
    }

    public boolean 是符号(String 文本) {
        return 类别 == 标记类别.符号 && 文本.equals(原文);
    }

    public boolean 是标识符(String 文本) {
        return 类别 == 标记类别.标识符 && 文本.equals(原文);
    }

    @Override
    public String toString() {
        return 类别 + "(" + 原文 + ")";
    }

    /** 一个源文件及其词法结果。 */
    public record 源文件(String 名称, String 路径, String 源码, List<标记> 标记) {}
}
