# 中文 javac 启动器：先按 cjc 规则转译，再编译
# 因此名称前置声明（`计数器 类 {`）、省略返回类型（`公有 加一() {`）等 cjc 语法在这里同样可用。
# 用法： .\中文javac.ps1 -encoding UTF-8 -d 输出 示例\你好世界.java
#        .\中文javac.ps1 -cp 标准库\target\cjc-stdlib-0.3.0.jar -d 输出 示例\标准库演示.java
# 说明：源码统一按 UTF-8 读取，传其他编码会提示并忽略；-d 缺省为当前目录。

chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path "$PSScriptRoot\..\..").Path
# 调用方可能把文件列表作为一个数组传入，先摊平成普通参数
$全部参数 = @()
foreach ($项 in $args) {
    # 数组元素会逐项展开，字符串按单个参数保留
    $全部参数 += $项
}
$源文件 = @()
$输出 = $null
$类路径 = $null
for ($i = 0; $i -lt $全部参数.Count; $i++) {
    $arg = [string]$全部参数[$i]
    if ($arg -eq '-encoding') {
        if ($i + 1 -lt $全部参数.Count) {
            $编码 = [string]$全部参数[++$i]
            if ($编码 -ne 'UTF-8' -and $编码 -ne 'UTF8') {
                Write-Warning "Cjc 统一按 UTF-8 读取源码，已忽略 -encoding $编码"
            }
        }
        continue
    }
    if ($arg -eq '-d') {
        if ($i + 1 -ge $全部参数.Count) {
            Write-Error "-d 后缺少输出目录"
            exit 2
        }
        $输出 = [string]$全部参数[++$i]
        continue
    }
    if ($arg -eq '-cp' -or $arg -eq '-classpath' -or $arg -eq '--class-path') {
        if ($i + 1 -ge $全部参数.Count) {
            Write-Error "$arg 后缺少类路径"
            exit 2
        }
        $类路径 = [string]$全部参数[++$i]
        continue
    }
    if ($arg.StartsWith('-')) {
        Write-Error "不支持的参数：$arg（该启动器只接受 -encoding、-d、-cp 与源码路径）"
        exit 2
    }
    $源文件 += $arg
}

if ($源文件.Count -eq 0) {
    Write-Error "没有源文件"
    exit 2
}
if (-not $输出) {
    $输出 = (Get-Location).Path
}

$cjcArgs = @('编译') + $源文件 + @('-d', $输出)
if ($类路径) {
    $cjcArgs += @('-cp', $类路径)
}
& "$root\cjc.ps1" @cjcArgs
exit $LASTEXITCODE
