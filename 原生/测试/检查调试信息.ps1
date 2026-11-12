# M6 DWARF 断言：验证 LLVM 后端产物里的中文源文件名与行号。
# 用法：.\原生\测试\对拍DWARF.ps1 [-路径 <文件...>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$objdump = "C:\msys64\mingw64\bin\objdump.exe"
if (-not (Test-Path $objdump)) {
    Write-Output "缺少 objdump：$objdump"
    exit 2
}

$工作目录 = Join-Path $根目录 "构建\原生\DWARF断言"
if (Test-Path $工作目录) {
    Remove-Item $工作目录 -Recurse -Force
}
New-Item -ItemType Directory -Force $工作目录 | Out-Null

if (-not $路径 -or $路径.Count -eq 0) {
    $路径 = @("教程\第01课你好世界.java", "教程\第34课密封类.java")
}
$文件们 = foreach ($项 in $路径) { Get-Item (Join-Path $根目录 $项) }

$通过 = 0
$失败 = @()

foreach ($文件 in $文件们) {
    $名 = $文件.BaseName
    $exe = Join-Path $工作目录 "$名.exe"
    & $cjc 原生 llvm 编译 $文件.FullName -o $exe *> $null
    if ($LASTEXITCODE -ne 0) {
        $失败 += "$名：编译失败"
        continue
    }

    $转储 = & $objdump --dwarf=decodedline $exe 2>&1 | Out-String
    if ($转储 -notmatch [regex]::Escape($文件.Name)) {
        $失败 += "$名：DWARF 缺少中文源文件名「$($文件.Name)」"
        continue
    }

    $行号们 = @()
    foreach ($行 in ($转储 -split "`r?`n")) {
        # 形如：第01课你好世界.java   7   0x140001584   x
        # 行号 0 是编译器给序言/收尾插入的标记，不算越界。
        if ($行 -match '^\s*\S+\.java\s+(\d+)\s+0x[0-9a-fA-F]+\s*$') {
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

    # 最后一条可执行语句所在行必须出现在行号表中。
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
    Write-Output ("通过  {0}  文件名与行号命中（{1} 条映射，最大行 {2}）" -f $名, $正行号们.Count, ($正行号们 | Measure-Object -Maximum).Maximum)
}

Write-Output ""
Write-Output ("DWARF 断言：通过 {0} / 共 {1}" -f $通过, $文件们.Count)
if ($失败.Count -gt 0) {
    Write-Output ("失败：" + ($失败 -join "；"))
    exit 1
}
exit 0
