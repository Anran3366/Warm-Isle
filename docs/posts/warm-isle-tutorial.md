# 从零搭一个会记事、会主动找你的 QQ AI 陪聊机器人

> 用开源项目 **暖屿（Warm Isle）** + DeepSeek + NapCat，把一台 2C4G 的云服务器变成 7×24 小时在线的 QQ 陪伴机器人。它能聊天、记得住你、发表情包、发语音条，还会在合适的时候主动找你。

**项目信息**

| | |
|---|---|
| 官网 | <https://web.mziran.cn> |
| Gitee（主仓） | <https://gitee.com/menran/warm_isle> |
| GitHub（镜像） | <https://github.com/Anran3366/Warm-Isle> |
| 开源协议 | AGPL-3.0 |
| 技术栈 | Java 21 · Spring Boot 3 · SQLite · Vue 3 · Docker Compose |

---

## 一、它和“调个 API 转发消息”有什么不同

如果你只是想让 QQ 里有个东西能回话，网上现成的转发脚本很多：收消息 → 拼 prompt → 调 DeepSeek → 发回去，一百行代码就能跑。

但真用起来，很快就会遇到三个问题：

1. **它记不住你。** 每次对话都是白纸，昨天说过的过敏、前天提过的考试，全靠你重复。
2. **它不是“一个人”。** 说话没有固定语气，一会儿像客服一会儿像 GPT，半夜还在问你“早安”。
3. **它不会主动找你。** 你不说话它就永远沉默——而这恰恰是“陪聊”和“问答”最大的区别。

暖屿补的就是这三件事：**人设、记忆、主动性**。模型只负责“说话”，剩下的交给业务层。

---

## 二、它能做什么

- **像微信一样聊天**：回复拆成 1–3 条短句，带打字延迟，不发小作文。
- **长期记忆**：每天中午自动把对话提炼成「事实卡 + 每日摘要」，下次聊天自动带上；按「机器人账号 × 聊天对象」隔离，不会串台。
- **主动消息**：早安、晚安、沉默 6 小时、跟进你提过的事；默认 23:30–08:00 静默，每天最多 3 条，两条间隔不少于 2 小时。
- **人设**：每个机器人账号可以启用不同人设；也能上传小说/聊天记录，自动提炼说话风格。
- **表情包**：聊天中自动收集、打标签，按情绪和时段发送；支持按关键词现场搜图。
- **语音条**：MiMo TTS 合成，支持音色克隆、概率发送、每日上限；对方发语音会自动转文字进上下文。
- **图片理解**：本地 CLIP/NSFW/OCR 预筛，通过后再送视觉模型；敏感图不下载、不上传。
- **多机器人账号**：每个号独立 NapCat 容器、独立 SQLite，可扫码、切换、下线、删除。
- **双协议端**：新建账号时可选 NapCat 或 SnowLuma，业务层不用改。
- **知识库 / 每日复盘 / 成本统计 / 关系与心情 / 后台扫码登录**。

---

## 三、整体架构

```
你的手机 QQ
    │  私聊消息
    ▼
┌─────────────┐   OneBot v11    ┌──────────────────┐
│ NapCat      │ ◄─────────────► │ 暖屿 Warm Isle   │
│ 或 SnowLuma │   WebSocket     │ Spring Boot      │
└─────────────┘                 └────────┬─────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    ▼                    ▼                    ▼
              DeepSeek API         SQLite（记忆）      imageguard（本地预筛）
```

- **协议端**：登录真实 QQ，把消息翻译成 OneBot v11 的 JSON；
- **暖屿**：负责人设、记忆、主动消息、表情、语音、图片；
- **DeepSeek**：只负责“根据上下文生成一句话”。

协议端和业务层解耦：今天用 NapCat，明天换 SnowLuma，业务代码不用动。

---

## 四、你需要准备什么

| 项 | 说明 |
|---|---|
| 服务器 | Ubuntu 22.04/24.04，**2C4G 起**（2G 能跑，但图片预筛容易 OOM） |
| QQ 小号 | 强烈建议用小号，别拿主号；机房 IP 有风控概率 |
| DeepSeek API Key | <https://platform.deepseek.com>，充 10 块钱能聊很久 |
| 域名（可选） | 有域名才能自动上 HTTPS；没有就先 IP + 强密码，别长期裸奔 |
| Linux 基础 | 会 `ssh`、会改文件、会看 `docker logs` |

安全组只开 **22 / 80 / 443**。后台 8080、协议端 3001、预筛 8081 都不要对公网开放。

---

## 五、部署：跟着复制就行

### 1. 安装 Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# 重新登录 SSH 后生效
docker compose version    # 需要 v2
```

### 2. 拉代码

```bash
sudo git clone https://gitee.com/menran/warm_isle.git /opt/warm-isle
cd /opt/warm-isle
```

> GitHub 访问不稳定时用 Gitee；两个仓库内容基本一致，以 Gitee 为准。

### 3. 构建 jar

后端是 Spring Boot，前端产物会打进 jar，所以要先构建一次：

```bash
sudo apt install -y openjdk-21-jdk maven

# Node 20
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

bash deploy/linux/build-jar.sh
# 产物：target/qqbot.jar
```

> 服务器内存小、构建容易 OOM，也可以在自己电脑上构建好，再 `scp target/qqbot.jar` 传到服务器。

### 4. 配置 `.env`

```bash
cd deploy/linux
cp .env.example .env
chmod 600 .env
vim .env
```

最少填这 5 项：

```ini
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
ADMIN_PASSWORD=换成一串很长的随机字符
PUBLIC_BASE_URL=http://你的服务器IP
QQBOT_WHITELIST=你的QQ号
TZ=Asia/Shanghai
```

几个容易踩的点：

- 变量名必须是 `QQBOT_WHITELIST` 这种**全大写、点换下划线**的形式，`qqbot.whitelist` 在容器里不生效；
- `QQBOT_WHITELIST` 留空 = 任何人私聊都会回，**千万别这么上线**；
- 想发语音条，再填 `MIMO_API_KEY`（小米 MiMo TTS/ASR）；
- 想收掉线邮件，再填 `SMTP_HOST` / `SMTP_USER` / `SMTP_PASS` / `ALERT_EMAIL`（QQ 邮箱用授权码，不是登录密码）。

### 5. 起容器

```bash
sudo docker compose up -d --build
sudo docker compose ps
```

正常会看到 `qqbot`、`napcat`、`imageguard`、`caddy` 等容器 running。

> 图片预筛需要三个 ONNX 模型（`clip_vision` / `clip_text` / `nsfw`，约 900MB）。没有模型时图片理解不可用，获取方式见 `deploy/linux/README.md`。

### 6. 扫码登录 QQ

浏览器打开 `http://你的服务器IP/`，用 `.env` 里的 `ADMIN_PASSWORD` 登录。

进「机器人账号」→「添加账号」→「扫码登录」→ 手机 QQ 扫码 → 状态变成「在线」。

两个常见现象：

- **显示在线但发消息不回**：协议端连着、QQ 掉登录了，点「刷新二维码」重扫；
- **过几小时又离线**：多半是 QQ 把设备踢了。项目自带看门狗，每 5 分钟检查一次，掉线会发邮件（需配 SMTP），并且“连得上但没登录”时只发二维码、不反复重启容器。

---

## 六、第一次使用：把人设和记忆配起来

### 1. 给它一个人设

后台 →「人设」→ 新建。可以手写，也可以上传小说/聊天记录自动提炼风格。

实测最影响“像不像人”的是这几条：

```
说话像发微信，短句为主，可以分几条发；
对方说得少你就更少；
不要用书面语、不要用列表、不要"作为 AI"；
不确定就说"不太确定"，别编。
```

### 2. 设置可聊天的 QQ

「机器人账号」→ 卡片里的「可聊天的 QQ」→ 从好友列表选择或直接输入 QQ → 保存。

留空 = 不限制，仅建议在试运行时短时间使用。

### 3. 记忆与主动消息

- **记忆**：每天 12:00 自动抽取，后台可看事实卡、摘要、待确认项；
- **主动消息**：「主动消息」页可开关、改时段和频率；
- **主人 QQ**：在账号页填你自己的号，只有这个号能让机器人改昵称/换头像。

### 4. 验证

用白名单里的号发一句：

```
在吗
```

正常会收到 1–3 条短句。如果没反应：

```bash
docker logs qqbot --tail 50          # 业务层有没有收到消息
docker logs napcat --tail 50         # 协议端有没有掉登录
curl http://127.0.0.1:8080/api/ping  # 后端活着吗
```

---

## 七、进阶玩法

### 多账号

每个账号独立容器、独立库、独立人设。后台「机器人账号」→ 添加，可选：

- **全新账号**：空库；
- **复制当前账号**：带记忆/人设的副本；
- **迁移旧账号数据**：旧号删除，数据搬过去。

单机内存有限，3.6G 的机器建议不超过 3 个号；开新号前先看「关于」页的内存。

### 切换协议端

账号卡片 →「切换接入」→ NapCat ↔ SnowLuma。会重建协议端容器，**聊天记录和记忆不动**，切换后重新扫码即可。

### 知识库

「知识库」→ 上传文件 → 选择解析类型：

- `persona`：生成人设；
- `style`：只学说话风格；
- `raw`：仅入库供检索。

解析只在每天 00:05–06:05 跑，内存低于 500MB 会自动暂停。

### 语音与表情

- 语音：配置 `MIMO_API_KEY` 后，可在聊天里说“发条语音”，也可设置概率自动发；
- 表情：聊天中收到的图会自动入库，后台审核；机器人按情绪和时段挑选发送。

---

## 八、常见问题

| 现象 | 排查 |
|---|---|
| 页面打不开 | `docker compose ps` 看 caddy/qqbot 是否 Up；80 端口是否放行 |
| 扫码后离线 | 看 napcat 日志；账号可能被风控，换号或等一会 |
| 机器人不回 | 白名单是否包含该 QQ；看 `docker compose logs qqbot` |
| 图片不理解 | imageguard 是否 OOM；模型文件是否齐全；`curl 127.0.0.1:8081/health` |
| 语音发不出 | `MIMO_API_KEY` 是否配置；日志搜「语音发送失败」 |
| 更新代码 | `git pull` → `cd deploy/linux && docker compose up -d --build qqbot` |
| 数据备份 | `tar czf qqbot-$(date +%F).tar.gz -C deploy/linux/data qqbot` |

---

## 九、安全与合规（请务必看）

1. **QQ 风控**：NapCat / SnowLuma 都是第三方非官方客户端，**违反 QQ 用户协议**，有封号风险。请用小号、别群发、别做营销，项目仅供学习与技术研究。
2. **协议端授权**：NapCat 与 SnowLuma 并非 AGPL 开源许可，**商业使用需单独获得授权**。
3. **AGPL-3.0**：你可以自用、修改、部署；如果把修改版作为网络服务对外提供，需要公开修改后的源码。
4. **后台安全**：强密码、`.env` 权限 600、不要长期开“一键放通”、尽量上 HTTPS。
5. **数据**：聊天记录和记忆都在 `deploy/linux/data/` 的 SQLite 里，务必定期备份到另一台机器。

---

## 十、项目地址

- 官网：<https://web.mziran.cn>
- Gitee：<https://gitee.com/menran/warm_isle>
- GitHub：<https://github.com/Anran3366/Warm-Isle>
- 交流群：601921002

项目还在持续更新，欢迎提 Issue、Fork，或者直接部署一套自己的。

---

## 附：各平台发布信息（可直接复制）

### 标题备选

1. 我用 Java 写了个会记事、会主动找你的 QQ AI 陪聊机器人，已开源
2. 别再只会转发 API 了：从零搭一个有人设、有记忆的 QQ 陪聊机器人
3. 2C4G 服务器 + DeepSeek，搭一个 7×24 小时的 QQ 陪伴机器人
4. 开源一个 QQ 陪聊机器人：人设、长期记忆、主动消息、语音条都有了

### 摘要（120 字内）

> 一个可自托管的 QQ AI 陪聊机器人：DeepSeek 负责说话，NapCat 负责登录，暖屿负责人设、长期记忆、主动消息、表情和语音。本文从零开始，带你用 Docker 在 2C4G 服务器上部署一套，并附完整避坑指南。项目 AGPL-3.0 开源。

### 推荐标签

`Java` `Spring Boot` `Docker` `开源` `AI` `DeepSeek` `QQ机器人` `自托管` `SQLite` `Vue3`

### 平台适配建议

| 平台 | 建议 |
|---|---|
| 掘金 | 选「开源」或「后端」分类；标题用第 1 或第 2 个；文末放官网和仓库链接 |
| CSDN | 分类选「人工智能」「Java」；表格较多，发布后检查渲染 |
| 知乎 | 用问题式标题，例如「如何从零搭建一个 QQ AI 陪聊机器人？」；开头先放效果 |
| 公众号 | 把长段落拆成 2–3 行一段；代码块截图更友好；文末放二维码/群号 |
| 小红书 | 拆成 9 图：效果截图 → 架构图 → 部署命令 → 后台截图；正文用短句 + emoji |
| B 站 | 视频简介放摘要 + 仓库链接；置顶评论放官网和部署文档 |
| V2EX | 发「分享创造」节点；标题用「分享一个自托管的 QQ AI 陪聊机器人」 |

### 配图建议

1. 手机 QQ 聊天效果（打码昵称/QQ 号）；
2. 后台仪表盘；
3. 架构图（可直接截本文的 ASCII 图，或用 draw.io 重画）；
4. 机器人账号 / 扫码页；
5. 人设与记忆页面；
6. 成本页。

> 发布前记得把截图里的真实 QQ 号、服务器 IP、域名后台地址打码；官网与仓库链接可以保留。
