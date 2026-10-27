#!/usr/bin/env bash
# 验证边下边播的关键配置是否真的生效。
# 每一项都对应一个可观察的事实, 不靠"看起来配了"来判断。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
[[ -f .env ]] && { set -a; source .env; set +a; }
export QB_PASSWORD="${QB_PASSWORD:-}"
if [[ -z "$QB_PASSWORD" ]]; then
  echo "提示: .env 里 QB_PASSWORD 为空, 将跳过需要 qBittorrent 认证的检查" >&2
fi

pass=0; fail=0
ok()   { echo "  [OK]   $*"; pass=$((pass+1)); }
bad()  { echo "  [FAIL] $*"; fail=$((fail+1)); }
warn() { echo "  [WARN] $*"; }

echo "=== 1. 容器状态 ==="
for svc in qbittorrent prowlarr seedstrem; do
  state=$(docker inspect -f '{{.State.Status}}' "tvpt-$svc" 2>/dev/null || echo "missing")
  [[ "$state" == "running" ]] && ok "$svc running" || bad "$svc 状态=$state"
done

echo "=== 2. qBittorrent 与 seedstrem 是否看到同一个下载目录 ==="
# 关键: 路径不一致会导致流式读取找不到文件, 这是最常见的手配错误
qb_mount=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/downloads"}}{{.Source}}{{end}}{{end}}' tvpt-qbittorrent 2>/dev/null)
ss_mount=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' tvpt-seedstrem 2>/dev/null)
if [[ -n "$qb_mount" && "$qb_mount" == "$ss_mount" ]]; then
  ok "共享目录一致: $qb_mount"
else
  bad "共享目录不一致 qB=$qb_mount seedstrem=$ss_mount"
fi

echo "=== 3. seedstrem 连通性 ==="
ext="${TVPT_EXTERNAL_URL:-}"
if [[ -n "$ext" ]]; then
  # addon 挂在 /stremio 下, 根路径 /manifest.json 返回的是前端 PWA manifest
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$ext/stremio/manifest.json" || echo 000)
  [[ "$code" == "200" ]] && ok "manifest 可访问 $ext/stremio/manifest.json" || bad "manifest 返回 $code (检查 external_url 与端口映射)"
  # Stremio 要求 CORS
  cors=$(curl -s -D - -o /dev/null --max-time 5 "$ext/stremio/manifest.json" | grep -i 'access-control-allow-origin' || true)
  [[ -n "$cors" ]] && ok "CORS 头存在" || warn "未发现 CORS 头, Stremio Web 可能拒绝加载"
  # 必须是 stream-only addon, 且不能返回 infoHash (PT passkey 泄露红线)
  curl -s --max-time 5 "$ext/stremio/manifest.json" | python3 -c '
import sys, json
m = json.load(sys.stdin)
res = m.get("resources")
prefixes = m.get("idPrefixes", [])
if res != ["stream"]:
    raise SystemExit("resources 异常: %r" % (res,))
if "tt" not in prefixes:
    raise SystemExit("idPrefixes 缺少 tt: %r" % (prefixes,))
print("  manifest 结构正确: stream-only, idPrefixes 含 tt")
' && ok "addon 类型正确" || bad "addon manifest 结构异常"
else
  bad "TVPT_EXTERNAL_URL 未设置"
fi

echo "=== 4. qBittorrent 队列功能是否开启 ==="
# topPrio 在队列未启用时返回 409, 而 A/B 切换依赖队列优先级
if prefs_out=$(bash scripts/qb-api.sh prefs 2>/dev/null); then
  if echo "$prefs_out" | grep -qE '^queueing_enabled\s+= True'; then
    ok "队列功能已开启 (topPrio 可用)"
  else
    bad "队列功能未开启, A/B 优先级切换会返回 409"
  fi
  echoes=$(echo "$prefs_out" | grep -E '^max_active_downloads')
  [[ -n "$echoes" ]] && echo "         $echoes"
else
  warn "无法连接 qBittorrent (QB_PASSWORD 未设置?), 跳过队列检查"
fi

echo "=== 5. PT 合规与连通性检查 ==="
# 私有种子启用 DHT/PEX/LSD 会向公开网络暴露 info hash, 多数 PT 站明令禁止
if prefs_out=$(QB_URL="http://127.0.0.1:8080" bash scripts/qb-api.sh prefs 2>/dev/null); then
  if echo "$prefs_out" | grep -qE '^(dht|pex|lsd)\s+= True'; then
    bad "DHT/PEX/LSD 未全部关闭, 违反 PT 站规则:"
    echo "$prefs_out" | grep -E '^(dht|pex|lsd)' | sed 's/^/         /'
  else
    ok "DHT/PEX/LSD 已全部关闭"
  fi
else
  warn "无法读取 qBittorrent 偏好, 跳过"
fi

# 6881-6889 是 BitTorrent 传统默认端口段, 多数 PT 站拉黑
# 用这些端口会出现 announce 报 "Port X is blacklisted", 连接数恒为 0
check_port=$(bash scripts/qb-api.sh port 2>/dev/null || echo "")
if [[ -n "$check_port" ]]; then
  if [[ "$check_port" =~ ^688[1-9]$ ]]; then
    bad "监听端口 $check_port 在 6881-6889 段内, 会被 PT 站拉黑, 必须更换"
  else
    ok "监听端口 $check_port 不在黑名单段内"
  fi
fi

echo "=== 6. 索引器连通性与搜索 ==="
PK="${PROWLARR_API_KEY:-}"
if [[ -z "$PK" ]]; then
  warn "PROWLARR_API_KEY 未设置, 跳过索引器检查"
else
  pidx=$(curl -s --max-time 10 -H "X-Api-Key: $PK" "http://127.0.0.1:9696/api/v1/indexer")
  echo "$pidx" | python3 -c '
import sys, json
d = json.load(sys.stdin)
if not d:
    raise SystemExit("未配置任何索引器")
for i in d:
    st = i.get("status") or {}
    disabled = st.get("disabledTill")
    print("  %-20s 启用=%s 最近失败=%s" % (
        i.get("name"), i.get("enable"),
        st.get("initialFailure") or disabled or "无"))
' && ok "索引器已配置" || bad "索引器检查失败"

  # 代理状态: PT 站在 Cloudflare 后面时直连会间歇性 TLS 失败
  api_get="$(curl -s --max-time 10 -H "X-Api-Key: $PK" "http://127.0.0.1:9696/api/v1/config/host")"
  if echo "$api_get" | grep -q '"proxyEnabled": *true'; then
    ph=$(echo "$api_get" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("%s:%s"%(d.get("proxyHostname"),d.get("proxyPort")))')
    ok "Prowlarr 代理已启用 ($ph)"
    # 代理端口必须从容器内可达, 且不能配成 127.0.0.1
    if [[ "$ph" == 127.0.0.1:* || "$ph" == localhost:* ]]; then
      bad "代理地址是回环地址, Prowlarr 容器内无法访问, 应填宿主机局域网 IP"
    fi
  else
    warn "Prowlarr 未启用代理; 若站点在 Cloudflare 后面会出现 SSL 连接失败"
  fi
fi

echo "=== 7. 站点 API 调用频率保护 ==="
cfg="$ROOT/data/seedstrem-config/config.yaml"
if [[ -f "$cfg" ]]; then
  ttl=$(grep -E '^\s+search_cache_ttl:' "$cfg" | awk '{print $2}')
  if [[ -n "$ttl" && "$ttl" != "0" && "$ttl" != "0s" ]]; then
    ok "搜索缓存已启用 ($ttl), 可减少 PT 站 API 调用"
  else
    bad "search_cache_ttl 为 0, 每次播放都会全量搜索所有站点, 有触发限流风险"
  fi
else
  bad "找不到 $cfg, 先执行 make init"
fi

echo
echo "通过 $pass 项, 失败 $fail 项"
[[ $fail -eq 0 ]] || exit 1