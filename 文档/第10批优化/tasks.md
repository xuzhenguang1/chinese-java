# 第 10 批优化 · 任务清单

状态：全部完成

- [x] 10.1 统一致命错误出口 `fail()`（15 处）
  - 验证：`.\cjc.ps1 检查 示例` → `检查通过：12 个文件`
  - 验证：转译基线 `差异=0`
- [x] 10.2 拆分 `main`（127 → 33 行）
  - 新增 `validateOptions` / `loadSources` / `runChecks` / `reportCheck` / `runTranslateOrCompile`
  - 新增 `LoadedSources`、`CheckCounts` 两个 record
  - 验证：`检查 教程 示例` 通过；47 文件转译 `差异=0`；`校验.ps1` EXIT=0
- [x] 10.3 拆分 `planVoidAndPrint`（134 → 8 行）
  - 新增 `planTypeScopes` / `planVoid` / `planPrint` 与 `TypeScope`
  - 验证：47 文件转译 `差异=0`
- [x] 10.4 拆分 `renderTranslated`（186 → 87 行）
  - 新增 `renderRepeat` / `renderArrayListFrom` / `renderKeyword` / `renderLiteral` / `renderInserted` / `renderRenamed` / `appendPendingClosers`
  - 验证：47 文件转译 `差异=0`；`检查 --含编译 --允许推断` EXIT=0
- [x] 10.5 `prevText` → `textAt`，新增 `textAt(SourceFile, List<Token>, int)` 重载
  - 替换 9 处手写 `t.text != null ? …` 回退
  - 验证：47 文件转译 `差异=0`
- [x] 10.6 拆分词表加载器
  - `loadKeywords` → `parseKeywordLine` / `parseKeywordList` / `buildKeywordIndexes`（87 → 26 行）
  - `loadMethodSugar` → `parseSugarLine` + `SugarTables`（86 → 30 行）
  - 验证：`检查 教程 示例 --含编译 --允许推断` EXIT=0；词表计数仍为 `56 / 121 / 34 / 50`
- [x] 10.7 拆分 `checkLoopBound`（69 → 39 行）
  - 新增 `findLoopClose` / `nextComparisonEnd` / `describeBound`
  - 验证：`新手易错提示（CJK101–105）：通过`
- [x] 10.8 精简 `校验.ps1`（690 → 619 行）
  - 新增 `失败项` / `新建临时目录` / `捕获输出`
  - 验证：`校验.ps1` 全部通过，EXIT=0
- [x] 10.9 收尾
  - `.\工具\发布\校验.ps1 -含原生` → `全部校验通过。` EXIT=0
  - 提交：第 10 批重构 + 文档

## 提交记录

1. `第10批优化 10.1–10.4：拆分 Cjc 主流程与转译渲染`
2. `补充 VSCode 扩展的 repository 字段`
3. `第10批优化 10.5–10.8：文本取值重载、词表加载器与校验脚本精简`

## 遗留

- `Cjc.java` 总行数 3627（较基线 +91），超出计划「≤ +40」；原因见 `spec.md` 第 4 节，方法规模目标已全部达成。
