# M14 DWARF 断言（Linux ELF）：验证 LLVM 后端 ELF 产物里的中文源文件名与行号。
# 与 PE 版 检查调试信息.ps1 口径一致；ELF 用 readelf --debug-dump=decodedline。
# 用法: .\原生\测试\检查调试信息Linux.ps1 [-路径 <文件...>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
# M17 E3：优先用 CJK_READELF 环境变量（Linux CI 用系统 readelf），
# 未设置时回退本机 MSYS2 路径（零回退）；也尝试 PATH 中的 readelf。
$readelf = if ($env:CJK_READELF) { $env:CJK_READELF } else { "C:\msys64\mingw64\bin\readelf.exe" }
if (-not (Test-Path $readelf)) {
    $系统命令 = Get-Command readelf -ErrorAction SilentlyContinue
    if ($系统命令) { $readelf = $系统命令.Source } else {
        Write-Output "缺少 readelf：$readelf（可用 CJK_READELF 指定）"
        exit 2
    }
}

$工作目录 = Join-Path $根目录 "构建\原生\DWARF断言Linux"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null
# ELF 也走 ASCII 路径，避免 readelf 对中文路径的 ANSI 解读。
$读取目录 = Join-Path ([System.IO.Path]::GetTempPath()) "cjk_dwarf_linux_tmp"
New-Item -ItemType Directory -Force $读取目录 | Out-Null

if (-not $路径 -or $路径.Count -eq 0) {
    $路径 = @("教程\第01课你好世界.java", "教程\第34课密封类.java")
}
$文件们 = foreach ($项 in $路径) { Get-Item (Join-Path $根目录 $项) }

$通过 = 0
$失败 = @()
$序号 = 0

foreach ($文件 in $文件们) {
    $序号++
    $名 = $文件.BaseName
    $elf = Join-Path $工作目录 ("obj{0}.elf" -f $序号)
    & $cjc 原生 llvm 编译 $文件.FullName -平台 linux -o $elf *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
        $失败 += "$名：ELF 编译失败"
        continue
    }

    # ELF magic 断言。
    $magic = [System.IO.File]::ReadAllBytes($elf)[0..3]
    if (-not ($magic[0] -eq 0x7F -and $magic[1] -eq 0x45 -and $magic[2] -eq 0x4C -and $magic[3] -eq 0x46)) {
        $失败 += "$名：产物非 ELF"
        continue
    }

    $读取elf = Join-Path $读取目录 ("obj{0}.elf" -f $序号)
    Copy-Item -LiteralPath $elf -Destination $读取elf -Force
    $旧 = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $转储 = & $readelf --debug-dump=decodedline $读取elf 2>&1 | Out-String
    $ErrorActionPreference = $旧
    if ($转储 -notmatch [regex]::Escape($文件.Name)) {
        $失败 += "$名：DWARF 缺少中文源文件名「$($文件.Name)」"
        continue
    }

    $行号们 = @()
    foreach ($行 in ($转储 -split "`r?`n")) {
        # readelf decodedline 形如：第01课你好世界.java  7  0x0000000000401584  x
        if ($行 -match '\.java\s+(\d+)\s+0x[0-9a-fA-F]+') {
            $行号们 += [int]$Matches[1]
        }
    }
    $正行号们 = @($行号们 | Where-Object { $_ -gt 0 })
    if ($正行号们.Count -eq 0) {
        $失败 += "$名：DWARF 行号表为空"
        continue
    }

    $总行数 = ([System.IO.File]::ReadAllLines($文件.FullName, [System.Text.Encoding]::UTF8)).Count
    $越界 = @($正行号们 | Where-Object { $_ -gt $总行数 })
    if ($越界.Count -gt 0) {
        $失败 += "$名：行号越界（源文件共 $总行数 行）：$((($越界 | Select-Object -Unique) -join ','))"
        continue
    }

    $语句行 = 1
    $源行们 = [System.IO.File]::ReadAllLines($文件.FullName, [System.Text.Encoding]::UTF8)
    for ($i = 0; $i -lt $源行们.Count; $i++) {
        $文 = $源行们[$i].Trim()
        if ($文 -ne "" -and -not $文.StartsWith("//") -and $文 -notmatch '^[{}]+$') {
            $语句行 = $i + 1
        }
    }
    if ($正行号们 -notcontains $语句行) {
        $失败 += "$名：行号表缺少语句行 $语句行"
        continue
    }

    $通过++
    Write-Output ("通过  {0}  ELF 文件名与行号命中（{1} 条映射，最大行 {2}）" -f $名, $正行号们.Count, ($正行号们 | Measure-Object -Maximum).Maximum)
}

Write-Output ""
Write-Output ("DWARF(Linux) 断言：通过 {0} / 共 {1}" -f $通过, $文件们.Count)
if ($失败.Count -gt 0) {
    Write-Output ("失败：" + ($失败 -join "；"))
    exit 1
}
exit 0
