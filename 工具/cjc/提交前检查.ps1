# 提交前校验：检查 git 暂存区中的中文 Java 文件
#
# 注册方式（在工程根目录执行）：
#   Copy-Item 工具\cjc\提交前检查.ps1 .git\hooks\pre-commit -Force
#
# 该脚本只做检查，不修改文件；发现 CJK001/CJK002 时以非 0 退出，阻断提交。
$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path "$PSScriptRoot\..\..").Path

# core.quotepath=false：否则 git 会把中文路径转义成 "\346\265\213..."，导致路径匹配失败
$staged = & git -C $root -c core.quotepath=false diff --cached --name-only --diff-filter=ACM -- "*.java"
if ($LASTEXITCODE -ne 0) {
    Write-Host "git 不可用，跳过提交前检查"
    exit 0
}

$files = @($staged | Where-Object { $_ -and (Test-Path (Join-Path $root $_)) } | ForEach-Object { Join-Path $root $_ })
if ($files.Count -eq 0) {
    exit 0
}

& java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dcjc.home=$root" `
    "$root\工具\cjc\Cjc.java" 检查 @files
exit $LASTEXITCODE
