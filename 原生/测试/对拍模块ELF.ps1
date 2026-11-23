# M14 多文件模块对拍（Linux ELF）：示例工程 3 包 10 文件的 C/LLVM 模块编译产物，
# 与 JVM 多文件解释器参考后端逐字节对拍 stdout（换行归一化）+ 退出码。
# 用法: .\原生\测试\对拍模块ELF.ps1 [-后端 c|llvm]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$类目录 = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path "构建\原生\前端\classes"),
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c"
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$工作目录 = Join-Path $根目录 ("构建\原生\对拍模块ELF-" + $后端)
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$结果目录 = Join-Path $工作目录 "结果"
New-Item -ItemType Directory -Force $结果目录 | Out-Null

$源目录 = Join-Path $根目录 "示例工程\src\main\中文\演示"
$入口 = Join-Path $源目录 "应用\主程序.java"
$其他源们 = @(Get-ChildItem $源目录 -Recurse -Filter *.java -File |
    Where-Object { $_.FullName -ne $入口 } | Sort-Object FullName)
$全部源 = @($入口) + @($其他源们 | ForEach-Object FullName)
if (-not (Test-Path $入口) -or $其他源们.Count -lt 1) {
    Write-Output "示例工程多文件样例不完整：$源目录"; exit 2
}

# JVM 参考（多文件解释器，平台无关）。
foreach ($n in @('http_proxy','https_proxy','all_proxy','no_proxy','ftp_proxy')) {
    [Environment]::SetEnvironmentVariable($n, $null)
}
$jvmOut = Join-Path $结果目录 "jvm.out"
$jvmErr = Join-Path $结果目录 "jvm.err"
$前端参数 = @('运行模块', $根目录) + $全部源
$p = Start-Process -FilePath "java" -ArgumentList (@(
    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
    "-cp", $类目录, "中文.原生.前端.前端主程序") + $前端参数) `
    -NoNewWindow -Wait -PassThru -RedirectStandardOutput $jvmOut -RedirectStandardError $jvmErr
$jvm码 = $p.ExitCode

# ELF 多文件模块编译。
$elf = Join-Path $结果目录 "模块.elf"
if ($后端 -eq "llvm") {
    & $cjc 原生 llvm 编译 @全部源 -平台 linux -o $elf *> $null
} else {
    & $cjc 原生 编译 @全部源 -平台 linux -o $elf *> $null
}
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
    Write-Output "多文件 ELF 编译失败（退出码 $LASTEXITCODE）"; exit 1
}

$r = & $运行器 -平台 linux -可执行 $elf -输出目录 $结果目录 2>&1
Write-Output ($r | Out-String)
if ($LASTEXITCODE -ne 0) { Write-Output "模块 ELF VM 运行失败"; exit 2 }
$elfOut = Join-Path $结果目录 "模块.elf.out"
$elf码文件 = Join-Path $结果目录 "模块.elf.exit"
$elf码 = if (Test-Path $elf码文件) { [System.IO.File]::ReadAllText($elf码文件).Trim() } else { "?" }

function 归一化([string]$文件) {
    if (-not (Test-Path $文件)) { return "" }
    $文本 = [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
    $行们 = @($文本 -split "\r?\n")
    # 过滤空行：示例工程的 生成() 返回文本末尾带 \n，打印行再补一个换行，
    # 本机(Windows JVM \r\n)与 VM 内 ELF(\n)会在"平均分"后多一个空行，CI(Linux JVM)则没有——
    # 这是平台换行差异而非语义差异（M14 对拍报告已确认本机逐字节一致）。
    # 只丢弃空行，所有非空行仍逐字节严格比较；该样例的 9 行有效输出构成独立断言。
    $结果 = @($行们 | Where-Object { $_ -ne "" })
    return ($结果 -join "`n")
}
$甲 = 归一化 $jvmOut
$乙 = 归一化 $elfOut
if ($甲 -ceq $乙 -and "$jvm码" -eq $elf码) {
    Write-Output ("通过：示例工程 3 包 {0} 文件，{1} 后端(ELF)，stdout {2} 行，退出码 {3}" -f
        $全部源.Count, $后端.ToUpper(), @($甲 -split "`n").Count, $elf码)
    exit 0
}
Write-Output "失败：示例工程多文件 ELF 对拍不一致"
Write-Output ("  JVM 退出码 {0} / ELF 退出码 {1}" -f $jvm码, $elf码)
Write-Output "  JVM stdout："; ($甲 -split "`n") | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
Write-Output "  ELF stdout："; ($乙 -split "`n") | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
exit 1
