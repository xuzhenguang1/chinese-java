# M6 任务

- [x] T1 布局探测与 hello 可行性
  - 实测：`cjk_value` 16、`jmp_buf` 256、`cjk_handler` 272、`cjk_class` 64。
  - `_setjmp(ptr, ptr)` + `llvm.frameaddress` 可正常 longjmp 回 catch。
  - 手写 `.ll` 链接运行时输出 `你好，LLVM`，退出码 0。
  - `objdump --dwarf=decodedline` 命中 `第01课你好世界.java` 第 5/7/8/9 行。
  - 运行时加入 `_Static_assert`，C 后端第 01 课复验通过。
- [x] T2 LLVM 发射器骨架 + 教程 01–08
  - `原生\后端LLVM` 落地：公共类型、类结构体、槽表、描述符、回调包装、DWARF、入口。
  - 01–08 对拍 8/8；同一源文件两次生成的 `.ll` SHA256 相同。
- [x] T3 对象模型、继承、接口、枚举、异常、GC（09–21）
  - 虚调用槽表、接口表、枚举按名、父方法调用、静态字段赋值。
  - 第 20 课 `_setjmp`/`longjmp`：`%cjk_handler` 16 字节对齐后 09–21 全过。
- [x] T4 集合、泛型、λ/流/可选值、文件、标准库（22–35）
  - 修复 Win64 `cjk_value` ABI：返回走 `sret`、形参走 `ptr align 8` 引用；回调包装同构。
  - 22–35 对拍 14/14，全量 35/35。
- [x] T5 DWARF 完整化与调试信息脚本
  - `原生\测试\检查调试信息.ps1`：`objdump --dwarf=decodedline` 命中中文文件名与真实行号，2/2。
- [x] T6 驱动与对拍收口（`-后端 llvm`、校验.ps1）
  - `校验.ps1` 新增 M6 段落：LLVM 对拍 35/35、DWARF 断言、基准；ADR 口径更新为 9 份。
- [x] T7 C vs LLVM 基准
  - `原生\测试\基准.ps1`：四项各跑 3 次取最小值，比值 1.015 / 1.010 / 1.000 / 0.971。
- [x] T8 文档、全量回归、清理
  - ADR-009、M6 spec/tasks/checklist、路线图 M6 回填。
- [ ] T9 汇总与提交询问

## T1 验证记录

```
sizeof(cjk_value)=16
offsetof(cjk_value,u)=8
sizeof(jmp_buf)=256
sizeof(cjk_handler)=272
offsetof(cjk_handler,prev)=256
offsetof(cjk_handler,exception)=264
sizeof(cjk_class)=64
offsetof(cjk_class,to_string)=56

target datalayout = "e-m:w-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-w64-windows-gnu"
%3 = call i32 @_setjmp(ptr noundef @buf, ptr noundef %2) #3

你好，LLVM
退出码=0

已捕获
退出码=0

CU: 第01课你好世界.java:
第01课你好世界.java   5   0x1400014e0
第01课你好世界.java   7   0x1400014ef
第01课你好世界.java   8   0x140001500
第01课你好世界.java   9   0x140001519
```
