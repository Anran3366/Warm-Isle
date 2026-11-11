# 博客每日一贴

每天 10:00（Asia/Shanghai）由 systemd timer 触发，用 DeepSeek 从 `topics.json` 选一个未写过的主题，生成一篇带 FAQ 结构化数据的文章，经博客上传接口发布，并推 IndexNow。

## 部署

```bash
sudo mkdir -p /opt/warm-isle-blog/app/daily
sudo cp deploy/blog-daily/* /opt/warm-isle-blog/app/daily/
sudo chown -R blogapp:blogapp /opt/warm-isle-blog/app/daily

sudo cp deploy/blog-daily/warm-isle-daily-post.service /etc/systemd/system/
sudo cp deploy/blog-daily/warm-isle-daily-post.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now warm-isle-daily-post.timer
```

`/etc/warm-isle-blog.env` 需要：

```
DEEPSEEK_API_KEY=...
INDEXNOW_KEY=...
DAILY_MODEL=deepseek-chat
```

- `DEEPSEEK_API_KEY`：生成文章用（可复用暖屿主程序的 key）
- `INDEXNOW_KEY`：随机 32 位十六进制，博客 `/blog/indexnow.txt` 会返回它
- 手动试跑：`sudo systemctl start warm-isle-daily-post.service`
- 只看生成不发布：在 `app/daily` 下用同一环境执行 `node daily-post.mjs --dry-run`

## 去重

- `topics.json` 记录 40 个主题，`used.json` 记录已用主题；
- 发布前读取全部文章标题与 slug，标题/ slug 重复会报错；
- 池子用完后继续从全量里挑，但提示词要求换新角度，标题与 slug 仍会查重。

## SEO / GEO

- 文章 front-matter 带 `summary`、`tags`、`faq`；
- 博客渲染 `BlogPosting` + `FAQPage` JSON-LD；
- 自动生成 `/blog/llms.txt`、sitemap、RSS；
- 发布后 Ping IndexNow（Bing/Yandex 等）；
- 根 `robots.txt` 放行 GPTBot、ClaudeBot、PerplexityBot 等 AI 爬虫。
