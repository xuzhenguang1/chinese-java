# 第 2 批优化任务清单

- [x] T1（2.1）`Cjc.java`：新增 `isElseIfSugar`；`checkSticky` 放行；`translate` 输出 `else if`
- [x] T2（2.2）`Cjc.java`：新增 `checkLoopBound`（CJK104）并接入 `checkBeginnerTraps`
- [x] T3（2.3）`Cjc.java`：新增 `intVariables` / `checkIntegerDivision`（CJK105）；`reportTrap` 返回 boolean 并支持 `忽略提示`
- [x] T4（2.3）`教程\第03课运算与字符串.java`：第 9 行补 `// 忽略提示 CJK105`
- [x] T5（2.4）`校验.ps1`：新手提示补 CJK104/105 断言
- [x] T6（2.4）`校验.ps1`：新增 2.10 `否则若` 正向用例
- [x] T7（2.5）运行时复核 `并发修改异常` / `栈溢出错误`（不改代码，除非复核失败）
- [x] T8 文档同步：`语言规范.md`、`速查表.md`、`教程\教程.md`、`AGENTS.md`、`CHANGELOG.md`
- [x] T9 真实回归：`.\cjc.ps1 检查 示例`、`.\工具\对话\运行.ps1 示例\脚本示例.java`、`.\工具\发布\校验.ps1 -含原生`
