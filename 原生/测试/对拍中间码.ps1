# M10 中间码自举：自举 MIR 降级与 Java 参考实现的逐字节对拍。
# 用法：.\原生\测试\对拍中间码.ps1 [-根目录 <仓库根>]
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

# 1) 编译自举前端（复用增量缓存）
# 当前 Windows clang/ld 对中文输出路径解析异常，先链接到 ASCII 临时路径再复制回目标位置。
$临时exe = Join-Path ([System.IO.Path]::GetTempPath()) "cjc_boot_mir.exe"
& $cjc 原生 编译 $源 -o $临时exe *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $临时exe)) {
    Write-Output "自举中间码：编译失败"
    exit 1
}
Copy-Item -LiteralPath $临时exe -Destination $exe -Force
Remove-Item -LiteralPath $临时exe -Force -ErrorAction SilentlyContinue
Write-Output "自举中间码：编译通过"

# 2) 教程 01–35：自举输出 vs 参考实现输出，逐文件逐行比较
$教程 = Join-Path $根目录 "教程"
$课程 = @(Get-ChildItem $教程 -Filter "第*课*.java" -File |
    Where-Object { $_.Name -match '^第(0[1-9]|[12][0-9]|3[0-5])课' } |
    Sort-Object Name)
$教程差 = @()
$内容行 = 0
foreach ($课 in $课程) {
    $参考 = @(& $cjc 原生 中间 $课.FullName)
    $自举 = @(& $exe 中间 $根目录 $课.FullName)
    if ($参考.Count -ne $自举.Count) {
        $教程差 += ("{0} 行数不同（参考 {1} / 自举 {2}）" -f $课.Name, $参考.Count, $自举.Count)
        continue
    }
    $差 = @(Compare-Object $参考 $自举 -SyncWindow 40)
    if ($差.Count -gt 0) {
        $首 = ($差 | Select-Object -First 2 | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join "；"
        $教程差 += ("{0} {1} 行不同（{2}）" -f $课.Name, $差.Count, $首)
        continue
    }
    $内容行 += [Math]::Max(0, $自举.Count - 1)
}
if ($课程.Count -eq 35 -and $教程差.Count -eq 0) {
    Write-Output ("教程中间码：逐字节一致（{0} 份，{1} 行）" -f $课程.Count, $内容行)
    $script:通过++
} else {
    记录失败 ("教程中间码：{0} 份 / 差异 {1} 条（{2}）" -f $课程.Count, $教程差.Count, ($教程差 -join '；'))
}

# 3) 快照对拍：自举逐文件产物 vs 原生\测试\MIR快照
$黄金 = Join-Path $根目录 "原生\测试\MIR快照"
# 只纳入 35 课教程快照；自举前端自身的当前基线由第 4 项单独对拍，
# M18 冻结的历史基线（自举前端.历史.mir.txt）只读对照，均不参与教程快照循环。
$基准文件 = @(Get-ChildItem $黄金 -Filter *.mir.txt -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^第.*课.*\.mir\.txt$' })
$快照差 = @()
foreach ($基准 in $基准文件) {
    $名 = $基准.Name -replace '\.mir\.txt$', ''
    $源文件 = Join-Path $教程 ($名 + ".java")
    if (-not (Test-Path $源文件)) {
        $快照差 += "缺少源 $($名).java"
        continue
    }
    $实际 = @(& $exe 中间 $根目录 $源文件) -join "`n"
    $期望 = [System.IO.File]::ReadAllText($基准.FullName, (New-Object System.Text.UTF8Encoding($false))) -replace "`r`n", "`n"
    if (($实际 + "`n") -cne $期望) {
        $快照差 += "不同 $($基准.Name)"
    }
}
if ($基准文件.Count -eq 35 -and $快照差.Count -eq 0) {
    Write-Output ("中间码快照：{0}/{0} 一致" -f $基准文件.Count)
    $script:通过++
} else {
    记录失败 ("中间码快照：基准 {0} 份 / 差异 {1} 条（{2}）" -f $基准文件.Count, $快照差.Count, ($快照差 -join '；'))
}

# 4) 自举自举第一阶：自举 exe 对自身源码产出的 MIR，与参考实现基线逐字节一致。
$自身基线 = Join-Path $黄金 "自举前端.mir.txt"
$自身源 = Join-Path $根目录 "原生\自举\自举前端.java"
if (Test-Path $自身基线) {
    $自身实际 = @(& $exe 中间 $根目录 $自身源)
    $自身期望 = [System.IO.File]::ReadAllText($自身基线, (New-Object System.Text.UTF8Encoding($false))) -replace "`r`n", "`n"
    $自身文本 = ($自身实际 -join "`n") + "`n"
    if ($自身文本 -ceq $自身期望) {
        Write-Output ("自举前端自身中间码：逐字节一致（{0} 行）" -f [Math]::Max(0, $自身实际.Count - 1))
        $script:通过++
    } else {
        # 定位首个差异行，便于诊断
        $期望行 = @($自身期望 -split "`n")
        $首差 = ""
        $n = [Math]::Max($自身实际.Count, $期望行.Count)
        for ($i = 0; $i -lt $n; $i++) {
            $a = if ($i -lt $自身实际.Count) { $自身实际[$i] } else { "" }
            $e = if ($i -lt $期望行.Count) { $期望行[$i] } else { "" }
            if ($a -cne $e) {
                $首差 = ("首个差异第 {0} 行：期望 <{1}> 实际 <{2}>" -f ($i + 1), $e, $a)
                break
            }
        }
        记录失败 ("自举前端自身中间码：不一致（{0}）" -f $首差)
    }
} else {
    记录失败 "自举前端自身中间码：缺少基线 $自身基线"
}

Write-Output ""
Write-Output ("自举中间码对拍：通过 {0} / 共 3 项" -f $script:通过)
if ($script:失败.Count -gt 0) {
    Write-Output ("失败：" + ($script:失败 -join "；"))
    exit 1
}
exit 0
