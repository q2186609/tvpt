#!/usr/bin/env bash
# HTTPS 证书：Cloudflare DNS-01 签发 / 续期（Let's Encrypt）
#
#   DOMAIN=example.com CF_ZONE_ID=<zone id> ./scripts/https-cert.sh
#   DOMAIN=example.com CF_ZONE_ID=<zone id> ./scripts/https-cert.sh --renew   # 强制续期
#
# 依赖：acme.sh（~/.acme.sh/acme.sh）、Cloudflare API Token（Zone:DNS:Edit）
# 证书文件安装到 CERT_DIR（默认 ~/.certs/<域名>/），该目录必须在版本库之外，
# 私钥切勿提交到任何仓库。
set -euo pipefail

DOMAIN="${DOMAIN:-}"
ZONE_ID="${CF_ZONE_ID:-}"
TOKEN_FILE="${CF_TOKEN_FILE:-$HOME/.cloudflare_api_token}"
ACME="${ACME:-$HOME/.acme.sh/acme.sh}"
CERT_DIR="${CERT_DIR:-$HOME/.certs/$DOMAIN}"

[ -n "$DOMAIN" ] || { echo "需要设置 DOMAIN 环境变量（要签发的域名）" >&2; exit 1; }
[ -n "$ZONE_ID" ] || { echo "需要设置 CF_ZONE_ID 环境变量（Cloudflare Zone ID）" >&2; exit 1; }

FORCE_RENEW=0
for a in "$@"; do
  case "$a" in
    --renew) FORCE_RENEW=1 ;;
    *) echo "未知参数: $a" >&2; exit 2 ;;
  esac
done

[ -x "$ACME" ] || { echo "找不到 acme.sh: $ACME" >&2; exit 1; }
[ -r "$TOKEN_FILE" ] || { echo "读不到 Cloudflare token: $TOKEN_FILE" >&2; exit 1; }

export CF_Token="$(tr -d '\r\n' < "$TOKEN_FILE")"
export CF_Zone_ID="$ZONE_ID"

mkdir -p "$CERT_DIR"

if [ "$FORCE_RENEW" = "1" ]; then
  "$ACME" --renew -d "$DOMAIN" --ecc --server letsencrypt --force
elif [ -d "$HOME/.acme.sh/${DOMAIN}_ecc" ]; then
  "$ACME" --renew -d "$DOMAIN" --ecc --server letsencrypt || echo "(未到续期时间，跳过签发)"
else
  "$ACME" --issue -d "$DOMAIN" --dns dns_cf --keylength ec-256 --server letsencrypt
fi

# 把最新的 active 证书安装到 CERT_DIR（每次续期后重跑这段即可）
"$ACME" --install-cert -d "$DOMAIN" --ecc \
  --key-file       "$CERT_DIR/privkey.pem" \
  --fullchain-file "$CERT_DIR/fullchain.pem" \
  --cert-file      "$CERT_DIR/cert.pem" \
  --ca-file        "$CERT_DIR/chain.pem"

chmod 600 "$CERT_DIR/privkey.pem"
chmod 644 "$CERT_DIR/fullchain.pem" "$CERT_DIR/cert.pem" "$CERT_DIR/chain.pem"

echo
openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -subject -issuer -dates -ext subjectAltName
