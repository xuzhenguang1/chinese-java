# 第23批优化任务清单

规格见 [spec.md](spec.md)。

## 步骤

- [x] T0 通读 5 个对话模板与 VSCode 扩展，确认模板已无英文，片段仍有 5 处英文/旧写法。
- [x] T1 `主方法` 片段改为 `入口 { … }`。
- [x] T2 `对于` 片段变量 `i` → `序号`。
- [x] T3 新增 `遍历` 片段（`对于 (项 : 名单)`）。
- [x] T4 `尝试` 片段 `Exception` → `输入输出异常` + `出错`。
- [x] T5 `接口` 片段 `String` → `字符串`。
- [x] T6 `导入` 片段 `java.util.List` → `中文.标准库.*`。
- [x] T7 校验 JSON：21 条片段，正文无英文残留。
- [x] T8 教程 8.1 版本号 0.12.0 → 0.14.1（3 处）；8.2 片段表补 `遍历` 与说明。
- [x] T9 重建 `chinese-java-0.14.1.vsix`，并解包核对片段内容。
- [x] T10 `.\工具\发布\校验.ps1 -含原生`：`全部校验通过。`，`EXIT=0`。
- [x] T11 写 spec/tasks，提交，汇报，不推送。

---

## 实测记录

### 片段校验

```text
片段数=21
片段正文无英文残留
```

### vsix 内容核对

```text
含遍历=True
含Exception=False
含java.util.List=False
含引言入口=True
DONE  Packaged: E:\xiadujava\工具\vscode-中文java\chinese-java-0.14.1.vsix (7 files, 12.96 KB)
```

### 全量校验

```text
版本一致：语言 v1.6 / 发布 1.6.2 / 扩展 0.14.1 / Maven 插件 0.2.0
检查通过：58 个文件（教程 / 示例 / 标准库）
模板库：检查通过 5 个文件；编译成功
VSCode 扩展：extension.js 语法通过
全部校验通过。
EXIT=0
```
