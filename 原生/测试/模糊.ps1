# M17 模糊测试：随机 Token 序列喂给 cjc 检查，断言健壮性（不崩溃 / 不挂起 / 中文诊断）。
# 只断言健壮性，不做输出逐字节对拍（随机输入无期望输出）。
# 用法：.\原生\测试\模糊.ps1 [-种子 20261122] [-轮数 200] [-超时毫秒 10000]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [int]$种子 = 20261122,
    [int]$轮数 = 50,
    [int]$超时毫秒 = 10000,
    [int]$最小token = 3,
    [int]$最大token = 40
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 同进程清代理变量：规避 Windows PowerShell 5.1 大小写双份代理导致子进程启动失败（M15/M16 已知）。
foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$java源 = Join-Path $根目录 "工具\cjc\Cjc.java"
$工作目录 = Join-Path $根目录 "构建\原生\模糊"
$样本目录 = Join-Path $根目录 "构建\模糊失败样本"
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$样本文件 = Join-Path $工作目录 "样本.java"

# ---------- Token 池：从三表提取所有中文词组（跳过注释与分区标题） ----------
$词表路径 = @(
    (Join-Path $根目录 "工具\cjc\关键字表.txt"),
    (Join-Path $根目录 "工具\cjc\近义词典.txt"),
    (Join-Path $根目录 "工具\cjc\方法糖.txt")
)
$词集合 = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($p in $词表路径) {
    if (-not (Test-Path $p)) { continue }
    foreach ($line in [System.IO.File]::ReadAllLines($p, [System.Text.Encoding]::UTF8)) {
        $修剪 = $line.TrimStart()
        if ($修剪.StartsWith("#") -or $修剪.StartsWith("[")) { continue }
        foreach ($m in [regex]::Matches($line, "[\u4e00-\u9fa5]{1,8}")) {
            [void]$词集合.Add($m.Value)
        }
    }
}
$词们 = @($词集合 | Sort-Object)
if ($词们.Count -lt 5) {
    Write-Output "模糊失败：Token 池过小（$($词们.Count)），词表读取异常。"
    exit 2
}
Write-Output ("Token 池：{0} 个中文词（种子 {1}，轮数 {2}）" -f $词们.Count, $种子, $轮数)

# ---------- 白名单治理静态检查（复用 M16 锚点机制） ----------
$白名单文件 = Join-Path $PSScriptRoot "差异白名单.txt"
$adr007 = Join-Path $根目录 "文档\原生\ADR\ADR-007-语义兼容与差异白名单.md"
$治理问题 = @()
if ((Test-Path $白名单文件) -and (Test-Path $adr007)) {
    $adr正文 = [System.IO.File]::ReadAllText($adr007, [System.Text.Encoding]::UTF8)
    $当前锚点 = $null
    foreach ($line in (Get-Content $白名单文件 -Encoding UTF8)) {
        $修 = $line.Trim()
        if ($修 -match '^#\s*依据锚点：\s*(ADR-\d+:[^\s]+)') {
            $当前锚点 = $Matches[1]
        } elseif ($修 -ne "" -and -not $修.StartsWith("#")) {
            if (($null -eq $当前锚点) -or (-not $adr正文.Contains($当前锚点))) {
                $治理问题 += "白名单条目『$修』缺少可追溯的 ADR 锚点"
            }
            $当前锚点 = $null
        }
    }
}

# ---------- 运行一次 cjc 检查（.NET Process 精确控制超时与编码） ----------
function 运行检查([string]$文件路径, [int]$超时) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "java"
    $psi.Arguments = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -Dcjc.home=`"$根目录`" `"$java源`" 检查 `"$文件路径`""
    $psi.WorkingDirectory = $根目录
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $超时了 = $false
    # 同步等待：cjc 检查输出很小（远低于管道缓冲），不会因缓冲区满而死锁。
    # 先 WaitForExit(超时) 再读取流，避免 async Result 在多轮后阻塞。
    if (-not $p.WaitForExit($超时)) {
        $超时了 = $true
        try { $p.Kill() } catch { }
        $p.WaitForExit(2000) | Out-Null
    }
    $o = $p.StandardOutput.ReadToEnd()
    $e = $p.StandardError.ReadToEnd()
    $码 = if ($超时了) { -999 } else { $p.ExitCode }
    $p.Dispose()
    return @{ 码 = $码; 输出 = $o; 错误 = $e; 超时 = $超时了 }
}

$随机 = New-Object System.Random($种子)
$标点 = @(" ", " ", " ", " ", "`n", ";", "，", "(" , ")", "`t")
$通过 = 0
$崩溃数 = 0
$挂起数 = 0
$诊断数 = 0
$失败详情 = @()

for ($轮 = 1; $轮 -le $轮数; $轮++) {
    $n = $随机.Next($最小token, $最大token + 1)
    $parts = @()
    for ($i = 0; $i -lt $n; $i++) {
        $parts += $词们[$随机.Next($词们.Count)]
        $parts += $标点[$随机.Next($标点.Count)]
    }
    $样本 = $parts -join ""
    [System.IO.File]::WriteAllText($样本文件, $样本, (New-Object System.Text.UTF8Encoding($false)))

    $r = 运行检查 $样本文件 $超时毫秒
    $err = [string]$r.错误

    # 断言 1：不挂起
    if ($r.超时) {
        $挂起数++
        $失败详情 += "轮 $轮：挂起（>$超时毫秒 ms）"
        continue
    }
    # 断言 2：不崩溃（退出码须在已知集合 0/1/2；无 JVM 致命错误 / 未捕获异常栈）
    $致命 = ($r.码 -notin @(0, 1, 2)) -or ($err -match 'A fatal error has been detected') -or ($err -match 'Exception in thread')
    if ($致命) {
        $崩溃数++
        $失败详情 += "轮 $轮：崩溃（退出码 $($r.码)）stderr=$($err.Substring(0, [Math]::Min(160, $err.Length)))"
        continue
    }
    # 断言 3：中文诊断（stderr 非空时须命中 CJK 码或含中文；纯英文异常栈判失败）
    if (-not [string]::IsNullOrWhiteSpace($err)) {
        $有码 = $err -match 'CJK\d{3}'
        $有中文 = $err -match '[\u4e00-\u9fa5]'
        if (-not ($有码 -or $有中文)) {
            $诊断数++
            $失败详情 += "轮 $轮：非中文诊断 stderr=$($err.Substring(0, [Math]::Min(160, $err.Length)))"
            continue
        }
    }
    $通过++
}

Write-Output ("模糊结果：通过 {0} / 共 {1}（崩溃 {2} / 挂起 {3} / 诊断 {4}）" -f $通过, $轮数, $崩溃数, $挂起数, $诊断数)

if ($失败详情.Count -gt 0) {
    New-Item -ItemType Directory -Force $样本目录 | Out-Null
    $失败详情 | ForEach-Object { Write-Output ("  " + $_) }
    # 记录失败概要（样本内容已由最后一轮覆盖，此处仅存概要与种子，保证可复现）
    $概要 = @("种子=$种子", "轮数=$轮数", "超时毫秒=$超时毫秒") + $失败详情
    [System.IO.File]::WriteAllLines((Join-Path $样本目录 "报告-种子$种子.txt"), $概要, (New-Object System.Text.UTF8Encoding($false)))
}
if ($治理问题.Count -gt 0) {
    Write-Output ("白名单治理问题：" + ($治理问题 -join "；"))
}
if (($失败详情.Count -gt 0) -or ($治理问题.Count -gt 0)) {
    exit 1
}
exit 0
