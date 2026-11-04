# 编译 jdk.compiler 原生补丁（中文关键字）
# 说明：补丁只替换 com.sun.tools.javac.parser.Tokens，向关键字表追加中文别名，
#       不改动 javac 其余逻辑，因此英文源码行为不变。
# 锁定版本：本补丁基于 JDK 25.0.2 的 src.zip 生成，升级 JDK 后需重新执行本脚本。

chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 版本锁定检查：补丁源文件取自 JDK 25 的 src.zip
$javaExe = (Get-Command java).Source
$javaHome = Split-Path (Split-Path $javaExe -Parent) -Parent
$releaseFile = Join-Path $javaHome 'release'
if (Test-Path $releaseFile) {
    $hit = Select-String -Path $releaseFile -Pattern 'JAVA_VERSION="(.+)"' | Select-Object -First 1
    if ($hit -and $hit.Matches[0].Groups[1].Value -notmatch '^25') {
        Write-Warning "补丁源文件取自 JDK 25.0.2，当前 JDK 为 $($hit.Matches[0].Groups[1].Value)，编译前请先比对 Tokens.java 差异"
    }
}

$root = $PSScriptRoot
$src = Join-Path $root '源码'
$out = Join-Path $root '类'

if (-not (Test-Path $src)) {
    Write-Error "找不到补丁源码目录：$src"
    exit 2
}
if (Test-Path $out) {
    Remove-Item -Recurse -Force $out
}
New-Item -ItemType Directory -Force $out | Out-Null

javac --patch-module "jdk.compiler=$src" --add-modules jdk.compiler -encoding UTF-8 -d $out "$src\com\sun\tools\javac\parser\Tokens.java"
if ($LASTEXITCODE -ne 0) {
    Write-Error "补丁编译失败，退出码 $LASTEXITCODE"
    exit $LASTEXITCODE
}
Write-Output "补丁编译成功：$out"
exit 0
