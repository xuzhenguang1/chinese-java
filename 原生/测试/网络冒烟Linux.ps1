# M14 网络冒烟（Linux ELF）：VM 内用 busybox nc 起本地 HTTP 服务，ELF 经 socket GET 读取中文正文。
# 验证 T4.2 的 socket HTTP 实现真实往返 + UTF-8 正文正确。
# 用法: .\原生\测试\网络冒烟Linux.ps1 [-根目录 <目录>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$运行器 = Join-Path $PSScriptRoot "运行器.ps1"
$探针源 = Join-Path $PSScriptRoot "网络探针.java"
$工作目录 = Join-Path $根目录 "构建\原生\网络冒烟Linux"
if (Test-Path $工作目录) { [System.IO.Directory]::Delete($工作目录, $true) }
New-Item -ItemType Directory -Force $工作目录 | Out-Null
$结果目录 = Join-Path $工作目录 "结果"
New-Item -ItemType Directory -Force $结果目录 | Out-Null

$elf = Join-Path $工作目录 "netprobe.elf"
& $cjc 原生 编译 $探针源 -平台 linux -o $elf *> $null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $elf)) {
    Write-Output "网络探针 ELF 编译失败"; exit 2
}

# init 前置脚本（全 ASCII，中文正文用八进制 UTF-8 字节）：
#   启用 loopback；后台循环用 nc 监听 127.0.0.1:80，返回 "你好，网络"（15 字节 UTF-8）。
# 经环境变量传给构建initramfs.py（Python 子进程继承）。
$响应体字节 = "你好，网络"   # 仅用于计算长度，实际下发用八进制。
$前置 = 'ip link set lo up 2>/dev/null; (while true; do printf "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 15\r\nConnection: close\r\n\r\n\344\275\240\345\245\275\357\274\214\347\275\221\347\273\234" | nc -l -p 80 >/dev/null 2>&1; done) & sleep 2'
[Environment]::SetEnvironmentVariable('CJK_INIT_PRE', $前置)
try {
    $r = & $运行器 -平台 linux -可执行 $elf -输出目录 $结果目录 2>&1
    Write-Output ($r | Out-String)
    if ($LASTEXITCODE -ne 0) { Write-Output "网络冒烟 VM 运行失败"; exit 2 }
} finally {
    [Environment]::SetEnvironmentVariable('CJK_INIT_PRE', $null)
}

$outFile = Join-Path $结果目录 "netprobe.elf.out"
$码文件 = Join-Path $结果目录 "netprobe.elf.exit"
$文本 = if (Test-Path $outFile) { [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) } else { "" }
$退出码 = if (Test-Path $码文件) { [System.IO.File]::ReadAllText($码文件).Trim() } else { "?" }

Write-Output ("网络探针输出：" + $文本.Trim())
if ($退出码 -eq "0" -and $文本.Contains("正文：你好，网络")) {
    Write-Output "网络冒烟(Linux)：通过，socket HTTP GET 中文正文往返成功"
    exit 0
}
Write-Output ("网络冒烟(Linux)：失败，退出码 {0}" -f $退出码)
exit 1
