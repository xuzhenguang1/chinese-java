# M6 LLVM 后端 规格

## 目标

复用 MIR 直接生成 LLVM IR，用 `-O2` 链接既有 C 运行时，产出与 C 后端行为一致、
且带中文调试信息的 Windows x64 可执行文件。

## 范围

- 新增 `原生\后端LLVM`：MIR → `.ll`，覆盖教程 01–35 的全部语法与内建调用。
- 驱动新增 `原生 llvm 编译` 子命令；C 后端保持默认，行为不变。
- DWARF：函数、语句两级行号，文件名保持中文源路径。
- 测试：C/LLVM 双后端对拍、调试信息断言、性能基准。

## 不在范围

- 新语言特性；MIR 结构不变。
- 替换 C 后端或修改 `工具\cjc\Cjc.java`。
- Linux ELF / macOS Mach-O 产物（沿用 ADR-001）。
- 自研优化 pass；只使用 LLVM `-O2`。

## 关键口径

### ABI

| 项 | 实测值（clang 23.1.5 / x86_64-w64-windows-gnu） |
| --- | --- |
| `sizeof(cjk_value)` | 16，`u` 偏移 8 |
| `sizeof(jmp_buf)` | 256 |
| `sizeof(cjk_handler)` | 272，`prev` 偏移 256，`exception` 偏移 264 |
| `sizeof(cjk_class)` | 64，`to_string` 偏移 56 |
| setjmp 符号 | `_setjmp(ptr buf, ptr frameaddress)`，需 `returns_twice` |
| target triple | `x86_64-w64-windows-gnu` |

`cjk_value` 是 16 字节结构体，Win64 不会按值传参：clang 生成的 C ABI 是
**返回走 `sret` 隐藏指针、形参走引用**。IR 必须手动降级，否则第一个进入运行时的
调用就访问冲突：

```llvm
; 声明：void @f(ptr sret(%cjk_value) align 8, ptr align 8)
%槽 = alloca %cjk_value, align 8
call void @cjk_map_put(ptr sret(%cjk_value) align 8 %槽, ptr align 8 %接收, ptr align 8 %键, ptr align 8 %值)
%结果 = load %cjk_value, ptr %槽, align 8
```

形参侧由调用方 `alloca` + `store` 后传地址；`_cjkcb_*` 回调包装也用同一套
（`ptr sret(%cjk_value) align 8 %ret, ptr %self, ptr align 8 %arg1, ptr align 8 %arg2`）。

datalayout：

```
e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128
```

### 类型

- `整数/布尔`→`i32`，`长整数`→`i64`，`短整数/字符`→`i16`，`字节`→`i8`，
  `浮点/双浮点`→`float/double`，引用类型→`ptr`，`无`→`void`。
- 结构体类型：`%cjk_value = { i32, i64 }`、`%cjk_class = { i32, ptr, ptr, i32, ptr, ptr, i32, ptr }`、
  `%cjk_handler = { [32 x i64], ptr, ptr }`。
- 全局/局部符号名一律 ASCII；中文类名、方法名、变量名按 C 后端 `编码()` 规则转十六进制。
- 引用运行时的中文全局（如 `CJK_CLS_非法参数异常`）用 LLVM 引号标识符声明。

### 调试信息

- `DICompileUnit`（DW_LANG_C11、FullDebug）+ `DIFile`（中文文件名 + 目录）。
- 每函数 `DISubprogram`，每语句 `DILocation`，附着到 call/br/ret/store 等指令。
- 模块 flags：`Dwarf Version 4`、`Debug Info Version 3`。

### 异常

- 与 C 后端同构：`cjk_handler` 栈 + `_setjmp`/`longjmp`。
- 局部变量 alloca 固定在 entry 块；调用点标记 `returns_twice`。

## 验收

1. `原生 llvm 编译` 可产出 01–35 的 exe。
2. LLVM 后端 35/35 与 JVM 的 stdout、退出码逐字节一致；与 C 后端一致。
3. `objdump --dwarf=decodedline` 命中中文源文件名与真实行号。
4. 基准四项 LLVM 不慢于 C（≤1.05）。
5. `.\工具\发布\校验.ps1 -含原生` 退出码 0。
