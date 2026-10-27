# tvpt PoC 常用命令
.PHONY: help init up down logs status addon-url verify pt-prefs prowlarr-proxy clean

help:
	@echo "make init           准备目录与配置模板 (首次运行)"
	@echo "make up             启动全部服务"
	@echo "make down           停止全部服务"
	@echo "make status         查看容器与种子状态"
	@echo "make logs           跟踪全部日志"
	@echo "make addon-url      打印 Stremio 安装地址"
	@echo "make pt-prefs       应用 PT 必需设置 (关 DHT/PEX/LSD, 改端口, 开队列)"
	@echo "make prowlarr-proxy 配置 Prowlarr 出网代理 (站点 SSL 失败时用)"
	@echo "make verify         端到端检查"
	@echo "make clean          停止并删除全部数据 (不可恢复)"

init:
	@bash scripts/setup.sh

up:
	docker compose up -d
	@echo "等待服务就绪..."
	@docker compose ps

down:
	docker compose down

logs:
	docker compose logs -f

status:
	@docker compose ps
	@echo "--- 下载器中的种子 ---"
	@bash scripts/qb-api.sh list 2>/dev/null || echo "qB 尚未就绪或凭据未配置"

pt-prefs:
	@bash scripts/qb-pt-prefs.sh

addon-url:
	@echo "$${TVPT_EXTERNAL_URL}/stremio/manifest.json"
	@echo "在 Stremio 里用 Add Addon 粘贴上面的地址"

# 代理地址默认取 .env 里的配置; 可覆盖:
#   make prowlarr-proxy PROXY_HOST=192.168.1.10 PROXY_PORT=7800
PROXY_HOST ?= $(shell grep -E '^PROWLARR_PROXY_HOST=' .env 2>/dev/null | cut -d= -f2)
PROXY_PORT ?= $(shell grep -E '^PROWLARR_PROXY_PORT=' .env 2>/dev/null | cut -d= -f2)
PROXY_TYPE ?= $(shell grep -E '^PROWLARR_PROXY_TYPE=' .env 2>/dev/null | cut -d= -f2 || echo http)
prowlarr-proxy:
	@bash scripts/prowlarr-proxy.sh "$(PROXY_HOST)" "$(PROXY_PORT)" "$(or $(PROXY_TYPE),http)"

verify:
	@bash scripts/verify.sh

clean:
	@read -p "这会删除 data/ 下所有下载和配置, 输入 yes 确认: " ok; \
	 [[ "$$ok" == "yes" ]] || { echo "已取消"; exit 1; }
	docker compose down -v
	rm -rf data
	@echo "已清理"
