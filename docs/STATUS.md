# 项目现状（STATUS）

> 最后更新：2026-11-10 17:00
> 部署形态：Linux 单机（Docker Compose）

## 一、能力总览

| 模块 | 状态 | 说明 |
|---|---|---|
| 多机器人账号 | ✅ | 每个账号独立协议端容器（NapCat 或 SnowLuma）、独立数据库；可添加/扫码/切换/下线/删除 |
| 人设 | ✅ | 按机器人账号隔离；支持从知识库小说自动生成 |
| 记忆（事实卡 + 摘要） | ✅ | 按「机器人 × 聊天对象」隔离；facts 自 v2 起只读保留 |
| 记忆 v2（memories） | ✅ | 四类记忆 + 检索注入 + 00:05 夜间整理 + 主动回忆 + 连续遗忘曲线（00:02 独立衰减）/ 复习强化 / 生日钉住与当天祝贺（world 暂无写入来源，等 P4） |
| 记忆星图 | ✅ | 后台 `/memory/star`：三旋臂星系，默认**所有聊过天的人**（可按人筛选），强度决定半径，悬停高亮一跳链路；接口 `/api/memories/graph`、`/api/memories/graph/users`；已改为不限条数、全部记忆按强度铺满盘面 |
| 记忆语义召回 | ✅ | `imageguard /embed`（bge-small-zh-v1.5，512 维）生成向量存 `memory_vectors`，关键词相关度与余弦融合（cos<0.45 当噪声），向量服务挂掉自动退回纯关键词；记忆块收敛：只注入真命中、同值去重、旧负面事件降权、不注入空块 |
| 提示词动态拼装 | ✅ | 人设切片 + 规则按场景注入；规则重写为 6 块（-48%），system 由约 2700 字降到 750–1260 字；变更记录见 `docs/prompt-changelog.md` |
| 回复指引库 | ✅ | 45 个常见场景（生病/加班/失眠/失恋/被催婚/缺钱…）各一条「怎么接 + 别怎么接」，入库为 `parse_type=raw`，检索时关键词粗排 + 向量重排（余弦 <0.40 丢弃） |
| 风格语料 | ✅ | `muice-style.txt` 620 条 + `lccc-style.txt` 4000 条，共 4620 条 style；只做台词风格轮换，不参与知识检索 |
| 官网统计接口 | ✅ | `GET /api/public/memory-stats`（免登录、60 秒缓存、只返回聚合数字）给官网星图实时取数量；CORS 只放开 `/api/public/**` |
| 相关文章（blog） | ✅ | `web.example.com/blog/`：论坛式列表 + MD 阅读页；密码上传（front-matter 解析）；阅读量（同 IP 同日去重）；sitemap/RSS/JSON-LD。服务 `warm-isle-blog`（Node，127.0.0.1:3300，专用用户 blogapp）；掘金连载 12 篇 + 手册/项目介绍 21 篇，共 33 篇 |
| 博客每日更新 | ✅ | 每天 10:00 systemd timer 触发：DeepSeek 按 40 个主题池生成新文章（标题/slug 查重）→ 博客上传 → IndexNow 推送；文章带 FAQPage JSON-LD，`/blog/llms.txt` 与 AI 爬虫放行 |
| 插件商店 | ✅ | 官方商店 A 审核分发，B/C/D 只读已审核索引；data/prompt/hosted/local 四类 runtime；Ed25519 验签 + 密钥轮换；已安装可升级、changelog、hosted/local 服务版本巡检；唱歌包 1.0.2 |
| 主动消息 | ✅ | 按「机器人 × 聊天对象」；早安/晚安/沉默/跟进/情感记忆 |
| 知识库 | ✅ 公用 | 所有账号共享；支持生成人设 / 台词风格 / 仅入库 |
| 表情包 | ✅ 公用 | 自动学习与发送 |
| 语音条 | ✅ 公用配置 | 音色、语速、概率所有账号一致；克隆音色共享；聊天里模型可标 `[[语音]]`，按概率发（点名 0.9 / 标记 0.7 / 日常 0.2） |
| 话题屏蔽 | ✅ 公用 | 预置色情/赌博/毒品/政治敏感四类关键词 |
| 图片理解 | ✅ | 本地 guard 预筛 + 云端视觉模型 |
| 后台登录 | ✅ | 扫码 + 手机授权码；密码登录保留 |
| 日志 | ✅ | 落盘 `logs/service.log`，按天切分、保留 30 天 |
| 关于页 | ✅ | 服务器内存/JVM/实例状态，5 秒实时刷新 |
| 开源发布 | ✅ | Gitee `warm_isle`，AGPL-3.0，单提交快照、手动同步 |

## 二、数据布局

```
data/
├── global.db     # 登录会话、扫码 token、机器人账号列表
├── shared.db     # 知识库、表情包、话题屏蔽（所有账号共用）
└── bots/
    ├── 1.db      # 账号 1 的全部业务数据
    └── <id>.db   # 账号 2、3…
```

- 账号 id 从 1 自增；`1` 是默认账号，不可删除；
- 每个账号的 `bots/<id>.db` 存：消息、事实卡（只读）、摘要、人设、白名单、心情、情感记忆、主动消息状态、用量，
  以及 v2 的 `memories`（记忆）、`memory_vectors`（记忆语义向量，由 imageguard `/embed` 生成）与 `episodes`（每日整理产物）。

## 三、接口一览（详细见 docs/api-contract.md）

### 认证
- `POST /api/auth/login` 密码登录
- `POST /api/auth/qr/new` 生成登录二维码会话
- `GET /api/auth/qr/{token}` 查询状态
- `POST /api/auth/qr/{token}/approve` 手机提交授权码
- `POST /api/auth/qr/{token}/claim` PC 领取会话
- `POST /api/auth/password` 修改后台授权码（加盐哈希存 shared.db，改后旧会话失效）

### 机器人账号
- `GET /api/account/list` 列表（含在线状态、QQ 号）
- `POST /api/account/create` 新建独立实例
- `GET /api/account/{id}` 单个详情
- `GET /api/account/{id}/qrcode` 登录二维码
- `POST /api/account/{id}/logout` 下线
- `DELETE /api/account/{id}` 删除（含容器与数据）
- `GET/PUT /api/account/{id}/whitelist` 该号可聊天的 QQ

### 业务
- `/api/personas` `/api/facts` `/api/summaries` `/api/push/*` `/api/emoji/*` `/api/voice/*`
- `/api/knowledge/*`（上传支持 `parseType=persona|style|raw`）
- `/api/topic-block` `/api/settings` `/api/usage` `/api/logs` `/api/system`
- `/api/messages` 聊天记录：`qqId=0` 看全部用户、`from/to` 按时间过滤；`/api/messages/users` 返回出现过的 QQ

> 所有业务接口通过请求头 `X-Bot-Id` 指定机器人账号，缺省为 1。

## 四、定时任务

| 任务 | 时间 | 说明 |
|---|---|---|
| 知识库解析 | 每天 00:05–06:05 | 只在窗口内跑；可用内存 <500MB 自动暂停 |
| 夜间记忆整理 | 每天 00:05 | 昨天的消息 → episode + 记忆打分（≥0.65 排主动回忆）；内存 <500MB 暂停 |
| 记忆衰减 | 每天 00:02 | 遍历记忆库所有用户，按遗忘曲线推进 strength，<0.15 归档；pinned 不衰减 |
| 记忆抽取 | 每天 12:00 | 事实卡 + 摘要 |
| 主动消息 | 每 1 分钟检查 | 按配置的时段与频率；**逐个机器人账号**在自己的上下文里判断与发送；含记忆到点的主动回忆 |
| ~~看门狗~~ | 每 5 分钟 | **已停用（2026-10-28）**：账号 1 改用 SnowLuma，NapCat 实例空闲；`napcat-watchdog.timer` 已 disable |
| 自动部署 | 每 5 分钟 | 仅代码变动才重建 |
| 日志归档 | 每天 | 保留 30 天 |

## 五、已知限制

- 单机 3.6G，安全上限约 3 个机器人账号；
- 前端用 hash 路由（`/#/...`），扫码地址必须带 `#`；
- PWA Service Worker 会缓存旧页面，部署后需硬刷新；
- 自动部署重建期间页面会 502 约 1.5 分钟；
- 语音条有 4 小时冷却与每日上限（每日上限可配，冷却暂不可配）；
- 后台无 `X-Bot-Id` 头的请求（如手动测试接口）会落到默认 QQ 号库，可能和账号库不一致；
- 聊天记录页的时间筛选在后端生效，关键词筛选在前端本地过滤；
- 记忆 v2 的后台页仍展示只读的 facts；memories 的列表仍只有 API，星图页（`/memory/star`）走 `/api/memories/graph`；
- 记忆主动回忆受主动消息的免打扰时段约束：当前 `push.quiet` 为 00:30–07:00；解除静默的瞬间会补发积压的旧跟进（2026-10-28 实测 4 秒内给 6 个用户各发 1 条），属于既有逻辑；
- 可行动性阈值已从 0.7 下调到 0.65（宁可多问一句）；误报率待观察；
- `facts` 与 `memories` 目前双写，facts 只读保留，尚未停写。

## 六、容器现状（2026-11-06 实查）

| 容器 | 状态 | 说明 |
|---|---|---|
| caddy / qqbot / imageguard / searxng / snowluma-1 | 运行中 | 账号 1「小满」走 `snowluma-1`（截图扫码，依赖远程桌面） |
| napcat | 运行中 | 11-04 起重新拉起的 NapCat 协议端；若无 NapCat 账号在用，可按下方命令停用以省内存 |
| wsproxy | 运行中 | 只服务 NapCat 账号（`127.0.0.1:3002`），随需求启停 |
| qqbot-official | 已停 | 官方版独立项目（`/opt/warm_isle_official`），数据保留 |

> `qqbot-autodeploy.timer` 仍为 **inactive**（2026-11-06 复核），部署以手动流程为准：
> `build-jar.sh` → scp jar → `docker compose up -d --build --force-recreate --no-deps qqbot`。

停用空闲的 NapCat 链路（释放约 300–500M 内存）：

```bash
cd /srv/qqbot/deploy/linux
sudo docker compose stop napcat wsproxy
sudo systemctl disable --now napcat-watchdog.timer   # 若仍启用
```

恢复 NapCat 接入：

```bash
cd /srv/qqbot/deploy/linux
sudo docker compose up -d napcat wsproxy
sudo systemctl enable --now napcat-watchdog.timer
```

> 注意：部署 qqbot 时必须带 `--no-deps`（`docker compose up -d --build --force-recreate --no-deps qqbot`），
> 否则 compose 会按 `depends_on` 把 napcat 一起拉起来。
