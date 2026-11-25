# M21 Mach-O 全量对拍：教程 01-35 的 Mach-O stdout/退出码与 JVM 后端逐字节一致（G7）。
# 仅在 macOS runner（CI macos-latest）上真机执行；非 macOS 宿主按 ADR-024 G7 诚实标注
# 「CI 实跑待环境」并退出 0（不虚报全绿，也不把环境缺失误判为功能失败）。
# 用法: pwsh -File 原生/测试/对拍MachO.ps1 [-后端 c|llvm] [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [ValidateSet("c", "llvm")]
    [string]$后端 = "c",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# G7 硬门槛：本机 Windows 无 macOS SDK，无法真机编译/运行 Mach-O。
if ($env:OS -eq "Windows_NT" -or -not (Test-Path '/usr/bin/clang')) {
    Write-Output "=== Mach-O 真机对拍：CI 实跑待环境 ==="
    Write-Output ("  当前宿主：{0}（需 macOS + clang + SDK）" -f $(if ($env:OS -eq "Windows_NT") { "Windows" } else { "非 macOS Unix" }))
    Write-Output ("  后端：{0}；35 课 Mach-O 对拍将在 GitHub Actions macos-latest job 真机执行。" -f $后端)
    Write-Output "  按 ADR-024 G7：本机不虚报全绿，环境缺失不计为功能失败（退出码 0）。"
    exit 0
}

foreach ($n in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($n, $null)
}

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 ("构建/原生/对拍MachO-" + $后端)
$jvm目录 = Join-Path $工作目录 "jvm"
$macho目录 = Join-Path $工作目录 "mach-o"
$结果目录 = Join-Path $工作目录 "结果"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
foreach ($d in @($jvm目录, $machO目录, $结果目录)) { New-Item -ItemType Directory -Force $d | Out-Null }

$文件们 = @()
if ($路径 -and $路径.Count -gt 0) {
    foreach ($项 in $路径) {
        if (Test-Path $项 -PathType Container) {
            $文件们 += @(Get-ChildItem $项 -Filter *.java -File | Where-Object { $_.Name -match '^第\d{2}课' } | Sort-Object Name)
        } elseif (Test-Path $项) { $文件们 += @(Get-Item $项) }
    }
} else {
    $文件们 = @(Get-ChildItem (Join-Path $根目录 "教程") -Filter *.java -File |
        Where-Object { $_.Name -match '^第\d{2}课' } | Sort-Object Name)
}

$用例 = @()
for ($i = 0; $i -lt $文件们.Count; $i++) {
    $用例 += [pscustomobject]@{ 源 = $文件们[$i]; 名 = $文件们[$i].BaseName; 标签 = ("case{0:D2}" -f ($i + 1)) }
}

Write-Output ("=== JVM 基准生成（{0} 课）===" -f $用例.Count)
foreach ($u in $用例) {
    & $cjc 编译 $u.源.FullName -d $jvm目录 *> $null
    if ($LASTEXITCODE -ne 0) { Write-Output ("JVM 编译失败：{0}" -f $u.名); continue }
    $outFile = Join-Path $结果目录 ($u.标签 + ".jvm.out")
    $errFile = Join-Path $结果目录 ($u.标签 + ".jvm.err")
    $p = Start-Process -FilePath "java" -ArgumentList @(
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
        "-cp", $jvm目录, $u.名
    ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    [System.IO.File]::WriteAllText((Join-Path $结果目录 ($u.标签 + ".jvm.exit")), [string]$p.ExitCode)
}

Write-Output ("=== Mach-O 批量编译（后端 {0}，{1} 课）===" -f $后端, $用例.Count)
foreach ($u in $用例) {
    $macos = Join-Path $machO目录 ($u.标签 + ".macos")
    if ($后端 -eq "llvm") {
        & $cjc 原生 llvm 编译 $u.源.FullName -平台 darwin -o $macos *> $null
    } else {
        & $cjc 原生 编译 $u.源.FullName -平台 darwin -o $macos *> $null
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $macos)) {
        Write-Output ("Mach-O 编译失败：{0}" -f $u.名)
        continue
    }
    chmod +x $macos
    $outFile = Join-Path $结果目录 ($u.标签 + ".macos.out")
    $errFile = Join-Path $结果目录 ($u.标签 + ".macos.err")
    $p = Start-Process -FilePath $macos -NoNewWindow -Wait -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    [System.IO.File]::WriteAllText((Join-Path $结果目录 ($u.标签 + ".macos.exit")), [string]$p.ExitCode)
}

function 读文本($文件) { if (Test-Path $文件) { return [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8) } return "" }
function 归一路径行($行) {
    $结果 = $行 -replace '\\', '/'
    $结果 = $结果 -replace '([：:])/?(?:[^：:\s|]*/)*?(构建/)', '$1$2'
    return $结果
}
function 归一化($文件) {
    $行们 = @((读文本 $文件) -split "\r?\n")
    for ($i = 0; $i -lt $行们.Count; $i++) {
        if ($行们[$i] -match '写入成功|无法|路径：') { $行们[$i] = 归一路径行 $行们[$i] }
    }
    return ($行们 -join "`n")
}

$通过 = 0
$失败 = @()
foreach ($u in $用例) {
    $jvmOut = Join-Path $结果目录 ($u.标签 + ".jvm.out")
    $macosOut = Join-Path $结果目录 ($u.标签 + ".macos.out")
    if (-not (Test-Path $macosOut)) { $失败 += ("{0}（Mach-O 无输出）" -f $u.名); continue }
    $甲 = 读文本 $jvmOut
    $乙 = 读文本 $macosOut
    $甲归 = ($甲 -replace "`r`n", "`n")
    $乙归 = ($乙 -replace "`r`n", "`n")
    if ($甲归 -cne $乙归) { $甲归 = 归一化 $jvmOut; $乙归 = 归一化 $macosOut }
    $jvm码 = if (Test-Path (Join-Path $结果目录 ($u.标签 + ".jvm.exit"))) { [System.IO.File]::ReadAllText((Join-Path $结果目录 ($u.标签 + ".jvm.exit"))).Trim() } else { "?" }
    $macos码 = if (Test-Path (Join-Path $结果目录 ($u.标签 + ".macos.exit"))) { [System.IO.File]::ReadAllText((Join-Path $结果目录 ($u.标签 + ".macos.exit"))).Trim() } else { "?" }
    if ($甲归 -ceq $乙归 -and $jvm码 -eq $macos码) { $通过++ } else { $失败 += $u.名 }
}

Write-Output ("Mach-O 对拍结果：通过 {0} / 共 {1}（后端 {2}，平台 darwin）" -f $通过, $用例.Count, $后端)
if ($失败.Count -gt 0) { Write-Output ("失败：" + ($失败 -join "、")); exit 1 }
exit 0
