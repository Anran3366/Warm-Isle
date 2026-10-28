# 暖屿 · 小满 · Warm Isle

> **开源说明**：公开版本在 [Gitee · warm_isle](https://gitee.com/menran/warm_isle) 与 [GitHub · Warm-Isle](https://github.com/Anran3366/Warm-Isle)。
> 开源仓由所有者**手动同步**，任何会话/脚本/CI 均不得自动推送；同步流程见
> [docs/open-source-policy.md](docs/open-source-policy.md)。
> 本仓为开源版本，遵循 AGPL-3.0，欢迎 Fork。

> 体验群：601921002


**Warm Isle（暖屿 · 小满）** 是一个自托管的 **QQ 陪聊机器人 / QQ 机器人框架**，用 **DeepSeek** 做对话模型、
**NapCat（OneBot v11）** 接 QQ。她能聊天、记得住你、发表情包、发语音条、看得懂图，还会在合适的时候主动找你，
并配了一个功能完整的网页后台：**多机器人账号**、知识库（RAG）、**语音克隆**、聊天记录检索。

适合想自己部署一个「有长期记忆的 QQ AI 女友/男友/搭子」的人，也适合想研究
**DeepSeek 接入 QQ**、**NapCat / OneBot v11 协议**、**长期记忆与主动消息设计** 的开发者。

> **名字含义**：暖（有温度、不敷衍）+ 屿（一座只属于你的小岛），后来加了机器人名「小满」做区分。
> 不追求热闹，只做一个安静陪着你的地方。英文名 **Warm Isle**。

> **命名说明**：Gitee 上另有一个同名的「暖屿 / Warm Isle」项目（2026-06 创建，FastAPI + Vue 的 AI 情感陪伴 App）。
> **本项目是跑在 QQ 上的陪聊机器人（Java / Spring Boot / OneBot v11），与它没有任何关系，也不是它的 fork。**

> 进度见 [docs/STATUS.md](docs/STATUS.md) ｜ 运维见 [docs/runbook.md](docs/runbook.md) ｜ 变更历史见 [CHANGELOG.md](CHANGELOG.md)

## 部署要求

| 项 | 建议 |
|---|---|
| 系统 | Linux（Ubuntu 22.04+ / Debian 12+） |
| 内存 | ≥ 2G（每多一个机器人账号约 +250M） |
| 磁盘 | ≥ 20G（guard 模型约 900M） |
| 依赖 | Docker + Docker Compose |
| 公网 | 需要 80/443（后台与扫码登录） |

详细步骤见 [docs/deploy-guide.md](docs/deploy-guide.md)。

## 功能

- **聊天**：DeepSeek 驱动，人设可编辑；像微信一样拆条发送、带打字延迟、有心情状态机
- **记忆**：事实卡（职业/偏好/关系/事件/承诺）+ 每日摘要 + 情感记忆（负面情绪两天后关心）
- **看图**：本地 CLIP/NSFW 预筛（敏感图不上传云端）→ 视觉模型描述 → 参与回复
- **表情包**：用户发的表情自动入库（保留 GIF）、模型打情绪/场景标签、聊天中按情绪发送
- **语音**：MiMo TTS，音色克隆（上传样本即学）、语气指令、后台可调发送概率与每日上限
- **主动消息**：早安 / 晚安 / 沉默 6 小时 / 跟进承诺 / 追问 / 停手 / 复聊抱怨
- **知识库**：上传文档 → 清洗切片 → 自动分类 → 聊天时检索 top-3 注入（夜间处理，内存保护）
- **多账号**：每个机器人号独立 NapCat 容器、独立数据库，后台可扫码登录/下线
- **话题屏蔽**：命中不走模型，支持拒绝/转移/静默
- **后台**：账号、聊天记录（按用户/时间/关键词筛选）、记忆、人设、表情、语音、知识库、推送、日志、关于

## 架构

```
                    ┌──────────────── qqbot 进程 ────────────────┐
Caddy :80 ────────> │ HTTP API（X-Bot-Id 切库）                   │
                    │ OneBotRegistry                              │
                    │   ├─ client A ──ws──> napcat-a (3001)       │
                    │   └─ client B ──ws──> napcat-b (3002)       │
                    └─────────────────────────────────────────────┘

data/
├── global.db          # 登录会话、扫码 token、账号列表
├── shared.db          # 知识库 / 表情包 / 话题屏蔽 / 后台密码（全账号共用）
└── bots/
    ├── 1.db           # 账号 1 的全部数据（消息/事实/人设/心情/主动消息）
    └── 4.db           # 账号 4
```

详见 [docs/multi-account-design.md](docs/multi-account-design.md)。

## 部署（Linux，生产）

```bash
# 1) 拉代码
git clone https://gitee.com/menran/warm_isle.git /srv/qqbot
cd /srv/qqbot

# 2) 配置
cp deploy/linux/.env.example deploy/linux/.env
vi deploy/linux/.env          # DeepSeek Key、MiMo Key、ADMIN_PASSWORD、PUBLIC_BASE_URL、QQBOT_WHITELIST

# 3) 起服务
cd deploy/linux
sudo docker compose up -d --build
```

自动部署（已配置）：`qqbot-autodeploy.timer` 每 5 分钟检查 Gitee，有新提交就重建并重启 `qqbot`。
手动触发：`sudo systemctl start qqbot-autodeploy.service`。

扫码登录机器人：后台 →「机器人账号」→ 新建/选择账号 → 扫码。

## 本地开发（Windows / macOS）

本地只需要构建工具，**不需要跑服务**（功能调试都在云端）。

```bat
:: Windows：JDK 21 + Maven + Node 20
mvn -DskipTests package                 :: 只编译后端
cd web && npm ci && npm run build       :: 只构建前端
```

前端产物若要进 jar：`scripts\build-all.bat`（前端构建 → 复制到 `src/main/resources/static` → 打包）。

## 配置项（`.env`）

| 键 | 说明 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek Key（聊天 + 视觉） |
| `DEEPSEEK_MODEL` | 默认 `deepseek-chat` |
| `NAPCAT_ACCESS_TOKEN` | OneBot WS token |
| `ADMIN_PASSWORD` | 后台初始授权码（后台可改，改后存 `shared.db`） |
| `PUBLIC_BASE_URL` | 外网地址，扫码登录用，如 `http://203.0.113.10` |
| `QQBOT_WHITELIST` | 允许聊天的 QQ，逗号分隔（按账号隔离时以后台为准） |
| `MIMO_API_KEY` / `MIMO_TTS_MODEL` | 语音合成 |
| `IMAGE_GUARD_URL` | 图片预筛地址，容器内 `http://guard:8081` |
| `REPLY_MAX_CHARS` / `REPLY_SPLIT_DELAY_MS` / `REPLY_TYPING_MS` | 拆条与打字延迟 |
| `GUARD_TIMEOUT_MS` | 预筛超时，默认 20000 |

完整示例见 [.env.example](.env.example) 与 `deploy/linux/.env.example`。

## 仓库结构

```
src/main/java/com/qqbot/   # 后端：bot / chat / memory / proactive / knowledge / voice / emoji / api
web/                       # Vue3 管理后台
guard/                     # 图片预筛（FastAPI + ONNX）
deploy/linux/              # Docker Compose、Caddyfile、systemd、自动部署脚本
scripts/                   # Windows 构建与数据库脚本
docs/                      # STATUS / api-contract / runbook / 多账号设计
```

## 文档

- [docs/STATUS.md](docs/STATUS.md) — 能力总览、接口一览、定时任务、已知限制
- [docs/api-contract.md](docs/api-contract.md) — 接口契约
- [docs/runbook.md](docs/runbook.md) — 运维手册（部署、备份、排障、改密码）
- [docs/multi-account-design.md](docs/multi-account-design.md) — 多账号设计
- [CHANGELOG.md](CHANGELOG.md) — 每次改动必须追加一条（含设备信息）

## 相关关键词

QQ 机器人 · QQ 陪聊 · QQ AI · DeepSeek API · DeepSeek 接入 QQ · NapCat · OneBot v11 · OneBot 协议 ·
AI 伴侣 · AI 女友 · 长期记忆 · 人设 / Persona · RAG 知识库 · 语音克隆 · TTS 语音条 · 表情包 ·
主动消息 · 多机器人账号 · Spring Boot · Vue 3 · SQLite · Docker

## 技术栈

Java 21 / Spring Boot 3 · SQLite（WAL） · Vue 3 + Element Plus + Vite · NapCat（OneBot v11）· DeepSeek · MiMo TTS · ONNX Runtime（DirectML / CPU）· Docker Compose + Caddy

## 文档

- [部署与使用指南](docs/deploy-guide.md)
- [提示词变更记录](docs/prompt-changelog.md)
- [变更日志](CHANGELOG.md)

官方群：601921002
