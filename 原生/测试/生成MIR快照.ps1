# 重新生成 M2 MIR 黄金快照：调用 原生\前端 的「中间」命令，不手写任何快照内容。
# 用法：.\原生\测试\生成MIR快照.ps1 [-输出目录 <目录>] [-路径 <文件或目录...>]
param(
    [string]$输出目录 = (Join-Path $PSScriptRoot "MIR快照"),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
$驱动 = Join-Path $根目录 "原生\驱动.ps1"
$类目录 = Join-Path $根目录 "构建\原生\前端\classes"
$主类 = "中文.原生.前端.前端主程序"

$文件们 = @()
if ($路径 -and $路径.Count -gt 0) {
    foreach ($项 in $路径) {
        if (Test-Path $项 -PathType Container) {
            # M2 只对教程 01–10 负责，目录输入自动收敛到这一段。
            $文件们 += @(Get-ChildItem $项 -Filter *.java -File |
                Where-Object { $_.Name -match '^第(0[1-9]|10)课' } | Sort-Object Name)
        } elseif (Test-Path $项) {
            $文件们 += @(Get-Item $项)
        } else {
            Write-Output "路径不存在：$项"
            exit 2
        }
    }
} else {
    $文件们 = @(Get-ChildItem (Join-Path $根目录 "教程") -Filter *.java -File |
        Where-Object { $_.Name -match '^第(0[1-9]|10)课' } | Sort-Object Name)
}

if ($文件们.Count -eq 0) {
    Write-Output "没有找到要生成快照的文件。"
    exit 2
}

# 先让驱动完成一次前端编译，避免编译日志混入快照。
& $驱动 中间 $文件们[0].FullName *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Output "前端编译或分析失败。"
    exit 2
}

New-Item -ItemType Directory -Force $输出目录 | Out-Null

foreach ($文件 in $文件们) {
    $目标 = Join-Path $输出目录 ($文件.BaseName + ".mir.txt")
    $临时 = Join-Path ([System.IO.Path]::GetTempPath()) ($文件.BaseName + ".mir.tmp")
    $参数 = @(
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-cp", $类目录,
        $主类, "中间", $根目录, $文件.FullName
    )
    $进程 = Start-Process -FilePath "java" -ArgumentList $参数 -NoNewWindow -Wait -PassThru -RedirectStandardOutput $临时
    if ($进程.ExitCode -ne 0) {
        Write-Output ("生成失败：{0}" -f $文件.Name)
        exit 1
    }
    $文本 = [System.IO.File]::ReadAllText($临时, (New-Object System.Text.UTF8Encoding($false)))
    $文本 = $文本 -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($目标, $文本, (New-Object System.Text.UTF8Encoding($false)))
    Remove-Item $临时 -Force
    Write-Output ("快照  " + $文件.BaseName)
}

Write-Output ("MIR 快照：{0} 份 -> {1}" -f $文件们.Count, $输出目录)
exit 0
