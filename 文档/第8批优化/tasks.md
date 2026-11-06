# 第 8 批优化 · 任务清单（Tasks）

> 配套文档：[spec.md](spec.md)。状态：已完成。

## 阶段一 · 基线与词表加载器

- [x] **8.1 文档与转译基线**
  - 结果：`构建\重构基线` 47 个文件（教程 35 + 示例 12）

- [x] **8.2 合并 4 个词表加载器**
  - 结果：新增 `TableLine` / `readTableLines` / `failTable`；四个加载器 93/96/54/45 → 81/85/47/34 行；提示语与退出码不变

## 阶段二 · 大方法拆分

- [x] **8.3 拆 `main`**
  - 结果：`main` 173 → 125 行；新增 `Options` / `parseOptions`（27 行）/ `optionValue`

- [x] **8.4 拆 `planSugar`**
  - 结果：`planSugar` 188 → 35 行；拆出 `planInference` / `planForVar` / `planRepeat` / `planLength` / `planElseIf` / `planSystem` / `planArraycopy`

- [x] **8.5 拆 `renderTranslated`**
  - 结果：`renderTranslated` 233 → 184 行；新增 `emit` / `emitToken` / `recordReplacements`

- [x] **8.6 拆 `scan`**
  - 结果：`scan` 164 → 89 行；拆出 `identifierKind` / `skipLineComment` / `skipBlockComment` / `skipTextBlock` / `skipLiteral` / `skipNumber` / `foldSentencePunctuation`

## 阶段三 · 回归

- [x] **8.7 全量回归与提交**
  - 转译基线 diff：47 个文件哈希一致，不一致数 0
  - `.\cjc.ps1 检查 教程 示例 --允许推断 --含编译`：检查通过（47 个文件）
  - `.\工具\发布\校验.ps1 -含原生`：全部校验通过，退出码 0
  - `git status --short`：已提交

## 完成定义

1. 8.1–8.7 每项都有真实输出。
2. 重构前后转译结果逐文件一致。
3. 词表计数与方法糖四区计数不变。
4. `.\工具\发布\校验.ps1 -含原生` 退出码 0。
