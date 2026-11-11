#!/usr/bin/env bash
# 本地：构建 staging 部署包（jar + 前端 + 插件包 + compose）
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
OUT="$ROOT/dist-staging"

echo "== 1/4 构建前端 =="
( cd "$ROOT/web" && npm run build )

echo "== 2/4 同步静态资源并打包 jar =="
cp -R "$ROOT/web/dist/." "$ROOT/src/main/resources/static/"
( cd "$ROOT" && mvn -q -DskipTests package )

echo "== 3/4 组装部署包 =="
rm -rf "$OUT" && mkdir -p "$OUT/packs"
cp "$ROOT/target/qqbot.jar" "$OUT/qqbot.jar"
cp "$ROOT/deploy/staging/Dockerfile" "$OUT/Dockerfile"
cp "$ROOT/deploy/staging/docker-compose.yml" "$OUT/docker-compose.yml"
cp "$ROOT/packs/menran.sing-1.0.0.zip" "$OUT/packs/"
mkdir -p "$OUT/data-staging/plugins"

echo "== 4/4 完成 =="
du -sh "$OUT"
echo "下一步：./deploy-remote.sh user@<服务器>"
