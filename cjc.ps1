# cjc 包装脚本：统一控制台 UTF-8 输出，然后调用 Cjc.java
# 用法：
#   .\cjc.ps1 检查 示例
#   .\cjc.ps1 编译 示例 -d 输出
#   .\cjc.ps1 编译 示例 -d 输出 --表 工具\cjc\关键字表.txt
# chcp 仅存在于 Windows；Linux/macOS 的 pwsh 无此命令（M17 双矩阵 CI 修复）。
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 原生子命令转发到原生\驱动.ps1（首个参数为「原生」时），不与 Java 转译链路混用。
if ($args.Count -gt 0 -and $args[0] -eq '原生') {
    $剩余 = @()
    if ($args.Count -gt 1) {
        $剩余 = $args[1..($args.Count - 1)]
    }
    & "$PSScriptRoot\原生\驱动.ps1" @剩余
    exit $LASTEXITCODE
}
# M17 CI 修复③：java source-file 模式会把含中文的源文件路径当作类名解码，
# 在 en-US locale 的 Windows runner 上「工具」变 ??，在 Linux 上 / 与 \ 混用变 /java。
# 统一：先切到源文件所在目录，只传纯 ASCII 文件名给 launcher；中文只留在 JVM 内部参数里。
# 因为工作目录会切到 工具\cjc，先把「存在的相对路径」参数转为绝对路径（基于原调用目录）。
$调用目录 = (Get-Location).Path
function 规范参数([object[]]$参数们) {
    $结果 = @()
    foreach ($项 in $参数们) {
        $文本 = [string]$项
        if ($文本 -and -not [System.IO.Path]::IsPathRooted($文本)) {
            $候选 = Join-Path $调用目录 $文本
            if (Test-Path $候选) { $文本 = (Resolve-Path $候选).Path }
        }
        $结果 += $文本
    }
    return ,$结果
}
$源码目录 = Join-Path $PSScriptRoot "工具\cjc"
# M15：依赖子命令转发到 依赖清单.java（source-file 模式），与 Java 转译链路解耦。
if ($args.Count -gt 0 -and $args[0] -eq '依赖') {
    $剩余 = @()
    if ($args.Count -gt 1) {
        $剩余 = 规范参数 $args[1..($args.Count - 1)]
    }
    Push-Location $源码目录
    try {
        java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "依赖清单.java" @剩余
        $码 = $LASTEXITCODE
    } finally { Pop-Location }
    exit $码
}
$规范args = 规范参数 $args
Push-Location $源码目录
try {
    java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dcjc.home=$PSScriptRoot" "Cjc.java" @规范args
    $码 = $LASTEXITCODE
} finally { Pop-Location }
exit $码
