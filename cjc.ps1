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

# M17 CI 修复④：en-US Windows 的 JVM 用系统 ANSI 解码命令行参数，中文命令（检查/编译）
# 与中文路径（教程/示例）会变成 ??。source-file 改 ASCII 名只解决了文件名，参数仍是中文。
# 统一改为「参数文件」协议：
#   1) 真实参数写进 UTF-8 临时文件；2) 仅用环境变量传该文件的 ASCII 路径；
#   3) Java 侧显式按 UTF-8 读回。命令行里只留纯 ASCII 文件名，彻底 locale 无关。
$调用目录 = (Get-Location).Path
function 规范参数([object[]]$参数们) {
    $结果 = @()
    foreach ($项 in $参数们) {
        $文本 = [string]$项
        # Linux 上校验.ps1 用 "$root\..." 拼路径；\ 在此不是分隔符，统一成 /
        if ($env:OS -ne "Windows_NT") {
            $文本 = $文本.Replace('\', '/')
        }
        # Push-Location 到 工具\cjc 会改变 CWD，所有相对路径都先基于原调用目录转绝对路径。
        # 已有的路径用 Resolve-Path 取规范形式；不存在的（如 新建 项目 <名称>）用 GetFullPath 拼出绝对路径。
        # 判据：含路径分隔符才算路径；命令词（新建/项目/检查）与选项（--表/-d）不含分隔符，原样保留。
        $含分隔符 = $文本.Contains('\') -or $文本.Contains('/')
        if ($含分隔符 -and -not [System.IO.Path]::IsPathRooted($文本)) {
            $候选 = Join-Path $调用目录 $文本
            if (Test-Path $候选) {
                $文本 = (Resolve-Path $候选).Path
            } else {
                $文本 = [System.IO.Path]::GetFullPath($候选)
            }
        } elseif ($文本 -and -not [System.IO.Path]::IsPathRooted($文本) -and (Test-Path $文本)) {
            # 不含分隔符但确实存在的相对目录（如 示例 / 教程）
            $文本 = (Resolve-Path $文本).Path
        }
        $结果 += $文本
    }
    return $结果
}

function 写参数文件([object[]]$参数们) {
    $路径 = Join-Path $env:TEMP ("cjc-args-" + [guid]::NewGuid().ToString('N') + ".txt")
    [System.IO.File]::WriteAllLines($路径, [string[]]$参数们, (New-Object System.Text.UTF8Encoding($false)))
    $env:CJC_ARGS_FILE = $路径
    return $路径
}

$源码目录 = Join-Path $PSScriptRoot "工具\cjc"

# M15：依赖子命令转发到 依赖清单.java（source-file 模式），与 Java 转译链路解耦。
if ($args.Count -gt 0 -and $args[0] -eq '依赖') {
    $剩余 = @()
    if ($args.Count -gt 1) {
        $剩余 = @(规范参数 $args[1..($args.Count - 1)])
    }
    $参数文件 = 写参数文件 $剩余
    Push-Location $源码目录
    try {
        java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "依赖清单.java"
        $码 = $LASTEXITCODE
    } finally {
        Pop-Location
        Remove-Item $参数文件 -Force -ErrorAction SilentlyContinue
        Remove-Item Env:CJC_ARGS_FILE -ErrorAction SilentlyContinue
    }
    exit $码
}

$规范args = @(规范参数 $args)
$参数文件 = 写参数文件 $规范args
Push-Location $源码目录
try {
    java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dcjc.home=$PSScriptRoot" "Cjc.java"
    $码 = $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item $参数文件 -Force -ErrorAction SilentlyContinue
    Remove-Item Env:CJC_ARGS_FILE -ErrorAction SilentlyContinue
}
exit $码
