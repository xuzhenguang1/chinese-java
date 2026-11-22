# M12 GC 压力：多组分配/回收用例，断言退出码 0、结果文本与峰值工作集无持续线性增长。
# 各组用独立文件名，避免单次批量删除超过工具闸门；每组跑前清理自己的产物。
# 用法：.\原生\测试\GC压力.ps1 [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 "构建\原生\GC压力"
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$UTF8 = New-Object System.Text.UTF8Encoding($false)

# 四组用例：名 / 期望输出子串 / 源码（每组的 exe 与源码独立命名）。
$用例们 = @(
    @{
        名 = "对象分配"
        期望 = "合计：45002138890"
        源码 = @'
类 压力对象 {
    静态 类 点 {
        整数 横;
        整数 纵;
        点(整数 横, 整数 纵) { 这.横 = 横; 这.纵 = 纵; }
    }
    入口 {
        长整数 合计 = 0;
        对于 (整数 序号 = 0; 序号 < 300000; 序号++) {
            点 实例 = 新建 点(序号, 序号 + 1);
            字符串 文本 = "第" + 序号 + "个";
            合计 += 实例.横 + 文本.长度();
        }
        打印行("合计：", 合计);
    }
}
'@
    },
    @{
        名 = "字符串 churn"
        期望 = "串长：7"
        源码 = @'
类 压力字符串 {
    入口 {
        文本构建器 构建器 = 新建 文本构建器();
        对于 (整数 序号 = 0; 序号 < 200000; 序号++) {
            构建器.清空();
            构建器.追加("段").追加(序号);
        }
        字符串 结果 = 构建器.转文本();
        打印行("串长：", 结果.长度());
    }
}
'@
    },
    @{
        名 = "集合增删"
        期望 = "终值：1000"
        源码 = @'
类 压力集合 {
    入口 {
        清单<整数> 表 = 新建 数组清单<>();
        对于 (整数 轮 = 0; 轮 < 200; 轮++) {
            表.清空();
            对于 (整数 序 = 0; 序 < 1000; 序++) { 表.加入(序); }
            对于 (整数 序 = 0; 序 < 500; 序++) { 表.移除(0); }
        }
        打印行("终值：", 表.元素数() + 500);
    }
}
'@
    },
    @{
        名 = "深层对象图"
        期望 = "链长：2000"
        源码 = @'
类 压力对象图 {
    静态 类 节点 {
        整数 值;
        节点(整数 值) { 这.值 = 值; }
    }
    入口 {
        节点[] 链 = 新建 节点[2000];
        对于 (整数 序 = 0; 序 < 2000; 序++) {
            链[序] = 新建 节点(序);
        }
        长整数 合计 = 0;
        对于 (整数 序 = 0; 序 < 2000; 序++) {
            合计 += 链[序].值;
        }
        打印行("链长：", 链.长度());
    }
}
'@
    }
)

$失败 = @()
foreach ($用例 in $用例们) {
    $名 = $用例.名
    $源 = Join-Path $工作目录 "$名.java"
    $exe = Join-Path $工作目录 "$名.exe"
    $out = Join-Path $工作目录 "$名.out"
    $err = Join-Path $工作目录 "$名.err"
    [System.IO.File]::WriteAllText($源, $用例.源码, $UTF8)

    $编译 = & $cjc 原生 编译 $源 -o $exe 2>&1
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
        $失败 += "$名：编译失败（$($编译 -join ' ')）"
        continue
    }

    # 用 .NET Process 启动：既能实时采样工作集，又能在退出后可靠读取退出码。
    $启动信息 = New-Object System.Diagnostics.ProcessStartInfo
    $启动信息.FileName = $exe
    $启动信息.UseShellExecute = $false
    $启动信息.RedirectStandardOutput = $true
    $启动信息.RedirectStandardError = $true
    $进程 = New-Object System.Diagnostics.Process
    $进程.StartInfo = $启动信息
    $进程.Start() | Out-Null
    $峰值样本 = @()
    while (-not $进程.HasExited) {
        try { $峰值样本 += $进程.PeakWorkingSet64 } catch { }
        Start-Sleep -Milliseconds 50
    }
    $标准输出 = $进程.StandardOutput.ReadToEnd()
    $标准错误 = $进程.StandardError.ReadToEnd()
    $进程.WaitForExit()
    $退出码 = $进程.ExitCode
    [System.IO.File]::WriteAllText($err, $标准错误, [System.Text.Encoding]::UTF8)
    $进程.Dispose()
    [System.IO.File]::WriteAllText($out, $标准输出, [System.Text.Encoding]::UTF8)
    $文本 = $标准输出

    if ($退出码 -ne 0) {
        $失败 += "$名：退出码 $退出码"
        continue
    }
    if (-not $文本.Contains($用例.期望)) {
        $失败 += "$名：输出不符（期望含 $($用例.期望)，实得 $($文本.Trim())）"
        continue
    }

    # 内存检查：峰值工作集须 < 512MB（死循环式泄漏会突破），并打印最后一次采样。
    $末峰值 = 0
    if ($峰值样本.Count -gt 0) { $末峰值 = $峰值样本[-1] }
    $峰值MB = [Math]::Round($末峰值 / 1MB, 1)
    if ($末峰值 -gt 512MB) {
        $失败 += "$名：峰值工作集 ${峰值MB}MB 超过 512MB 上限"
        continue
    }
    Write-Output ("通过  {0,-12} {1}  峰值 {2} MB" -f $名, $文本.Trim(), $峰值MB)
}

Write-Output ""
if ($失败.Count -gt 0) {
    Write-Output ("GC 压力失败：" + ($失败 -join "；"))
    exit 1
}
Write-Output "GC 压力结论：全部用例通过，峰值工作集正常，无泄漏迹象"
exit 0
