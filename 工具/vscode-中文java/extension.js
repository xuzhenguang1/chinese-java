const vscode = require('vscode');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

let collection;
let running = null;
let runTerminal = null;

// 输入防抖：每个文档一个定时器，500ms 内的连续输入只触发一次检查
const pendingChecks = new Map();

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

    // Quick Fix：CJK001 补空格；CJK002/106 换标点；CJK101 改「等于(...)」；CJK103 补「读行();」；CJK104 改「<」
    context.subscriptions.push(vscode.languages.registerCodeActionsProvider({ language: 'java' }, {
        provideCodeActions(document, range, context) {
            const actions = [];
            for (const diagnostic of context.diagnostics) {
                if (diagnostic.source !== 'cjc') {
                    continue;
                }
                if (diagnostic.code === 'CJK001') {
                    const action = new vscode.CodeAction('在关键字后补空格', vscode.CodeActionKind.QuickFix);
                    action.edit = new vscode.WorkspaceEdit();
                    action.edit.insert(document.uri, diagnostic.range.start, ' ');
                    action.diagnostics = [diagnostic];
                    actions.push(action);
                } else if (diagnostic.code === 'CJK104') {
                    const start = diagnostic.range.start;
                    const 行文本 = document.lineAt(start.line).text;
                    if (行文本.substring(start.character, start.character + 2) === '<=') {
                        const action = new vscode.CodeAction('把「<=」改成「<」', vscode.CodeActionKind.QuickFix);
                        action.edit = new vscode.WorkspaceEdit();
                        action.edit.replace(document.uri, new vscode.Range(start.line, start.character, start.line, start.character + 2), '<');
                        action.diagnostics = [diagnostic];
                        actions.push(action);
                    }
                } else if (diagnostic.code === 'CJK002' || diagnostic.code === 'CJK106') {
                    const start = diagnostic.range.start;
                    const 行文本 = document.lineAt(start.line).text;
                    const 原文 = 行文本.charAt(start.character);
                    const 替换 = 标点替换(原文, 行文本);
                    if (替换) {
                        const action = new vscode.CodeAction(`把「${原文}」换成「${替换}」`, vscode.CodeActionKind.QuickFix);
                        action.edit = new vscode.WorkspaceEdit();
                        action.edit.replace(document.uri,
                            new vscode.Range(start.line, start.character, start.line, start.character + 1), 替换);
                        action.diagnostics = [diagnostic];
                        actions.push(action);
                    }
                } else if (diagnostic.code === 'CJK103') {
                    const start = diagnostic.range.start;
                    const 行文本 = document.lineAt(start.line).text;
                    const 匹配 = /([A-Za-z0-9_\u4e00-\u9fa5]+)\s*\.\s*读整数\s*\(/.exec(行文本);
                    if (匹配) {
                        const action = new vscode.CodeAction(`补一句「${匹配[1]}.读行();」`, vscode.CodeActionKind.QuickFix);
                        action.edit = new vscode.WorkspaceEdit();
                        const 缩进 = (行文本.match(/^\s*/) || [''])[0];
                        action.edit.insert(document.uri, new vscode.Position(start.line, 行文本.length),
                            `\n${缩进}${匹配[1]}.读行();`);
                        action.diagnostics = [diagnostic];
                        actions.push(action);
                    }
                } else if (diagnostic.code === 'CJK101') {
                    const start = diagnostic.range.start;
                    const 行文本 = document.lineAt(start.line).text;
                    const 比较 = /([A-Za-z0-9_\u4e00-\u9fa5.]+?)\s*==\s*([A-Za-z0-9_\u4e00-\u9fa5.]+)/.exec(行文本);
                    if (比较 && (行文本.match(/==/g) || []).length === 1) {
                        const action = new vscode.CodeAction('改用「等于(...)」比较内容', vscode.CodeActionKind.QuickFix);
                        action.edit = new vscode.WorkspaceEdit();
                        const 新行 = 行文本.slice(0, 比较.index)
                            + `${比较[1]}.等于(${比较[2]})`
                            + 行文本.slice(比较.index + 比较[0].length);
                        action.edit.replace(document.uri,
                            new vscode.Range(start.line, 0, start.line, 行文本.length), 新行);
                        action.diagnostics = [diagnostic];
                        actions.push(action);
                    }
                }
            }
            return actions;
        }
    }, { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] }));

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
        cancelPending(document);
        runCheck(document);
    }));

    // 4.9 输入时检查：500ms 防抖；检查的是内存中的内容（临时文件），无需先保存
    context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(event => {
        if (!event.document.fileName.endsWith('.java')) {
            return;
        }
        if (!vscode.workspace.getConfiguration('cjc').get('checkOnType', true)) {
            return;
        }
        scheduleCheck(event.document);
    }));

    context.subscriptions.push(vscode.workspace.onDidCloseTextDocument(document => {
        cancelPending(document);
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

function cancelPending(document) {
    const key = document.uri.toString();
    const timer = pendingChecks.get(key);
    if (timer) {
        clearTimeout(timer);
        pendingChecks.delete(key);
    }
}

function scheduleCheck(document) {
    const key = document.uri.toString();
    const previous = pendingChecks.get(key);
    if (previous) {
        clearTimeout(previous);
    }
    const timer = setTimeout(() => {
        pendingChecks.delete(key);
        if (document.isClosed) {
            return;
        }
        runCheck(document, document.getText());
    }, 500);
    pendingChecks.set(key, timer);
}

// content 为 undefined 时检查磁盘文件（保存场景）；否则写临时文件检查未保存内容
function runCheck(document, content) {
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
        running = null;
    }

    let target = document.fileName;
    let tempFile = null;
    if (content !== undefined) {
        // 每次检查用独立临时文件，避免上一次进程还没释放文件时覆盖失败
        const base = 'cjc-vscode-' + document.uri.toString().replace(/[^a-zA-Z0-9]/g, '_');
        tempFile = path.join(os.tmpdir(), base + '-' + Date.now() + '.java');
        try {
            fs.writeFileSync(tempFile, content, 'utf8');
        } catch (ignored) {
            return;
        }
        target = tempFile;
    }

    const cleanup = () => {
        if (tempFile) {
            try {
                fs.unlinkSync(tempFile);
            } catch (ignored) {
                // 临时文件可能已被清理
            }
        }
    };

    const child = spawn(javaExecutable(), [
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-Dcjc.home=' + root,
        cjcPath,
        '检查',
        target
    ], { cwd: root, windowsHide: true });

    let output = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });

    child.on('error', () => {
        cleanup();
        if (running === child) {
            running = null;
        }
    });

    child.on('close', code => {
        cleanup();
        // 已经被更新的一次检查替代：不覆盖最新结果
        if (running !== child) {
            return;
        }
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
        // `错误 CJK001/002` 是阻断编译的错误；`提示 CJK101–105` 只是新手建议，显示为警告
        const match = /(错误|提示)\s+(CJK\d{3})\s+第(\d+)行\s+第(\d+)列/.exec(lines[i]);
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

        const lineNumber = Number(match[3]) - 1;
        const column = Number(match[4]) - 1;
        const lineLength = lineNumber < document.lineCount ? document.lineAt(lineNumber).text.length : 0;
        const end = Math.min(lineLength, column + 1);
        const range = new vscode.Range(lineNumber, column, lineNumber, end);

        const 级别 = match[1] === '提示' ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Error;
        const 描述 = message || match[2];
        const diagnostic = new vscode.Diagnostic(range, suggestion ? `${描述}\n建议：${suggestion}` : 描述, 级别);
        diagnostic.source = 'cjc';
        diagnostic.code = match[2];
        diagnostics.push(diagnostic);
    }

    return diagnostics;
}

function deactivate() {
    for (const timer of pendingChecks.values()) {
        clearTimeout(timer);
    }
    pendingChecks.clear();
    if (running) {
        try {
            running.kill();
        } catch (ignored) {
            // 忽略退出时的进程终止错误
        }
        running = null;
    }
}

// 全角标点 → 半角替代字符。`。` 要看位置：行末改 `;`，句中改 `.`。
function 标点替换(字符, 行文本) {
    if (字符 === '。') {
        return 行文本.trimEnd().endsWith('。') ? ';' : '.';
    }
    const 映射 = {
        '、': ',', '“': '"', '”': '"', '‘': "'", '’': "'",
        '《': '"', '》': '"', '「': '"', '」': '"', '『': '"', '』': '"',
        '…': '...', '—': '-', '－': '-', '　': ' '
    };
    return 映射[字符] || null;
}

module.exports = { activate, deactivate };
