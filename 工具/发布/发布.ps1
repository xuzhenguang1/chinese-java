# 发布打包：构建标准库与 Maven 插件，打原生补丁，生成可分发的 zip。
#
# 用法：
#   .\工具\发布\发布.ps1                         # 完整构建并打包（版本默认读 工具\版本.json 的「发布」）
#   .\工具\发布\发布.ps1 -版本 1.0.0 -跳过测试     # 跳过 JUnit
#   .\工具\发布\发布.ps1 -跳过构建                # 仅用现有产物重新打包
#   .\工具\发布\发布.ps1 -跳过原生                # 不构建路线 B 补丁类
#
# 产物：发布\中文Java-<版本>.zip
param(
    [string]$版本 = "",
    [switch]$跳过构建,
    [switch]$跳过测试,
    [switch]$跳过原生,
    [switch]$保留暂存
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path "$PSScriptRoot\..\..").Path

# 版本默认从单一源读取，避免发布包与代码版本漂移
if ([string]::IsNullOrWhiteSpace($版本)) {
    $版本文件 = Join-Path $root "工具\版本.json"
    if (-not (Test-Path $版本文件)) {
        Write-Error "找不到版本源：$版本文件"
        exit 2
    }
    $版本 = (Get-Content -LiteralPath $版本文件 -Raw -Encoding UTF8 | ConvertFrom-Json).发布
}

$发布目录 = Join-Path $root "发布"
$暂存 = Join-Path $发布目录 "中文Java-$版本"
$压缩包 = Join-Path $发布目录 "中文Java-$版本.zip"

function 执行([string]$说明, [scriptblock]$动作) {
    Write-Host "== $说明 ==" -ForegroundColor Cyan
    # 原生命令（如 mvn）会把 JVM 警告写到 stderr，不应被当作失败；成败只看退出码
    $偏好 = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $动作
        $退出码 = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $偏好
    }
    if ($退出码 -ne 0) {
        Write-Error "$说明 失败（退出码 $退出码）"
        exit $退出码
    }
}

function 复制([string]$源, [string]$目标) {
    if (-not (Test-Path $源)) {
        Write-Error "缺少文件：$源"
        exit 2
    }
    Copy-Item -Path $源 -Destination $目标 -Recurse -Force
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "找不到 mvn，无法构建"
    exit 2
}

# 1) 构建
if (-not $跳过构建) {
    $测试参数 = @()
    if ($跳过测试) {
        $测试参数 = @("-DskipTests")
    }
    执行 "构建标准库 cjc-stdlib" {
        & mvn -q -f "$root\标准库\pom.xml" clean package @测试参数
    }
    执行 "构建 Maven 插件 cjc-maven-plugin" {
        & mvn -q -f "$root\工具\maven-插件\pom.xml" clean package -DskipTests
    }
    if (-not $跳过原生) {
        执行 "构建原生补丁（JDK 25）" {
            & "$root\工具\原生补丁\编译.ps1"
        }
    }
}

# 2) 组装暂存目录
if (Test-Path $暂存) {
    Remove-Item -Recurse -Force $暂存
}
New-Item -ItemType Directory -Force $暂存 | Out-Null

复制 (Join-Path $root "README.md") $暂存
复制 (Join-Path $root "CHANGELOG.md") $暂存
复制 (Join-Path $root "语言规范.md") $暂存
复制 (Join-Path $root "速查表.md") $暂存
复制 (Join-Path $root "AGENTS.md") $暂存
复制 (Join-Path $root "文档\历史") (Join-Path $暂存 "文档\历史")
$发布说明 = Join-Path $root "发布说明-$版本.md"
if (Test-Path $发布说明) {
    Copy-Item $发布说明 $暂存 -Force
} else {
    Write-Warning "未找到 发布说明-$版本.md，分发包中不含发布说明"
}
复制 (Join-Path $root "cjc.ps1") $暂存
复制 (Join-Path $root "教程") $暂存
复制 (Join-Path $root "示例") $暂存

New-Item -ItemType Directory -Force (Join-Path $暂存 "工具\cjc") | Out-Null
Copy-Item "$root\工具\cjc\Cjc.java", "$root\工具\cjc\关键字表.txt", "$root\工具\cjc\近义词典.txt", "$root\工具\cjc\词林.txt", "$root\工具\cjc\词典源.json", "$root\工具\cjc\词典源.py", "$root\工具\cjc\同步词表.ps1", "$root\工具\cjc\词典更新.ps1", "$root\工具\cjc\提交前检查.ps1" `
    (Join-Path $暂存 "工具\cjc") -Force

复制 (Join-Path $root "工具\版本.json") (Join-Path $暂存 "工具")
复制 (Join-Path $root "工具\原生补丁") (Join-Path $暂存 "工具\原生补丁")
复制 (Join-Path $root "工具\对话") (Join-Path $暂存 "工具")

New-Item -ItemType Directory -Force (Join-Path $暂存 "标准库") | Out-Null
$标准库jar = Get-ChildItem "$root\标准库\target\cjc-stdlib-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $标准库jar) {
    Write-Error "找不到标准库 jar，请先构建"
    exit 2
}
Copy-Item $标准库jar.FullName (Join-Path $暂存 "标准库") -Force
Copy-Item "$root\标准库\pom.xml" (Join-Path $暂存 "标准库") -Force
复制 (Join-Path $root "标准库\src") (Join-Path $暂存 "标准库")

New-Item -ItemType Directory -Force (Join-Path $暂存 "工具\maven-插件") | Out-Null
$插件jar = Get-ChildItem "$root\工具\maven-插件\target\cjc-maven-plugin-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($插件jar) {
    Copy-Item $插件jar.FullName (Join-Path $暂存 "工具\maven-插件") -Force
} else {
    Write-Warning "未找到 Maven 插件 jar，分发包中不含插件"
}

$扩展源 = Join-Path $root "工具\vscode-中文java"
if (Test-Path $扩展源) {
    # 除 vsix 外还带上 关键字数据.json 与 syntaxes\中文java.tmLanguage.json，
    # 分发包内的 同步词表.ps1 / 词典更新.ps1 需要写这两个文件
    复制 $扩展源 (Join-Path $暂存 "工具\vscode-中文java")
    if (-not (Get-ChildItem (Join-Path $扩展源 "*.vsix") -ErrorAction SilentlyContinue)) {
        Write-Warning "未找到 VSCode 扩展 vsix，分发包中只含扩展源码"
    }
} else {
    Write-Warning "未找到 VSCode 扩展目录，分发包中不含扩展"
}

New-Item -ItemType Directory -Force $发布目录 | Out-Null

# 3) 压缩
if (Test-Path $压缩包) {
    Remove-Item -Force $压缩包
}
Compress-Archive -Path (Join-Path $暂存 "*") -DestinationPath $压缩包 -CompressionLevel Optimal
$大小KB = [math]::Round((Get-Item $压缩包).Length / 1KB, 1)
$文件数 = (Get-ChildItem $暂存 -Recurse -File).Count

if (-not $保留暂存) {
    Remove-Item -Recurse -Force $暂存
}

Write-Host "发布完成：$压缩包（$文件数 个文件，$大小KB KB）" -ForegroundColor Green
