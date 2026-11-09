# 重新生成 M1 AST 黄金快照：调用 原生\驱动.ps1，不手写任何快照内容。
# 用法：.\原生\测试\生成语法树快照.ps1 [-输出目录 <目录>] [-路径 <文件或目录...>]
param(
    [string]$输出目录 = (Join-Path $PSScriptRoot "语法树快照"),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
$驱动 = Join-Path $根目录 "原生\驱动.ps1"

if (-not $路径 -or $路径.Count -eq 0) {
    $路径 = @((Join-Path $根目录 "教程"))
}

& $驱动 快照 $输出目录 @路径
exit $LASTEXITCODE
