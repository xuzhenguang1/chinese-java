# ADR-009 LLVM 后端与调试信息

- 状态：已接受（M6）
- 日期：2026-11-11
- 关联：ADR-001（目标产物）、ADR-004（MIR）、ADR-005（C 后端与行号）、ADR-006（运行时与对象模型）、ADR-008（测试与对拍策略）

## 结论

新增 `原生\后端LLVM`，从同一份 MIR **直接发射 LLVM IR**，由 clang `-O2` 链接既有 C 运行时：

1. **直接发射，不经过 C**。MIR 的类型、槽位、装箱、内建调用规则与 C 后端共用同一套语义；
   C 后端保持默认且行为不变，继续作为对拍基准。
2. **运行时不变**。对象头、`cjk_class`、`cjk_handler`、集合、GC、标准库全部复用 `原生\运行时`；
   LLVM IR 只按 ABI 调用这些 C 符号。
3. **Win64 ABI 显式还原**。clang 对 16 字节结构体不会自动按值传递，必须在 IR 里手工降级：
   - 返回：`void @f(ptr sret(%cjk_value) align 8, ...)`；
   - 形参：`ptr align 8`，由调用方 `alloca` + `store` 后传地址。
   漏掉这一层会表现为「第一个进入运行时的调用就访问冲突」。
4. **异常同构**。`%cjk_handler` 栈 + `_setjmp(ptr buf, ptr llvm.frameaddress(0))`；
   `jmp_buf` 需 16 字节对齐（`alloca %cjk_handler, align 16`），`_setjmp` 标记 `returns_twice`。
5. **DWARF**。`DICompileUnit` + `DIFile`（中文文件名与目录）+ 每函数 `DISubprogram` + 每语句 `DILocation`；
   `main` 不挂 `!dbg`，避免 `-O2` 下「inlinable function call ... requires debug info」。
6. **确定性**。只用 `LinkedHashMap`/`LinkedHashSet`；临时量、标签、元数据统一递增编号；
   同一源文件两次生成的 `.ll` 逐字节相同。
7. **符号命名**。全局与局部符号一律 ASCII；中文类名/方法名/变量名按 C 后端 `编码()` 规则转码；
   运行时里的中文全局用引号标识符（`@"CJK_CLS_非法参数异常"`）。

## 理由

1. 复用 MIR 与 C 运行时，语义差异面最小，35 课可以逐字节对拍。
2. 走 LLVM 才能拿到 `-O2` 优化与标准 DWARF，调试器能停在中文源行上。
3. 运行时仍是一套 C 实现，避免两套对象模型、两套 GC。
4. C 后端保留为默认产物，LLVM 是可选后端，出问题可回退。

## 替代方案

| 方案 | 不采用的原因 |
| --- | --- |
| MIR → C → clang | C 后端已存在；再经 C 无法直接控制 DWARF 与 ABI，也不算「LLVM 后端」 |
| 自研后端（x86 汇编） | 优化与调试信息成本远高于收益 |
| 直接用 clang 编译生成的 C 并加 `-g` | 行号来自 C，不是中文源；异常/装箱语义仍需 C 层配合 |
| 为 LLVM 另写运行时 | 两套对象模型与 GC，违反 ADR-006 单运行时决策 |

## 验收口径

- `.\cjc.ps1 原生 llvm 编译 教程\第01课你好世界.java -o 构建\原生\llvm\第01课.exe` 成功，输出与 JVM 一致。
- `原生\测试\对拍原生.ps1 -后端 llvm`：35/35，stdout 与退出码逐字节一致。
- `原生\测试\检查调试信息.ps1`：`objdump --dwarf=decodedline` 命中中文文件名与真实行号。
- `原生\测试\基准.ps1`：四项比值 ≤ 1.05，结论「LLVM 不慢于 C」。
- `.\工具\发布\校验.ps1 -含原生` 退出码 0。

## 影响

- 驱动新增 `原生 llvm 检查|编译`；`对拍原生.ps1` 新增 `-后端 llvm`。
- `校验.ps1` 的 M6 段落依赖 clang 与 `objdump`；缺失时跳过并给出提示。
- 生成的 `.ll`、exe 全部落在 `构建\`，不入库。
