#!/usr/bin/env bash
# 应用 PT 场景必需的 qBittorrent 偏好设置。
# 幂等: 重复执行结果一致。
#
# 每一项都对应 PT 站的硬性规则或边下边播的必要条件, 不是可选的优化。
set -euo pipefail

QB_URL="${QB_URL:-http://127.0.0.1:8080}"
QB_USER="${QB_USERNAME:-admin}"
QB_PASS="${QB_PASSWORD:?QB_PASSWORD 未设置}"

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

curl -sS -c "$JAR" -X POST "$QB_URL/api/v2/auth/login" \
  -H "Referer: $QB_URL" -H "Origin: $QB_URL" \
  --data-urlencode "username=$QB_USER" \
  --data-urlencode "password=$QB_PASS" >/dev/null

# dht/pex/lsd: 私有种子必须关闭, 否则向公开网络泄露 info hash, 违反 PT 站规则
# listen_port: 不能用 6881-6889, 这是 BitTorrent 传统默认端口段, 多数 PT 站
#             列入黑名单 (实测某 PT 站返回 "Port 6881 is blacklisted", 导致
#             announce 失败、连接数恒为 0、完全无法下载)
# queueing_enabled: topPrio/increasePrio 在队列关闭时返回 409, A/B 切换依赖它
# web_ui_max_auth_fail_count / web_ui_ban_duration: 默认 5 次失败封 IP 一小时,
#             seedstrem 是高频轮询方, 密码配错时容易把自己封掉
PREFS="{
  \"dht\": false,
  \"pex\": false,
  \"lsd\": false,
  \"listen_port\": ${TORRENTING_PORT:-45001},
  \"random_port\": false,
  \"queueing_enabled\": true,
  \"max_active_downloads\": 3,
  \"max_active_torrents\": 8,
  \"max_active_uploads\": 5,
  \"web_ui_max_auth_fail_count\": 20,
  \"web_ui_ban_duration\": 60,
  \"web_ui_session_timeout\": 86400
}"

curl -sS -b "$JAR" -H "Referer: $QB_URL" \
  -X POST "$QB_URL/api/v2/app/setPreferences" \
  --data-urlencode "json=$PREFS" -o /dev/null -w "setPreferences: HTTP %{http_code}\n"

# ---- tracker 代理 ----
# 部分站点在国内不可直连, qB 会报 skipping tracker announce
# (unreachable), 新种子因此永远 0%。只代理 announce/HTTP, peer 连接保持直连
# (PT 站禁止把 peer 流量走代理, 也影响分享率统计)。
PROXY_HOST="${QB_TRACKER_PROXY_HOST:-192.168.1.10}"
PROXY_PORT="${QB_TRACKER_PROXY_PORT:-7800}"
if [ "${QB_TRACKER_PROXY_ENABLED:-true}" = "true" ]; then
  echo "--- 配置 tracker 代理 $PROXY_HOST:$PROXY_PORT ---"
  curl -sS -b "$JAR" -H "Referer: $QB_URL"     -X POST "$QB_URL/api/v2/app/setPreferences"     --data-urlencode "json={\"proxy_type\":\"HTTP\",\"proxy_ip\":\"$PROXY_HOST\",\"proxy_port\":$PROXY_PORT,\"proxy_peer_connections\":false,\"proxy_hostname_lookup\":true,\"proxy_torrents_only\":false}"     -o /dev/null -w "setProxy: HTTP %{http_code}
"
fi

echo "--- 回读确认 ---"
curl -sS -b "$JAR" -H "Referer: $QB_URL" "$QB_URL/api/v2/app/preferences" | python3 -c '
import sys, json
p = json.load(sys.stdin)
checks = {
    "dht": False, "pex": False, "lsd": False,
    "queueing_enabled": True,
}
ok = True
for k, want in checks.items():
    got = p.get(k)
    mark = "OK  " if got == want else "FAIL"
    if got != want:
        ok = False
    print("%s %-20s = %-6s (期望 %s)" % (mark, k, got, want))
port = p.get("listen_port")
port_ok = not (6881 <= int(port or 0) <= 6889)
print("%s %-20s = %-6s (不能用 6881-6889, PT 站会拉黑)" % ("OK  " if port_ok else "FAIL", "listen_port", port))
if not port_ok:
    ok = False
print("     %-20s = %s" % ("proxy_type", p.get("proxy_type")))
for k in ("max_active_downloads", "max_active_torrents", "max_active_uploads"):
    print("     %-20s = %s" % (k, p.get(k)))
sys.exit(0 if ok else 1)
'
