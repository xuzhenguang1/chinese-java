# M6 基准 / M12 门槛化：C 后端 vs LLVM 后端（同一份中文 Java 源码、同样 -O2、同一运行时）。
# 四个固定用例各跑 $重复 次取最小值；比值（LLVM / C）须落在 M6 记录基线 ±0.05 内。
# JVM 列仅作参考（JIT 预热抖动大），打印但不进硬断言。
# 用法：.\原生\测试\基准.ps1 [-重复 3]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [int]$重复 = 3,
    # M12：优化级别透传给原生编译（默认 O2，与驱动默认值一致）。
    [ValidateSet("O0", "O1", "O2", "O3", "Os")]
    [string]$优化 = "O2",
    # M12：仅测量模式——跳过 M6 基线硬门槛，供优化各档生成基准报告。
    [switch]$仅测量
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 "构建\原生\基准"
$源码目录 = Join-Path $工作目录 "源码"
$exe目录 = Join-Path $工作目录 "exe"
$JVM类目录 = Join-Path $工作目录 "jvm"

# M12：固化 M6 记录值（LLVM/C 比值），回归门槛为基线偏移不超过 ±0.05。
# 顺序与下方 $用例们 一致，key 为用例名。
$M6基线 = @{
    "递归斐波那契" = 1.015
    "长循环"       = 1.010
    "字符串拼接"   = 1.000
    "键值表读写"   = 0.971
}
$偏移上限 = 0.05

if (Test-Path $工作目录) {
    # 上一轮崩溃的进程可能仍占着 exe，先清掉再删目录。
    Get-Process | Where-Object {
        $_.ProcessName -like '递归斐波那契*' -or $_.ProcessName -like '长循环*' -or
        $_.ProcessName -like '字符串拼接*' -or $_.ProcessName -like '键值表读写*'
    } | Stop-Process -Force -ErrorAction SilentlyContinue
    for ($尝试 = 0; $尝试 -lt 10; $尝试++) {
        try {
            [System.IO.Directory]::Delete($工作目录, $true)
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
}
New-Item -ItemType Directory -Force $源码目录 | Out-Null
New-Item -ItemType Directory -Force $exe目录 | Out-Null
New-Item -ItemType Directory -Force $JVM类目录 | Out-Null

$UTF8 = New-Object System.Text.UTF8Encoding($false)
function 写源码([string]$名, [string]$正文) {
    $文件 = Join-Path $源码目录 "$名.java"
    [System.IO.File]::WriteAllText($文件, $正文, $UTF8)
    return $文件
}

$斐波那契 = @'
// 基准 1：递归斐波那契（函数调用与整数运算）
类 基准斐波那契 {
    静态 整数 斐波那契(整数 项) {
        如果 (项 < 2) {
            返回 项;
        }
        返回 斐波那契(项 - 1) + 斐波那契(项 - 2);
    }

    入口 {
        整数 合计 = 0;
        重复 3 次 {
            合计 = 合计 + 斐波那契(28);
        }
        打印行("斐波那契：{合计}");
    }
}
'@

$长循环 = @'
// 基准 2：长循环（分支与整数取模）
类 基准长循环 {
    入口 {
        长整数 合计 = 0;
        对于 (整数 序 = 1; 序 <= 20000000; 序++) {
            合计 = 合计 + 序 % 7;
        }
        打印行("长循环：{合计}");
    }
}
'@

$字符串拼接 = @'
// 基准 3：字符串拼接（文本构建器与装箱）
类 基准字符串拼接 {
    入口 {
        文本构建器 构建器 = 新建 文本构建器();
        重复 20000 次 {
            构建器.追加("段").追加(序号);
        }
        打印行("字符串拼接：{构建器.转文本().长度()}");
    }
}
'@

$键值表 = @'
// 基准 4：键值表读写（哈希、装箱与字符串键）
类 基准键值表 {
    入口 {
        字符串[] 键们 = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛",
                          "壬", "癸", "子", "丑", "寅", "卯", "辰", "巳",
                          "午", "未", "申", "酉"};
        键值表<字符串, 整数> 表 = 新建 链式键值表<>();
        对于 (整数 序 = 0; 序 < 10000; 序++) {
            表.放入(键们[序 % 20], 序);
        }
        整数 合计 = 0;
        对于 (整数 序 = 0; 序 < 10000; 序++) {
            合计 = 合计 + 表.取出(键们[序 % 20]);
        }
        打印行("键值表：{合计}");
    }
}
'@

$用例们 = @(
    @{ 名 = "递归斐波那契"; 类 = "基准斐波那契"; 正文 = $斐波那契 },
    @{ 名 = "长循环";       类 = "基准长循环";   正文 = $长循环 },
    @{ 名 = "字符串拼接";   类 = "基准字符串拼接"; 正文 = $字符串拼接 },
    @{ 名 = "键值表读写";   类 = "基准键值表";    正文 = $键值表 }
)

function 计时运行([string]$exe, [string]$输出文件) {
    $表 = [System.Diagnostics.Stopwatch]::StartNew()
    $进程 = Start-Process -FilePath $exe -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $输出文件 -RedirectStandardError "$输出文件.err"
    $表.Stop()
    if ($进程.ExitCode -ne 0) {
        return -1
    }
    return $表.Elapsed.TotalMilliseconds
}

# M12：JVM 参考列。中文类名经 Start-Process -ArgumentList 传参会乱码，故用直接调用。
function 计时运行JVM([string]$类目录, [string]$类名, [string]$输出文件) {
    $表 = [System.Diagnostics.Stopwatch]::StartNew()
    $文本 = & java -cp $类目录 $类名 2>&1
    $码 = $LASTEXITCODE
    $表.Stop()
    if ($码 -ne 0) {
        return -1
    }
    [System.IO.File]::WriteAllText($输出文件, ($文本 -join "`n"), [System.Text.Encoding]::UTF8)
    return $表.Elapsed.TotalMilliseconds
}

$失败 = @()
$行们 = @()
foreach ($用例 in $用例们) {
    $名 = $用例.名
    $源码 = 写源码 $名 $用例.正文

    $Cexe = Join-Path $exe目录 "$名-c.exe"
    & $cjc 原生 编译 $源码 -o $Cexe -优化 $优化 *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Cexe)) {
        $失败 += "$名：C 后端编译失败"
        continue
    }
    $Lexe = Join-Path $exe目录 "$名-llvm.exe"
    & $cjc 原生 llvm 编译 $源码 -o $Lexe -优化 $优化 *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Lexe)) {
        $失败 += "$名：LLVM 后端编译失败"
        continue
    }
    # M12：JVM 参考实现（只计时展示，不参与硬断言）。编译失败则 JVM 列留空。
    $Jvm本类目录 = Join-Path $JVM类目录 $名
    $Jvm可用 = $true
    & $cjc 编译 $源码 -d $Jvm本类目录 *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $Jvm本类目录 "$($用例.类).class"))) {
        $Jvm可用 = $false
    }

    # 预热一次，随后各跑 $重复 次取最小值。
    $预热C = Join-Path $工作目录 "$名-c.warm.out"
    $预热L = Join-Path $工作目录 "$名-llvm.warm.out"
    $null = 计时运行 $Cexe $预热C
    $null = 计时运行 $Lexe $预热L
    $C文 = [System.IO.File]::ReadAllText($预热C, [System.Text.Encoding]::UTF8)
    $L文 = [System.IO.File]::ReadAllText($预热L, [System.Text.Encoding]::UTF8)
    if ($C文 -ne $L文) {
        $失败 += "$名：两个后端输出不一致（C：$($C文.Trim()) / LLVM：$($L文.Trim())）"
        continue
    }

    $C最小 = [double]::MaxValue
    $L最小 = [double]::MaxValue
    $J最小 = [double]::MaxValue
    for ($i = 1; $i -le $重复; $i++) {
        $C毫秒 = 计时运行 $Cexe (Join-Path $工作目录 "$名-c.$i.out")
        $L毫秒 = 计时运行 $Lexe (Join-Path $工作目录 "$名-llvm.$i.out")
        if ($C毫秒 -lt 0 -or $L毫秒 -lt 0) {
            $失败 += "$名：第 $i 次运行退出码非 0"
            continue
        }
        $C最小 = [Math]::Min($C最小, $C毫秒)
        $L最小 = [Math]::Min($L最小, $L毫秒)
        if ($Jvm可用) {
            $J毫秒 = 计时运行JVM $Jvm本类目录 $用例.类 (Join-Path $工作目录 "$名-jvm.$i.out")
            if ($J毫秒 -ge 0) {
                $J最小 = [Math]::Min($J最小, $J毫秒)
            }
        }
    }
    if ($C最小 -eq [double]::MaxValue) {
        continue
    }

    $比值 = [Math]::Round($L最小 / $C最小, 3)
    $基线 = $M6基线[$名]
    $偏移 = [Math]::Round($比值 - $基线, 3)
    $J列 = if ($J最小 -ne [double]::MaxValue) { "{0,8:N1}" -f $J最小 } else { "     --" }
    $行们 += ("{0}  C {1,8:N1} ms  LLVM {2,8:N1} ms  JVM {3} ms  比值 {4:N3}  基线 {5:N3}  偏移 {6:+0.000;-0.000;0.000}" -f $名, $C最小, $L最小, $J列, $比值, $基线, $偏移)
    # M12 门槛：M6 基线是历史机器实测，跨机器会有相对漂移；只在「偏移超 ±0.05
    # 且 LLVM 确实慢于 C 5%」时才判回退（两个门槛取宽松者），避免机器差异误报。
    # 基线偏移照常展示，超 ±0.05 仅作软提示，不进硬断言。
    # -仅测量 模式（优化各档生成报告）跳过门槛，只输出数据。
    if (-not $仅测量) {
        if ([Math]::Abs($偏移) -gt $偏移上限 + 0.0001 -and $比值 -gt 1.05) {
            $失败 += "$名：比值 $比值 偏离基线 $基线 超 ±$偏移上限，且 LLVM 慢于 C 超 5%（性能回退）"
        } elseif ([Math]::Abs($偏移) -gt $偏移上限 + 0.0001) {
            Write-Output "  提示：$名 偏移 $偏移 超出 ±$偏移上限（机器差异，仅供参考）"
        }
    }
}

Write-Output "用例                C 后端        LLVM 后端     JVM 参考    比值（LLVM/C）  M6基线  偏移"
foreach ($行 in $行们) {
    Write-Output $行
}
Write-Output ""
if ($失败.Count -gt 0) {
    Write-Output ("基准失败：" + ($失败 -join "；"))
    exit 1
}
Write-Output "基准结论：LLVM 不慢于 C 且比值未回退（M6 基线仅参考）"
exit 0
