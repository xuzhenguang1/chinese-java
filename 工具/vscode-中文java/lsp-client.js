// M21 G9：中文 Java 轻量 LSP client（不依赖 vscode-languageclient，离线可用）。
// 负责：spawn language-server.js（stdio）、LSP 帧协议、文档同步、诊断回填、
//       completion/hover/definition provider 走 LSP；连接失败时由 extension.js 回退 JSON 链路。
'use strict';
const vscode = require('vscode');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

class LspClient {
    constructor(context, 根目录) {
        this.根目录 = 根目录;
        this.context = context;
        this.collection = vscode.languages.createDiagnosticCollection('cjc-lsp');
        context.subscriptions.push(this.collection);
        this.文档们 = new Map();      // uri → version
        this.待回复 = new Map();
        this.序号 = 0;
        this.缓冲 = Buffer.alloc(0);
        this.就绪 = false;
        this.已退出 = false;

        const 服务脚本 = path.join(__dirname, 'language-server.js');
        this.子 = spawn(process.execPath, [服务脚本], { cwd: 根目录, stdio: ['pipe', 'pipe', 'pipe'] });
        this.子.stdout.on('data', d => this.收数据(d));
        this.子.on('error', () => { this.就绪 = false; });
        this.子.on('exit', () => { this.已退出 = true; this.就绪 = false; });
        context.subscriptions.push({ dispose: () => { try { this.子.kill(); } catch (e) { /* 忽略 */ } } });
    }

    async 启动() {
        try {
            const r = await this.请求('initialize', {
                processId: process.pid,
                rootUri: vscode.Uri.file(this.根目录).toString(),
                capabilities: {}
            });
            if (!r || !r.result || !r.result.capabilities) return false;
            this.发通知('initialized', {});
            this.就绪 = true;
            this.注册能力();
            this.监听文档();
            return true;
        } catch (e) {
            this.就绪 = false;
            return false;
        }
    }

    注册能力() {
        const 选择器 = { language: 'java' };
        this.context.subscriptions.push(vscode.languages.registerCompletionItemProvider(选择器, {
            provideCompletionItems: (doc, pos) => this.请求('textDocument/completion', {
                textDocument: { uri: doc.uri.toString() }, position: { line: pos.line, character: pos.character }
            }).then(r => (r.result || []).map(x => {
                const item = new vscode.CompletionItem(x.label, x.kind || vscode.CompletionItemKind.Text);
                if (x.detail) item.detail = x.detail;
                if (x.documentation) item.documentation = x.documentation;
                if (x.insertText) item.insertText = x.insertText;
                return item;
            })).catch(() => [])
        }));
        this.context.subscriptions.push(vscode.languages.registerHoverProvider(选择器, {
            provideHover: (doc, pos) => this.请求('textDocument/hover', {
                textDocument: { uri: doc.uri.toString() }, position: { line: pos.line, character: pos.character }
            }).then(r => {
                if (!r.result || !r.result.contents) return undefined;
                return new vscode.Hover(new vscode.MarkdownString(r.result.contents.value));
            }).catch(() => undefined)
        }));
        this.context.subscriptions.push(vscode.languages.registerDefinitionProvider(选择器, {
            provideDefinition: (doc, pos) => this.请求('textDocument/definition', {
                textDocument: { uri: doc.uri.toString() }, position: { line: pos.line, character: pos.character }
            }).then(r => (r.result || []).map(d => new vscode.Location(
                vscode.Uri.parse(d.uri),
                new vscode.Range(d.range.start.line, d.range.start.character, d.range.end.line, d.range.end.character)
            ))).catch(() => [])
        }));
    }

    监听文档() {
        this.context.subscriptions.push(vscode.workspace.onDidOpenTextDocument(doc => {
            if (doc.languageId !== 'java') return;
            this.文档们.set(doc.uri.toString(), doc.version);
            this.发通知('textDocument/didOpen', {
                textDocument: { uri: doc.uri.toString(), languageId: doc.languageId, version: doc.version, text: doc.getText() }
            });
        }));
        this.context.subscriptions.push(vscode.workspace.onDidChangeTextDocument(e => {
            if (e.document.languageId !== 'java') return;
            const uri = e.document.uri.toString();
            if (!this.文档们.has(uri)) {
                this.发通知('textDocument/didOpen', {
                    textDocument: { uri, languageId: e.document.languageId, version: e.document.version, text: e.document.getText() }
                });
                this.文档们.set(uri, e.document.version);
                return;
            }
            const 变更 = e.contentChanges.map(c => ({ text: c.text }));
            this.发通知('textDocument/didChange', {
                textDocument: { uri, version: e.document.version }, contentChanges: 变更
            });
            this.文档们.set(uri, e.document.version);
        }));
        this.context.subscriptions.push(vscode.workspace.onDidCloseTextDocument(doc => {
            const uri = doc.uri.toString();
            if (!this.文档们.has(uri)) return;
            this.发通知('textDocument/didClose', { textDocument: { uri } });
            this.文档们.delete(uri);
            this.collection.delete(doc.uri);
        }));
        // 已打开的文档补发 didOpen。
        for (const doc of vscode.workspace.textDocuments) {
            if (doc.languageId !== 'java') continue;
            this.文档们.set(doc.uri.toString(), doc.version);
            this.发通知('textDocument/didOpen', {
                textDocument: { uri: doc.uri.toString(), languageId: doc.languageId, version: doc.version, text: doc.getText() }
            });
        }
    }

    收数据(chunk) {
        this.缓冲 = Buffer.concat([this.缓冲, chunk]);
        while (true) {
            const 头尾 = this.缓冲.indexOf('\r\n\r\n');
            if (头尾 < 0) break;
            const 头 = this.缓冲.slice(0, 头尾).toString('ascii');
            const m = /Content-Length:\s*(\d+)/i.exec(头);
            if (!m) { this.缓冲 = this.缓冲.slice(头尾 + 4); continue; }
            const 长 = parseInt(m[1], 10);
            if (this.缓冲.length < 头尾 + 4 + 长) break;
            const 体 = this.缓冲.slice(头尾 + 4, 头尾 + 4 + 长).toString('utf8');
            this.缓冲 = this.缓冲.slice(头尾 + 4 + 长);
            let 消息;
            try { 消息 = JSON.parse(体); } catch (e) { continue; }
            if (消息.id !== undefined && this.待回复.has(消息.id)) {
                this.待回复.get(消息.id)(消息);
                this.待回复.delete(消息.id);
            } else if (消息.method === 'textDocument/publishDiagnostics') {
                const p = 消息.params;
                const 诊断们 = (p.diagnostics || []).map(d => {
                    const v = new vscode.Diagnostic(
                        new vscode.Range(d.range.start.line, d.range.start.character, d.range.end.line, d.range.end.character),
                        d.message, d.severity
                    );
                    v.source = d.source || 'cjc';
                    v.code = d.code;
                    return v;
                });
                this.collection.set(vscode.Uri.parse(p.uri), 诊断们);
            }
        }
    }

    请求(方法, 参数) {
        return new Promise((解决, 拒绝) => {
            if (this.已退出) return 拒绝(new Error('LSP 已退出'));
            const id = ++this.序号;
            this.待回复.set(id, 解决);
            const 消息 = JSON.stringify({ jsonrpc: '2.0', id, method: 方法, params: 参数 });
            try {
                this.子.stdin.write('Content-Length: ' + Buffer.byteLength(消息, 'utf8') + '\r\n\r\n' + 消息);
            } catch (e) { this.待回复.delete(id); 拒绝(e); }
        });
    }

    发通知(方法, 参数) {
        if (this.已退出) return;
        const 消息 = JSON.stringify({ jsonrpc: '2.0', method: 方法, params: 参数 });
        try { this.子.stdin.write('Content-Length: ' + Buffer.byteLength(消息, 'utf8') + '\r\n\r\n' + 消息); }
        catch (e) { /* 连接已断，交由回退链路 */ }
    }
}

module.exports = { LspClient };
