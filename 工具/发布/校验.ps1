# 全量校验（CI 用）：关键字表、中文源码检查、标准库单元测试，可选原生补丁回归。
#
# 用法（在工程根目录或任意目录执行）：
#   .\工具\发布\校验.ps1                 # 路线 A + 标准库测试
#   .\工具\发布\校验.ps1 -含原生          # 追加路线 B（编译补丁并编译教程）
#
# 退出码：0 全部通过；1 存在失败项；2 环境缺失。

param(
    [switch]$含原生
)

$ErrorActionPreference = "Stop"
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$失败 = @()

function 运行([string]$说明, [scriptblock]$动作) {
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
        $script:失败 += $说明
        Write-Host "失败：$说明（退出码 $退出码）" -ForegroundColor Red
    }
}

# 1) 词表单源同步校验（关键字表 + 近义词典 → 原生补丁 / VSCode 数据 / 高亮正则）
运行 "词表同步检查" {
    & "$root\工具\cjc\同步词表.ps1" -检查
}

# 2) 关键字表加载校验 + 中文源码空格/标点检查
运行 "cjc 检查（教程 / 示例 / 标准库）" {
    & "$root\cjc.ps1" 检查 "$root\教程" "$root\示例" "$root\标准库\src" "--表" "$root\工具\cjc\关键字表.txt"
}

# 2.5) 关键字表表头版本必须与发布版本一致，避免文档与本表长期漂移
$tableHeader = (Get-Content "$root\工具\cjc\关键字表.txt" -TotalCount 1 -Encoding UTF8).Trim()
if ($tableHeader -notmatch 'v1\.4') {
    Write-Error "关键字表表头版本与发布不一致：$tableHeader（应为 v1.4）"
    exit 2
}
Write-Host "关键字表版本：$tableHeader"

# 2.6) 标准库类名白名单：`数值.转文本` / `文本.格式化` 不得被成员糖改写成英文方法名
$白名单目录 = Join-Path $root "构建\白名单检查"
if (Test-Path $白名单目录) {
    Remove-Item -Recurse -Force $白名单目录
}
New-Item -ItemType Directory -Force $白名单目录 | Out-Null
$白名单源码 = Join-Path $白名单目录 "白名单检查.java"
@'
导入 中文.标准库.*;

类 白名单检查 {
    入口 {
        打印行(数值.转文本(42));
        打印行(文本.格式化("%s", "甲"));
    }
}
'@ | Set-Content -Path $白名单源码 -Encoding UTF8
运行 "标准库类名白名单：转译" {
    & "$root\cjc.ps1" 转译 $白名单源码 -d (Join-Path $白名单目录 "输出")
}
$译后 = Get-Content (Join-Path $白名单目录 "输出\白名单检查.java") -Raw -Encoding UTF8
if ($译后 -match '数值\.转文本' -and $译后 -match '文本\.格式化') {
    Write-Host "标准库类名白名单：通过" -ForegroundColor Green
} else {
    $失败 += "标准库类名白名单"
    Write-Host "标准库类名白名单：标准库类名后的中文方法被成员糖改写" -ForegroundColor Red
}

# 3) 标准库单元测试（surefire 匹配 *测试.java）
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "找不到 mvn，无法运行标准库测试"
    exit 2
}
运行 "标准库 mvn test" {
    & mvn -q -f "$root\标准库\pom.xml" test
}

# 4) 语法糖冒烟：脚本模式与 入口
运行 "示例：脚本模式" {
    & "$root\工具\对话\运行.ps1" "$root\示例\脚本示例.java"
}
运行 "示例：入口写法" {
    & "$root\工具\对话\运行.ps1" "$root\示例\入口示例.java"
}
运行 "示例：字符串插值" {
    & "$root\工具\对话\运行.ps1" "$root\示例\插值示例.java"
}
运行 "示例：自动导入" {
    & "$root\工具\对话\运行.ps1" "$root\示例\自动导入示例.java"
}
运行 "示例：类型推断（--允许推断）" {
    & "$root\工具\对话\运行.ps1" "$root\示例\推断示例.java" -允许推断
}

# 5) 模板库：检查全部模板；有标准库 jar 时再编译一遍
$模板目录 = Join-Path $root "工具\对话\模板"
运行 "模板库：检查" {
    & "$root\cjc.ps1" 检查 $模板目录
}
$标准库jar = Get-ChildItem (Join-Path $root "标准库\target") -Filter "cjc-stdlib-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($标准库jar) {
    $模板输出 = Join-Path $root "构建\模板编译"
    if (Test-Path $模板输出) {
        Remove-Item -Recurse -Force $模板输出
    }
    运行 "模板库：编译" {
        & "$root\cjc.ps1" 编译 $模板目录 -d $模板输出 -cp $标准库jar.FullName
    }
} else {
    Write-Host "跳过模板编译：未找到标准库 jar" -ForegroundColor Yellow
}

# 6) VSCode 扩展：JS 语法（有 node 时）
if (Get-Command node -ErrorAction SilentlyContinue) {
    运行 "VSCode 扩展：extension.js 语法" {
        & node --check (Join-Path $root "工具\vscode-中文java\extension.js")
    }
} else {
    Write-Host "跳过扩展语法检查：未找到 node" -ForegroundColor Yellow
}

# 7) 诊断建议字段：两个反例必须报 CJK001/CJK002 且带「建议：」
$反例 = @((Join-Path $root "测试\粘连案例.java"), (Join-Path $root "测试\不支持标点.java"))
$偏好 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $反例输出 = (& "$root\cjc.ps1" 检查 @反例 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $偏好
}
if ($反例输出 -match 'CJK001' -and $反例输出 -match 'CJK002' -and $反例输出 -match '建议：') {
    Write-Host "诊断建议字段：通过" -ForegroundColor Green
} else {
    $失败 += "诊断建议字段"
    Write-Host "诊断建议字段：缺少错误码或「建议：」" -ForegroundColor Red
}

# 7.5) 运行时异常中文化：故意越界，输出必须含中文异常名与「建议：」
$异常目录 = Join-Path $root "构建\异常校验"
if (Test-Path $异常目录) {
    Remove-Item -Recurse -Force $异常目录
}
New-Item -ItemType Directory -Force $异常目录 | Out-Null
$异常源码 = Join-Path $异常目录 "异常校验.java"
@'
打印行("越界演示");
整数[] 数组 = {1, 2, 3};
打印行("值：" + 数组[5]);
'@ | Set-Content -Path $异常源码 -Encoding UTF8
$偏好 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $异常输出 = (& "$root\工具\对话\运行.ps1" $异常源码 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $偏好
}
if ($异常输出 -match '程序运行出错' -and $异常输出 -match '数组越界异常' -and $异常输出 -match '建议：') {
    Write-Host "运行时异常中文化：通过" -ForegroundColor Green
} else {
    $失败 += "运行时异常中文化"
    Write-Host "运行时异常中文化：输出未中文化" -ForegroundColor Red
}

# 7.6) 新建项目脚手架：只校验生成的骨架文件（不跑 mvn，避免依赖本地仓库）
$脚手架 = Join-Path $root "构建\脚手架校验\示例工具"
if (Test-Path (Split-Path $脚手架)) {
    Remove-Item -Recurse -Force (Split-Path $脚手架)
}
运行 "新建项目脚手架：生成" {
    Push-Location $root
    try {
        & "$root\cjc.ps1" 新建 项目 "构建\脚手架校验\示例工具"
    } finally {
        Pop-Location
    }
}
if ((Test-Path (Join-Path $脚手架 "pom.xml")) -and (Test-Path (Join-Path $脚手架 "src\main\中文\演示\应用\主程序.java"))) {
    Write-Host "新建项目脚手架：通过" -ForegroundColor Green
} else {
    $失败 += "新建项目脚手架"
    Write-Host "新建项目脚手架：缺少 pom.xml 或主程序.java" -ForegroundColor Red
}

# 8) 可选：原生补丁回归（基于 JDK 25 的 Tokens.java）
if ($含原生) {
    运行 "原生补丁编译" {
        & "$root\工具\原生补丁\编译.ps1"
    }
    $原生输出 = Join-Path $root "构建\M7校验\原生教程"
    if (Test-Path $原生输出) {
        Remove-Item -Recurse -Force $原生输出
    }
    New-Item -ItemType Directory -Force $原生输出 | Out-Null
    运行 "中文javac 编译教程" {
        & "$root\工具\原生补丁\中文javac.ps1" -encoding UTF-8 -d $原生输出 (Get-ChildItem "$root\教程\*.java" | ForEach-Object { $_.FullName })
    }
}

Write-Host ""
if ($失败.Count -eq 0) {
    Write-Host "全部校验通过。" -ForegroundColor Green
    exit 0
}
Write-Host "校验失败项：$($失败 -join '；')" -ForegroundColor Red
exit 1
