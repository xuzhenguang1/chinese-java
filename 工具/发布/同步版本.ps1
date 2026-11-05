# 版本单一源：所有版本号集中维护在 工具\版本.json，本脚本负责校验与写回。
#
# 用法：
#   .\工具\发布\同步版本.ps1          # 只校验（默认，CI 与 校验.ps1 使用）
#   .\工具\发布\同步版本.ps1 -写入    # 把 版本.json 的值写回各文件
#
# 退出码：0 一致；1 存在不一致；2 缺少版本文件。

param(
    [switch]$写入
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$版本文件 = Join-Path $root "工具\版本.json"
if (-not (Test-Path $版本文件)) {
    Write-Error "缺少版本文件：$版本文件"
    exit 2
}
$版本 = Get-Content $版本文件 -Raw -Encoding UTF8 | ConvertFrom-Json
$语言 = $版本.'语言'
$发布 = $版本.'发布'
$扩展 = $版本.'扩展'
$插件 = $版本.'Maven插件'

$script:不一致 = 0

# 读取 UTF-8 文本并按原文件的 BOM 状态写回，避免 PowerShell 脚本丢失 BOM 后被当成 ANSI。
function 读取([string]$路径) {
    return [System.IO.File]::ReadAllText($路径, [System.Text.Encoding]::UTF8)
}

function 写回([string]$路径, [string]$文本) {
    $编码 = New-Object System.Text.UTF8Encoding($false)
    $字节 = [System.IO.File]::ReadAllBytes($路径)
    if ($字节.Length -ge 3 -and $字节[0] -eq 0xEF -and $字节[1] -eq 0xBB -and $字节[2] -eq 0xBF) {
        $编码 = New-Object System.Text.UTF8Encoding($true)
    }
    [System.IO.File]::WriteAllText($路径, $文本, $编码)
}

function 同步([string]$相对路径, [string]$说明, [string]$正则, [string]$新值, [string]$期望) {
    $路径 = Join-Path $root $相对路径
    if (-not (Test-Path $路径)) {
        $script:不一致++
        Write-Host "缺失：$相对路径（$说明）" -ForegroundColor Red
        return
    }
    $原文 = 读取 $路径
    $匹配 = ([regex]$正则).Match($原文)
    if (-not $匹配.Success) {
        $script:不一致++
        Write-Host "未匹配：$说明（$相对路径 结构可能已变）" -ForegroundColor Red
        return
    }
    $当前 = $匹配.Groups['版本'].Value
    if ($当前 -eq $期望) {
        Write-Host "通过：$说明（$期望）" -ForegroundColor Green
        return
    }
    if ($写入) {
        $评估 = [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $新值 }
        $新文 = ([regex]$正则).Replace($原文, $评估, 1)
        写回 $路径 $新文
        Write-Host "已写回：$说明（$当前 → $期望）" -ForegroundColor Yellow
        return
    }
    $脚本:不一致++
    Write-Host "不一致：$说明（当前「$当前」，应为「$期望」）" -ForegroundColor Red
}

# 语言版本：关键字表表头
同步 "工具\cjc\关键字表.txt" "关键字表表头" '(?m)^# 中文 Java 关键字表 (?<版本>v[\d.]+)' `
    ('# 中文 Java 关键字表 v' + $语言) ("v" + $语言)

# 发布版本：发布.ps1 现在直接读取 版本.json，无需写回。
# VSCode 扩展版本
同步 "工具\vscode-中文java\package.json" "VSCode 扩展版本" '(?m)^\s*"version": "(?<版本>[^"]+)"' `
    ('  "version": "' + $扩展 + '",') $扩展

# Maven 插件版本：插件工程 pom 的项目版本
同步 "工具\maven-插件\pom.xml" "Maven 插件版本" '(?m)^    <version>(?<版本>[^<]+)</version>' `
    ('    <version>' + $插件 + '</version>') $插件

# Maven 插件版本：Cjc 脚手架提示与生成的 pom（第一条 <version> 即插件版本）
同步 "工具\cjc\Cjc.java" "脚手架提示中的插件版本" '(?<=cjc-maven-plugin )(?<版本>[0-9][0-9.]*)' `
    $插件 $插件
同步 "工具\cjc\Cjc.java" "脚手架 pom 中的插件版本" '(?m)^        sb\.append\("                <version>(?<版本>[0-9.]+)' `
    ('        sb.append("                <version>' + $插件) $插件

Write-Host ""
if ($script:不一致 -gt 0) {
    Write-Host "版本不一致 $($script:不一致) 处：改 工具\版本.json 后运行 .\工具\发布\同步版本.ps1 -写入" -ForegroundColor Red
    exit 1
}
Write-Host "版本一致：语言 v$语言 / 发布 $发布 / 扩展 $扩展 / Maven 插件 $插件" -ForegroundColor Green
exit 0
