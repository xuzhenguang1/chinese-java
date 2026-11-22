# M9 语义自举：自举语义分析与 Java 参考实现的逐字节对拍。
# 用法：.\原生\测试\对拍语义.ps1 [-根目录 <仓库根>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$源 = Join-Path $根目录 "原生\自举\自举前端.java"
$工作 = Join-Path $根目录 "构建\自举"
New-Item -ItemType Directory -Force $工作 | Out-Null
$exe = Join-Path $工作 "自举前端.exe"

$script:通过 = 0
$script:失败 = @()

function 记录失败([string]$消息) {
    $script:失败 += $消息
}

# 1) 编译自举前端（复用增量缓存）
& $cjc 原生 编译 $源 -o $exe *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    Write-Output "自举语义：编译失败"
    exit 1
}
Write-Output "自举语义：编译通过"

# 2) 参考输出与自举输出的逐字节比较
function 对拍([string]$名, [string[]]$目标) {
    $参考 = @(& $cjc 原生 语义 @目标 | Where-Object { $_ -match '^(类|字段|方法|诊断|汇总)\|' })
    $自举 = @(& $exe 语义 $根目录 @目标)
    if ($参考.Count -ne $自举.Count) {
        记录失败 ("{0}：行数不同（参考 {1} / 自举 {2}）" -f $名, $参考.Count, $自举.Count)
        return @()
    }
    $差 = @(Compare-Object $参考 $自举 -SyncWindow 40)
    if ($差.Count -gt 0) {
        $首 = ($差 | Select-Object -First 3 | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join "；"
        记录失败 ("{0}：{1} 行不同（{2}）" -f $名, $差.Count, $首)
        return @()
    }
    return $自举
}

# 3) 教程 35 课语义对拍
$教程 = Join-Path $根目录 "教程"
$教程输出 = @(对拍 "教程语义" @($教程))
if ($教程输出.Count -gt 0) {
    $汇总 = @($教程输出 | Where-Object { $_ -like '汇总|*' })
    if ($汇总.Count -eq 1 -and $汇总[0] -match '^汇总\|35\|') {
        Write-Output ("教程语义：逐字节一致（{0} 行，{1}）" -f $教程输出.Count, $汇总[0])
        $script:通过++
    } else {
        记录失败 ("教程语义：文件数不是 35（{0}）" -f ($汇总 -join ','))
    }
}

# 4) 语义快照对拍：自举逐文件产物 vs 原生\测试\语义快照
$黄金 = Join-Path $根目录 "原生\测试\语义快照"
$基准文件 = @(Get-ChildItem $黄金 -Filter *.语义.txt -File -ErrorAction SilentlyContinue)
$快照差 = @()
if ($基准文件.Count -eq 35) {
    foreach ($基准 in $基准文件) {
        $名 = $基准.Name -replace '\.语义\.txt$', ''
        $源文件 = Join-Path $教程 ($名 + ".java")
        if (-not (Test-Path $源文件)) {
            $快照差 += "缺少源 $($名).java"
            continue
        }
        $实际 = @(& $exe 语义 $根目录 $源文件) -join "`n"
        $期望 = [System.IO.File]::ReadAllText($基准.FullName, (New-Object System.Text.UTF8Encoding($false))) -replace "`r`n", "`n"
        if (($实际 + "`n") -cne $期望) {
            $快照差 += "不同 $($基准.Name)"
        }
    }
}
if ($基准文件.Count -eq 35 -and $快照差.Count -eq 0) {
    Write-Output "语义快照：35/35 一致"
    $script:通过++
} else {
    记录失败 ("语义快照：基准 {0} 份 / 差异 {1} 条（{2}）" -f $基准文件.Count, $快照差.Count, ($快照差 -join '；'))
}

# 5) 语义反例：20 条单错用例，两侧诊断与期望输出逐字节一致
$反例目录 = Join-Path $根目录 "原生\测试\反例语义"
$反例文件 = @(Get-ChildItem $反例目录 -Filter *.java -File -ErrorAction SilentlyContinue)
$反例差 = @()
$命中 = 0
foreach ($反例 in $反例文件) {
    $期望路径 = Join-Path $反例目录 ($反例.BaseName + ".语义.txt")
    if (-not (Test-Path $期望路径)) {
        $反例差 += "缺少期望 $($反例.BaseName).语义.txt"
        continue
    }
    $参考 = @(& $cjc 原生 语义 $反例.FullName | Where-Object { $_ -match '^(类|字段|方法|诊断|汇总)\|' })
    $自举 = @(& $exe 语义 $根目录 $反例.FullName)
    $期望 = [System.IO.File]::ReadAllText($期望路径, (New-Object System.Text.UTF8Encoding($false))) -replace "`r`n", "`n"
    if ((($参考 -join "`n") + "`n") -cne $期望) {
        $反例差 += "参考不同 $($反例.BaseName)"
    }
    if ((($自举 -join "`n") + "`n") -cne $期望) {
        $反例差 += "自举不同 $($反例.BaseName)"
    }
    if (@($自举 | Where-Object { $_ -like '诊断|*' }).Count -eq 1) {
        $命中++
    }
}
if ($反例文件.Count -ge 12 -and $反例差.Count -eq 0 -and $命中 -eq $反例文件.Count) {
    Write-Output ("语义反例：一致（{0} 条全部命中）" -f $反例文件.Count)
    $script:通过++
} else {
    记录失败 ("语义反例：{0} 条 / 命中 {1} / 差异 {2} 条（{3}）" -f $反例文件.Count, $命中, $反例差.Count, ($反例差 -join '；'))
}

Write-Output ""
Write-Output ("自举语义对拍：通过 {0} / 共 3 项" -f $script:通过)
if ($script:失败.Count -gt 0) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
exit 0
