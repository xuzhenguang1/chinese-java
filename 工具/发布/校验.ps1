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

function 失败项([string]$名称, [string]$说明, [string]$详情 = "") {
    $script:失败 += $名称
    Write-Host "$名称：$说明" -ForegroundColor Red
    if ($详情) {
        Write-Host $详情
    }
}

function 新建临时目录([string]$名称) {
    $目录 = Join-Path $root "构建\$名称"
    if (Test-Path $目录) {
        Remove-Item -Recurse -Force $目录
    }
    New-Item -ItemType Directory -Force $目录 | Out-Null
    return $目录
}

function 捕获输出([scriptblock]$动作) {
    # 原生命令会把 JVM 警告写到 stderr，不应被当作失败；输出合并成文本供断言
    $偏好 = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $动作 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $偏好
    }
}

# 0) 版本单一源：工具\版本.json 与各文件中的版本号必须一致
运行 "版本一致性检查" {
    & "$root\工具\发布\同步版本.ps1"
}

# 1) 词表单源同步校验（关键字表 + 近义词典 → 原生补丁 / VSCode 数据 / 高亮正则）
运行 "词表同步检查" {
    & "$root\工具\cjc\同步词表.ps1" -检查
}

# 2) 关键字表加载校验 + 中文源码空格/标点检查
运行 "cjc 检查（教程 / 示例 / 标准库）" {
    & "$root\cjc.ps1" 检查 "$root\教程" "$root\示例" "$root\标准库\src" "--表" "$root\工具\cjc\关键字表.txt"
}

# 2.5) 关键字表表头版本必须与 工具\版本.json 的「语言」一致，避免文档与本表长期漂移
$tableHeader = (Get-Content "$root\工具\cjc\关键字表.txt" -TotalCount 1 -Encoding UTF8).Trim()
$版本单一源 = Get-Content "$root\工具\版本.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$语言版本 = [string]$版本单一源.语言
if ($tableHeader -notmatch ("v" + [regex]::Escape($语言版本))) {
    Write-Error "关键字表表头版本与发布不一致：$tableHeader（应为 v$语言版本）"
    exit 2
}
Write-Host "关键字表版本：$tableHeader"

# 2.5.1) 词表计数必须与关键字表表头、README、AGENTS 一致，避免文档漂移
$词表输出 = & "$root\cjc.ps1" 检查 "$root\示例" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Error "词表计数检查无法运行 cjc：$词表输出"
    exit 2
}
$计数匹配 = [regex]::Match($词表输出, '关键字 (\d+) 条 \+ 类型别名 (\d+) 条，词典 (\d+) 组')
$表头匹配 = [regex]::Match($tableHeader, '(\d+) 个英文关键字 / (\d+) 个中文词 / (\d+) 个类型别名')
if (-not $计数匹配.Success -or -not $表头匹配.Success) {
    Write-Error "无法解析词表计数：表头=$tableHeader 输出=$词表输出"
    exit 2
}
$中文词 = [int]$计数匹配.Groups[1].Value
$类型别名 = [int]$计数匹配.Groups[2].Value
$词典组 = [int]$计数匹配.Groups[3].Value
$表头英文 = [int]$表头匹配.Groups[1].Value
$表头中文 = [int]$表头匹配.Groups[2].Value
$表头别名 = [int]$表头匹配.Groups[3].Value
$表行 = Get-Content "$root\工具\cjc\关键字表.txt" -Encoding UTF8
$别名起 = [array]::IndexOf($表行, '# 类型别名（java.lang 常用类型与基本类型的包装类，不是 Java 关键字）')
$别名止 = [array]::IndexOf($表行, '# 类型与字面量')
if ($别名起 -lt 0 -or $别名止 -lt 0) {
    Write-Error "无法定位关键字表的类型别名区段"
    exit 2
}
$英文集合 = New-Object 'System.Collections.Generic.HashSet[string]'
for ($i = 0; $i -lt $表行.Length; $i++) {
    if ($i -ge $别名起 -and $i -lt $别名止) { continue }
    # 英文=[中文] 列表写法：英文在左
    if ($表行[$i] -match '^([A-Za-z][A-Za-z0-9_-]*)\s*=\s*\[') { [void]$英文集合.Add($Matches[1]); continue }
    # 中文=英文 单别名写法（record / sealed / permits / non-sealed / yield）：英文在右
    if ($表行[$i] -match '^[^=]+\s*=\s*([A-Za-z][A-Za-z0-9_-]*)\s*$') { [void]$英文集合.Add($Matches[1]) }
}
$英文词 = $英文集合.Count
$readme = Get-Content "$root\README.md" -Raw -Encoding UTF8
$agents = Get-Content "$root\AGENTS.md" -Raw -Encoding UTF8
if ($表头英文 -ne $英文词 -or $表头中文 -ne $中文词 -or $表头别名 -ne $类型别名) {
    Write-Error "关键字表表头与实际不一致：表头 $表头英文/$表头中文/$表头别名，实际 $英文词/$中文词/$类型别名"
    exit 2
}
if ($readme -notmatch "$英文词 个英文关键字" -or $readme -notmatch "$中文词 个中文词" -or $readme -notmatch "$类型别名 个类型别名") {
    Write-Error "README 词表计数与关键字表不一致（应为 $英文词 / $中文词 / $类型别名）"
    exit 2
}
if ($agents -notmatch "$中文词 个中文词" -or $agents -notmatch "$类型别名 个类型别名") {
    Write-Error "AGENTS 词表计数与关键字表不一致（应为 $中文词 / $类型别名）"
    exit 2
}
# 教程与语言规范：词数/组数同样只能来自词表，不能再手写一份
$同步输出 = & "$root\工具\cjc\同步词表.ps1" -检查 2>&1 6>&1 | Out-String
$同步匹配 = [regex]::Match($同步输出, '关键字 (\d+) 条 \+ 类型别名 (\d+) 条')
if (-not $同步匹配.Success) {
    Write-Error "无法解析同步词表计数：$同步输出"
    exit 2
}
$词数 = [int]$同步匹配.Groups[1].Value
$教程文档 = Get-Content "$root\教程\教程.md" -Raw -Encoding UTF8
$规范文档 = Get-Content "$root\语言规范.md" -Raw -Encoding UTF8
if ($教程文档 -notmatch "$词典组 组、$词数 词" -or $教程文档 -notmatch "$中文词 个中文词") {
    Write-Error "教程词表计数与词表不一致（应为 $词典组 组、$词数 词 / $中文词 个中文词）"
    exit 2
}
if ($规范文档 -notmatch "$词典组 组、$词数 词") {
    Write-Error "语言规范词表计数与词表不一致（应为 $词典组 组、$词数 词）"
    exit 2
}
Write-Host "词表计数：$英文词 个英文关键字 / $中文词 个中文词 / $类型别名 个类型别名 / 词典 $词典组 组 / $词数 词" -ForegroundColor Green

# 2.5.2) 方法糖表单一源：Cjc 不再内置默认表，文件必须存在且四区非空，白名单恰为 10 个
$糖表 = @{ 成员 = @(); 声明 = @(); 静态 = @(); 白名单 = @() }
$糖分区 = $null
$糖行号 = 0
foreach ($糖行 in (Get-Content "$root\工具\cjc\方法糖.txt" -Encoding UTF8)) {
    $糖行号++
    $糖行修剪 = $糖行.Trim()
    if ($糖行修剪 -eq '' -or $糖行修剪.StartsWith('#')) { continue }
    if ($糖行修剪 -match '^\[(.+)\]$') {
        $糖分区 = $Matches[1]
        if (-not $糖表.ContainsKey($糖分区)) {
            Write-Error "方法糖表第 $糖行号 行未知分区：[$糖分区]（只支持 [成员] / [声明] / [静态] / [白名单]）"
            exit 2
        }
        continue
    }
    if ($null -eq $糖分区) {
        Write-Error "方法糖表第 $糖行号 行不属于任何分区：$糖行修剪"
        exit 2
    }
    $糖表[$糖分区] += $糖行修剪
}
foreach ($糖必需 in @('成员', '声明', '静态', '白名单')) {
    if ($糖表[$糖必需].Count -eq 0) {
        Write-Error "方法糖表分区 [$糖必需] 为空或缺失"
        exit 2
    }
}
$cjc源码 = Get-Content "$root\工具\cjc\Cjc.java" -Raw -Encoding UTF8
foreach ($糖遗留 in @('MEMBER_CALLS.put(', 'MEMBER_DECLS.put(', 'STATIC_CALLS.put(')) {
    if ($cjc源码.Contains($糖遗留)) {
        Write-Error "Cjc.java 仍有内置方法糖初始化：$糖遗留（应全部改由 方法糖.txt 提供）"
        exit 2
    }
}
foreach ($糖条目 in ($糖表['成员'] + $糖表['声明'])) {
    $糖英文 = ($糖条目 -split '=', 2)[1]
    if ($糖英文 -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*$') {
        Write-Error "方法糖表英文不是合法标识符：$糖条目"
        exit 2
    }
}
foreach ($糖条目 in $糖表['静态']) {
    $糖英文 = ($糖条目 -split '=', 2)[1]
    if ($糖英文 -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$') {
        Write-Error "方法糖表静态英文格式不正确：$糖条目"
        exit 2
    }
}
if ($糖表['白名单'].Count -ne 10) {
    Write-Error "方法糖表白名单应为 10 个标准库类名，实际 $($糖表['白名单'].Count) 个"
    exit 2
}
Write-Host "方法糖表：成员 $($糖表['成员'].Count) / 声明 $($糖表['声明'].Count) / 静态 $($糖表['静态'].Count) / 白名单 $($糖表['白名单'].Count)" -ForegroundColor Green

# 2.6) 标准库类名白名单：`数值.转文本` / `文本.去空白` 不得被成员糖改写成英文方法名
$白名单目录 = 新建临时目录 "白名单检查"
$白名单源码 = Join-Path $白名单目录 "白名单检查.java"
@'
导入 中文.标准库.*;

类 白名单检查 {
    入口 {
        打印行(数值.转文本(42));
        打印行(文本.去空白("  甲  "));
    }
}
'@ | Set-Content -Path $白名单源码 -Encoding UTF8
运行 "标准库类名白名单：转译" {
    & "$root\cjc.ps1" 转译 $白名单源码 -d (Join-Path $白名单目录 "输出")
}
$译后 = Get-Content (Join-Path $白名单目录 "输出\白名单检查.java") -Raw -Encoding UTF8
if ($译后 -match '数值\.转文本' -and $译后 -match '文本\.去空白') {
    Write-Host "标准库类名白名单：通过" -ForegroundColor Green
} else {
    失败项 "标准库类名白名单" "标准库类名后的中文方法被成员糖改写"
}

# 2.7) 简化糖：增强 for 省类型、集合工厂（清单.从/集.从/键值表.从）、Stream 成员糖与收集器
$简化输出 = & "$root\工具\对话\运行.ps1" "$root\教程\第32课集合与流.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $简化输出.Contains("姓张的有 2 人") -and $简化输出.Contains("大写：张三、李四、张小明")) {
    Write-Host "简化糖（增强 for / 集合工厂 / Stream）：通过" -ForegroundColor Green
} else {
    失败项 "简化糖" "示例输出不符合预期" $简化输出
}

# 2.8) 计数循环糖：`重复 N 次 { … }` 与 `重复 N 次 (名) { … }`
$重复输出 = & "$root\工具\对话\运行.ps1" "$root\教程\第31课重复循环.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $重复输出.Contains("第3次") -and $重复输出.Contains("处理第3份")) {
    Write-Host "计数循环（重复 N 次）：通过" -ForegroundColor Green
} else {
    失败项 "计数循环" "示例输出不符合预期" $重复输出
}

# 2.9) 新手易错提示：CJK101–CJK105
$提示目录 = 新建临时目录 "新手提示检查"
@'
字符串 甲 = "甲";
字符串 乙 = "甲";
若 (甲 == 乙) { 打印行("相等"); }
清单<字符串> 名单 = 清单.从("甲");
名单.整理();
扫描器 输入 = 新建 扫描器(系统.标准输入);
输入.读整数();
字符串 行 = 输入.读行();
对于 (整数 序号 = 0; 序号 <= 名单.元素数(); 序号++) { 打印行(名单.取出(序号)); }
整数 平均 = 5 / 2;
'@ | Set-Content -Path (Join-Path $提示目录 "新手提示.java") -Encoding UTF8
$提示输出 = 捕获输出 { & "$root\cjc.ps1" 检查 $提示目录 }
if ($提示输出 -match 'CJK101' -and $提示输出 -match 'CJK102' -and $提示输出 -match 'CJK103' -and $提示输出 -match 'CJK104' -and $提示输出 -match 'CJK105') {
    Write-Host "新手易错提示（CJK101–105）：通过" -ForegroundColor Green
} else {
    失败项 "新手易错提示" "缺少 CJK101–CJK105 之一" $提示输出
}

# 2.10) 语法糖 `否则若 (` → `else if (`
$否则若目录 = 新建临时目录 "否则若检查"
@'
整数 分数 = 75;
若 (分数 >= 90) {
    打印行("优秀");
} 否则若 (分数 >= 60) {
    打印行("中等");
} 否则 {
    打印行("待努力");
}
'@ | Set-Content -Path (Join-Path $否则若目录 "否则若检查.java") -Encoding UTF8
$否则若输出 = & "$root\工具\对话\运行.ps1" (Join-Path $否则若目录 "否则若检查.java") 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $否则若输出.Contains("中等")) {
    Write-Host "语法糖（否则若）：通过" -ForegroundColor Green
} else {
    失败项 "语法糖否则若" "运行失败或输出不符合预期" $否则若输出
}

# 2.11) 泛型实参自动包装：清单<整数> / 键值表<字符串, 双浮点> / 嵌套泛型
$泛型输出 = & "$root\工具\对话\运行.ps1" "$root\示例\泛型基本类型.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $泛型输出.Contains("矩阵首行首列：1") -and $泛型输出.Contains("苹果单价：5.5")) {
    Write-Host "泛型实参自动包装：通过" -ForegroundColor Green
} else {
    失败项 "泛型实参自动包装" "运行失败或输出不符合预期" $泛型输出
}

# 2.12) `记录`（record）：自动 转文本 与访问器
$记录输出 = & "$root\工具\对话\运行.ps1" "$root\教程\第33课记录.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $记录输出.Contains("学生[姓名=小明, 年龄=18]") -and $记录输出.Contains("小明，18 岁")) {
    Write-Host "记录（record）：通过" -ForegroundColor Green
} else {
    失败项 "记录（record）" "运行失败或输出不符合预期" $记录输出
}

# 2.12b) `密封` / `许可` / `非密封` 与 `产出` / 切换表达式
$密封输出 = & "$root\工具\对话\运行.ps1" "$root\教程\第34课密封类.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $密封输出.Contains("三角形面积：10.0")) {
    Write-Host "密封类与许可：通过" -ForegroundColor Green
} else {
    失败项 "密封类与许可" "运行失败或输出不符合预期" $密封输出
}
$产出输出 = & "$root\工具\对话\运行.ps1" "$root\教程\第35课产出.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $产出输出.Contains("普通会员无折扣") -and $产出输出.Contains("金 会员折扣：0.8")) {
    Write-Host "切换表达式与产出：通过" -ForegroundColor Green
} else {
    失败项 "切换表达式与产出" "运行失败或输出不符合预期" $产出输出
}

# 2.13) `检查 --含编译`：正例通过；反例（类型错误）必须退出码 1
运行 "cjc 检查 --含编译（教程）" {
    & "$root\cjc.ps1" 检查 --含编译 "$root\教程"
}
$编译反例目录 = 新建临时目录 "含编译反例"
@'
类 含编译反例 {
    入口 {
        整数 甲 = "文本";
        打印行(甲);
    }
}
'@ | Set-Content -Path (Join-Path $编译反例目录 "含编译反例.java") -Encoding UTF8
$编译反例输出 = 捕获输出 { & "$root\cjc.ps1" 检查 --含编译 $编译反例目录 }
$编译反例码 = $LASTEXITCODE
if ($编译反例码 -eq 1 -and $编译反例输出 -match '类型不匹配' -and $编译反例输出 -match '第3行') {
    Write-Host "检查 --含编译 反例：通过" -ForegroundColor Green
} else {
    失败项 "检查 --含编译 反例" "退出码或诊断不符合预期（期望 1 / 类型不匹配）" $编译反例输出
}

# 2.14) `数组清单.从(...)`：一行建可变清单
$可变清单输出 = & "$root\工具\对话\运行.ps1" "$root\示例\可变清单工厂.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $可变清单输出.Contains("元素数 3：[甲, 乙, 丙]") -and $可变清单输出.Contains("嵌套 2 个：[甲, 戊]")) {
    Write-Host "数组清单.从（可变清单）：通过" -ForegroundColor Green
} else {
    失败项 "数组清单.从" "运行失败或输出不符合预期" $可变清单输出
}

# 2.15) CJK106：`。` / `、` 自动纠正（退出码 0）；`--严格标点` 与弯引号仍按 CJK002 阻断
$标点折叠输出 = & "$root\工具\对话\运行.ps1" "$root\示例\标点折叠.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $标点折叠输出.Contains("他说。好") -and $标点折叠输出.Contains("CJK106")) {
    Write-Host "标点折叠（CJK106）：通过" -ForegroundColor Green
} else {
    失败项 "标点折叠" "运行失败、输出缺失或未提示 CJK106" $标点折叠输出
}
$标点反例目录 = 新建临时目录 "标点折叠反例"
@'
打印行("你好")。
'@ | Set-Content -Path (Join-Path $标点反例目录 "严格标点.java") -Encoding UTF8
@'
打印行(“引号”)。
'@ | Set-Content -Path (Join-Path $标点反例目录 "弯引号.java") -Encoding UTF8
$严格输出 = 捕获输出 { & "$root\cjc.ps1" 检查 --严格标点 (Join-Path $标点反例目录 "严格标点.java") }
$严格码 = $LASTEXITCODE
$引号输出 = 捕获输出 { & "$root\cjc.ps1" 检查 (Join-Path $标点反例目录 "弯引号.java") }
$引号码 = $LASTEXITCODE
if ($严格码 -eq 1 -and $严格输出 -match 'CJK002') {
    Write-Host "严格标点反例（CJK002）：通过" -ForegroundColor Green
} else {
    失败项 "严格标点反例" "退出码或诊断不符合预期（期望 1 / CJK002）" $严格输出
}
if ($引号码 -eq 1 -and $引号输出 -match 'CJK002') {
    Write-Host "弯引号反例（CJK002）：通过" -ForegroundColor Green
} else {
    失败项 "弯引号反例" "退出码或诊断不符合预期（期望 1 / CJK002）" $引号输出
}

# 2.16) `打印行` / `打印` 多参数自动拼接
$打印输出 = & "$root\工具\对话\运行.ps1" "$root\示例\打印多参数.java" 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $打印输出.Contains("姓名：小明，年龄：18") -and $打印输出.Contains("7 -> 4") -and $打印输出.Contains("同一行拼接")) {
    Write-Host "打印多参数：通过" -ForegroundColor Green
} else {
    失败项 "打印多参数" "运行失败或输出不符合预期" $打印输出
}

# 2.13) 诊断中文化固定反例：13 条覆盖常见编译错误，逐条断言中文输出
$诊断目录 = 新建临时目录 "诊断反例检查"
$诊断反例 = @(
    @{ 名 = '01未定义变量'; 期望 = '找不到符号：变量'; 源码 = @'
类 测试 {
    入口 {
        打印行(未定义的名字);
    }
}
'@ },
    @{ 名 = '02缺分号'; 期望 = '缺少「;」'; 源码 = @'
类 测试 {
    入口 {
        整数 甲 = 1
        打印行(甲);
    }
}
'@ },
    @{ 名 = '03缺返回'; 期望 = '方法缺少 return'; 源码 = @'
类 取数器 {
    公有 静态 整数 取() {
        整数 乙 = 1;
    }
}
'@ },
    @{ 名 = '04泛型不匹配'; 期望 = '类型不匹配：「整数」不能赋给「字符串」'; 源码 = @'
导入 中文.标准库.*;
类 测试 {
    入口 {
        清单<字符串> 名单 = 新建 数组清单<>();
        名单.加入(123);
    }
}
'@ },
    @{ 名 = '05访问私有'; 期望 = '「值」是私有的'; 源码 = @'
类 甲 {
    私有 整数 值 = 1;
}
类 测试 {
    入口 {
        甲 实例 = 新建 甲();
        打印行(实例.值);
    }
}
'@ },
    @{ 名 = '06构造器参数'; 期望 = '构造器 人应用到给定类型'; 源码 = @'
类 人 {
    公有 人(整数 岁) { }
}
类 测试 {
    入口 {
        人 甲 = 新建 人(18, 20);
    }
}
'@ },
    @{ 名 = '07抽象未实现'; 期望 = '抽象方法叫'; 源码 = @'
抽象 类 动物 {
    公有 抽象 无 叫();
}
类 猫 继承 动物 {
}
'@ },
    @{ 名 = '09非法字符'; 期望 = '出现了非法字符「#」'; 源码 = @'
类 测试 {
    入口 {
        打印行(1); #
    }
}
'@ },
    @{ 名 = '10包不存在'; 期望 = '包「别处.不存在」不存在'; 源码 = @'
导入 别处.不存在.*;
类 测试 {
    入口 {
    }
}
'@ },
    @{ 名 = '11不能解引用'; 期望 = '无法取消引用'; 源码 = @'
类 测试 {
    入口 {
        整数 甲 = 1;
        打印行(甲.字段);
    }
}
'@ },
    @{ 名 = '12断言'; 期望 = '检查通过'; 源码 = @'
类 测试 {
    入口 {
        断言 (1 == 2);
    }
}
'@ },
    @{ 名 = '13漏导入标准库'; 期望 = '导入 中文.标准库'; 源码 = @'
类 测试 {
    入口 {
        输出.打印行("你好");
    }
}
'@ }
)
$诊断失败 = 0
$诊断偏好 = $ErrorActionPreference
# cjc 的诊断走 stderr，PowerShell 会包装成 NativeCommandError；这里临时放行，靠退出码与文本断言判断
$ErrorActionPreference = 'Continue'
foreach ($例 in $诊断反例) {
    $路径 = Join-Path $诊断目录 ($例.名 + '.java')
    Set-Content -Path $路径 -Value $例.源码 -Encoding UTF8
    $输出 = & "$root\cjc.ps1" 检查 --含编译 $路径 2>&1 | Out-String
    if (-not $输出.Contains($例.期望)) {
        Write-Host "诊断反例未命中：$($例.名)（期望包含「$($例.期望)」）" -ForegroundColor Red
        $诊断失败++
    }
}
# 08 重复类：同一目录两个同名类
$重复目录 = Join-Path $诊断目录 '08重复类'
New-Item -ItemType Directory -Force $重复目录 | Out-Null
Set-Content -Path (Join-Path $重复目录 '甲.java') -Value "类 甲 {`n}" -Encoding UTF8
Set-Content -Path (Join-Path $重复目录 '乙.java') -Value "类 甲 {`n}" -Encoding UTF8
$重复输出 = & "$root\cjc.ps1" 检查 --含编译 $重复目录 2>&1 | Out-String
if (-not $重复输出.Contains('类重复')) {
    Write-Host "诊断反例未命中：08重复类（期望包含「类重复」）" -ForegroundColor Red
    $诊断失败++
}
# 14 缺标准库 jar：`编译` 不带 -cp 时必须直接提示 jar 与构建命令
$缺jar目录 = 新建临时目录 "缺标准库jar"
$缺jar文件 = Join-Path $缺jar目录 '显示.java'
Set-Content -Path $缺jar文件 -Value @'
导入 中文.标准库.*;

类 显示 {
    入口 {
        输出.打印行("你好");
    }
}
'@ -Encoding UTF8
$缺jar输出 = 捕获输出 { & "$root\cjc.ps1" 编译 $缺jar文件 -d (Join-Path $缺jar目录 'out') }
$缺jar码 = $LASTEXITCODE
if ($缺jar码 -ne 1 -or -not $缺jar输出.Contains('标准库 jar 不在类路径') -or -not $缺jar输出.Contains('标准库\pom.xml')) {
    Write-Host "诊断反例未命中：14缺标准库jar（期望退出码 1、提示 jar 与 pom.xml 构建命令）" -ForegroundColor Red
    $诊断失败++
}
$ErrorActionPreference = $诊断偏好
if ($诊断失败 -gt 0) {
    $失败 += "诊断中文化反例（$诊断失败 条未命中）"
} else {
    Write-Host "诊断反例：13 条 + 缺标准库 jar 提示全部命中中文输出" -ForegroundColor Green
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
    $模板输出 = 新建临时目录 "模板编译"
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
$反例输出 = 捕获输出 { & "$root\cjc.ps1" 检查 @反例 }
if ($反例输出 -match 'CJK001' -and $反例输出 -match 'CJK002' -and $反例输出 -match '建议：') {
    Write-Host "诊断建议字段：通过" -ForegroundColor Green
} else {
    失败项 "诊断建议字段" "缺少错误码或「建议：」"
}

# 7.5) 运行时异常中文化：故意越界，输出必须含中文异常名与「建议：」
$异常目录 = 新建临时目录 "异常校验"
$异常源码 = Join-Path $异常目录 "异常校验.java"
@'
打印行("越界演示");
整数[] 数组 = {1, 2, 3};
打印行("值：" + 数组[5]);
'@ | Set-Content -Path $异常源码 -Encoding UTF8
$异常输出 = 捕获输出 { & "$root\工具\对话\运行.ps1" $异常源码 }
if ($异常输出 -match '程序运行出错' -and $异常输出 -match '数组越界异常' -and $异常输出 -match '建议：') {
    Write-Host "运行时异常中文化：通过" -ForegroundColor Green
} else {
    失败项 "运行时异常中文化" "输出未中文化"
}

# 7.6) 新建项目脚手架：只校验生成的骨架文件（不跑 mvn，避免依赖本地仓库）
$脚手架 = Join-Path $root "构建\脚手架校验\示例工具"
if (Test-Path $脚手架) { Remove-Item -Recurse -Force $脚手架 }
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
    失败项 "新建项目脚手架" "缺少 pom.xml 或主程序.java"
}

# 8) 可选：原生实现 M0 检查 + 原生补丁回归（基于 JDK 25 的 Tokens.java）
if ($含原生) {
    # 8.0) 原生实现 M0：驱动可调用、与 cjc 共用同一份词表、ADR 齐备
    $原生检查输出 = 捕获输出 { & "$root\cjc.ps1" 原生 检查 "$root\教程" }
    $原生检查码 = $LASTEXITCODE
    if ($原生检查码 -eq 0 -and
        $原生检查输出 -match '关键字表：英文 56 / 中文 121 / 类型别名 34' -and
        $原生检查输出 -match '近义词典：50 组' -and
        $原生检查输出 -match '方法糖：成员 52 / 声明 3 / 静态 56 / 白名单 10' -and
        $原生检查输出 -match '诊断中文表：\d+ 条' -and
        $原生检查输出 -match '教程：35 个 \.java 文件') {
        Write-Host "原生 M0：驱动可调用，词表计数一致" -ForegroundColor Green
    } else {
        失败项 "原生 M0 驱动" "退出码、词表计数或教程统计不符合预期" $原生检查输出
    }
    $词表副本 = @(Get-ChildItem (Join-Path $root "原生") -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { @('关键字表.txt', '近义词典.txt', '方法糖.txt', '诊断中文表.txt') -contains $_.Name })
    if ($词表副本.Count -eq 0) {
        Write-Host "原生 M0：未发现词表副本（单一数据源）" -ForegroundColor Green
    } else {
        失败项 "原生 M0 词表副本" "原生目录下存在词表副本：$($词表副本.FullName -join '；')"
    }
    $adr文件 = @(Get-ChildItem (Join-Path $root "文档\原生\ADR") -Filter "ADR-*.md" -File -ErrorAction SilentlyContinue)
    if ($adr文件.Count -eq 8) {
        Write-Host "原生 M0：ADR 8 份齐备" -ForegroundColor Green
    } else {
        失败项 "原生 M0 ADR" "ADR 数量应为 8，实际 $($adr文件.Count)"
    }

    # 8.1) 原生 M1：35 课 Token/AST、黄金快照可重复、词法/语法/新手提示反例
    #      D1 口径：语法类只要求缺分号、非法字符命中；其余语义诊断延到 M2。
    $m1检查输出 = 捕获输出 { & "$root\cjc.ps1" 原生 检查 "$root\教程" }
    $m1检查码 = $LASTEXITCODE
    if ($m1检查码 -eq 0 -and $m1检查输出 -match '汇总：35/35 解析成功') {
        Write-Host "原生 M1：35/35 解析成功" -ForegroundColor Green
    } else {
        失败项 "原生 M1 解析" "退出码或汇总不符合预期" $m1检查输出
    }

    $快照基准 = Join-Path $root "原生\测试\语法树快照"
    $快照临时 = 新建临时目录 "M1快照校验"
    $快照生成输出 = 捕获输出 { & "$root\cjc.ps1" 原生 快照 $快照临时 "$root\教程" }
    $快照码 = $LASTEXITCODE
    $基准文件 = @(Get-ChildItem $快照基准 -Filter *.ast.txt -File -ErrorAction SilentlyContinue)
    $临时文件 = @(Get-ChildItem $快照临时 -Filter *.ast.txt -File)
    $快照差异 = @()
    if ($快照码 -eq 0 -and $基准文件.Count -eq 35 -and $临时文件.Count -eq 35) {
        $快照差异 = @(Compare-Object @($基准文件 | Get-Content -Raw) @($临时文件 | Get-Content -Raw))
    }
    if ($快照码 -eq 0 -and $基准文件.Count -eq 35 -and $临时文件.Count -eq 35 -and $快照差异.Count -eq 0) {
        Write-Host "原生 M1：AST 黄金快照 35 份可重复" -ForegroundColor Green
    } else {
        失败项 "原生 M1 快照" "基准 $($基准文件.Count) 份 / 重生成 $($临时文件.Count) 份 / 差异 $($快照差异.Count) 条" $快照生成输出
    }

    $m1反例目录 = 新建临时目录 "M1诊断反例"
    $m1反例 = @(
        @{ 名 = '01粘连'; 码 = 'CJK001'; 退出码 = 1; 源码 = "整数 结果 = 1;`n打印行(结果);`n返回结果;`n" },
        @{ 名 = '02缺分号'; 码 = '缺少「;」'; 退出码 = 1; 源码 = "整数 甲 = 1`n打印行(甲);`n" },
        @{ 名 = '03弯引号'; 码 = 'CJK002'; 退出码 = 1; 源码 = ('打印行(' + [char]0x201C + '你好' + [char]0x201D + ');' + "`n") },
        @{ 名 = '04句号'; 码 = 'CJK106'; 退出码 = 0; 源码 = "打印行(`"甲`")。`n" },
        @{ 名 = '05非法字符'; 码 = '出现了非法字符「#」'; 退出码 = 1; 源码 = "整数 甲 = 1;`n# 注释`n打印行(甲);`n" },
        @{ 名 = '06字符串等号'; 码 = 'CJK101'; 退出码 = 0; 源码 = "字符串 甲 = `"甲`";`n字符串 乙 = `"乙`";`n若 (甲 == 乙) {`n    打印行(`"同`");`n}`n" },
        @{ 名 = '07整理无参'; 码 = 'CJK102'; 退出码 = 0; 源码 = "清单<整数> 表 = 数组清单.从(1, 2);`n表.整理();`n" },
        @{ 名 = '08读整数后读行'; 码 = 'CJK103'; 退出码 = 0; 源码 = "扫描器 输入 = 新建 扫描器(系统.标准输入);`n输入.读整数();`n输入.读行();`n" },
        @{ 名 = '09循环上界'; 码 = 'CJK104'; 退出码 = 0; 源码 = "整数[] 数字 = {1, 2, 3};`n对于 (整数 序号 = 0; 序号 <= 数字.元素数(); 序号++) {`n    打印行(数字[序号]);`n}`n" },
        @{ 名 = '10整数相除'; 码 = 'CJK105'; 退出码 = 0; 源码 = "整数 甲 = 5;`n整数 乙 = 2;`n整数 丙 = 甲 / 乙;`n" }
    )
    $m1反例失败 = 0
    foreach ($例 in $m1反例) {
        $反例文件 = Join-Path $m1反例目录 ("$($例.名).java")
        Set-Content -Path $反例文件 -Value $例.源码 -Encoding UTF8 -NoNewline
        $反例输出 = 捕获输出 { & "$root\cjc.ps1" 原生 检查 $反例文件 }
        $反例码 = $LASTEXITCODE
        if ($反例码 -ne $例.退出码 -or $反例输出 -notmatch [regex]::Escape($例.码)) {
            $m1反例失败++
            Write-Host "原生 M1 反例未命中：$($例.名)（期望 $($例.码) / 退出码 $($例.退出码)，实际 $反例码）" -ForegroundColor Red
        }
    }
    if ($m1反例失败 -eq 0) {
        Write-Host "原生 M1：10 条词法/语法/新手提示反例全部命中" -ForegroundColor Green
    } else {
        失败项 "原生 M1 反例" "$m1反例失败 条未命中"
    }

    运行 "原生补丁编译" {
        & "$root\工具\原生补丁\编译.ps1"
    }
    $原生输出 = 新建临时目录 "M7校验\原生教程"
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
