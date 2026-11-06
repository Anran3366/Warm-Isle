# 多账号（多 QQ 号同时在线）设计

> 目标：多个机器人 QQ 号同时在线，各自独立的会话、记忆、人设、主动消息。
> 状态：已实现（2026-10-23）。阶段 1~3 全部上线，详见 docs/STATUS.md。

**运行现状（2026-10-23）**

| 项 | 现状 |
|---|---|
| 部署 | 云服务器 `203.0.113.10`，Docker Compose，Caddy 反代 80 → qqbot:8080 |
| 账号 | 后台「机器人账号」新建/扫码/下线；每个账号一个 `napcat-<id>` 容器 |
| 数据库 | `global.db`（会话/账号）+ `shared.db`（知识库/表情/话题/密码）+ `bots/<id>.db` |
| 主动消息 | 调度遍历所有账号，在各自 `BotContext` 内判断与发送 |
| 后台切换 | 前端每个请求带 `X-Bot-Id`，缺省落到默认账号 |
| 已知限制 | 无 `X-Bot-Id` 的手动接口调用会落默认库；单机 3.6G 建议不超过 3 个账号 |

## 1. 总体架构

```
                ┌─────────────── qqbot 进程 ───────────────┐
Caddy :80 ─────>│ HTTP API（按 X-Bot-Id 切库）              │
                │                                          │
                │ OneBotRegistry                           │
                │   ├─ client A ──ws──> napcat-a (3001)    │
                │   ├─ client B ──ws──> napcat-b (3002)    │
                │   └─ client C ──ws──> napcat-c (3003)    │
                └──────────────────────────────────────────┘
```

- 每个机器人账号一个独立的 **NapCat 容器**（独立端口、独立数据目录）；
- qqbot 进程内维护多条 WebSocket，消息按账号路由；
- **数据隔离用「每个账号一个 SQLite 文件」**，而不是给所有表加 `bot_id` 列：
  - 改动面小，查询语句完全不用动；
  - 天然隔离，不会串台；
  - 备份/迁移按账号粒度进行。

## 2. 数据布局

```
data/
├── global.db            # 全局：登录会话、扫码 token、账号列表
└── bots/
    ├── 100000000.db    # 账号 A 的全部数据（消息/事实/人设/知识库/心情…）
    ├── 3981234567.db    # 账号 B
    └── ...
```

- `global.db` 表：`auth_sessions`、`login_qr`、`bot_accounts`
- `bots/<qq>.db` 表：其余全部（沿用现有 schema）
- 迁移：启动时若 `bots/` 为空且存在旧的 `qqbot.db`，自动复制为 `bots/<默认账号>.db`

`bot_accounts` 结构：

```sql
CREATE TABLE bot_accounts (
  qq_id INTEGER PRIMARY KEY,
  name TEXT,
  container TEXT NOT NULL,     -- napcat-<qq>
  ws_port INTEGER NOT NULL,
  ui_port INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1
)
```

## 3. 上下文传递

- `BotContext`（ThreadLocal）保存当前 botId；
- HTTP：`BotContextFilter` 读取请求头 `X-Bot-Id`，设置后清理；
- WebSocket：各 `OneBotClient` 在分发事件前 `BotContext.set(botId)`；
- 异步：`MessageDispatcher` 的线程池任务在提交前捕获 botId，执行时重新 set；
- 定时任务：遍历账号列表，逐个 `BotContext.run(botId, ...)`。

## 4. 后端改造清单

| 模块 | 改动 |
|---|---|
| `Db` | 按 `BotContext` 选择 `bots/<id>.db`；首次打开懒建表；启动迁移旧库 |
| `GlobalDb` | 新增，存放 auth/login_qr/bot_accounts |
| `AuthService` / `QrLoginService` | 改用 `GlobalDb` |
| `OneBotClient` | 从单例 Bean 改为工厂创建，持有 botId 与 wsUrl |
| `OneBotRegistry` | 管理 Map<qq, client>，启动时按 `bot_accounts` 连接，支持动态添加/移除 |
| `MessageDispatcher` | `onEvent(botId, event)`，异步任务带上下文 |
| `AccountService` | 创建/启动/停止 NapCat 容器（Docker API），分配端口 |
| 定时任务 | 主动消息、记忆抽取、知识库处理按账号循环 |
| 备份脚本 | 改为打包 `global.db` + `bots/` |

## 5. 前端改造清单

| 项 | 改造 |
|---|---|
| API 客户端 | 所有请求带 `X-Bot-Id`（来自 localStorage） |
| 顶栏 | 账号切换器（复用现有用户切换器位置） |
| 机器人账号页 | 列表 + 添加（新建容器）/ 下线 / 切换 |
| 聊天记录 / 记忆 / 人设 / 情绪 | 跟随当前账号，切换后自动刷新 |
| 知识库 / 表情包 / 语音 | 跟随当前账号（各自独立） |

## 6. 资源估算

| 账号数 | NapCat 内存 | qqbot | guard | 合计 |
|---|---|---|---|---|
| 1 | ~250MB | ~500MB | ~1.1GB | ~1.9GB |
| 3 | ~750MB | ~550MB | ~1.1GB | ~2.4GB |

3.6GB 机器安全上限约 **3 个账号**（需保留缓冲）。

## 7. 实施顺序

1. **数据隔离**：`GlobalDb` + `Db` 按上下文切库 + 旧库迁移（1 天）
2. **运行时多连接**：`OneBotClient` 改造 + `OneBotRegistry`（1 天）
3. **容器编排**：动态创建 NapCat、端口分配、共享二维码目录（1 天）
4. **前端多账号**：请求头、切换器、账号页（1 天）
5. **回归与上线**：单账号行为不变，逐步添加第二账号验证（0.5 天）

## 8. 风险

- **消息路由错误**：回复发到错误的号 → 上下文传递必须显式，禁止依赖全局变量；
- **内存**：第 3 个账号起要观察，必要时加 swap 或降 guard 并发；
- **PWA 缓存**：每次前端发布后需硬刷新；
- **旧数据迁移**：迁移前自动备份 `qqbot.db`，失败则拒绝启动并保留原库。
