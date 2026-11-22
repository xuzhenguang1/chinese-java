# M14 GC 压力（Linux ELF）：复用 M12 的四组用例，编译为 ELF 后在 Alpine VM 批量运行。
# 断言：退出码 0 + 输出含期望子串（VM 内无法采样 Windows 工作集，故不做内存峰值断言）。
# 用法: .\原生\测试\GC压力Linux.ps1 [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$cjc = Join-Path $根目录 "cjc.ps1"
$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$工作目录 = Join-Path $根目录 "构建\原生\GC压力Linux"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$结果目录 = Join-Path $工作目录 "结果"
New-Item -ItemType Directory -Force $结果目录 | Out-Null

$用例们 = @(
    @{ 名="对象分配"; 期望="合计：45002138890"; 源码=@'
类 压力对象 {
    静态 类 点 {
        整数 横; 整数 纵;
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
'@ },
    @{ 名="字符串churn"; 期望="串长：7"; 源码=@'
类 压力字符串 {
    入口 {
        文本构建器 构建器 = 新建 文本构建器();
        对于 (整数 序号 = 0; 序号 < 200000; 序号++) {
            构建器.清空(); 构建器.追加("段").追加(序号);
        }
        字符串 结果 = 构建器.转文本();
        打印行("串长：", 结果.长度());
    }
}
'@ },
    @{ 名="集合增删"; 期望="终值：1000"; 源码=@'
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
'@ },
    @{ 名="深层对象图"; 期望="链长：2000"; 源码=@'
类 压力对象图 {
    静态 类 节点 {
        整数 值;
        节点(整数 值) { 这.值 = 值; }
    }
    入口 {
        节点[] 链 = 新建 节点[2000];
        对于 (整数 序 = 0; 序 < 2000; 序++) { 链[序] = 新建 节点(序); }
        长整数 合计 = 0;
        对于 (整数 序 = 0; 序 < 2000; 序++) { 合计 += 链[序].值; }
        打印行("链长：", 链.长度());
    }
}
'@ }
)

$elf们 = @()
$期望表 = @{}
$序号 = 0
foreach ($用例 in $用例们) {
    $序号++
    $标签 = ("gc{0:D2}" -f $序号)
    $源 = Join-Path $工作目录 ($标签 + ".java")
    $elf = Join-Path $工作目录 ($标签 + ".elf")
    [System.IO.File]::WriteAllText($源, $用例.源码, $utf8NoBom)
    & $cjc 原生 编译 $源 -平台 linux -o $elf *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
        Write-Output ("GC 编译失败：{0}" -f $用例.名)
        continue
    }
    $elf们 += $elf
    $期望表[$标签] = @{ 名=$用例.名; 期望=$用例.期望 }
}

$r = & $运行器 -平台 linux -可执行 $elf们 -输出目录 $结果目录 2>&1
Write-Output ($r | Out-String)
if ($LASTEXITCODE -ne 0) { Write-Output "GC VM 运行失败。"; exit 2 }

$通过 = 0
$失败 = @()
foreach ($标签 in $期望表.Keys | Sort-Object) {
    $期望 = $期望表[$标签]
    $out = Join-Path $结果目录 ($标签 + ".elf.out")
    $码 = Join-Path $结果目录 ($标签 + ".elf.exit")
    $文本 = if (Test-Path $out) { [System.IO.File]::ReadAllText($out, [System.Text.Encoding]::UTF8) } else { "" }
    $退出码 = if (Test-Path $码) { [System.IO.File]::ReadAllText($码).Trim() } else { "?" }
    if ($退出码 -eq "0" -and $文本.Contains($期望.期望)) {
        $通过++
        Write-Output ("通过  {0}  {1}  退出码 0" -f $期望.名, $文本.Trim())
    } else {
        $失败 += $期望.名
        Write-Output ("失败  {0}：退出码 {1}，输出「{2}」" -f $期望.名, $退出码, $文本.Trim())
    }
}
Write-Output ("GC 压力(Linux) 断言：通过 {0} / 共 {1}" -f $通过, $期望表.Count)
if ($失败.Count -gt 0) { Write-Output ("失败：" + ($失败 -join "、")); exit 1 }
exit 0
