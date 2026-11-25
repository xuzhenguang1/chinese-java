# M20 运行时二期 — 规格

> 状态：已完成（2026-11-24）。决策依据：`文档/原生/ADR/ADR-023-M20运行时二期范围.md`、`后续路线计划书-M19至M22.md` §4/§7.2。

## 1. 目标

1. **浮点根治（G6）**：`Double.toString` 最短回读表示与 Java 对齐，撤销 M16 登记的 `MIN_VALUE` 次正规数已知差异。
2. **泛型单态化（G4）**：自定义泛型类按实参生成特化 struct/方法，跳过值盒装箱；MIR 文本与 105 份快照不变；C/LLVM 双后端对称。
3. **异常展开（G5）**：双后端统一到编译器异常 ABI，替换 `setjmp`/`longjmp` 双轨；保留 `cjk_frame` 中文调用栈。

## 2. 非目标

- 不改 MIR 语法/文本、对象头、数组布局、UTF-16 字符串表示、M19 符号编码与别名。
- 不引入 Rust 后端、LTO、`-march` 优化。
- 不把 `声明抛出` 变成强制检查。
- 不为上界擦除的静态泛型方法（`取较大<T extends 可比较<T>>`）做高成本特化，按 ADR-023 保留擦除回退。

## 3. 架构口径

| 项 | 口径 |
|---|---|
| 浮点 | `原生/运行时/cjkrt.c:格式化浮点()` 回读循环从 `p=1` 起（Java「小数部分至少一位」），撤销 `浮点已知差异.txt` 的 `^minvalue` 行与 ADR-007 限制第 2 条 |
| 泛型 | 后端实例收集器登记 `(类名, 实参)`；类型封闭类（未实现接口、未被继承、未出现在父类/接口引用）才特化；多态类保留擦除版本给 vtable/descriptor |
| 泛型路由 | 接收者静态类型带实参的实例调用直连特化函数；菱形 `new` 从期望类型反推；`转文本`/值参按接收者实参反推真实类型 |
| 异常 | 抛出点统一 `原生/运行时/异常.cpp: cjk_throw`，抛 `cjk_exception*`（Itanium ABI）；C 后端生成 C++ `try/catch`，LLVM 生成 `landingpad` + personality；catch 匹配仍走 `cjk_is_instance` |
| 平台 ABI | PE：`__gxx_personality_seh0` + mingw libstdc++；ELF/musl：`__gxx_personality_v0` + Alpine 静态 `libc++abi`/`libunwind`/`liblzma`（落在 `构建/工具链/unwind/lib`） |
| 中文栈 | `cjk_frame_push/pop/set_line/mark/rewind` 两后端保留；未捕获异常由入口外层 landing 调 `cjk_uncaught()` 打印类名/源行/中文调用栈 |

## 4. 验收

- 35 课 C/LLVM 三方对拍零回退；MIR 快照 35/35 + 自举前端 47852 行一致。
- 第 20/21 课异常三方逐字节一致；未捕获异常中文栈定位正确；PE/ELF 双平台均实测。
- 第 25 课 box/unbox 调用点 C 8→3、LLVM 13→8（剩余为上界擦除回退形态）。
- `校验.ps1` 8.19 全绿；ADR 计数 22→23；文档四件套齐备。
