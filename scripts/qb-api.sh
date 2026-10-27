#!/usr/bin/env bash
# qBittorrent WebUI API 最小封装, 供验证用。
# 用法: qb-api.sh <list|files|prio|prefs|setprio|limits|delete> [参数]
#
# 认证要点 (来自官方 WebUI-API 文档):
#   - POST /api/v2/auth/login 返回 SID cookie
#   - 必须带 Referer 或 Origin, 且与 Host 完全一致, 否则 CSRF 校验失败
set -euo pipefail

# 未显式传入时, 从项目根的 .env 读取凭据
if [[ -z "${QB_PASSWORD:-}" ]]; then
  _env_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env"
  if [[ -f "$_env_file" ]]; then
    # shellcheck disable=SC1090
    set -a; source "$_env_file"; set +a
  fi
fi

QB_URL="${QB_URL:-http://127.0.0.1:8080}"
QB_USER="${QB_USERNAME:-admin}"
QB_PASS="${QB_PASSWORD:?QB_PASSWORD 未设置}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

login() {
  curl -sS -c "$JAR" -X POST "$QB_URL/api/v2/auth/login" \
    -H "Referer: $QB_URL" -H "Origin: $QB_URL" \
    --data-urlencode "username=$QB_USER" \
    --data-urlencode "password=$QB_PASS" >/dev/null
}

api() {
  local path="$1"; shift
  curl -sS -b "$JAR" -H "Referer: $QB_URL" "$QB_URL$path" "$@"
}

# 内联 python 统一用小写变量名, 避免 shell 双引号转义问题
read -r -d '' PY_LIST <<'EOF' || true
import sys, json
for t in json.load(sys.stdin):
    seq = t.get("seq_dl")
    flp = t.get("f_l_piece_prio")
    print("%s %6.2f%% dl=%7.2fMB/s seq=%s flp=%s %-12s %s" % (
        t["hash"][:12], t["progress"] * 100, t["dlspeed"] / 1048576,
        seq, flp, t["state"], t["name"][:55]))
EOF

read -r -d '' PY_FILES <<'EOF' || true
import sys, json
names = {0: "跳过", 1: "普通", 6: "高", 7: "最高"}
for i, f in enumerate(json.load(sys.stdin)):
    print("[%d] prio=%d(%-4s) %6.2f%% %7.2fGB %s" % (
        i, f["priority"], names.get(f["priority"], "?"),
        f["progress"] * 100, f["size"] / 1073741824, f["name"]))
EOF

read -r -d '' PY_PRIO <<'EOF' || true
import sys, json
t = json.load(sys.stdin)[0]
print("顺序下载 seq_dl         :", t.get("seq_dl"))
print("首尾分片优先 f_l_piece_prio:", t.get("f_l_piece_prio"))
print("进度                    : %.2f%%" % (t["progress"] * 100))
print("已完成字节               :", t["completed"])
EOF

login

case "${1:-}" in
  list)
    api "/api/v2/torrents/info?filter=all" | python3 -c "$PY_LIST"
    ;;
  files)
    hash="${2:?需要 hash}"
    api "/api/v2/torrents/files?hash=$hash" | python3 -c "$PY_FILES"
    ;;
  prio)
    hash="${2:?需要 hash}"
    api "/api/v2/torrents/info?hashes=$hash" | python3 -c "$PY_PRIO"
    ;;
  prefs)
    api "/api/v2/app/preferences" | python3 -c '
import sys, json
p = json.load(sys.stdin)
for k in ("queueing_enabled", "max_active_downloads", "max_active_torrents",
          "dht", "pex", "lsd", "listen_port", "max_ratio_enabled"):
    print("%-24s = %s" % (k, p.get(k)))
'
    ;;
  port)
    # 只输出监听端口, 便于脚本判断是否落在 6881-6889 黑名单段
    api "/api/v2/app/preferences" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("listen_port"))'
    ;;
  setprio)
    hash="${2:?需要 hash}"; action="${3:?需要 top|bottom|up|down}"
    api -X POST "$QB_URL/api/v2/torrents/${action}Prio" \
      --data-urlencode "hashes=$hash" -w "HTTP %{http_code}\n"
    ;;
  limits)
    hash="${2:?需要 hash}"; kbps="${3:?需要 KB/s, 0 表示清除}"
    limit=$((kbps * 1024))
    api -X POST "$QB_URL/api/v2/torrents/setDownloadLimit" \
      --data-urlencode "hashes=$hash" --data-urlencode "limit=$limit" -w "HTTP %{http_code}\n"
    ;;
  delete)
    hash="${2:?需要 hash}"; keep="${3:-keep}"
    delfiles=false; [[ "$keep" == "purge" ]] && delfiles=true
    api -X POST "$QB_URL/api/v2/torrents/delete" \
      --data-urlencode "hashes=$hash" --data-urlencode "deleteFiles=$delfiles" -w "HTTP %{http_code}\n"
    ;;
  *)
    echo "用法: $0 {list|files <hash>|prio <hash>|prefs|setprio <hash> <top|bottom|up|down>|limits <hash> <KBps>|delete <hash> [purge]}" >&2
    exit 1
    ;;
esac
