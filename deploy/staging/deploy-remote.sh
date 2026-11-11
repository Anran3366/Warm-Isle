#!/usr/bin/env bash
# 把 staging 部署包传到线上并启动（只动 staging，不碰生产容器）
#
#   用法：./deploy-remote.sh user@203.0.113.10
#
# 前置：
#   1. 先跑 ./build-bundle.sh
#   2. 生产已有 ai-companion_default 网络（docker network ls 能看到）
#   3. 服务器磁盘 ≥ 300MB 空闲（jar 76MB + 镜像层）
set -euo pipefail
cd "$(dirname "$0")"
TARGET="${1:?用法: ./deploy-remote.sh user@<服务器>}"
BUNDLE="$(cd ../.. && pwd)/dist-staging"

[ -f "$BUNDLE/qqbot.jar" ] || { echo "先跑 ./build-bundle.sh"; exit 1; }

echo "== 上传部署包 =="
ssh "$TARGET" 'mkdir -p /opt/qqbot-staging'
scp -q "$BUNDLE/qqbot.jar" "$BUNDLE/Dockerfile" "$BUNDLE/docker-compose.yml" "$TARGET:/opt/qqbot-staging/"
ssh "$TARGET" 'mkdir -p /opt/qqbot-staging/packs /opt/qqbot-staging/data-staging/plugins'
scp -q "$BUNDLE/packs/menran.sing-1.0.0.zip" "$TARGET:/opt/qqbot-staging/packs/"

echo "== 记录部署前状态（用于回滚比对） =="
ssh "$TARGET" 'docker ps --format "{{.Names}}\t{{.Status}}" | sort > /tmp/before-staging.txt; free -m | head -2'

echo "== 启动 staging（生产容器不受影响） =="
ssh "$TARGET" 'cd /opt/qqbot-staging && docker compose up -d --build'
ssh "$TARGET" 'sleep 6; docker ps --format "{{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "staging|qqbot|napcat"'

echo
echo "部署完成。接下来："
echo "  1. 建一条隧道看后台：ssh -L 8082:127.0.0.1:8082 $TARGET"
echo "  2. 用测试号扫码：ssh -L 6199:127.0.0.1:6199 $TARGET → http://127.0.0.1:6199"
echo "     NapCat 里把 OneBot WS 客户端指向 ws://wsproxy-staging:3002"
echo "  3. 后台「能力插件」→ 安装本地 zip → 选 menran.sing-1.0.0.zip → 打开测试号开关"
