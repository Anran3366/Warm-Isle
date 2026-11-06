#!/usr/bin/env bash
# 服务器端自动部署：检查 Gitee 是否有新提交，有则构建并重启
# 用法：手动执行 或 由 qqbot-autodeploy.timer 每 5 分钟触发
#
# 设计要点：
# - flock 防并发（构建时间长，定时器可能重叠）
# - 构建前先 stop 应用容器，腾出内存（2C4G 同时跑构建 + 服务会 OOM）
# - 构建失败不重启旧版本，保留现场
# - 部署后做 /api/ping 健康检查
set -euo pipefail

ROOT="${QQBOT_ROOT:-/srv/qqbot}"
DEPLOY="$ROOT/deploy/linux"
COMPOSE=(docker compose -f "$DEPLOY/docker-compose.yml")
LOCK="/tmp/qqbot-autodeploy.lock"
LOG_PREFIX="[autodeploy $(date '+%F %T')]"

log() { echo "$LOG_PREFIX $*"; }

mkdir -p "$ROOT/logs"
exec 9>"$LOCK"
if ! flock -n 9; then
  log "已有部署任务在运行，跳过"
  exit 0
fi

cd "$ROOT"
git fetch --quiet origin main || { log "git fetch 失败（网络？）"; exit 1; }

LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"
if [ "$LOCAL" = "$REMOTE" ]; then
  exit 0
fi

log "发现新提交 ${REMOTE:0:8}（本地 ${LOCAL:0:8}），开始部署"

# 1) 拉代码（此时服务仍在跑）
log "拉取代码"
if ! git pull --ff-only origin main; then
  log "git pull 失败，回滚到旧版本"
  git reset --hard "$LOCAL"
  exit 1
fi

# 2) 只有代码变动才重建（纯文档/脚本改动直接跳过，不停机）
CHANGED="$(git diff --name-only "$LOCAL" "$REMOTE" || true)"
if ! echo "$CHANGED" | grep -qE '^(src/|web/|pom.xml|guard/|deploy/linux/Dockerfile|deploy/linux/guard/)'; then
  log "本次仅文档/脚本变更，跳过构建与重启"
  exit 0
fi

# 3) 停 qqbot/guard 腾内存（2C4G 同时构建会 OOM，实测 137）
log "停止 qqbot/guard"
"${COMPOSE[@]}" stop qqbot guard >/dev/null 2>&1 || true

# 4) 构建
log "构建 jar（前端 + 后端）"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx768m}"
if ! bash "$DEPLOY/build-jar.sh" >>"$ROOT/logs/deploy.log" 2>&1; then
  log "构建失败，回滚代码并恢复旧版本"
  git reset --hard "$LOCAL"
  "${COMPOSE[@]}" up -d >/dev/null 2>&1 || true
  exit 1
fi

# 4) 重建并启动
log "重启容器"
"${COMPOSE[@]}" up -d --build

# 5) 健康检查（最多 60 秒）
log "等待健康检查"
ok=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/api/ping >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 2
done
if [ "$ok" = "1" ]; then
  log "部署成功：${REMOTE:0:8}"
else
  log "健康检查失败，请查看 docker compose logs qqbot"
  exit 1
fi
