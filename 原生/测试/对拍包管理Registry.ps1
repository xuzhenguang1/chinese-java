# M21 G8 registry/semver 对拍：验证 semver 求解、本地 registry、远程下载 + SHA256 + 缓存。
# 自包含：脚本内构造临时 registry 样例与本地 HTTP 服务，测完清理，不污染仓库。
# 用法: pwsh -File 原生/测试/对拍包管理Registry.ps1
# 验收点：① semver 各操作符选版正确；② 范围无解中文诊断 exit 2；③ 缓存反向哈希随范围变化；
#         ④ 远程下载 + SHA256 校验 + 缓存落地；⑤ 断网缓存命中离线复现；⑥ 校验和不符中文诊断。
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path
)

$ErrorActionPreference = "Continue"
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
foreach ($k in @([Environment]::GetEnvironmentVariables().Keys | Where-Object { $_ -match '(?i)proxy' })) {
    [Environment]::SetEnvironmentVariable($k, $null)
}

$解析器 = Join-Path $根目录 "工具\cjc\依赖清单.java"
$临时 = Join-Path $根目录 "构建\原生\对拍Registry"
if (Test-Path $临时) { [System.IO.Directory]::Delete($临时, $true) }
$registry = Join-Path $临时 "依赖库"
$主工程 = Join-Path $临时 "主工程"
$site = Join-Path $临时 "site"
foreach ($d in @($registry, $主工程, $site)) { New-Item -ItemType Directory -Force $d | Out-Null }
foreach ($v in @('1.2.0','1.3.0','2.0.0')) {
    $包 = Join-Path $registry ("测试工具/" + $v)
    New-Item -ItemType Directory -Force (Join-Path $包 "src/中文") | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $包 "依赖清单.txt"), "包名 test.tool`n版本 $v`n入口 src/中文/工具.java`n源目录 src/中文`n", [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $包 "src/中文/工具.java"), "public class 工具 { public static String 名() { return `"$v`"; } }`n", [System.Text.Encoding]::UTF8)
}

$失败 = @()
function 写清单([string]$范围) {
    [System.IO.File]::WriteAllText((Join-Path $主工程 "依赖清单.txt"),
        "包名 test.main`n版本 1.0.0`n入口 src/中文/主.java`n源目录 src/中文`n依赖 tool 库 测试工具 版本 $范围`n",
        [System.Text.Encoding]::UTF8)
}
# 远程段用 ASCII 包名，避免 HTTP 服务端对 percent-encoded 中文路径不解码。
function 写远程清单([string]$范围) {
    [System.IO.File]::WriteAllText((Join-Path $主工程 "依赖清单.txt"),
        "包名 test.main`n版本 1.0.0`n入口 src/中文/主.java`n源目录 src/中文`n依赖 toolkit 库 toolkit 版本 $范围`n",
        [System.Text.Encoding]::UTF8)
}
$主源目录 = Join-Path (Join-Path $主工程 "src") "中文"
New-Item -ItemType Directory -Force $主源目录 | Out-Null
[System.IO.File]::WriteAllText((Join-Path $主源目录 "主.java"), "public class 主 { public static void main(String[] a) {} }`n", [System.Text.Encoding]::UTF8)
$清单路径 = Join-Path $主工程 "依赖清单.txt"
$env:CJK_REGISTRY = $registry
$env:CJK_REGISTRY_URL = $null

function 解析([string]$范围, [string]$输出) {
    写清单 $范围
    & java "-Dfile.encoding=UTF-8" $解析器 解析 $清单路径 $输出 2>&1 | Out-Null
    return $LASTEXITCODE
}
function 选中版本([string]$输出) {
    if (-not (Test-Path $输出)) { return "" }
    $文本 = [System.IO.File]::ReadAllText($输出, [System.Text.Encoding]::UTF8)
    if ($文本 -match '1\.3\.0') { return "1.3.0" }
    if ($文本 -match '1\.2\.0') { return "1.2.0" }
    if ($文本 -match '2\.0\.0') { return "2.0.0" }
    return ""
}
$out = Join-Path $临时 "result.txt"

# ① semver 操作符选版。
$期望 = @{ '^1.2.0'='1.3.0'; '~1.2'='1.2.0'; '1.x'='1.3.0'; '1.2.x'='1.2.0'; '1.2.0'='1.2.0'; '^2.0'='2.0.0' }
foreach ($k in $期望.Keys) {
    $码 = 解析 $k $out
    $得 = 选中版本 $out
    if ($码 -eq 0 -and $得 -eq $期望[$k]) { Write-Host "  通过：$k → $得" }
    else { $失败 += "semver $k 期望 $($期望[$k]) 实得 $得（exit $码）" }
}
# ② 范围无解。
$码 = 解析 '^3.0' $out
if ($码 -eq 2) { Write-Host "  通过：^3.0 → 范围无解（exit 2）" } else { $失败 += "范围无解未命中 exit 2（实得 $码）" }
# ③ 缓存反向：哈希随范围文本变化。
写清单 '^1.2.0'
& java "-Dfile.encoding=UTF-8" $解析器 哈希 $清单路径 (Join-Path $临时 "h1.txt") 2>&1 | Out-Null
写清单 '~1.2'
& java "-Dfile.encoding=UTF-8" $解析器 哈希 $清单路径 (Join-Path $临时 "h2.txt") 2>&1 | Out-Null
$h1 = [System.IO.File]::ReadAllText((Join-Path $临时 "h1.txt"), [System.Text.Encoding]::UTF8).Trim()
$h2 = [System.IO.File]::ReadAllText((Join-Path $临时 "h2.txt"), [System.Text.Encoding]::UTF8).Trim()
if ($h1 -and $h2 -and $h1 -ne $h2) { Write-Host "  通过：改范围 → 哈希变化（缓存必重编）" } else { $失败 += "缓存反向：改范围哈希未变化" }

# ④⑤⑥ 远程下载段：优先 python3 + 本地 HTTP；无 python3 则标注「CI 实跑待环境」。
# 解析真实 python3：跳过 Microsoft Store 存根（WindowsApps 下无许可证），用工作区内版本。
$pythonCmd = $null
foreach ($候选 in @(
    (Join-Path $env:USERPROFILE ".workbuddy\binaries\python\versions\3.13.12\python3.exe"),
    "C:\msys64\mingw64\bin\python3.exe"
)) {
    if (Test-Path $候选) { $pythonCmd = $候选; break }
}
if (-not $pythonCmd) {
    $找到 = Get-Command python3 -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch 'WindowsApps' }
    if ($找到) { $pythonCmd = $找到.Source }
}
$有python = ($null -ne $pythonCmd)
$env:CJK_REGISTRY_URL = $null
if ($有python) {
    $远程包 = Join-Path $site "toolkit/1.4.0"
    New-Item -ItemType Directory -Force (Join-Path $远程包 "src/中文") | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $远程包 "依赖清单.txt"), "包名 test.toolkit`n版本 1.4.0`n入口 src/中文/工具.java`n源目录 src/中文`n", [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $远程包 "src/中文/工具.java"), "public class 工具 {}`n", [System.Text.Encoding]::UTF8)
    $制作 = @'
import zipfile, hashlib, os, sys
site, payload = sys.argv[1], sys.argv[2]
z = os.path.join(site, "1.4.0.zip")
with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as f:
    for root, _, files in os.walk(payload):
        for fn in files:
            p = os.path.join(root, fn)
            f.write(p, os.path.relpath(p, payload))
h = hashlib.sha256(open(z, "rb").read()).hexdigest().upper()
open(os.path.join(site, "versions.txt"), "w").write("# version sha256\n1.4.0 " + h + "\n")
'@
    $制作脚本 = Join-Path $临时 "makezip.py"
    [System.IO.File]::WriteAllText($制作脚本, $制作)
    & $pythonCmd $制作脚本 (Join-Path $site "toolkit") $远程包 2>&1 | Out-Null
    $端口 = 18873
    $服务 = Start-Process -FilePath $pythonCmd -ArgumentList @('-m','http.server',"$端口",'--bind','127.0.0.1','--directory',(Join-Path $site '')) -PassThru -WindowStyle Hidden
    Start-Sleep -Milliseconds 800
    $本地缓存 = Join-Path $registry "toolkit"
    $env:CJK_REGISTRY_URL = "http://127.0.0.1:$端口"
    写远程清单 '^1.4'
    & java "-Dfile.encoding=UTF-8" $解析器 解析 $清单路径 $out 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $本地缓存 "1.4.0/依赖清单.txt"))) {
        Write-Host "  通过：远程下载 + SHA256 校验 + 缓存落地"
    } else { $失败 += "远程下载/缓存落地失败" }
    # 断网复现：URL 指向不可达端口，缓存命中仍成功。
    $env:CJK_REGISTRY_URL = "http://127.0.0.1:19998"
    & java "-Dfile.encoding=UTF-8" $解析器 解析 $清单路径 (Join-Path $临时 "offline.txt") 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "  通过：断网缓存命中离线复现" } else { $失败 += "断网缓存命中失败" }
    # 校验和不符：删缓存 + 篡改元数据哈希 + 重下应 exit 2 且诊断含「校验和不符」。
    if (Test-Path $本地缓存) { [System.IO.Directory]::Delete($本地缓存, $true) }
    [System.IO.File]::WriteAllText((Join-Path $site "toolkit/versions.txt"), "# version sha256`n1.4.0 DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF`n")
    $env:CJK_REGISTRY_URL = "http://127.0.0.1:$端口"
    # CJC_ERR_FILE：诊断写 UTF-8 文件，免 PS5.1 管道乱码（依赖清单.java 同 CJC_ARGS_FILE 协议）。
    $env:CJC_ERR_FILE = Join-Path $临时 "bad.err"
    & java "-Dfile.encoding=UTF-8" $解析器 解析 $清单路径 $out 2>&1 | Out-Null
    $校验码 = $LASTEXITCODE
    $诊断 = if (Test-Path $env:CJC_ERR_FILE) { [System.IO.File]::ReadAllText($env:CJC_ERR_FILE, [System.Text.Encoding]::UTF8) } else { "" }
    $env:CJC_ERR_FILE = $null
    if ($校验码 -eq 2 -and $诊断 -match '校验和不符') { Write-Host "  通过：校验和不符 → 中文诊断 exit 2" } else { $失败 += ("校验和不符诊断异常（exit {0}）：{1}" -f $校验码, $诊断) }
    if ($服务 -and -not $服务.HasExited) { Stop-Process -Id $服务.Id -Force -ErrorAction SilentlyContinue }
} else {
    Write-Host "  跳过：远程下载段（无 python3，CI 实跑待环境）；本地 registry 段已全验。"
}

$env:CJK_REGISTRY = $null
$env:CJK_REGISTRY_URL = $null
if (Test-Path $临时) { [System.IO.Directory]::Delete($临时, $true) }
if ($失败.Count -eq 0) { Write-Host "registry/semver 对拍：全部通过。"; exit 0 }
Write-Host ("registry/semver 对拍失败：" + ($失败 -join "、")); exit 1
