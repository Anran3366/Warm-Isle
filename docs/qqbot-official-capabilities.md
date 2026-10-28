# QQ 官方机器人能力地图

> 基于 QQ 机器人开放平台 API v2 当前文档整理  
> 资料基线：2026-10-21 文档版本；访问时间：2026-10-26  
> 适用对象：单聊 / 群聊 / QQ 频道（含频道私信）官方机器人  
> 说明：带「白名单 / 内邀」标记的能力，需要向平台申请或由运营开通。

---

## 0. 一句话结论

**QQ 官方机器人是一个以「机器人身份」运行在 QQ 单聊、群聊、频道里的服务端应用：它可以收发文本与富媒体、响应用户点击和群成员变化、管理群与频道，也能作为大模型 / Agent 的 QQ 入口。**

它不是个人 QQ 号自动化，不提供好友列表、朋友圈、任意用户私聊、任意群发，也不能绕过用户授权和平台频控。

---

## 1. 能力总览

| # | 能力 | 说明 |
|---|---|---|
| 1 | 单聊对话 | 用户添加机器人后可直接私聊；支持主动消息、被动回复、富媒体、按钮、流式输出 |
| 2 | 群聊对话 | 群主添加机器人后可 @ 对话；群主可授权接收全量消息；支持群管理 |
| 3 | 频道（Guild） | 文字子频道消息、Embed / Ark / Markdown、表情表态、频道私信、子频道管理 |
| 4 | 富媒体 | 图片、视频、语音、文件；支持 URL 上传、二进制直传、分片上传 |
| 5 | Markdown | 单聊 / 群聊自定义 Markdown 已全面开放；频道 Markdown 仍需内邀 |
| 6 | 交互组件 | 消息按钮（跳转 / 回调 / 指令）、自定义菜单、指令面板、@ 提醒、文字指令 |
| 7 | 实时事件 | WebSocket 或 Webhook 接收消息、好友、群、频道、互动、授权等事件 |
| 8 | AI / Agent | 流式消息、输入中状态、快捷菜单、消息反馈、清空会话、切换模型等 Agent 友好能力 |
| 9 | 群管理 | 群信息、机器人群内状态、入群申请、自动审批、禁言、黑名单、成员管理 |
| 10 | 频道管理 | 频道 / 子频道查询与创建修改、频道私信、消息撤回、表情表态 |
| 11 | 运营入口 | 机器人资料、分享链接、单聊自定义菜单、指令面板、二维码绑定 |
| 12 | 生态接入 | 官方 Go / Python / Node SDK；OpenClaw、DSH、Hermes 等 Agent 插件 |

---

## 2. 四种会话场景

| 场景 | 机器人如何进入 | 用户如何触发 | 关键限制 |
|---|---|---|---|
| **QQ 单聊** | 用户添加机器人 / 扫码分享链接 | 用户直接发消息 | 需要 `user_openid`；主动消息受用户开关和配额限制 |
| **QQ 群聊** | 群主添加机器人 | 默认 @机器人；群主可开全量消息 | 需要 `group_openid`；全量消息需群主设置 |
| **文字子频道** | 机器人加入频道，拥有子频道发言权限 | 频道成员发消息 / @机器人 | 需要机器人 WebSocket 在线；主动消息有子频道限额 |
| **频道私信** | 机器人与同一频道成员创建 DMS | 成员私信机器人 | 主动私信每天每用户 2 条、每机器人 200 条 |

> 上述场景的 ID 类型不同：`user_openid`、`group_openid`、`guild_id` / `channel_id`。单聊与群聊的上传接口互不相通。

---

## 3. 消息能力

### 3.1 能发什么

单聊 / 群聊发送侧目前支持三类 `msg_type`：

| msg_type | 类型 | 内容字段 | 单聊 | 群聊 | 备注 |
|---|---|---|---|---|---|
| 0 | 文本 | `content` | ✅ | ✅ | 最基础、最稳定 |
| 2 | Markdown | `markdown.content` | ✅ | ✅ | 自定义 Markdown 已开放给所有机器人 |
| 7 | 富媒体 | `media.file_info` | ✅ | ✅ | 图片 / 视频 / 语音 / 文件 |

除此之外：

- **输入中状态**：单聊支持 `msg_type=6` + `input_notify`，展示“对方正在输入”。
- **流式消息**：单聊专用接口 `/v2/users/{openid}/stream_messages`，适合大模型逐字输出。
- **频道消息**：子频道支持文本、图片、Markdown、Embed、Ark、引用回复，走 `/channels/{channel_id}/messages`。
- **按钮**：在消息里附加 `keyboard`，最多 5 行 × 每行 5 个按钮。
- **@ 与文字交互**：群聊 / 频道支持 `<qqbot-at-user>`；Markdown 支持“回车指令”和“参数指令”标签；频道支持 `@everyone`、子频道跳转、系统表情。

> 单聊 / 群聊目前**不支持发 Ark、Embed 或结构化卡片**。收到卡片可以，主动发卡片不行；这是频道场景才有的能力。

### 3.2 主动消息、被动回复、互动召回

| 类型 | 条件 | 用途 | 当前主要限额 |
|---|---|---|---|
| **主动消息** | 不携带 `msg_id` / `event_id` | 定时提醒、运营通知、主动触达 | 单聊 1000 条/用户/天；群聊 1000 条/群/天；单关系 20 条/分钟 |
| **被动回复** | 携带 `msg_id` 或 `event_id` | 回复用户 / 事件 | 单聊 60 分钟内每条消息 4 次；群聊 5 分钟内每条消息 5 次 |
| **互动召回** | 携带 `is_wakeup=true` | 用户聊过之后重新召回 | 30 天内 4 个周期，每周期 1 条 |

补充：

- 主动消息可以被用户在机器人资料卡里关闭；关闭后主动消息会失败。
- 相同 `msg_id + msg_seq` 重复发送会失败；多次回复同一消息要递增 `msg_seq`。
- 互动召回与普通主动消息是两套额度，不要混用。
- 用户/群主动推送开关变化会以 `C2C_MSG_RECEIVE` / `C2C_MSG_REJECT`、`GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT` 事件通知。

### 3.3 富媒体

| file_type | 类型 | 支持格式 | 软限制 | 硬限制 |
|---|---|---|---|---|
| 1 | 图片 | png / jpg（文档另处也列出 gif / webp / bmp） | 20MB | 200MB |
| 2 | 视频 | mp4 | 30MB | 200MB |
| 3 | 语音 | silk（部分场景兼容 mp3 / wav / ogg） | 20MB | 200MB |
| 4 | 文件 | 任意 | 200MB | 200MB |

三种上传方式：

1. **URL 上传**：平台拉取公网 URL，适合已有 CDN 资源。
2. **二进制直传**：`file_data` 放 base64，单文件 ≤ 5MB，最简单的本地文件方案。
3. **分片上传**：`upload_prepare` → 分片 PUT → `upload_part_finish` → 合并，适合大文件。

发送时使用上传返回的 `file_info`：

```json
{
  "msg_type": 7,
  "media": { "file_info": "..." }
}
```

`file_info` 有 `ttl`，过期需要重新上传。图片、视频、语音、文件都走同一套富媒体框架。

### 3.4 按钮与交互

消息按钮支持三类 `action.type`：

| type | 行为 | 典型用途 |
|---|---|---|
| 0 | 跳转 HTTP / 小程序 scheme | 打开网页、活动页 |
| 1 | 回调后台 | 确认、打卡、投票、工单 |
| 2 | 指令按钮 | 自动在输入框插入 `@bot` + data |

- 回调按钮会触发 `INTERACTION_CREATE`；机器人要在 **3 秒内**调用 `PUT /interactions/{interaction_id}` 响应，否则用户端一直 loading。
- 按钮模板需要申请；**自定义按钮目前属于内邀能力**。
- 除消息按钮外，还支持：
  - **单聊自定义菜单**：底部菜单，支持发送消息、跳转链接、子菜单。
  - **指令面板**：可在单聊、群聊、文字子频道、频道私信配置，按场景 / 用户 / 群生效。
  - **消息反馈**（点赞 / 点踩）、**清空会话**、**进出故事集**、**切换模型**、**用户 / 群授权**等 `INTERACTION_CREATE` 子类型，面向智能体场景。

### 3.5 接收侧

收到用户消息时：

- `message_type=0`：普通文本；
- `message_type=3`：结构化卡片（`ark_data`）；
- `message_type=103`：引用消息（`msg_elements`）；
- `attachments`：图片、视频、语音、文件等附件；
- 语音附件可能带 `voice_wav_url` 和 `asr_refer_text`（ASR 参考文本）。

群聊默认只推送 @机器人 的消息；群主开启“全量消息”后，才会收到 `GROUP_MESSAGE_CREATE`。这是权限设置，不是代码能绕过的。

---

## 4. 事件与实时通信

### 4.1 接入方式

| 方式 | 特点 | 适用 |
|---|---|---|
| **WebSocket** | 长连接，机器人需要保持在线；支持心跳 / Resume | 实时对话、开发初期 |
| **Webhook** | HTTP 回调，平台主动推送；HTTPS，端口 80/443/8080/8443 | 云函数、无状态服务、生产环境 |

频道发消息接口明确要求机器人 **WebSocket 在线**，因此频道场景通常要维护网关连接。

### 4.2 主要事件

| 分类 | 事件 |
|---|---|
| 单聊 | `C2C_MESSAGE_CREATE`、`C2C_MSG_RECEIVE`、`C2C_MSG_REJECT` |
| 好友 | `FRIEND_ADD`、`FRIEND_DEL` |
| 群聊 | `GROUP_AT_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE`（全量）、`GROUP_ADD_ROBOT`、`GROUP_DEL_ROBOT`、`GROUP_JOIN_REQUEST`、`GROUP_MEMBER_ADD`、`GROUP_MEMBER_REMOVE` |
| 群接收开关 | `GROUP_MSG_RECEIVE`、`GROUP_MSG_REJECT` |
| 互动 | `INTERACTION_CREATE`（按钮、快捷菜单、消息反馈、清空会话、授权等） |
| 订阅消息 | `SUBSCRIBE_MESSAGE_STATUS`（用户/群授权状态变化） |
| 频道 | `GUILD_CREATE/UPDATE/DELETE`、`CHANNEL_CREATE/UPDATE/DELETE`、频道消息 / 私信 / 表态等事件 |
| 审核 | 频道主动消息审核结果 `MESSAGE_AUDIT` |

事件订阅由 `intents` 位图控制，常用：

- `GROUP_AND_C2C_EVENT (1<<25)`：单聊、群聊、好友、群成员、授权等；
- `INTERACTION (1<<26)`：按钮 / 快捷菜单互动；
- `GUILDS / GUILD_MESSAGES / PUBLIC_GUILD_MESSAGES`：频道相关。

部分事件需要申请权限，订阅无权限的 intent 会导致 WebSocket 鉴权失败。

---

## 5. 机器人与运营入口

| 能力 | 说明 |
|---|---|
| 机器人资料 | 获取 `id`、昵称、头像、分享链接（`/users/@me`） |
| 分享链接 | 生成 / 使用机器人分享链接，扫码或点击添加 |
| 单聊自定义菜单 | 底部菜单，支持开关、发消息、跳转、子菜单 |
| 指令面板 | 单聊 / 群聊 / 文字子频道 / 频道私信，按场景与对象生效 |
| 主动消息开关事件 | 监听用户 / 群是否允许主动推送 |
| 订阅消息授权事件 | 用户对订阅模板允许 / 拒绝后更新 `subscribe_id` |
| 快捷菜单回调 | 单聊快捷菜单点击，`INTERACTION_CREATE type=12` |

这些入口的价值：用户不需要记指令，点击菜单/面板就能发现机器人能力；对 AI 机器人尤其重要。

---

## 6. 群聊管理能力

| 能力 | 接口 / 事件 | 可用性 |
|---|---|---|
| 群基本信息 | `GET /v2/groups/{group_openid}/info` | 白名单 |
| 机器人群内状态 | `GET /v2/groups/{group_openid}/bot_state` | 白名单 |
| 入群申请列表 | `GET .../join_request_list` | 以接口权限为准 |
| 入群申请审批 | `POST .../approval_join_request/{member_openid}` | 以接口权限为准 |
| 入群自动审批策略 | 创建 / 查询 / 修改 / 删除 / 执行 / 维护白名单 | 机器人需为群管理员；最多 20 个策略 |
| 群禁言状态 | `GET .../restrict_chat_setting` | 以接口权限为准 |
| 设置群成员禁言 | `POST .../restrict_chat_setting` | 机器人需有管理权限 |
| 群成员列表 | `GET .../members` | 内邀；每页最多 30 条 |
| 群成员信息 | `GET .../members/{member_openid}` | 内邀 |
| 批量移除群成员 | `POST .../batch_remove_members` | 以接口权限为准 |
| 群黑名单 | 查询 / 操作 | 以接口权限为准 |

机器人群内状态包含：

- 是否允许主动推送（`allow_proactive_msg`）；
- 接收消息设置：`all` / `only_mention` / `mention_and_context`；
- 机器人在群里的角色：成员 / 管理员 / 群主。

> 群管理是一组“白名单 + 管理员权限”能力，不适合假设所有机器人都能直接调用。错误码 `11253` 表示应用没有接口访问权限。

---

## 7. 频道（Guild）能力

| 能力 | 说明 |
|---|---|
| 频道 / 子频道信息 | 获取频道详情、子频道列表、子频道详情 |
| 子频道管理 | 创建、修改、删除子频道（需要频道权限） |
| 子频道消息 | 文本、图片、Markdown、Embed、Ark、引用回复 |
| 频道私信 | 与同频道成员创建 DMS，发送 / 撤回私信 |
| 撤回消息 | 机器人可撤回自己发送的消息 |
| 表情表态 | 对频道消息添加 / 删除表情表态 |
| 频道事件 | 频道、子频道、成员、消息、表态等事件 |
| 消息审核 | 主动消息审核结果通过 `MESSAGE_AUDIT` 查询 |

频道主动消息限制：

| 限制项 | 规则 |
|---|---|
| 子频道主动消息 | 每个子频道每天默认 20 条 |
| 推送范围 | 每个频道每天默认只能向 2 个子频道主动推送 |
| 发送速率 | 每个子频道每秒最多 5 条 |
| 频道私信 | 每机器人每天对每个用户 2 条；每天累计 200 条 |
| 被动回复 | 有效期 5 分钟 |
| 在线要求 | 发送消息需要 WebSocket 在线 |

---

## 8. Agent / 大模型接入

平台现在把“AI Agent 进 QQ”作为独立入口，提供：

- **快速创建 QQ 机器人**：扫码创建，自动获得 AppID / AppSecret；
- **扫码绑定**：第三方 Agent 通过 `@tencent-connect/qqbot-connector` 展示二维码，用户扫码授权；
- **QQ 龙虾机器人**：把 OpenClaw、Hermes、Workbuddy、QClaw、腾讯云 OpenClaw 等 Agent 接入 QQ；一个 QQ 账号最多可创建 5 个独立机器人；
- **官方插件**：OpenClaw QQ Bot 插件、DeepSeek Harness (DSH) QQ Bot 插件；
- **消息通道能力**：文字、图片、视频、文件上下行；图片理解、语音转录、画图等取决于所使用的模型 / Skill，不是平台自带；
- **智能体交互事件**：消息反馈（赞/踩）、清空会话、切换模型、故事集、用户/群授权；
- **流式消息**：单聊逐段输出，适合 LLM 生成；
- **输入中状态**：提升等待体验。

> Agent 接入是“通道 + 插件”模式：QQ 负责消息与用户触达，推理、记忆、工具调用由 Agent / 模型侧提供。

---

## 9. 鉴权、权限与合规

### 9.1 鉴权

```text
POST https://api.bot.qq.com/app/getAppAccessToken
{ "appId": "...", "clientSecret": "..." }
```

- 返回 `access_token`，默认有效期约 7200 秒；
- 调用 OpenAPI 时使用请求头：`Authorization: QQBot <access_token>`；
- 需要自行缓存、在过期前刷新；
- 接口域名自 2026-08-10 起统一为 `api.bot.qq.com`。

### 9.2 权限

- 事件订阅由 `intents` 控制，部分 intent 需要申请；
- 群管理、群成员、自定义按钮、频道 Markdown 等属于白名单 / 内邀能力；
- 认证类型会影响主动消息 Bot 维度频控（企业/个人 vs 未认证）；
- 频道操作依赖频道角色与子频道权限。

### 9.3 合规

官方运营规范明确要求：

- 机器人实际能力与简介一致，功能不能过于简单或同质化；
- 不得批量注册、刷量、诱导分享、外挂、欺诈；
- 收集用户数据需告知并征得同意，不得泄露或超范围使用；
- 需要内容安全过滤机制；
- 不得滥用接口能力、过度营销或骚扰用户。

违反可能导致限制接口、下架、封禁。

---

## 10. 官方机器人“不能做什么”

| 不能做 | 原因 / 边界 |
|---|---|
| 用 QQ 号直接查 `openid` 或反查用户 | 平台只给 openid；没有手机号 / QQ 号映射接口 |
| 获取用户好友列表、用户所在群列表、朋友圈 | 平台不提供；隐私与关系链保护 |
| 给任意陌生人主动私聊 | 必须先有 openid，且用户允许主动消息 |
| 无限制群发 / 营销轰炸 | 主动消息有用户开关、频控、日限额 |
| 读取群内全部消息（默认） | 默认只能收 @ 消息；需群主开启全量消息 |
| 在单聊/群聊发 Ark、Embed、卡片 | 发送侧仅支持文本 / Markdown / 富媒体 |
| 发送任意自定义 UI | 只有 Markdown、按钮、菜单、面板等平台组件 |
| 内置 LLM / ASR / TTS / 画图 | 平台只提供消息通道；模型能力由开发者接入 |
| 代替用户操作 QQ / 自动加好友 | 这是个人号自动化，不是官方机器人能力 |
| 绕过主动消息配额 | 平台服务端频控，无法通过换 IP / 重试绕过 |
| 任意撤回他人消息 | 仅能撤回自己的消息；群管理能力需管理员权限 |

---

## 11. 典型产品形态

| 形态 | 用到的能力 |
|---|---|
| AI 聊天助手 | 单聊 / 群聊被动回复、Markdown、流式消息、输入中状态、按钮、指令面板 |
| 定时提醒 / 运营推送 | 主动消息、订阅消息、用户授权事件、模板 / Markdown |
| 群助手 / 群管 | @ 回复、全量消息、入群审批、禁言、黑名单、成员管理、按钮 |
| 客服 / 工单 | 指令面板、按钮回调、富媒体、引用回复、消息撤回 |
| 社区 / 频道运营 | 子频道消息、Embed / Ark、表情表态、频道私信、审核事件 |
| Agent 入口 | 扫码绑定、OpenClaw / DSH 插件、消息反馈、切换模型、清空会话 |
| 内容分发 | Markdown 图文、图片 / 视频 / 语音 / 文件、分享链接、素材管理 |

---

## 12. 配额与限制速查

| 项目 | 当前规则 |
|---|---|
| 单聊主动消息 | 1000 条/用户/天；单关系 20 条/分钟 |
| 群聊主动消息 | 1000 条/群/天；单关系 20 条/分钟 |
| Bot 维度 | 企业/个人认证 10 QPS；未认证 5 QPS & 30 QPM（单聊） |
| 单聊被动回复 | 60 分钟；每条消息 4 次 |
| 群聊被动回复 | 5 分钟；每条消息 5 次 |
| 互动召回 | 30 天内 4 个周期，每周期 1 条 |
| 消息撤回 | 发送后 2 分钟内 |
| 富媒体直传 | ≤5MB；分片 / URL 可到 200MB |
| 图片素材 | 单张 ≤10MB；单机器人 100 张；引用有效期 90 天滑动 |
| 子频道主动消息 | 20 条/子频道/天；2 个子频道/频道/天；5 条/秒 |
| 频道私信 | 2 条/用户/天；200 条/Bot/天 |
| AccessToken | 约 7200 秒，提前刷新 |
| 按钮回调响应 | 3 秒内 |

> 主动消息额度、事件权限和认证类型会随平台政策调整，以开放平台后台和接口返回为准。

---

## 13. 文档口径与踩坑提醒

1. **旧文档的「主动消息每月 4 条」已不是当前口径。**  
   旧版 `send.md` 仍写着 4 条/月、被动 5 次；当前 v2 消息概述写的是 1000 条/用户/天、20 条/分钟，单聊被动 4 次。实际调用也以当前接口返回为准。

2. **不同页面存在细节差异。**  
   例如语音格式有的页面只写 silk，有的写 silk/mp3/wav/ogg；图片格式有的写 png/jpg，有的写 gif/webp/bmp。工程上应优先按“上传接口 + 实际文件”验证，并准备失败重试。

3. **按钮能力分两档。**  
   模板按钮需要申请；自定义按钮是内邀。不要默认所有机器人都能自由布局按钮。

4. **群管理能力大面积依赖白名单。**  
   群信息、群成员、 bot_state 等接口可能返回 `11253`，需要单独申请；入群自动审批还要求机器人是群管理员。

5. **群全量消息是群主设置，不是代码配置。**  
   默认只推 @ 消息；收不到普通群消息时先检查群设置。

6. **频道和单聊/群聊是两套消息模型。**  
   频道支持 Embed / Ark / 内嵌格式，单聊/群聊不支持；不要把频道文档直接套到 QQ 群。

7. **主动消息必须先拿到 openid。**  
   openid 只能从事件里获得；没有 openid 就无法构造单聊/群聊发送路径。

8. **主动消息用户开关会导致失败。**  
   即使用量没超，用户关闭「允许主动发送」也会失败；应监听开关事件并降级为被动回复。

---

## 14. 给当前项目的落地建议

1. **主链路用被动回复**：保存 `msg_id`，在 60 分钟内回复；多次回复递增 `msg_seq`。
2. **主动消息只做提醒**：先检查用户/群的主动推送开关，再做低频、可解释的触达。
3. **先拿 openid，再谈发送**：用 WebSocket / Webhook 捕获事件并持久化用户、群关系。
4. **富媒体统一走文件上传**：小文件二进制直传，大文件分片；业务层只保存 `file_info` + `ttl`。
5. **交互优先按钮/面板**：比让用户记指令更稳；回调 3 秒内先 ACK，再异步处理。
6. **AI 输出优先流式**：单聊使用 `stream_messages`，体验显著高于“转圈等全文”。
7. **群管理功能做权限探测**：先调 `bot_state` / 检查角色，再显示禁言、审批等入口；不要假设有权限。
8. **对所有 4xx 做降级**：尤其 `40034100`（频控）、`40034105`（无主动权限）、`40034128`（被动超时/超次）、`11253`（无接口权限）。

---

## 15. 官方文档索引

| 主题 | 链接 |
|---|---|
| 启动接入 | https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/getting-started.html |
| 获取 AccessToken | https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html |
| 消息收发概述 / 配额 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html |
| 消息类型 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/type/overview.html |
| 发送单聊消息 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html |
| 发送群聊消息 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html |
| 流式消息 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_stream_messages.post.html |
| 富媒体消息 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/rich-media.html |
| Markdown | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/type/markdown.html |
| 素材管理 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html |
| 消息按钮 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/msg-btn.html |
| 互动事件 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/interaction_create.html |
| 自定义菜单与指令面板 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/menu-panel/ |
| WebSocket | https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html |
| Webhook | https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/webhook.html |
| 通用事件 / Intents | https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/payload.html |
| 群成员 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_members.get.html |
| 频道消息 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html |
| 频道私信 | https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html |
| 接口索引 | https://bot.q.qq.com/wiki/develop/api-v2/autogen/ |
| Agent 接入 | https://bot.q.qq.com/wiki/agent-qqbot/ |
| 运营规范 | https://bot.q.qq.com/wiki/business/ |
| 旧版文档（仅对比） | https://raw.githubusercontent.com/tencent-connect/bot-docs/master/docs/develop/api-v2/server-inter/message/send-receive/send.md |

---

*本文档由 DSH Agent 根据公开官方文档整理，未复制官方全文；平台能力、配额和权限以开放平台最新页面与实际接口返回为准。*
