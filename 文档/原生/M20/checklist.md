# M20 验收清单

## 浮点根治（G6）

- [x] `格式化浮点()` 回读循环从 `p=1` 起，符合 Java「小数部分至少一位」。
- [x] 82510 样本回归：仅 7 个次正规数输出改变，且全部改对；无弄坏正常样本。
- [x] `浮点已知差异.txt` 移除 `^minvalue` 行；ADR-007 限制第 2 条撤销。
- [x] 浮点边界 7/7 逐字节一致，无「已登记差异」。

## 泛型单态化（G4）

- [x] 实例收集器：变量/字段/调用/新建的类型实参登记，去重保序。
- [x] 类型封闭判定：接口/继承/父类型引用类退回擦除，vtable/descriptor 不动。
- [x] C：特化 struct + 构造/new/方法 + 静态调用路由 + 菱形 new 反推实参。
- [x] LLVM：同口径特化 struct/函数，字段读写/对象大小/转文本/值参按实参替换。
- [x] 第25课 box/unbox：C 8→3，LLVM 13→8；余项按 ADR-023 擦除回退。
- [x] 35 课 C/LLVM 对拍零回退、MIR 快照 35/35、自举 47852 行一致。

## 异常展开（G5）

- [x] PE 工具链：clang++ + libstdc++，`__gxx_personality_seh0`，运行时 C 加 `-fexceptions`。
- [x] ELF 工具链：Alpine 静态 `libc++abi`/`libunwind`/`liblzma`，`__gxx_personality_v0`，缓存键纳入哈希。
- [x] `cjk_throw` 改为 Itanium 抛出 `cjk_exception*`；`cjk_rethrow`、`cjk_uncaught`、`cjk_catch_object`、`cjk_end_catch` 落地。
- [x] LLVM：`landingpad catch ptr null` + `invoke` 保护调用 + 入口未捕获 landing；不再发射 setjmp 处理器栈。
- [x] C：生成 C++ `try/catch (cjk_exception*)` + 外层 `catch (...)` 兜底/重抛；不再发射 setjmp。
- [x] 中文帧：`cjk_frame_*` 两后端保留，未捕获异常打印类名/源行/中文调用栈。
- [x] 第20/21课 PE/ELF 实测三方一致；未捕获异常 PE/ELF 均定位到中文帧。
- [x] 35 课 C/LLVM 对拍零回退（唯一差异为本地临时 harness 的盘符大小写，非产物差异）。

## 收口

- [x] `校验.ps1` 新增 8.19，ADR 计数 22→23；`.\工具\发布\校验.ps1 -含原生` 全量 8.0–8.19 退出码 0。
- [x] ADR-006 异常节更新为二期展开口径；ADR-007 浮点限制撤销。
- [x] M20 文档四件套（spec/tasks/checklist/对拍报告）齐备。
- [x] 路线图 / CHANGELOG 补段。
- [x] 提交并推送。
