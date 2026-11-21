# M14：Linux ELF 交叉编译（可运行 + 全量对拍）

> 状态：方案待评审。方向来自用户 2026-11-20 三项决策：① 一步到位做可运行 ELF（不做分阶段）；② 联网下载 musl 静态交叉工具链 + qemu-user；③ 文件 IO 与 socket 网络全做（35 课 + 网络冒烟纳入验收）。
> 前置：M13（ADR-017 编号重排：原 M13 ELF → M14）、平台抽象层与多文件模块已落地，全量校验退出码 0。

## 1. 目标

在 Windows 宿主机上，用 **musl 静态交叉工具链**把中文 Java 源码编译为**可运行的 x86_64 Linux ELF**，并用 **qemu-user** 在本机完成与 JVM 的逐字节对拍，验收口径与 Windows PE 完全一致（35 课 C/LLVM 双后端 + 多文件模块 + 异常栈 + GC + 自举不动点 + DWARF + 基准）。

### 非目标

- 不交叉产 Mach-O / macOS 产物；不改 Windows PE 的任何默认行为（默认仍 PE/Win64）。
- 不引入包管理（M15）或跨文件自举不动点（另立 ADR）。
- 不改 35 课源文件、AST/MIR/语义快照、自举不动点文本基线、C/LLVM 符号编码 `编码()`。
- 不把 glibc 作为目标 libc（选 musl，理由见 §3.1）。

## 2. 现状核查（2026-11-20 实测）

### 2.1 本机能力——比 M13 报告更乐观，但缺口明确

| 能力 | 实测结果 | 证据 |
| --- | --- | --- |
| clang 交叉编译 | clang 23.1.5（MSYS2 MinGW），`--target=x86_64-linux-gnu -c -x c /dev/null` **成功产出 ELF64 对象**（ELF 头 `7f 45 4c 46 02`，744 字节） | 本次实测 |
| lld 跨目标链接 | `ld.lld.exe` 存在；`-nostdlib -static --target=x86_64-linux-gnu` 能链出 ELF64 可执行文件（仅缺 `_start` 警告） | 本次实测 |
| Linux sysroot / libc / crt1.o | **无**。M13 报告 8 个运行时 `.c` 全部卡在预处理（`setjmp.h`/`wchar.h` 缺失） | `文档/原生/M13/平台配置与静态检查.md:21-40` |
| 交叉 gcc / qemu / musl 包 | pacman 本地库查询均无；系统无 `x86_64-linux-gnu-gcc`、`qemu-x86_64`、`zig` | 本次实测 |
| WSL | 被安全策略禁用（M3 记录 `wsl --status` 退出码 50） | ADR-017:33 |

**结论**：clang + lld 已具备 ELF codegen/link 能力，M14 只需补齐「libc/sysroot + 运行器 + 代码层平台化」，不需要从零搭工具链。

### 2.1.1 实施期发现：Windows 无 qemu-user（2026-11-20）

方案原假设"下载 Windows 版 qemu-user（`qemu-x86_64.exe`）在本机直接跑 ELF"经实测**不成立**：

- 官方 Stefan Weil Windows 安装包（qemu-w64-setup 11.1.0，已下载并校验 SHA512 一致）只含 `qemu-system-*` 全系列 system emulator，**不含任何 linux-user 模式的 `qemu-*-exe`**（实测安装目录 65 个 exe，无 `qemu-x86_64.exe`）。MSYS2 的 `mingw-w64-*-qemu` 包同源，同样只有 system emulator。
- WSL 被工具层安全策略硬性禁止（`wsl.exe` 命中 Program Blacklist，`--status`/`-l` 均拒绝执行），不可绕过。
- 本机无 Wine、无现成 Linux 内核镜像。

**已验证的替代路径**：用已装好的 `qemu-system-x86_64.exe` + Alpine `linux-virt` 内核（v3.20 `linux-virt-6.6.142-r0.apk`，约 10MB）+ `alpine-minirootfs`（3.5MB）构造最小 Linux 启动环境，通过 `-kernel`/`-initrd` 直接启动、串口捕获输出来验证 ELF 运行。两个下载源均已实测可达。**但该路径的工程量与每次 VM 启动开销明显高于原方案**，属于方案级偏离，需用户在本阶段收口时确认采用。

在此确认前，T0–T4（工具链、探针、ABI 平台化、驱动、运行时补完）照常推进——T1 ABI 探针是编译期常量，不依赖运行器。

### 2.2 代码层缺口——M13 归档清单之外的三块大工作量

1. **LLVM ABI 平台化**（`LLVM生成器.java`）：
   - `调()` :856-899 与回调包装 :373-376 用 Win64 sret/byref 传 `%cjk_value`，SysV AMD64 应走寄存器；不改则几乎每个运行时调用都错，比 setjmp 更早炸。
   - `尝试语句()` :1215/1238 两参 `_setjmp(ptr, ptr)` + `llvm.frameaddress.p0`；glibc/musl 是单参 `setjmp`。
   - `cjk_handler` 手写布局 :236 `[32 x i64]`（256B）与 GEP :1229-1233 隐含偏移 256/264；C 侧 `cjkrt.c:184 h->exception = e` 由目标 ABI 算偏移，**两端必须一致**。M13 文档对 glibc 的推测（200B/208）不能直接套到 musl，必须探针实测。
   - triple :46、datalayout :184-186 的 `m:w` → `m:e`。
2. **缓存平台分桶**（`驱动.ps1`）：缓存键 :135-143/:145-161 不含 target/sysroot/flag；对象名 :172/:556/:671、状态文件 :503/:623 无平台维度。不改造会静默复用 PE 对象，出现「编译成功但产物是 PE」。
3. **运行时 Linux 分支补完**：
   - `文件.c:454` 引用 `CJK_CLS_文件路径`，但该符号仅在 Windows 分支 :61 定义 → ELF 链接必 undefined symbol。
   - `cjk_path_absolute()` :476 直接 `return path`，第27课输出与 JVM 不一致。
   - `exists/create_directories` :498-500/:507 是静默桩，与「不静默」承诺不符。
   - 网络段 `标准库.c:925-946` 全是中文诊断桩，socket 网络需从零实现。

### 2.3 对拍覆盖分析

- 35 课中**只有第27课依赖真实文件 IO**（路径取/建目录/写读/绝对路径/存在性），0 课依赖网络；第28课命令行参数已由平台层完整覆盖。
- 示例工程 10 源文件只依赖命令行参数，无文件/网络 → runner 就位即可对拍。
- 自举不动点 175 组（35 课 × 5 命令）输出为文本，平台无关；需 ELF runner 并执行换行归一化（CRLF/LF）。
- DWARF 断言（`检查调试信息.ps1`）写死 MinGW objdump 与 Win64 地址形态；ELF 需新建独立断言，PE 断言保留零回退。
- 基准 `基准.ps1:27-33` 的 M6 比值是 Win64/PE 实测值，**ELF 必须重新基线**，不能沿用、也不能算回退。

## 3. 核心设计

### 3.1 工具链：musl 静态交叉 + qemu-user（决策②）

- 下载预编译 **musl-cross-make x86_64-linux-musl** 工具链（含 musl 头文件、`libc.a`、`crt1.o`、交叉 gcc），解压到 `构建/工具链/musl/`（构建产物，不提交），记录 SHA256 与版本号。
- 下载 **qemu-user**（Windows 版 `qemu-x86_64.exe`），放到 `构建/工具链/qemu/`，记录版本与哈希。
- **为什么 musl 而非 glibc**：静态链接天然（避免 glibc NSS/DNS 静态告警）、单套头文件 + `libc.a` 自包含、setjmp 单参、无需动态 loader；适合本项目「单文件、双击运行」的交付形态。
- 工具链不进仓库、不进 PATH；由 `原生/驱动.ps1` 与测试脚本从 `构建/工具链/` 按约定路径解析，缺失时给中文安装指引（与现有 clang 缺失提示同构）。
- 目标 triple：`x86_64-linux-musl`（LLVM 规范名 `x86_64-unknown-linux-musl`）；静态链接 `-static -lm -lpthread`，去 `-lwinhttp -lws2_32 -lshell32`。

### 3.2 平台配置对象：一处定义，八处收敛（决策①）

新增 `原生/后端LLVM/.../LLVM平台配置.java`（或生成器内部 record），把散落在 8 处的平台值收进一个对象，由 `-平台 windows|linux` 驱动选择：

| 配置项 | Windows（默认，逐字节不变） | Linux/musl（探针实测后填） |
| --- | --- | --- |
| target triple | `x86_64-w64-windows-gnu` | `x86_64-unknown-linux-musl` |
| datalayout | `e-m:w-...` | `e-m:e-...` |
| `%cjk_handler` 布局 | `[32 x i64]` / align 16 | 探针实测 musl `sizeof(jmp_buf)` 与 `offsetof(exception)` 后填 |
| setjmp 声明/调用 | 两参 `_setjmp(ptr, ptr)` + frameaddress | 单参 `setjmp(ptr)`，去 frameaddress |
| `%cjk_value` 传参/返回 | sret + byref | SysV：两个 eightbyte 走 RAX:RDX / 寄存器按值 |
| 影子栈 | 平台无关，不改 | 不改 |

**探针先行**：任何 ABI 数值（jmp_buf 大小、align、handler 偏移）都必须用交叉工具链编译一个 C 探针程序，输出 `sizeof(jmp_buf)`、`offsetof(cjk_handler, exception)`、`_Alignof(jmp_buf)`，把实测值写进配置与 `cjkrt.c` 的 `_Static_assert`，**禁止照搬 M13 对 glibc 的推测值**。

### 3.3 驱动参数化与缓存分桶

- `原生/驱动.ps1` 新增 `-平台 windows|linux`（默认 windows），`cjc.ps1` 透传。
- `查找clang()` 按平台返回工具链路径 + target/sysroot 参数；编译参数（:176/:538/:560/:662/:675）追加 `--target` 与 `-DCJK_PLATFORM_LINUX`；链接（:203/:662）按平台选库。
- 缓存全链路加平台维度：工具链哈希纳入 target + sysroot 路径/版本 + flag；对象名/状态文件/工作目录加 `-linux` 后缀；状态 JSON 增 `平台` 字段并参与命中判定。
- Windows 的 ANSI 路径绕行（:190-202）改为可选项（Linux 侧不需要）。
- 输出扩展名按平台 `.exe` / `.elf`（或无扩展名），目标目录加平台后缀。

### 3.4 运行时 Linux 补完（决策③）

- `文件.c`：补 `CJK_CLS_文件路径` Linux 定义；用 POSIX `open/read/write/stat/opendir/unlink/rename/mkdir` 实现八个 IO 函数（UTF-8 路径字节直用，不走 wchar）；`cjk_path_absolute()` 用 `realpath`；`exists/create_directories` 给真实语义。
- `标准库.c` 网络段：用 `socket/getaddrinfo/connect/send/recv` 实现 `cjk_http_get_text/status/get/download`（同步阻塞，对齐 WinHTTP 现有语义）；DNS 用 musl 内建 resolver，静态链接可用。网络冒烟纳入验收。
- `cjkrt.c`：Linux 侧 `_Static_assert` 按探针实测值扩出；`cjk_handler` 字段访问改为 runtime helper（`cjk_handler_push/pop/set_exception/get_exception`），LLVM IR 只传 ptr，彻底消除手写偏移歧义。
- `cjk_platform.c`：线程栈顶 `pthread_getattr_np` 失败时不要退回哨兵地址（M13 隐患），改为明确放弃扫描或读 `/proc/self/maps`。

### 3.5 运行器与对拍改造

- 新增 `原生/测试/运行器.ps1`：统一「运行可执行文件」入口——Windows 直接 `Start-Process`，Linux 用 `qemu-x86_64 -L <musl-root> <exe>`。改造 `对拍原生.ps1`、`对拍模块.ps1`、`对拍自举不动点.ps1`、`基准.ps1` 走该入口。
- 第27课绝对路径：JVM（Windows）与 ELF（Linux）输出含平台路径，对拍时按第29课既有白名单机制做**路径归一化**（把盘符路径与 POSIX 绝对路径都归一为占位符），并另设一条真实路径语义专项断言，避免「归一化掉真 bug」。
- 换行归一化：C/LLVM exe 输出 CRLF、Java 解释器与 ELF 输出 LF，对拍统一 `tr -d '\r'`（沿用 M13 对拍模块脚本口径）。
- DWARF：新增 `检查调试信息Linux.ps1`（readelf/llvm-dwarfdump、ELF 地址形态），PE 断言保留；校验脚本两条并存。
- 基准：musl/ELF 下重新实测 M6 四例比值，写 `M14/基准报告.md`，ELF 阈值独立、不沿用 PE 基线。

## 4. 验收口径

1. `cjc.ps1 原生 编译 -平台 linux <源文件> -o <exe>` 产出**可运行 ELF64**，qemu-user 运行输出与 JVM 一致。
2. 35 课 C/LLVM 双后端 ELF 对拍 **35/35**（含第27课路径归一化、第20/21课异常、第28课参数）。
3. 示例工程 10 源文件 ELF C/LLVM/JVM 三方逐字节对拍一致。
4. 自举不动点 ELF 侧 175 组输出与文本基线逐字节一致（换行归一化后）。
5. 网络冒烟与 GC 压力四组在 ELF 侧通过；异常栈 C/LLVM 逐字节一致。
6. ELF DWARF 行号断言通过（新脚本），PE DWARF 断言零回退。
7. ELF 基准四例重新基线并写入报告，无 >1.05 回退。
8. `.\工具\发布\校验.ps1 -含原生 -平台 linux` 退出码 0（新增 8.13）；`-平台 windows` 原口径退出码 0；ADR 计数断言 17 → 18。

## 5. 风险与回退

| 风险 | 缓解 |
| --- | --- |
| musl/qemu 下载被网络或安全策略拦截 | 工具链放构建目录、下载失败给中文指引；若始终无法获取，回退到 M14.1（静态校验，代码层成果保留） |
| 探针实测值与推测不符（ABI 布局） | 探针先行，配置值全部由实测填充；`_Static_assert` 在两端同时守护 |
| qemu-user 路径/换行与 Windows 不一致 | 路径白名单归一化 + 换行归一化 + 真实路径语义专项断言 |
| 新 ABI 改造破坏 Windows PE | Windows 为默认值且逐字节复用；每步跑 PE 全量校验零回退才进下一步 |
| 第20/21课异常在 ELF 侧行为漂移 | handler helper 消除手写偏移；C/LLVM 双后端逐字节对拍兜底 |
| 基准数值不可移植 | ELF 独立基线，绝不沿用 PE 比值 |

## 6. 交付物

- `文档/原生/M14/{spec,tasks,checklist}.md`、`文档/原生/ADR/ADR-018-M14可运行ELF范围.md`
- 代码：`LLVM平台配置.java`（新）、`LLVM生成器.java`、`驱动.ps1`、`cjc.ps1`、`cjkrt.c/h`、`文件.c`、`标准库.c`、`cjk_platform.c`
- 测试：`运行器.ps1`（新）、`检查调试信息Linux.ps1`（新）、现有 4 个对拍/基准脚本改造、`校验.ps1` 新增 8.13
- 文档：`M14/基准报告.md`、`M14/工具链与ABI探针报告.md`、路线图/CHANGELOG/ADR 计数更新
