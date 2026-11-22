# M17 内存检查（Linux ELF）：按 ADR-020 E1 阶梯，用 -内存检查 旗标编译并运行压力用例。
# ASan 可用时验证 ASan 产物；不可用时降级 Clang 静态分析（编译期收集告警）+ 运行产物。
# 断言：产物运行退出码 0 + 输出含期望子串；静态分析告警仅汇总记录，不作硬门槛。
# 用法: .\原生\测试\内存检查Linux.ps1 [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# 同进程清代理：规避 PS5.1 大小写双份代理导致子进程失败（M15/M16 已知）。
foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$cjc = Join-Path $根目录 "cjc.ps1"
$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$工作目录 = Join-Path $根目录 "构建\原生\内存检查Linux"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$结果目录 = Join-Path $工作目录 "结果"
New-Item -ItemType Directory -Force $结果目录 | Out-Null

# 用例：GC 三组 + 异常一组（覆盖分配/字符串/集合/异常路径）。
$用例们 = @(
    @{ 名="对象分配"; 期望="合计：2288890"; 源码=@'
类 内存对象 {
    入口 {
        长整数 合计 = 0;
        对于 (整数 序号 = 0; 序号 < 300000; 序号++) {
            字符串 文本 = "第" + 序号 + "个";
            合计 += 文本.长度();
        }
        打印行("合计：", 合计);
    }
}
'@ },
    @{ 名="字符串churn"; 期望="串长：7"; 源码=@'
类 内存字符串 {
    入口 {
        文本构建器 构建器 = 新建 文本构建器();
        对于 (整数 序号 = 0; 序号 < 200000; 序号++) {
            构建器.清空(); 构建器.追加("段").追加(序号);
        }
        打印行("串长：", 构建器.转文本().长度());
    }
}
'@ },
    @{ 名="集合增删"; 期望="终值：1000"; 源码=@'
类 内存集合 {
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
    @{ 名="异常路径"; 期望="捕获"; 源码=@'
类 内存异常 {
    入口 {
        尝试 {
            整数 零 = 0;
            整数 商 = 10 / 零;
            打印行(商);
        } 捕获 (算术异常 异) {
            打印行("捕获");
        }
    }
}
'@ }
)

$elf们 = @()
$期望表 = @{}
$序号 = 0
foreach ($用例 in $用例们) {
    $序号++
    $标签 = ("mem{0:D2}" -f $序号)
    $源 = Join-Path $工作目录 ($标签 + ".java")
    $elf = Join-Path $工作目录 ($标签 + ".elf")
    [System.IO.File]::WriteAllText($源, $用例.源码, $utf8NoBom)
    # 关键：-内存检查 旗标，触发 E1 阶梯（ASan 或降级静态分析）。
    & $cjc 原生 编译 $源 -平台 linux -内存检查 -无缓存 -o $elf 2>&1 | ForEach-Object {
        # 静态分析告警（驱动以 [静态分析] 前缀写 stderr）；汇总记录，不判失败。
        if ("$_" -match '静态分析|内存检查：') { Write-Output ("  " + $_) }
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
        Write-Output ("内存检查编译失败：{0}（退出码 {1}）" -f $用例.名, $LASTEXITCODE)
        $期望表[$标签] = @{ 名=$用例.名; 期望=$用例.期望; 编译失败=$true }
        continue
    }
    $elf们 += $elf
    $期望表[$标签] = @{ 名=$用例.名; 期望=$用例.期望; 编译失败=$false }
}

if ($elf们.Count -gt 0) {
    $r = & $运行器 -平台 linux -可执行 $elf们 -输出目录 $结果目录 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Output "内存检查 VM 运行失败。"; }
}

$通过 = 0
$失败 = @()
foreach ($标签 in $期望表.Keys | Sort-Object) {
    $期望 = $期望表[$标签]
    if ($期望.编译失败) { $失败 += $期望.名; Write-Output ("失败  {0}：编译失败" -f $期望.名); continue }
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
Write-Output ("内存检查(Linux) 断言：通过 {0} / 共 {1}" -f $通过, $期望表.Count)
if ($失败.Count -gt 0) { Write-Output ("失败：" + ($失败 -join "、")); exit 1 }
exit 0
