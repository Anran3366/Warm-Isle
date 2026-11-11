# Linux 部署（全云方案）

> 目标：把 NapCat + 后端 + 图片预筛全放到一台 Linux VPS 上，家里电脑可以关机。
> 适合：2C4G / Ubuntu 24.04 / 5Mbps / 40GB 的系统盘。

---

## 0. 前置

| 项 | 说明 |
|---|---|
| 服务器 | Ubuntu 22.04 / 24.04，**2C4G 起**（2C2G 跑不动 NapCat + guard） |
| 域名（可选） | 有域名才能自动 HTTPS；没有就用 IP + 强密码 |
| QQ 小号 | 建议养几天再上；机房 IP 有一定风控概率 |
| 端口 | 安全组只开 **22 / 80 / 443**，8080/3001/8081 一律不开 |

---

## 1. 装 Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER   # 重新登录后生效
docker compose version          # 需要 v2
```

---

## 2. 拉代码并构建 jar

```bash
git clone https://gitee.com/menran/warm_isle.git /srv/qqbot
cd /srv/qqbot
git checkout feat/linux          # 本分支

# 需要 Node 20 + Maven + JDK21（或直接在本机构建好再上传 target/qqbot.jar）
bash deploy/linux/build-jar.sh
```

> 构建产物只用到 `target/qqbot.jar`，`src/main/resources/static/` 里的前端产物也在 jar 内。

---

## 3. 准备图片预筛模型

`guard` 需要 3 个 ONNX 文件：`clip_vision.onnx`、`clip_text.onnx`、`nsfw.onnx`。

**方案 A（最快）**：从现在的 Windows 机器上复制：

```powershell
# Windows 上执行
scp -r E:\qqbot\guard\models\*.onnx user@<服务器IP>:/opt/qqbot/deploy/linux/data/guard/models/
scp -r E:\tools\models\hf user@<服务器IP>:/opt/qqbot/deploy/linux/data/guard/hf/
```

**方案 B**：在 Linux 上用 `python guard/export_onnx.py` 重新导出（需要 torch，约 2GB，一次性）。

---

## 4. 配置

```bash
cd /opt/qqbot/deploy/linux
cp .env.example .env
chmod 600 .env
vim .env      # 填 DEEPSEEK_API_KEY / MIMO_API_KEY / ADMIN_PASSWORD / qqbot.whitelist
```

---

## 5. 启动

```bash
cd /opt/qqbot/deploy/linux
docker compose up -d --build
docker compose ps
docker compose logs -f qqbot
```

看日志出现 `Started QqBotApplication` 即成功。

---

## 6. NapCat 首次登录

NapCat 容器启动后，用以下任一方式扫码：

```bash
# 方式一：看日志里的二维码链接
docker compose logs -f napcat

# 方式二：WebUI（需要 SSH 隧道到本机浏览器）
ssh -L 6099:127.0.0.1:6099 user@<服务器IP>
# 浏览器打开 http://127.0.0.1:6099 → 扫码
```

登录成功后，NapCat 会把会话存在 `data/napcat/`，重启不用重新扫（除非掉登录）。

> 注意：NapCat 的 OneBot WS 必须在配置里开启（端口 3001，反向 WS 或正向 WS 都行），
> 并设置 access token 与 `.env` 里的 `NAPCAT_ACCESS_TOKEN` 一致。

---

## 7. 访问后台

- 无域名：`http://<服务器IP>:8080`（安全组要先放行，**不建议长期**）
- 有域名：把 `docker-compose.yml` 里的 `caddy` 服务取消注释，`Caddyfile` 填域名，`docker compose up -d`，访问 `https://你的域名`

---

## 8. 备份

```bash
# 每天 03:00 备份到本机并保留 14 天
crontab -e
0 3 * * * cd /opt/qqbot/deploy/linux && docker compose exec -T qqbot sh -c 'ls /app/data' >/dev/null && cp data/qqbot/qqbot.db backups/qqbot-$(date +\%Y\%m\%d).db && find backups -name 'qqbot-*.db' -mtime +14 -delete
```

> 更稳妥：`rclone` 同步到对象存储，别只留在同一块盘上。

---

## 9. 排障

| 现象 | 排查 |
|---|---|
| 容器起不来 | `docker compose logs <服务名>` |
| 后台 502 | `curl 127.0.0.1:8080/api/ping` |
| 图片不处理 | `curl 127.0.0.1:8081/health`，看 `models` 是否都为 true |
| 机器人不回复 | 看 qqbot 日志里 NapCat 是否连上；`docker compose logs napcat` |
| 内存吃紧 | `docker stats`；JVM `-Xmx` 降到 384m，guard 调低阈值或用 `mem_limit` |

---

## 10. 已知差异（相对 Windows 版）

| 项 | Windows | Linux |
|---|---|---|
| 图片预筛 | DirectML（GPU，~30ms） | **CPU（~0.3–1s）**，`GUARD_USE_GPU=0` |
| 服务管理 | NSSM | systemd / docker restart |
| 备份镜像 | `F:\backup` | 自行配置（本地目录 / rclone） |
| 免扫码登录 | 未实现 | 同样未实现 |

---

## 11. 自动部署（两种方式，二选一）

### 方式 A：Windows 构建后上传（推荐，停机 10 秒）

适合"先在 Windows 验证，再上云"的流程。在 Windows 仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-to-server.ps1 -Server user@1.2.3.4
```

它会：构建 → `scp target/qqbot.jar` → 远程 `docker compose up -d --build qqbot`。

跳过构建、直接推现有 jar：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-to-server.ps1 -Server user@1.2.3.4 -SkipBuild
```

### 方式 B：服务器定时拉取（零额外容器，停机 2–3 分钟）

服务器上每 5 分钟检查一次 Gitee，有新提交就构建重启：

```bash
sudo cp /srv/qqbot/deploy/linux/systemd/qqbot-autodeploy.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now qqbot-autodeploy.timer

# 手动触发一次
sudo systemctl start qqbot-autodeploy.service
# 看日志
journalctl -u qqbot-autodeploy -f
```

脚本 `auto-deploy.sh` 的行为：

1. `flock` 防并发；
2. 有新提交 → **先停 qqbot/guard 腾内存**（2C4G 同时构建会 OOM）；
3. `git pull` → `build-jar.sh`；
4. 构建失败 → 回滚代码、恢复旧容器；
5. 启动后做 `/api/ping` 健康检查，失败则保留现场并退出非零。

> 不需要额外的"部署容器"。真要容器化 CI，可选 watchtower（约 20MB 内存），
> 但需要先把镜像推到 registry，收益不大。

