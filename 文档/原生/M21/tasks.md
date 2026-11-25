# M21 任务清单

| 任务 | 内容 | 状态 | 证据 |
|---|---|---|---|
| T0 | ADR-024（G7/G8/G9 决策固化）+ 修订 ADR-010/019 旧口径 | 完成 | `3e76e5b`；ADR 23→24 |
| T1.1 | `cjk_platform.h` 三平台互斥宏（darwin/linux/windows） | 完成 | 宏探针三档全绿 |
| T1.2 | `cjk_platform.c` POSIX 分支核验（darwin 共用 linux） | 完成 | musl 头编译 darwin/linux 双宏通过；`pthread_getattr_np` MinGW 缺失属工具链差异 |
| T1.3 | LLVM `darwin()` 工厂 + 前端 `CJC_PLATFORM=darwin` 分支 | 完成 | clang 23.1.5 探针实测 datalayout `e-m:o...` / triple `x86_64-apple-macosx10.4.0` |
| T1.4 | 驱动 `-平台 darwin` 全链路（编译/链接/缓存/状态/CJC_PLATFORM） | 完成 | `检查` 动作输出 `.macos`/Mach-O 提示；PE/ELF 零回退 |
| T1.5 | CI macos job + Mach-O 对拍/DWARF 脚本 | 完成 | `.github/workflows/校验.yml` macho job；本机 G7 口径退出 0 |
| T1.6 | Mach-O 本机静态断言脚本 | 完成 | `静态断言MachO.ps1`：宏互斥 + IR 三常量 + IR 语法，全绿 |
| T2.1 | semver 版本/范围解析器（数值比较） | 完成 | `依赖清单.java` 内 `版本`/`范围` 类 |
| T2.2 | 本地 registry 目录 + semver 求解接入「库」模式 | 完成 | `^1.2`→1.3.0；M15 路径模式零回退 |
| T2.3 | 远程 HTTP 下载器（超时/SHA256/缓存） | 完成 | 本地 HTTP 实测：下载 + 校验 + 缓存 + 断网复现；中文路径 percent 编码修复 |
| T2.4 | 两类中文诊断（范围无解/下载失败·校验和不符） | 完成 | exit 2 + `CJC_ERR_FILE` UTF-8 输出协议 |
| T2.5 | registry 样例 + 缓存反向用例 | 完成 | `原生/测试/依赖样例/仓库/`；`对拍包管理Registry.ps1` 11/11 |
| T3.1 | language server（stdio，四项能力） | 完成 | `language-server.js`；`test-lsp.js` 四项自测全绿 |
| T3.2 | VSCode LSP client + JSON 回退 + vsix 重建 | 完成 | `lsp-client.js`；extension.js 默认 lsp；vsix 0.15.0 |
| T4.1 | PE/ELF 四路对拍零回退 | 完成 | 全量校验 8.0–8.20 通过 |
| T4.2 | 校验 8.20 + ADR 计数 24 | 完成 | `校验.ps1` 8.20 节 |
| T4.3 | M21 文档四件套 + 路线图/CHANGELOG | 完成 | 本目录 + 路线图/CHANGELOG |
| T4.4 | 提交并推送 | 完成 | 阶段 0–3 提交 `3e76e5b`/`95268bc`/`2035463`/`f0dc908`；收口提交与推送见下方 |
