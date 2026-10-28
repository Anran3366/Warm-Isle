#!/usr/bin/env bash
# 一键部署 QQ 陪聊机器人（Ubuntu / Debian）
# 用法：sudo bash scripts/install-server.sh
set -euo pipefail

REPO_URL="${QQBOT_REPO:-https://gitee.com/menran/warm_isle.git}"
TARGET="${QQBOT_DIR:-/srv/qqbot}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"

log() { echo -e "\033[1;32m[部署]\033[0m $*"; }
warn() { echo -e "\033[1;33m[提示]\033[0m $*"; }

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 运行：sudo bash scripts/install-server.sh"
  exit 1
fi

# 1. 检查内存
mem_mb=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
log "内存：${mem_mb} MB"
if [ "$mem_mb" -lt 1900 ]; then
  warn "内存低于 2GB，imageguard 可能被 OOM，建议 4GB"
fi

# 2. 安装 Docker
if ! command -v docker >/dev/null 2>&1; then
  log "安装 Docker ..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
else
  log "Docker 已安装：$(docker --version)"
fi

# 3. 拉代码
if [ ! -d "$TARGET/.git" ]; then
  log "拉取代码到 $TARGET ..."
  mkdir -p "$(dirname "$TARGET")"
  git clone "$REPO_URL" "$TARGET"
else
  log "代码已存在，更新中 ..."
  git -C "$TARGET" pull --rebase || true
fi

cd "$TARGET/deploy/linux"

# 4. 生成 .env
if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  if [ -n "$ADMIN_PASSWORD" ]; then
    sed -i "s|^ADMIN_PASSWORD=.*|ADMIN_PASSWORD=${ADMIN_PASSWORD}|" .env
  fi
  if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
    sed -i "s|^DEEPSEEK_API_KEY=.*|DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}|" .env
  fi
  if [ -n "${PUBLIC_BASE_URL:-}" ]; then
    sed -i "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=${PUBLIC_BASE_URL}|" .env
  fi
  warn "已生成 .env，请补全以下必填项：DEEPSEEK_API_KEY / ADMIN_PASSWORD / PUBLIC_BASE_URL"
  warn "编辑：nano $TARGET/deploy/linux/.env"
else
  log ".env 已存在，跳过"
fi

# 5. 构建启动
log "构建并启动容器（首次约 3-8 分钟）..."
docker compose up -d --build

sleep 5
docker compose ps

ip=$(curl -s -m 5 https://api.ipify.org || echo "服务器IP")
log "部署完成"
echo "后台地址：http://${ip}"
echo "下一步：登录后台 → 机器人账号 → 添加账号 → 扫码"
echo "日志：cd $TARGET/deploy/linux && docker compose logs -f qqbot"
