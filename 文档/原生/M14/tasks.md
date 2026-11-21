# M14 任务分解（Linux ELF 交叉编译）

> 状态：已实施待收尾。按 T0→T8 顺序执行，**探针先行**（T0/T1 未拿到实测值前不写任何 ABI 常量）。
> 每步以真实命令输出收尾，Windows PE 默认行为零回退是贯穿全程的硬门槛。

---

## T0 工具链落地（musl + qemu）

- [x] T0.1 下载预编译 `x86_64-linux-musl-cross`（musl-cross-make），解压到 `构建/工具链/musl/`；记录版本、来源 URL、SHA256 到 `M14/工具链与ABI探针报告.md`。
- [x] T0.2 下载 Windows 版 `qemu-user`（`qemu-x86_64.exe`），放 `构建/工具链/qemu/`；记录版本与哈希。
- [x] T0.3 冒烟：`x86_64-linux-musl-gcc -static hello.c -o hello.elf` → `qemu-x86_64 hello.elf` 输出正确。确认 musl 可静态链接、qemu 可运行。
- [x] T0.4 确认 `构建/工具链/` 已被 .gitignore 排除；不提交二进制。

**收尾命令**：`构建/工具链/musl/bin/x86_64-linux-musl-gcc --version` + `构建/工具链/qemu/qemu-x86_64.exe --version` 均有输出。

## T1 ABI 探针（决定所有平台常量）

- [x] T1.1 用 musl 交叉 gcc 编译 C 探针，输出：`sizeof(jmp_buf)`、`offsetof(cjk_handler, exception)`、`_Alignof(jmp_buf)`、`sizeof(void*)`、`sizeof(long)`、`sizeof(cjk_value)`。
- [x] T1.2 编译含 `setjmp/longjmp` 的最小程序并用 qemu 跑通，确认 `setjmp` 签名（单参）与展开行为。
- [x] T1.3 编译含 `pthread_create` + `clock_gettime(CLOCK_REALTIME)` 的程序，qemu 跑通，确认链接库（`-lpthread` 是否必需）。
- [x] T1.4 把全部实测值写入 `M14/工具链与ABI探针报告.md`，标注"后续配置值唯一来源"。

**收尾命令**：探针程序在 qemu 下输出完整数值表，报告文件已提交。

## T2 LLVM 平台配置对象（一处定义）

- [x] T2.1 新增 `原生/后端LLVM/src/中文/原生/后端LLVM/LLVM平台配置.java`：record 持有 triple、datalayout、`cjk_handler` 元素类型/align、setjmp 声明与调用串、`cjk_value` ABI 模式（SRET/BYREF vs REG）；提供 `windows()` / `linux(探针值)` 两个工厂。
- [x] T2.2 `LLVM生成器.java` 注入配置对象，替换 :46 系统行、:184-186 datalayout、:236 handler 布局、:373-376 回调包装、:856-899 调用 ABI、:1212-1310 尝试语句（setjmp/frameaddress/GEP/align）共 8 处硬编码。
- [x] T2.3 `cjkrt.c` 新增 handler 访问 helper（`cjk_handler_push/pop/set_exception/get_exception`），`cjkrt.h` 声明；C 后端 `生成尝试()` 同步改用 helper（Windows 行为逐字节不变）。
- [x] T2.4 `cjkrt.c` 的 `_Static_assert` 按平台分派：Win64 保留 256，Linux 用 T1 实测值。

**收尾命令**：`javac` 全量通过；`校验.ps1 -含原生`（Windows 默认）退出码 0——证明 ABI 重构对 PE 零回退。

## T3 驱动平台参数化 + 缓存分桶

- [x] T3.1 `驱动.ps1` 新增 `-平台 windows|linux`（默认 windows），`cjc.ps1` 透传；`查找clang()`/`输出工具链()`/缺失提示按平台分支。
- [x] T3.2 编译参数（:176/:538/:560/:662/:675）追加 `--target`/`--sysroot`/`-DCJK_PLATFORM_LINUX`；链接（:203/:662）按平台选库（Linux `-static -lm -lpthread`，去 Win32 库）。
- [x] T3.3 缓存全链路平台分桶：工具链哈希纳入 target/sysroot/flags；对象名、状态文件、工作目录加 `-linux`；状态 JSON 增 `平台` 字段并参与命中。
- [x] T3.4 输出扩展名 `.exe`/`.elf`；Windows ASCII 绕行改可选；LLVM 一步链接分支（:662）统一走 `链接可执行()`。

**收尾命令**：同一源文件 `-平台 windows` 与 `-平台 linux` 各编译一次，产物分别为 PE/ELF（读文件头验证），且第二次不命中第一次缓存；Windows 全量校验退出码 0。

## T4 运行时 Linux 补完

- [x] T4.1 `文件.c`：补 `CJK_CLS_文件路径` Linux 定义；八个 IO 函数改 POSIX 实现（UTF-8 路径）；`cjk_path_absolute()` 用 `realpath`；`exists/create_directories` 给真实语义。
- [x] T4.2 `标准库.c` 网络段：Linux 分支实现 `socket/getaddrinfo/connect/send/recv` 版 HTTP（对齐 WinHTTP 语义），替换五个桩；网络冒烟纳入验收。
- [x] T4.3 `cjk_platform.c`：线程栈顶 `pthread_getattr_np` 失败时不再退回哨兵，改为放弃扫描或读 `/proc/self/maps`。

**收尾命令**：musl 交叉编译全部 8 个 `.c` 成功（`--target` + sysroot）；`标准库.c`/`文件.c` 的 ELF 对象无 undefined symbol（用 `nm -u` 核对）。

## T5 端到端首通（单课）

- [x] T5.1 用 T2–T4 成果编译第01课为 ELF，qemu 运行输出与 JVM 一致。
- [x] T5.2 编译第20/21课（异常），验证 handler helper 在 ELF 侧捕获正确、异常栈 C/LLVM 逐字节一致。
- [x] T5.3 编译第27课（文件 IO），验证路径归一化后与 JVM 一致；第28课（命令行参数）直接一致。

**收尾命令**：四课 ELF C/LLVM 对拍通过；任一失败则回到 T1 探针/T2 ABI 排查，不进入 T6。

## T6 全量对拍改造

- [x] T6.1 新增 `原生/测试/运行器.ps1`：Windows `Start-Process` / Linux `qemu-x86_64 -L <musl-root>` 统一入口。
- [x] T6.2 `对拍原生.ps1`、`对拍模块.ps1`、`对拍自举不动点.ps1`、`基准.ps1` 改走运行器；第27课路径归一化 + 真实路径语义专项断言；换行归一化沿用 `-平台` 感知。
- [x] T6.3 新增 `检查调试信息Linux.ps1`（readelf/llvm-dwarfdump、ELF 地址形态），PE 断言保留。
- [x] T6.4 35 课 C/LLVM ELF 对拍 35/35；示例工程三方对拍；自举不动点 175 组；网络冒烟 + GC 压力四组；异常栈一致。

**收尾命令**：各脚本 `-平台 linux` 全绿，输出汇总写入 `M14/对拍报告.md`。

## T7 基准重测与文档

- [x] T7.1 `基准.ps1` 增加 ELF 基线常量（musl 实测），`M14/基准报告.md` 写入四例新比值与阈值（±0.05、>1.05 硬失败）。
- [x] T7.2 ELF DWARF 行号断言通过；`M14/工具链与ABI探针报告.md` 完整。
- [x] T7.3 路线图 M14 小节状态更新；CHANGELOG 补 M14 段；ADR-018 状态 Accepted。

**收尾命令**：三份 M14 文档齐备，数值均来自实测。

## T8 收尾与全量回归

- [x] T8.1 `校验.ps1` 新增 8.13（平台产物格式 + 工具链存在性 + M14 报告存在性 + ELF 对拍结果），ADR 计数 17 → 18。
- [x] T8.2 `校验.ps1 -含原生`（默认 Windows）退出码 0；`-平台 linux` 模式退出码 0。
- [x] T8.3 checklist 全勾；tasks 全勾；提交询问（用户历来要求提交 + 推送，届时确认）。

**收尾命令**：两次全量校验均退出码 0；工作区仅 `.workbuddy/` 与构建产物；提交信息干净。

---

## 依赖关系

```
T0 工具链 → T1 探针 → T2 LLVM 配置 ┐
                    T3 驱动参数化 ├→ T5 首通 → T6 全量对拍 → T7 基准文档 → T8 收尾
                    T4 运行时补完 ┘
```

**硬规则**：T1 之前不写任何 ABI 常量；T2 完成前必须跑一次 Windows 全量校验确认零回退；T5 未首通不进入 T6。
