package 中文.原生.后端LLVM;

import java.util.List;

/**
 * M14 LLVM 后端平台配置：把散落在生成器各处的平台相关常量集中到一处。
 *
 * <p>Windows 工厂逐字节复现 M0–M13 的既有值（PE/Win64/MinGW）；Linux 工厂的 ABI 数值
 * 必须来自 T1 交叉编译探针实测（见 文档/原生/M14/工具链与ABI探针报告.md），
 * 禁止照搬推测值——jmp_buf 布局写错会让异常捕获读到野指针。</p>
 */
public record LLVM平台配置(
        String 系统行,
        String 数据布局中段,
        String 处理器元素,            // %cjk_handler 里 jmp_buf 的 IR 元素类型，如 "[32 x i64]"
        int 处理器对齐,               // alloca %cjk_handler 的 align
        String setjmp函数名,          // "_setjmp" 或 "setjmp"
        List<String> setjmp参数类型,   // setjmp 声明的参数类型列表
        boolean 需要帧地址,            // 是否调用 llvm.frameaddress 取第二参
        boolean 值用引用传递           // true=Win64 sret/byref；false=SysV 寄存器按值
) {
    /** Windows PE/Win64/MinGW：M0–M13 既有值，逐字节复现。 */
    public static LLVM平台配置 windows() {
        return new LLVM平台配置(
                "x86_64-w64-windows-gnu",
                "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128",
                "[32 x i64]", 16,
                "_setjmp", List.of("ptr", "ptr"), true,
                true);
    }

    /**
     * Linux ELF/musl 1.2.5/SysV：全部数值来自 T1 交叉编译探针实测
     * （见 文档/原生/M14/工具链与ABI探针报告.md，禁止照搬推测）。
     * 关键实测：jmp_buf=200 字节 → [25 x i64]；setjmp 单参、无 llvm.frameaddress。
     */
    public static LLVM平台配置 linux() {
        return new LLVM平台配置(
                "x86_64-unknown-linux-musl",
                "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128",
                "[25 x i64]", 8,
                "setjmp", List.of("ptr"), false,
                false);
    }

    /** 是否 Windows 目标（供少数细分支使用；优先用具体字段判断）。 */
    public boolean 是Windows() {
        return 需要帧地址;
    }
}
