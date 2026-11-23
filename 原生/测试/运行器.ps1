# M14 统一运行器：Windows 直接运行 exe / Linux 批量打包 ELF 到 Alpine VM 运行。
# Linux 模式：接收若干 ELF，一次启动 VM（经 Python 辅助），解析串口标记，写 stdout 与退出码到输出目录。
# 用法:
#   Windows: 运行器.ps1 -平台 windows -可执行 <exe> -输出目录 <dir> -名 <name>
#   Linux:   运行器.ps1 -平台 linux -可执行 <elf...> -输出目录 <dir>
param(
    [ValidateSet("windows", "linux")]
    [string]$平台 = "windows",
    [string[]]$可执行 = @(),
    [string]$输出目录,
    [string]$名 = "",
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [int]$超时秒 = 180
)

$ErrorActionPreference = "Stop"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not $输出目录) { Write-Output "运行器：必须指定 -输出目录"; exit 2 }
New-Item -ItemType Directory -Force $输出目录 | Out-Null

if ($平台 -eq "windows") {
    if ($可执行.Count -eq 0) { Write-Output "运行器：Windows 模式需要 -可执行"; exit 2 }
    $exe = $可执行[0]
    if (-not (Test-Path $exe)) { Write-Output "运行器：找不到 $exe"; exit 2 }
    $标签 = if ($名) { $名 } else { [System.IO.Path]::GetFileNameWithoutExtension($exe) }
    $outFile = Join-Path $输出目录 ($标签 + ".out")
    $errFile = Join-Path $输出目录 ($标签 + ".err")
    $码文件 = Join-Path $输出目录 ($标签 + ".exit")
    $进程 = Start-Process -FilePath $exe -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    [System.IO.File]::WriteAllText($码文件, [string]$进程.ExitCode)
    Write-Output ("运行器 windows：{0} 退出码 {1}" -f $标签, $进程.ExitCode)
    exit 0
}

# ---------------- Linux 批量模式 ----------------
$工具链 = Join-Path $根目录 "构建\工具链"
$alpine = Join-Path $工具链 "alpine"
$minirootfs = Join-Path $alpine "minirootfs.tar.gz"
$打包器 = Join-Path $根目录 "原生\测试\工具\构建initramfs.py"
$运行VM脚本 = Join-Path $根目录 "原生\测试\工具\运行VM.py"

$有效 = @()
foreach ($elf in $可执行) {
    if (-not (Test-Path $elf)) { Write-Output "运行器：找不到 ELF $elf"; exit 2 }
    $bytes = [System.IO.File]::ReadAllBytes($elf)
    if ($bytes.Length -lt 4 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45) {
        Write-Output "运行器：不是 ELF：$elf"; exit 2
    }
    $有效 += $elf
}
if ($有效.Count -eq 0) { Write-Output "运行器：Linux 模式需要至少一个 ELF"; exit 2 }

# M17 E3：支持 CJK_QEMU / CJK_KERNEL / CJK_ALPINE 环境变量覆盖（Linux CI）；
# 未设置时回退本机 E: junction，零回退。运行VM.py 读取同名环境变量。
$asciiQemu = if ($env:CJK_QEMU) { $env:CJK_QEMU } else { "E:\cjk-qemu\qemu-system-x86_64.exe" }
$asciiAlpine = if ($env:CJK_ALPINE) { $env:CJK_ALPINE } else { "E:\cjk-alpine" }
$内核路径 = if ($env:CJK_KERNEL) { $env:CJK_KERNEL } else { (Join-Path $asciiAlpine "boot\vmlinuz-virt") }
# 环境变量覆盖时，把这些值同步给运行VM.py（子进程继承）。
if ($env:CJK_QEMU) { $env:CJK_QEMU = $asciiQemu }
if ($env:CJK_ALPINE) { $env:CJK_ALPINE = $asciiAlpine }
if ($env:CJK_KERNEL) { $env:CJK_KERNEL = $内核路径 }
if (-not (Test-Path $asciiQemu) -or -not (Test-Path $内核路径)) {
    Write-Output "运行器：qemu/kernel 缺失（$asciiQemu / $内核路径），无法启动 VM"
    exit 2
}

$临时 = Join-Path ([System.IO.Path]::GetTempPath()) ("cjk_runner_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $临时 | Out-Null
$initrd = Join-Path $临时 "initrd.gz"
$raw = Join-Path $临时 "vmraw.txt"

$旧策略 = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$pyOut = & python $打包器 $minirootfs $initrd @有效 2>&1
$py码 = $LASTEXITCODE
$ErrorActionPreference = $旧策略
if ($py码 -ne 0) { Write-Output "运行器：打包失败"; $pyOut | ForEach-Object { Write-Output $_ }; exit 2 }

$ErrorActionPreference = "Continue"
$vmOut = & python $运行VM脚本 $initrd $raw $超时秒 2>&1
$vm码 = $LASTEXITCODE
$ErrorActionPreference = $旧策略
if ($vm码 -ne 0 -and -not (Test-Path $raw)) {
    Write-Output "运行器：VM 启动失败（退出码 $vm码）"
    $vmOut | ForEach-Object { Write-Output $_ }
    exit 2
}
Write-Output ("运行器 Linux：VM 完成（码 {0}）" -f $vm码)

if (-not (Test-Path $raw)) { Write-Output "运行器：VM 无输出"; exit 2 }
$文本 = [System.IO.File]::ReadAllText($raw, [System.Text.Encoding]::UTF8)
$开始 = $文本.IndexOf("===CJK_ELF_START===")
if ($开始 -ge 0) { $文本 = $文本.Substring($开始) }

$模式 = New-Object System.Text.RegularExpressions.Regex(
    '===CJK_CASE:(?<名>[^=\r\n]+)===\r?\n(?<正文>.*?)===CJK_EXIT:(?<码>\d+)===',
    [System.Text.RegularExpressions.RegexOptions]::Singleline)
$命中 = @{}
foreach ($m in $模式.Matches($文本)) {
    $标签 = [System.IO.Path]::GetFileNameWithoutExtension($m.Groups['名'].Value)
    $命中[$标签] = @{ out = $m.Groups['正文'].Value; exit = $m.Groups['码'].Value }
}

$缺 = @()
foreach ($elf in $有效) {
    $标签 = [System.IO.Path]::GetFileNameWithoutExtension($elf)
    if (-not $命中.ContainsKey($标签)) { $缺 += $标签; continue }
    [System.IO.File]::WriteAllText((Join-Path $输出目录 ($标签 + ".elf.out")), $命中[$标签].out, $utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $输出目录 ($标签 + ".elf.exit")), $命中[$标签].exit)
}
if ($缺.Count -gt 0) {
    Write-Output ("运行器：以下 ELF 未出结果：" + ($缺 -join "、"))
    exit 2
}
Write-Output ("运行器 Linux：写回 {0} 例结果到 {1}" -f $有效.Count, $输出目录)
exit 0