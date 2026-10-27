#!/usr/bin/env bash
# 配置 Prowlarr 的出网代理。
#
# 背景: 部分 PT 站点在 Cloudflare 后面, 从国内直连时 TLS 握手会被间歇重置
# (实测 curl 成功率约 30%, 而 HTTP/3 走 UDP 是 100%)。Prowlarr 基于
# HttpClient 只走 TCP, 所以必须经过代理才能稳定访问。
#
# 用法:
#   scripts/prowlarr-proxy.sh <代理主机> <代理端口> [类型]
#   scripts/prowlarr-proxy.sh off          # 关闭代理
#
# 示例:
#   scripts/prowlarr-proxy.sh 192.168.1.10 7800 http
#
# 注意: 代理绕过列表默认包含 docker 网段和本地地址, 保证 qBittorrent
# 等内网服务不会被绕到代理上。
set -euo pipefail

# 未显式传入时, 从项目根的 .env 读取 API Key
if [[ -z "${PROWLARR_API_KEY:-}" ]]; then
  _env_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env"
  if [[ -f "$_env_file" ]]; then
    # shellcheck disable=SC1090
    set -a; source "$_env_file"; set +a
  fi
fi

PROWLARR_URL="${PROWLARR_URL:-http://127.0.0.1:9696}"
PK="${PROWLARR_API_KEY:?PROWLARR_API_KEY 未设置, 见 .env}"

api() {
  local method="$1" path="$2"
  shift 2
  curl -sS --max-time 30 -X "$method" -H "X-Api-Key: $PK" \
    -H "Content-Type: application/json" "$@" "$PROWLARR_URL$path"
}

current=$(api GET /api/v1/config/host)

if [[ "${1:-}" == "off" ]]; then
  echo "$current" | python3 -c '
import sys, json
d = json.load(sys.stdin)
d["proxyEnabled"] = False
json.dump(d, sys.stdout)
' > /tmp/prowlarr-host.json
  api PUT /api/v1/config/host --data @/tmp/prowlarr-host.json -o /dev/null -w "关闭代理: HTTP %{http_code}\n"
  exit 0
fi

proxy_host="${1:?需要代理主机地址}"
proxy_port="${2:?需要代理端口}"
proxy_type="${3:-http}"

echo "$current" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['proxyEnabled'] = True
d['proxyType'] = '$proxy_type'
d['proxyHostname'] = '$proxy_host'
d['proxyPort'] = $proxy_port
# 内网服务直连: docker 网段 + 容器名 + 本地地址
d['proxyBypassFilter'] = 'localhost,127.0.0.1,172.27.0.0/16,10.0.0.0/8,192.168.0.0/16,qbittorrent,prowlarr,seedstrem'
d['proxyBypassLocalAddresses'] = True
# 走代理时证书链完整, 保持校验开启
d['certificateValidation'] = 'enabled'
json.dump(d, sys.stdout)
" > /tmp/prowlarr-host.json

echo "提交代理配置: $proxy_type://$proxy_host:$proxy_port"
api PUT /api/v1/config/host --data @/tmp/prowlarr-host.json -o /dev/null -w "  HTTP %{http_code}\n"

sleep 3
echo "--- 回读确认 ---"
api GET /api/v1/config/host | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("  proxyEnabled          =", d.get("proxyEnabled"))
print("  proxyType             =", d.get("proxyType"))
print("  proxyHostname:port    = %s:%s" % (d.get("proxyHostname"), d.get("proxyPort")))
print("  certificateValidation =", d.get("certificateValidation"))
'
rm -f /tmp/prowlarr-host.json
