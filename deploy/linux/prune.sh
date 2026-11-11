#!/usr/bin/env bash
# 清理 Docker 构建产物：悬空镜像 + 构建缓存（保留 2G 缓存，避免下次全量重建）
# 用法：bash deploy/linux/prune.sh
set -euo pipefail

echo "▸ 清理悬空镜像"
docker image prune -f | tail -2

echo "▸ 清理构建缓存（保留 2G）"
docker builder prune -f --keep-storage 2GB | tail -2

echo "▸ 当前占用"
docker system df
