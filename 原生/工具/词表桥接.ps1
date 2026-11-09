# 原生前端词表桥接（M0）
# 只读 工具\cjc 下的 4 份词表，不复制、不缓存、不生成第二份词表。
# 用法：
#   .\原生\工具\词表桥接.ps1
#   .\原生\工具\词表桥接.ps1 -对象      # 返回统计对象，不打印
param(
    [string]$根目录 = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [switch]$对象
)

$ErrorActionPreference = "Stop"

$词表目录 = Join-Path $根目录 "工具\cjc"
$文件名 = [ordered]@{
    关键字表   = "关键字表.txt"
    近义词典   = "近义词典.txt"
    方法糖     = "方法糖.txt"
    诊断中文表 = "诊断中文表.txt"
}
foreach ($名 in $文件名.Keys) {
    $路径 = Join-Path $词表目录 $文件名[$名]
    if (-not (Test-Path $路径)) {
        Write-Error "找不到${名}：$路径"
        exit 2
    }
}

Import-Module (Join-Path $词表目录 "词表.psm1") -Force

$近义路径 = Join-Path $词表目录 "近义词典.txt"
$关键字路径 = Join-Path $词表目录 "关键字表.txt"
$糖路径 = Join-Path $词表目录 "方法糖.txt"
$诊断路径 = Join-Path $词表目录 "诊断中文表.txt"

# 关键字表：类型别名区段单独计数；其余区段过滤后交给 词表.psm1 的同一套解析器展开近义词典。
$表行 = @(Get-Content $关键字路径 -Encoding UTF8)
$别名起 = [array]::IndexOf($表行, '# 类型别名（java.lang 常用类型与基本类型的包装类，不是 Java 关键字）')
$别名止 = [array]::IndexOf($表行, '# 类型与字面量')
if ($别名起 -lt 0 -or $别名止 -le $别名起) {
    Write-Error "关键字表缺少类型别名区段标记"
    exit 2
}
$别名行 = @($表行[($别名起 + 1)..($别名止 - 1)] | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') })

$过滤行 = @()
for ($i = 0; $i -lt $表行.Count; $i++) {
    if ($i -ge $别名起 -and $i -lt $别名止) { continue }
    $过滤行 += $表行[$i]
}

$临时目录 = Join-Path ([System.IO.Path]::GetTempPath()) ("原生词表桥接-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $临时目录 | Out-Null
$临时表 = Join-Path $临时目录 "关键字表.txt"
try {
    [System.IO.File]::WriteAllLines($临时表, $过滤行, (New-Object System.Text.UTF8Encoding($false)))
    $词典 = 读取近义词典 $近义路径
    $关键字 = 读取关键字表 $临时表 $词典.Groups
} finally {
    Remove-Item -Recurse -Force $临时目录 -ErrorAction SilentlyContinue
}

# 方法糖：四区解析与 工具\发布\校验.ps1 保持同一规则。
$糖 = @{ 成员 = @(); 声明 = @(); 静态 = @(); 白名单 = @() }
$糖区 = $null
$糖行号 = 0
foreach ($糖行 in (Get-Content $糖路径 -Encoding UTF8)) {
    $糖行号++
    $糖行修剪 = $糖行.Trim()
    if ($糖行修剪 -eq '' -or $糖行修剪.StartsWith('#')) { continue }
    if ($糖行修剪 -match '^\[(.+)\]$') {
        $糖区 = $Matches[1]
        if (-not $糖.ContainsKey($糖区)) {
            Write-Error "方法糖表第 $糖行号 行未知分区：[$糖区]"
            exit 2
        }
        continue
    }
    if ($null -eq $糖区) {
        Write-Error "方法糖表第 $糖行号 行不属于任何分区：$糖行修剪"
        exit 2
    }
    $糖[$糖区] += $糖行修剪
}
foreach ($必需 in @('成员', '声明', '静态', '白名单')) {
    if ($糖[$必需].Count -eq 0) {
        Write-Error "方法糖表分区 [$必需] 为空或缺失"
        exit 2
    }
}

$诊断条数 = @(Get-Content $诊断路径 -Encoding UTF8 | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') }).Count

$结果 = [pscustomobject]@{
    关键字表英文   = $关键字.CanonicalByEn.Count
    关键字表中文   = $关键字.WordEn.Count
    类型别名       = $别名行.Count
    近义词典组     = $词典.Groups.Count
    方法糖成员     = $糖['成员'].Count
    方法糖声明     = $糖['声明'].Count
    方法糖静态     = $糖['静态'].Count
    方法糖白名单   = $糖['白名单'].Count
    诊断规则       = $诊断条数
    词表目录       = $词表目录
}
if ($对象) {
    return $结果
}

Write-Output "词表桥接：读取 4 份词表（唯一数据源）"
Write-Output ("  关键字表：英文 {0} / 中文 {1} / 类型别名 {2}" -f $结果.关键字表英文, $结果.关键字表中文, $结果.类型别名)
Write-Output ("  近义词典：{0} 组" -f $结果.近义词典组)
Write-Output ("  方法糖：成员 {0} / 声明 {1} / 静态 {2} / 白名单 {3}" -f $结果.方法糖成员, $结果.方法糖声明, $结果.方法糖静态, $结果.方法糖白名单)
Write-Output ("  诊断中文表：{0} 条" -f $结果.诊断规则)
Write-Output ("  来源：{0}" -f $结果.词表目录)
