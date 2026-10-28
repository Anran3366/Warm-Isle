#!/usr/bin/env bash
# 会话交接摘要：新会话开场跑一次，快速了解当前状态
set -e
cd "$(dirname "$0")/.."

echo "════════ 项目状态 $(date '+%Y-%m-%d %H:%M') ════════"
echo
echo "▸ 分支与工作区"
echo "  $(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD)"
git status --porcelain | head -5 || true
[ -z "$(git status --porcelain)" ] && echo "  工作区干净"
echo
echo "▸ 与远端差异（左=本地领先 右=远端领先）"
git fetch -q origin 2>/dev/null || echo "  (fetch 失败，跳过)"
git rev-list --left-right --count HEAD...@{u} 2>/dev/null || true
echo
echo "▸ 最近 12 次提交"
git log --oneline -12
echo
echo "▸ CHANGELOG 最近 3 条"
grep -n "^## " CHANGELOG.md | head -3
echo
echo "▸ 交接文档"
for f in docs/HANDOFF.md docs/STATUS.md docs/saas-plan.md docs/api-contract.md; do
  [ -f "$f" ] && echo "  - $f（$(wc -l < "$f" | tr -d ' ') 行）"
done
echo
echo "▸ 提醒"
echo "  - 改完代码先更新 CHANGELOG.md（含设备信息）"
echo "  - 结束会话前更新 docs/HANDOFF.md 的「当前进度」"
