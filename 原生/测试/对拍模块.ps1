# M13 多文件对拍：示例工程（3 包 10 文件）的原生 C/LLVM 模块编译，
# 与 JVM 参考后端（多文件解释器）逐字节对拍 stdout 与退出码。
# 换行符归一化：原生 exe 在 Windows 输出 CRLF，Java 参考输出 LF，逐行比较时统一。
# 用法：.\原生\测试\对拍模块.ps1 [-根目录 <目录>] [-类目录 <目录>] [-后端 c|llvm]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$类目录 = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path "构建\原生\前端\classes"),
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c"
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 ("构建\原生\对拍模块-" + $后端)
$exe目录 = Join-Path $工作目录 "exe"
if (Test-Path $工作目录) {
    [System.IO.Directory]::Delete($工作目录, $true)
}
New-Item -ItemType Directory -Force $exe目录 | Out-Null

# 示例工程：3 包 10 文件，入口在 演示.应用.主程序。
$源目录 = Join-Path $根目录 "示例工程\src\main\中文\演示"
$入口 = Join-Path $源目录 "应用\主程序.java"
$其他源们 = @(Get-ChildItem $源目录 -Recurse -Filter *.java -File |
    Where-Object { $_.FullName -ne $入口 } | Sort-Object FullName)
$全部源 = @($入口) + @($其他源们 | ForEach-Object FullName)

if (-not (Test-Path $入口) -or $其他源们.Count -lt 1) {
    Write-Output "示例工程多文件样例不完整：$源目录"
    exit 2
}

$JvmOut = Join-Path $工作目录 "jvm.out"
$JvmErr = Join-Path $工作目录 "jvm.err"
$ExeOut = Join-Path $工作目录 "exe.out"
$ExeErr = Join-Path $工作目录 "exe.err"
$Exe = Join-Path $exe目录 "模块.exe"

function 归一化([string]$文件) {
    if (-not (Test-Path $文件)) { return "" }
    $文本 = [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
    $行们 = @($文本 -split "\r?\n")
    return ($行们 -join "`n")
}

# 1) JVM 参考：多文件解释器运行模块（无参数，默认 3 学生）。
foreach ($n in @('http_proxy','https_proxy','all_proxy','no_proxy','ftp_proxy')) {
    [Environment]::SetEnvironmentVariable($n, $null)
}
$前端参数 = @('运行模块', $根目录) + $全部源
$进程 = Start-Process -FilePath "java" -ArgumentList (@(
    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
    "-cp", $类目录, "中文.原生.前端.前端主程序") + $前端参数) `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $JvmOut -RedirectStandardError $JvmErr
$jvm码 = $进程.ExitCode

# 2) 原生模块编译：驱动编译接受多源，第一个为入口。
if ($后端 -eq "llvm") {
    & $cjc 原生 llvm 编译 @全部源 -o $Exe *> $null
} else {
    & $cjc 原生 编译 @全部源 -o $Exe *> $null
}
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Exe)) {
    Write-Output ("失败：原生模块编译失败（{0}，退出码 {1}）" -f $后端, $LASTEXITCODE)
    exit 1
}

$进程 = Start-Process -FilePath $Exe -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $ExeOut -RedirectStandardError $ExeErr
$exe码 = $进程.ExitCode

# 3) 归一化后逐行比较 + 退出码比较。
$甲 = 归一化 $JvmOut
$乙 = 归一化 $ExeOut
if ($甲 -ceq $乙 -and $jvm码 -eq $exe码) {
    Write-Output ("通过：示例工程 3 包 {0} 文件，{1} 后端，stdout {2} 行，退出码 {3}" -f
        $全部源.Count, $后端.ToUpper(), @($甲 -split "`n").Count, $exe码)
    exit 0
}

Write-Output ("失败：示例工程多文件对拍不一致（{0}）" -f $后端)
Write-Output ("  JVM 退出码 {0} / 原生退出码 {1}" -f $jvm码, $exe码)
Write-Output "  JVM stdout："
($甲 -split "`n") | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
Write-Output "  原生 stdout："
($乙 -split "`n") | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
$错 = 归一化 $ExeErr
if ($错) {
    Write-Output "  原生 stderr："
    ($错 -split "`n") | Select-Object -First 8 | ForEach-Object { Write-Output ("    " + $_) }
}
exit 1
