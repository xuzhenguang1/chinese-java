# 第 13 批优化 · 任务清单

状态：全部完成

- [x] 13.1 `pomTemplate` 按头部 / cjc 插件 / jar 插件拆分（50 → 9 行）
  - 新增 `pomHead` / `appendCjcPlugin` / `appendJarPlugin`
  - 验证：新旧 `新建 项目` 生成的 `pom.xml`、`主程序.java` SHA256 完全一致
- [x] 13.2a `loadDictionary` 拆出 `parseDictionaryLine` / `collectSynonyms`（47 → 11 行）
  - 验证：关键字表加载与词典校验（50 组）行为不变，47 文件转译 `差异=0`
- [x] 13.2b `parseSugarLine` 拆出 `parseSugarSection` / `parseWhitelist` / `parseSugarEntry`（46 → 18 行）
  - 验证：方法糖表加载（成员 45 / 声明 3 / 静态 47 / 白名单 10）不变
- [x] 13.3 `scanAt` 拆出 `scanOpaqueSpan`（49 → 23 行）；`planTypeScopes` 引入 `ScopeWalk` + `stepTypeScope`（45 → 26 行）
  - 验证：47 文件转译 `差异=0`
- [x] 13.4 收尾
  - `.\工具\发布\校验.ps1 -含原生` → `全部校验通过。` EXIT=0
  - `.\工具\对话\运行.ps1 示例\脚本示例.java` → EXIT=0
  - 清理 `构建\第13批` 临时脚本与对照目录
  - 提交代码与文档

## 提交记录

1. `第13批优化：POM 模板、词表解析与扫描作用域拆分`
2. `第13批优化：补写规格与任务清单`

## 遗留

- `Cjc.java` 总行数 3830（较上批 +62），来自新方法签名、`ScopeWalk` 状态类与注释。
- 仍 ≥ 40 行的方法：`localizeRuntimeLine` 44、`runExecution` 44、`checkLoopBound` 44、
  `suggestionFor` 44、`localizeJavacMessage` 42、`renderTranslated` 42、`compile` 40，可留给后续批次。
