// M21 G9：中文 Java Language Server（stdio LSP）。
// 单一数据源：关键字数据.json / 补全数据.json（由 同步词表.ps1 生成），诊断复用前端 `原生 诊断 --json`。
// 提供四项能力：textDocument/completion、hover、definition、publishDiagnostics。
// 不引入新语义、不重复维护词表；VSCode 侧以 LSP client 接入，失败时回退原 JSON 诊断链路。

'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const 目录 = __dirname;
const 根目录 = path.resolve(目录, '..', '..');

function 读JSON(名, 兜底) {
    try { return JSON.parse(fs.readFileSync(path.join(目录, 名), 'utf8').replace(/^\uFEFF/, '')); }
    catch (e) { return 兜底; }
}
const 关键字信息 = 读JSON('关键字数据.json', {});
const 补全信息 = 读JSON('补全数据.json', {});

// 统一说明索引：中文 → [英文, 说明]（与 extension.js 兼容链路同源）。
const 说明索引 = new Map(Object.entries(关键字信息));
for (const [中文, 信息] of Object.entries(补全信息.类型别名 || {})) {
    if (!说明索引.has(中文)) 说明索引.set(中文, 信息);
}
for (const 分区 of ['成员', '声明', '静态']) {
    for (const [中文, 信息] of Object.entries((补全信息.方法糖 || {})[分区] || {})) {
        if (!说明索引.has(中文)) 说明索引.set(中文, [信息[0], '方法糖：' + 分区]);
    }
}
for (const [类名, 说明] of Object.entries(补全信息.标准库 || {})) {
    if (!说明索引.has(类名)) 说明索引.set(类名, [类名, 说明]);
}

// ---------------- 文档仓库（内存维护 didOpen/didChange/didClose） ----------------
const 文档们 = new Map(); // uri → { text, version, languageId }
function 行文本(文本, 行号) {
    const 行们 = 文本.split(/\r?\n/);
    return 行们[行号] || '';
}
function 词范围(文本, 行号, 列号) {
    const 行 = 行文本(文本, 行号);
    // 中文标识符按连续中文/字母/数字/下划线取词。
    const 正则 = /[\u4e00-\u9fa5A-Za-z0-9_]+/g;
    let m;
    while ((m = 正则.exec(行)) !== null) {
        if (列号 >= m.index && 列号 <= m.index + m[0].length) {
            return { 词: m[0], 起: m.index, 止: m.index + m[0].length };
        }
    }
    return null;
}

// ---------------- 诊断：复用前端 诊断 --json ----------------
const 诊断队列 = new Map(); // uri → timer
function 请求诊断(uri, 文档) {
    if (诊断队列.has(uri)) clearTimeout(诊断队列.get(uri));
    诊断队列.set(uri, setTimeout(() => {
        诊断队列.delete(uri);
        const 目标 = 文档.路径 || uri转路径(uri);
        if (!目标 || !fs.existsSync(目标)) {
            发送通知('textDocument/publishDiagnostics', { uri, diagnostics: [] });
            return;
        }
        // 优先直接起 java（前端 classes 就绪），否则经 cjc.ps1 兼容链路。
        const 类目录 = path.join(根目录, '构建', '原生', '前端', 'classes');
        const 主类 = '中文.原生.前端.前端主程序';
        const 用直连 = fs.existsSync(类目录);
        const 参数 = 用直连
            ? ['-Dstdout.encoding=UTF-8', '-cp', 类目录, 主类, '诊断', 根目录, 目标, '--json']
            : [path.join(根目录, 'cjc.ps1'), '原生', '诊断', '--json', 目标];
        const 命令 = 用直连 ? 'java' : 'powershell.exe';
        let 输出 = '';
        const 子 = spawn(命令, 参数, { cwd: 根目录, windowsHide: true });
        子.stdout.on('data', d => { 输出 += d.toString('utf8'); });
        子.on('error', () => { 发送通知('textDocument/publishDiagnostics', { uri, diagnostics: [] }); });
        子.on('close', () => {
            const 诊断们 = [];
            for (const 行 of 输出.split(/\r?\n/)) {
                if (!行.trim()) continue;
                let 项;
                try { 项 = JSON.parse(行); } catch (e) { continue; }
                if (typeof 项.码 !== 'string' || typeof 项.行 !== 'number' || typeof 项.列 !== 'number') continue;
                const 行号 = Math.max(0, 项.行 - 1);
                const 列号 = Math.max(0, 项.列 - 1);
                诊断们.push({
                    range: {
                        start: { line: 行号, character: 列号 },
                        end: { line: 行号, character: 列号 + 1 }
                    },
                    severity: 项.错误 ? 1 : 2,
                    source: 'cjc',
                    code: 项.码,
                    message: 项.消息 || 项.码
                });
            }
            发送通知('textDocument/publishDiagnostics', { uri, diagnostics: 诊断们 });
        });
    }, 400));
}
function uri转路径(uri) {
    try {
        const u = new URL(uri);
        if (u.protocol === 'file:') {
            let p = decodeURIComponent(u.pathname);
            if (/^\/[A-Za-z]:/.test(p)) p = p.slice(1); // Windows: /E:/... → E:/...
            return p;
        }
    } catch (e) { /* 非 file uri 交由调用方忽略 */ }
    return null;
}

// ---------------- 定义跳转：在文档内与 workspace 扫描声明 ----------------
function 查找定义(uri, 词) {
    const 结果 = [];
    const 本 = 文档们.get(uri);
    const 目标们 = [];
    if (本 && 本.路径) 目标们.push(本.路径);
    // workspace 内同名 .java 声明（限定数量，避免全盘扫描）。
    if (工作区根) {
        try {
            for (const 文件 of 遍历Java(工作区根, 0)) 目标们.push(文件);
        } catch (e) { /* 扫描失败只查当前文档 */ }
    }
    // 中文标识符后不能用 \b（中文非 \\w），改用前瞻排除标识符字符即可。
    const 声明正则 = new RegExp('(?:class|interface|record|enum|公有类|类|接口)\\s+' + 转义(词) + '(?![\\u4e00-\\u9fa5A-Za-z0-9_])');
    const 方法正则 = new RegExp('(?:\\b|\\s)' + 转义(词) + '\\s*\\(');
    for (const 文件 of 目标们.slice(0, 200)) {
        let 文本;
        try { 文本 = fs.readFileSync(文件, 'utf8'); } catch (e) { continue; }
        const 行们 = 文本.split(/\r?\n/);
        for (let i = 0; i < 行们.length; i++) {
            if (声明正则.test(行们[i]) || 方法正则.test(行们[i])) {
                结果.push({
                    uri: 'file://' + 文件.replace(/\\/g, '/'),
                    range: { start: { line: i, character: 0 }, end: { line: i, character: 行们[i].length } }
                });
            }
        }
        if (结果.length >= 20) break;
    }
    return 结果.length ? 结果 : null;
}
function 遍历Java(目录, 深度) {
    if (深度 > 6) return [];
    const 结果 = [];
    for (const 项 of fs.readdirSync(目录, { withFileTypes: true })) {
        if (项.name === '构建' || 项.name === '.git' || 项.name === 'node_modules') continue;
        const 全 = path.join(目录, 项.name);
        if (项.isDirectory()) 结果.push(...遍历Java(全, 深度 + 1));
        else if (项.name.endsWith('.java')) 结果.push(全);
    }
    return 结果;
}
function 转义(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

// ---------------- LSP 帧协议（stdio，Content-Length + JSON） ----------------
let 工作区根 = null;
function 发送(消息) {
    const 文本 = JSON.stringify(消息);
    process.stdout.write('Content-Length: ' + Buffer.byteLength(文本, 'utf8') + '\r\n\r\n' + 文本);
}
function 发送通知(方法, 参数) { 发送({ jsonrpc: '2.0', method: 方法, params: 参数 }); }
function 回复(id, 结果) { 发送({ jsonrpc: '2.0', id, result: 结果 }); }
function 回复错(id, 码, 消息) { 发送({ jsonrpc: '2.0', id, error: { code: 码, message: 消息 } }); }

let 缓冲 = Buffer.alloc(0);
process.stdin.on('data', chunk => {
    缓冲 = Buffer.concat([缓冲, chunk]);
    while (true) {
        const 头尾 = 缓冲.indexOf('\r\n\r\n');
        if (头尾 < 0) break;
        const 头 = 缓冲.slice(0, 头尾).toString('ascii');
        const 匹配 = /Content-Length:\s*(\d+)/i.exec(头);
        if (!匹配) { 缓冲 = 缓冲.slice(头尾 + 4); continue; }
        const 长 = parseInt(匹配[1], 10);
        if (缓冲.length < 头尾 + 4 + 长) break;
        const 体 = 缓冲.slice(头尾 + 4, 头尾 + 4 + 长).toString('utf8');
        缓冲 = 缓冲.slice(头尾 + 4 + 长);
        let 消息;
        try { 消息 = JSON.parse(体); } catch (e) { continue; }
        处理消息(消息);
    }
});

function 处理消息(消息) {
    const 方法 = 消息.method;
    const id = 消息.id;
    switch (方法) {
        case 'initialize':
            工作区根 = (消息.params && (消息.params.rootUri || (消息.params.workspaceFolders || [])[0] && 消息.params.workspaceFolders[0].uri))
                ? uri转路径(消息.params.rootUri || 消息.params.workspaceFolders[0].uri) : null;
            回复(id, {
                capabilities: {
                    textDocumentSync: { openClose: true, change: 1, save: { includeText: false } },
                    completionProvider: { triggerCharacters: ['.'] },
                    hoverProvider: true,
                    definitionProvider: true
                },
                serverInfo: { name: 'cjk-language-server', version: '0.1.0' }
            });
            break;
        case 'initialized':
            break;
        case 'shutdown':
            回复(id, null);
            break;
        case 'exit':
            process.exit(0);
            break;
        case 'textDocument/didOpen': {
            const d = 消息.params.textDocument;
            文档们.set(d.uri, { text: d.text, version: d.version, languageId: d.languageId, 路径: uri转路径(d.uri) });
            请求诊断(d.uri, 文档们.get(d.uri));
            break;
        }
        case 'textDocument/didChange': {
            const d = 消息.params.textDocument;
            const 变更 = (消息.params.contentChanges || [])[消息.params.contentChanges.length - 1];
            const 已有 = 文档们.get(d.uri) || {};
            文档们.set(d.uri, { ...已有, text: 变更 ? 变更.text : (已有.text || ''), version: d.version });
            请求诊断(d.uri, 文档们.get(d.uri));
            break;
        }
        case 'textDocument/didClose': {
            文档们.delete(消息.params.textDocument.uri);
            发送通知('textDocument/publishDiagnostics', { uri: 消息.params.textDocument.uri, diagnostics: [] });
            break;
        }
        case 'textDocument/completion': {
            const uri = 消息.params.textDocument.uri;
            const 项们 = [];
            const 已用 = new Set(Object.keys(关键字信息));
            for (const [中文, [英文, 说明]] of Object.entries(关键字信息)) {
                项们.push({ label: 中文, kind: 14, detail: 英文, documentation: 说明, insertText: 中文 + ' ' });
            }
            const 追加 = (中文, 英文, 说明, kind, 插入) => {
                if (已用.has(中文)) return;
                已用.add(中文);
                项们.push({ label: 中文, kind, detail: 英文, documentation: 说明, insertText: 插入 });
            };
            for (const [中文, 信息] of Object.entries(补全信息.类型别名 || {})) 追加(中文, 信息[0], 信息[1], 7, 中文);
            for (const 分区 of ['成员', '声明', '静态']) {
                for (const [中文, 信息] of Object.entries((补全信息.方法糖 || {})[分区] || {})) 追加(中文, 信息[0], 信息[1], 2, 中文);
            }
            for (const [类名, 说明] of Object.entries(补全信息.标准库 || {})) 追加(类名, 类名, 说明, 8, 类名);
            回复(id, 项们);
            break;
        }
        case 'textDocument/hover': {
            const uri = 消息.params.textDocument.uri;
            const { line, character } = 消息.params.position;
            const 文档 = 文档们.get(uri);
            let 词 = null;
            if (文档) {
                const r = 词范围(文档.text, line, character);
                if (r) 词 = r.词;
            }
            const 信息 = 词 ? 说明索引.get(词) : null;
            if (!信息) { 回复(id, null); break; }
            回复(id, {
                contents: { kind: 'markdown', value: '**' + 词 + '** → `' + 信息[0] + '`\n\n' + 信息[1] }
            });
            break;
        }
        case 'textDocument/definition': {
            const uri = 消息.params.textDocument.uri;
            const { line, character } = 消息.params.position;
            const 文档 = 文档们.get(uri);
            let 词 = null;
            if (文档) {
                const r = 词范围(文档.text, line, character);
                if (r) 词 = r.词;
            }
            回复(id, 词 ? 查找定义(uri, 词) : null);
            break;
        }
        default:
            if (id !== undefined) 回复错(id, -32601, '未知方法：' + 方法);
    }
}
