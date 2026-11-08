# 第 12 批优化 · 任务清单

状态：全部完成

- [x] 12.1 `renderTranslated` 瘦身（86 → 42 行）
  - 新增 `renderSugared` / `isEntrySugar`
  - 验证：47 文件转译 `差异=0`
- [x] 12.2 `translateFragment` 瘦身（58 → 24 行）
  - 新增 `fragmentStaticCall`（`FragmentCall`）、`appendFragmentToken`、`isFragmentMemberCall`、
    `isAfterDot` / `isBeforeDot`、`appendKeywordPad`
  - 验证：47 文件转译 `差异=0`；字符串插值示例通过
- [x] 12.3 `planTypeScopes` 瘦身（53 → 45 行）
  - 新增 `typeDeclarationKind` / `popTypeScope`
  - 验证：记录、密封类、接口与泛型用例通过
- [x] 12.4 收尾
  - `.\工具\发布\校验.ps1 -含原生` → `全部校验通过。` EXIT=0
  - `.\工具\对话\运行.ps1 示例\脚本示例.java` → EXIT=0
  - 提交代码与文档

## 提交记录

1. `第12批优化：渲染、片段转译与类型作用域拆分`
2. `第12批优化：补写规格与任务清单`

## 遗留

- `Cjc.java` 总行数 3768（较上批 +55），来自新方法签名、注释与两个 record。
