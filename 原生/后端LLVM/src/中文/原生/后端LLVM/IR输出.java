package 中文.原生.后端LLVM;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M6：LLVM IR 文本拼装。
 *
 * 负责分段累积类型、全局、声明、函数与元数据，统一处理字符串常量去重、
 * UTF-8 字节转义与调试元数据编号，保证同一输入生成逐字节一致的 .ll。
 */
public final class IR输出 {

    private final StringBuilder 类型段 = new StringBuilder();
    private final StringBuilder 全局段 = new StringBuilder();
    private final StringBuilder 声明段 = new StringBuilder();
    private final StringBuilder 函数段 = new StringBuilder();
    private final StringBuilder 元数据段 = new StringBuilder();
    private final Map<String, String> 字符串表 = new LinkedHashMap<>();
    private final Map<String, String> 已声明函数 = new LinkedHashMap<>();
    private int 下一字符串;
    private int 下一元数据;
    private String 文件元数据;
    private String 编译单元元数据;
    private String 子程序类型元数据;

    /** 建立 DIFile / DICompileUnit / DISubroutineType，供各函数复用。 */
    public void 初始化调试信息(String 文件名, String 目录) {
        String 文件 = 元数据("!DIFile(filename: \"" + 转义元数据(文件名)
                + "\", directory: \"" + 转义元数据(目录) + "\")");
        String 类型 = 元数据("!DISubroutineType(types: !{null})");
        String 单元 = 元数据("distinct !DICompileUnit(language: DW_LANG_C11, file: "
                + 文件 + ", producer: \"cjc LLVM 后端\", isOptimized: true,"
                + " runtimeVersion: 0, emissionKind: FullDebug)");
        this.文件元数据 = 文件;
        this.子程序类型元数据 = 类型;
        this.编译单元元数据 = 单元;
    }

    public String 文件元数据() {
        return 文件元数据;
    }

    public String 编译单元元数据() {
        return 编译单元元数据;
    }

    public String 子程序类型元数据() {
        return 子程序类型元数据;
    }

    /** 内容 → `@.str.N`，同内容只生成一次；返回全局名。 */
    public String 新字符串(String 内容) {
        String 已有 = 字符串表.get(内容);
        if (已有 != null) {
            return 已有;
        }
        byte[] 字节 = 内容.getBytes(StandardCharsets.UTF_8);
        String 名 = "@.str." + (下一字符串++);
        StringBuilder 文 = new StringBuilder(名);
        文.append(" = private unnamed_addr constant [").append(字节.length + 1)
                .append(" x i8] c\"");
        for (byte 值 : 字节) {
            文.append(转义字节(值 & 0xFF));
        }
        文.append("\\00\"\n");
        字符串表.put(内容, 名);
        全局段.append(文);
        return 名;
    }

    public void 类型定义(String 文本) {
        类型段.append(文本).append('\n');
    }

    public void 声明全局(String 文本) {
        全局段.append(文本).append('\n');
    }

    public void 声明(String 返回类型, String 函数名, List<String> 参数类型) {
        声明(返回类型, 函数名, 参数类型, "");
    }

    /** 声明外部函数；后缀用于 `returns_twice` 等属性。 */
    public void 声明(String 返回类型, String 函数名, List<String> 参数类型, String 后缀) {
        if (已声明函数.containsKey(函数名)) {
            return;
        }
        已声明函数.put(函数名, 返回类型);
        StringBuilder 文 = new StringBuilder("declare ").append(返回类型)
                .append(" @").append(函数名).append('(');
        if (参数类型.isEmpty()) {
            文.append(')');
        } else {
            文.append(String.join(", ", 参数类型)).append(')');
        }
        if (!后缀.isEmpty()) {
            文.append(' ').append(后缀);
        }
        声明段.append(文).append('\n');
    }

    public void 函数(String 文本) {
        函数段.append(文本);
    }

    /** 新建元数据节点，返回 `!N`。 */
    public String 元数据(String 文本) {
        int 编号 = 下一元数据++;
        元数据段.append('!').append(编号).append(" = ").append(文本).append('\n');
        return "!" + 编号;
    }

    /** 组装完整模块。 */
    public String 组装(String 模块头) {
        StringBuilder 文 = new StringBuilder(模块头);
        文.append(类型段);
        文.append(全局段);
        文.append(声明段);
        文.append(函数段);
        文.append('\n');
        文.append("!llvm.dbg.cu = !{").append(编译单元元数据).append("}\n");
        String 版本 = 元数据("!{i32 7, !\"Dwarf Version\", i32 4}");
        String 调试 = 元数据("!{i32 2, !\"Debug Info Version\", i32 3}");
        文.append("!llvm.module.flags = !{").append(版本).append(", ").append(调试).append("}\n");
        文.append(元数据段);
        return 文.toString();
    }

    private static String 转义字节(int 值) {
        if (值 == '"' ) {
            return "\\22";
        }
        if (值 == '\\') {
            return "\\5C";
        }
        if (值 >= 0x20 && 值 < 0x7F) {
            return String.valueOf((char) 值);
        }
        return String.format("\\%02X", 值);
    }

    static String 转义元数据(String 文本) {
        if (文本 == null) {
            return "";
        }
        StringBuilder 文 = new StringBuilder(文本.length());
        for (int i = 0; i < 文本.length(); i++) {
            char 字符 = 文本.charAt(i);
            if (字符 == '"' || 字符 == '\\') {
                文.append('\\').append(字符);
            } else {
                文.append(字符);
            }
        }
        return 文.toString();
    }
}
