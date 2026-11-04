# cjc 包装脚本：统一控制台 UTF-8 输出，然后调用 Cjc.java
# 用法：
#   .\cjc.ps1 检查 示例
#   .\cjc.ps1 编译 示例 -d 输出
#   .\cjc.ps1 编译 示例 -d 输出 --表 工具\cjc\关键字表.txt
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dcjc.home=$PSScriptRoot" "$PSScriptRoot\工具\cjc\Cjc.java" @args
exit $LASTEXITCODE
