# M8 自举一期：自举词法器与 Java 参考实现的逐字节对拍。
# 用法：.\原生\测试\对拍分词.ps1 [-根目录 <仓库根>]
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

# 1) 编译自举词法器
& $cjc 原生 编译 $源 -o $exe *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    Write-Output "自举分词：编译失败"
    exit 1
}
Write-Output "自举分词：编译通过"

# 2) 词表读数对拍
$参考词表 = @(& $cjc 原生 词表 | Where-Object { $_ -match '^(词表桥接|  )' -and $_ -notmatch '^  来源：' })
$自举词表 = @(& $exe 词表 $根目录)
if ($参考词表.Count -eq $自举词表.Count -and @(Compare-Object $参考词表 $自举词表).Count -eq 0) {
    Write-Output ("词表读数：一致（{0} 行）" -f $自举词表.Count)
    $script:通过++
} else {
    记录失败 "词表读数不一致"
}

# 3) 分词对拍：返回自举输出，失败时返回空数组
function 对拍([string]$名, [string[]]$目标) {
    $参考 = @(& $cjc 原生 分词 @目标 | Where-Object { $_ -match '^(文件|标记|诊断|汇总)\|' })
    $自举 = @(& $exe 分词 $根目录 @目标)
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

# 4) 教程 35 课全量对拍
$教程 = Join-Path $根目录 "教程"
$教程输出 = @(对拍 "教程" @($教程))
if ($教程输出.Count -gt 0) {
    $汇总 = @($教程输出 | Where-Object { $_ -like '汇总|*' })
    if ($汇总.Count -eq 1 -and $汇总[0] -match '^汇总\|35\|') {
        Write-Output ("教程分词：逐字节一致（{0} 行，{1}）" -f $教程输出.Count, $汇总[0])
        $script:通过++
    } else {
        记录失败 ("教程：文件数不是 35（{0}）" -f ($汇总 -join ','))
    }
}

# 5) 词法反例：CJK001 粘连 / CJK002 坏标点 / CJK106 句读折叠
$反例目录 = Join-Path $工作 "反例"
if (Test-Path $反例目录) {
    Remove-Item $反例目录 -Recurse -Force
}
New-Item -ItemType Directory -Force $反例目录 | Out-Null

写文本 (Join-Path $反例目录 "粘连.java") @'
公开 类 反例粘连 {
    入口 {
        整数 值 = 1;
        打印行(最终值);
    }
}
'@
写文本 (Join-Path $反例目录 "弯引号.java") @'
公开 类 反例引号 {
    入口 {
        打印行(“你好”);
    }
}
'@
写文本 (Join-Path $反例目录 "句读.java") @'
公开 类 反例句读 {
    入口 {
        整数 甲 = 1、整数 乙 = 2。
        打印行(甲 + 乙);
    }
}
'@

$反例输出 = @(对拍 "词法反例" @($反例目录))
if ($反例输出.Count -gt 0) {
    $期望们 = @(
        @('CJK001', '诊断|错误|CJK001|'),
        @('CJK002', '诊断|错误|CJK002|'),
        @('CJK106', '诊断|提示|CJK106|')
    )
    $缺 = @()
    foreach ($期望 in $期望们) {
        if (@($反例输出 | Where-Object { $_.StartsWith($期望[1]) }).Count -eq 0) {
            $缺 += $期望[0]
        }
    }
    if ($缺.Count -eq 0) {
        Write-Output ("词法反例：一致（CJK001 / CJK002 / CJK106 全部命中，{0} 行）" -f $反例输出.Count)
        $script:通过++
    } else {
        记录失败 ("词法反例：缺少 " + ($缺 -join ' / '))
    }
}

# 6) 中文路径断言：argv 与文件名都带中文
$中文目录 = Join-Path $反例目录 "中文路径"
New-Item -ItemType Directory -Force $中文目录 | Out-Null
写文本 (Join-Path $中文目录 "问候.java") @'
公开 类 问候 {
    入口 {
        打印行("你好，中文路径");
    }
}
'@
$中文输出 = @(对拍 "中文路径" @((Join-Path $中文目录 "问候.java")))
if ($中文输出.Count -gt 0 -and @($中文输出 | Where-Object { $_ -eq '文件|问候.java' }).Count -eq 1) {
    Write-Output ("中文路径：一致（{0} 行）" -f $中文输出.Count)
    $script:通过++
}

Write-Output ""
Write-Output ("自举分词对拍：通过 {0} / 共 4 项" -f $script:通过)
if ($script:失败.Count -gt 0) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
exit 0
