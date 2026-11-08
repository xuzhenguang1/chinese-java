# 第 10 批优化 · 规格与结果

状态：已完成（随下一版本发布前提交）

## 1. 目标

本批只做**结构重构**，不改变任何转译/检查/运行行为：

1. `工具\cjc\Cjc.java` 中最大的几个方法降到可读范围（目标：全部 ≤ 100 行，`main` ≤ 35 行）。
2. 收敛重复的错误出口与文本取值写法。
3. `工具\发布\校验.ps1` 从 690 行压缩到 ≤ 620 行，消除临时目录与失败断言的重复块。
4. 行为不变通过**转译结果逐文件 SHA256** 比对验证。

## 2. 护栏

基线：`构建\第10批\基线.sha256`（教程 35 + 示例 12，共 47 个文件）。

```powershell
.\cjc.ps1 转译 教程 示例 -d 构建\第10批\当前
# 逐个文件与 基线.sha256 比对
```

注意：基线生成时**未**带 `--允许推断`；带该开关时 `示例\推断示例.java` 会按预期展开 `var`，不属于回归。

## 3. 变更明细

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 10.1 | 新增 `fail(String)`，替换 15 处「`System.err.println` + `System.exit(2)`」 | 致命错误出口统一 |
| 10.2 | `main` 拆为 `validateOptions` / `loadSources` / `runChecks` / `reportCheck` / `runTranslateOrCompile`；新增 `LoadedSources`、`CheckCounts` 两个 record | `main` 127 → 33 行 |
| 10.3 | `planVoidAndPrint` 拆为 `planTypeScopes` / `planVoid` / `planPrint`；新增 `TypeScope` | 134 → 8 行，子方法均 ≤ 60 |
| 10.4 | `renderTranslated` 拆出 `renderRepeat` / `renderArrayListFrom` / `renderKeyword` / `renderLiteral` / `renderInserted` / `renderRenamed` / `appendPendingClosers` | 186 → 87 行 |
| 10.5 | `prevText` 更名 `textAt`；新增 `textAt(SourceFile, List<Token>, int)` 越界安全重载，替换 9 处手写 `t.text != null ? …` 回退 | 调用点统一 |
| 10.6 | `loadKeywords` 拆为 `parseKeywordLine` / `parseKeywordList` / `buildKeywordIndexes`；`loadMethodSugar` 拆为 `parseSugarLine` + `SugarTables` 状态对象 | 87/86 → 26/30 行 |
| 10.7 | `checkLoopBound` 拆为 `findLoopClose` / `nextComparisonEnd` / `describeBound` | 69 → 39 行 |
| 10.8 | `校验.ps1` 新增 `失败项` / `新建临时目录` / `捕获输出`，替换 10 处临时目录块、15 处失败断言块、5 处 `$ErrorActionPreference` 包裹 | 690 → 619 行 |
| 10.9 | 全量 `校验.ps1 -含原生` + 文档归档 | 通过 |

## 4. 行数

| 文件 | 改动前 | 改动后 | 说明 |
| --- | --- | --- | --- |
| `工具\cjc\Cjc.java` | 3536 | 3627 | 最大方法 `renderTranslated` 87 行 |
| `工具\发布\校验.ps1` | 690 | 619 | 达到 ≤ 620 |

`Cjc.java` 比计划中的「不增超过 40 行」多增了 51 行：多出来的行来自拆方法时新增的签名、注释与两个 record/状态对象（`LoadedSources`、`CheckCounts`、`TypeScope`、`SugarTables`）。没有删除任何既有注释或校验逻辑；方法规模与可读性目标全部达成。

## 5. 真实验证输出

```text
.\cjc.ps1 检查 教程 示例
检查通过：58 个文件（关键字 121 条 + 类型别名 34 条，词典 50 组）。
另有 4 处标点已自动纠正（CJK106，仅提示，不影响编译）。
```

```text
转译基线比对（教程 35 + 示例 12）
基线=47 差异=0
```

```text
.\工具\发布\校验.ps1 -含原生
... 全部校验通过。
EXIT=0
```

## 6. 不做的事

- 不拆分 `Cjc.java` 为多文件（单文件源码模式牵动 `cjc.ps1`、`校验.ps1`、Maven 插件）。
- 不改变任何关键字/方法糖/诊断文案；`关键字表.txt`、`方法糖.txt`、`近义词词典.txt` 未改。
- 不调整 `--允许推断` / `--严格标点` 等开关语义。
