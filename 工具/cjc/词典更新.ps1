# 词典自动更新：从近义词 API 拉取候选词，合并进 近义词典.txt，并自动同步三处产物。
#
# 用法（在工程根目录执行）：
#   .\工具\cjc\词典更新.ps1              # 按 词典源.json 更新全部组（只增不删）
#   .\工具\cjc\词典更新.ps1 -干跑        # 只打印将新增的近义词，不写文件
#   .\工具\cjc\词典更新.ps1 -组名 若     # 只更新指定组
#
# 配置：工具\cjc\词典源.json（默认本地词林，无需密钥）。
# 过滤：候选先经 词典源.py 的字数/歧义过滤，再跳过跨组重复、排除词与源码中已有的中文标识符。
# 安全：写入词典后自动运行 同步词表.ps1 并编译教程；任何一步失败即回滚词典与产物。

param(
    [string]$组名 = '',
    [switch]$干跑,
    [int]$超时 = 20
)

$ErrorActionPreference = 'Stop'
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dictPath = Join-Path $PSScriptRoot '近义词典.txt'
$configPath = Join-Path $PSScriptRoot '词典源.json'
$syncPath = Join-Path $PSScriptRoot '同步词表.ps1'

function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Write-Utf8([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Split-List([string]$Text, [string[]]$Seps) {
    $pattern = '[' + (($Seps | ForEach-Object { [regex]::Escape($_) }) -join '') + ']'
    return @($Text -split $pattern | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-ByPath($Object, [string]$Path) {
    $current = $Object
    foreach ($segment in ($Path -split '\.')) {
        if ($null -eq $current) { return $null }
        $current = $current.$segment
    }
    return $current
}

function Expand-Template([string]$Text, [string]$Word) {
    $value = $Text.Replace('{词语}', $Word)
    return [regex]::Replace($value, '\{环境:([A-Za-z0-9_]+)\}', {
            param($m)
            $name = $m.Groups[1].Value
            $envValue = [Environment]::GetEnvironmentVariable($name)
            if ([string]::IsNullOrEmpty($envValue)) {
                throw "缺少环境变量：$name"
            }
            return $envValue
        })
}

function Get-CandidateWords($Node, [string[]]$Fields, [string[]]$Seps) {
    $words = @()
    if ($null -eq $Node) { return $words }
    $items = if ($Node -is [System.Array]) { $Node } else { @($Node) }
    foreach ($item in $items) {
        if ($item -is [string]) {
            $words += Split-List $item $Seps
            continue
        }
        if ($item -is [ValueType]) { continue }
        foreach ($field in $Fields) {
            $value = $item.$field
            if ($null -eq $value) { continue }
            if ($value -is [System.Array]) {
                foreach ($v in $value) { $words += Split-List ([string]$v) $Seps }
            } else {
                $words += Split-List ([string]$value) $Seps
            }
            break
        }
    }
    return $words
}

# 去掉注释与字符串/字符字面量，只留代码本体
function Remove-注释与字面量([string]$Text) {
    $text = [regex]::Replace($Text, '/\*[\s\S]*?\*/', ' ')
    $text = [regex]::Replace($text, '//[^\r\n]*', ' ')
    $text = [regex]::Replace($text, '"""[\s\S]*?"""', ' ')
    $text = [regex]::Replace($text, '"(\\.|[^"\\\r\n])*"', ' ')
    $text = [regex]::Replace($text, "'(\\.|[^'\\\r\n])*'", ' ')
    return $text
}

# 自动校正：收集教程/示例/标准库源码里已使用的纯中文标识符，候选词与之重名即跳过
function Get-源码标识符([string]$Root) {
    $names = @{}
    $dirs = @((Join-Path $Root '教程'), (Join-Path $Root '示例'), (Join-Path $Root '标准库\src'))
    foreach ($dir in $dirs) {
        if (-not (Test-Path $dir)) { continue }
        foreach ($file in @(Get-ChildItem -Path $dir -Recurse -Filter *.java -File -ErrorAction SilentlyContinue)) {
            $clean = Remove-注释与字面量 (Read-Utf8 $file.FullName)
            foreach ($m in [regex]::Matches($clean, '[\u4e00-\u9fa5A-Za-z0-9_]+')) {
                $name = $m.Value
                if ($name -match '^[\u4e00-\u9fa5]+$' -and -not $names.ContainsKey($name)) {
                    $names[$name] = $file.Name
                }
            }
        }
    }
    return $names
}

if (-not (Test-Path $configPath)) {
    Write-Error "找不到配置：$configPath"
    exit 2
}
$cfg = (Read-Utf8 $configPath) | ConvertFrom-Json
if (-not $cfg.启用) {
    Write-Host '词典源未启用：词典源.json 中 启用=false，未做任何修改。'
    Write-Host '启用步骤：把 启用 改为 true。本地词源（默认）无需密钥；接口模式需设置对应环境变量。'
    exit 0
}

# ---- 1. 读词典与索引 ----
$text = Read-Utf8 $dictPath
$nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = $text -split "`r?`n"

$groups = @{}
$lineIndex = @{}
$allWords = @{}
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i].Trim()
    if ($i -eq 0 -and $line.Length -gt 0 -and [int]$line[0] -eq 0xFEFF) {
        $line = $line.Substring(1).Trim()
    }
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    if ($line -notmatch '^(.+?)=\[(.*)\]$') { continue }
    $name = $Matches[1].Trim()
    $words = Split-List $Matches[2] @('，', ',')
    $groups[$name] = $words
    $lineIndex[$name] = $i
    # 组名本身也是关键字，先占位，避免其他组把它当近义词加进去
    $allWords[$name] = $name
    foreach ($word in $words) { $allWords[$word] = $name }
}
if ($groups.Count -eq 0) {
    Write-Error '近义词典为空或格式错误'
    exit 2
}

# ---- 2. 查询接口并收集候选 ----
$skipGroups = @($cfg.'跳过组')
$excluded = @($cfg.'排除词')
$seps = if ($cfg.'分隔符') { @($cfg.'分隔符') } else { @('、', '，', ',', ';', '；', '/', '|') }
$fields = if ($cfg.'词字段') { @($cfg.'词字段' -split ',') } else { @('words') }
$method = if ($cfg.'方法') { [string]$cfg.'方法' } else { 'GET' }

$targets = if ($组名) { @($组名) } else { @($groups.Keys) }
$词源类型 = if ($cfg.'词源' -eq '本地') { '本地' } else { '接口' }
$sourceNames = Get-源码标识符 $root
$changes = @{}
foreach ($name in $targets) {
    if (-not $groups.ContainsKey($name)) {
        Write-Warning "词典中没有组：$name"
        continue
    }
    if ($skipGroups -contains $name) { continue }

    $queryWord = $name
    if ($cfg.'查询词' -and $cfg.'查询词'.PSObject.Properties[$name]) {
        $queryWord = [string]$cfg.'查询词'.$name
    }

    $candidates = @()
    if ($词源类型 -eq '本地') {
        $program = (Expand-Template ([string]$cfg.'本地'.'程序') $queryWord).Replace('{目录}', $PSScriptRoot)
        $argList = @()
        foreach ($item in @($cfg.'本地'.'参数')) {
            $argList += (Expand-Template ([string]$item) $queryWord).Replace('{目录}', $PSScriptRoot)
        }
        $raw = (& $program @argList 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) {
            # 「未找到」表示自动过滤后没有可用候选，不是故障，不刷警告
            if ($raw -notlike '未找到*') {
                Write-Warning "查询「$name」失败：$program 退出码 $LASTEXITCODE"
            }
            continue
        }
        if ($raw -eq '') { continue }
        $candidates = Split-List $raw $seps
    } else {
        $query = @()
        foreach ($property in $cfg.参数.PSObject.Properties) {
            $key = [uri]::EscapeDataString($property.Name)
            $value = [uri]::EscapeDataString((Expand-Template ([string]$property.Value) $queryWord))
            $query += "$key=$value"
        }
        $separator = if ($cfg.接口.Contains('?')) { '&' } else { '?' }
        $uri = $cfg.接口 + $separator + ($query -join '&')

        try {
            $response = Invoke-RestMethod -Uri $uri -Method $method -TimeoutSec $超时 -ErrorAction Stop
        } catch {
            Write-Warning "查询「$name」失败：$($_.Exception.Message)"
            continue
        }

        if ($cfg.'成功码路径') {
            $code = Get-ByPath $response $cfg.'成功码路径'
            if ([string]$code -ne [string]$cfg.'成功码') {
                Write-Warning "「$name」接口返回码 $code，跳过"
                continue
            }
        }

        $node = Get-ByPath $response $cfg.'返回路径'
        $candidates = Get-CandidateWords $node $fields $seps
    }

    $added = @()
    foreach ($candidate in $candidates) {
        $word = $candidate.Trim()
        if ($word -notmatch '^[\u4e00-\u9fa5]{1,6}$') { continue }
        if ($word -eq $name) { continue }
        if ($groups[$name] -contains $word) { continue }
        if ($allWords.ContainsKey($word) -and $allWords[$word] -ne $name) {
            Write-Host "  跳过「$word」：已属于「$($allWords[$word])」"
            continue
        }
        if ($excluded -contains $word) { continue }
        if ($sourceNames.ContainsKey($word)) {
            Write-Host "  跳过「$word」：与源码标识符重名（$($sourceNames[$word])）"
            continue
        }
        $added += $word
        # 立即登记，防止同一轮后续组再把这个词加一遍（跨组重复会被 Cjc 判为致命错误）
        $allWords[$word] = $name
    }
    $added = @($added | Select-Object -Unique)
    if ($added.Count -eq 0) { continue }
    $changes[$name] = $added
}

if ($changes.Count -eq 0) {
    Write-Host '没有可新增的近义词，词典保持不变。'
    exit 0
}

# ---- 3. 汇总或写回 ----
$total = 0
foreach ($name in $changes.Keys) {
    $words = $changes[$name]
    $total += $words.Count
    Write-Host "「$name」+ $($words -join '、')"
}
if ($干跑) {
    Write-Host "干跑完成：将新增 $total 个近义词，未写文件。去掉 -干跑 即应用。"
    exit 0
}

$backup = $text
foreach ($name in $changes.Keys) {
    $merged = @($groups[$name]) + @($changes[$name])
    $lines[$lineIndex[$name]] = $name + '=[' + ($merged -join '，') + ']'
}
Write-Utf8 $dictPath ($lines -join $nl)
Write-Host "已写入词典：新增 $total 个近义词，正在同步产物并回归检查……"

function Undo([string]$reason) {
    Write-Utf8 $dictPath $backup
    # 同步与编译都会调用原生命令（java/mvn），stderr 不能当作终止错误
    $偏好 = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $syncPath | Out-Null
    } finally {
        $ErrorActionPreference = $偏好
    }
    if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
    Write-Warning "更新已回滚：$reason"
    exit 1
}

# 原生命令（java 会把编译错误写到 stderr）不应触发 Stop；成败只看退出码
function 执行([string]$说明, [scriptblock]$动作) {
    $偏好 = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $动作
        $退出码 = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $偏好
    }
    if ($退出码 -ne 0) { Undo $说明 }
}

$outDir = Join-Path $root '构建\词典更新校验'
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }

执行 '同步词表失败' { & $syncPath }
执行 '教程编译失败（新近义词与既有标识符冲突）' { & "$root\cjc.ps1" 编译 "$root\教程" -d $outDir }
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }

Write-Host "词典更新完成：新增 $total 个近义词，已同步原生补丁与编辑器词表。"
