# 运维手册（云端 Linux + Docker）

> 生产环境：`203.0.113.10`（腾讯云 `your-server`，Ubuntu 26.04）
> 代码目录：`/srv/qqbot`，部署目录：`/srv/qqbot/deploy/linux`
> 本地 Windows 机器只用于改代码 / 构建，不跑服务。

## 1. 组件与位置

| 组件 | 位置 / 端口 | 说明 |
|---|---|---|
| 公网入口 | `http://203.0.113.10:80` | Caddy 反代到容器 `qqbot:8080` |
| 后端 + 后台 | 容器 `qqbot` → 8080 | Spring Boot，jar 在 `/app/app.jar` |
| 图片预筛 | 容器 `imageguard` → 8081 | FastAPI + ONNX（CPU） |
| NapCat | 容器 `napcat`、`napcat-<id>` | 每个机器人号一个，OneBot WS 3001/3002… |
| 数据 | `deploy/linux/data/qqbot/` | `global.db` / `shared.db` / `bots/*.db` / emoji / voice / knowledge |
| 日志 | `deploy/linux/data/logs/` | 容器日志 + 应用日志，30 天归档 |
| 配置 | `deploy/linux/.env` | 不入库；改完需重启 `qqbot` |
| 部署 | `deploy/linux/docker-compose.yml` | `caddy` / `qqbot` / `imageguard` / `napcat` |
| 自动部署 | systemd `qqbot-autodeploy.timer` | 每 5 分钟拉 Gitee，有变更就重建 |
| 看门狗 | systemd `napcat-watchdog.timer` | 每 5 分钟检查掉线，二维码邮件告警 |

SSH：`ssh user@203.0.113.10`（已配置密钥免密；`user` 可免密 sudo）。

## 2. 日常操作

```bash
cd /srv/qqbot/deploy/linux

sudo docker compose ps                        # 容器状态
sudo docker compose logs -f --tail=100 qqbot  # 后端日志
sudo docker compose restart qqbot             # 重启后端（改 .env 后）
sudo docker compose up -d --build qqbot       # 改代码后重建（一般自动部署会做）

curl -s http://127.0.0.1:8080/api/ping         # 探活
curl -s http://127.0.0.1:8081/health           # 预筛健康
```

查看数据：

```bash
D=/srv/qqbot/deploy/linux/data/qqbot
sudo sqlite3 $D/bots/1.db 'select count(*),max(created_at) from messages;'
sudo sqlite3 $D/bots/1.db 'select * from proactive_log order by id desc limit 5;'
sudo sqlite3 $D/shared.db 'select key,value from settings;'
```

## 3. 部署流程

**正常流程**：本地改代码 → `git push` → 服务器 5 分钟内自动拉取、构建、重启。

```bash
# 立刻触发一次自动部署
sudo systemctl start qqbot-autodeploy.service
sudo journalctl -u qqbot-autodeploy -f
```

**手动全量部署**（首次或排障）：

```bash
cd /srv/qqbot && sudo git pull
cd deploy/linux && sudo docker compose up -d --build
```

注意：重建 `qqbot` 期间后台会 502 约 1–2 分钟。

## 4. 多账号与扫码

- 后台 →「机器人账号」：新建账号（自动创建 `napcat-<id>` 容器与端口）→ 点「扫码登录」
- 扫码地址基于 `PUBLIC_BASE_URL`，手机打开 `http://203.0.113.10/#/m/<token>` 输入授权码即可
- 每个账号独立数据库 `bots/<id>.db`，独立白名单、独立主动消息状态
- 下线：账号页「下线」；删除：连容器与数据一起删（谨慎）
- 掉线告警：看门狗每 5 分钟检查，连续掉线发邮件（`SMTP_*` 配置），最多 5 次

## 5. 数据与备份

**关键三件套，缺一不可**：

```bash
cd /srv/qqbot/deploy/linux/data/qqbot
sudo tar czf /tmp/qqbot-backup-$(date +%F).tar.gz global.db shared.db bots/
```

- `global.db`：登录会话、账号列表
- `shared.db`：知识库 / 表情库 / 话题屏蔽 / 后台密码哈希
- `bots/*.db`：每个机器人号的全部业务数据

恢复：

```bash
cd /srv/qqbot/deploy/linux
sudo docker compose stop qqbot
sudo tar xzf /tmp/qqbot-backup-2026-10-23.tar.gz -C data/qqbot
sudo docker compose start qqbot
```

## 6. 日志

```bash
sudo docker logs qqbot --since 30m | grep -Ei 'error|proactive'
ls -lh /srv/qqbot/deploy/linux/data/logs/
```

- 后台「日志」页可按来源/级别/尾行数查看
- 文件日志保留 30 天，自动归档

## 7. 图片预筛

- 容器 `imageguard`，`/health` 探活，`/check` 检测
- 后端 `IMAGE_GUARD_URL=http://guard:8081`，超时 `GUARD_TIMEOUT_MS=20000`
- 模型在 `deploy/linux/data/guard/models/`：`clip_vision.onnx` / `clip_text.onnx` / `nsfw.onnx`
- 命中敏感直接拦截，图片不上传云端；超时按「未检测」处理并记日志
- 「所有图片都被判敏感」：先看 `docker logs imageguard` 和 `/health`

## 8. 管理后台登录

- 初始密码来自 `deploy/linux/.env` 的 `ADMIN_PASSWORD`
- 登录：`POST /api/auth/login` → 种 HttpOnly cookie（7 天）
- 除 `login`、`/api/ping`、`/api/auth/qr*` 外，所有 `/api/**` 都要登录
- **改密码**：后台「设置 → 登录密码」，需当前密码；新密码加盐 SHA-256 存 `shared.db` 的 `settings.admin.passwordHash`，改完旧会话全部失效（当前浏览器自动换新 cookie），重启不丢
- **忘记密码**
  1. 删自定义密码，回落到 `.env`：
     ```bash
     sqlite3 /srv/qqbot/deploy/linux/data/qqbot/shared.db \
       "delete from settings where key='admin.passwordHash';"
     sudo docker compose restart qqbot
     ```
  2. 或直接改 `.env` 的 `ADMIN_PASSWORD` 并重启（仅在没设过自定义密码时生效）

## 9. 聊天记录与主动消息

**聊天记录页**
- 用户筛选：全部用户 / 指定 QQ（`GET /api/messages?qqId=0` 表示全部）
- 时间筛选：今天 / 近 3 天 / 近 7 天 / 近 30 天 或自定义起止（后端 `from`/`to` 生效）
- 关键词：在已加载结果里本地过滤并高亮
- 每条用户消息上方标注 `QQ xxx`，头像显示号码后 4 位

**主动消息**
- 每 60 秒检查一次，**逐个机器人账号**在各自上下文里判断与发送，计数/停手按号隔离
- 触发优先级：跟进到期 > 早安(08:30–09:30) > 晚安(22:30–23:25) > 沉默(≥6h，09:00–23:00)
- 硬限制：23:30–08:00 静默、每天 ≤3 条、两条间隔 ≥2 小时；发 1 条不回 6h 追问，累计 12h 停手
- 手动测试（绕过时间窗，仍需对应 NapCat 在线）：
  ```bash
  curl -b cookies.txt -X POST http://127.0.0.1:8080/api/push/test \
    -H "Content-Type: application/json" -d '{"type":"silence"}'
  ```

## 10. 常见故障

| 现象 | 排查 |
|---|---|
| 后台 502 | `docker compose ps`；`docker logs qqbot --tail=100`；重建期间短暂 502 属正常 |
| 机器人不回消息 | `docker logs qqbot | grep -i napcat`；确认对应 `napcat-<id>` 在线 |
| 扫码页空白 | `PUBLIC_BASE_URL` 是否配置；前端是 hash 路由，链接必须带 `/#/m/` |
| 图片全被拦截 | `imageguard` 是否 healthy；`GUARD_TIMEOUT_MS`；模型文件是否齐全 |
| 主动消息不发 | 静默期？当天满 3 条？距上条 <2 小时？用户已停手？ |
| 内存不足 | 关于页看内存；减少账号；夜间知识库解析 <500MB 时自动暂停 |
| 数据落错库 | 手动调用接口没带 `X-Bot-Id` 时会落到默认号库，属已知限制 |

## 11. 附录：本地 Windows 构建机

只保留 JDK 21 / Maven / Node 20（`E:\tools`），用于改代码后本地编译验证：

```bat
cd E:\qqbot
mvn -DskipTests package
cd web && npm ci && npm run build
```

不要再在本地注册 NSSM 服务或跑 NapCat——运行时统一在云端。

## 12. NapCat 稳定性（掉线排查）

### 先分清三种“断”

```bash
# a) 容器是否被 OOM 杀过
sudo docker ps -a --filter name=napcat --format '{{.Names}}\t{{.Status}}'
sudo dmesg -T | grep -i "killed process" | tail -5

# b) NapCat 侧的掉线原因
sudo docker logs napcat --since 6h 2>&1 | grep -Ei "error|kick|offline|logout|login" | tail -40

# c) 接入层是否在自我修复
sudo docker logs qqbot --since 6h 2>&1 | grep -Ei "探测失败|主动断开|补发|已连接 NapCat|连接关闭" | tail -40
```

- 出现 `主动断开 NapCat 连接以触发重连` → 半开连接已自愈，属正常保护
- 出现 `重连后补发 N 条离线消息` → 断线期间的回复被补发了
- 出现 `心跳静默` → NapCat 假在线（连接开着但不再收消息）

### 把 NapCat 版本钉死（强烈建议）

`latest` 是浮动标签，NapCat 发版快，自动 pull 到坏版本会表现为“某天开始老掉线”。

```bash
# 1) 取当前跑得好好的镜像 digest
sudo docker inspect --format '{{index .RepoDigests 0}}' napcat
#   mlikiowa/napcat-docker@sha256:xxxx

# 2) 写进 deploy/linux/.env
NAPCAT_IMAGE=mlikiowa/napcat-docker@sha256:xxxx

# 3) 让配置生效（只是把镜像引用固定下来，不会重新拉取）
cd /srv/qqbot/deploy/linux && sudo docker compose up -d napcat
```

升级时改这一行；出问题改回上一行。

### 后台看到的连接状态

`GET /api/status` 的 `bot` 段：

| 字段 | 含义 |
|---|---|
| `online` / `loggedIn` | QQ 是否真的登录（探活拿到真实 QQ 号才算） |
| `napcat` | WebSocket 是否通；等扫码时 `napcat=true` 而 `loggedIn=false` |
| `heartbeatSeen` | NapCat 是否在发心跳（false = 没开心跳，此时不做心跳判活） |
| `heartbeatAgoSec` | 距上次心跳多少秒；`-1` 表示没收到过 |
| `outbox` | 断线重试队列里的待发文本条数（最多 20 条，超过 60 秒丢弃） |

> 2026-10-26 之前的版本里 `online` 等价于“WS 能通”，NapCat 停在扫码页时会误报在线，
> 看门狗因此从不告警。现在 `online` 只认真正登录。

### 看门狗的两条路径

| 状态 | 动作 |
|---|---|
| `napcat=false`（连不上） | 重启 NapCat（5/10/20/40 分钟退避），重启后快速登录成功则清零 |
| `napcat=true` 但 `loggedIn=false`（等扫码） | **不重启**，只抓当前二维码发邮件——重启会把二维码冲掉 |

### 已知边界

- 补发只覆盖**文本**；图片、语音、表情在断线期间不补发
- 看门狗退避为 5/10/20/40 分钟，连续 5 次后只重启不再发邮件
- `napcat` 容器限 1G 内存；多账号实例同样限 1G（`AccountService.dockerCreate` 的 `Memory`）
