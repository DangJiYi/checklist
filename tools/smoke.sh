#!/usr/bin/env bash
# 对真机跑一遍完整流程的冒烟脚本。
#
# 手点界面测不出锁规则：前端把按钮禁用了，你永远看不到后端是否真的会拒绝。
# 这个脚本直接打接口，绕过界面。
#
#   ./tools/smoke.sh 192.168.1.7 xxxx-xxxx
#   BASE=http://127.0.0.1:8787 ./tools/smoke.sh          # 用 adb forward 时
#
# 需要 curl。全部通过时会打印 PASS，任一断言失败立刻退出（set -e）。

set -uo pipefail

BASE="${BASE:-}"
if [ -z "$BASE" ]; then
  if [ $# -ge 1 ]; then
    BASE="http://$1:8787"
  else
    echo "用法: $0 <手机IP> [令牌]"
    echo "  或: BASE=http://127.0.0.1:8787 $0"
    exit 2
  fi
fi
TOKEN="${2:-${TOKEN:-}}"

# charset 必须显式写：NanoHTTPD 不带 charset 时按 US-ASCII 解码，中文会变乱码
H=(-H "Content-Type: application/json; charset=utf-8")
# 令牌已取消；保留这个参数只是为了不破坏旧用法，不再发送任何鉴权头
[ -n "$TOKEN" ] && echo "(提示：本项目已不做鉴权，传入的令牌会被忽略)"

fails=0
pass=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; fails=$((fails+1)); }

# api METHOD PATH [BODY] -> 输出 "<body>\n<status>"
api() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -m 10 -X "$method" "${H[@]}" -d "$body" -w '\n%{http_code}' "$BASE$path"
  else
    curl -sS -m 10 -X "$method" "${H[@]}" -w '\n%{http_code}' "$BASE$path"
  fi
}

body_of() { printf '%s' "$1" | sed '$d'; }
code_of() { printf '%s' "$1" | tail -n1; }

# 从 JSON 里抠数字字段（不引 jq，手机环境里常没有）
num() { printf '%s' "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2; }

say "0. 连通性 $BASE"
r=$(api GET /api/ping)
if [ "$(code_of "$r")" = "200" ]; then ok "ping 200"; else bad "ping 失败：$(code_of "$r")"; echo "$r"; exit 1; fi
if printf '%s' "$r" | grep -q '"app":"checklist"'; then ok "app 标识正确"; else bad "8787 上不是本应用"; fi

say "1. 建模板"
r=$(api POST /api/templates '{"name":"冒烟模板","items":["刷牙","喝水","拉伸"]}')
if [ "$(code_of "$r")" = "200" ]; then ok "模板创建 200"; else bad "模板创建失败"; echo "$r"; fi
TPL=$(num "$(body_of "$r")" id)
[ -n "$TPL" ] && ok "模板 id=$TPL" || bad "没解析到模板 id"

say "2. 从模板建实例（快照）"
r=$(api POST /api/instances "{\"templateId\":$TPL,\"clientToken\":\"smoke-$$\",\"localDate\":\"$(date +%F)\"}")
if [ "$(code_of "$r")" = "200" ]; then ok "实例创建 200"; else bad "实例创建失败"; echo "$r"; fi
INST=$(num "$(body_of "$r")" id)
TOTAL=$(num "$(body_of "$r")" totalCount)
[ "$TOTAL" = "3" ] && ok "totalCount=3" || bad "totalCount=$TOTAL，期望 3"

say "3. 幂等：同一 clientToken 不重复建表"
r=$(api POST /api/instances "{\"templateId\":$TPL,\"clientToken\":\"smoke-$$\",\"localDate\":\"$(date +%F)\"}")
INST2=$(num "$(body_of "$r")" id)
[ "$INST" = "$INST2" ] && ok "返回同一张表 id=$INST2" || bad "建出了第二张表 $INST2"

say "4. 逐项勾选"
ITEMS=($(printf '%s' "$(api GET "/api/instances/$INST")" | grep -o '"id":[0-9]*,"name"' | grep -o '[0-9]*'))
for it in "${ITEMS[@]}"; do
  r=$(api POST "/api/items/$it/toggle")
  c=$(num "$(body_of "$r")" completedCount)
  [ "$(code_of "$r")" = "200" ] && ok "勾选 $it -> completedCount=$c" || bad "勾选 $it 失败"
done

r=$(api GET "/api/instances/$INST")
if printf '%s' "$r" | grep -q '"status":"completed_full"'; then ok "全部勾满自动 completed_full"; else bad "没有自动完成"; fi
if printf '%s' "$r" | grep -q '"locked":true'; then ok "locked=true"; else bad "没有锁定"; fi

say "5. 锁定后必须被后端拒绝（关键断言）"
FIRST="${ITEMS[0]}"
r=$(api POST "/api/items/$FIRST/toggle")
CODE=$(code_of "$r")
if [ "$CODE" = "409" ]; then ok "锁定后 toggle 返回 409"; else bad "锁定后 toggle 返回 $CODE，期望 409（前端禁用不够，后端必须拒绝）"; fi
if printf '%s' "$(body_of "$r")" | grep -q '"code":"LOCKED"'; then ok "错误码 LOCKED"; else bad "错误码不是 LOCKED"; fi

say "6. 打开开关后可改，且不退回 active"
r=$(api PUT /api/settings '{"allow_edit_completed":"1"}')
if [ "$(code_of "$r")" = "200" ]; then ok "开关已打开"; else bad "改设置失败"; fi
r=$(api POST "/api/items/$FIRST/toggle")
if [ "$(code_of "$r")" = "200" ]; then ok "开关打开后 toggle 200"; else bad "开关打开后仍失败：$(code_of "$r")"; fi
if printf '%s' "$(body_of "$r")" | grep -q '"status":"completed_partial"'; then
  ok "降为 completed_partial"
else
  bad "状态不是 completed_partial（绝不该回到 active）"
fi
if printf '%s' "$(body_of "$r")" | grep -q '"locked":false'; then ok "locked=false"; else bad "locked 仍为 true"; fi

say "7. 日历与导出"
r=$(api GET "/api/calendar/$(date +%Y)/$(date +%-m)")
if [ "$(code_of "$r")" = "200" ]; then ok "日历 200"; else bad "日历失败"; fi
r=$(api GET /api/status)
if printf '%s' "$(body_of "$r")" | grep -q '"schemaVersion":1'; then ok "schemaVersion=1"; else bad "schemaVersion 不符"; fi
r=$(api GET /api/export)
if printf '%s' "$(body_of "$r")" | grep -q '"instances"'; then ok "导出含 instances"; else bad "导出异常"; fi

say "8. 删除整张表"
r=$(api DELETE "/api/instances/$INST")
if [ "$(code_of "$r")" = "200" ]; then ok "整表删除 200"; else bad "删除失败"; fi
r=$(api GET "/api/instances/$INST")
[ "$(code_of "$r")" = "404" ] && ok "删除后 404" || bad "删除后仍可读：$(code_of "$r")"

say "9. 收尾：关掉开关、删模板"
api PUT /api/settings '{"allow_edit_completed":"0"}' >/dev/null
api DELETE "/api/templates/$TPL" >/dev/null
ok "已清理"

printf '\n通过 %d 项，失败 %d 项\n' "$pass" "$fails"
[ "$fails" -eq 0 ] || exit 1
