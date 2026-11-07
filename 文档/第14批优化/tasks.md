# 第 14 批优化 · 任务清单

状态：全部完成

- [x] 14.1 `runExecution` 拆出 `compileAndRun` / `buildRunCommand` / `waitForExit`（44 → 13 行）
  - 验证：47 文件转译 `差异=0`；`运行.ps1 示例\脚本示例.java` 输出与退出码不变
- [x] 14.2 `localizeRuntimeLine` 拆出 `runtimeHeaderLine` / `causedByLine` / `stackFrameLine`（44 → 14 行）
  - 验证：47 文件转译 `差异=0`；运行时异常中文化检查通过
- [x] 14.3 `suggestionFor` 改箭头 `switch` + `endPunctSuggestion`（44 → 15 行）
  - 验证：诊断反例 12 条仍全部命中中文输出
- [x] 14.4 `checkLoopBound` 循环体移入 `inspectLoopBound`（44 → 22 行）
  - 验证：新手易错提示（CJK101–105）通过；47 文件转译 `差异=0`
- [x] 14.5 `renderTranslated` 单 token 处理移入 `renderToken`（42 → 17 行）
  - 验证：47 文件转译 `差异=0`
- [x] 14.6 `compile` 拆出 `writeTempSources` / `runCompiler`（40 → 18 行）
  - 验证：`检查 --含编译` 与模板库编译、原生补丁编译全部通过
- [x] 14.7 `localizeJavacMessage` 拆出 `symbolDiagnostic` / `symbolFromDetail`（42 → 24 行）
  - 验证：诊断反例 12 条通过
- [x] 14.8 `printDiagnostics` 拆出 `diagnosticLabel` / `printPlainDiagnostic` / `printLocatedDiagnostic`（43 → 24 行）
  - 验证：诊断反例 12 条仍全部命中中文输出，位置行与脱字符格式不变
- [x] 14.9 收尾
  - `.\工具\发布\校验.ps1 -含原生` → `全部校验通过。` EXIT=0
  - `.\工具\对话\运行.ps1 示例\脚本示例.java` → EXIT=0
  - 清理 `构建\第14批` 临时脚本与对照目录
  - 提交代码与文档

## 提交记录

1. `第14批优化：运行、诊断与编译流程拆分`
2. `第14批优化：补写规格与任务清单`

## 遗留

- `Cjc.java` 总行数 3884（较上批 +54），来自新方法签名与中文注释。
- 已无 ≥ 40 行的静态方法；下一批可关注 30–39 行区间：`renderSugared` 39、`splitInterpolation` 38、`checkSticky` 36。
