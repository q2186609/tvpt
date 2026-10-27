#!/usr/bin/env bash
# tvpt 初始化脚本: 准备目录、拷贝配置模板、校验必要变量。
# 幂等: 已存在的配置文件不会被覆盖。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "缺少 .env。先执行: cp .env.example .env 然后填写。" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

missing=()
[[ -z "${QB_PASSWORD:-}" ]] && missing+=("QB_PASSWORD")
[[ -z "${PROWLARR_API_KEY:-}" ]] && missing+=("PROWLARR_API_KEY")
[[ -z "${TVPT_EXTERNAL_URL:-}" ]] && missing+=("TVPT_EXTERNAL_URL")
if ((${#missing[@]})); then
  echo "以下变量仍未填写: ${missing[*]}" >&2
  echo "Prowlarr 的 API Key 要在 Prowlarr 首次启动后才能拿到, 先跑 make init 再回填。" >&2
fi

if [[ "${TVPT_EXTERNAL_URL:-}" == *localhost* || "${TVPT_EXTERNAL_URL:-}" == *127.0.0.1* ]]; then
  echo "TVPT_EXTERNAL_URL 不能用 localhost/127.0.0.1: Stremio 客户端在别的设备上," >&2
  echo "必须填 NAS 的局域网地址, 例如 http://192.168.1.10:8081" >&2
  exit 1
fi

mkdir -p data/qbittorrent-config data/prowlarr-config data/seedstrem-config data/downloads

# seedstrem 只认 /config/config.yaml; 已存在时不覆盖, 保住 Web UI 的改动和生成的密码
cfg_target="data/seedstrem-config/config.yaml"
if [[ ! -f "$cfg_target" ]]; then
  cp config/seedstrem.yaml "$cfg_target"
  echo "已创建 $cfg_target"
else
  echo "$cfg_target 已存在, 跳过"
fi

# 两个容器都以 PUID/PGID 写这个目录, 属主必须一致
if command -v sudo >/dev/null 2>&1 && [[ "$(id -u)" -ne 0 ]]; then
  sudo chown -R "${PUID:-1000}:${PGID:-1000}" data/downloads data/seedstrem-config 2>/dev/null || \
    echo "警告: 无法修改 data/ 属主, 若出现权限错误请手动 chown ${PUID:-1000}:${PGID:-1000} data" >&2
fi

echo "初始化完成。"
