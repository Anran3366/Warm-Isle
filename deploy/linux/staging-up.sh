#!/usr/bin/env bash
# 构建并启动 staging（插件灰度测试）实例。生产容器完全不受影响。
set -euo pipefail

cd "$(dirname "$0")"
ROOT="../.."

echo "== 构建 jar =="
( cd "$ROOT" && mvn -q -DskipTests package )

echo "== 构建前端并打进 jar =="
if [ -d "$ROOT/web/node_modules" ]; then
  ( cd "$ROOT/web" && npm run build )
else
  echo "web/node_modules 不存在，跳过前端构建（沿用 jar 内已有前端）"
fi

echo "== 准备 staging 目录 =="
mkdir -p data-staging/{qqbot,napcat/config,napcat/cache,napcat/qq,wsproxy,logs,plugins}

echo "== 启动 staging（不影响生产） =="
docker compose -f docker-compose.staging.yml up -d --build qqbot-staging

echo
echo "staging 已启动："
echo "  后台：ssh -L 8082:127.0.0.1:8082 user@<server> → http://127.0.0.1:8082"
echo "  扫码：ssh -L 6199:127.0.0.1:6199 user@<server> → http://127.0.0.1:6199"
echo "  插件目录：$(pwd)/data-staging/plugins"
