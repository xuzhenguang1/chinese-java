# M21 Mach-O 本机静态断言（G7）：无需 macOS SDK，验证目标平台配置桶正确。
# 三段断言：① 平台宏互斥（darwin/linux/windows 恰一为 1）；② darwin IR 三常量
# （datalayout e-m:o / macOS triple / Itanium personality）；③ clang IR 语法解析。
# 供 校验.ps1 8.20 调用；真机编译/对拍标注「CI 实跑待环境」由 对拍MachO.ps1 负责。
# 用法: pwsh -File 原生/测试/静态断言MachO.ps1
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$失败 = @()
$探测目录 = Join-Path $根目录 "构建/原生/静态断言MachO"
if (Test-Path $探测目录) { [System.IO.Directory]::Delete($探测目录, $true) }
$类目录 = Join-Path $探测目录 "classes"
$输出目录 = Join-Path $探测目录 "out"
foreach ($d in @($类目录, $输出目录)) { New-Item -ItemType Directory -Force $d | Out-Null }

# ---- ① 平台宏互斥性（纯预处理，不依赖系统头）----
$探针 = Join-Path $探测目录 "宏选择探针.c"
@'
#include "cjk_platform.h"
#if CJK_PLATFORM_WINDOWS + CJK_PLATFORM_LINUX + CJK_PLATFORM_DARWIN != 1
#  error "三平台宏必须互斥且恰有一个为 1"
#endif
#ifdef 期望_DARWIN
#  if CJK_PLATFORM_DARWIN != 1 || CJK_PLATFORM_WINDOWS != 0 || CJK_PLATFORM_LINUX != 0
#    error "darwin 平台宏不正确"
#  endif
#endif
int 探针(void) { return 0; }
'@ | Set-Content -Path $探针 -Encoding UTF8
$clang = "clang"
foreach ($宏 in @(@('CJK_PLATFORM_WINDOWS','期望_WINDOWS'), @('CJK_PLATFORM_LINUX','期望_LINUX'), @('CJK_PLATFORM_DARWIN','期望_DARWIN'))) {
    $参数 = @('-std=c11', '-I', (Join-Path $根目录 "原生/运行时"))
    if ($宏[0] -ne 'CJK_PLATFORM_WINDOWS') { $参数 += "-D$($宏[0])" }
    $参数 += @("-D$($宏[1])", '-fsyntax-only', $探针)
    & $clang @参数 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $失败 += ("平台宏探针失败：{0}" -f $宏[0]) }
}

# ---- ② Java 四目录编译 + darwin IR 三常量 ----
$java源们 = @()
foreach ($子 in @('前端/src', '中间表示/src', '后端C/src', '后端LLVM/src')) {
    $java源们 += @(Get-ChildItem (Join-Path $根目录 "原生/$子") -Recurse -Filter *.java -File)
}
& javac -encoding UTF-8 -d $类目录 $java源们.FullName 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { $失败 += "javac 编译失败" }

$ir = Join-Path $输出目录 "darwin.ll"
$原平台 = [Environment]::GetEnvironmentVariable('CJC_PLATFORM')
[Environment]::SetEnvironmentVariable('CJC_PLATFORM', 'darwin')
try {
    & java "-Dstdout.encoding=UTF-8" -cp $类目录 "中文.原生.前端.前端主程序" 编译LLVM $根目录 (Join-Path $根目录 "教程/第01课你好世界.java") $ir 2>&1 | Out-Null
} finally {
    [Environment]::SetEnvironmentVariable('CJC_PLATFORM', $原平台)
}
if (-not (Test-Path $ir)) {
    $失败 += "darwin IR 未生成"
} else {
    $文本 = [System.IO.File]::ReadAllText($ir, [System.Text.Encoding]::UTF8)
    if ($文本 -notmatch 'e-m:o-p270:32:32') { $失败 += "darwin datalayout 非 Mach-O (e-m:o)" }
    if ($文本 -notmatch 'x86_64-apple-macosx') { $失败 += "darwin triple 缺失" }
    if ($文本 -notmatch 'personality ptr @__gxx_personality_v0') { $失败 += "darwin personality 非 Itanium/DWARF" }
    # ③ clang IR 语法解析（纯 IR 解析，不需 macOS SDK）。
    $解析输出 = Join-Path $输出目录 "darwin.parsed.ll"
    & $clang --target=x86_64-apple-darwin -S -emit-llvm -x ir $ir -o $解析输出 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $失败 += "darwin IR clang 解析失败" }
}

if (Test-Path $探测目录) { [System.IO.Directory]::Delete($探测目录, $true) }
if ($失败.Count -eq 0) {
    Write-Output "Mach-O 本机静态断言：全部通过（平台宏互斥 + IR 三常量 + IR 语法）；真机对拍 CI 实跑待环境。"
    exit 0
}
Write-Output ("Mach-O 本机静态断言失败：" + ($失败 -join "、"))
exit 1
