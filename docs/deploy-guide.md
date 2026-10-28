# 部署与使用指南

> 适用于当前版本：Spring Boot 3 + SQLite + NapCat + SearXNG + imageguard。
> 一套完整的「QQ 陪聊机器人」：多账号、记忆、人设、表情包、语音、主动消息、每日复盘、成本统计。

---

## 一、最低配置

### 服务器

| 项目 | 最低 | 推荐 | 说明 |
|---|---|---|---|
| CPU | 2 核 | 4 核 | imageguard 的 OCR/CLIP 是 CPU 推理 |
| 内存 | **2 GB** | **4 GB** | imageguard 常驻约 1.4 GB，其余约 0.8 GB |
| 磁盘 | 10 GB | 20 GB | 模型文件约 1 GB，聊天数据增长慢 |
| 系统 | Ubuntu 22.04 | Ubuntu 24.04 | 需要 Docker 24+ |
| 带宽 | 1 Mbps | 3 Mbps | 主要消耗在图片/语音 |

> 2 GB 内存也能跑，但必须给 imageguard 留足空间，否则会被 OOM 杀掉，导致图片理解全部失败。

### 各容器内存占用（实测）

| 容器 | 用途 | 实测 | compose 上限 |
|---|---|---|---|
| `qqbot` | 后端 + 前端 + 调度任务 | 270 MB | 1200 MB（JVM 堆 768 MB） |
| `imageguard` | 图片安全预筛 | 1.36 GB | 1800 MB |
| `searxng` | 搜梗 / 搜表情 | 100 MB | 512 MB |
| `napcat` | QQ 协议端 | 125 MB | 不限制 |
| `caddy` | 反向代理 | 16 MB | 256 MB |

### 本地开发机

- Windows 10/11
- JDK 21、Maven 3.9、Node 20（脚本会自动装）
- Git

---

## 二、服务器一键部署

### 方式 A：运行脚本（推荐）

```bash
# 上传仓库后
sudo bash scripts/install-server.sh
```

脚本会依次完成：

1. 安装 Docker 与 Docker Compose 插件
2. 检查内存是否 ≥ 2 GB
3. 生成 `.env`（从 `.env.example` 复制并提示填写）
4. 构建并启动所有容器
5. 输出后台地址与下一步操作

### 方式 B：手动

```bash
# 1. 安装 Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER

# 2. 拉代码
git clone https://gitee.com/menran/warm_isle.git /srv/qqbot
cd /srv/qqbot/deploy/linux

# 3. 配置
cp .env.example .env
chmod 600 .env
vim .env          # 见下一节，至少填 3 项

# 4. 启动
sudo docker compose up -d --build

# 5. 看状态
sudo docker compose ps
sudo docker compose logs -f qqbot
```

启动后访问 `http://服务器IP`，用 `ADMIN_PASSWORD` 登录。

---

## 三、`.env` 逐项说明

文件位置：`deploy/linux/.env`（从 `.env.example` 复制）

### 必填（不填跑不起来）

| 变量 | 说明 | 示例 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 大模型密钥，聊天/抽取/复盘都用它 | `sk-xxxx` |
| `ADMIN_PASSWORD` | 后台登录密码 | 强密码 |
| `PUBLIC_BASE_URL` | 生成扫码链接用，填公网地址 | `http://1.2.3.4` |

### 选填（不填对应功能不可用）

| 变量 | 说明 | 不填的影响 |
|---|---|---|
| `MIMO_API_KEY` | 小米 MiMo，TTS 语音 + ASR 听语音 | 不能发语音、听不懂语音 |
| `MIMO_TTS_MODEL` | 默认 `mimo-v2.5-tts` | — |
| `MIMO_ASR_MODEL` | 默认 `mimo-v2.5-asr` | — |
| `MIMO_TTS_VOICE` | 音色，默认「茉莉」 | — |
| `DEEPSEEK_MODEL` | 默认 `deepseek-chat` | — |
| `VISION_MODEL` | 图片理解用的模型 | 图片只看不描述 |
| `QQBOT_WHITELIST` | 允许聊天的 QQ，逗号分隔；留空=不限制 | 见后台「可聊天的 QQ」 |
| `BOT_NAME` | 默认人设名 | 默认「小满」 |
| `SMTP_*` / `ALERT_EMAIL` | 掉线邮件告警 | 不告警 |
| `GUARD_NSFW_THRESHOLD` | 图片NSFW阈值，默认 0.85 | — |
| `GUARD_TIMEOUT_MS` | 图片预筛超时，默认 20000 | — |
| `TZ` | 时区，必须 `Asia/Shanghai` | 时间类功能会错乱 |

> ⚠️ 变量名必须用 `QQBOT_WHITELIST` 这种形式。写成 `qqbot.whitelist` 在容器里不生效（Spring 不会读）。

### 修改配置后

```bash
cd /srv/qqbot/deploy/linux
sudo docker compose up -d qqbot
```

---

## 四、登录 QQ（添加机器人账号）

1. 打开 `http://服务器IP`，输入 `ADMIN_PASSWORD` 登录
2. 左侧「机器人账号」→「添加账号」
3. 填写名称（如「小满」），选择：
   - **全新账号**：空库
   - **复制当前账号**：带记忆/人设的副本
4. 点击「查看」获取二维码
5. 用手机 QQ 扫码登录
6. 状态变成「在线」即可

> 多账号：每个账号独立 NapCat 容器、独立数据库、独立人设。端口自动分配。

### 扫码不显示

- 确认 `PUBLIC_BASE_URL` 是公网可访问地址
- 看日志：`sudo docker compose logs -f qqbot | grep 二维码`

### 删除账号

「机器人账号」→「删除」→ 选择：

- **保留数据**：只删账号和容器，数据保留，下次同 QQ 登录自动接回
- **同时删除数据**：连库一起删

---

## 五、基础使用

### 1. 设置可聊天的 QQ

「机器人账号」→ 卡片里的「可聊天的 QQ」→ 添加好友 → 保存。

留空 = 不限制（谁都能聊）。

### 2. 试运行一键放通

「设置」→「试运行 · 一键放通」打开后：

- 自动通过好友申请（含已堆积的）
- 任何好友都能直接聊天（不看白名单）
- 新好友会自动自我介绍，没回再补一个话题，仍没回就停止

稳定后建议关掉，改回白名单。

### 3. 人设

「人设」→ 新建/编辑 →「启用」。

- 每个机器人账号可以启用不同人设
- 「查看系统提示词」可看到当前完整规则
- 改动提示词要同步更新 `docs/prompt-changelog.md`

### 4. 知识库

「知识库」→ 上传文件 → 解析类型：

- `style`：说话风格
- `faq`：问答
- `persona`：人设（会创建一条人设）

### 5. 表情包

「表情包」→ 自动收集用户发的图 → 人工审核（拒绝=删除）。

机器人自己会按时段过滤（凌晨不发「早安」）。

### 6. 语音

- 主动发：在聊天里说「发条语音」
- 概率发：设置里的 50% 概率，每用户每天 3 条、间隔 90 分钟
- 听语音：对方发语音会自动转文字进上下文

### 7. 主动消息

「主动消息」→ 开关、频率、免打扰时段。

### 8. 每日复盘

每天 00:10 自动复盘前一天，给出问题与建议，可一键追加到人设。

### 9. 成本

「成本」页：今日花费、 token、按用户排行、近 7 天趋势。

> 用户维度是从 2026-10-25 之后才开始记录的，之前的数据都在 `QQ 0` 下。

### 10. 关系与心情

「关系与心情」：熟悉度等级、消息数、认识天数、当前心情。

---

## 六、搜梗与找表情（SearXNG）

`docker-compose.yml` 已内置 `searxng` 容器，启用百度/必应/搜狗。

- 配置文件：`deploy/linux/searxng/settings.yml`
- 机器人通过 `http://searxng:8080` 访问，不需要额外配置
- 每天 08:30 自动学热梗
- 用户说「来一张 X 的表情包」时现场搜索、审核、入库、发送

如果要换搜索引擎，编辑 `settings.yml` 里的 `engines`，然后：

```bash
sudo docker compose restart searxng
```

---

## 七、常用运维指令

```bash
cd /srv/qqbot/deploy/linux

# 查看状态
sudo docker compose ps
sudo docker stats --no-stream

# 日志
sudo docker compose logs -f qqbot
sudo docker compose logs -f napcat

# 重启
sudo docker compose restart qqbot

# 更新代码并重建
cd /srv/qqbot
sudo git pull
cd deploy/linux
sudo docker compose up -d --build qqbot

# 手动触发自动部署
sudo systemctl start qqbot-autodeploy

# 备份数据
sudo tar czf /root/qqbot-$(date +%F).tar.gz -C deploy/linux/data qqbot
```

### 自动部署（可选）

服务器已配置 `qqbot-autodeploy.timer`，每 5 分钟拉 Gitee 并重建。安装方式见 `scripts/`。

---

## 八、Windows 本地开发

```powershell
# 一键安装环境（Git / JDK21 / Maven / Node20）
powershell -ExecutionPolicy Bypass -File scripts\setup-windows-all.ps1

# 构建
cd E:\qqbot
mvn -DskipTests package

# 前端单独调试
cd web
npm install
npm run dev
```

> 本地不跑生产服务，生产在服务器上。

---

## 九、常见问题

| 现象 | 排查 |
|---|---|
| 页面打不开 | `docker compose ps` 看 caddy/qqbot 是否 Up；80 端口是否放行 |
| 扫码后离线 | 看 napcat 日志；账号可能被风控，换号或等一会 |
| 机器人不回 | 白名单是否包含该 QQ；是否开了放通；看 `docker compose logs qqbot` |
| 语音发不出 | `MIMO_API_KEY` 是否配置；日志搜「语音发送失败」 |
| 听不清语音 | 日志搜「获取语音失败」；NapCat 的 ffmpeg 是否正常 |
| 图片不理解 | imageguard 是否 OOM；`curl 127.0.0.1:8081/health` |
| 搜不到梗 | `docker compose logs searxng`；百度可能限流，会自动切必应 |
| 记忆只有几条 | 关于页统计的是 active；待确认的要手动通过或等自动审核 |

---

## 十、安全建议

1. 后台密码用强密码，不要用默认值
2. `.env` 权限 600，不要提交到 git
3. 不要长期开着「一键放通」
4. 定期备份 `deploy/linux/data`
5. imageguard 不要关闭，它负责把敏感图挡在本地

---

## 附：文件与端口

| 路径 | 说明 |
|---|---|
| `deploy/linux/.env` | 环境变量 |
| `deploy/linux/docker-compose.yml` | 容器编排 |
| `deploy/linux/data/qqbot` | 业务数据（bots/global.db/shared.db/voices） |
| `deploy/linux/data/napcat` | QQ 登录态 |
| `deploy/linux/searxng/settings.yml` | 搜索引擎配置 |
| `docs/prompt-changelog.md` | 提示词变更记录 |
| `CHANGELOG.md` | 全量变更日志 |

| 端口 | 用途 |
|---|---|
| 80 | 后台（Caddy） |
| 8080 | 后端（仅容器内） |
| 3001 | NapCat WebSocket（仅本机） |
| 6099 | NapCat WebUI（仅本机） |
| 8081 | imageguard（仅本机） |
| 8080(searxng) | 元搜索（仅容器网络） |

