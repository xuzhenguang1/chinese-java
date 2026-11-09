# M2 对拍：教程 01–10 的程序 stdout 与退出码，必须与 JVM 后端逐字节一致。
# 用法：.\原生\测试\对拍.ps1 [-根目录 <目录>] [-类目录 <目录>] [-路径 <文件或目录...>]
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$类目录 = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path "构建\原生\前端\classes"),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$路径
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cjc = Join-Path $根目录 "cjc.ps1"
$工作目录 = Join-Path $根目录 "构建\原生\对拍"
$jvm目录 = Join-Path $工作目录 "jvm"
if (Test-Path $工作目录) {
    Remove-Item $工作目录 -Recurse -Force
}
New-Item -ItemType Directory -Force $工作目录 | Out-Null
New-Item -ItemType Directory -Force $jvm目录 | Out-Null

$文件们 = @()
if ($路径 -and $路径.Count -gt 0) {
    foreach ($项 in $路径) {
        if (Test-Path $项 -PathType Container) {
            # M2 只对教程 01–10 负责，目录输入自动收敛到这一段。
            $文件们 += @(Get-ChildItem $项 -Filter *.java -File |
                Where-Object { $_.Name -match '^第(0[1-9]|10)课' } | Sort-Object Name)
        } elseif (Test-Path $项) {
            $文件们 += @(Get-Item $项)
        } else {
            Write-Output "路径不存在：$项"
            exit 2
        }
    }
} else {
    $文件们 = @(Get-ChildItem (Join-Path $根目录 "教程") -Filter *.java -File |
        Where-Object { $_.Name -match '^第(0[1-9]|10)课' } | Sort-Object Name)
}

if ($文件们.Count -eq 0) {
    Write-Output "没有找到要对比的文件。"
    exit 2
}

function 读取文本([string]$文件) {
    if (-not (Test-Path $文件)) {
        return ""
    }
    return [System.IO.File]::ReadAllText($文件, [System.Text.Encoding]::UTF8)
}

$通过 = 0
$失败 = @()

foreach ($文件 in $文件们) {
    $名 = $文件.BaseName
    $JvmOut = Join-Path $工作目录 "$名.jvm.out"
    $JvmErr = Join-Path $工作目录 "$名.jvm.err"
    $MirOut = Join-Path $工作目录 "$名.mir.out"
    $MirErr = Join-Path $工作目录 "$名.mir.err"

    & $cjc 编译 $文件.FullName -d $jvm目录 *> $null
    $编译码 = $LASTEXITCODE
    if ($编译码 -ne 0) {
        $失败 += "JVM 编译失败：$名"
        continue
    }

    & java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dfile.encoding=UTF-8" `
        -cp $jvm目录 $名 1> $JvmOut 2> $JvmErr
    $jvm码 = $LASTEXITCODE

    & java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" `
        -cp $类目录 中文.原生.前端.前端主程序 运行 $根目录 $文件.FullName 1> $MirOut 2> $MirErr
    $mir码 = $LASTEXITCODE

    $同输出 = $false
    if ((Test-Path $JvmOut) -and (Test-Path $MirOut)) {
        $甲 = [System.IO.File]::ReadAllBytes($JvmOut)
        $乙 = [System.IO.File]::ReadAllBytes($MirOut)
        $同输出 = ($甲.Length -eq $乙.Length)
        if ($同输出) {
            for ($i = 0; $i -lt $甲.Length; $i++) {
                if ($甲[$i] -ne $乙[$i]) {
                    $同输出 = $false
                    break
                }
            }
        }
    }

    if ($同输出 -and $jvm码 -eq $mir码) {
        $通过++
        Write-Output ("通过  {0}  stdout {1} 字节，退出码 {2}" -f $名, ([System.IO.File]::ReadAllBytes($JvmOut)).Length, $jvm码)
        continue
    }

    $失败 += $名
    Write-Output ("失败  {0}  JVM 退出码 {1} / 解释器退出码 {2}" -f $名, $jvm码, $mir码)
    if (-not $同输出) {
        Write-Output "  JVM stdout："
        (读取文本 $JvmOut) -split "`R" | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
        Write-Output "  解释器 stdout："
        (读取文本 $MirOut) -split "`R" | Select-Object -First 12 | ForEach-Object { Write-Output ("    " + $_) }
    }
    $错 = 读取文本 $MirErr
    if ($错) {
        Write-Output "  解释器 stderr："
        $错 -split "`R" | Select-Object -First 8 | ForEach-Object { Write-Output ("    " + $_) }
    }
}

Write-Output ""
Write-Output ("对拍结果：通过 {0} / 共 {1}" -f $通过, $文件们.Count)
if ($失败.Count -gt 0) {
    Write-Output ("失败：" + ($失败 -join "、"))
    exit 1
}
exit 0
