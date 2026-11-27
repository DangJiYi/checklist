#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 XML 注释里是否出现连续两个减号。

XML 规范不允许注释内容包含 "--"，aapt 会直接失败：
    Error: The string "--" is not permitted within comments.
报错行号指向注释本身，很容易看漏。这个检查把它提前到本地。
"""

import io
import os
import re
import sys

# 默认扫项目里的资源；命令行传路径时只扫指定的（方便单独验证某个文件）
ROOTS = sys.argv[1:] or ["app/src/main/res", "app/src/main/AndroidManifest.xml"]

bad = 0
checked = 0

for root in ROOTS:
    if not os.path.exists(root):
        continue
    if os.path.isfile(root):
        files = [root]
    else:
        files = []
        for dirpath, _dirs, names in os.walk(root):
            for n in names:
                if n.endswith(".xml"):
                    files.append(os.path.join(dirpath, n))

    for path in files:
        checked += 1
        src = io.open(path, encoding="utf-8").read()
        # 只取注释体，忽略结束标记本身
        for m in re.finditer(r"<!--(.*?)-->", src, re.S):
            body = m.group(1)
            if "--" not in body:
                continue
            line = src.count("\n", 0, m.start()) + 1
            snippet = body.strip().splitlines()[0] if body.strip() else ""
            print("FAIL %s:%d  注释内含连续减号: %s" % (path, line, snippet[:60]))
            bad += 1

if bad == 0:
    print("PASS 扫描 %d 个 XML，注释均合法" % checked)
else:
    print("FAIL 共 %d 处不合法注释" % bad)

sys.exit(0 if bad == 0 else 1)
