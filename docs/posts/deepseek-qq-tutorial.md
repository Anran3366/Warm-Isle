# 把 DeepSeek 接到 QQ 上：搭一个会记事、会主动找你的 AI 陪聊机器人

> 用开源项目 **暖屿 · 小满（Warm Isle）** 从零搭一个跑在自己服务器上的 QQ 陪聊机器人：
> DeepSeek 负责说话，NapCat 负责登录 QQ，中间那层"人设 + 长期记忆 + 主动关心"是暖屿 · 小满做的。
>
> 项目地址：
> - Gitee：<https://gitee.com/menran/warm_isle>（AGPL-3.0）
> - GitHub 镜像：<https://github.com/Anran3366/Warm-Isle>

---

## 为什么不用"直接调 API + 转发消息"

如果你只是想让 QQ 里有个东西能回话，网上现成的转发脚本一大把：收消息 → 拼 prompt → 调 DeepSeek → 发回去，一百行代码搞定。

但真用起来会很快遇到三个问题：

1. **它记不住你**。每次对话都是白纸，昨天说过的过敏、前天提过的考试，全靠你自己重复。
2. **它不是"一个人"**。说话没有固定语气，一会儿像客服一会儿像 GPT，深夜还在问你"早安"。
3. **它不会主动找你**。你不说话它就永远沉默——而这恰恰是"陪聊"和"问答"最大的区别。

暖屿 · 小满这套东西，本质上是把这三件事补上：**人设、记忆、主动性**。DeepSeek 只是它的"嘴"。

---

## 整体架构

```
你的手机 QQ
    │  私聊消息
    ▼
┌─────────────┐   OneBot v11    ┌──────────────┐
│ NapCat      │ ◄─────────────► │ 暖屿 · 小满（qqbot）│
│ (QQ 协议端) │   WebSocket     │ Spring Boot  │
└─────────────┘                 └──────┬───────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
              DeepSeek API        SQLite（记忆）      imageguard（图片预筛）
```

- **NapCat**：登录真实 QQ 客户端，把 QQ 的消息翻译成 OneBot v11 的 JSON；
- **暖屿 · 小满**：你自己写的业务层，负责人设、记忆抽取、主动消息、表情包、语音；
- **DeepSeek**：只负责"根据上下文生成一句话"。

协议端和业务层是解耦的——今天用 NapCat，明天想换 SnowLuma 也行，业务代码不用动。

---

## 你需要准备什么

| 项 | 说明 |
|---|---|
| 一台服务器 | Ubuntu 22.04/24.04，**2C4G 起**。2G 内存跑不动（QQ + JVM + 图片预筛） |
| 一个 QQ 小号 | 强烈建议用小号，别拿主号。机房 IP 有风控概率，先养几天 |
| DeepSeek API Key | <https://platform.deepseek.com>，充 10 块钱能聊很久 |
| 域名（可选） | 有域名才能自动上 HTTPS；没有就先 IP + 强密码，别长期裸奔 |
| 一点 Linux 基础 | 会 `ssh`、会改文件、会看 `docker logs` |

安全组只开 **22 / 80 / 443**。后台的 8080、协议端的 3001、预筛的 8081 一律不要对公网开放——它们都只绑 `127.0.0.1`，走 Caddy 反代出去。

---

## 第一步：装 Docker、拉代码

```bash
# 1. 装 Docker
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER    # 重新登录后生效
docker compose version           # 需要 v2

# 2. 拉代码到 /opt
sudo git clone https://gitee.com/menran/warm_isle.git /srv/qqbot
cd /srv/qqbot
```

## 第二步：构建 jar

暖屿 · 小满的后端是 Spring Boot，前端产物打包在 jar 里，所以要先构建一次：

```bash
# 需要 JDK 21 + Maven 3.9 + Node 20
sudo apt install -y openjdk-21-jdk maven
# Node 20 自己装一下（nvm 或 nodesource 都行）

bash deploy/linux/build-jar.sh
# 产物：target/qqbot.jar
```

> 服务器内存小、构建时容易 OOM，也可以在自己电脑上构建好，再 `scp target/qqbot.jar` 传到服务器的 `/srv/qqbot/target/`。

## 第三步：配置 `.env`

```bash
cd deploy/linux
cp .env.example .env
chmod 600 .env
vim .env
```

至少要填这几项：

```ini
# DeepSeek
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
DEEPSEEK_MODEL=deepseek-chat

# 后台密码（一定别用弱口令）
ADMIN_PASSWORD=换成一串很长的随机字符

# 机器人的 QQ 号
BOT_QQ_ID=123456789

# 谁能和它聊天，逗号分隔
QQBOT_WHITELIST=你的QQ号
```

几个容易踩的点：

- 变量名必须是 `QQBOT_WHITELIST` 这种**全大写、点换成下划线**的形式，`qqbot.whitelist` 在容器里不会生效；
- `QQBOT_WHITELIST` 留空 = 任何人私聊都会回，千万别这么上线；
- `BOT_NAME` 可以改，默认叫「小满」。

## 第四步：起容器

```bash
docker compose up -d --build
docker compose ps
```

正常应该是四个容器 running：`qqbot`、`napcat`、`imageguard`、`caddy`（还有内部的 `wsproxy`、`searxng`）。

> 图片预筛 `imageguard` 需要三个 ONNX 模型（`clip_vision.onnx` / `clip_text.onnx` / `nsfw.onnx`）。没有模型它会起不来，而没有它所有图片都会被判为敏感、一律不回。模型怎么来见 `deploy/linux/README.md`：从现成环境拷贝，或者用 `guard/export_onnx.py` 自己导出。

## 第五步：扫码登录 QQ

浏览器打开 `http://你的服务器IP/`，用 `.env` 里的 `ADMIN_PASSWORD` 登录后台。

进「机器人账号」→ 点「扫码登录」→ 后台会显示协议端容器里 QQ 的登录二维码 → 手机 QQ 扫码 → 账号状态变成「在线」。

这一步是整个流程里最容易出问题的地方，记住两个现象：

- **显示「在线」但发消息不回**：说明协议端连着、但 QQ 掉登录了。点「刷新二维码」重新扫；
- **扫码后过几小时又变离线**：多半是 QQ 把设备踢了。项目里的看门狗会每 5 分钟检查一次、掉线发邮件（需要配 `SMTP_*`），并在「连得上但没登录」时只发二维码、不反复重启容器——因为反复重启会把二维码冲掉。

## 第六步：给它一个人设

后台 →「人设」→ 新建。可以手写，也可以「从知识库生成」：

- 上传一段小说/聊天记录 → 解析类型选「生成人设」→ 它会提炼出说话风格；
- 也可以选「台词风格」，只学语气不学内容。

人设不是越详细越好。实测最影响"像不像人"的是这几条：

```
说话像发微信，短句为主，可以分几条发；
对方说得少你就更少；
不要用书面语、不要用列表、不要"作为AI"；
不确定就说"不太确定"，别编。
```

## 第七步：配记忆和主动消息

- **记忆**：每天中午自动抽取一次（时间可在后台改），把对话提炼成「事实卡」和「摘要」，下次聊天自动带上。按「机器人账号 × 聊天对象」隔离，不会串台。
- **主动消息**：默认 23:30–08:00 静默、每天最多 3 条、两条间隔不少于 2 小时。触发优先级是：跟进到期 > 早安 > 晚安 > 沉默超过 6 小时。
- **主人 QQ**：在账号页填上你自己的号，只有这个号能让机器人改昵称/换头像。

## 第八步：验证

用白名单里的号给机器人发一句：

```
在吗
```

正常会收到 1–3 条短句，而不是一大段。如果没反应，按顺序查：

```bash
docker logs qqbot --tail 50          # 业务层有没有收到消息
docker logs napcat --tail 50         # 协议端有没有掉登录
curl http://127.0.0.1:8080/api/ping  # 后端活着吗
```

---

## 可选：换成 SnowLuma 当协议端

暖屿 · 小满现在支持两种协议端，后台新建账号时可以直接选：

- **NapCat**：成熟、社区大、二维码落盘，后台直接显示；
- **SnowLuma**：较新，自带 WebUI/远程桌面，单实例能多开账号，但吃内存（单账号常驻 300MB–1.2GB）。

切换方式：账号卡片 →「切换接入」→ 选另一个 → 会重建协议端容器，**聊天记录和记忆不动**，切换后重新扫码即可。

同一条 OneBot v11 协议，业务层完全不用改。

---

## 几个真实的坑

1. **别用 `latest` 标签**。协议端发版快，某次自动拉取到坏版本，表现就是"某天开始老掉线"。把镜像 digest 钉死在 `.env` 里。
2. **内存要给够**。3.6G 的机器上，图片预筛一个人就吃 1.2G，JVM 768M，协议端 300M 起。开第二个号之前先看「关于」页的内存。
3. **后台没有 HTTPS 之前，密码是明文传输的**。要么等备案上域名（Caddy 会自动签证书），要么至少换成 32 位随机串。
4. **SQLite 要备份**。聊天记录和记忆全在 `data/qqbot/` 下的几个 `.db` 里，机器一挂就全没了。加一条 cron 每天打包到别的机器。
5. **群聊默认不处理**。当前只处理私聊——想让它进群，得自己改 `MessageDispatcher` 的路由和 @ 判定。

---

## 关于合规

- 暖屿 · 小满是 AGPL-3.0 开源项目，**可以自用、可以改、可以部署**；对外提供服务时注意 AGPL 的传染性。
- **协议端不是开源许可**：NapCat 与 SnowLuma 都明确禁止商业使用，商用需要单独授权。
- QQ 侧：这是第三方非官方客户端，**违反 QQ 用户协议**，有封号风险；请用小号、别群发、别做营销。项目仅供学习和技术研究。

---

## 最后

这套东西我跑了几个月，最深的体会是：**模型能力早就不是瓶颈了，难的是"像个人"**。

同一句「晚安」，DeepSeek 能写出二十种说法，但真正让人觉得"它记得我"的，是它知道你今天面试、知道你昨天没睡好、知道这个点你该睡了——这些都不在模型里，在记忆和时机里。

项目还在长，欢迎来提 issue 或者直接 fork：

- Gitee：<https://gitee.com/menran/warm_isle>
- GitHub：<https://github.com/Anran3366/Warm-Isle>
