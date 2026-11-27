#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""add → commit → push，然后打印本次触发的 Actions 页面地址。

只做这一件事，不做版本号自增、不做 tag、不做 release。
推送后构建在 GitHub 上跑，结果要看 Actions 页面，这个脚本不假装知道结果。

用法:
    python tools/push.py                       # 用默认提交信息
    python tools/push.py 修复勾选状态不同步      # 自定义提交信息
    python tools/push.py --dry-run             # 只看要提交什么，不推
"""

import re
import subprocess
import sys
from pathlib import Path

# Windows 控制台默认 cp1252，直接 print 中文会 UnicodeEncodeError。
# 先改编码再说话，比把提示全改成英文有用。
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = "android.yml"

OK = "[ok]"
WARN = "[warn]"
ERR = "[error]"


def run(args, check=True):
    proc = subprocess.run(
        args,
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        sys.stderr.write(f"{ERR} `{' '.join(args)}` 失败\n")
        if proc.stdout.strip():
            sys.stderr.write(proc.stdout.strip() + "\n")
        if proc.stderr.strip():
            sys.stderr.write(proc.stderr.strip() + "\n")
        sys.exit(proc.returncode)
    return proc


def repo_url():
    """把 remote 地址规范成 https://github.com/OWNER/REPO。"""
    raw = run(["git", "remote", "get-url", "origin"], check=False)
    url = (raw.stdout or "").strip()
    if not url:
        return None
    url = re.sub(r"\.git$", "", url)
    m = re.match(r"^git@([^:]+):(.+)$", url)
    if m:
        return f"https://{m.group(1)}/{m.group(2)}"
    m = re.match(r"^(https?://[^/]+/[^/]+/[^/]+)$", url)
    if m:
        return m.group(1)
    return None


def main(argv):
    dry = "--dry-run" in argv
    words = [a for a in argv if a != "--dry-run"]
    message = " ".join(words).strip() or "checklist: 首次可运行骨架"

    branch = (run(["git", "branch", "--show-current"]).stdout or "").strip()
    if not branch:
        sys.stderr.write(f"{ERR} 当前不在任何分支上（detached HEAD），先切回 main\n")
        return 2

    print(f"{OK} 仓库 {ROOT}")
    print(f"{OK} 分支 {branch}")

    run(["git", "add", "-A"])

    staged = run(["git", "diff", "--cached", "--name-only"], check=False)
    files = [f for f in (staged.stdout or "").splitlines() if f.strip()]
    if not files:
        print(f"{WARN} 没有改动，跳过提交")
    else:
        print(f"{OK} 暂存 {len(files)} 个文件")
        for f in files[:20]:
            print(f"     {f}")
        if len(files) > 20:
            print(f"     … 另有 {len(files) - 20} 个")

        if dry:
            print(f"{WARN} --dry-run：到此为止，没有提交也没有推送")
            return 0

        run(["git", "commit", "-m", message])
        sha = (run(["git", "rev-parse", "--short", "HEAD"]).stdout or "").strip()
        print(f"{OK} 已提交 {sha}")

    if dry:
        print(f"{WARN} --dry-run：没有推送")
        return 0

    push = run(["git", "push", "origin", branch], check=False)
    if push.returncode != 0:
        sys.stderr.write((push.stderr or "") + "\n")
        sys.stderr.write(f"{ERR} 推送失败。常见原因：没有校验凭据、分支受保护、需要先 pull。\n")
        return push.returncode

    print(f"{OK} 已推送 origin/{branch}")

    base = repo_url()
    if base:
        print("")
        print("构建页面（等 10 秒左右开始出现新的 run）：")
        print(f"  {base}/actions/workflows/{WORKFLOW}")
        print("产物在 run 页面底部的 Artifacts → checklist-debug")
    else:
        print(f"{WARN} 认不出 remote 地址，去 GitHub 仓库的 Actions 页面看结果")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
