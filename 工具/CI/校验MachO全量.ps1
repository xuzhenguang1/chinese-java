# M21 CI：Mach-O 全量对拍（供 macos-latest job 调用，G7）。
# 本机 Windows 非 macOS 时，各子脚本按 G7 标注「CI 实跑待环境」并退出 0；
# 本聚合脚本汇总结果，不把环境缺失误判为失败，也不虚报全绿。
# 用法: pwsh -File 工具/CI/校验MachO全量.ps1
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$测试目录 = Join-Path $根目录 "原生/测试"
$失败 = @()
function 运行一项([string]$名, [string]$脚本, [hashtable]$参数 = @{}) {
    Write-Host ("===== {0} =====" -f $名)
    & (Join-Path $测试目录 $脚本) @参数
    $码 = $LASTEXITCODE
    if ($码 -ne 0) {
        $script:失败 += ("{0}（退出码 {1}）" -f $名, $码)
        Write-Host ("失败：{0} 退出码 {1}" -f $名, $码)
    } else {
        Write-Host ("通过/待环境：{0}" -f $名)
    }
}

# 1. 教程 35 课 Mach-O 对拍：C / LLVM 双后端。
运行一项 "教程 Mach-O 对拍 C" "对拍MachO.ps1" @{ 后端 = "c" }
运行一项 "教程 Mach-O 对拍 LLVM" "对拍MachO.ps1" @{ 后端 = "llvm" }

# 2. DWARF 中文行号（llvm-dwarfdump，CJK_DWARFDUMP 可配置）。
运行一项 "Mach-O DWARF 中文行号" "检查调试信息MachO.ps1"

Write-Host ""
if ($失败.Count -eq 0) {
    Write-Host "Mach-O 全量：真机全通过，或本机标注「CI 实跑待环境」（G7，不虚报全绿）。"
    exit 0
}
Write-Host ("Mach-O 全量失败：" + ($失败 -join "、"))
exit 1
