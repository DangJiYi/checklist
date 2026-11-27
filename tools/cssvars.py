#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性脚本：把 app.css 里散落的硬编码颜色换成主题变量。

只在 :root / html[data-theme="dark"] 两个块之外替换，
否则会把变量定义本身也改掉，变成循环引用。
"""

import io
import re
import sys

PATH = "app/src/main/assets/web/app.css"

# 顺序重要：先替换长的、具体的，避免被短规则抢先匹配
RULES = [
    ("background: rgba(15, 19, 25, .92);", "background: var(--topbar-bg);"),
    ("color: #cfd8e3;", "color: var(--topbar-ink);"),
    ("color: #8e9aa8;", "color: var(--topbar-tab);"),
    ("box-shadow: 0 18px 44px rgba(0, 0, 0, .45), 0 1px 0 rgba(255, 255, 255, .6) inset;",
     "box-shadow: 0 18px 44px var(--shadow), 0 1px 0 var(--sheen) inset;"),
    ("box-shadow: 0 18px 44px rgba(0, 0, 0, .5), 0 0 0 1px rgba(0, 0, 0, .05) inset;",
     "box-shadow: 0 18px 44px var(--shadow), 0 0 0 1px rgba(0, 0, 0, .05) inset;"),
    ("border: 2px solid #cdc5b3;", "border: 2px solid var(--box-border);"),
    ("background: #e8e1d3;", "background: var(--track);"),
    ("border: 1px solid #d6cebd;", "border: 1px solid var(--btn-border);"),
    ("background: #f4efe4;", "background: var(--btn-hover);"),
    ("background: #f3eee2;", "background: var(--sunken);"),
    ("background: #d8d0bf;", "background: var(--box-off);"),
    ("background: #276b5b;", "background: var(--accent-strong);"),
    ("linear-gradient(180deg, rgba(255, 255, 255, .55), rgba(255, 255, 255, 0) 42%),",
     "linear-gradient(180deg, var(--sheen), rgba(255, 255, 255, 0) 42%),"),
    ("background: rgba(0, 0, 0, .04);", "background: var(--cell-bg);"),
]

src = io.open(PATH, encoding="utf-8").read()

# 找变量定义块的范围，替换时跳过它们
protected = []
for m in re.finditer(r":root \{|html\[data-theme=\"dark\"\] \{", src):
    start = m.end()
    depth = 1
    i = start
    while i < len(src) and depth > 0:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
        i += 1
    protected.append((m.start(), i))


def in_protected(pos):
    return any(a <= pos < b for a, b in protected)


changed = 0
for old, new in RULES:
    out = []
    idx = 0
    while True:
        hit = src.find(old, idx)
        if hit < 0:
            out.append(src[idx:])
            break
        if in_protected(hit):
            out.append(src[idx:hit + len(old)])
        else:
            out.append(src[idx:hit])
            out.append(new)
            changed += 1
        idx = hit + len(old)
    src = "".join(out)

io.open(PATH, "w", encoding="utf-8", newline="\n").write(src)
print("replaced %d occurrence(s)" % changed)

# 剩下的纯 #fff 要单独看：有的在深色背景上本来就该是白色（勾选符号、徽章文字）
left = [ln for ln in src.splitlines() if "#fff" in ln]
print("remaining #fff lines:")
for ln in left:
    print("  " + ln.strip())
