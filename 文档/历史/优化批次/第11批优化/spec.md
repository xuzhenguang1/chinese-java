# 第 11 批优化 · 规格与结果

状态：已完成（随下一版本发布前提交）

## 1. 目标

本批只做**结构重构**，不改变任何转译/检查/运行行为：

1. 拆分 `工具\cjc\Cjc.java` 中仍然偏大的词法扫描、插值与翻译计划方法（目标：单个方法 ≤ 50 行）。
2. 收敛 `reportSticky` / `checkPunctuation` / `checkFoldedPunctuation` / `trapSuppressed` / `reportTrap`
   中重复的「偏移 → 行/列/行文本」计算。
3. 行为不变通过**转译结果逐文件 SHA256** 比对验证。

## 2. 护栏

基线：`构建\第10批\基线.sha256`（教程 35 + 示例 12，共 47 个文件）。

```powershell
.\cjc.ps1 转译 教程 示例 -d 构建\第11批\当前
# 逐个文件与 基线.sha256 比对
```

注意：基线生成时**未**带 `--允许推断`；带该开关时 `示例\推断示例.java` 会按预期展开 `var`，不属于回归。

## 3. 变更明细

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 11.1 | `scan` 拆为「循环 + `scanAt`」；注释、区间 token、标识符、标点各自成为小方法（`skipComment` / `addRangeToken` / `scanIdentifier` / `scanFoldedPunct` / `addBadPunct`） | 89 → 9 行；`scanAt` 49 行 |
| 11.2 | 新增 `SourcePos` record 与 `linePosition`，替换 5 处重复的行/列计算 | 各报告函数减少 3–5 行，输出文案逐字不变 |
| 11.3 | `appendInterpolated` 拆为 `splitInterpolation` + `appendInterpolationParts`，新增 `Interpolation` record | 72 → 20 行；子方法 39 / 23 行 |
| 11.4 | `planCalls` 拆为 `planStaticCalls` / `planMemberCalls` / `planMethodDecls` | 64 → 5 行；子方法 ≤ 25 |
| 11.5 | `planGenerics` 拆为 `planWrapGenerics` / `planArrayListFrom` + `findMatchingParen`，新增 `ArrayListPlan` | 64 → 6 行；子方法 ≤ 25 |
| 11.6 | `planPrintArgs` 拆为 `scanPrintArgs` / `markPrintArgs`，新增 `PrintArgs` record | 62 → 25 行；子方法 ≤ 22 |
| 11.7 | `planRepeat` 拆出 `isStatementStart` / `findRepeatMarker` / `repeatHeaderEnd` | 59 → 31 行 |
| 11.8 | 全量 `校验.ps1 -含原生` + 文档归档 | 通过 |

## 4. 行数

| 方法 | 改动前 | 改动后 | 说明 |
| --- | --- | --- | --- |
| `scan` | 89 | 9 | 主体移至 `scanAt`（49） |
| `appendInterpolated` | 72 | 20 | `splitInterpolation` 39 + `appendInterpolationParts` 23 |
| `planCalls` | 64 | 5 | 三个计划器 ≤ 25 |
| `planGenerics` | 64 | 6 | `planWrapGenerics`、`planArrayListFrom` |
| `planPrintArgs` | 62 | 25 | `scanPrintArgs`、`markPrintArgs` |
| `planRepeat` | 59 | 31 | 三个辅助方法 ≤ 20 |
| `Cjc.java` 总行数 | 3627 | 3713 | +86 行为拆方法签名、注释与新 record |

## 5. 真实验证输出

```text
.\cjc.ps1 检查 教程 示例
检查通过：47 个文件（关键字 121 条 + 类型别名 34 条，词典 50 组）。
另有 4 处标点已自动纠正（CJK106，仅提示，不影响编译）。
```

```text
转译基线比对（教程 35 + 示例 12）
基线条目=47 差异=0
```

```text
.\工具\发布\校验.ps1 -含原生
... 全部校验通过。
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

- 不拆分 `Cjc.java` 为多文件（单文件源码模式牵动 `cjc.ps1`、`校验.ps1`、Maven 插件）。
- 不改变任何关键字/方法糖/诊断文案；`关键字表.txt`、`方法糖.txt`、`近义词词典.txt` 未改。
- 不调整 `--允许推断` / `--严格标点` 等开关语义。
