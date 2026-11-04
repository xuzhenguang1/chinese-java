const vscode = require('vscode');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

let collection;
let running = null;
let runTerminal = null;

// 中文关键字数据：中文 → [英文关键字, 用途]。由 工具\cjc\同步词表.ps1 从
// 关键字表.txt + 近义词典.txt 生成到 关键字数据.json，勿手改本块。
const KEYWORD_INFO = require('./关键字数据.json');

function activate(context) {
    collection = vscode.languages.createDiagnosticCollection('cjc');
    context.subscriptions.push(collection);

    context.subscriptions.push(vscode.languages.registerCompletionItemProvider({ language: 'java' }, {
        provideCompletionItems() {
            return Object.entries(KEYWORD_INFO).map(([中文, [英文, 说明]]) => {
                const item = new vscode.CompletionItem(中文, vscode.CompletionItemKind.Keyword);
                item.detail = 英文;
                item.documentation = 说明;
                // 中文关键字后必须跟空格或标点，补全时自动补一个空格
                item.insertText = 中文 + ' ';
                return item;
            });
        }
    }));

    context.subscriptions.push(vscode.languages.registerHoverProvider({ language: 'java' }, {
        provideHover(document, position) {
            const range = document.getWordRangeAtPosition(position, /[\u4e00-\u9fa5]+/);
            if (!range) {
                return undefined;
            }
            const 词 = document.getText(range);
            const info = KEYWORD_INFO[词];
            if (!info) {
                return undefined;
            }
            const [英文, 说明] = info;
            return new vscode.Hover(new vscode.MarkdownString(`**${词}** → \`${英文}\`\n\n${说明}`), range);
        }
    }));

    context.subscriptions.push(vscode.commands.registerCommand('cjc.runFile', async () => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || !editor.document.fileName.endsWith('.java')) {
            vscode.window.showWarningMessage('请在 .java 文件中使用「运行当前文件」。');
            return;
        }
        const root = workspaceRoot();
        if (!root) {
            vscode.window.showWarningMessage('请先打开包含 工具\\对话\\运行.ps1 的工作区。');
            return;
        }
        const runner = path.join(root, '工具', '对话', '运行.ps1');
        if (!fs.existsSync(runner)) {
            vscode.window.showWarningMessage('未找到运行脚本：' + runner);
            return;
        }
        // 运行脚本从磁盘读取源码，先保存未落盘的修改
        if (editor.document.isDirty) {
            await editor.document.save();
        }

        // 复用同一个终端，交互式程序（如 Scanner 菜单）可以直接输入
        if (!runTerminal || runTerminal.exitStatus !== undefined) {
            runTerminal = vscode.window.createTerminal({ name: '中文 Java', cwd: root });
            context.subscriptions.push(runTerminal);
        }
        runTerminal.show();

        const file = editor.document.fileName;
        // Windows 用 PowerShell 调用 &；其他平台用 pwsh -File，避免 bash 把 & 当后台运行
        const command = process.platform === 'win32'
            ? `& "${runner}" "${file}"`
            : `pwsh -NoProfile -File "${runner}" "${file}"`;
        runTerminal.sendText(command);
    }));

    context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
        if (!document.fileName.endsWith('.java')) {
            return;
        }
        if (!vscode.workspace.getConfiguration('cjc').get('checkOnSave', true)) {
            return;
        }
        runCheck(document);
    }));

    context.subscriptions.push(vscode.workspace.onDidCloseTextDocument(document => {
        collection.delete(document.uri);
    }));
}

function workspaceRoot() {
    const folders = vscode.workspace.workspaceFolders;
    return folders && folders.length > 0 ? folders[0].uri.fsPath : null;
}

function javaExecutable() {
    if (process.env.JAVA_HOME) {
        const candidate = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }
    return 'java';
}

function runCheck(document) {
    const root = workspaceRoot();
    if (!root) {
        return;
    }
    const cjcPath = path.join(root, '工具', 'cjc', 'Cjc.java');
    if (!fs.existsSync(cjcPath)) {
        return;
    }

    if (running) {
        try {
            running.kill();
        } catch (ignored) {
            // 上一个检查进程已退出
        }
    }

    const child = spawn(javaExecutable(), [
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-Dcjc.home=' + root,
        cjcPath,
        '检查',
        document.fileName
    ], { cwd: root, windowsHide: true });

    let output = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });

    child.on('error', () => {
        running = null;
    });

    child.on('close', code => {
        running = null;
        collection.delete(document.uri);
        if (code === 0 || code === 1) {
            collection.set(document.uri, parseDiagnostics(output, document));
        }
    });

    running = child;
}

function parseDiagnostics(text, document) {
    const lines = text.split(/\r?\n/);
    const diagnostics = [];

    for (let i = 0; i < lines.length; i++) {
        const match = /错误\s+(CJK\d{3})\s+第(\d+)行\s+第(\d+)列/.exec(lines[i]);
        if (!match) {
            continue;
        }

        let message = '';
        let suggestion = '';
        for (let j = i + 1; j < lines.length; j++) {
            const line = lines[j].trim();
            if (!line || /^\d+\s*\|/.test(line) || line.startsWith('|')) {
                break;
            }
            if (line.startsWith('建议：')) {
                suggestion = line.slice('建议：'.length);
                continue;
            }
            if (line.startsWith('修正：')) {
                break;
            }
            message = message ? message + ' ' + line : line;
        }

        const lineNumber = Number(match[2]) - 1;
        const column = Number(match[3]) - 1;
        const lineLength = lineNumber < document.lineCount ? document.lineAt(lineNumber).text.length : 0;
        const end = Math.min(lineLength, column + 1);
        const range = new vscode.Range(lineNumber, column, lineNumber, end);

        const 描述 = message || match[1];
        const diagnostic = new vscode.Diagnostic(range, suggestion ? `${描述}\n建议：${suggestion}` : 描述, vscode.DiagnosticSeverity.Error);
        diagnostic.source = 'cjc';
        diagnostic.code = match[1];
        diagnostics.push(diagnostic);
    }

    return diagnostics;
}

function deactivate() {
    if (running) {
        try {
            running.kill();
        } catch (ignored) {
            // 忽略退出时的进程终止错误
        }
        running = null;
    }
}

module.exports = { activate, deactivate };
