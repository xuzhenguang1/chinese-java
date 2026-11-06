# 词表公共模块：同步词表.ps1 与 词典更新.ps1 复用同一套 UTF-8 读写、列表切分与表解析。
# 修改这里会同时影响两个脚本；新增消费方请直接 Import-Module 本文件。

# 读取 UTF-8 文本（BOM 由 .NET 自动识别）。
function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

# 按原文件的 BOM 状态写回，避免 PowerShell 脚本丢失 BOM 后被当成 ANSI。
function Write-Utf8([string]$Path, [string]$Text) {
    $编码 = New-Object System.Text.UTF8Encoding($false)
    if (Test-Path $Path) {
        $字节 = [System.IO.File]::ReadAllBytes($Path)
        if ($字节.Length -ge 3 -and $字节[0] -eq 0xEF -and $字节[1] -eq 0xBB -and $字节[2] -eq 0xBF) {
            $编码 = New-Object System.Text.UTF8Encoding($true)
        }
    }
    [System.IO.File]::WriteAllText($Path, $Text, $编码)
}

# 列表切分：默认按中英文逗号；可传入自定义分隔符集合。
function Split-List([string]$Text, [string[]]$Seps = @('，', ',')) {
    $pattern = '[' + (($Seps | ForEach-Object { [regex]::Escape($_) }) -join '') + ']'
    return @($Text -split $pattern | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

# 读取近义词典：返回原文、换行符、行数组、组名→近义词、组名行号、全部词索引。
# 格式错误抛出异常，由调用方决定错误文案与退出码。
function 读取近义词典([string]$Path, [switch]$宽松) {
    if (-not (Test-Path $Path)) {
        throw "找不到近义词典：$Path"
    }
    $text = Read-Utf8 $Path
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
        if ($line -eq '' -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^(.+?)=\[(.*)\]$') {
            if ($宽松) { continue }
            throw "近义词典第$($i + 1)行格式错误：$line"
        }
        $name = $Matches[1].Trim()
        $words = Split-List $Matches[2]
        if ($words.Count -eq 0 -and -not $宽松) {
            throw "近义词典第$($i + 1)行近义词列表为空：$line"
        }
        if ($groups.ContainsKey($name) -and -not $宽松) {
            throw "近义词典第$($i + 1)行组名重复：$name"
        }
        $groups[$name] = $words
        $lineIndex[$name] = $i
        # 组名本身也是关键字，先占位，避免其他组把它当近义词加进去
        $allWords[$name] = $name
        foreach ($word in $words) {
            $allWords[$word] = $name
        }
    }
    return [pscustomobject]@{
        Text = $text
        NewLine = $nl
        Lines = $lines
        Groups = $groups
        LineIndex = $lineIndex
        AllWords = $allWords
    }
}

# 读取关键字表：按词典展开组名，返回「中文→英文」「词条对」「英文→规范名」三张索引。
# 词条对保持关键字表的出现顺序，VSCode 数据与高亮正则依赖该顺序。
function 读取关键字表([string]$Path, $Groups) {
    if (-not (Test-Path $Path)) {
        throw "找不到关键字表：$Path"
    }
    $wordEn = @{}
    $pairs = @()
    $canonicalByEn = @{}
    $lineNo = 0
    foreach ($raw in (Read-Utf8 $Path) -split "`r?`n") {
        $lineNo++
        $line = $raw.Trim()
        if ($lineNo -eq 1 -and $line.Length -gt 0 -and [int]$line[0] -eq 0xFEFF) {
            $line = $line.Substring(1).Trim()
        }
        if ($line -eq '' -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Za-z][A-Za-z0-9_]*)=\[(.*)\]$') {
            if ($line -match '^(.+?)=(.+)$') {
                $name = $Matches[1].Trim()
                $english = $Matches[2].Trim()
                if (-not $canonicalByEn.ContainsKey($english)) {
                    $canonicalByEn[$english] = $name
                }
                $words = @($name)
                if ($Groups.ContainsKey($name)) {
                    $words += $Groups[$name]
                }
                foreach ($word in $words) {
                    if (-not $wordEn.ContainsKey($word)) {
                        $wordEn[$word] = $english
                        $pairs += [pscustomobject]@{ Word = $word; English = $english; Canonical = $canonicalByEn[$english] }
                    }
                }
                continue
            }
            throw "关键字表第${lineNo}行无法解析：$line"
        }
        $english = $Matches[1]
        if (-not $canonicalByEn.ContainsKey($english)) {
            $canonicalByEn[$english] = $null
        }
        foreach ($part in (Split-List $Matches[2])) {
            if ($null -eq $canonicalByEn[$english]) {
                $canonicalByEn[$english] = $part
            }
            $words = @($part)
            if ($Groups.ContainsKey($part)) {
                $words += $Groups[$part]
            }
            foreach ($word in $words) {
                if (-not $wordEn.ContainsKey($word)) {
                    $wordEn[$word] = $english
                    $pairs += [pscustomobject]@{ Word = $word; English = $english; Canonical = $canonicalByEn[$english] }
                }
            }
        }
    }
    if ($pairs.Count -eq 0) {
        throw '关键字表与词典展开后为空'
    }
    return [pscustomobject]@{
        WordEn = $wordEn
        Pairs = $pairs
        CanonicalByEn = $canonicalByEn
    }
}

Export-ModuleMember -Function Read-Utf8, Write-Utf8, Split-List, 读取近义词典, 读取关键字表
