# 同步词表：从 关键字表.txt + 近义词典.txt 生成原生补丁别名块、VSCode 数据与高亮正则。
#
# 用法：
#   .\工具\cjc\同步词表.ps1          # 生成/更新三个消费方
#   .\工具\cjc\同步词表.ps1 -检查    # 只比对，不写文件；不一致时退出码 1
#
# 生成物：
#   工具\原生补丁\源码\com\sun\tools\javac\parser\Tokens.java  CJC_KEYWORDS 块
#   工具\vscode-中文java\关键字数据.json
#   工具\vscode-中文java\补全数据.json
#   工具\vscode-中文java\syntaxes\中文java.tmLanguage.json

param(
    [switch]$检查
)

$ErrorActionPreference = 'Stop'
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tablePath = Join-Path $PSScriptRoot '关键字表.txt'
$dictPath = Join-Path $PSScriptRoot '近义词典.txt'
$tokensPath = Join-Path $root '工具\原生补丁\源码\com\sun\tools\javac\parser\Tokens.java'
$dataPath = Join-Path $root '工具\vscode-中文java\关键字数据.json'
$completionPath = Join-Path $root '工具\vscode-中文java\补全数据.json'
$sugarPath = Join-Path $PSScriptRoot '方法糖.txt'
$tmPath = Join-Path $root '工具\vscode-中文java\syntaxes\中文java.tmLanguage.json'

Import-Module (Join-Path $PSScriptRoot '词表.psm1') -Force

# ---- 1. 读近义词典：组名 → 近义词 ----
try {
    $词典 = 读取近义词典 $dictPath
} catch {
    Write-Error $_.Exception.Message
    exit 2
}
$groups = $词典.Groups

# ---- 2. 读关键字表：组名展开为「规范名 + 近义词」 ----
try {
    $表 = 读取关键字表 $tablePath $groups
} catch {
    Write-Error $_.Exception.Message
    exit 2
}
$wordEn = $表.WordEn
$pairs = $表.Pairs
$canonicalByEn = $表.CanonicalByEn

# ---- 2.5 说明文案与类型别名 ----
# 原生补丁只接受 Java 关键字，因此 $descByEn 的键同时充当「可写入补丁」的英文白名单；
# 类型别名（String/Object 等，见 关键字表.txt 的「类型别名」一节）只进 VSCode 数据与高亮。
$descByEn = @{
    'class' = '声明类'; 'interface' = '声明接口'; 'enum' = '声明枚举'; 'package' = '声明包'
    'record' = '声明记录（不可变数据类，JDK 16+）'
    'sealed' = '密封类或密封接口（JDK 17+），只允许许可的子类型'
    'permits' = '列出密封类型的许可子类型（JDK 17+）'
    'non-sealed' = '解除密封，允许任意子类继承（JDK 17+）'
    'yield' = 'switch 表达式的分支返回值（JDK 14+）'
    'import' = '导入类型或包'; 'new' = '创建对象或数组'; 'extends' = '继承父类'; 'implements' = '实现接口'
    'this' = '当前对象引用'; 'super' = '父类引用'; 'public' = '公开访问'; 'private' = '私有访问'
    'protected' = '受保护访问'; 'static' = '静态成员'; 'final' = '不可变、不可继承或不可重写'
    'abstract' = '抽象类或抽象方法'; 'synchronized' = '同步方法或同步代码块'; 'native' = '本地方法'
    'volatile' = '易失字段（线程可见性）'; 'transient' = '序列化时忽略该字段'
    'strictfp' = '严格浮点运算（JDK 17+ 已默认严格）'; 'if' = '条件分支'; 'else' = '否则分支'
    'for' = '计数循环'; 'while' = '条件循环'; 'do' = 'do-while 循环'; 'switch' = '分支选择'
    'case' = '分支情形'; 'default' = '默认分支'; 'break' = '跳出循环或分支'; 'continue' = '继续下一轮循环'
    'return' = '返回结果'; 'instanceof' = '类型判断'; 'try' = '尝试执行'; 'catch' = '捕获异常'
    'throw' = '抛出异常'; 'throws' = '声明方法可能抛出的异常'; 'finally' = '最终执行的代码块'
    'assert' = '断言检查（需 -ea 生效）'; 'int' = '32 位整数'; 'long' = '64 位整数'; 'short' = '16 位整数'
    'byte' = '8 位整数'; 'float' = '单精度浮点'; 'double' = '双精度浮点'; 'char' = '字符'
    'boolean' = '布尔值'; 'void' = '无返回值'; 'null' = '空引用'; 'true' = '真值'; 'false' = '假值'
}
$typeAliasDesc = @{
    'String' = '字符串类型（java.lang.String）'
    'Object' = '任意对象类型（java.lang.Object）'
    'Integer' = '整数包装类（java.lang.Integer），用于泛型等引用场景'
    'Long' = '长整数包装类（java.lang.Long）'
    'Short' = '短整数包装类（java.lang.Short）'
    'Byte' = '字节包装类（java.lang.Byte）'
    'Float' = '浮点包装类（java.lang.Float）'
    'Double' = '双浮点包装类（java.lang.Double）'
    'Boolean' = '布尔包装类（java.lang.Boolean）'
    'Character' = '字符包装类（java.lang.Character）'
    'List' = '列表接口（java.util.List）'
    'ArrayList' = '数组实现列表（java.util.ArrayList）'
    'Map' = '键值表接口（java.util.Map）'
    'LinkedHashMap' = '保持插入顺序的键值表（java.util.LinkedHashMap）'
    'Set' = '去重集合接口（java.util.Set）'
    'HashSet' = '散列去重集合（java.util.HashSet）'
    'HashMap' = '散列键值表（java.util.HashMap）'
    'StringBuilder' = '可变文本构建器（java.lang.StringBuilder）'
    'Path' = '文件路径（java.nio.file.Path）'
    'Scanner' = '控制台输入读取器（java.util.Scanner）'
    'Comparable' = '可比较接口（java.lang.Comparable）'
    'Optional' = '可选值容器（java.util.Optional）'
    'Override' = '覆写父类方法的注解（java.lang.Override）'
    'ExecutorService' = '线程池（java.util.concurrent.ExecutorService）'
    'Future' = '异步结果（java.util.concurrent.Future）'
    'Logger' = '日志记录器（java.util.logging.Logger）'
    'IOException' = '输入输出异常（java.io.IOException）'
    'InterruptedException' = '线程中断异常（java.lang.InterruptedException）'
    'ExecutionException' = '异步执行异常（java.util.concurrent.ExecutionException）'
    'IllegalArgumentException' = '非法参数异常（java.lang.IllegalArgumentException）'
    'NumberFormatException' = '数字格式异常（java.lang.NumberFormatException）'
    'NullPointerException' = '空指针异常（java.lang.NullPointerException）'
    'ArrayIndexOutOfBoundsException' = '数组越界异常（java.lang.ArrayIndexOutOfBoundsException）'
    'ArithmeticException' = '算术异常（java.lang.ArithmeticException）'
}

$aliasEnglish = @($typeAliasDesc.Keys)
# record / sealed / permits / non-sealed / yield 是 Java 上下文关键字（javac 的 TokenKind 里没有），
# 只进编辑器词表，不写入原生补丁。
$contextualEnglish = @('record', 'sealed', 'permits', 'non-sealed', 'yield')
# PowerShell 的 @{} 查询键时忽略大小写，故用 -ccontains 区分 'Long' 与 'long'
$keywordPairs = @($pairs | Where-Object { $descByEn.ContainsKey($_.English) -and -not ($aliasEnglish -ccontains $_.English) -and -not ($contextualEnglish -ccontains $_.English) })
$aliasPairs = @($pairs | Where-Object { $aliasEnglish -ccontains $_.English })
$keywordEnglishCount = @($keywordPairs | ForEach-Object { $_.English } | Sort-Object -Unique).Count

# ---- 3. 生成原生补丁别名块 ----
$nl = "`r`n"
$tokensText = Read-Utf8 $tokensPath
if ($tokensText -notmatch "`r`n") {
    $nl = "`n"
}
$markerStart = '            // >>> 自动生成：CJC_KEYWORDS（来源 工具\cjc\关键字表.txt + 近义词典.txt，勿手改）'
$markerEnd = '            // <<< 自动生成：CJC_KEYWORDS'
$entryLines = for ($i = 0; $i -lt $keywordPairs.Count; $i++) {
    $comma = if ($i -lt $keywordPairs.Count - 1) { ',' } else { '' }
    "            Map.entry(`"$($keywordPairs[$i].Word)`", `"$($keywordPairs[$i].English)`")$comma"
}
$startIdx = $tokensText.IndexOf($markerStart)
$endIdx = if ($startIdx -ge 0) { $tokensText.IndexOf($markerEnd, $startIdx) } else { -1 }
if ($startIdx -lt 0 -or $endIdx -lt 0) {
    Write-Error "Tokens.java 缺少自动生成标记（$markerStart）"
    exit 2
}
$tokensNew = $tokensText.Substring(0, $startIdx) + $markerStart + $nl +
        ($entryLines -join $nl) + $nl + $markerEnd +
        $tokensText.Substring($endIdx + $markerEnd.Length)

# ---- 4. 生成 VSCode 数据 JSON ----
$data = [ordered]@{}
foreach ($pair in $pairs) {
    $base = if ($aliasEnglish -ccontains $pair.English) { $typeAliasDesc[$pair.English] } else { $descByEn[$pair.English] }
    if ($pair.Word -eq $pair.Canonical) {
        $说明 = $base
    } else {
        $说明 = "「$($pair.Canonical)」的同义词，$base"
    }
    $data[$pair.Word] = @($pair.English, $说明)
}
$dataNew = ($data | ConvertTo-Json -Depth 5) + $nl

# ---- 4.5 生成补全数据：类型别名 + 方法糖 + 标准库类名 ----
$糖 = @{ 成员 = @(); 声明 = @(); 静态 = @(); 白名单 = @() }
$糖区 = $null
foreach ($糖行 in ((Read-Utf8 $sugarPath) -split "`r?`n")) {
    $糖内容 = $糖行.Trim()
    if ($糖内容 -eq '' -or $糖内容.StartsWith('#')) {
        continue
    }
    if ($糖内容 -match '^\[(.+)\]$') {
        $糖区 = $Matches[1]
        continue
    }
    if ($null -eq $糖区 -or -not $糖.ContainsKey($糖区)) {
        continue
    }
    $糖[$糖区] += $糖内容
}

$补全 = [ordered]@{
    类型别名 = [ordered]@{}
    方法糖 = [ordered]@{ 成员 = [ordered]@{}; 声明 = [ordered]@{}; 静态 = [ordered]@{} }
    标准库 = [ordered]@{}
}
foreach ($pair in $aliasPairs) {
    $补全.类型别名[$pair.Word] = @($pair.English, $typeAliasDesc[$pair.English])
}
foreach ($项 in $糖['成员']) {
    $部分 = $项 -split '=', 2
    if ($部分.Count -eq 2) {
        $补全.方法糖.成员[$部分[0].Trim()] = @($部分[1].Trim(), '成员方法糖：名称.中文(...) → .英文(...)')
    }
}
foreach ($项 in $糖['声明']) {
    $部分 = $项 -split '=', 2
    if ($部分.Count -eq 2) {
        $补全.方法糖.声明[$部分[0].Trim()] = @($部分[1].Trim(), '重写方法声明糖')
    }
}
foreach ($项 in $糖['静态']) {
    $部分 = $项 -split '=', 2
    if ($部分.Count -eq 2) {
        $补全.方法糖.静态[$部分[0].Trim()] = @($部分[1].Trim(), '静态调用糖')
    }
}
foreach ($类名 in $糖['白名单']) {
    $补全.标准库[$类名.Trim()] = '标准库类名（先 导入 中文.标准库.*）'
}
$completionNew = ($补全 | ConvertTo-Json -Depth 5) + $nl

# ---- 5. 生成高亮正则文件 ----
$sortedWords = @($pairs | ForEach-Object { $_.Word } | Sort-Object @{ Expression = { $_.Length }; Descending = $true }, @{ Expression = { $_ }; Descending = $false })
$regex = '(?<!\p{L})(' + ($sortedWords -join '|') + ')(?!\p{L})'
$jsonRegex = $regex.Replace('\', '\\')
$tmNew = @"
{
  "scopeName": "source.chinese-java.injection",
  "injectionSelector": "L:source.java -comment -string",
  "patterns": [
    {
      "include": "#chinese-keywords"
    },
    {
      "include": "#chinese-punctuation"
    }
  ],
  "repository": {
    "chinese-keywords": {
      "match": "$jsonRegex",
      "name": "keyword.other.chinese-java"
    },
    "chinese-punctuation": {
      "match": "[（）；，｛｝＝：．＋－＊／＜＞]",
      "name": "punctuation.separator.chinese-java"
    }
  }
}
"@
$tmNew = $tmNew -replace "`r?`n", $nl

# ---- 6. 写入或比对 ----
$targets = @(
    @{ Path = $tokensPath; Text = $tokensNew },
    @{ Path = $dataPath; Text = $dataNew },
    @{ Path = $completionPath; Text = $completionNew },
    @{ Path = $tmPath; Text = $tmNew }
)

if ($检查) {
    $dirty = $false
    foreach ($target in $targets) {
        $current = if (Test-Path $target.Path) { Read-Utf8 $target.Path } else { '' }
        if ($current -ne $target.Text) {
            Write-Host "不一致：$($target.Path)"
            $dirty = $true
        }
    }
    if ($dirty) {
        Write-Host "词表未同步（关键字 $($keywordPairs.Count) 条 + 类型别名 $($aliasPairs.Count) 条），请运行 工具\cjc\同步词表.ps1"
        exit 1
    }
    Write-Host "词表一致：关键字 $($keywordPairs.Count) 条 + 类型别名 $($aliasPairs.Count) 条（$keywordEnglishCount 个英文关键字，词典 $($groups.Count) 组）"
    exit 0
}

foreach ($target in $targets) {
    Write-Utf8 $target.Path $target.Text
    Write-Host "已更新：$($target.Path)"
}
Write-Host "同步完成：关键字 $($keywordPairs.Count) 条 + 类型别名 $($aliasPairs.Count) 条（$keywordEnglishCount 个英文关键字，词典 $($groups.Count) 组）"
