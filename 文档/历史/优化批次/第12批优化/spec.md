# 第 12 批优化 · 规格与结果

状态：已完成

## 1. 目标

延续第 11 批，继续压缩 `工具\cjc\Cjc.java` 中的长方法（目标 ≤ 50 行），行为零变化：

1. `renderTranslated` 只保留循环骨架，语法糖分派移入 `renderSugared`。
2. `translateFragment` 只保留循环骨架，静态糖与单 token 处理各自成方法。
3. `planTypeScopes` 的类型声明识别与退栈逻辑抽成独立方法。

## 2. 护栏

基线：`构建\第10批\基线.sha256`（教程 35 + 示例 12，共 47 个文件），验证方式同第 11 批：

```powershell
.\cjc.ps1 转译 教程 示例 -d 构建\第12批\当前
# 逐个文件与 基线.sha256 比对
```

## 3. 变更明细

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 12.1 | `renderTranslated` 拆出 `renderSugared` 与 `isEntrySugar`；主循环只负责跳 token、关键字/字面量输出与收尾 | 86 → 42 行；`renderSugared` 40 行 |
| 12.2 | `translateFragment` 拆出 `fragmentStaticCall`（`FragmentCall` record）、`appendFragmentToken`、`isFragmentMemberCall`、`isAfterDot` / `isBeforeDot`、`appendKeywordPad` | 58 → 24 行；子方法 ≤ 27 |
| 12.3 | `planTypeScopes` 拆出 `typeDeclarationKind` 与 `popTypeScope` | 53 → 45 行 |
| 12.4 | 全量 `校验.ps1 -含原生` + 文档归档 | 通过 |

## 4. 行数

| 方法 | 改动前 | 改动后 |
| --- | --- | --- |
| `renderTranslated` | 86 | 42 |
| `translateFragment` | 58 | 24 |
| `planTypeScopes` | 53 | 45 |
| `Cjc.java` 总行数 | 3713 | 3768 |

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

- 不改关键字/方法糖/近义词词典与诊断文案。
- 不拆分 `Cjc.java`（JEP 330 单文件源码模式）。
- 不引入新的运行时依赖或调整命令语义。
