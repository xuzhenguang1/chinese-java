# 第 13 批优化 · 规格与结果

状态：已完成

## 1. 目标

延续第 11、12 批，继续压缩 `工具\cjc\Cjc.java` 中的长方法（本批目标 ≤ 40 行），行为零变化：

1. `pomTemplate` 按「头部 / cjc 插件 / jar 插件」拆成三个输出片段，POM 字节完全一致。
2. `loadDictionary` 把逐行解析与近义词拆分抽成独立方法，加载顺序与报错文案不变。
3. `parseSugarLine` 把分区头、白名单、条目三类行各自成方法。
4. `scanAt` / `planTypeScopes` 顺带瘦身，主方法只保留分派与循环骨架。

## 2. 护栏

基线：`构建\第10批\基线.sha256`（教程 35 + 示例 12，共 47 个文件），验证方式同第 11、12 批：

```powershell
.\cjc.ps1 转译 教程 示例 -d 构建\第13批\当前
# 逐个文件与 基线.sha256 比对（不带 --允许推断）
```

POM 字节一致单独验证：用 `git archive` 取出 HEAD 版 `Cjc.java`，在两个同深度临时目录各执行
`新建 项目 演示 --表 <仓库关键字表>`，比对 `pom.xml` 与 `主程序.java` 的 SHA256。

## 3. 变更明细

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 13.1 | `pomTemplate` 拆出 `pomHead` / `appendCjcPlugin` / `appendJarPlugin`，主方法只拼接尾部 | 50 → 9 行；子方法 20 / 18 / 14 行 |
| 13.2a | `loadDictionary` 拆出 `parseDictionaryLine` 与 `collectSynonyms` | 47 → 11 行；子方法 26 / 21 行 |
| 13.2b | `parseSugarLine` 拆出 `parseSugarSection` / `parseWhitelist` / `parseSugarEntry` | 46 → 18 行；子方法 12 / 9 / 20 行 |
| 13.3 | `scanAt` 把空白/注释/字面量/标识符/数字整段跳过的分支移入 `scanOpaqueSpan`；`planTypeScopes` 引入 `ScopeWalk` 状态与 `stepTypeScope` | 49 → 23 行（子方法 32）；45 → 26 行（子方法 16） |
| 13.4 | 全量 `校验.ps1 -含原生` + 文档归档 | 通过 |

## 4. 行数

| 方法 | 改动前 | 改动后 |
| --- | --- | --- |
| `pomTemplate` | 50 | 9 |
| `loadDictionary` | 47 | 11 |
| `parseSugarLine` | 46 | 18 |
| `scanAt` | 49 | 23 |
| `planTypeScopes` | 45 | 26 |
| `Cjc.java` 总行数 | 3768 | 3830 |

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
新建项目脚手架字节比对（HEAD 版 Cjc vs 本批 Cjc）
旧pom 6AF63AC459FC0C504B16E4937950F938FA4B501C1B4DCF5FE62C1E69EC2537DB
新pom 6AF63AC459FC0C504B16E4937950F938FA4B501C1B4DCF5FE62C1E69EC2537DB
旧源 5864C8501D9B1F985A5AD8666A8516C2FC3D477EF5A252C74F2942B089886517
新源 5864C8501D9B1F985A5AD8666A8516C2FC3D477EF5A252C74F2942B089886517
工程骨架字节一致
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
