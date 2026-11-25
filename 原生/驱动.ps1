# 原生实现驱动（M3：C 后端 MVP）
# 对外入口：.\cjc.ps1 原生 <检查|语法树|快照|词表|语义|中间|运行|对拍|编译|对拍原生> [参数...]
# M3 把教程 01–08 的 MIR 降级为 C，用 clang 链接成 Windows exe。
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$参数,
    # 编译输出路径；显式声明避免 PowerShell 把 -o 当成 -OutVariable 的缩写。
    [string]$o,
    # 关闭增量缓存，强制全量编译（用于对照与排错）。
    [switch]$无缓存,
    # M12：clang 优化级别（默认 O2 不变）；仅影响原生编译，供基准各档实测。
    [ValidateSet("O0", "O1", "O2", "O3", "Os")]
    [string]$优化 = "O2",
    # 目标平台。windows=PE/Win64（默认，逐字节保留 M0–M13 行为）；linux=ELF/musl/SysV；
    # darwin=macOS Mach-O（M21 ADR-024 G7：骨架 + CI 待跑，本机无 macOS SDK 无法真机链接）。
    [ValidateSet("windows", "linux", "darwin")]
    [string]$平台 = "windows",
    # M15：依赖清单路径（可选）。给定时其闭包哈希并入缓存键，改依赖即触发重编。
    [string]$清单,
    # M17：内存检查（仅 linux 生效）。注入 ASan 编译/链接参数，并并入工具链哈希隔离缓存。
    # ASan 不可用时按 ADR-020 的阶梯降级，本旗标保持"尽力检测"语义，不作为硬门槛。
    [switch]$内存检查
)

$ErrorActionPreference = "Continue"
# clang 优化开关：-优化 <档> 展开为 "-O<档>"（Os 保持 "-Os"）。
$优化开关 = if ($优化 -eq "Os") { "-Os" } else { "-$优化" }
# M17：内存检查开关。仅在 linux 平台生效；Windows 给中文提示后忽略（不影响既有路径）。
$启用内存检查 = $false
if ($内存检查) {
    if ($平台 -eq 'linux') {
        $启用内存检查 = $true
    } else {
        [Console]::Error.WriteLine("提示：-内存检查 仅在 -平台 linux 生效，其他平台已忽略（改用 GC 压力用例）。")
    }
}
if ($env:OS -eq "Windows_NT") { chcp 65001 | Out-Null }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$根目录 = (Resolve-Path "$PSScriptRoot\..").Path

# ---- 平台配置（M14 一处定义，M21 扩 darwin，全链路引用）----
$目标Linux = $平台 -eq 'linux'
$目标Darwin = $平台 -eq 'darwin'
# M21：$目标Unix 控制共享 POSIX 路径（运行时 pthread/clock、C++ 源按 C++ 编译）；
# Windows 保持逐字节既有行为。darwin 与 linux 都走 Unix 分支，但工具链不同。
$目标Unix = $目标Linux -or $目标Darwin
$扩展名 = if ($目标Linux) { '.elf' } elseif ($目标Darwin) { '.macos' } else { '.exe' }
$muslTarget = 'x86_64-linux-musl'
$muslSysroot = Join-Path $根目录 "构建\工具链\alpine\sysroot"
$muslLib = Join-Path $muslSysroot "usr\lib"
$darwinTarget = 'x86_64-apple-darwin'
# clang 交叉编译参数：Linux 需 target/sysroot + 平台宏；darwin 只需 target + 平台宏
# （CI macOS runner 用系统 SDK，本机 Windows 无 SDK → G7 标注「CI 实跑待环境」）；
# Windows 保持既有（空）。
$平台编译参数 = if ($目标Linux) {
    # _GNU_SOURCE 同时开放 clock_gettime/_POSIX 与 pthread_getattr_np/musl GNU 扩展。
    @("--target=$muslTarget", "--sysroot=$muslSysroot", '-DCJK_PLATFORM_LINUX', '-D_GNU_SOURCE')
} elseif ($目标Darwin) {
    @("--target=$darwinTarget", '-DCJK_PLATFORM_DARWIN', '-D_GNU_SOURCE')
} else {
    @()
}
# M20 G5：Itanium 异常 ABI 的静态依赖。PE 用 mingw libstdc++（personality 由 clang 自动选），
# ELF/musl sysroot 无 C++ ABI/unwind，改从 Alpine 静态包落地的 lib 目录补齐。
# M21 darwin：CI macOS 用系统 libc++/libunwind（-nostdinc++ 不能用，需 SDK 头），
# 本机无 SDK 故不预置路径，靠 CI runner 系统工具链兜底。
$unwind库目录 = Join-Path $根目录 "构建\工具链\unwind\lib"
$unwind头文件 = Join-Path $根目录 "构建\工具链\unwind\libcxx\usr\include\c++\v1"
$CXX平台参数 = if ($目标Linux) {
    @('-nostdinc++', '-isystem', $unwind头文件)
} else {
    @()
}
$CXX链接库 = if ($目标Linux) {
    @('-L' + $unwind库目录, '-lc++abi', '-lunwind', '-llzma')
} elseif ($目标Darwin) {
    @('-lc++', '-lunwind')
} else {
    @('-lstdc++', '-lgcc_s')
}

# ---- M17 内存检查模式（ASan 优先，runtime 缺失时降级静态分析）----
# ADR-020 E1：musl 全静态 + 自建 GC 场景下，clang 需有 x86_64-unknown-linux-musl 的
# libclang_rt.asan*.a 才能链接 ASan；本机 msys clang 通常不含该 runtime，故实测探测。
$内存检查模式 = "none"   # none | asan | analyze
$asan编译参数 = @()
$asan链接参数 = @()
if ($启用内存检查) {
    # 内联探测 clang（查找clang 函数此时尚未定义，避免前向引用）。
    $探测clang = $null
    foreach ($候选 in @("C:\msys64\mingw64\bin\clang.exe")) {
        if (Test-Path $候选) { $探测clang = $候选; break }
    }
    if (-not $探测clang) {
        $探测命令 = Get-Command clang -ErrorAction SilentlyContinue
        if ($探测命令) { $探测clang = $探测命令.Source }
    }
    $有asan运行时 = $false
    if ($探测clang) {
        $资源目录 = (& $探测clang -print-resource-dir 2>$null).Trim()
        if ($资源目录 -and (Test-Path $资源目录)) {
            # 只认 musl target 的 asan 运行时（路径含 musl，如 .../lib/x86_64-unknown-linux-musl/）。
            # 不能放宽到 'linux'：clang 自带的 lib/linux/libclang_rt.asan-x86_64.a 是 glibc 版，
            # 依赖 _DYNAMIC/_Unwind_*/gnu_get_libc_version 等动态符号，与本链 musl -static
            # -nostdlib 链接不兼容（CI 上实测 undefined symbol）。glibc 版一律视为不可用，
            # 走 ADR-020 阶梯 B 静态分析。
            $asan库们 = @(Get-ChildItem (Join-Path $资源目录 "lib") -Filter "libclang_rt.asan*.a" -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.DirectoryName -match 'musl' -or $_.Name -match 'musl' })
            if ($asan库们.Count -gt 0) { $有asan运行时 = $true }
        }
    }
    if ($有asan运行时) {
        $内存检查模式 = "asan"
        $asan编译参数 = @('-fsanitize=address', '-fno-omit-frame-pointer', '-O1')
        $asan链接参数 = @('-fsanitize=address')
        Write-Host "内存检查：ASan 运行时可用，采用 ASan 模式（ADR-020 阶梯 A）。"
    } else {
        $内存检查模式 = "analyze"
        Write-Host "内存检查：musl ASan 运行时缺失，降级为 Clang 静态分析（ADR-020 阶梯 B）。"
    }
}

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
    Write-Output "  .\cjc.ps1 原生 分词   [路径...]"
    Write-Output "  .\cjc.ps1 原生 语法   [路径...]"
    Write-Output "  .\cjc.ps1 原生 语义   [路径...]"
    Write-Output "  .\cjc.ps1 原生 自举"
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
    # 平台/target/sysroot 纳入哈希：切换平台后缓存必然失效，避免 PE/ELF 串味。
    $文本 = 取文件哈希 $PSCommandPath + "|平台=" + $平台
    # M17：内存检查开关纳入哈希，避免 ASan 产物与普通产物串缓存。
    $文本 += "|内存检查=" + $(if ($启用内存检查) { "1" } else { "0" })
    if ($目标Linux) {
        $文本 += "|target=$muslTarget|sysroot=" + $muslSysroot
        if (Test-Path -LiteralPath $muslSysroot) {
            # sysroot 内 libc.a/crt*.o 变化也应让缓存失效。
            foreach ($关键 in @('usr\lib\crt1.o', 'usr\lib\crti.o', 'usr\lib\crtn.o', 'usr\lib\libc.a')) {
                $文本 += "|" + $关键 + ":" + (取文件哈希 (Join-Path $muslSysroot $关键))
            }
        }
        # M20 G5：Itanium 静态库变化同样影响异常 ABI，纳入缓存键。
        foreach ($库 in @('libc++abi.a', 'libunwind.a', 'liblzma.a')) {
            $路径 = Join-Path $unwind库目录 $库
            if (Test-Path -LiteralPath $路径) {
                $文本 += "|unwind:" + $库 + ":" + (取文件哈希 $路径)
            }
        }
    }
    foreach ($目录 in $源码目录们) {
        foreach ($源 in @(Get-ChildItem $目录 -Recurse -Filter *.java -File | Sort-Object FullName)) {
            $文本 += "|" + $源.FullName + ":" + (取文件哈希 $源.FullName)
        }
    }
    return 取文本哈希 $文本
}

# M15：计算依赖清单闭包哈希（调用 依赖清单.java 的 哈希 子命令）。
# 无清单时返回空串；清单解析失败时直接退出 2，避免静默复用旧产物。
function 取依赖哈希 {
    param([string]$清单路径)
    if ([string]::IsNullOrWhiteSpace($清单路径)) {
        return ""
    }
    $绝对 = @(转绝对路径 @($清单路径))[0]
    if (-not (Test-Path $绝对)) {
        Write-Output "依赖清单不存在：$绝对"
        exit 2
    }
    $java源 = Join-Path $根目录 "工具\cjc\依赖清单.java"
    # 经 UTF-8 文件中转，避免 Windows PowerShell 5.1 捕获 java stdout 时的中文路径乱码。
    $临时 = Join-Path $根目录 "构建\原生\缓存\依赖哈希.tmp"
    New-Item -ItemType Directory -Force (Split-Path $临时) | Out-Null
    $原始 = & java "-Dfile.encoding=UTF-8" $java源 "哈希" $绝对 $临时 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Output ($原始 -join [Environment]::NewLine)
        exit 2
    }
    return (Get-Content $临时 -Raw -Encoding UTF8).Trim()
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
    $缓存子目录 = if ($目标Linux) { "运行时-linux" } elseif ($目标Darwin) { "运行时-darwin" } else { "运行时" }
    $缓存目录 = Join-Path $根目录 ("构建\原生\缓存\" + $缓存子目录)
    New-Item -ItemType Directory -Force $缓存目录 | Out-Null
    $对象们 = @()
    $命中 = 0
    foreach ($源 in $运行时.源们) {
        $哈希 = 取文件哈希 $源.FullName
        $对象 = Join-Path $缓存目录 ("{0}-{1}.o" -f $源.BaseName, $哈希.Substring(0, 8))
        if (Test-Path $对象) {
            $命中++
        } else {
            $输出 = & $clang -c @平台编译参数 @asan编译参数 $优化开关 -std=c11 -fexceptions -fwrapv -fno-strict-aliasing -I $运行时.目录 $源.FullName -o $对象 2>&1
            if ($LASTEXITCODE -ne 0) {
                $输出 | ForEach-Object { Write-Output $_ }
                Write-Output ("运行时编译失败：{0}" -f $源.Name)
                exit 1
            }
        }
        $对象们 += $对象
    }
    # M20 G5：Itanium 抛出 shim（C++ 源文件，按平台选 personality）。
    $clangPP = Join-Path (Split-Path $clang -Parent) "clang++.exe"
    $异常源 = Join-Path $运行时.目录 "异常.cpp"
    $异常哈希 = 取文件哈希 $异常源
    $异常对象 = Join-Path $缓存目录 ("异常-{0}.o" -f $异常哈希.Substring(0, 8))
    if (Test-Path $异常对象) {
        $命中++
    } else {
        $输出 = & $clangPP -c @平台编译参数 @CXX平台参数 @asan编译参数 $优化开关 -fexceptions -fwrapv -fno-strict-aliasing -I $运行时.目录 $异常源 -o $异常对象 2>&1
        if ($LASTEXITCODE -ne 0) {
            $输出 | ForEach-Object { Write-Output $_ }
            Write-Output "运行时编译失败：异常.cpp"
            exit 1
        }
    }
    $对象们 += $异常对象
    return [pscustomobject]@{ 对象们 = $对象们; 命中 = $命中; 总数 = $运行时.源们.Count + 1 }
}

function 链接可执行 {
    param([string]$clang, [string]$模块对象, $运行时对象, [string]$输出exe)
    # Windows clang/ld 会把中文路径按 ANSI 解读；先复制为 ASCII 临时文件再链接。
    $链接目录 = Join-Path ([System.IO.Path]::GetTempPath()) ("cjc-link-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $链接目录 | Out-Null
    try {
        $临时对象们 = @()
        $索引 = 0
        foreach ($对象 in @(@($模块对象) + @($运行时对象.对象们))) {
            $临时对象 = Join-Path $链接目录 ("obj{0}.o" -f $索引)
            Copy-Item -LiteralPath $对象 -Destination $临时对象 -Force
            $临时对象们 += $临时对象
            $索引 = $索引 + 1
        }
        $临时输出 = Join-Path $链接目录 ("out" + $扩展名)
        if ($目标Linux) {
            # musl 静态链接：默认 ld 只支持 PE，必须 -fuse-ld=lld；且 clang 会注入
            # Windows crtbeginT/-lgcc，故 -nostdlib 并手动指定 musl crt1/crti/crtn + libc.a。
            # musl 1.2.5 已把 pthread/dl/rt 并入 libc，无需额外库。
            $输出 = & $clang --target=$muslTarget --sysroot=$muslSysroot -nostdlib -fuse-ld=lld -static @asan链接参数 `
                (Join-Path $muslLib "crt1.o") (Join-Path $muslLib "crti.o") `
                $临时对象们 $CXX链接库 -L$muslLib -lc (Join-Path $muslLib "crtn.o") -o $临时输出 2>&1
        } elseif ($目标Darwin) {
            # Mach-O：CI macOS runner 用系统 clang + SDK，clang++ 链接系统 libc++/libunwind。
            # 本机 Windows 无 macOS SDK，此分支只在 CI 执行（G7：CI 实跑待环境）。
            $clangPP = Join-Path (Split-Path $clang -Parent) "clang++"
            if (-not (Test-Path $clangPP)) { $clangPP = "clang++" }
            $输出 = & $clangPP --target=$darwinTarget @平台编译参数 $临时对象们 -lm @CXX链接库 -o $临时输出 2>&1
        } else {
            $clangPP = Join-Path (Split-Path $clang -Parent) "clang++.exe"
            $输出 = & $clangPP -static $临时对象们 -lm -lwinhttp -lws2_32 -lshell32 @CXX链接库 -o $临时输出 2>&1
        }
        if ($输出) {
            $输出 | ForEach-Object { [Console]::Error.WriteLine($_) }
        }
        if ($LASTEXITCODE -eq 0 -and (Test-Path $临时输出)) {
            Copy-Item -LiteralPath $临时输出 -Destination $输出exe -Force
        }
        return $LASTEXITCODE
    } finally {
        Remove-Item -LiteralPath $链接目录 -Recurse -Force -ErrorAction SilentlyContinue
    }
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
        平台 = $平台
        源哈希 = $源哈希
        运行时哈希 = $运行时哈希
        工具链哈希 = $工具链哈希
        # M15：依赖清单闭包哈希（无清单时为空串，既有路径零影响）。
        依赖哈希 = if ($script:依赖哈希) { $script:依赖哈希 } else { "" }
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
    Write-Output ("  目标平台：{0}（产物扩展名 {1}）" -f $平台, $扩展名)
    if ($目标Linux) {
        if (Test-Path -LiteralPath $muslSysroot) {
            Write-Output ("  musl sysroot：{0}" -f $muslSysroot)
        } else {
            Write-Output ("  musl sysroot 缺失：{0}（M14 工具链未落地）" -f $muslSysroot)
        }
    }
    if ($目标Darwin) {
        Write-Output ("  Mach-O target：{0}（macOS SDK 由 CI macos runner 提供；本机 Windows 无 SDK，真机对拍 CI 实跑待环境）" -f $darwinTarget)
    }
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
if ($子命令 -notin @('检查', '诊断', '分词', '语法', '语义', '语法树', '快照', '词表', '中间', '运行', '对拍', '编译', '对拍原生', 'llvm', '自举')) {
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
    '分词' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('分词', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '语法' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('语法', $根目录) + $绝对路径)
        exit $LASTEXITCODE
    }
    '语义' {
        $绝对路径 = 转绝对路径 $路径列表
        if ($绝对路径.Count -eq 0) {
            $绝对路径 = @((Join-Path $根目录 "教程"))
        }
        确保前端已编译
        运行前端 (@('语义', $根目录) + $绝对路径)
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
    '自举' {
        确保前端已编译
        & (Join-Path $PSScriptRoot "测试\对拍分词.ps1") -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        & (Join-Path $PSScriptRoot "测试\对拍语法.ps1") -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        & (Join-Path $PSScriptRoot "测试\对拍语义.ps1") -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        & (Join-Path $PSScriptRoot "测试\对拍中间码.ps1") -根目录 $根目录
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        & (Join-Path $PSScriptRoot "测试\对拍自举不动点.ps1") -根目录 $根目录
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
        # M13：编译接受 <源文件...>；第一个为入口。多源走"编译模块"共享分析。
        # 过滤空/纯空白位置参数：PowerShell 5.1 把中间的空数组 @() 展开成一个空串。
        $源参数 = @($参数[1..($参数.Count - 1)] | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $源文件们 = @(转绝对路径 @($源参数))
        if ($源文件们.Count -lt 1) {
            显示用法
            exit 2
        }
        $源文件 = $源文件们[0]
        $是多源 = $源文件们.Count -gt 1
        $输出exe = $null
        if ($o) {
            $输出exe = @(转绝对路径 @($o))[0]
        }
        foreach ($源 in $源文件们) {
            if (-not (Test-Path $源)) {
                Write-Output "源文件不存在：$源"
                exit 2
            }
        }
        $模块 = [System.IO.Path]::GetFileNameWithoutExtension($源文件)
        $C目录 = Join-Path $根目录 "构建\原生"
        New-Item -ItemType Directory -Force $C目录 | Out-Null
        $C文件 = Join-Path $C目录 ($模块 + ".c")
        if (-not $输出exe) {
            $输出exe = Join-Path $C目录 ($模块 + $扩展名)
        }
        New-Item -ItemType Directory -Force ([System.IO.Path]::GetDirectoryName($输出exe)) | Out-Null

        $clang = 查找clang
        if (-not $clang) {
            Write-Output "未找到 clang，无法生成原生可执行文件。"
            Write-Output "  请安装 MSYS2 的 mingw-w64-x86_64-clang，并把 C:\msys64\mingw64\bin 加入 PATH；"
            Write-Output "  或把 clang 放到 C:\msys64\mingw64\bin\clang.exe。"
            exit 2
        }
        # M20 G5：生成的 C 以 C++ 模式编译，才能落地 try/catch 的 landing 代码。
        $clangPP = Join-Path (Split-Path $clang -Parent) "clang++.exe"

        $运行时 = 取运行时信息
        $工具链哈希 = 取工具链哈希
        # M15：依赖清单闭包哈希并入缓存键（改依赖即触发重编）。无清单时为空串，行为不变。
        $script:依赖哈希 = 取依赖哈希 $清单
        if ($script:依赖哈希) {
            $工具链哈希 = $工具链哈希 + "|依赖=" + $script:依赖哈希
        }
        if ($是多源) {
            # 聚合哈希：任一被引用源文件变化都让缓存失效。
            $聚合 = ($源文件们 | ForEach-Object { 取文件哈希 $_ }) -join ''
            $源哈希 = 取文本哈希 $聚合
        } else {
            $源哈希 = 取文件哈希 $源文件
        }
        $缓存目录 = Join-Path $根目录 "构建\原生\缓存"
        $状态文件 = Join-Path $缓存目录 ("状态\" + $模块 + $(if ($目标Linux) { '.linux' } elseif ($目标Darwin) { '.darwin' } else { '' }) + ".json")

        if (-not $无缓存) {
            $状态 = 读取缓存状态 $状态文件
            if ($null -ne $状态 -and $状态.平台 -eq $平台 -and $状态.源哈希 -eq $源哈希 -and
                $状态.运行时哈希 -eq $运行时.哈希 -and $状态.工具链哈希 -eq $工具链哈希 -and
                $状态.输出 -eq $输出exe -and (Test-Path $输出exe)) {
                Write-Output ("增量：源文件与运行时未变，跳过编译（{0}）" -f $输出exe)
                exit 0
            }
        }

        确保前端已编译
        if ($是多源) {
            $前端参数 = @('编译模块', $根目录, $C文件, $源文件) + @($源文件们[1..($源文件们.Count - 1)])
            运行前端 $前端参数
        } else {
            运行前端 @('编译', $根目录, $源文件, $C文件)
        }
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }

        $C文本 = [System.IO.File]::ReadAllText($C文件, [System.Text.Encoding]::UTF8)
        $C哈希 = 取文本哈希 $C文本

        # M17 E1 阶梯 B：ASan 不可用时对生成的 C 与运行时 C 跑 clang 静态分析。
        # 告警写入报告文件（避免刷屏），stderr 只给一行摘要；不改变编译/链接流程，产物照常可运行（不设硬门槛）。
        if ($内存检查模式 -eq "analyze") {
            $分析工作目录 = Join-Path $根目录 "构建\原生\静态分析"
            New-Item -ItemType Directory -Force $分析工作目录 | Out-Null
            $分析报告 = Join-Path $根目录 "构建\原生\静态分析报告.txt"
            $所有告警 = @()
            $分析目标们 = @($C文件) + @($运行时.源们 | ForEach-Object FullName)
            foreach ($分析目标 in $分析目标们) {
                if (-not (Test-Path $分析目标)) { continue }
                $唯一名 = [guid]::NewGuid().ToString("N") + ".plist"
                $plist路径 = Join-Path $分析工作目录 $唯一名
                $分析结果 = & $clang --analyze $分析目标 -I $运行时.目录 -o $plist路径 2>&1
                if ($分析结果) { $所有告警 += @($分析结果) }
            }
            $告警文件们 = @($所有告警 | Where-Object { "($_)" -match 'warning:' })
            [System.IO.File]::WriteAllLines($分析报告, [string[]]$所有告警, (New-Object System.Text.UTF8Encoding($false)))
            [Console]::Error.WriteLine("内存检查：Clang 静态分析完成，潜在告警 $($告警文件们.Count) 条（详见 构建\原生\静态分析报告.txt）。")
        }

        if ($无缓存) {
            Write-Output "增量：未启用缓存（-无缓存）"
            # 与缓存分支共用「链接可执行」以绕开 clang/ld 对中文路径的 ANSI 解读：
            # 先强制重编运行时对象与模块对象（不读缓存），再把所有对象复制成 ASCII 名链接。
            $运行时对象 = 编译运行时对象 $clang $运行时
            $无缓存目录 = Join-Path ([System.IO.Path]::GetTempPath()) ("cjc-nocache-" + [guid]::NewGuid().ToString("N"))
            New-Item -ItemType Directory -Force $无缓存目录 | Out-Null
            $模块对象 = Join-Path $无缓存目录 "module.o"
            try {
                $模块输出 = & $clangPP -c -x c++ @平台编译参数 @CXX平台参数 @asan编译参数 $优化开关 -fexceptions -fwrapv -fno-strict-aliasing -I $运行时.目录 $C文件 -o $模块对象 2>&1
                $编译码 = $LASTEXITCODE
                if ($编译码 -ne 0) {
                    $模块输出 | ForEach-Object { Write-Output $_ }
                } else {
                    $编译码 = 链接可执行 $clang $模块对象 $运行时对象 $输出exe
                }
            } finally {
                Remove-Item -LiteralPath $无缓存目录 -Recurse -Force -ErrorAction SilentlyContinue
            }
            if ($编译码 -ne 0) {
                Write-Output "clang 编译失败（退出码 $编译码）。"
                exit 1
            }
        } else {
            $运行时对象 = 编译运行时对象 $clang $运行时
            $模块缓存 = Join-Path $缓存目录 $(if ($目标Linux) { "模块-linux" } elseif ($目标Darwin) { "模块-darwin" } else { "模块" })
            New-Item -ItemType Directory -Force $模块缓存 | Out-Null
            $模块对象 = Join-Path $模块缓存 ("{0}-{1}.o" -f $模块, $C哈希.Substring(0, 8))
            if (Test-Path $模块对象) {
                $模块状态 = "命中"
            } else {
                $输出 = & $clangPP -c -x c++ @平台编译参数 @CXX平台参数 @asan编译参数 $优化开关 -fexceptions -fwrapv -fno-strict-aliasing -I $运行时.目录 $C文件 -o $模块对象 2>&1
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
        # M13：llvm 编译接受 <源文件...>，第一个为入口；多源走"编译模块LLVM"。
        $源参数 = @($参数[2..($参数.Count - 1)] | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $源文件们 = @(转绝对路径 @($源参数))
        $源文件 = $源文件们[0]
        $是多源 = $源文件们.Count -gt 1
        foreach ($源 in $源文件们) {
            if (-not (Test-Path $源)) {
                Write-Output "源文件不存在：$源"
                exit 2
            }
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
            $输出exe = Join-Path $llvm目录 ($模块 + $扩展名)
        }

        $运行时 = 取运行时信息
        $工具链哈希 = 取工具链哈希
        # M15：依赖清单闭包哈希并入缓存键（改依赖即触发重编）。无清单时为空串，行为不变。
        $script:依赖哈希 = 取依赖哈希 $清单
        if ($script:依赖哈希) {
            $工具链哈希 = $工具链哈希 + "|依赖=" + $script:依赖哈希
        }
        if ($是多源) {
            $聚合 = ($源文件们 | ForEach-Object { 取文件哈希 $_ }) -join ''
            $源哈希 = 取文本哈希 $聚合
        } else {
            $源哈希 = 取文件哈希 $源文件
        }
        $缓存目录 = Join-Path $根目录 "构建\原生\缓存"
        $状态文件 = Join-Path $缓存目录 ("状态\" + $模块 + $(if ($目标Linux) { '.linux' } elseif ($目标Darwin) { '.darwin' } else { '' }) + ".llvm.json")

        if ($动作 -eq '编译' -and -not $无缓存) {
            $状态 = 读取缓存状态 $状态文件
            if ($null -ne $状态 -and $状态.平台 -eq $平台 -and $状态.源哈希 -eq $源哈希 -and
                $状态.运行时哈希 -eq $运行时.哈希 -and $状态.工具链哈希 -eq $工具链哈希 -and
                $状态.输出 -eq $输出exe -and (Test-Path $输出exe)) {
                Write-Output ("增量：源文件与运行时未变，跳过编译（{0}）" -f $输出exe)
                exit 0
            }
        }

        确保前端已编译
        # M14：前端 LLVM 生成器从 CJC_PLATFORM 读取目标平台配置；生成后恢复原值。
        $原平台环境变量 = [Environment]::GetEnvironmentVariable('CJC_PLATFORM')
        if ($目标Linux) {
            [Environment]::SetEnvironmentVariable('CJC_PLATFORM', 'linux')
        } elseif ($目标Darwin) {
            [Environment]::SetEnvironmentVariable('CJC_PLATFORM', 'darwin')
        } else {
            [Environment]::SetEnvironmentVariable('CJC_PLATFORM', $null)
        }
        $前端退出码 = 0
        try {
            if ($是多源) {
                $前端参数 = @('编译模块LLVM', $根目录, $IR文件, $源文件) + @($源文件们[1..($源文件们.Count - 1)])
                运行前端 $前端参数
            } else {
                运行前端 @('编译LLVM', $根目录, $源文件, $IR文件)
            }
            $前端退出码 = $LASTEXITCODE
        } finally {
            [Environment]::SetEnvironmentVariable('CJC_PLATFORM', $原平台环境变量)
        }
        if ($前端退出码 -ne 0) {
            exit $前端退出码
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
            if ($目标Unix) {
                # Unix 无缓存（linux/darwin）：先编 IR 与运行时对象，再统一走「链接可执行」（musl 需手动 crt 顺序）。
                $运行时对象 = 编译运行时对象 $clang $运行时
                $无缓存目录 = Join-Path ([System.IO.Path]::GetTempPath()) ("cjc-nocache-" + [guid]::NewGuid().ToString("N"))
                New-Item -ItemType Directory -Force $无缓存目录 | Out-Null
                $模块对象 = Join-Path $无缓存目录 "module.o"
                try {
                    $模块输出 = & $clang -c @平台编译参数 @asan编译参数 $优化开关 -std=c11 -fwrapv -fno-strict-aliasing $IR文件 -o $模块对象 2>&1
                    $编译码 = $LASTEXITCODE
                    if ($编译码 -ne 0) {
                        $模块输出 | ForEach-Object { Write-Output $_ }
                    } else {
                        $编译码 = 链接可执行 $clang $模块对象 $运行时对象 $输出exe
                    }
                } finally {
                    Remove-Item -LiteralPath $无缓存目录 -Recurse -Force -ErrorAction SilentlyContinue
                }
                if ($编译码 -ne 0) {
                    Write-Output "clang 编译/链接失败（退出码 $编译码）。"
                    exit 1
                }
            } else {
                $clangPP = Join-Path (Split-Path $clang -Parent) "clang++.exe"
                & $clangPP $优化开关 -fexceptions -fwrapv -fno-strict-aliasing -static -I $运行时.目录 $IR文件 @($运行时.源们 | ForEach-Object FullName) (Join-Path $运行时.目录 "异常.cpp") -lm -lwinhttp -lws2_32 -lshell32 @CXX链接库 -o $输出exe
                if ($LASTEXITCODE -ne 0) {
                    Write-Output "clang 编译失败（退出码 $LASTEXITCODE）。"
                    exit 1
                }
            }
        } else {
            $运行时对象 = 编译运行时对象 $clang $运行时
            $模块缓存 = Join-Path $缓存目录 $(if ($目标Linux) { "模块-linux" } elseif ($目标Darwin) { "模块-darwin" } else { "模块" })
            New-Item -ItemType Directory -Force $模块缓存 | Out-Null
            $模块对象 = Join-Path $模块缓存 ("{0}-{1}.ll.o" -f $模块, $IR哈希.Substring(0, 8))
            if (Test-Path $模块对象) {
                $模块状态 = "命中"
            } else {
                $输出 = & $clang -c @平台编译参数 @asan编译参数 $优化开关 -std=c11 -fwrapv -fno-strict-aliasing $IR文件 -o $模块对象 2>&1
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
