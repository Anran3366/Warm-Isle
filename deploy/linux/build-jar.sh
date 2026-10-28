#!/usr/bin/env bash
# 构建可运行 jar：先构建前端，复制进 static，再打后端
# 用法：bash deploy/linux/build-jar.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

echo "==> [1/3] 构建前端"
cd "$ROOT/web"
if [ -f package-lock.json ]; then npm ci; else npm install; fi
npm run build

echo "==> [2/3] 复制前端产物到 static"
STATIC="$ROOT/src/main/resources/static"
mkdir -p "$STATIC"
rm -rf "${STATIC:?}"/*
cp -R "$ROOT/web/dist/." "$STATIC/"

echo "==> [3/3] 打包后端"
cd "$ROOT"
mvn -B -DskipTests ${MVN_EXTRA_ARGS:-} package

echo "==> 完成: $ROOT/target/qqbot.jar"
ls -lh "$ROOT/target/qqbot.jar"
