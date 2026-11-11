# 插件版生产上线记录（2026-11-10）

> 与 `plugin-staging-deploy.md` 的区别：本文记录**直接上生产**的实际步骤。
> staging 方案已建但未启用（`/opt/qqbot-staging` 保留，容器已停）。

## 1. 上线方式

不新开测试账号，直接用现有 QQ 账号（小满）测。做法是把插件版 jar 换进生产镜像，
`PLUGIN_ENABLED=true` 打开链路，其余配置一律不动。

- 只重建并重启 `qqbot` 一个容器；`napcat` / `snowluma-1` / `wsproxy` / `caddy` 未重启
- 停机窗口约 10 秒，期间事件由 `wsproxy` 缓冲，恢复后补发，不丢消息
- `docker compose up -d qqbot` 会顺带重启 `guard`（`depends_on` 导致），无用户可见影响

## 2. 实际执行步骤

```bash
# 0. 备份（必须）
cd /srv/qqbot
sudo cp target/qqbot.jar target/qqbot.jar.pre-plugin
sudo cp deploy/linux/.env deploy/linux/.env.pre-plugin

# 1. 换 jar（本地构建产物）
scp target/qqbot.jar user@<server>:/tmp/qqbot-new.jar
sudo cp /tmp/qqbot-new.jar /srv/qqbot/target/qqbot.jar

# 2. 打开插件开关（.env 被 qqbot 服务 env_file 引用）
sudo tee -a /srv/qqbot/deploy/linux/.env <<'EOF'

# ── 插件（M1 data 型）────────────────────────────────
PLUGIN_ENABLED=true
PLUGIN_DIR=/app/data/plugins
EOF

# 3. 重建并只重启 qqbot
cd /srv/qqbot/deploy/linux
sudo docker compose build qqbot && sudo docker compose up -d qqbot
```

启动日志应出现：`插件注册表已加载: 0 个，意图索引 0 条`（此时还没装包）。

## 3. 装包与启用

```bash
# 登录拿 Cookie（后台是 httpOnly Cookie，不是 Bearer token）
PW=$(sudo grep '^ADMIN_PASSWORD=' /srv/qqbot/deploy/linux/.env | cut -d= -f2-)
curl -s -c /tmp/pj.txt -X POST -H 'Content-Type: application/json' \
  -d "{\"password\":\"$PW\"}" http://127.0.0.1:8080/api/auth/login

# 安装 → 启用（botId=1 是小满）
curl -s -b /tmp/pj.txt -X POST -F "file=@menran.sing-1.0.0.zip" \
  http://127.0.0.1:8080/api/plugins/install
curl -s -b /tmp/pj.txt -X POST \
  "http://127.0.0.1:8080/api/plugins/menran.sing/enable?botId=1"
```

成功标志：日志 `插件注册表已加载: 1 个，意图索引 6 条`，
落盘 `/srv/qqbot/deploy/linux/data/qqbot/plugins/menran.sing/1.0.0/`。

## 3.1 商店验签（不配就看不到商店）

商店的 `index.json` / `revocations.json` 用 Ed25519 签名，宿主默认 `PLUGIN_STORE_SIGNATURE_REQUIRED=true`。
**公钥为空时商店响应会被 fail-closed 拒绝**，后台商店页只会显示不可用。

```bash
# 从商店拿公钥（SPKI DER 的 base64）
curl -s https://store.example.com/api/v1/pubkey

# 写进实例 .env，再 up -d qqbot
sudo tee -a /srv/qqbot/deploy/linux/.env <<'EOF'
PLUGIN_STORE_PUBLIC_KEY=<上面的 base64>
PLUGIN_STORE_SIGNATURE_REQUIRED=true
EOF
```

自建商店换自己的公钥；只做本地 zip 安装时可把 `PLUGIN_STORE_URL` 置空，不配公钥也不影响本地安装。

## 4. 回滚（1 分钟内）

```bash
cd /srv/qqbot
sudo cp target/qqbot.jar.pre-plugin target/qqbot.jar
sudo cp deploy/linux/.env.pre-plugin deploy/linux/.env
cd deploy/linux && sudo docker compose build qqbot && sudo docker compose up -d qqbot
```

只想停插件、不想回滚：后台把插件开关关掉即可（行为立刻回到改造前），
或把 `.env` 里 `PLUGIN_ENABLED` 改成 false 后 `up -d qqbot`。

## 5. 必须知道的坑

1. **自动部署已关闭**：`qqbot-autodeploy.timer` 当前为 disabled，不会覆盖线上。
   但仍要注意：生产跑的是手动构建的 jar，不是 Gitee main 自动源码。
2. **`seedDefaultAccount()` 硬编码过 `ws://napcat:3001`**。多实例部署时新实例会连到
   生产的 NapCat，已改为读 `NAPCAT_CONTAINER` / `NAPCAT_INNER_WS_URL`（默认值不变）。
3. **音频走 base64**，不依赖 qqbot 与 NapCat 共享卷；单条上限 1MB（base64 后约 1.4MB），`wsproxy` 128m 够用。
4. 插件语音与日常语音**共用**每日 3 条额度，且执行前预占，失败释放。


## 6. 2026-11-10 更新（插件商店 v2.3）

- 商店：`store.example.com`，开放发布 + A 集中审核；实例后台「上架插件 / 上架审核」；无作者账号。
- 实例：固定官方商店地址与公钥，验签强制开启；审核/发布代理走 `PLUGIN_STORE_ADMIN_URL`（仅 A）。
- 插件：唱歌包 1.0.2，补「我要听歌」「想听歌」示例；商店、实例均为 1.0.2。
- 更新可见性：已安装列表可升级、changelog、导航角标；hosted/local `/health` 版本巡检（30 分钟）。
- 密钥轮换：`scripts/rotate-key.mjs` + `/api/v1/keys/rotation`，实例自动采纳。
- 协议端：NapCat 与 SnowLuma 同级，按账号任选，没有默认主协议端。
- 详细设计：`docs/plugin-store-v2.3.md`；作者手册：`docs/plugin-dev-manual-v2.3.md`。
