# M20 任务清单

| 任务 | 内容 | 状态 | 证据 |
|---|---|---|---|
| T0 | ADR-023（G4/G5/G6 + 完整单态化决断）Accepted | 完成 | `524520e` 前的 ADR 提交 `774d852` |
| T1 | 浮点根治：回读循环 `p=1`、撤销已知差异与 ADR-007 限制 | 完成 | `524520e`；82510 样本仅 7 个次正规数改变且全修正 |
| T2a | C 后端完整单态化：实例收集、特化 struct/构造/new/方法、静态路由、菱形 new | 完成 | `7869ccb`；第25课 8→3 |
| T2b | LLVM 后端对称实现：特化 struct/函数、invoke 路由、字段/大小/转文本替换 | 完成 | `b82c37b`；第25课 13→8 |
| T3-0 | unwind 工具链：PE libstdc++ / ELF Alpine `libc++abi`+`libunwind`+`liblzma` | 完成 | 驱动编译/链接参数、缓存键；PE probe 与 ELF probe 实测 |
| T3-1 | 运行时 ABI：`cjk_throw`/`cjk_rethrow`/`cjk_uncaught`/`cjk_catch_object` | 完成 | `原生/运行时/异常.cpp`；`cjkrt.c` 移除 longjmp 抛出 |
| T3-2 | LLVM landingpad + personality + 入口未捕获兜底 | 完成 | 第20/21课 PE/ELF 实测、未捕获中文栈 |
| T3-3 | C 生成代码走 C++ try/catch landing | 完成 | 第20/21课 PE/ELF 实测、未捕获中文栈 |
| T4 | 校验 8.19、ADR-006 修订、文档四件套、路线图/CHANGELOG、提交推送 | 完成 | 本目录与 8.19 节；`校验.ps1 -含原生` 8.0–8.19 全绿（退出码 0） |

## 推进顺序与约束

- 顺序锁定：浮点根治 → 泛型单态化 → DWARF 展开；前两项零回退后才动异常 ABI。
- G5 单轨化后无 setjmp 回退；若某平台无法对齐须回用户重新对齐，不自动降级。
- 所有运行时/后端改动以 35 课零回退、快照零回退为硬门槛。
