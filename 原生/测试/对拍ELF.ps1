# M14 Linux ELF 全量对拍：教程 01-35 的 ELF stdout/退出码与 JVM 后端逐字节一致。
# Windows 侧沿用 对拍原生.ps1；本脚本专用于 -平台 linux（Alpine VM 运行 ELF）。
# 换行（JVM CRLF / VM LF）、路径（Windows 绝对 / POSIX 绝对）、白名单行归一化后比较。
# 用法: .\原生\测试\对拍ELF.ps1 [-后端 c|llvm] [-路径 <文件或目录...>] [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c",
    [switch]$复用结果,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$工作目录 = Join-Path $根目录 ("构建\原生\对拍ELF-" + $后端)
$jvm目录 = Join-Path $工作目录 "jvm"
$elf目录 = Join-Path $工作目录 "elf"
$结果目录 = Join-Path $工作目录 "结果"
if (-not $复用结果) {
    if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
    New-Item -ItemType Directory -Force $jvm目录 | Out-Null
    New-Item -ItemType Directory -Force $elf目录 | Out-Null
    New-Item -ItemType Directory -Force $结果目录 | Out-Null
} elseif (-not (Test-Path $结果目录)) {
    Write-Output "复用结果模式：结果目录不存在：$结果目录"; exit 2
}

$文件们 = @()
if ($路径 -and $路径.Count -gt 0) {
    foreach ($项 in $路径) {
        if (Test-Path $项 -PathType Container) {
            $文件们 += @(Get-ChildItem $项 -Filter *.java -File |
                Where-Object { $_.Name -match '^第\d{2}课' } | Sort-Object Name)
        } elseif (Test-Path $项) {
            $文件们 += @(Get-Item $项)
        } else {
            Write-Output "路径不存在：$项"; exit 2
        }
    }
} else {
    $文件们 = @(Get-ChildItem (Join-Path $根目录 "教程") -Filter *.java -File |
        Where-Object { $_.Name -match '^第\d{2}课' } | Sort-Object Name)
}
if ($文件们.Count -eq 0) { Write-Output "没有找到要对比的文件。"; exit 2 }

# 避免 Start-Process 代理变量大小写冲突。
foreach ($n in @('http_proxy','https_proxy','all_proxy','no_proxy','ftp_proxy')) {
    [Environment]::SetEnvironmentVariable($n, $null)
}

$白名单 = @()
$白名单文件 = Join-Path $PSScriptRoot "差异白名单.txt"
if (Test-Path $白名单文件) {
    $白名单 = @(Get-Content $白名单文件 -Encoding UTF8 |
        Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") })
}

# ELF basename 统一为 "caseNN"（ASCII），与 JVM 名解耦，避免中文 basename 在 VM 侧的潜在问题。
$用例 = @()
for ($i = 0; $i -lt $文件们.Count; $i++) {
    $用例 += [pscustomobject]@{
        源 = $文件们[$i]
        名 = $文件们[$i].BaseName
        标签 = ("case{0:D2}" -f ($i + 1))
    }
}

if (-not $复用结果) {
    Write-Output ("=== JVM 基准生成（{0} 课）===" -f $用例.Count)
    foreach ($u in $用例) {
        $cls = $u.名
        & $cjc 编译 $u.源.FullName -d $jvm目录 *> $null
        if ($LASTEXITCODE -ne 0) { Write-Output ("JVM 编译失败：{0}" -f $u.名); continue }
        $outFile = Join-Path $结果目录 ($u.标签 + ".jvm.out")
        $errFile = Join-Path $结果目录 ($u.标签 + ".jvm.err")
        $p = Start-Process -FilePath "java" -ArgumentList @(
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
            "-cp", $jvm目录, $cls
        ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        [System.IO.File]::WriteAllText((Join-Path $结果目录 ($u.标签 + ".jvm.exit")), [string]$p.ExitCode)
    }
    Write-Output "JVM 基准生成完成。"

    Write-Output ("=== ELF 批量编译（后端 {0}，{1} 课）===" -f $后端, $用例.Count)
    $elf们 = @()
    foreach ($u in $用例) {
        $elf = Join-Path $elf目录 ($u.标签 + ".elf")
        if ($后端 -eq "llvm") {
            & $cjc 原生 llvm 编译 $u.源.FullName -平台 linux -o $elf *> $null
        } else {
            & $cjc 原生 编译 $u.源.FullName -平台 linux -o $elf *> $null
        }
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
            Write-Output ("ELF 编译失败：{0}" -f $u.名)
        } else {
            $elf们 += $elf
        }
    }
    Write-Output ("ELF 编译完成：{0}/{1}" -f $elf们.Count, $用例.Count)

    if ($elf们.Count -eq 0) { Write-Output "没有可运行的 ELF。"; exit 1 }
    Write-Output "=== VM 批量运行 ==="
    $r = & $运行器 -平台 linux -可执行 $elf们 -输出目录 $结果目录 2>&1
    Write-Output ($r | Out-String)
    if ($LASTEXITCODE -ne 0) { Write-Output "运行器失败。"; exit 2 }
} else {
    Write-Output "复用已有结果，跳过 JVM 生成 / ELF 编译 / VM 运行。"
}

function 读字节($文件) {
    if (-not (Test-Path $文件)) { return [byte[]]@() }
    return [System.IO.File]::ReadAllBytes($文件)
}
function 读文本($文件) {
    if (-not (Test-Path $文件)) { return "" }
    return [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
}
# 路径行归一化：JVM 绝对路径与 ELF POSIX 绝对路径统一成项目根相对路径。
function 归一路径行($行) {
    # JVM: E:\xiadujava\构建\... → 构建/...；ELF: /构建/... → 构建/...
    $结果 = $行 -replace '[A-Za-z]:\\xiadujava\\?', ''
    $结果 = $结果 -replace '\\', '/'
    $结果 = $结果 -replace '(?<=[：:])/', ''
    return $结果
}
function 归一化($文件) {
    $行们 = @((读文本 $文件) -split "\r?\n")
    for ($i = 0; $i -lt $行们.Count; $i++) {
        foreach ($式 in $白名单) {
            if ($行们[$i] -match $式) { $行们[$i] = "__白名单__"; break }
        }
        if ($行们[$i] -match '写入成功|无法|路径：') {
            $行们[$i] = 归一路径行 $行们[$i]
        }
    }
    return ($行们 -join "`n")
}

$通过 = 0
$失败 = @()
foreach ($u in $用例) {
    $jvmOut = Join-Path $结果目录 ($u.标签 + ".jvm.out")
    $elfOut = Join-Path $结果目录 ($u.标签 + ".elf.out")
    $jvm码文件 = Join-Path $结果目录 ($u.标签 + ".jvm.exit")
    $elf码文件 = Join-Path $结果目录 ($u.标签 + ".elf.exit")
    if (-not (Test-Path $elfOut)) {
        $失败 += ("{0}（ELF 无输出：可能编译失败）" -f $u.名); continue
    }
    $jvm码 = if (Test-Path $jvm码文件) { [System.IO.File]::ReadAllText($jvm码文件).Trim() } else { "?" }
    $elf码 = if (Test-Path $elf码文件) { [System.IO.File]::ReadAllText($elf码文件).Trim() } else { "?" }

    $甲 = 读字节 $jvmOut
    $乙 = 读字节 $elfOut
    $同 = ($甲.Length -eq $乙.Length)
    if ($同) { for ($k = 0; $k -lt $甲.Length; $k++) { if ($甲[$k] -ne $乙[$k]) { $同 = $false; break } } }
    if (-not $同) { $同 = ((归一化 $jvmOut) -ceq (归一化 $elfOut)) }

    if ($同 -and $jvm码 -eq $elf码) {
        $通过++
    } else {
        $失败 += $u.名
        Write-Output ("失败 {0}：JVM码={1} ELF码={2}" -f $u.名, $jvm码, $elf码)
        Write-Output ("  JVM : " + ((读文本 $jvmOut) -replace "`r?`n", " | ").Substring(0, [Math]::Min(120, (读文本 $jvmOut).Length)))
        Write-Output ("  ELF : " + ((读文本 $elfOut) -replace "`r?`n", " | ").Substring(0, [Math]::Min(120, (读文本 $elfOut).Length)))
    }
}

Write-Output ""
Write-Output ("对拍结果：通过 {0} / 共 {1}（后端 {2}，平台 linux）" -f $通过, $用例.Count, $后端)
if ($失败.Count -gt 0) {
    Write-Output ("失败：" + ($失败 -join "、"))
    exit 1
}
exit 0