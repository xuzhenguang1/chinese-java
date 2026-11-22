# M16 对拍：浮点边界用例的原生 exe stdout 与 JVM 后端逐字节一致。
# 与 对拍原生.ps1 同构，额外为标准库 数值.转文本 显式带 classpath。
# 用法：.\原生\测试\对拍浮点.ps1 [-根目录 <目录>] [-类目录 <目录>] [-后端 c|llvm] [-全部] [-路径 <文件或目录...>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$类目录 = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path "构建\原生\前端\classes"),
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c",
    [switch]$全部,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$stdlib = @(Get-ChildItem (Join-Path $根目录 "标准库\target") -Filter "cjc-stdlib-*.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1)
if ($stdlib.Count -eq 0) {
    Write-Output "找不到标准库 jar，请先执行 mvn -f 标准库\pom.xml package"
    exit 2
}
$stdlib路径 = $stdlib[0].FullName

# M16 已知差异登记：行标签正则 -> ADR-007 锚点。命中行的差异视为"已登记"，不计回归。
$已知差异 = @()
$已知差异文件 = Join-Path $PSScriptRoot "浮点已知差异.txt"
if (Test-Path $已知差异文件) {
    $已知差异 = @(Get-Content $已知差异文件 -Encoding UTF8 |
        Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") } |
        ForEach-Object { ($_ -split '\|')[0].Trim() })
}

function 读取文本([string]$文件) {
    if (-not (Test-Path $文件)) { return "" }
    return [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
}

function 归一化已知差异([string]$文件, [string[]]$正则们) {
    $行们 = @((读取文本 $文件) -split "\r?\n")
    for ($i = 0; $i -lt $行们.Count; $i++) {
        foreach ($式 in $正则们) {
            if ($行们[$i] -match $式) { $行们[$i] = "__已登记__"; break }
        }
    }
    return ($行们 -join "`n")
}

$后端们 = if ($全部) { @('c', 'llvm') } else { @($后端) }
$总通过 = 0
$总用例 = 0
$所有失败 = @()

foreach ($端 in $后端们) {
    $工作目录 = Join-Path $根目录 ("构建\原生\对拍浮点-" + $端)
    $jvm目录 = Join-Path $工作目录 "jvm"
    $exe目录 = Join-Path $工作目录 "exe"
    if (Test-Path $工作目录) {
        [System.IO.Directory]::Delete($工作目录, $true)
    }
    New-Item -ItemType Directory -Force $jvm目录 | Out-Null
    New-Item -ItemType Directory -Force $exe目录 | Out-Null

    $文件们 = @()
    if ($路径 -and $路径.Count -gt 0) {
        foreach ($项 in $路径) {
            if (Test-Path $项 -PathType Container) {
                $文件们 += @(Get-ChildItem $项 -Filter "浮*.java" -File | Sort-Object Name)
            } elseif (Test-Path $项) {
                $文件们 += @(Get-Item $项)
            } else {
                Write-Output "路径不存在：$项"
                exit 2
            }
        }
    } else {
        $文件们 = @(Get-ChildItem (Join-Path $PSScriptRoot "浮点边界") -Filter "浮*.java" -File | Sort-Object Name)
    }

    if ($文件们.Count -eq 0) {
        Write-Output "没有找到要对比的浮点用例。"
        exit 2
    }

    $白名单 = @()
    $白名单文件 = Join-Path $PSScriptRoot "差异白名单.txt"
    if (Test-Path $白名单文件) {
        $白名单 = @(Get-Content $白名单文件 -Encoding UTF8 |
            Where-Object { $_.Trim() -ne "" -and -not $_.TrimStart().StartsWith("#") })
    }

    function 归一化文本([string]$文件) {
        $行们 = @((读取文本 $文件) -split "\r?\n")
        for ($i = 0; $i -lt $行们.Count; $i++) {
            foreach ($式 in $白名单) {
                if ($行们[$i] -match $式) {
                    $行们[$i] = "__白名单__"
                    break
                }
            }
        }
        return ($行们 -join "`n")
    }

    $通过 = 0
    $失败 = @()
    Write-Output ("=== 后端 {0} ===" -f $端.ToUpper())

    foreach ($文件 in $文件们) {
        $名 = $文件.BaseName
        $JvmOut = Join-Path $工作目录 "$名.jvm.out"
        $JvmErr = Join-Path $工作目录 "$名.jvm.err"
        $ExeOut = Join-Path $工作目录 "$名.exe.out"
        $ExeErr = Join-Path $工作目录 "$名.exe.err"
        $Exe = Join-Path $exe目录 "$名.exe"

        # JVM：编译与运行都带标准库 jar。
        & $cjc 编译 $文件.FullName -d $jvm目录 -cp $stdlib路径 *> $null
        if ($LASTEXITCODE -ne 0) {
            $失败 += "$名（JVM 编译失败）"
            continue
        }
        $jvmCp = $jvm目录 + [IO.Path]::PathSeparator + $stdlib路径
        $进程 = Start-Process -FilePath "java" -ArgumentList @(
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
            "-cp", $jvmCp, $名
        ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $JvmOut -RedirectStandardError $JvmErr
        $jvm码 = $进程.ExitCode

        # 原生：C 或 LLVM 后端。
        if ($端 -eq "llvm") {
            & $cjc 原生 llvm 编译 $文件.FullName -o $Exe *> $null
        } else {
            & $cjc 原生 编译 $文件.FullName -o $Exe *> $null
        }
        if ($LASTEXITCODE -ne 0) {
            $失败 += "$名（原生编译失败）"
            continue
        }

        $进程 = Start-Process -FilePath $Exe -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $ExeOut -RedirectStandardError $ExeErr
        $exe码 = $进程.ExitCode

        $同输出 = $false
        $白名单命中 = $false
        $已登记命中 = $false
        if ((Test-Path $JvmOut) -and (Test-Path $ExeOut)) {
            $甲 = [System.IO.File]::ReadAllBytes($JvmOut)
            $乙 = [System.IO.File]::ReadAllBytes($ExeOut)
            $同输出 = ($甲.Length -eq $乙.Length)
            if ($同输出) {
                for ($i = 0; $i -lt $甲.Length; $i++) {
                    if ($甲[$i] -ne $乙[$i]) { $同输出 = $false; break }
                }
            }
            if (-not $同输出 -and $白名单.Count -gt 0) {
                $归一甲 = 归一化文本 $JvmOut
                $归一乙 = 归一化文本 $ExeOut
                if ($归一甲 -ceq $归一乙) { $同输出 = $true; $白名单命中 = $true }
            }
            if (-not $同输出 -and $已知差异.Count -gt 0) {
                $归一甲 = 归一化已知差异 $JvmOut $已知差异
                $归一乙 = 归一化已知差异 $ExeOut $已知差异
                if ($归一甲 -ceq $归一乙) { $同输出 = $true; $已登记命中 = $true }
            }
        }

        if ($同输出 -and $jvm码 -eq $exe码) {
            $通过++
            $标记 = if ($白名单命中) { "（白名单）" } elseif ($已登记命中) { "（已登记差异 ADR-007）" } else { "" }
            Write-Output ("一致  {0}{1}" -f $名, $标记)
            continue
        }

        $失败 += $名
        Write-Output ("差异  {0}  JVM 退出码 {1} / 原生退出码 {2}" -f $名, $jvm码, $exe码)
        Write-Output "  JVM stdout："
        (读取文本 $JvmOut) -split "`R" | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
        Write-Output "  原生 stdout："
        (读取文本 $ExeOut) -split "`R" | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
        $错 = 读取文本 $ExeErr
        if ($错) {
            Write-Output "  原生 stderr："
            $错 -split "`R" | Select-Object -First 8 | ForEach-Object { Write-Output ("    " + $_) }
        }
    }

    Write-Output ("后端 {0}：一致 {1} / 共 {2}" -f $端.ToUpper(), $通过, $文件们.Count)
    $总通过 += $通过
    $总用例 += $文件们.Count
    if ($失败.Count -gt 0) {
        $所有失败 += $失败 | ForEach-Object { "${端}:$_" }
    }
}

Write-Output ""
Write-Output ("对拍结果：一致 {0} / 共 {1}" -f $总通过, $总用例)
if ($所有失败.Count -gt 0) {
    Write-Output ("差异用例：" + ($所有失败 -join "、"))
    exit 1
}
exit 0
