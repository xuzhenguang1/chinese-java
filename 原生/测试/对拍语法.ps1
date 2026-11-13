# M8 自举二期：自举语法分析与 AST 打印，对拍 Java 参考实现与黄金快照。
# 用法：.\原生\测试\对拍语法.ps1 [-根目录 <仓库根>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
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

function 写文本([string]$路径, [string]$内容) {
    $编码 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($路径, $内容, $编码)
}

# 1) 编译自举前端（复用增量缓存）
& $cjc 原生 编译 $源 -o $exe *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    Write-Output "自举语法：编译失败"
    exit 1
}
Write-Output "自举语法：编译通过"

# 2) 参考输出与自举输出的逐字节比较
function 对拍([string]$名, [string[]]$目标) {
    $参考 = @(& $cjc 原生 语法 @目标 | Where-Object { $_ -match '^(文件|诊断|树|汇总)\|' })
    $自举 = @(& $exe 语法 $根目录 @目标)
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

# 3) 教程 35 课语法对拍
$教程 = Join-Path $根目录 "教程"
$教程输出 = @(对拍 "教程语法" @($教程))
if ($教程输出.Count -gt 0) {
    $汇总 = @($教程输出 | Where-Object { $_ -like '汇总|*' })
    if ($汇总.Count -eq 1 -and $汇总[0] -match '^汇总\|35\|') {
        Write-Output ("教程语法：逐字节一致（{0} 行，{1}）" -f $教程输出.Count, $汇总[0])
        $script:通过++
    } else {
        记录失败 ("教程语法：文件数不是 35（{0}）" -f ($汇总 -join ','))
    }
}

# 4) AST 快照对拍：自举 快照 产物 vs 原生\测试\语法树快照
$黄金 = Join-Path $根目录 "原生\测试\语法树快照"
$快照目录 = Join-Path $工作 "语法快照"
if (Test-Path $快照目录) {
    Remove-Item $快照目录 -Recurse -Force
}
& $exe 快照 $根目录 $快照目录 $教程 | Out-Null
$快照码 = $LASTEXITCODE
$黄金文件 = @(Get-ChildItem $黄金 -Filter *.ast.txt -File -ErrorAction SilentlyContinue)
$快照文件 = @(Get-ChildItem $快照目录 -Filter *.ast.txt -File -ErrorAction SilentlyContinue)
$快照差 = @()
if ($快照码 -eq 0 -and $黄金文件.Count -eq 35 -and $快照文件.Count -eq 35) {
    foreach ($基准 in $黄金文件) {
        $对应 = Join-Path $快照目录 $基准.Name
        if (-not (Test-Path $对应)) {
            $快照差 += "缺少 $($基准.Name)"
        } elseif ((Get-Content $基准.FullName -Raw) -cne (Get-Content $对应 -Raw)) {
            $快照差 += "不同 $($基准.Name)"
        }
    }
}
if ($快照码 -eq 0 -and $黄金文件.Count -eq 35 -and $快照文件.Count -eq 35 -and $快照差.Count -eq 0) {
    Write-Output "快照：35/35 一致"
    $script:通过++
} else {
    记录失败 ("快照：基准 {0} 份 / 自举 {1} 份 / 差异 {2} 条（{3}）" -f $黄金文件.Count, $快照文件.Count, $快照差.Count, ($快照差 -join '；'))
}
Remove-Item $快照目录 -Recurse -Force -ErrorAction SilentlyContinue

# 5) 语法反例：缺 ; / 缺 } / 非法表达式 / 缺 )
$反例目录 = Join-Path $工作 "反例语法"
if (Test-Path $反例目录) {
    Remove-Item $反例目录 -Recurse -Force
}
New-Item -ItemType Directory -Force $反例目录 | Out-Null

写文本 (Join-Path $反例目录 "缺分号.java") "整数 甲 = 1`n打印行(甲);`n"
写文本 (Join-Path $反例目录 "缺右大括号.java") "公开 类 甲 {`n    入口 {`n        打印行(1);`n"
写文本 (Join-Path $反例目录 "非法表达式.java") "打印行(1 + );`n"
写文本 (Join-Path $反例目录 "缺右括号.java") "打印行(`"甲`";`n"

$反例输出 = @(对拍 "语法反例" @($反例目录))
if ($反例输出.Count -gt 0) {
    $期望们 = @('缺少「;」', '缺少「}」', '无法识别的表达式', '缺少「)」')
    $缺 = @()
    foreach ($期望 in $期望们) {
        $命中 = @($反例输出 | Where-Object { $_.StartsWith('诊断|') -and $_.Contains($期望) }).Count
        if ($命中 -eq 0) {
            $缺 += $期望
        }
    }
    if ($缺.Count -eq 0) {
        Write-Output ("语法反例：一致（{0} 全部命中，{1} 行）" -f ($期望们 -join ' / '), $反例输出.Count)
        $script:通过++
    } else {
        记录失败 ("语法反例：缺少 " + ($缺 -join ' / '))
    }
}
Remove-Item $反例目录 -Recurse -Force -ErrorAction SilentlyContinue

Write-Output ""
Write-Output ("自举语法对拍：通过 {0} / 共 3 项" -f $script:通过)
if ($script:失败.Count -gt 0) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
exit 0
