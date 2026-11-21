# ADR-018：M14 范围（可运行 Linux ELF，musl 静态交叉 + qemu 全量对拍）

- 状态：Accepted（2026-11-21 实施完成：35 课 C/LLVM ELF 对拍 35/35 ×2、DWARF 2/2、GC 4/4、网络冒烟通过、Windows 全量校验零回退；运行器因 Windows 无 qemu-user 改为 qemu-system + Alpine VM）
- 日期：2026-11-20
- 关联：路线图第 14 节、ADR-001（一期平台口径）、ADR-010:47-55（交叉编译延后）、ADR-017（编号重排）、M13 spec:74-80 / 平台配置与静态检查.md、用户 2026-11-20 三项方向决策

## 背景

ADR-017 把原 M13「Linux ELF 交叉编译」顺延为 M14，前置依赖（平台抽象层 + 多文件模块）已由 M13 完成并推送（全量校验退出码 0）。ADR-010 当初否决交叉编译的理由——"运行时依赖 ws2_32/winhttp 与 Windows 控制台"——已由 M13 平台抽象层解除。

2026-11-20 实测本机能力后，发现 M13 报告"本机不能产 ELF"的结论需要修正：clang 23.1.5 + ld.lld 已能产出合法 ELF64 对象与可执行文件（缺的只是 libc/sysroot 与 `_start`）。同时代码层暴露三块 M13 文档未覆盖的大工作量：LLVM SysV ABI（cjk_value 传参、setjmp 单参、handler 手写偏移）、缓存平台分桶、运行时 Linux 文件 IO/网络桩。

用户 2026-11-20 决策：① 一步到位做可运行 ELF（不分阶段）；② 联网下载 musl 静态交叉工具链 + qemu-user；③ 文件 IO 与 socket 网络全做，网络冒烟纳入验收。

## 决策

### 1. 目标：可运行 ELF + 全量对拍，不做分阶段降级

M14 完成的定义是：本机（Windows 宿主）交叉编译产出的 ELF 经 qemu-user 运行，能与 JVM 逐字节对拍通过 35 课 + 多文件模块 + 自举不动点 + 异常栈 + GC + 网络冒烟 + DWARF + 基准。**验收口径与 Windows PE 对齐**，不因为"跨平台"降低到"只看 ELF 头部结构"。

### 2. 目标 libc 选 musl，不选 glibc

- musl 静态链接天然，无 glibc NSS/DNS 静态告警；单套头文件 + `libc.a` 自包含，预编译工具链开箱即用。
- setjmp 单参、SysV AMD64 调用约定，平台配置收敛到一个对象。
- 目标 triple `x86_64-unknown-linux-musl`，链接 `-static -lm -lpthread`，去 Win32 库。
- 工具链与 qemu 从网络下载到 `构建/工具链/`，记录版本/来源/SHA256，不入库、不进 PATH。

### 3. ABI 常量一律探针实测，禁止照搬推测

M13 `平台配置与静态检查.md:14-17` 对 glibc 的推测（datalayout `m:e`、jmp_buf 200B/偏移 208、glibc 单参 setjmp）只是方向参考。**musl 的实际 `sizeof(jmp_buf)`、`offsetof(cjk_handler,exception)`、align 必须用交叉 gcc 编译探针实测**，结果写进 `LLVM平台配置` 与 `cjkrt.c` 的 `_Static_assert`。handler 字段访问改为 runtime helper，让 LLVM IR 只传 ptr，消除手写偏移歧义。

### 4. Windows PE 逐字节零回退是硬门槛

- `-平台 windows` 保持默认值，LLVM 平台配置 `windows()` 逐字节复现现有 IR；handler helper 在 C 后端的使用不改变 Windows 输出。
- 每完成一个代码层改动（T2、T3、T4）都跑一次 Windows 全量校验，退出码 0 才进下一步。
- 35 课 AST/MIR/语义快照、自举不动点文本基线、C/LLVM 符号编码 `编码()`、Windows DWARF/基准基线全部零回退；ELF 侧新增独立基准，不修改也不复用 PE 基线。

### 5. 缓存必须平台分桶

驱动缓存键增加 target/sysroot/flags，对象名、状态文件、工作目录加平台后缀，状态 JSON 增 `平台` 字段参与命中。否则同一模块在两平台间切换会静默复用 `.o`，出现"编译成功但产物是 PE"的假阳性。

### 6. 文件 IO 与 socket 网络全做，按平台口径对拍

- `文件.c` Linux 分支补 `CJK_CLS_文件路径` 定义（M13 遗留链接期硬伤）、POSIX 八函数、`realpath` 绝对路径、真实 `exists/create_directories`。
- `标准库.c` 网络段用 socket/getaddrinfo 实现 HTTP 五函数，网络冒烟纳入 ELF 验收。
- 第27课绝对路径差异（JVM Windows 盘符 vs ELF POSIX 路径）用白名单归一化 + 真实路径语义专项断言双保险，避免归一化掩盖真 bug。

## 理由

1. **兑现 ADR-010 的解除条件**：M13 已解平台耦合，M14 是 ADR-010 预留的交叉编译路径的自然落地，一步到位才有完整的"中文源码 → 双平台可执行文件"闭环。
2. **本机可完整验收**：musl 自包含 + qemu-user 使 Windows 宿主具备端到端 ELF 对拍能力，无需 WSL 或远程 Linux 机器，避免 M13 那种"只能静态检查"的降级口径。
3. **风险靠探针和零回退兜住**：ABI 用实测值、handler 用 helper、Windows 每步校验、ELF 基准独立——把"改 ABI 最容易炸"的风险压到可发现、可回滚的范围。

## 替代方案与取舍

| 方案 | 不采用的原因 |
| --- | --- |
| 分两阶段（M14 先静态校验、M14.1 再运行） | 用户明确选一步到位；本机 musl+qemu 已具备端到端能力，分期反而拖长反馈周期 |
| glibc sysroot | 静态链接有 NSS/DNS 告警，动态链接又需 loader 与 rpath，交付形态比 musl 复杂 |
| 照搬 M13 文档 glibc 推测值填 ABI | 那是 glibc 且是推测；musl 实测值可能不同，写错会让第20/21课异常读野指针 |
| 继续用写死的 Win64 ABI，只改 triple/datalayout | `cjk_value` 传参、setjmp 签名、handler 偏移三处会在 ELF 侧系统性错，必然对拍失败 |
| 缓存不平台分桶 | PE/ELF 对象互相覆盖，产生"编译成功但跑的是 PE"的假阳性，比显式报错更危险 |
| 第27课直接跳过或只归一化掉路径 | 跳过破坏 35/35 完整口径；只归一化会掩盖绝对路径真 bug，需专项断言兜底 |
| 沿用 PE 基准判定 ELF 性能回退 | 两平台 libc 与调用约定不同，数值不可比，会产生误报 |

## 影响

- 新增 `文档/原生/M14/*`、本 ADR；路线图第 14 节补 M14 小节；`校验.ps1` 新增 8.13，ADR 计数 17 → 18。
- 驱动 `驱动.ps1`/`cjc.ps1` 新增 `-平台`；LLVM 生成器与 `cjkrt.c/h` 做 ABI 平台化；`文件.c`/`标准库.c`/`cjk_platform.c` 补 Linux 实现。
- 新增 `LLVM平台配置.java`、`运行器.ps1`、`检查调试信息Linux.ps1`；改造 4 个对拍/基准脚本。
- `构建/工具链/` 为本地工具链目录，不提交；ELF 产物与缓存按平台分桶，不影响现有 PE 目录。
- Windows PE 产物、35 课对拍/快照/不动点/DWARF/基准与多文件模块对拍全部必须零回退。

## 验收

见 [M14 checklist](../M14/checklist.md)。核心：`cjc.ps1 原生 编译 -平台 linux` 产出可运行 ELF；35 课 C/LLVM ELF 对拍 35/35；示例工程三方对拍；自举不动点 175 组；`.\\工具\\发布\\校验.ps1 -含原生`（Windows 默认）与 `-平台 linux` 双模式退出码 0。
