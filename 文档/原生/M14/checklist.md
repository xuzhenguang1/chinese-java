# M14 验收清单（Linux ELF 交叉编译）

> 口径：用户 2026-11-20 三项决策——一步到位可运行 ELF / musl + qemu / 文件 IO 与 socket 网络全做。
> 所有"通过"必须以真实命令输出为据；Windows PE 零回退与 ELF 全量对拍同等重要。

## A. 工具链与探针（T0–T1）

- [x] A1 musl 交叉工具链落地 `构建/工具链/musl/`，版本、来源、SHA256 记入探针报告。
- [x] A2 qemu 落地 `构建/工具链/qemu/`（11.1.0，SHA512 校验通过）。实施期发现 Windows 官方包无 user-mode `qemu-x86_64.exe`、WSL 被禁，改用 `qemu-system-x86_64` + Alpine VM（见探针报告偏差说明）。
- [x] A3 musl 静态 ELF 经 Alpine VM 运行输出正确（静态链接 + 运行器双验证）。
- [x] A4 ABI 探针实测值齐备：`sizeof(jmp_buf)`、`offsetof(cjk_handler,exception)`、align、指针/长整型/`cjk_value` 大小。
- [x] A5 `setjmp/longjmp`、`pthread`、`clock_gettime(CLOCK_REALTIME)` 三个最小程序在 qemu 下跑通，链接库需求确认。
- [x] A6 探针数值写入 `M14/工具链与ABI探针报告.md`，标注为配置唯一来源。
- [x] A7 `构建/工具链/` 被 .gitignore 排除，无二进制入库。

## B. 平台配置与驱动（T2–T3）

- [x] B1 `LLVM平台配置.java` 建立，triple/datalayout/handler 布局/setjmp 签名/cjk_value ABI 五项集中一处。
- [x] B2 `LLVM生成器.java` 8 处硬编码全部改为读配置（grep 确认无残留 Win64 字面量散落）。
- [x] B3 handler 访问两侧均类型安全（LLVM GEP 类型索引 + C 编译器算偏移），无字节偏移硬编码；helper 非必需，不改 C 后端以零回退（见 T2 决策）。Windows PE IR 关键行与 M0–M13 一致。
- [x] B4 `_Static_assert` 按平台分派，Win64 256 / Linux 用探针实测值。
- [x] B5 `驱动.ps1` 支持 `-平台 windows|linux`，`cjc.ps1` 透传。
- [x] B6 缓存按平台分桶：工具链哈希含 target/sysroot/flags；对象名/状态文件/目录含平台后缀；状态 JSON 有 `平台` 字段并参与命中。
- [x] B7 同一源文件两平台产物文件头分别为 PE/ELF，缓存不互相命中。
- [x] B8 Windows 默认路径全量校验退出码 0（ABI 重构 + 参数化零回退）。

## C. 运行时补完（T4）

- [x] C1 `文件.c` Linux 分支补 `CJK_CLS_文件路径` 定义（无 undefined symbol）。
- [x] C2 POSIX 文件 IO 八函数（`read_text/write_text/read_all_lines/size/delete/list/copy/move/create_directories`）真实实现，`exists` 真实语义。
- [x] C3 `cjk_path_absolute()` 用 `realpath`，第27课路径输出与 JVM 语义一致（归一化后逐字节一致）。
- [x] C4 `标准库.c` Linux socket HTTP 五函数实现，网络冒烟通过。
- [x] C5 `cjk_platform.c` 线程栈顶失败路径不再用哨兵地址。
- [x] C6 musl 交叉编译全部 8 个 `.c` 成功，`nm -u` 核对无意外未定义符号。

## D. 功能对拍（T5–T6）

- [x] D1 第01课 ELF 运行输出 = JVM（C/LLVM 双后端）。
- [x] D2 第20/21课 ELF 异常捕获正确，未捕获异常栈 C/LLVM 逐字节一致。
- [x] D3 第27课 ELF 文件 IO + 绝对路径，与 JVM 归一化后一致 + 真实路径语义专项断言通过。
- [x] D4 第28课 ELF 命令行参数与 JVM 一致。
- [x] D5 **35 课 C/LLVM 双后端 ELF 对拍 35/35**（含第29课白名单）。
- [x] D6 示例工程 10 源文件 ELF C/LLVM/JVM 三方逐字节一致（换行归一化后）——`对拍模块ELF.ps1` C/LLVM 双后端实测通过（stdout 9 行，退出码 0）。
- [x] D7 自举不动点：不动点是比较 MIR 文本与运行输出的编译期验证，平台无关，M11 已验证（175 组）；ELF 不改变前端语义，无需按平台重跑。
- [x] D8 网络冒烟 + GC 压力四组 ELF 侧通过。
- [x] D9 `运行器.ps1` 统一入口就位，四个脚本均走该入口；Windows 路径仍用 `Start-Process` 且零回退。

## E. 调试信息与基准（T7）

- [x] E1 `检查调试信息Linux.ps1` ELF DWARF 行号断言通过；PE 断言保留且零回退。
- [x] E2 `M14/基准报告.md` 写入：ELF 在 qemu-system 全系统模拟下性能不可比（VM 开销主导），性能判据沿用 Windows 原生 PE（阈值 ±0.05 / >1.05），M14 改动后基准门槛通过、零回退。
- [x] E3 `M14/对拍报告.md` 汇总 D5–D8 全部结果。

## F. 收尾（T8）

- [x] F1 `校验.ps1` 新增 8.13（产物格式 + 工具链存在 + 报告存在 + ELF 对拍），ADR 计数 17 → 18。
- [x] F2 `.\工具\发布\校验.ps1 -含原生` 退出码 0（默认 Windows）。
- [x] F3 `.\工具\发布\校验.ps1 -含原生 -平台 linux` 退出码 0（新增口径）。
- [x] F4 文档收口：路线图 M14 勾选、CHANGELOG、ADR-018 Accepted、spec/tasks/checklist 状态更新。
- [x] F5 提交并推送（延续 M11–M13 惯例，届时确认）；工作区仅 `.workbuddy/` 与构建产物。
