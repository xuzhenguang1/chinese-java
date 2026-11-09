# cjc 包装脚本：统一控制台 UTF-8 输出，然后调用 Cjc.java
# 用法：
#   .\cjc.ps1 检查 示例
#   .\cjc.ps1 编译 示例 -d 输出
#   .\cjc.ps1 编译 示例 -d 输出 --表 工具\cjc\关键字表.txt
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 原生子命令转发到原生\驱动.ps1（首个参数为「原生」时），不与 Java 转译链路混用。
if ($args.Count -gt 0 -and $args[0] -eq '原生') {
    $剩余 = @()
    if ($args.Count -gt 1) {
        $剩余 = $args[1..($args.Count - 1)]
    }
    & "$PSScriptRoot\原生\驱动.ps1" @剩余
    exit $LASTEXITCODE
}
java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dcjc.home=$PSScriptRoot" "$PSScriptRoot\工具\cjc\Cjc.java" @args
exit $LASTEXITCODE
