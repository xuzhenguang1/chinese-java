# 原生实现驱动（M0）
# 对外入口：.\cjc.ps1 原生 检查 [路径...]
# M0 阶段只做词表桥接、源码统计与工具链探测，不产出可执行文件。
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$参数
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$根目录 = (Resolve-Path "$PSScriptRoot\..").Path
$桥接 = Join-Path $PSScriptRoot "工具\词表桥接.ps1"

function 显示用法 {
    Write-Output "用法：.\cjc.ps1 原生 检查 [路径...]"
    Write-Output "      M0 阶段只做词表桥接与源码统计，不产出可执行文件。"
}

if ($参数.Count -eq 0) {
    显示用法
    exit 2
}

$子命令 = $参数[0]
if ($子命令 -in @('-h', '--帮助', '帮助')) {
    显示用法
    exit 0
}
if ($子命令 -ne '检查') {
    Write-Output "未知原生子命令：$子命令"
    显示用法
    exit 2
}

$路径列表 = @()
if ($参数.Count -gt 1) {
    $路径列表 = $参数[1..($参数.Count - 1)]
}
if ($路径列表.Count -eq 0) {
    $路径列表 = @((Join-Path $根目录 "教程"))
}

Write-Output "原生驱动（M0）：检查"

& $桥接 -根目录 $根目录
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$统计 = @()
foreach ($路径 in $路径列表) {
    $完整 = $路径
    if (-not [System.IO.Path]::IsPathRooted($完整)) {
        $完整 = Join-Path (Get-Location).Path $完整
    }
    if (-not (Test-Path $完整)) {
        Write-Output "路径不存在：$路径"
        exit 2
    }
    $条目 = Get-Item $完整
    if ($条目.PSIsContainer) {
        $文件 = @(Get-ChildItem $完整 -Recurse -Filter *.java -File | Sort-Object FullName)
    } else {
        $文件 = @($条目)
    }
    $行数 = 0
    foreach ($文件项 in $文件) {
        $行数 += (Get-Content $文件项.FullName -Encoding UTF8 | Measure-Object -Line).Lines
    }
    $显示 = $完整
    if ($显示.StartsWith($根目录)) {
        $显示 = $显示.Substring($根目录.Length).TrimStart('\')
    }
    $统计 += [pscustomobject]@{ 路径 = $显示; 文件数 = $文件.Count; 行数 = $行数 }
}

Write-Output "源码统计："
foreach ($项 in $统计) {
    Write-Output ("  {0}：{1} 个 .java 文件 / {2} 行" -f $项.路径, $项.文件数, $项.行数)
}

$clang = $null
foreach ($候选 in @("C:\msys64\mingw64\bin\clang.exe")) {
    if (Test-Path $候选) {
        $clang = $候选
        break
    }
}
if (-not $clang) {
    $命令 = Get-Command clang -ErrorAction SilentlyContinue
    if ($命令) {
        $clang = $命令.Source
    }
}

Write-Output "工具链："
if ($clang) {
    $版本 = (& $clang --version 2>&1 | Select-Object -First 1)
    Write-Output ("  clang：{0}（{1}）" -f $版本, $clang)
} else {
    Write-Output "  clang：未找到；M3 起需要 C 后端，请安装 MSYS2 mingw-w64-x86_64-clang 并把 C:\msys64\mingw64\bin 加入 PATH。"
}

Write-Output "检查完成：M0 只做词表桥接与统计，尚未产出可执行文件。"
