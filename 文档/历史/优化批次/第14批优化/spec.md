# 第 14 批优化 · 规格与结果

状态：已完成

## 1. 目标

延续第 11–13 批，继续压缩 `工具\cjc\Cjc.java` 中的长方法（本批目标：主方法 ≤ 25 行，子方法 ≤ 35 行），
行为零变化：

1. `runExecution` 只保留入口确定、临时目录与退出码收尾，编译运行、命令行组装、等待子进程各自成方法。
2. `localizeRuntimeLine` 只做三类行的分派，异常头 / `Caused by:` / 栈帧的格式化各自成方法。
3. `suggestionFor` 改用箭头 `switch` 合并同义标点分组，顿号与句号的分支移入 `endPunctSuggestion`。
4. `checkLoopBound` 的循环体移入 `inspectLoopBound`，主方法只保留循环骨架。
5. `renderTranslated` 的单 token 渲染移入 `renderToken`，cursor 作为参数传递。
6. `compile` 拆出临时源码写入 `writeTempSources` 与 `runCompiler`，主方法只留流程与返回值。
7. `localizeJavacMessage` 把「找不到符号」两类特例移入 `symbolDiagnostic` / `symbolFromDetail`。
8. 顺带清掉遗漏的长方法 `printDiagnostics`：级别标签、无位置输出、带位置输出各自成方法。

## 2. 护栏

基线：`构建\第10批\基线.sha256`（教程 35 + 示例 12，共 47 个文件），验证方式同第 11–13 批：

```powershell
.\cjc.ps1 转译 教程 示例 -d 构建\第14批\当前
# 逐个文件与 基线.sha256 比对（不带 --允许推断）
```

每完成一组拆分即跑一次检查与基线比对，要求 47 项全部 `差异=0 缺失=0`。

## 3. 变更明细

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 14.1 | `runExecution` 拆出 `compileAndRun` / `buildRunCommand` / `waitForExit` | 44 → 13 行；子方法 15 / 14 / 13 行 |
| 14.2 | `localizeRuntimeLine` 拆出 `runtimeHeaderLine` / `causedByLine` / `stackFrameLine` | 44 → 14 行；子方法 18 / 9 / 11 行 |
| 14.3 | `suggestionFor` 改箭头 `switch`，顿号/句号移入 `endPunctSuggestion` | 44 → 15 行；子方法 6 行 |
| 14.4 | `checkLoopBound` 的循环体移入 `inspectLoopBound` | 44 → 22 行；子方法 24 行 |
| 14.5 | `renderTranslated` 的单 token 处理移入 `renderToken` | 42 → 17 行；子方法 25 行 |
| 14.6 | `compile` 拆出 `writeTempSources` / `runCompiler` | 40 → 18 行；子方法 10 / 18 行 |
| 14.7 | `localizeJavacMessage` 拆出 `symbolDiagnostic` / `symbolFromDetail` | 42 → 24 行；子方法 13 / 7 行 |
| 14.8 | `printDiagnostics` 拆出 `diagnosticLabel` / `printPlainDiagnostic` / `printLocatedDiagnostic` | 43 → 24 行；子方法 7 / 6 / 16 行 |

## 4. 行数

| 方法 | 改动前 | 改动后 |
| --- | --- | --- |
| `runExecution` | 44 | 13 |
| `localizeRuntimeLine` | 44 | 14 |
| `suggestionFor` | 44 | 15 |
| `checkLoopBound` | 44 | 22 |
| `renderTranslated` | 42 | 17 |
| `compile` | 40 | 18 |
| `localizeJavacMessage` | 42 | 24 |
| `printDiagnostics` | 43 | 24 |
| `Cjc.java` 总行数 | 3830 | 3884 |

全部 `static` 方法中已无 ≥ 40 行者；剩余最长三个是 `renderSugared` 39、`splitInterpolation` 38、`checkSticky` 36。

## 5. 真实验证输出

```text
.\cjc.ps1 检查 教程 示例
检查通过：47 个文件（关键字 121 条 + 类型别名 34 条，词典 50 组）。
另有 4 处标点已自动纠正（CJK106，仅提示，不影响编译）。
```

```text
转译基线比对（教程 35 + 示例 12）
基线项：47 差异=0 缺失=0
基线比对通过
```

```text
.\工具\发布\校验.ps1 -含原生
诊断反例：12 条全部命中中文输出
全部校验通过。
EXIT=0
```

```text
.\工具\对话\运行.ps1 示例\脚本示例.java
你好，脚本模式
小明 今年 18 岁
第 1 行
第 2 行
第 3 行
EXIT=0
```

## 6. 不做的事

- 不改关键字表、方法糖表、近义词典与诊断文案。
- 不拆分 `Cjc.java`（JEP 330 单文件源码模式）。
- 不改变任何命令语义与输出文本。
