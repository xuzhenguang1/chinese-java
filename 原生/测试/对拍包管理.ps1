# M15 包管理三方对拍：按依赖清单解析出的源文件列表，原生 C/LLVM 与 JVM 参考逐字节对拍。
# 用法：.\原生\测试\对拍包管理.ps1 -清单 <清单路径> [-后端 c|llvm] [-全部]
#   -全部：c 与 llvm 各跑一遍。
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [Parameter(Mandatory = $true)]
    [string]$清单,
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c",
    [switch]$全部,
    [ValidateSet("windows", "linux")]
    [string]$平台 = "windows"
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$类目录 = Join-Path $根目录 "构建\原生\前端\classes"
$cjc = Join-Path $根目录 "cjc.ps1"
$解析器 = Join-Path $根目录 "工具\cjc\依赖清单.java"

function 转绝对([string]$路径) {
    if ([System.IO.Path]::IsPathRooted($路径)) { return $路径 }
    return (Join-Path (Get-Location).Path $路径)
}

$清单绝对 = @(转绝对 $清单)[0]
$工作目录 = Join-Path $根目录 ("构建\原生\对拍包-" + $平台 + "-" + $后端)
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$列表 = Join-Path $工作目录 "源列表.tmp"

# 1) 用清单解析源文件列表（UTF-8 文件中转，避免 PS5.1 捕获 java stdout 的中文路径乱码）。
java "-Dfile.encoding=UTF-8" $解析器 解析 $清单绝对 $列表
if ($LASTEXITCODE -ne 0) {
    Write-Output "失败：依赖清单解析失败（$清单绝对）"
    exit 1
}
$全部源 = @(Get-Content $列表 -Encoding UTF8)
$入口 = $全部源[0]
if ($全部源.Count -lt 1 -or -not (Test-Path $入口)) {
    Write-Output "失败：清单未给出有效入口：$入口"
    exit 1
}

function 归一化([string]$文件) {
    if (-not (Test-Path $文件)) { return "" }
    $文本 = [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
    return (($文本 -split "\r?\n") -join "`n")
}

$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$扩展名 = if ($平台 -eq "linux") { ".elf" } else { ".exe" }
$后端们 = if ($全部) { @('c', 'llvm') } else { @($后端) }
$总败 = 0
foreach ($端 in $后端们) {
    $JvmOut = Join-Path $工作目录 "jvm-$端.out"
    $JvmErr = Join-Path $工作目录 "jvm-$端.err"
    $ExeOut = Join-Path $工作目录 "exe-$端.out"
    $ExeErr = Join-Path $工作目录 "exe-$端.err"
    $Exe = Join-Path $工作目录 ("模块-" + $端 + $扩展名)

    foreach ($n in @('http_proxy','https_proxy','all_proxy','no_proxy','ftp_proxy')) {
        [Environment]::SetEnvironmentVariable($n, $null)
    }
    $前端参数 = @('运行模块', $根目录) + $全部源
    $进程 = Start-Process -FilePath "java" -ArgumentList (@(
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
        "-cp", $类目录, "中文.原生.前端.前端主程序") + $前端参数) `
        -NoNewWindow -Wait -PassThru -RedirectStandardOutput $JvmOut -RedirectStandardError $JvmErr
    $jvm码 = $进程.ExitCode

    # 展开 splatting 在 PS5.1 多层转发（-清单/@全部源/@平台参数）时会错位，改为 if 分支写死。
    if ($平台 -eq "linux") {
        if ($端 -eq "llvm") {
            & $cjc 原生 llvm 编译 @全部源 -o $Exe -清单 $清单绝对 -平台 linux *> $null
        } else {
            & $cjc 原生 编译 @全部源 -o $Exe -清单 $清单绝对 -平台 linux *> $null
        }
    } else {
        if ($端 -eq "llvm") {
            & $cjc 原生 llvm 编译 @全部源 -o $Exe -清单 $清单绝对 *> $null
        } else {
            & $cjc 原生 编译 @全部源 -o $Exe -清单 $清单绝对 *> $null
        }
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Exe)) {
        Write-Output ("失败：{0} 原生编译失败（退出码 {1}）" -f $端.ToUpper(), $LASTEXITCODE)
        $总败++
        continue
    }
    if ($平台 -eq "linux") {
        # ELF 走 Alpine VM 运行器，输出 <basename>.out 与 <basename>.exit（basename 含 .elf）。
        $标签 = "模块-" + $端 + ".elf"
        & $运行器 -平台 linux -可执行 $Exe -输出目录 $工作目录 -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            Write-Output ("失败：{0} ELF 运行器失败" -f $端.ToUpper())
            $总败++
            continue
        }
        Copy-Item (Join-Path $工作目录 ($标签 + ".out")) $ExeOut -Force
        $exe码 = [int](Get-Content (Join-Path $工作目录 ($标签 + ".exit")) -Raw).Trim()
    } else {
        $进程2 = Start-Process -FilePath $Exe -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $ExeOut -RedirectStandardError $ExeErr
        $exe码 = $进程2.ExitCode
    }

    $甲 = 归一化 $JvmOut
    $乙 = 归一化 $ExeOut
    if ($甲 -ceq $乙 -and $jvm码 -eq $exe码) {
        Write-Output ("通过：清单 {0} 源文件，{1} 后端，stdout {2} 行，退出码 {3}" -f
            $全部源.Count, $端.ToUpper(), @($甲 -split "`n").Count, $exe码)
    } else {
        Write-Output ("失败：清单三方对拍不一致（{0}）" -f $端.ToUpper())
        Write-Output ("  JVM 退出码 {0} / 原生退出码 {1}" -f $jvm码, $exe码)
        Write-Output "  JVM stdout："
        ($甲 -split "`n") | Select-Object -First 10 | ForEach-Object { Write-Output ("    " + $_) }
        Write-Output "  原生 stdout："
        ($乙 -split "`n") | Select-Object -First 10 | ForEach-Object { Write-Output ("    " + $_) }
        $总败++
    }
}

if ($总败 -gt 0) { exit 1 }
Write-Output "汇总：清单三方对拍全部通过"
exit 0
