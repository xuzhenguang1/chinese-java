# 原生实现驱动（M3：C 后端 MVP）
# 对外入口：.\cjc.ps1 原生 <检查|语法树|快照|词表|中间|运行|对拍|编译|对拍原生> [参数...]
# M3 把教程 01–08 的 MIR 降级为 C，用 clang 链接成 Windows exe。
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$参数,
    # 编译输出路径；显式声明避免 PowerShell 把 -o 当成 -OutVariable 的缩写。
    [string]$o,
    # 关闭增量缓存，强制全量编译（用于对照与排错）。
    [switch]$无缓存
)

$ErrorActionPreference = "Continue"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$根目录 = (Resolve-Path "$PSScriptRoot\..").Path
$桥接 = Join-Path $PSScriptRoot "工具\词表桥接.ps1"
$源码目录们 = @(
    (Join-Path $PSScriptRoot "前端\src"),
    (Join-Path $PSScriptRoot "中间表示\src"),
    (Join-Path $PSScriptRoot "后端C\src"),
    (Join-Path $PSScriptRoot "后端LLVM\src")
)
$类目录 = Join-Path $根目录 "构建\原生\前端\classes"
$编译标记 = Join-Path $类目录 ".已编译"
$主类 = "中文.原生.前端.前端主程序"
# 诊断 --json 时抑制驱动的非 JSON 输出，避免污染编辑器解析。
$script:静默 = $false

function 显示用法 {
    Write-Output "用法："
    Write-Output "  .\cjc.ps1 原生 检查   [路径...]"
    Write-Output "  .\cjc.ps1 原生 诊断   [--json] [路径...]"
    Write-Output "  .\cjc.ps1 原生 语法树 [路径...]"
    Write-Output "  .\cjc.ps1 原生 快照   <输出目录> [路径...]"
    Write-Output "  .\cjc.ps1 原生 中间   [路径...]"
    Write-Output "  .\cjc.ps1 原生 运行   [路径...]"
    Write-Output "  .\cjc.ps1 原生 对拍   [路径...]"
    Write-Output "  .\cjc.ps1 原生 编译   <源文件> [-o <输出 exe>] [-无缓存]"
    Write-Output "  .\cjc.ps1 原生 llvm 编译 <源文件> [-o <输出 exe>]"
    Write-Output "  .\cjc.ps1 原生 llvm 检查 <源文件>"
    Write-Output "  .\cjc.ps1 原生 对拍原生 [路径...]"
    Write-Output "  .\cjc.ps1 原生 词表"
    Write-Output "  M1 词法语法，M2 语义/MIR/解释器，M3 C 后端（教程 01–08）。"
}

function 查找clang {
    foreach ($候选 in @("C:\msys64\mingw64\bin\clang.exe")) {
        if (Test-Path $候选) {
            return $候选
        }
    }
    $命令 = Get-Command clang -ErrorAction SilentlyContinue
    if ($命令) {
        return $命令.Source
    }
    return $null
}

function 确保前端已编译 {
    $源码 = @()
    foreach ($目录 in $源码目录们) {
        $源码 += @(Get-ChildItem $目录 -Recurse -Filter *.java -File)
    }
    $需要编译 = -not (Test-Path $编译标记)
    if (-not $需要编译) {
        $最新 = ($源码 | Measure-Object -Property LastWriteTime -Maximum).Maximum
        $需要编译 = $最新 -gt (Get-Item $编译标记).LastWriteTime
    }
    if (-not $需要编译) {
        return
    }
    New-Item -ItemType Directory -Force $类目录 | Out-Null
    $输出 = & javac -encoding UTF-8 -d $类目录 ($源码 | ForEach-Object FullName) 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($script:静默) {
            $输出 | ForEach-Object { [Console]::Error.WriteLine($_) }
            [Console]::Error.WriteLine("前端编译失败。")
        } else {
            $输出 | ForEach-Object { Write-Output $_ }
            Write-Output "前端编译失败。"
        }
        exit 2
    }
    Set-Content -Path $编译标记 -Value (Get-Date -Format o) -Encoding UTF8
    if (-not $script:静默) {
        Write-Output ("前端编译：{0} 个源文件 -> 构建\原生\前端\classes" -f $源码.Count)
    }
}

function 转绝对路径 {
    param([string[]]$列表)
    $结果 = @()
    foreach ($项 in $列表) {
        if ([System.IO.Path]::IsPathRooted($项)) {
            $结果 += $项
        } else {
            $结果 += (Join-Path (Get-Location).Path $项)
        }
    }
    return $结果
}

function 取文件哈希 {
    param([string]$文件)
    if (-not (Test-Path -LiteralPath $文件)) {
        return ""
    }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $文件).Hash
}

function 取文本哈希 {
    param([string]$文本)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $字节 = [System.Text.Encoding]::UTF8.GetBytes($文本)
        return ([System.BitConverter]::ToString($sha.ComputeHash($字节))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

# 驱动、前端、中间表示与后端源码任一变化都会让缓存失效，避免改了编译器还复用旧产物。
function 取工具链哈希 {
    $文本 = 取文件哈希 $PSCommandPath
    foreach ($目录 in $源码目录们) {
        foreach ($源 in @(Get-ChildItem $目录 -Recurse -Filter *.java -File | Sort-Object FullName)) {
            $文本 += "|" + $源.FullName + ":" + (取文件哈希 $源.FullName)
        }
    }
    return 取文本哈希 $文本
}

function 取运行时信息 {
    $运行时 = Join-Path $PSScriptRoot "运行时"
    $源们 = @(Get-ChildItem $运行时 -Filter *.c -File | Sort-Object Name)
    if ($源们.Count -eq 0) {
        Write-Output "运行时源码缺失：$运行时"
        exit 2
    }
    $文本 = ""
    foreach ($源 in $源们) {
        $文本 += $源.Name + ":" + (取文件哈希 $源.FullName) + ";"
    }
    return [pscustomobject]@{
        目录 = $运行时
        源们 = $源们
        哈希 = (取文本哈希 $文本)
    }
}

# 每个运行时 .c 按内容哈希编译一次，产物放在 构建\原生\缓存\运行时。
function 编译运行时对象 {
    param([string]$clang, $运行时)
    $缓存目录 = Join-Path $根目录 "构建\原生\缓存\运行时"
    New-Item -ItemType Directory -Force $缓存目录 | Out-Null
    $对象们 = @()
    $命中 = 0
    foreach ($源 in $运行时.源们) {
        $哈希 = 取文件哈希 $源.FullName
        $对象 = Join-Path $缓存目录 ("{0}-{1}.o" -f $源.BaseName, $哈希.Substring(0, 8))
        if (Test-Path $对象) {
            $命中++
        } else {
            $输出 = & $clang -c -O2 -std=c11 -fwrapv -fno-strict-aliasing -I $运行时.目录 $源.FullName -o $对象 2>&1
            if ($LASTEXITCODE -ne 0) {
                $输出 | ForEach-Object { Write-Output $_ }
                Write-Output ("运行时编译失败：{0}" -f $源.Name)
                exit 1
            }
        }
        $对象们 += $对象
    }
    return [pscustomobject]@{ 对象们 = $对象们; 命中 = $命中; 总数 = $运行时.源们.Count }
}

function 链接可执行 {
    param([string]$clang, [string]$模块对象, $运行时对象, [string]$输出exe)
    $输出 = & $clang -static $模块对象 @($运行时对象.对象们) -lm -lwinhttp -lws2_32 -o $输出exe 2>&1
    if ($输出) {
        $输出 | ForEach-Object { [Console]::Error.WriteLine($_) }
    }
    return $LASTEXITCODE
}

function 读取缓存状态 {
    param([string]$状态文件)
    if (-not (Test-Path $状态文件)) {
        return $null
    }
    try {
        return (Get-Content $状态文件 -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function 写入缓存状态 {
    param([string]$状态文件, [string]$源哈希, [string]$运行时哈希, [string]$工具链哈希, [string]$输出)
    $状态 = [ordered]@{
        源哈希 = $源哈希
        运行时哈希 = $运行时哈希
        工具链哈希 = $工具链哈希
        输出 = $输出
        时间 = (Get-Date -Format o)
    }
    New-Item -ItemType Directory -Force (Split-Path $状态文件) | Out-Null
    ($状态 | ConvertTo-Json) | Set-Content -Path $状态文件 -Encoding UTF8
}

function 运行前端 {
    param([string[]]$前端参数)
    & java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp $类目录 $主类 @前端参数
}

function 输出源码统计 {
    param([string[]]$路径列表)
    $统计 = @()
    foreach ($路径 in $路径列表) {
        if (-not (Test-Path $路径)) {
            Write-Output "路径不存在：$路径"
            exit 2
        }
        $条目 = Get-Item $路径
        if ($条目.PSIsContainer) {
            $文件 = @(Get-ChildItem $路径 -Recurse -Filter *.java -File | Sort-Object FullName)
        } else {
            $文件 = @($条目)
        }
        $行数 = 0
        foreach ($文件项 in $文件) {
            $行数 += (Get-Content $文件项.FullName -Encoding UTF8 | Measure-Object -Line).Lines
        }
        $显示 = $路径
        if ($显示.StartsWith($根目录)) {
            $显示 = $显示.Substring($根目录.Length).TrimStart('\')
        }
        $统计 += [pscustomobject]@{ 路径 = $显示; 文件数 = $文件.Count; 行数 = $行数 }
    }

    Write-Output "源码统计："
    foreach ($项 in $统计) {
        Write-Output ("  {0}：{1} 个 .java 文件 / {2} 行" -f $项.路径, $项.文件数, $项.行数)
    }
}

function 输出工具链 {
    $clang = 查找clang

    Write-Output "工具链："
    if ($clang) {
        $版本 = (& $clang --version 2>&1 | Select-Object -First 1)
        Write-Output ("  clang：{0}（{1}）" -f $版本, $clang)
    } else {
        Write-Output "  clang：未找到；M3 起需要 C 后端，请安装 MSYS2 mingw-w64-x86_64-clang 并把 C:\msys64\mingw64\bin 加入 PATH。"
    }
}

if ($参数.Count -eq 0) {
    显示用法
    exit 2
}

$子命令 = $参数[0]
if ($子命令 -in @('-h', '--帮助', '帮助')) {
    显示用法
    exit 0
}
if ($子命令 -notin @('检查', '诊断', '语法树', '快照', '词表', '中间', '运行', '对拍', '编译', '对拍原生', 'llvm')) {
    Write-Output "未知原生子命令：$子命令"
    显示用法
    exit 2
}

$路径列表 = @()
if ($参数.Count -gt 1) {
    $路径列表 = @($参数[1..($参数.Count - 1)])
}

switch ($子命令) {
    '词表' {
        & $桥接 -根目录 $根目录
        exit $LASTEXITCODE
    }
    '检查' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        Write-Output "原生驱动（M1）：词法与语法检查"
        & $桥接 -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        输出源码统计 $绝对路径
        输出工具链
        确保前端已编译
        运行前端 (@('检查', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '诊断' {
        $json = $false
        $文件参数 = @()
        foreach ($项 in $路径列表) {
            if ($项 -eq '--json') {
                $json = $true
            } else {
                $文件参数 += $项
            }
        }
        $绝对路径 = 转绝对路径 $文件参数
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        $script:静默 = $json
        确保前端已编译
        $前端参数 = @('诊断', $根目录) + $绝对路径
        if ($json) {
            $前端参数 += '--json'
        }
        运行前端 $前端参数
        exit $LASTEXITCODE
    }
    '语法树' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('语法树', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '快照' {
        if ($参数.Count -lt 2) {
            显示用法
            exit 2
        }
        $输出目录 = @(转绝对路径 @($参数[1]))[0]
        $文件路径 = @()
        if ($参数.Count -gt 2) {
            $文件路径 = 转绝对路径 @($参数[2..($参数.Count - 1)])
        }
        确保前端已编译
        运行前端 (@('快照', $根目录, $输出目录) + $文件路径)
        exit $LASTEXITCODE
    }
    '中间' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('中间', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '运行' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('运行', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '对拍' {
        确保前端已编译
        & (Join-Path $PSScriptRoot "测试\对拍.ps1") -根目录 $根目录 -类目录 $类目录 -路径 $路径列表
        exit $LASTEXITCODE
    }
    '对拍原生' {
        确保前端已编译
        & (Join-Path $PSScriptRoot "测试\对拍原生.ps1") -根目录 $根目录 -类目录 $类目录 -路径 $路径列表
        exit $LASTEXITCODE
    }
    '编译' {
        if ($参数.Count -lt 2) {
            显示用法
            exit 2
        }
        $源文件 = @(转绝对路径 @($参数[1]))[0]
        $输出exe = $null
        if ($o) {
            $输出exe = @(转绝对路径 @($o))[0]
        }
        for ($i = 2; $i -lt $参数.Count; $i++) {
            Write-Output "未知编译参数：$($参数[$i])"
            显示用法
            exit 2
        }
        if (-not (Test-Path $源文件)) {
            Write-Output "源文件不存在：$源文件"
            exit 2
        }
        $模块 = [System.IO.Path]::GetFileNameWithoutExtension($源文件)
        $C目录 = Join-Path $根目录 "构建\原生"
        New-Item -ItemType Directory -Force $C目录 | Out-Null
        $C文件 = Join-Path $C目录 ($模块 + ".c")
        if (-not $输出exe) {
            $输出exe = Join-Path $C目录 ($模块 + ".exe")
        }
        New-Item -ItemType Directory -Force ([System.IO.Path]::GetDirectoryName($输出exe)) | Out-Null

        $clang = 查找clang
        if (-not $clang) {
            Write-Output "未找到 clang，无法生成原生可执行文件。"
            Write-Output "  请安装 MSYS2 的 mingw-w64-x86_64-clang，并把 C:\msys64\mingw64\bin 加入 PATH；"
            Write-Output "  或把 clang 放到 C:\msys64\mingw64\bin\clang.exe。"
            exit 2
        }

        $运行时 = 取运行时信息
        $工具链哈希 = 取工具链哈希
        $源哈希 = 取文件哈希 $源文件
        $缓存目录 = Join-Path $根目录 "构建\原生\缓存"
        $状态文件 = Join-Path $缓存目录 ("状态\" + $模块 + ".json")

        if (-not $无缓存) {
            $状态 = 读取缓存状态 $状态文件
            if ($null -ne $状态 -and $状态.源哈希 -eq $源哈希 -and
                $状态.运行时哈希 -eq $运行时.哈希 -and $状态.工具链哈希 -eq $工具链哈希 -and
                $状态.输出 -eq $输出exe -and (Test-Path $输出exe)) {
                Write-Output ("增量：源文件与运行时未变，跳过编译（{0}）" -f $输出exe)
                exit 0
            }
        }

        确保前端已编译
        运行前端 @('编译', $根目录, $源文件, $C文件)
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }

        $C文本 = [System.IO.File]::ReadAllText($C文件, [System.Text.Encoding]::UTF8)
        $C哈希 = 取文本哈希 $C文本

        if ($无缓存) {
            Write-Output "增量：未启用缓存（-无缓存）"
            & $clang -O2 -std=c11 -fwrapv -fno-strict-aliasing -static -I $运行时.目录 $C文件 @($运行时.源们 | ForEach-Object FullName) -lm -lwinhttp -lws2_32 -o $输出exe
            if ($LASTEXITCODE -ne 0) {
                Write-Output "clang 编译失败（退出码 $LASTEXITCODE）。"
                exit 1
            }
        } else {
            $运行时对象 = 编译运行时对象 $clang $运行时
            $模块缓存 = Join-Path $缓存目录 "模块"
            New-Item -ItemType Directory -Force $模块缓存 | Out-Null
            $模块对象 = Join-Path $模块缓存 ("{0}-{1}.o" -f $模块, $C哈希.Substring(0, 8))
            if (Test-Path $模块对象) {
                $模块状态 = "命中"
            } else {
                $输出 = & $clang -c -O2 -std=c11 -fwrapv -fno-strict-aliasing -I $运行时.目录 $C文件 -o $模块对象 2>&1
                if ($LASTEXITCODE -ne 0) {
                    $输出 | ForEach-Object { Write-Output $_ }
                    Write-Output "clang 编译失败（退出码 $LASTEXITCODE）。"
                    exit 1
                }
                $模块状态 = "未命中"
            }
            $链接码 = 链接可执行 $clang $模块对象 $运行时对象 $输出exe
            if ($链接码 -ne 0) {
                Write-Output "clang 链接失败（退出码 $链接码）。"
                exit 1
            }
            Write-Output ("增量：运行时命中 {0}/{1}，模块{2}" -f $运行时对象.命中, $运行时对象.总数, $模块状态)
            写入缓存状态 $状态文件 $源哈希 $运行时.哈希 $工具链哈希 $输出exe
        }
        Write-Output ("已生成 C：{0}" -f $C文件)
        Write-Output ("已生成可执行文件：{0}" -f $输出exe)
        exit 0
    }
    'llvm' {
        if ($参数.Count -lt 3) {
            显示用法
            exit 2
        }
        $动作 = $参数[1]
        if ($动作 -notin @('编译', '检查')) {
            Write-Output "未知 llvm 动作：$动作"
            显示用法
            exit 2
        }
        $源文件 = @(转绝对路径 @($参数[2]))[0]
        for ($i = 3; $i -lt $参数.Count; $i++) {
            Write-Output "未知 llvm 参数：$($参数[$i])"
            显示用法
            exit 2
        }
        if (-not (Test-Path $源文件)) {
            Write-Output "源文件不存在：$源文件"
            exit 2
        }
        $模块 = [System.IO.Path]::GetFileNameWithoutExtension($源文件)
        $llvm目录 = Join-Path $根目录 "构建\原生\llvm"
        New-Item -ItemType Directory -Force $llvm目录 | Out-Null
        $IR文件 = Join-Path $llvm目录 ($模块 + ".ll")

        $输出exe = $null
        if ($o) {
            $输出exe = @(转绝对路径 @($o))[0]
        }
        if (-not $输出exe) {
            $输出exe = Join-Path $llvm目录 ($模块 + ".exe")
        }

        $运行时 = 取运行时信息
        $工具链哈希 = 取工具链哈希
        $源哈希 = 取文件哈希 $源文件
        $缓存目录 = Join-Path $根目录 "构建\原生\缓存"
        $状态文件 = Join-Path $缓存目录 ("状态\" + $模块 + ".llvm.json")

        if ($动作 -eq '编译' -and -not $无缓存) {
            $状态 = 读取缓存状态 $状态文件
            if ($null -ne $状态 -and $状态.源哈希 -eq $源哈希 -and
                $状态.运行时哈希 -eq $运行时.哈希 -and $状态.工具链哈希 -eq $工具链哈希 -and
                $状态.输出 -eq $输出exe -and (Test-Path $输出exe)) {
                Write-Output ("增量：源文件与运行时未变，跳过编译（{0}）" -f $输出exe)
                exit 0
            }
        }

        确保前端已编译
        运行前端 @('编译LLVM', $根目录, $源文件, $IR文件)
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        Write-Output ("已生成 LLVM IR：{0}" -f $IR文件)
        if ($动作 -eq '检查') {
            exit 0
        }
        New-Item -ItemType Directory -Force ([System.IO.Path]::GetDirectoryName($输出exe)) | Out-Null

        $clang = 查找clang
        if (-not $clang) {
            Write-Output "未找到 clang，无法生成原生可执行文件。"
            exit 2
        }

        $IR文本 = [System.IO.File]::ReadAllText($IR文件, [System.Text.Encoding]::UTF8)
        $IR哈希 = 取文本哈希 $IR文本

        if ($无缓存) {
            Write-Output "增量：未启用缓存（-无缓存）"
            & $clang -O2 -std=c11 -fwrapv -fno-strict-aliasing -static -I $运行时.目录 $IR文件 @($运行时.源们 | ForEach-Object FullName) -lm -lwinhttp -lws2_32 -o $输出exe
            if ($LASTEXITCODE -ne 0) {
                Write-Output "clang 编译失败（退出码 $LASTEXITCODE）。"
                exit 1
            }
        } else {
            $运行时对象 = 编译运行时对象 $clang $运行时
            $模块缓存 = Join-Path $缓存目录 "模块"
            New-Item -ItemType Directory -Force $模块缓存 | Out-Null
            $模块对象 = Join-Path $模块缓存 ("{0}-{1}.ll.o" -f $模块, $IR哈希.Substring(0, 8))
            if (Test-Path $模块对象) {
                $模块状态 = "命中"
            } else {
                $输出 = & $clang -c -O2 -std=c11 -fwrapv -fno-strict-aliasing $IR文件 -o $模块对象 2>&1
                if ($LASTEXITCODE -ne 0) {
                    $输出 | ForEach-Object { Write-Output $_ }
                    Write-Output "clang 编译失败（退出码 $LASTEXITCODE）。"
                    exit 1
                }
                $模块状态 = "未命中"
            }
            $链接码 = 链接可执行 $clang $模块对象 $运行时对象 $输出exe
            if ($链接码 -ne 0) {
                Write-Output "clang 链接失败（退出码 $链接码）。"
                exit 1
            }
            Write-Output ("增量：运行时命中 {0}/{1}，模块{2}" -f $运行时对象.命中, $运行时对象.总数, $模块状态)
            写入缓存状态 $状态文件 $源哈希 $运行时.哈希 $工具链哈希 $输出exe
        }
        Write-Output ("已生成可执行文件：{0}" -f $输出exe)
        exit 0
    }
}
