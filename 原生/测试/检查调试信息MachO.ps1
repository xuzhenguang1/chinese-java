# M21 DWARF 断言（macOS Mach-O）：验证 LLVM 后端 Mach-O 产物含中文源文件名与行号（G7）。
# 与 PE 版 检查调试信息.ps1 / ELF 版 检查调试信息Linux.ps1 口径一致；
# Mach-O 用 llvm-dwarfdump（--debug-line / --debug-info）替代 readelf（ADR-024 CJK_DWARFDUMP）。
# 非 macOS 宿主按 G7 标注「CI 实跑待环境」退出 0，不虚报全绿。
# 用法: pwsh -File 原生/测试/检查调试信息MachO.ps1 [-路径 <文件...>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ($env:OS -eq "Windows_NT" -or -not (Test-Path '/usr/bin/clang')) {
    Write-Output "=== Mach-O DWARF 断言：CI 实跑待环境 ==="
    Write-Output "  llvm-dwarfdump 检查将在 GitHub Actions macos-latest job 真机执行（ADR-024 G7）。"
    exit 0
}

$dwarfdump = if ($env:CJK_DWARFDUMP) { $env:CJK_DWARFDUMP } else { "llvm-dwarfdump" }
if (-not (Get-Command $dwarfdump -ErrorAction SilentlyContinue)) {
    Write-Output "缺少 llvm-dwarfdump：$dwarfdump（可用 CJK_DWARFDUMP 指定）"
    exit 2
}

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 "构建/原生/DWARF断言MachO"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null

if (-not $路径 -or $路径.Count -eq 0) {
    $路径 = @("教程/第01课你好世界.java", "教程/第34课密封类.java")
}
$文件们 = foreach ($项 in $路径) { Get-Item (Join-Path $根目录 $项) }

$通过 = 0
$失败 = @()
$序号 = 0
foreach ($文件 in $文件们) {
    $序号++
    $名 = $文件.BaseName
    $macos = Join-Path $工作目录 ("obj{0}.macos" -f $序号)
    & $cjc 原生 llvm 编译 $文件.FullName -平台 darwin -o $macos *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $macos)) {
        $失败 += "$名：Mach-O 编译失败"
        continue
    }
    # Mach-O magic：0xFEEDFACF（小端 CF FA ED FE）。
    $magic = [System.IO.File]::ReadAllBytes($macos)[0..3]
    if (-not ($magic[0] -eq 0xCF -and $magic[1] -eq 0xFA -and $magic[2] -eq 0xED -and $magic[3] -eq 0xFE)) {
        $失败 += "$名：产物非 Mach-O 64"
        continue
    }
    $行转储 = & $dwarfdump --debug-line $macos 2>&1 | Out-String
    $信息转储 = & $dwarfdump --debug-info $macos 2>&1 | Out-String
    if ($行转储 -match '第01课|第34课|\.java' -and $信息转储 -match 'DW_TAG_compile_unit') {
        $通过++
    } else {
        $失败 += "$名：DWARF 中文源名/行号缺失"
    }
}

Write-Output ("Mach-O DWARF 断言：通过 {0} / 共 {1}" -f $通过, $文件们.Count)
if ($失败.Count -gt 0) { Write-Output ("失败：" + ($失败 -join "、")); exit 1 }
exit 0
