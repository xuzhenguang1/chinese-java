# 第 11 批优化 · 任务清单

状态：全部完成

- [x] 11.1 拆分 `scan`（89 → 9 行）
  - 新增 `scanAt` / `skipComment` / `addRangeToken` / `scanIdentifier` / `scanFoldedPunct` / `addBadPunct`
  - 验证：47 文件转译 `差异=0`
- [x] 11.2 诊断位置统一
  - 新增 `SourcePos` record 与 `linePosition`，替换 5 处重复的行/列/行文本计算
  - 验证：47 文件转译 `差异=0`；`校验.ps1` 诊断反例 12 条全部命中
- [x] 11.3 拆分 `appendInterpolated`（72 → 20 行）
  - 新增 `splitInterpolation` / `appendInterpolationParts` 与 `Interpolation`
  - 验证：47 文件转译 `差异=0`；字符串插值示例通过
- [x] 11.4 拆分翻译计划器
  - `planCalls` → `planStaticCalls` / `planMemberCalls` / `planMethodDecls`
  - `planGenerics` → `planWrapGenerics` / `planArrayListFrom` / `findMatchingParen`
  - `planPrintArgs` → `scanPrintArgs` / `markPrintArgs`
  - `planRepeat` → `isStatementStart` / `findRepeatMarker` / `repeatHeaderEnd`
  - 验证：47 文件转译 `差异=0`；`数组清单.从`、计数循环、打印多参数用例通过
- [x] 11.5 收尾
  - `.\工具\发布\校验.ps1 -含原生` → `全部校验通过。` EXIT=0
  - `.\工具\对话\运行.ps1 示例\脚本示例.java` → EXIT=0
  - 提交：第 11 批重构 + 文档

## 提交记录

1. `第11批优化 11.1–11.2：词法扫描拆分与诊断位置统一`
2. `第11批优化 11.3–11.4：插值与翻译计划器拆分`

## 遗留

- `Cjc.java` 总行数 3713（较上批 +86），增幅来自拆方法签名、注释与四个新 record；方法规模目标全部达成。
