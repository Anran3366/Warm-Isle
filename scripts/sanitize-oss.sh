#!/usr/bin/env bash
# 生成用于开源仓的脱敏快照（不推送，只落盘）
# 用法：bash scripts/sanitize-oss.sh /tmp/ai-companion-oss
# 兼容 GNU sed（Linux / Git Bash）与 BSD sed（macOS）
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-/tmp/ai-companion-oss}"
if [ "${SKIP_EXPORT:-0}" != "1" ]; then
  rm -rf "$OUT"
  mkdir -p "$OUT"
fi

# sed -i 在 GNU 与 BSD 上参数不同，这里统一
sed_i() {
  if sed --version >/dev/null 2>&1; then
    sed -i "$@"
  else
    sed -i '' "$@"
  fi
}

# 在当前目录所有文本文件里做正则替换；$3 可选：跳过某个相对路径
replace_all() {
  local pattern="$1" replacement="$2" skip="${3:-}"
  while IFS= read -r f; do
    if [ -n "$skip" ] && [ "$f" = "./$skip" ]; then
      continue
    fi
    sed_i -E "s#${pattern}#${replacement}#g" "$f"
  done < <(grep -rlE -- "$pattern" . 2>/dev/null | grep -v '^\./\.git/' || true)
}

echo "▸ 导出受版本控制的文件…"
if [ "${SKIP_EXPORT:-0}" = "1" ]; then
  # Windows 的 MinGit 没有 tar 时，可先用 Expand-Archive 解压 zip，再带 SKIP_EXPORT=1 运行
  [ -d "$OUT" ] || { echo "目录不存在：$OUT"; exit 1; }
  echo "  跳过导出，直接处理 $OUT"
else
  git archive HEAD | tar -x -C "$OUT"
fi

echo "▸ 删除内部文档、站点源码与商业代码…"
# 内部文档 / 私有配置
rm -rf "$OUT/docs/saas-plan.md" "$OUT/docs/saas-status.md" "$OUT/docs/HANDOFF.md" \
       "$OUT/docs/UNFINISHED.md" "$OUT/docs/qoder-prompt.md" \
       "$OUT"/docs/official-*.md
rm -f  "$OUT/PLAN.md" "$OUT/AGENTS.md" "$OUT/watermark_removed.png"
rm -f  "$OUT/deploy/linux/.env" "$OUT/.env"
# 站点源码（个人主页 + 官网）不进入开源仓
rm -rf "$OUT/site"
# SaaS 商业代码：不得开源
rm -rf "$OUT/saas" "$OUT/deploy/linux/provisioner" "$OUT/src/main/java/com/qqbot/billing"
rm -f  "$OUT/deploy/linux/docker-compose.saas.yml" \
       "$OUT/src/main/java/com/qqbot/account/ProvisionerClient.java"

echo "▸ 改写开源版 README…"
# 1) 去掉生产地址段（标题 + 列表 + 紧随的空行）
awk '
  /^## 线上地址$/ { skip=1; seen=0; next }
  skip {
    if ($0 ~ /^[[:space:]]*$/) { if (seen) { skip=0 }; next }
    seen=1; next
  }
  { print }
' "$OUT/README.md" > "$OUT/README.md.tmp" && mv "$OUT/README.md.tmp" "$OUT/README.md"

# 2) 「当前形态」整段替换为「部署要求」
awk '
  /^## 当前形态/ {
    print "## 部署要求";
    print "";
    print "| 项 | 建议 |";
    print "|---|---|";
    print "| 系统 | Linux（Ubuntu 22.04+ / Debian 12+） |";
    print "| 内存 | ≥ 2G（每多一个机器人账号约 +250M） |";
    print "| 磁盘 | ≥ 20G（guard 模型约 900M） |";
    print "| 依赖 | Docker + Docker Compose |";
    print "| 公网 | 需要 80/443（后台与扫码登录） |";
    print "";
    print "详细步骤见 [docs/deploy-guide.md](docs/deploy-guide.md)。";
    print "";
    skip=1; next
  }
  skip && /^## / { skip=0 }
  !skip { print }
' "$OUT/README.md" > "$OUT/README.md.tmp" && mv "$OUT/README.md.tmp" "$OUT/README.md"

sed_i 's/本仓为私有主仓，包含生产配置与商业方案，\*\*请勿公开\*\*。/本仓为开源版本，遵循 AGPL-3.0，欢迎 Fork。/' "$OUT/README.md"
sed_i 's/ ｜ 完整方案见 \[PLAN\.md\](PLAN\.md)//' "$OUT/README.md"
sed_i '/\[AGENTS\.md\](AGENTS\.md)/d' "$OUT/README.md"
# 开源版保留交流群入口
awk '{ print } /本仓为开源版本，遵循 AGPL-3.0，欢迎 Fork。/{ print ""; print "> 体验群：601921002" }' \
  "$OUT/README.md" > "$OUT/README.md.tmp" && mv "$OUT/README.md.tmp" "$OUT/README.md"
printf '\n官方群：601921002\n' >> "$OUT/README.md"

# 开源版 Caddyfile 只保留 :80 兜底，不带生产域名与静态站域名块
if grep -q '^:80 {' "$OUT/deploy/linux/Caddyfile" 2>/dev/null; then
  sed_i '1,/^:80 {/{/^:80 {/!d;}' "$OUT/deploy/linux/Caddyfile"
  {
    echo "# 默认只监听 80；有域名后把 :80 换成你的域名，Caddy 会自动申请 HTTPS"
    echo
    cat "$OUT/deploy/linux/Caddyfile"
  } > "$OUT/deploy/linux/Caddyfile.tmp" && mv "$OUT/deploy/linux/Caddyfile.tmp" "$OUT/deploy/linux/Caddyfile"
fi

# 开源版后台不展示备案号
sed_i '/<footer class="icp-footer">/,/<\/footer>/d' "$OUT/web/src/layouts/MainLayout.vue"
sed_i '/\.icp-footer/d' "$OUT/web/src/layouts/MainLayout.vue"

echo "▸ 删除文档里的生产域名与备案信息…"
# STATUS 头部只保留通用信息
sed_i '/^> 线上：/d; /^> 备案：/d; /^> 未完成事项：/d' "$OUT/docs/STATUS.md"
sed_i 's|^> 部署形态：.*$|> 部署形态：Linux 单机（Docker Compose）|' "$OUT/docs/STATUS.md"
# 部署指南里的「线上域名与备案」整节删除
sed_i '/^## 线上域名与备案/,$d' "$OUT/docs/deploy-guide.md" 2>/dev/null || true

# 后续替换都在快照目录内执行，绝不能碰私有工作区
cd "$OUT"

echo "▸ 脱敏替换…"
# 其余文档里出现的真实域名统一换成示例域名（教程文档保留官网链接）
replace_all 'mziran\.cn' 'example.com' 'docs/posts/warm-isle-tutorial.md' || true
replace_all '110\.42\.203\.207' '203.0.113.10' || true
replace_all 'your-server' 'your-server' || true
replace_all '100000000' '100000000' || true
replace_all '100000000' '100000000' || true
replace_all '/srv/qqbot' '/srv/qqbot' || true
replace_all '(root|ubuntu)@110' '用户@203' || true
replace_all 'menran/warm_isle' 'menran/warm_isle' || true
replace_all 'warm_isle' 'warm_isle' || true
replace_all 'git@gitee\.com:' 'https://gitee.com/' || true
replace_all 'sk-[A-Za-z0-9]{20,}' 'sk-REDACTED' || true
# 运维细节：SSH 用户名、密钥别名、登录方式
replace_all '(root|ubuntu)@' 'user@' || true
replace_all 'deploy-key' 'deploy-key' || true
replace_all '`user`' '`user`' || true
replace_all '用初始凭据登录' '用初始凭据登录' || true

echo "▸ 自检：仍可能敏感的片段"
grep -rnE "110\.42\.203\.207|100000000|100000000|your-server|/srv/qqbot|bot\.mziran\.cn|sk-[A-Za-z0-9]{20,}" . 2>/dev/null | head -10 || true

echo
echo "完成：$OUT"
echo "下一步：人工检查后，在所有者确认的前提下手动 push 到开源仓。"
