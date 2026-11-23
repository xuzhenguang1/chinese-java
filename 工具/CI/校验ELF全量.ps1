# M17 CI：ELF 全量对拍（供 ubuntu-latest job 调用）。
# 前置：已运行 工具/CI/准备ELF资产.py 且设置 CJK_QEMU/CJK_KERNEL/CJK_ALPINE/CJK_READELF。
# 串起 M14 已在本机验证过的 ELF 对拍脚本，任一失败即非零退出。
# 用法: pwsh -File 工具/CI/校验ELF全量.ps1
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 同进程清代理（PS5.1 大小写双份代理会导致 Start-Process 失败）。
foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$测试目录 = Join-Path $根目录 "原生\测试"
$失败 = @()

# 参数必须用 hashtable 按名 splat：用 [string[]] + @参数 会按位置绑定，
# 调用 @("-后端","c") 时把 "-后端" 塞进子脚本首个参数 $根目录，拼出 ".../-后端/教程"。
function 运行一项([string]$名, [string]$脚本, [hashtable]$参数 = @{}) {
    Write-Host ("===== {0} =====" -f $名)
    & (Join-Path $测试目录 $脚本) @参数
    $码 = $LASTEXITCODE
    if ($码 -ne 0) {
        $script:失败 += ("{0}（退出码 {1}）" -f $名, $码)
        Write-Host ("失败：{0} 退出码 {1}" -f $名, $码)
    } else {
        Write-Host ("通过：{0}" -f $名)
    }
}

# 1. 教程 35 课 ELF 对拍：C / LLVM 双后端（各约 35 课，VM 批量）。
运行一项 "教程 ELF 对拍 C" "对拍ELF.ps1" @{ 后端 = "c" }
运行一项 "教程 ELF 对拍 LLVM" "对拍ELF.ps1" @{ 后端 = "llvm" }

# 2. 示例工程多文件模块 ELF 对拍：C / LLVM。
运行一项 "多文件模块 ELF 对拍 C" "对拍模块ELF.ps1" @{ 后端 = "c" }
运行一项 "多文件模块 ELF 对拍 LLVM" "对拍模块ELF.ps1" @{ 后端 = "llvm" }

# 3. DWARF 中文行号（系统 readelf，CJK_READELF 已设）。
运行一项 "DWARF 中文行号" "检查调试信息Linux.ps1"

# 4. GC 压力四组。
运行一项 "GC 压力" "GC压力Linux.ps1"

# 5. 网络冒烟。
运行一项 "网络冒烟" "网络冒烟Linux.ps1"

# 6. 内存检查（ASan 或降级静态分析）。
运行一项 "内存检查" "内存检查Linux.ps1"

Write-Host ""
Write-Host ("ELF 全量对拍结果：{0}" -f $(if ($失败.Count -eq 0) { "全部通过" } else { "失败 " + ($失败 -join "、") }))
if ($失败.Count -gt 0) { exit 1 }
exit 0
