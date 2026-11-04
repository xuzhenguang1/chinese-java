#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""词典源：离线近义词查询，供 词典更新.ps1 使用。

主源是《同义词词林（扩展版）》（词林.txt，格式：编码 词 词 ...，仅 `=` 编码为严格同义）。
可选补充：`synonyms` 库（需自行安装），未安装时自动跳过。

用法：
  python 词典源.py 若                    # 打印候选词，顿号分隔（默认过滤单字与高歧义词）
  python 词典源.py --serve 8080           # 启动本地 HTTP 服务
  python 词典源.py --serve 8080 --synonyms  # 同时启用 synonyms 库补充

HTTP 返回（与 词典源.json 的 成功码路径/返回路径/词字段 对应）：
  {"code": 0, "word": "若", "source": "词林", "list": [{"words": "如果、假如、若是"}]}
"""

import argparse
import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CILIN = os.path.join(BASE, '词林.txt')
DEFAULT_DICT = os.path.join(BASE, '近义词典.txt')
中文词 = re.compile(r'^[\u4e00-\u9fa5]{1,6}$')


class 词源:
    def __init__(self, cilin_path, dict_path, use_synonyms=False, 上限=8, 最少重叠=1,
                 编码='=', 不消歧=False, 最少字数=2, 歧义阈值=3, 锚定=False):
        self.索引 = {}
        self.现有 = {}
        self.歧义 = {}
        self.上限 = 上限
        self.最少重叠 = 最少重叠
        self.编码 = set(编码)
        self.不消歧 = 不消歧
        self.最少字数 = 最少字数
        self.歧义阈值 = 歧义阈值
        self.锚定 = 锚定
        self.近义词库 = None
        self.load_cilin(cilin_path)
        self.load_dict(dict_path)
        if use_synonyms:
            try:
                import synonyms
                self.近义词库 = synonyms
            except ImportError:
                print('提示：未安装 synonyms 库，仅使用词林。', file=sys.stderr)

    def load_cilin(self, path):
        if not os.path.isfile(path):
            print(f'找不到词林文件：{path}', file=sys.stderr)
            sys.exit(2)
        with open(path, 'r', encoding='utf-8') as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) < 2:
                    continue
                code, words = parts[0], parts[1:]
                # 歧义度按整份词林统计（不限编码）：一个词跨的义类（编码前 2 字符）越多越歧义
                for word in words:
                    self.歧义.setdefault(word, set()).add(code[:2])
                if code[-1] not in self.编码:
                    continue
                for word in words:
                    self.索引.setdefault(word, []).append((code, words))

    def load_dict(self, path):
        if not os.path.isfile(path):
            return
        with open(path, 'r', encoding='utf-8-sig') as f:
            for line in f:
                text = line.strip()
                if not text or text.startswith('#'):
                    continue
                m = re.match(r'^(.+?)=\[(.*)\]$', text)
                if not m:
                    continue
                self.现有[m.group(1).strip()] = [w.strip() for w in re.split(r'[，,]', m.group(2)) if w.strip()]

    def 合格(self, word):
        """自动过滤：字数不足、或跨义类过多（歧义大）的候选直接丢弃。"""
        if len(word) < self.最少字数:
            return False
        if self.歧义阈值 > 0 and len(self.歧义.get(word, ())) >= self.歧义阈值:
            return False
        return True

    def 查询(self, word):
        """返回 (候选词, 来源)。

        默认只用组名（或 查询词）作为种子：以现有近义词作为同一词林组内的命中证据，
        命中数达到 最少重叠 才采纳。用整组词作种子会把「互斥/扩展」等别义带进来，
        所以不做。

        不消歧模式下不按重叠打分，直接合并该词在词林中出现的所有已选编码组，
        用于「尽可能多近义词」的激进扩充。
        """
        candidates = []
        source = []
        existing = set(self.现有.get(word, [])) - {word}
        lines = [words for _, words in self.索引.get(word, [])]
        if lines:
            if self.锚定:
                # 语义锚定：只取与现有近义词重叠达标的词林组，避免把组名的其它义项（如「空」→天空）带进来
                least = max(1, self.最少重叠)
                selected = [words for words in lines if len(set(words) & existing) >= least]
            elif self.不消歧:
                selected = lines
            else:
                scored = [(len(set(words) & existing), words) for words in lines]
                best = max(score for score, _ in scored)
                if best >= self.最少重叠:
                    selected = [words for score, words in scored if score == best]
                else:
                    selected = []
            seen = set()
            for words in selected:
                for item in words:
                    if item == word or not 中文词.match(item) or item in existing or item in candidates:
                        continue
                    if not self.合格(item):
                        continue
                    if item in seen:
                        continue
                    seen.add(item)
                    candidates.append(item)
            if candidates:
                source.append('词林')

        if self.近义词库 is not None:
            try:
                near = self.近义词库.near(word, self.上限 + 1)
                for item, score in near:
                    if item == word or not 中文词.match(item) or item in candidates:
                        continue
                    if score < 0.5 or not self.合格(item):
                        continue
                    candidates.append(item)
                if candidates:
                    source.append('synonyms')
            except Exception as error:
                print(f'synonyms 查询失败：{error}', file=sys.stderr)

        return candidates[:self.上限], '+'.join(source) or '无'


def 处理(word, 源):
    candidates, source = 源.查询(word)
    if not candidates:
        return {'code': 1, 'word': word, 'msg': '未找到近义词'}
    return {'code': 0, 'word': word, 'source': source, 'list': [{'words': '、'.join(candidates)}]}


def serve(port, 源):
    class 处理器(BaseHTTPRequestHandler):
        def do_GET(self):
            query = parse_qs(urlparse(self.path).query)
            word = (query.get('word') or query.get('词语') or [''])[0].strip()
            payload = 处理(word, 源) if word else {'code': 1, 'msg': '缺少参数 word'}
            body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            sys.stderr.write('%s %s\n' % (self.address_string(), fmt % args))

    httpd = ThreadingHTTPServer(('127.0.0.1', port), 处理器)
    print(f'词典源服务已启动：http://127.0.0.1:{port}/?word=若')
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


def main():
    parser = argparse.ArgumentParser(description='离线近义词源（词林 + 可选 synonyms）')
    parser.add_argument('word', nargs='?', help='要查询的词；配合 --serve 时可省略')
    parser.add_argument('--serve', type=int, metavar='PORT', help='启动本地 HTTP 服务')
    parser.add_argument('--词林', default=DEFAULT_CILIN, help='词林.txt 路径')
    parser.add_argument('--词典', default=DEFAULT_DICT, help='近义词典.txt 路径（用于消歧）')
    parser.add_argument('--synonyms', action='store_true', help='启用 synonyms 库补充')
    parser.add_argument('--上限', type=int, default=8, help='每组最多返回的候选词数')
    parser.add_argument('--最少重叠', type=int, default=1,
                        help='候选所在的词林组至少要与现有近义词重叠几个词；0 表示不设门槛（歧义词会带偏）')
    parser.add_argument('--编码', default='=',
                        help='收录的词林编码末位：= 严格同义，# 同类，@ 独立；可组合，如 "=#@"')
    parser.add_argument('--不消歧', action='store_true',
                        help='合并该词在词林中的所有已选编码组，不再按重叠打分（激进扩充）')
    parser.add_argument('--最少字数', type=int, default=2,
                        help='候选词最少字数；1 表示允许单字（单字歧义大），默认 2')
    parser.add_argument('--歧义阈值', type=int, default=3,
                        help='候选词跨义类数达到该值即丢弃（0 表示不过滤），默认 3')
    parser.add_argument('--锚定', action='store_true',
                        help='只从含现有近义词的词林组取候选（自动语义校正，优先于 --不消歧）')
    args = parser.parse_args()

    if not args.word and not args.serve:
        parser.print_help()
        return 1

    源 = 词源(args.词林, args.词典, args.synonyms, args.上限, args.最少重叠,
              args.编码, args.不消歧, args.最少字数, args.歧义阈值, args.锚定)
    if args.serve:
        serve(args.serve, 源)
        return 0

    candidates, _ = 源.查询(args.word)
    if not candidates:
        print(f'未找到「{args.word}」的近义词')
        return 1
    print('、'.join(candidates))
    return 0


if __name__ == '__main__':
    sys.exit(main())
