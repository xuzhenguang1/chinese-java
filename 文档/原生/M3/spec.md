# M3 C 后端 MVP（无对象）

- 状态：进行中
- 日期：2026-11-09
- 关联：路线图第 7 节 M3；ADR-005（C 后端与行号）；ADR-006（运行时与对象模型）；ADR-007（语义兼容）

## 1. 目标

把 M2 产出的 MIR 降级为 C 源文件，由 clang 编译链接成 Windows x64 本机 exe，覆盖教程 01–08：

- 变量与八个基本类型、`字符串`
- 算术/比较/逻辑/位运算、强制转换、复合赋值、自增自减
- 字符串拼接与插值（M2 已脱糖为 `二元 文本 +`）
- `若/否则若/否则`、三目
- `对于`（三段式与增强）、`当`、`做当`、`跳出`、`继续`
- 一维/二维数组、`长度`、`系统.数组复制`
- 静态方法与重载、`入口`
- `打印行` / `打印`

不在 M3 范围：对象、字段、继承、接口、异常、泛型、标准库集合（M4/M5）。

## 2. 行号链路

M1 的 AST 不带位置，且 AST 黄金快照不能变。方案：

1. `语法分析器` 在解析语句时把「语句节点 → 起始行」记录到 `IdentityHashMap`，AST 文本打印不读取该表，快照零差异。
2. `语义分析器` 构造时接收该表与中文源相对路径；每翻译完一条 MIR 语句，记录「MIR 语句 → 行号」到 `中间代码.位置信息`。
3. `C生成器` 在每个 MIR 语句生成的代码前输出
   `#line <行号> "<相对路径>"`；行号缺失时回退到上一条语句的行号。
4. MIR 文本格式不变，`中间打印器` 不输出行号，M2 快照无需更新。

## 3. C 生成约定

- 产物：`构建\原生\<模块>.c`，仅作中间产物，不入库。
- C 标识符：`_cjk_<码点十六进制>`，逐段以下划线连接；上方注释保留中文原名。
- 类型映射：

| MIR 类型 | C 类型 |
| --- | --- |
| 整数 | `int32_t` |
| 长整数 | `int64_t` |
| 短整数 | `int16_t` |
| 字节 | `int8_t` |
| 浮点 | `float` |
| 双浮点 | `double` |
| 字符 | `uint16_t` |
| 布尔 | `int32_t`（只取 0/1） |
| 文本 | `cjk_string*` |
| 数组 | `cjk_array*` |
| 无 | `void` |

- 方法：`static <返回类型> _cjk_<类>_<方法>(参数...)`；`<入口>` 生成 `static void _cjk_<类>_entry(void)` 与 `int main(void)`。
- 类名/方法名/变量名全部转义，重载通过参数类型不同由 C 天然区分（同名同参数个数不同参数类型在 C 中同名会冲突），因此方法名附带参数类型签名短码。
- 语句：`块` → `{ ... }`；`局部变量语句` → `T 名 = 表达式;`；`若/当/做当/对于` 直译。
- 增强 `对于` 的 MIR 初始化是 `块`：块内声明直接写在 `for` 之前，避免作用域提前结束。
- 取模/除法：插入除零检查；整数溢出依赖 `-fwrapv`；移位先掩码（int 31、long 63）。
- 数组读写：每次插入长度检查，越界打印中文错误并退出码 1（M3 无异常，M4 换成异常）。
- 空引用：M3 只有数组与字符串可能为空，解引用前检查。

## 4. 运行时接口（`原生\运行时\cjkrt.h`）

```c
typedef struct { int32_t len; uint16_t* data; } cjk_string;

cjk_string* cjk_str_from_utf8(const char* s);
cjk_string* cjk_str_from_i32(int32_t v);
cjk_string* cjk_str_from_i64(int64_t v);
cjk_string* cjk_str_from_f32(float v);
cjk_string* cjk_str_from_f64(double v);
cjk_string* cjk_str_from_bool(int32_t v);
cjk_string* cjk_str_from_char(uint16_t v);
cjk_string* cjk_str_concat(cjk_string* a, cjk_string* b);
cjk_string* cjk_str_trim(cjk_string* s);
int32_t     cjk_str_len(cjk_string* s);
int32_t     cjk_str_equal(cjk_string* a, cjk_string* b);

void cjk_print_str(cjk_string* s);
void cjk_print_i32(int32_t v);
void cjk_print_i64(int64_t v);
void cjk_print_f32(float v);
void cjk_print_f64(double v);
void cjk_print_bool(int32_t v);
void cjk_print_char(uint16_t v);
void cjk_newline(void);

cjk_array* cjk_array_new(int32_t len, int32_t kind);
int32_t    cjk_array_len(cjk_array* a);
void       cjk_array_check(cjk_array* a, int32_t index, int32_t line);
void       cjk_array_copy(cjk_array* src, int32_t src_pos, cjk_array* dst, int32_t dst_pos, int32_t len, int32_t line);

void cjk_fatal(int32_t line, const char* message);
```

- 字符串内部 UTF-16 码元；边界 UTF-8 转换；`cjk_string*` 为 NULL 时按 `null` 文本处理。
- 浮点打印与 `Double.toString` / `Float.toString` 对齐：最短可回读十进制，[1e-3, 1e7) 用普通小数且至少一位小数，其余用 `d.dddE±x`（大写 E、无 `+`）。
- 数组统一结构：`{ int32_t len; int32_t kind; void* data; }`，`kind` 区分基本类型与引用。

## 5. 命令行

```
.\cjc.ps1 原生 编译 <源文件> [-o <输出 exe>] [-保留C]
```

- 默认 C：`构建\原生\<模块>.c`；默认 exe：`构建\原生\<模块>.exe`。
- clang 探测：`C:\msys64\mingw64\bin\clang.exe` → PATH `clang`。
- 编译参数：`-O2 -std=c11 -fwrapv -fno-strict-aliasing -static`，同时编译 `原生\运行时\cjkrt.c`。
- 缺少 clang：中文安装指引，退出码 2，不生成半成品 exe。

## 6. 验收

1. `.\cjc.ps1 原生 编译 教程\第01课你好世界.java -o 构建\原生\第01课.exe` 生成 exe，运行输出「你好，中文编程！」。
2. 教程 01–08 的 exe stdout、退出码与 JVM 后端逐字节一致（`.\cjc.ps1 原生 对拍原生 教程`）。
3. Windows 跑一遍；Linux 用同一份 C 与运行时，`clang` 编译后重跑 01–08 对拍。
4. 教程 09–35 在 C 后端给出中文「M3 暂不支持」诊断，不崩溃。
5. `.\工具\发布\校验.ps1 -含原生` 退出码 0。
