# M11 自举五期：自举不动点（等价验证）
#
# 背景：参考实现的 C 后端只接受内存中的中间代码.程序对象，全仓没有
# 「MIR 文本 -> 程序对象」的解析入口，因此无法按字面把第一阶 MIR 文本
# 直接喂给 C 后端编译出 B。本脚本采用等价不动点口径：
#   A = 现有自举 exe（构建\自举\自举前端.exe）
#   B = 用标准编译命令从同一份自举源码独立重新编译出的 exe（...-B.exe）
# 断言：
#   1) B 对自身源码产出的 MIR == 参考基线 == A 对自身源码产出的 MIR；
#   2) B 跑教程 01–35 的词表/分词/语法/语义/中间码输出与 A 逐字节一致；
#   3) M18：A/B 对专用多文件样例的 语义模块/中间模块 输出与 JVM 参考逐字节一致。
# 不比较 exe 二进制哈希（链接时间戳不稳定），只比较 MIR 文本与运行输出。
# 用法：.\原生\测试\对拍自举不动点.ps1 [-根目录 <仓库根>]

param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$源 = Join-Path $根目录 "原生\自举\自举前端.java"
$工作 = Join-Path $根目录 "构建\自举"
$exeA = Join-Path $工作 "自举前端.exe"
$exeB = Join-Path $工作 "自举前端-B.exe"
$基线 = Join-Path $根目录 "原生\测试\MIR快照\自举前端.mir.txt"
$教程 = Join-Path $根目录 "教程"
$类目录 = Join-Path $根目录 "构建\原生\前端\classes"
$主类 = "中文.原生.前端.前端主程序"
$样例目录 = Join-Path $根目录 "原生\测试\自举多文件样例"

New-Item -ItemType Directory -Force $工作 | Out-Null

$script:通过 = 0
$script:失败 = @()

function 记录失败([string]$消息) {
    $script:失败 += $消息
}

# 0) 前置：A 必须存在；用标准编译命令独立编译 B。
if (-not (Test-Path $exeA)) {
    Write-Output "自举不动点：A（自举前端.exe）缺失，请先运行对拍中间码.ps1"
    exit 1
}
$编译输出 = & $cjc 原生 编译 $源 -o $exeB 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exeB)) {
    Write-Output "自举不动点：B 编译失败"
    $编译输出 | Select-Object -Last 8 | ForEach-Object { Write-Output $_ }
    exit 1
}
Write-Output "自举不动点：A/B 就绪"

# 1) 自身 MIR：A == 基线 == B
$基线文本 = [System.IO.File]::ReadAllText($基线, (New-Object System.Text.UTF8Encoding($false))) -replace "`r`n", "`n"
$a自身 = @(& $exeA 中间 $根目录 $源)
$b自身 = @(& $exeB 中间 $根目录 $源)
$a文本 = ($a自身 -join "`n") + "`n"
$b文本 = ($b自身 -join "`n") + "`n"
if ($a文本 -ceq $基线文本 -and $b文本 -ceq $基线文本) {
    Write-Output ("自身 MIR：A==基线==B（{0} 行）" -f [Math]::Max(0, $b自身.Count - 1))
    $script:通过++
} else {
    $首差 = ""
    $n = [Math]::Max($a自身.Count, $b自身.Count)
    for ($i = 0; $i -lt $n; $i++) {
        $a = if ($i -lt $a自身.Count) { $a自身[$i] } else { "" }
        $b = if ($i -lt $b自身.Count) { $b自身[$i] } else { "" }
        if ($a -cne $b) {
            $首差 = ("首个差异第 {0} 行：A <{1}> B <{2}>" -f ($i + 1), $a, $b)
            break
        }
    }
    记录失败 ("自身 MIR：A/B/基线不一致（{0}）" -f $首差)
}

# 2) 教程 01–35 × 5 命令：A 与 B 逐字节一致
$课程 = @(Get-ChildItem $教程 -Filter "第*课*.java" -File |
    Where-Object { $_.Name -match '^第(0[1-9]|[12][0-9]|3[0-5])课' } |
    Sort-Object Name)
$命令们 = @('词表', '分词', '语法', '语义', '中间')
$比对数 = 0
$差异 = @()
foreach ($课 in $课程) {
    foreach ($命令 in $命令们) {
        $比对数++
        $输出A = @(& $exeA $命令 $根目录 $课.FullName 2>$null)
        $输出B = @(& $exeB $命令 $根目录 $课.FullName 2>$null)
        if ($输出A.Count -ne $输出B.Count) {
            $差异 += ("{0} {1} 行数不同（{2}/{3}）" -f $课.Name, $命令, $输出A.Count, $输出B.Count)
            continue
        }
        $差 = @(Compare-Object $输出A $输出B -SyncWindow 40)
        if ($差.Count -gt 0) {
            $首 = ($差 | Select-Object -First 1 | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ""
            $差异 += ("{0} {1} {2} 行不同（{3}）" -f $课.Name, $命令, $差.Count, $首)
        }
    }
}
if ($课程.Count -eq 35 -and $差异.Count -eq 0) {
    Write-Output ("教程输出：A==B（{0} 课 × {1} 命令 = {2} 组逐字节一致）" -f $课程.Count, $命令们.Count, $比对数)
    $script:通过++
} else {
    记录失败 ("教程输出：{0} 课 / {1} 组比对 / 差异 {2} 条（{3}）" -f $课程.Count, $比对数, $差异.Count, ($差异 | Select-Object -First 5 | ForEach-Object { $_ }) -join '；')
}

# 3) M18：跨文件三方一致（A/B 自举 exe == JVM 参考），含语义模块与中间模块。
$样例入口 = Join-Path $样例目录 "06主程序.java"
$样例其他 = @(Get-ChildItem $样例目录 -Filter "*.java" -File |
    Where-Object { $_.FullName -ne $样例入口 } | Sort-Object Name | ForEach-Object { $_.FullName })
$样例源 = @($样例入口) + @($样例其他)
$跨文件差异 = @()
foreach ($命令 in @('语义模块', '中间模块')) {
    $参考参 = @($命令, $根目录) + $样例源
    $参考出 = @(& java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp $类目录 $主类 @参考参 2>$null)
    $a出 = @(& $exeA $命令 $根目录 $样例源 2>$null)
    $b出 = @(& $exeB $命令 $根目录 $样例源 2>$null)
    $归一 = { param($行们) ($行们 -join "`n") -replace "`r`n", "`n" }
    $参考文 = & $归一 $参考出
    $a文 = & $归一 $a出
    $b文 = & $归一 $b出
    if ($a文 -cne $参考文 -or $b文 -cne $参考文) {
        $跨文件差异 += "$命令 三方不一致"
    }
}
if ($跨文件差异.Count -eq 0) {
    Write-Output "跨文件三方：A==B==参考（语义模块 + 中间模块，6 文件专用样例）"
    $script:通过++
} else {
    记录失败 ("跨文件三方：" + ($跨文件差异 -join "；"))
}

Write-Output ("自举不动点检查：通过 {0} / 共 3 项" -f $script:通过)
if ($script:失败.Count -gt 0 -or $script:通过 -ne 3) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
Write-Output "自举不动点：通过"
exit 0
