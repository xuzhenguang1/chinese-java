# M18 跨文件自举三方对拍（A/B 自举 exe + JVM 参考）
#
# 断言：
#   1) 正例（6 文件）：A/B/参考 的 语义模块 与 中间模块 输出逐字节一致（换行归一化）；
#   2) 双输入：显式文件列表 与 -清单 依赖清单.txt 在 A 上输出逐字节一致；
#   3) 负例三例（双入口/未找到导入/引用不存在类型）：A/B/参考 诊断逐字节一致；
#   4) 黄金快照：参考侧输出与 自举多文件快照/ 逐字节一致（-更新快照 可重建）。
# 不比较 exe 二进制哈希（链接时间戳不稳定）。
# 用法：.\原生\测试\对拍自举模块.ps1 [-根目录 <目录>] [-更新快照]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [switch]$更新快照
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$类目录 = Join-Path $根目录 "构建\原生\前端\classes"
$主类 = "中文.原生.前端.前端主程序"
$exeA = Join-Path $根目录 "构建\自举\自举前端.exe"
$exeB = Join-Path $根目录 "构建\自举\自举前端-B.exe"
$样例目录 = Join-Path $根目录 "原生\测试\自举多文件样例"
$快照目录 = Join-Path $根目录 "原生\测试\自举多文件快照"
$清单 = Join-Path $样例目录 "依赖清单.txt"

$入口 = Join-Path $样例目录 "06主程序.java"
$其他 = @(Get-ChildItem $样例目录 -Filter "*.java" -File |
    Where-Object { $_.FullName -ne $入口 } | Sort-Object Name | ForEach-Object { $_.FullName })
$正例源 = @($入口) + @($其他)

$script:通过 = 0
$script:失败 = @()

function 归一([string[]]$行们) {
    return (($行们 -join "`n") -replace "`r`n", "`n")
}
function 记录失败([string]$消息) { $script:失败 += $消息 }

# 0) 前置：A/B exe 与参考 classes 必须存在。
foreach ($必需 in @($exeA, $exeB, $类目录)) {
    if (-not (Test-Path $必需)) {
        Write-Output "对拍自举模块：缺少 $必需"
        exit 1
    }
}
New-Item -ItemType Directory -Force $快照目录 | Out-Null

# 1) 正例三方对拍：语义模块 + 中间模块。
foreach ($命令 in @("语义模块", "中间模块")) {
    $参 = @($命令, $根目录) + $正例源
    $参考 = @(& java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp $类目录 $主类 @参 2>&1)
    $a = @(& $exeA $命令 $根目录 $正例源 2>&1)
    $b = @(& $exeB $命令 $根目录 $正例源 2>&1)
    $文本参考 = 归一 $参考
    $文本A = 归一 $a
    $文本B = 归一 $b
    if ($文本A -ceq $文本参考 -and $文本B -ceq $文本参考) {
        Write-Output ("正例 {0}：A==B==参考（{1} 行）" -f $命令, $参考.Count)
        $script:通过++
    } else {
        记录失败 ("正例 {0} 三方不一致（参考 {1} / A {2} / B {3} 行）" -f $命令, $参考.Count, $a.Count, $b.Count)
    }
}

# 2) 双输入一致性：显式列表 vs -清单（A/B 都验）。
foreach ($exe in @($exeA, $exeB)) {
    $名 = if ($exe -eq $exeA) { "A" } else { "B" }
    foreach ($命令 in @("语义模块", "中间模块")) {
        $参显 = @($命令, $根目录) + $正例源
        $显式 = @(& $exe @参显 2>&1)
        $清单出 = @(& $exe $命令 $根目录 -清单 $清单 2>&1)
        if ((归一 $显式) -ceq (归一 $清单出)) {
            Write-Output ("双输入 {0} {1}：显式列表 == -清单（{2} 行）" -f $名, $命令, $显式.Count)
            $script:通过++
        } else {
            记录失败 ("双输入 {0} {1} 不一致（显式 {2} / 清单 {3} 行）" -f $名, $命令, $显式.Count, $清单出.Count)
        }
    }
}

# 3) 负例三例：A/B/参考 诊断逐字节一致。
foreach ($例 in @("双入口", "未找到导入", "引用不存在类型")) {
    $目录 = Join-Path $样例目录 "负例\$例"
    $源 = @(Get-ChildItem $目录 -Filter "*.java" -File | Sort-Object Name | ForEach-Object { $_.FullName })
    $参 = @("语义模块", $根目录) + $源
    $参考 = @(& java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp $类目录 $主类 @参 2>&1)
    $a = @(& $exeA 语义模块 $根目录 $源 2>&1)
    $b = @(& $exeB 语义模块 $根目录 $源 2>&1)
    $参考诊断 = @($参考 | Where-Object { $_ -match '^诊断\|' })
    $a诊断 = @($a | Where-Object { $_ -match '^诊断\|' })
    $b诊断 = @($b | Where-Object { $_ -match '^诊断\|' })
    if ($参考诊断.Count -gt 0 -and
            (归一 $参考诊断) -ceq (归一 $a诊断) -and (归一 $参考诊断) -ceq (归一 $b诊断)) {
        Write-Output ("负例 {0}：A==B==参考（{1}）" -f $例, $参考诊断[0])
        $script:通过++
    } else {
        记录失败 ("负例 {0} 诊断三方不一致（参考 {1} / A {2} / B {3} 条）" -f $例, $参考诊断.Count, $a诊断.Count, $b诊断.Count)
    }
}

# 4) 黄金快照：参考侧输出落盘；非更新模式下逐字节核对。
foreach ($命令 in @("语义模块", "中间模块")) {
    $快照 = Join-Path $快照目录 ("参考." + $命令 + ".txt")
    $参 = @($命令, $根目录) + $正例源
    $参考 = @(& java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp $类目录 $主类 @参 2>&1)
    $文本 = 归一 $参考
    if ($更新快照) {
        [System.IO.File]::WriteAllText($快照, $文本 + "`n", (New-Object System.Text.UTF8Encoding($false)))
        Write-Output ("快照已更新：{0}（{1} 行）" -f $命令, $参考.Count)
        $script:通过++
    } else {
        if (-not (Test-Path $快照)) {
            记录失败 ("黄金快照缺失：$快照（请先 -更新快照）")
        } else {
            $旧 = [System.IO.File]::ReadAllText($快照, [System.Text.Encoding]::UTF8) -replace "`r`n", "`n"
            if ($旧 -ceq ($文本 + "`n")) {
                Write-Output ("黄金快照 {0}：一致（{1} 行）" -f $命令, $参考.Count)
                $script:通过++
            } else {
                记录失败 ("黄金快照 $命令 与参考输出不一致")
            }
        }
    }
}

$应过 = 2 + 4 + 3 + 2
Write-Output ("跨文件自举对拍：通过 {0} / 共 {1} 项" -f $script:通过, $应过)
if ($script:失败.Count -gt 0 -or $script:通过 -ne $应过) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
Write-Output "跨文件自举对拍：通过"
exit 0
