# 对话式中文 Java：一条命令完成「检查 → 编译 → 运行」。
# 用法：
#   .\工具\对话\运行.ps1 示例\脚本示例.java
#   .\工具\对话\运行.ps1 示例\标准库演示.java -类路径 标准库\target\cjc-stdlib-0.3.0.jar
#   .\工具\对话\运行.ps1 示例\入口示例.java -主类 入口示例
#   .\工具\对话\运行.ps1 示例\推断示例.java -允许推断
# 说明：不带 -类路径 时，若 标准库\target 下有 cjc-stdlib-*.jar 会自动带上。
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$文件,
    [string]$类路径,
    [string]$主类,
    [switch]$允许推断
)

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not (Test-Path -LiteralPath $文件)) {
    $候选 = Join-Path $root $文件
    if (Test-Path -LiteralPath $候选) {
        $文件 = $候选
    } else {
        Write-Host "错误：找不到文件 $文件" -ForegroundColor Red
        exit 2
    }
}

if (-not $类路径) {
    $标准库目录 = Join-Path $root '标准库\target'
    if (Test-Path -LiteralPath $标准库目录) {
        $jar = Get-ChildItem -LiteralPath $标准库目录 -Filter 'cjc-stdlib-*.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($jar) {
            $类路径 = $jar.FullName
        }
    }
}

$参数 = @('运行', $文件)
if ($类路径) { $参数 += '-cp', $类路径 }
if ($主类) { $参数 += '-主类', $主类 }
if ($允许推断) { $参数 += '--允许推断' }

& (Join-Path $root 'cjc.ps1') @参数
exit $LASTEXITCODE
