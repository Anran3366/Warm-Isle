# 暖屿插件开发规范

> 面向第三方插件作者。本文是**要求**，不是建议。
> 标注「必须」的条款在发布体检时强制校验，不通过不允许上架。
> 设计动机见 `plugin-store-v2.3.md`，完整示例与流程见 `plugin-dev-manual-v2.3.md`。
> 本文只讲「怎么写才合规」；与手册冲突时，以手册的当前实现为准。

---

## 0. 一句话说清插件是什么

插件是一个**独立进程**，通过 HTTP 接收宿主的调用请求，返回一段结果（音频、图片、文本、结构化数据）。

它**不是**注入到宿主里的代码，因此：

- 不能用宿主的数据库
- 不能拿到 QQ 协议凭据
- 不能自己发消息（发不发、怎么发、发完记不记得，都是宿主的事）

你只负责一件事：**给定参数，产出结果**。

---

## 1. 最小可用插件

一个能跑的最小插件只有两个文件。

`manifest.json`：

```json
{
  "schemaVersion": 2,
  "id": "hello-world",
  "name": "打个招呼",
  "version": "0.1.0",
  "type": "capability",
  "author": { "name": "your-name" },
  "description": "返回一句问候语",
  "tags": ["示例"],
  "license": "MIT",
  "minAppVersion": "1.2.0",
  "runtime": { "type": "manual", "endpoint": "http://127.0.0.1:3400" },
  "intent": {
    "id": "text.greet",
    "examples": ["跟我打个招呼", "说句你好", "问候我一下"]
  },
  "actions": [{
    "name": "greet",
    "description": "向用户问好",
    "params": {},
    "returns": ["text"],
    "timeoutMs": 5000
  }]
}
```

服务端只需要两个路由：

```
GET  /health   → 200 {"ok": true}
POST /invoke   → {"status":"ok","text":"你好呀"}
```

就这些。剩下的都可以不写。

---

## 2. 包结构

扩展名 `.wplugin`，本质是 zip。

```
hello-world.wplugin
├── manifest.json     必需
├── README.md         必需，商店详情页直接渲染
├── preview.png       可选，封面 1200×630，≤300KB
└── payload/          可选，纯数据（音频样本、模板、词库）
    └── voice/
```

**包里不允许出现任何可执行文件**（`.sh .js .jar .class .exe .so .py .dll`）。这条沿用内容包的安全模型：包是声明，代码通过 `runtime` 获取。

`payload/` 只放数据，总大小 ≤ 20 MB，条目 ≤ 500。

---

## 3. manifest 字段

### 3.1 基础信息

| 字段 | 必填 | 约束 |
|---|---|---|
| `schemaVersion` | ✅ | 能力插件固定 `2` |
| `id` | ✅ | `^[a-z0-9][a-z0-9-]{2,40}$`，全局唯一，发布后不可改 |
| `name` | ✅ | 展示名，2–20 字 |
| `version` | ✅ | 语义化版本 `x.y.z` |
| `type` | ✅ | 固定 `capability` |
| `author.name` | ✅ | 署名 |
| `description` | ✅ | ≤ 80 字，写清「能做什么」，不要写「很强大」 |
| `tags` | ✅ | ≤ 6 个，用于商店筛选 |
| `license` | ✅ | SPDX 标识，缺失不允许发布 |
| `minAppVersion` | ✅ | 低于此版本的实例拒绝安装 |
| `homepage` | ❌ | 仓库或文档地址 |

### 3.2 运行时 `runtime`

| 字段 | 必填 | 说明 |
|---|---|---|
| `type` | ✅ | `manual` / `docker` / `remote` |
| `endpoint` | ✅（manual/remote） | 插件服务地址，必须 `http://127.0.0.1:PORT` 或 `https://` |
| `image` | ✅（docker） | 镜像地址，必须带 `@sha256:` 摘要 |
| `healthPath` | ❌ | 默认 `/health` |
| `startTimeoutMs` | ❌ | 默认 3000，超过判定启动失败 |

三档的信任等级：

| type | 含义 | P0 支持 | 审核 |
|---|---|---|---|
| `manual` | 用户自己把进程跑起来，填地址 | ✅ | 常规 |
| `docker` | 宿主拉镜像并启动 | P1 | 需镜像摘要 + 人工 |
| `remote` | 作者托管在公网 | 暂不支持 | 最高，需备案与可用性承诺 |

**`endpoint` 必须绑定 `127.0.0.1`。**声明 `0.0.0.0` 或局域网地址一律拒绝——那意味着宿主的数据会被暴露到网络上。

### 3.3 意图 `intent`

这是本规范和你见过的其他插件框架最大的不同：**你不写触发关键词，你声明一个意图**。

```json
"intent": {
  "id": "music.sing",
  "examples": ["唱首歌", "来一段", "唱个七里香", "给我唱一段吧"],
  "exclusive": false
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 必须存在于商店的**意图注册表**，不能自造 |
| `examples` | ✅ | 5–10 条用户真实会说的话，用于生成意图向量 |
| `exclusive` | ❌ | 该意图同时只能启用一个实现，默认 `false` |

`examples` 的硬性要求：

| 要求 | 反例 | 正例 |
|---|---|---|
| 至少 4 个字 | `歌`、`唱` | `唱首歌给我听` |
| 是完整句子 | `音乐` | `放点音乐吧` |
| 不带具体实体名 | `唱七里香` | `唱个歌` |
| 覆盖不同说法 | 四条都是「唱歌」 | 「唱首歌」「来一段」「想听你唱」 |

**禁止把单个字、泛动词、语气词写进 examples**（`看`、`来`、`帮`、`那个`、`嗯`）。这些词会把别人的正常聊天吞掉，体检会直接拒。

新增意图需要向商店申请，写明「这个意图解决什么需求、和已有意图的区别」。申请通过后才可被引用。

首批内置意图见附录 A。

### 3.4 动作 `actions`

```json
"actions": [{
  "name": "sing",
  "description": "为用户唱一首歌",
  "params": {
    "song":  { "type": "string", "required": true,  "description": "歌名" },
    "style": { "type": "enum", "required": false,
               "values": ["温柔","欢快","伤感"], "description": "演唱风格" }
  },
  "returns": ["audio"],
  "timeoutMs": 60000
}]
```

| 字段 | 约束 |
|---|---|
| `name` | `^[a-z][a-z0-9_]{1,30}$`，插件内唯一 |
| `description` | 一句话，会**原样注入模型**用于补参数，写清「对谁做什么」 |
| `params[].type` | `string` / `number` / `boolean` / `enum` |
| `params[].required` | 缺省 `false` |
| `returns` | `audio` / `image` / `text` / `data` 的组合 |
| `timeoutMs` | 1000–120000 |

一个插件最多 8 个 action。超过说明你在做多个插件。

**`description` 的质量直接决定参数补全的准确率。**它是模型唯一能看到的说明，不要写「唱歌功能」，写「为用户唱一首指定歌曲」。

### 3.5 记忆 `memory`

**决定宿主会不会记住这次执行。理解了这一节，你就理解了整个系统。**

```json
"memory": {
  "policy": "episodic",
  "type": "relation",
  "keyTemplate": "唱过:{song}",
  "valueTemplate": "《{song}》，{duration}秒",
  "importance": 0.85,
  "emotion": 0.3
}
```

三种 `policy`：

| policy | 语义 | 适用 | 宿主行为 |
|---|---|---|---|
| `none` | **默认**，不写记忆 | 天气、翻译、计算、汇率 | 什么都不做 |
| `episodic` | 记「我最近做过」 | 唱歌、画图、发视频 | 写一条，进入【我最近做过的】 |
| `commitment` | 记「我答应过」 | 提醒、日程、待办 | 写一条 + 排主动兑现 |

**怎么选？问自己一句话：用户会不会在另一个话题里主动回头提它？**

- 「上次那首」「刚才那个视频」「你答应提醒我的」——会，写
- 「今天天气怎么样」——不会，他下次会重新问，写了是噪音

选错 `none` 没有惩罚，选错 `episodic` 会污染用户的记忆库。**拿不准就选 `none`。**

#### 模板变量

`keyTemplate` / `valueTemplate` 只允许使用以下变量：

| 变量 | 来源 |
|---|---|
| `{参数名}` | action 的 `params` |
| `{duration}` | 本次执行耗时（秒），仅 `episodic` |
| `{result}` | 响应里的 `text` 字段，≤ 40 字 |
| `{now}` | 当前日期 `MM-dd` |

出现未声明的变量 → 整条记忆不写（不允许写半截），并在响应里返回警告。

#### 硬约束（宿主强制，manifest 无法覆盖）

| 约束 | 值 | 原因 |
|---|---|---|
| `subject` | 恒为 `bot` | **插件不能声明用户的属性** |
| `type` | 只允许 `relation` / `event` | `profile` 是最敏感的用户画像，不开放 |
| `importance` | 封顶 0.85 | 防止靠高分抢占每轮 prompt 预算 |
| `value` 长度 | ≤ 80 字 | 防止塞长文 |
| `pinned` | 禁止 | 不能绕过遗忘曲线永久驻留 |
| 每插件每天 | ≤ 20 条 | 防止刷库 |
| 同 `key` | 覆盖，不追加 | 防止流水账堆积 |

#### 好例子 / 坏例子

**✅ 唱歌**
```json
"keyTemplate": "唱过:{song}", "valueTemplate": "《{song}》，{duration}秒"
```
存成 `唱过:七里香` → `《七里香》，32秒`。用户说「再唱一次七里香」能命中，「上次那首」走时间通道也能找回。

**✅ 抖音**
```json
"keyTemplate": "发过:抖音", "valueTemplate": "{title}（{url}）"
```
存成 `发过:抖音` → `外卖小哥的100种死法（https://v.douyin.com/xxx）`。`{title}` 是检索锚点。

**❌ 抖音的坏写法**
```json
"valueTemplate": "发了一个视频"
```
没有任何可检索的内容。用户说「刚才那个视频」，一个词都对不上，这条记忆等于白写。

**❌ 冒充用户属性**
```json
"keyTemplate": "状态", "valueTemplate": "心情不太好"
```
这是把机器人的判断写成用户的事实。宿主会拒绝，并对插件记一次违规。

**❌ 该写不写**
提醒类插件用 `"policy": "none"`。第二天用户说「你不是说提醒我吗」，机器人完全失忆——这是事故，不是 bug。

#### commitment 类额外要求

`policy: "commitment"` 时必须提供：

```json
"memory": {
  "policy": "commitment",
  "keyTemplate": "答应过:{thing}",
  "valueTemplate": "{when} 提醒{thing}",
  "importance": 0.95,
  "followUp": { "atParam": "when", "hintTemplate": "该提醒他{thing}了" }
}
```

`atParam` 指向一个**能解析成时间**的参数（ISO 8601 或 `YYYY-MM-DD HH:mm`）。解析失败 → 拒绝写入并返回错误，不能悄悄当成普通记忆。

### 3.6 提议 `proposal`

**默认关闭，且我们建议你保持关闭。**

```json
"proposal": {
  "enabled": false,
  "text": "要是想听点声音，我可以唱一段",
  "minConfidence": 0.45
}
```

开启后，宿主在用户**没有明确请求**但语义相关时，会让模型自然提一句。开启的代价：

| 约束 | 值 |
|---|---|
| 同一插件 24h 内提议 | ≤ 1 次 |
| 全部插件 24h 合计 | ≤ 2 次 |
| 被拒绝后静默 | 7 天 |
| 接受率 < 20%（样本 ≥ 5） | 自动关闭提议权限 |

`text` 的写法要求：**带退路、不说满、不催促**。

| 反例 | 正例 |
|---|---|
| 需要我给你唱首歌吗？ | 要是想听点声音，我可以唱一段 |
| 要不要试试我的新功能？ | —（不要提"功能"） |
| 我可以帮你查天气、翻译、点歌… | —（不要罗列能力） |

**注意**：`enabled: true` 不代表会被频繁触发。宿主只在置信度介于 0.35–0.5、且用户那句话是「状态陈述+隐含诉求」时才考虑。「好累啊」这类纯情绪倾诉**永远不会触发提议**，这是写死在宿主里的，不读你的配置。

### 3.7 配额与权限

```json
"quota": { "perUserPerDay": 10, "perInstancePerHour": 60 },
"permissions": ["network.outbound"]
```

| 权限 | 说明 | 默认 |
|---|---|---|
| `network.outbound` | 访问公网 | 拒绝 |
| `payload.read` | 读取自己包内 `payload/` | 允许 |
| `temp.write` | 写临时目录（≤ 200MB） | 允许 |

声明 `network.outbound` 时必须在 README 写明访问哪些域名、用途。安装页会展示。

---

## 4. HTTP 接口契约

### 4.1 路由

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/health` | 200 + `{"ok":true}`，3 秒内返回 |
| `POST` | `/invoke` | 执行动作 |
| `GET` | `/task/{taskId}` | 长任务轮询（可选） |

### 4.2 请求

```json
{
  "taskId": "01HQ3F8ZK9XQ2M",
  "action": "sing",
  "params": { "song": "七里香", "style": "温柔" },
  "ctx": {
    "userId": 123456,
    "nickname": "小明",
    "now": "2026-11-06T15:20:00+08:00",
    "locale": "zh-CN"
  }
}
```

`taskId` 全局唯一。**必须幂等**：同一 `taskId` 重复请求返回相同结果，不得重复生成或重复计费。

`ctx` 只给最小必要信息。没有 QQ 号之外的任何用户数据，不要试图获取。

### 4.3 同步响应

```json
{
  "status": "ok",
  "audio": "data:audio/mp3;base64,....",
  "durationSec": 32,
  "text": "给你唱一段",
  "memory": { "song": "七里香" }
}
```

| 字段 | 说明 |
|---|---|
| `status` | `ok` / `pending` / `error` |
| `audio` | base64（≤ 2MB）或 `audioUrl`（HTTPS） |
| `image` | base64（≤ 5MB）或 `imageUrl` |
| `text` | 附在结果旁的一句话，≤ 60 字，可为空 |
| `memory` | 覆盖 `params` 中同名变量，供模板渲染 |

**音频格式**：`mp3` / `wav` / `amr` / `silk` 之一。宿主用 ffmpeg 转成 QQ 语音条，你不需要关心容器细节。

**绝对不要**把音频或图片 base64 拼进 `text`。一条 base64 能把整个提示词撑爆，宿主会直接丢弃这次结果。

### 4.4 异步响应

耗时超过 10 秒的动作**必须**返回 `pending`，否则用户会以为机器人死了。

```json
{ "status": "pending", "taskId": "01HQ3F8ZK9XQ2M", "estimateSec": 40 }
```

宿主随后轮询 `GET /task/{taskId}`，直到拿到 `ok` 或 `error`，最长 120 秒。

`pending` 期间宿主会：
1. 先发一句短话（「好，等我一下」），这句**不进记忆**
2. 在你返回结果前，如果用户又发消息，宿主会告诉模型「你正在为他唱《七里香》」
3. 拿到结果后按 `memory` 声明落库

### 4.5 错误

```json
{
  "status": "error",
  "code": "SONG_NOT_FOUND",
  "message": "曲库里没有这首歌",
  "userHint": "这首我这儿没有，换一首？",
  "retryable": false
}
```

| 字段 | 要求 |
|---|---|
| `code` | 大写下划线，插件内唯一，用于统计 |
| `message` | 给开发者看的，可以技术化 |
| `userHint` | **给用户看的一句话**，≤ 40 字，用机器人的口吻。缺省时宿主说「这个我暂时弄不了」 |
| `retryable` | `true` 时宿主最多重试 1 次（同 taskId） |

`userHint` 是最容易被忽略、也最影响体验的字段。写「系统错误码 500」和写「这首我这儿没有，换一首？」是两种产品。

---

## 5. 红线

违反任一条，直接下架，不做警告。

1. **不许自己发消息。**你没有 QQ 凭据，任何试图连接协议端的行为视为攻击。
2. **不许读写宿主的数据库和文件系统。**沙箱外的路径一律无权限。
3. **不许把大体积内容塞进 `text` / `valueTemplate`。**单字段上限 80 字（记忆）/ 60 字（text）。
4. **不许伪造执行结果。**返回`ok`但实际没做事，视为欺诈。
5. **不许在响应里声称"我已经发送了"。**发不发是宿主决定的，你只返回内容。
6. **不许长期阻塞。**单次请求超过 `timeoutMs` 会被熔断，连续 3 次失败自动停用 30 分钟。
7. **不许把 `endpoint` 暴露到公网。**必须 `127.0.0.1`。
8. **不许在 `examples` 里写泛词。**见 §3.3。

---

## 6. 发布体检

上传后自动跑，全部通过才进人工审核。

| 检查项 | 判定 |
|---|---|
| manifest schema | 字段齐全、正则合规 |
| 包安全 | 无可执行物、无路径穿越、大小合规 |
| `/health` 可达 | 3 秒内 200 |
| 每个 action 冒烟 | 用 `examples` + 默认参数调一次，必须返回 `ok` |
| 响应体积 | audio ≤ 2MB、image ≤ 5MB |
| 幂等 | 同 taskId 连调两次，结果一致 |
| 超时 | 在 `timeoutMs` 内返回 |
| 意图合法性 | `intent.id` 在注册表内 |
| **词表冲突** | `examples` 与其他插件同类意图相似度 > 0.85 时警告并给出替代建议 |
| **记忆模板** | 变量全部已声明；`valueTemplate` 渲染后含具体信息（长度 ≥ 6 字且不是纯动词） |
| 权限一致性 | 声明了 `network.outbound` 但 README 未说明域名 → 警告 |

体检失败会返回**具体到字段**的报错，不是"审核不通过"。

---

## 7. 三个完整示例

### A. 唱歌（episodic）

```json
{
  "intent": { "id": "music.sing", "examples": ["唱首歌", "来一段", "想听你唱", "给我唱一个"] },
  "actions": [{
    "name": "sing",
    "description": "为用户唱一首歌",
    "params": { "song": { "type": "string", "description": "歌名，没说就随机" } },
    "returns": ["audio"],
    "timeoutMs": 60000
  }],
  "memory": {
    "policy": "episodic", "type": "relation",
    "keyTemplate": "唱过:{song}", "valueTemplate": "《{song}》，{duration}秒",
    "importance": 0.85, "emotion": 0.3
  }
}
```

### B. 看抖音（episodic）

```json
{
  "intent": { "id": "media.douyin", "examples": ["看个抖音", "刷个视频", "来个搞笑视频"] },
  "actions": [{
    "name": "fetch",
    "description": "找一条符合要求的抖音视频发给用户",
    "params": { "keyword": { "type": "string", "description": "主题，没指定就随机" } },
    "returns": ["text", "data"],
    "timeoutMs": 15000
  }],
  "memory": {
    "policy": "episodic", "type": "relation",
    "keyTemplate": "发过:抖音", "valueTemplate": "{title}（{url}）",
    "importance": 0.7
  }
}
```

关键在 `valueTemplate` 必须带 `{title}`——只写「发了一个视频」等于没写。

### C. 提醒（commitment）

```json
{
  "intent": { "id": "reminder.create", "examples": ["提醒我", "帮我记一下", "明天叫我"] },
  "actions": [{
    "name": "create",
    "description": "创建一个定时提醒",
    "params": {
      "thing": { "type": "string", "required": true, "description": "要提醒的事" },
      "when":  { "type": "string", "required": true, "description": "时间，ISO 8601" }
    },
    "returns": ["text"],
    "timeoutMs": 5000
  }],
  "memory": {
    "policy": "commitment", "type": "relation",
    "keyTemplate": "答应过:{thing}", "valueTemplate": "{when} 提醒{thing}",
    "importance": 0.95,
    "followUp": { "atParam": "when", "hintTemplate": "该提醒他{thing}了" }
  }
}
```

---

## 8. 版本与兼容

- `version` 遵循 semver。`manifest` 字段只增不改，删字段必须升主版本。
- 插件声明 `minAppVersion`，低于此版本的实例不会出现在商店。
- 宿主调用时带 `X-Plugin-Protocol: 2`，你的服务应当校验；不认识的协议版本返回 `426`。
- 已安装插件被作者删除版本时不强制卸载，但后台标红「已下架」。

---

## 附录 A：首批内置意图

| intent id | 含义 | 典型 action |
|---|---|---|
| `music.sing` | 唱歌 | 返回音频 |
| `music.play` | 播放现成歌曲 | 返回音频 |
| `media.douyin` | 抖音短视频 | 标题 + 链接 |
| `media.bilibili` | B 站视频 | 标题 + 链接 |
| `image.generate` | 生成图片 | 图片 |
| `image.search` | 搜图 | 图片 |
| `text.translate` | 翻译 | 文本 |
| `text.summarize` | 总结 | 文本 |
| `weather.query` | 天气 | 文本 |
| `reminder.create` | 创建提醒 | 文本 + 主动兑现 |
| `todo.manage` | 待办 | 文本 |
| `web.fetch` | 抓网页 | 文本 |

这 12 个覆盖了早期绝大多数需求。需要新意图请申请，说明用途与和已有意图的边界。

---

## 附录 B：开发检查清单

发布前逐条自查：

- [ ] `/health` 3 秒内返回 200
- [ ] 每个 action 用默认参数能跑通
- [ ] 同一个 `taskId` 调两次结果一致
- [ ] 耗时 > 10 秒的动作返回 `pending`
- [ ] `text` 里没有 base64
- [ ] `examples` 都是完整句子，没有单字和泛词
- [ ] `memory.policy` 选的是 `none`，除非有明确理由
- [ ] 若选 `episodic`，`valueTemplate` 渲染后包含具体对象名
- [ ] 若选 `commitment`，`followUp.atParam` 能解析成时间
- [ ] 错误响应都带 `userHint`
- [ ] README 写清了 `network.outbound` 访问的域名
