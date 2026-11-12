# M6 LLVM 后端 实施计划

## Context（为什么做）

M5 已让原生 C 后端在教程 01–35 上与 JVM 逐字节一致，全量校验退出码 0。
M6 按路线图第 7 节接入 LLVM：**复用 MIR 直接生成 LLVM IR**，用 `-O2` 产出 exe，
并让 gdb/lldb 能看到中文源文件与行号（DWARF）。

硬约束：

- 不改 `工具\cjc\Cjc.java` 转译链路，不改 C 后端产物；C 后端仍是默认后端与对拍基准。
- 运行时继续用 `原生\运行时` 的 C 实现（cjkrt），LLVM 后端只替换「MIR → 目标代码」这一段。
- 全部中文交流与中文诊断；无法支持的能力必须报明确中文错误，不允许静默降级。
- 临时实验放 `构建\`，结束即删；提交前询问用户，不推送。

## 目标与验收

1. `.\cjc.ps1 原生 llvm 编译 教程\第01课你好世界.java -o 构建\原生\llvm\第01课.exe` 可产出并运行。
2. 教程 01–35 在 LLVM 后端与 JVM 后端 stdout、退出码逐字节一致；
   同时与 C 后端逐字节一致（`对拍原生.ps1 -后端 llvm` 35/35）。
3. `objdump --dwarf=decodedline` 能看到中文源文件名（如 `第20课捕获异常.java`）与真实行号；
   本机有 gdb 时 `info line` 同样命中。
4. 基准（递归 fib、长循环、字符串拼接、键值表读写）在 `-O2` 下 LLVM 不慢于 C 后端
   （计时噪声容忍 5%，报告 3 次最小值与比值）。
5. `.\工具\发布\校验.ps1 -含原生` 退出码 0，新增 M6 断言；M1–M5 快照与 C 后端输出零变化。
6. 生成过程确定性：同一源文件两次生成的 `.ll` 逐字节相同（保留在 `构建\原生\llvm\`）。

## 关键设计

### 1. 后端形态：MIR → LLVM IR 直接发射

新增 `原生\后端LLVM\src\中文\原生\后端LLVM\LLVM生成器.java`，输入 `中间代码.程序`，输出 `.ll` 文本。

- 表达式发射器返回 `(类型, LLVM 值)`，值既可以是 SSA 临时量 `%tN`，也可以是常量。
- 局部变量、参数槽、try 处理器一律用 **entry 块 alloca + load/store**；
  临时值保持 SSA，不强制内存往返。
- 控制流：`br` / 条件 `br` / 标签；`对于`、`当`、`做当` 直接映射；
  `&&`、`||` 走短路基本块；`三目` 在汇合块用 `phi`。
- `顺序表达式`（M5 `产出`）在表达式上下文中先发射前奏语句，再返回结果值。
- `实例判断` 的绑定名与 C 后端一样由语义阶段脱糖，LLVM 只生成 `cjk_is_instance`。
- 所有指针使用 opaque `ptr`（LLVM 23 已移除 typed pointer），GEP 显式带类型。

### 2. 类型与运行时 ABI

| 中文类型 | LLVM 类型 |
| --- | --- |
| 整数 / 布尔 | `i32` |
| 长整数 | `i64` |
| 短整数 / 字符 | `i16` |
| 字节 | `i8` |
| 浮点 / 双浮点 | `float` / `double` |
| 文本、数组、类、空 | `ptr` |
| 无 | `void` |

- `%cjk_value = type { i32, i64 }`：与 C 的 `{ int32_t kind; union { int64_t/double/void* } }`
  内存布局一致（kind 在 0，联合体在 8，大小 16）；构造/读取用 `insertvalue` / `extractvalue`。
- `%cjk_class = type { i32, ptr, ptr, i32, ptr, ptr, i32, ptr }`，与 `cjk_class` 字段顺序一致。
- `%cjk_handler = type { [16 x i64], ptr, ptr }`：mingw-w64 x64 的 `jmp_buf` 为 128 字节，
  即 `buf` 在 0、`prev` 在 128、`exception` 在 136。T1 用 C 探针实测，
  并在运行时加 `_Static_assert(sizeof(cjk_handler) == 144)` 防漂移。
- 运行时函数全部按 `cjkrt.h` 原型声明为 `external`；结构体按值传递/返回由 LLVM 按
  Win64 ABI 自动处理，与 clang 编译 C 的结果一致。
- target triple 固定 `x86_64-w64-windows-gnu`；datalayout 在 T1 从 clang 探针获取后写成常量。

### 3. 命名、类布局与装箱（与 C 后端同一套语义）

- 复用 C 后端的命名规则：`_cjkf_` / `_cjkctor_` / `_cjknew_` / `_cjkcls_` / `_cjkmt_` /
  `_cjkobj_` / `_cjksf_` / `_cjkv_` / `_cjkcb_` / `_cjkhook_`，中文一律用码点十六进制编码
  （`编码()` 同规则）。为避免改动 C 后端，LLVM 生成器内保留一份同名工具方法并注释来源。
- 类结构体：`cls` 指针 + 祖先字段（按继承链、去重）+ 本类字段；
  静态字段、枚举常量、枚举数组按 C 后端同样顺序生成全局。
- 类描述符：id 从 100 起、父描述符、槽表、接口表、`to_string` 钩子，全部与 C 后端一致。
- 虚调用：`接收者->cls->methods[槽位]`，槽位编号规则与 C 后端一致。
- 装箱/拆箱：完整移植 `到值` / `从值` / `到原生值` / `适配` / `值参` 五组规则，
  这是与集合、流、标准库保持逐字节一致的关键。

### 4. 异常

- 与 C 后端同构：`cjk_handler` 栈 + `setjmp`/`longjmp`。
- 声明 `i32 @setjmp(ptr) returns_twice`；try 体、捕获分支、兜底、未捕获重抛按 C 后端顺序发射。
- 所有局部 alloca 在 entry 块，保证 `longjmp` 回来后变量值有效；
  T3 增加「try 前赋值、catch 后读取」的回归用例。

### 5. DWARF 调试信息

- 模块级：`!llvm.dbg.cu`、`!llvm.module.flags`（Dwarf Version 4、Debug Info Version 3）。
- 每个生成函数一个 `DISubprogram`，文件名用中文源路径（UTF-8 字节按 IR 转义输出），
  行号取 MIR 语句行号。
- 每条语句的首个指令附加 `!dbg !DILocation`；`call`、`br`、`ret` 必须带行号。
- 验收用 `objdump --dwarf=decodedline` 检查中文文件名与行号，不依赖 gdb 安装。

### 6. 驱动与脚本

- `前端主程序` 增加 `编译LLVM <根目录> <源文件> <输出.ll>`，与现有 `编译` 并列；
  驱动编译源目录加入 `原生\后端LLVM\src`。
- `原生\驱动.ps1` 增加 `llvm` 子命令：
  - `.\cjc.ps1 原生 llvm 编译 <源文件> [-o <exe>] [-只生成IR]`
  - 生成 `构建\原生\llvm\<模块>.ll`，再调用 clang：`.ll + 运行时 .c` 一起 `-O2 -g` 链接，
    保留中文报错（clang 缺失时给安装指引）。
- `对拍原生.ps1` 增加 `-后端 c|llvm`（默认 `c`，兼容现有调用）；
  LLVM 模式用 `原生 llvm 编译`，输出到独立目录。
- `校验.ps1` 新增 M6 断言：LLVM 对拍 35/35、调试信息命中、基准不慢于 C。

### 7. 测试与基准

- `原生\测试\检查调试信息.ps1`：对第 01/20/35 课编译 LLVM，解析 `objdump --dwarf=decodedline`，
  断言中文文件名与 ≥3 个不同行号；有 gdb 时追加 `info line`。
- `原生\测试\基准.ps1`：四个固定用例，C 与 LLVM 各跑 3 次取最小值，输出表格与比值；
  LLVM > C × 1.05 判失败。

## 文件改动

| 文件 | 改动 |
| --- | --- |
| `原生\后端LLVM\src\中文\原生\后端LLVM\LLVM生成器.java` | 新增：MIR → LLVM IR、DWARF、异常、装箱、内建调用 |
| `原生\后端LLVM\src\中文\原生\后端LLVM\IR输出.java` | 新增：模块/函数/指令/元数据的文本拼装与转义 |
| `原生\前端\src\中文\原生\前端\前端主程序.java` | 增加 `编译LLVM` 命令 |
| `原生\驱动.ps1` | 源码目录、`llvm` 子命令、clang 调用与中文诊断 |
| `原生\运行时\cjkrt.c` | 增加 `_Static_assert(sizeof(cjk_handler) == 144)`（仅 Windows x64） |
| `原生\测试\对拍原生.ps1` | `-后端 c|llvm` |
| `原生\测试\检查调试信息.ps1` | 新增：DWARF 中文文件名/行号断言 |
| `原生\测试\基准.ps1` | 新增：C vs LLVM 基准 |
| `工具\发布\校验.ps1` | 新增 M6 三段断言 |
| `文档\原生\ADR\ADR-009-LLVM后端与调试信息.md` | 新增：直接发射、ABI、setjmp、DWARF 决策 |
| `文档\原生\M6\spec.md` / `tasks.md` / `checklist.md` | 新增：设计、任务、真实输出 |
| `文档\原生\路线图.md` | M6 状态与验证结论回填 |

## 任务顺序

1. **T1 布局探测与 hello 可行性**：C 探针实测 `sizeof(cjk_value/jmp_buf/cjk_handler)`、
   setjmp 实际符号、target triple/datalayout；手写最小 `.ll` 调 `cjk_rt_init` + `cjk_print_str`
   跑通，并用 objdump 验证中文行号。产出 `构建\` 临时探针，结束删除。
2. **T2 发射器骨架 + 01–08**：类型/全局/描述符/入口/函数/局部/控制流/字符串/数组/内建打印；
   `原生 llvm 编译` 跑通 01–08，与 JVM 对拍一致。
3. **T3 对象模型、继承、接口、枚举、异常、GC（09–21）**：槽表、构造器、静态初始化、
   setjmp 异常；09–21 对拍一致，补 try 变量存活用例。
4. **T4 集合、泛型、λ/流/可选值、文件、标准库（22–35）**：移植装箱/拆箱与全部内建调用；
   22–35 对拍一致。
5. **T5 DWARF 完整化**：每函数 DISubprogram、每语句 DILocation；`检查调试信息.ps1`
   在 01/20/35 课命中中文文件名与行号。
6. **T6 驱动与对拍收口**：`对拍原生.ps1 -后端 llvm` 35/35；`校验.ps1` M6 断言接入。
7. **T7 基准**：`基准.ps1` 四项，LLVM 不慢于 C（≤1.05），记录真实数字。
8. **T8 文档与全量回归**：ADR-009、M6 spec/tasks/checklist、路线图回填；
   `.\工具\发布\校验.ps1 -含原生` 退出码 0；清理 `构建\` 临时文件。
9. **T9 交付**：汇总真实命令输出，询问是否提交（不推送）。

## 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| `cjk_value` 结构体 ABI 不一致 | 集合/流全部错乱 | T1 用 C 探针校验布局；对拍覆盖集合 |
| setjmp 符号或 jmp_buf 尺寸不符 | 异常直接崩溃 | T1 实测符号与尺寸；运行时静态断言 |
| `-O2` 把跨 setjmp 变量提升到寄存器 | catch 后读到旧值 | alloca 在 entry 块 + `returns_twice`；专项回归用例 |
| DWARF 中文路径编码错误 | gdb/objdump 看不到源文件 | UTF-8 字节转义 + objdump 断言 |
| 内建调用分支遗漏 | 某课对拍失败 | 对照 `C生成器.内建调用` 逐分支移植并计数核对 |
| IR 文本非确定性 | 快照/复现失败 | 临时量、标签、元数据统一递增编号，禁止哈希表迭代顺序 |
| 编译时间过长 | 全量校验超时 | 单文件 IR + 运行时 C 一次链接；必要时缓存运行时目标文件 |

## 完成后的验证命令

```powershell
.\cjc.ps1 原生 llvm 编译 教程\第01课你好世界.java -o 构建\原生\llvm\第01课.exe
.\构建\原生\llvm\第01课.exe
.\原生\测试\对拍原生.ps1 -后端 llvm 教程
.\原生\测试\检查调试信息.ps1
.\原生\测试\基准.ps1
.\工具\发布\校验.ps1 -含原生
```
