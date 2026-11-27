#!/usr/bin/env bash
# 重写全部 commit 的作者/提交者身份，把真实邮箱换成 GitHub noreply。
#
# 为什么需要这个：本机 git config 里存的是真实邮箱，之前 18 个 commit
# 原样带上了它，而仓库是公开的。noreply 地址是 GitHub 官方机制，
# 提交仍然归属于同一个账号，但不会暴露邮箱。
#
# 只改 email 匹配的行，其他 commit（比如 Initial commit 已经是 noreply）不动。
set -euo pipefail

cd "$(dirname "$0")/.."

OLD_EMAIL="chengmankkkk@outlook.com"
NEW_EMAIL="53961226+DangJiYi@users.noreply.github.com"
NEW_NAME="DangJiYi"

echo "重写前："
git log --format="%ae" | sort | uniq -c

echo
echo "开始重写..."

FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --env-filter "
if [ \"\$GIT_AUTHOR_EMAIL\" = \"$OLD_EMAIL\" ]; then
  export GIT_AUTHOR_EMAIL=\"$NEW_EMAIL\"
  export GIT_AUTHOR_NAME=\"$NEW_NAME\"
fi
if [ \"\$GIT_COMMITTER_EMAIL\" = \"$OLD_EMAIL\" ]; then
  export GIT_COMMITTER_EMAIL=\"$NEW_EMAIL\"
  export GIT_COMMITTER_NAME=\"$NEW_NAME\"
fi
" -- --all

echo
echo "重写后："
git log --format="%ae" | sort | uniq -c

echo
echo "残留的真实邮箱数（应为 0）："
git log --all --format="%ae %ce" | grep -c "$OLD_EMAIL" || echo "0"
