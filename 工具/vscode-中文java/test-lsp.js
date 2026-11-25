// M21 G9：LSP server 四项能力自测（stdio 帧协议客户端）。
// 用法: node 工具/vscode-中文java/test-lsp.js
'use strict';
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const server = spawn(process.execPath, [path.join(__dirname, 'language-server.js')], { stdio: ['pipe', 'pipe', 'pipe'] });
let 缓冲 = Buffer.alloc(0);
const 待回复 = new Map();
const 通知们 = [];
server.stdout.on('data', chunk => {
    缓冲 = Buffer.concat([缓冲, chunk]);
    while (true) {
        const 头尾 = 缓冲.indexOf('\r\n\r\n');
        if (头尾 < 0) break;
        const 头 = 缓冲.slice(0, 头尾).toString('ascii');
        const m = /Content-Length:\s*(\d+)/i.exec(头);
        if (!m) { 缓冲 = 缓冲.slice(头尾 + 4); continue; }
        const 长 = parseInt(m[1], 10);
        if (缓冲.length < 头尾 + 4 + 长) break;
        const 体 = 缓冲.slice(头尾 + 4, 头尾 + 4 + 长).toString('utf8');
        缓冲 = 缓冲.slice(头尾 + 4 + 长);
        const 消息 = JSON.parse(体);
        if (消息.id !== undefined && 待回复.has(消息.id)) {
            待回复.get(消息.id)(消息); 待回复.delete(消息.id);
        } else if (消息.method) {
            通知们.push(消息);
        }
    }
});
let 序号 = 0;
function 请求(方法, 参数) {
    return new Promise(解决 => {
        const id = ++序号;
        待回复.set(id, 解决);
        发送({ jsonrpc: '2.0', id, method: 方法, params: 参数 });
    });
}
function 发送(消息) {
    const 文本 = JSON.stringify(消息);
    server.stdin.write('Content-Length: ' + Buffer.byteLength(文本, 'utf8') + '\r\n\r\n' + 文本);
}
function 通知(方法, 参数) { 发送({ jsonrpc: '2.0', method: 方法, params: 参数 }); }
const 睡 = ms => new Promise(r => setTimeout(r, ms));

(async () => {
    const 失败 = [];
    function 断言(名, 条件) {
        if (条件) console.log('  通过：' + 名);
        else { console.log('  失败：' + 名); 失败.push(名); }
    }

    // initialize：能力声明四项齐备。
    const init = await 请求('initialize', { processId: process.pid, rootUri: null, capabilities: {} });
    断言('initialize 声明四项能力',
        init.result && init.result.capabilities.completionProvider &&
        init.result.capabilities.hoverProvider && init.result.capabilities.definitionProvider &&
        init.result.capabilities.textDocumentSync);
    通知('initialized', {});

    // didOpen：注入含中文关键字与全角标点的文档。
    const uri = 'file:///E:/xiadujava/构建/lsp-test.java';
    const 文本 = '公有类 测试 {\n  公有 静态 无 main(字符串[] 参数) {\n    打印行("你好"）\n  }\n}\n';
    const 目标 = path.join(__dirname, '..', '..', '构建', 'lsp-test.java');
    fs.mkdirSync(path.dirname(目标), { recursive: true });
    fs.writeFileSync(目标, 文本, 'utf8');
    通知('textDocument/didOpen', { textDocument: { uri, languageId: 'java', version: 1, text: 文本 } });
    await 睡(1500);

    // completion：含中文关键字（公有）与类型别名（字符串）。
    const comp = await 请求('textDocument/completion', { textDocument: { uri }, position: { line: 0, character: 2 } });
    const 标签 = new Set((comp.result || []).map(x => x.label));
    断言('completion 含中文关键字「公有」', 标签.has('公有'));
    断言('completion 含类型别名「字符串」', 标签.has('字符串'));
    断言('completion 含标准库类名「输出」', 标签.has('输出'));

    // hover：悬停「字符串」有说明（字符串位于第 1 行第 15 列）。
    const hover = await 请求('textDocument/hover', { textDocument: { uri }, position: { line: 1, character: 16 } });
    const hover文本 = hover.result && hover.result.contents ? hover.result.contents.value : '';
    断言('hover 返回「字符串」说明', hover文本.includes('字符串') && hover文本.includes('→'));

    // definition：跳转到「测试」类声明（第 0 行第 4 列）。
    const def = await 请求('textDocument/definition', { textDocument: { uri }, position: { line: 0, character: 5 } });
    const 定义们 = def.result || [];
    断言('definition 找到类声明', 定义们.some(d => d.range && d.range.start.line === 0));

    // diagnostics：didOpen 后收到 publishDiagnostics，转发前端诊断（本例含 CJK001 粘连 + 语法诊断）。
    const 诊断通知 = 通知们.filter(n => n.method === 'textDocument/publishDiagnostics' && n.params.uri === uri);
    const 最后诊断 = 诊断通知[诊断通知.length - 1];
    const 诊断们 = 最后诊断 ? 最后诊断.params.diagnostics : [];
    断言('diagnostics 转发前端诊断（含码与 source）',
        诊断们.length > 0 && 诊断们.every(d => d.code && d.source === 'cjc' && d.range && d.severity));

    await 请求('shutdown', {});
    通知('exit', {});
    try { fs.unlinkSync(目标); } catch (e) { /* 忽略 */ }
    server.kill();
    if (失败.length === 0) { console.log('LSP 四项能力自测：全部通过。'); process.exit(0); }
    console.log('LSP 自测失败：' + 失败.join('、'));
    process.exit(1);
})().catch(e => { console.error(e); process.exit(1); });
