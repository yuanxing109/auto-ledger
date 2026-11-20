#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Kotlin/XML 静态体检（本机没有 Android SDK 时的兜底）。

它不做语义分析 —— CI 上的 `./gradlew :app:assembleDebug` 才是真编译。
这里只保证"不会因为少个 } 或截断的字符串，让 CI 白跑一轮"：
  * Kotlin：`(){}[]` 配平，字符串/字符/注释/`${...}` 内嵌字符串都能正确跳过
  * XML：ElementTree 能解析

用法：python3 tools/check-kotlin-syntax.py        # 退出码 0=干净
"""
import glob
import io
import sys
import xml.etree.ElementTree as ET

PAIRS = {")": "(", "]": "[", "}": "{"}


class Scanner:
    def __init__(self, text):
        self.s = text
        self.i = 0
        self.n = len(text)
        self.stack = []
        self.line = 1

    def peek(self, k=0):
        j = self.i + k
        return self.s[j] if j < self.n else ""

    def skip_line_comment(self):
        while self.i < self.n and self.s[self.i] != "\n":
            self.i += 1

    def skip_block_comment(self):
        self.i += 2
        while self.i < self.n:
            if self.peek() == "\n":
                self.line += 1
            if self.s.startswith("*/", self.i):
                self.i += 2
                return
            self.i += 1

    def skip_quoted(self, quote, raw=False):
        """跳过字符串；raw 用于三引号。

        字符串里可以嵌 `${...}`，模板里还能再嵌字符串（`CsvImporter.esca` 就是），
        所以遇到 `${` 必须递归，否则括号栈会被内层引号搞乱。
        """
        while self.i < self.n:
            c = self.s[self.i]
            if c == "\n":
                self.line += 1
            if c == "\\" and not raw:
                self.i += 2
                continue
            if self.s.startswith("${", self.i):
                self.skip_template_expr()
                continue
            if self.s.startswith(quote, self.i):
                self.i += len(quote)
                return
            self.i += 1

    def skip_template_expr(self):
        depth = 1
        self.i += 2  # 吃掉 ${
        while self.i < self.n and depth > 0:
            c = self.s[self.i]
            if self.s.startswith("//", self.i):
                self.skip_line_comment()
                continue
            if self.s.startswith("/*", self.i):
                self.skip_block_comment()
                continue
            if self.s.startswith('"""', self.i):
                self.i += 3
                self.skip_quoted('"""', raw=True)
                continue
            if c == '"':
                self.i += 1
                self.skip_quoted('"')
                continue
            if c == "'":
                self.i += 1
                self.skip_quoted("'")
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif c == "\n":
                self.line += 1
            self.i += 1

    def run(self):
        while self.i < self.n:
            c = self.s[self.i]
            if c == "\n":
                self.line += 1
                self.i += 1
                continue
            if self.s.startswith("//", self.i):
                self.skip_line_comment()
                continue
            if self.s.startswith("/*", self.i):
                self.skip_block_comment()
                continue
            if self.s.startswith('"""', self.i):
                self.i += 3
                self.skip_quoted('"""', raw=True)
                continue
            if c == '"':
                self.i += 1
                self.skip_quoted('"')
                continue
            if c == "'":
                self.i += 1
                self.skip_quoted("'")
                continue
            if self.s.startswith("${", self.i):
                self.skip_template_expr()
                continue
            if c in "([{":
                self.stack.append((c, self.line))
                self.i += 1
                continue
            if c in ")]}":
                if not self.stack:
                    return "第 %d 行多余 %s" % (self.line, c)
                top, ln = self.stack.pop()
                if PAIRS[c] != top:
                    return "第 %d 行 %s 与第 %d 行 %s 不配对" % (self.line, c, ln, top)
                self.i += 1
                continue
            self.i += 1
        if self.stack:
            top, ln = self.stack[-1]
            return "第 %d 行 %s 未闭合" % (ln, top)
        return None


def main():
    kt = sorted(glob.glob("app/src/main/**/*.kt", recursive=True))
    xml = sorted(glob.glob("app/src/main/**/*.xml", recursive=True))
    bad = 0
    for path in kt:
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            err = Scanner(f.read()).run()
        if err:
            bad += 1
            print("KT  %s: %s" % (path.replace("\\", "/"), err))
    for path in xml:
        try:
            ET.parse(path)
        except ET.ParseError as e:
            bad += 1
            print("XML %s: %s" % (path.replace("\\", "/"), e))
    print("---")
    print("kotlin=%d xml=%d problems=%d" % (len(kt), len(xml), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())