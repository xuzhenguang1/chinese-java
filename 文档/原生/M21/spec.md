# M21 平台与生态 — 规格

> 状态：已完成（2026-11-25）。决策依据：`文档/原生/ADR/ADR-024-M21平台与生态范围.md`、`后续路线计划书-M19至M22.md` §5/§7.3（G7/G8/G9）。

## 1. 目标

1. **Mach-O 平台（G7，骨架 + CI 待跑）**：平台层加 `CJK_PLATFORM_DARWIN` 三平台互斥选择；驱动 `-平台 darwin` 全链路配置桶；LLVM `darwin()` 工厂（datalayout/triple 探针实测）；CI macos-latest job 接线。本机无 macOS SDK，只做可编译静态断言，真机对拍标注「CI 实跑待环境」，不虚报全绿。
2. **registry/semver（G8，含远程下载）**：中文 DSL 依赖新增「库」模式（`依赖 <名称> 库 <包名> 版本 <范围>`），semver 范围求解（`^`/`~`/`x`/精确）；本地 registry 目录 + 远程 HTTP 下载 + SHA256 校验 + 缓存命中离线可复现；新增「范围无解」「下载失败/校验和不符」两类中文诊断。
3. **完整 LSP（G9，四项全做）**：新建 stdio language server，复用词表/补全数据单一数据源与前端 `诊断 --json`，提供诊断/补全/悬停/定义跳转；VSCode 扩展改轻量 LSP client，保留原 JSON/兼容回退；vsix 重建。

## 2. 非目标

- 不做跨架构矩阵（仅 x86_64）；不自建远程 registry 服务端；Mach-O 不在本机真机验证（无 SDK）。
- 不改 C/LLVM 后端、运行时、MIR 文本、符号编码（ADR-022/023 冻结）；不改 35 课源文件与快照基线。
- LSP 不引入新语言语义，只做既有数据的协议化出口；不替换 VSCode Java 扩展的语言模式。

## 3. 架构口径

- 三块独立提交、独立可回退：`3e76e5b`（ADR）→ `95268bc`（平台层）→ `2035463`（registry）→ `f0dc908`（LSP）→ 收口。
- 平台层：darwin 与 linux 共用 POSIX 分支（pthread/clock/argv），不复制第二份实现；Windows 分支逐字节不动。
- registry：M15 `路径` 精确版本模式零回退；新增 `库` 模式走本地 registry（`仓库/依赖库/<包名>/<版本>/`），本地无匹配时按 `CJK_REGISTRY_URL` 远程下载；`CJK_REGISTRY` 可覆盖 registry 根；缓存命中即不联网。
- LSP：server 无 vscode 依赖、无 npm 运行时依赖，纯 Node stdio 帧协议；client 用 vscode 原生 API，不引入 `vscode-languageclient`。

## 4. 验收标准

1. Mach-O：平台宏互斥断言、darwin IR 三常量（e-m:o / macOS triple / Itanium personality）、IR 语法解析通过；CI macos job 与驱动/LLVM 配置桶齐备；真机对拍标注「CI 实跑待环境」。
2. registry：`^`/`~`/`x`/精确选版正确；范围无解中文诊断 exit 2；改范围哈希变化（缓存必重编）；远程下载 + SHA256 + 缓存落地；断网缓存命中离线复现；校验和不符中文诊断 exit 2。
3. LSP：四项能力自测全绿（initialize/completion/hover/definition/diagnostics）；VSCode 配置默认 lsp，失败回退；vsix 0.15.0 重建。
4. 全量 `校验.ps1 -含原生` 新增 8.20 后退出码 0，ADR 24 份；PE/ELF 四路对拍、MIR 快照、自举不动点零回退。
