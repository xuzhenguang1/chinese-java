# M21 验收清单

## Mach-O（G7）

- [x] `cjk_platform.h` 三平台宏互斥，恰有一个为 1；darwin/linux 共用 POSIX 路径。
- [x] darwin IR 三常量：datalayout `e-m:o...`、triple `x86_64-apple-macosx10.4.0`、personality `__gxx_personality_v0`。
- [x] datalayout/triple 由 clang 23.1.5 交叉编译探针实测（非推测）；jmp_buf 字段注明 CI 实跑待环境。
- [x] 驱动 `-平台 darwin` 配置桶齐备（扩展名/编译/链接/缓存分桶/状态文件/CJC_PLATFORM）。
- [x] CI macos-latest job 接线 `校验MachO全量.ps1`；DWARF 用 `llvm-dwarfdump`（CJK_DWARFDUMP）。
- [x] 本机 G7 口径：静态断言通过，真机对拍标注「CI 实跑待环境」，不虚报全绿。
- [x] Windows PE 默认路径零回退（第01课编译运行输出正确）。

## registry/semver（G8）

- [x] semver 数值序求解：`^1.2`→1.3.0、`~1.2`→1.2.0、`1.x`→1.3.0、`1.2.x`→1.2.0、精确→指定版本。
- [x] M15 `路径` 精确版本模式零回退（正常/版本冲突/循环依赖样例全通过）。
- [x] 范围无解中文诊断（含可用版本上下文）退出码 2。
- [x] 远程下载 + SHA256 校验 + 缓存落地（本地 HTTP 服务实测）。
- [x] 缓存命中离线可复现（URL 指向不可达端口仍解析成功）。
- [x] 校验和不符中文诊断（含 URL/期望/实际）退出码 2。
- [x] 缓存反向用例：改版本范围 → 聚合哈希变化 → 必重编。
- [x] 中文包名 percent-encode 修复（远程下载 URL 编码）。

## 完整 LSP（G9）

- [x] language server 四项能力：completion / hover / definition / publishDiagnostics。
- [x] 复用单一数据源（关键字数据.json / 补全数据.json）与前端 `诊断 --json`，不重复维护词表。
- [x] VSCode 配置默认 `lsp`；LSP 不可用自动回退「原生/兼容」JSON 链路。
- [x] VSCode client 用原生 API，零 npm 运行时依赖。
- [x] `test-lsp.js` 四项自测全绿；vsix 0.15.0 重建（test-lsp.js 已排除）。

## 收口

- [x] M21 文档四件套（spec/tasks/checklist/对拍报告）齐备。
- [x] `校验.ps1` 新增 8.20，ADR 计数 23→24。
- [x] 路线图 / CHANGELOG 补段。
- [x] 全量 `校验.ps1 -含原生` 退出码 0。
- [x] 提交并推送。
