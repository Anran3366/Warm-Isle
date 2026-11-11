# API 契约 v1（发给 Qoder 的唯一依据）

- 前缀：`/api`
- 编码：`application/json; charset=utf-8`
- 认证：`POST /api/auth/login` 成功后返回 HttpOnly session cookie，之后所有请求自动携带
- 错误格式：

```json
{ "code": "FACT_NOT_FOUND", "message": "事实卡不存在", "traceId": "9f2c..." }
```

- 分页：`?page=1&size=20` → `{ "items": [...], "total": 123 }`
- 时间：ISO 8601 字符串，如 `2026-10-22T12:00:05+08:00`

---

## 1. 认证

### POST /api/auth/login
请求：`{ "password": "******" }`
响应：`{ "ok": true }`（同时种 cookie）
错误：401 `{ "code": "AUTH_FAILED", "message": "密码错误" }`

### POST /api/auth/logout
响应：`{ "ok": true }`

### POST /api/auth/password
修改后台授权码（需要已登录）。密码以加盐 SHA-256 存入 `shared.db` 的 `settings.admin.passwordHash`，改完所有旧会话失效，并给当前浏览器换发新 cookie。
请求：`{ "currentPassword": "旧密码", "newPassword": "新密码（≥6 位）" }`
响应：`{ "ok": true }`
错误：401 `{ "code": "AUTH_FAILED", "message": "当前密码不正确" }`；400 `{ "code": "INVALID_PASSWORD", "message": "新密码至少 6 位" }`

---

## 2. 状态与用量

### GET /api/status
```json
{
  "bot": { "online": true, "napcat": true, "uptimeSec": 86400 },
  "today": { "messages": 87, "cost": 0.18, "tokens": 120345 },
  "persona": { "id": "p1", "name": "小满" },
  "memory": { "facts": 42, "pending": 3, "lastExtractedAt": "2026-10-22T12:00:05+08:00" }
}
```

### GET /api/usage?from=2026-10-16&to=2026-10-22
```json
[{ "date": "2026-10-22", "cost": 0.18, "tokens": 120345 }]
```

---

## 3. 人设

### GET /api/personas
```json
[{ "id": "p1", "name": "小满", "content": "# 人设…", "active": true }]
```

### POST /api/personas
请求：`{ "name": "小满", "content": "markdown..." }` → 返回新建对象

### PUT /api/personas/{id}
请求：`{ "name": "...", "content": "..." }`

### DELETE /api/personas/{id}

### POST /api/personas/{id}/activate
响应：`{ "ok": true }`

### GET /api/prompt/preview
返回最近一次回复实际使用的 prompt（调试用）：
```json
{
  "persona": "…800 tokens…",
  "facts": "…300 tokens…",
  "memories": "…200 tokens…",
  "history": "…1800 tokens…",
  "user": "今天好累",
  "totalTokens": 3120
}
```

---

## 4. 记忆

### GET /api/facts?category=&q=&page=1&size=20
```json
{
  "items": [{
    "id": "f1", "category": "profile", "key": "城市",
    "value": "杭州", "confidence": 0.95, "status": "active",
    "firstSeen": "2026-03-02", "lastConfirmed": "2026-10-21"
  }],
  "total": 42
}
```

`category`：`profile | preference | relationship | event | commitment`
`status`：`active | outdated | disputed | pending`

### POST /api/facts
请求：`{ "category": "event", "key": "面试", "value": "10月22日字节二面", "confidence": 0.9 }`

### PUT /api/facts/{id}
### DELETE /api/facts/{id}

### GET /api/facts/pending
返回 `{ "items": [...] }`，元素同 facts，`status = "pending"`

### POST /api/facts/{id}/confirm
### POST /api/facts/{id}/reject

### GET /api/summaries?from=&to=
```json
[{ "date": "2026-10-21", "text": "今天聊了…" }]
```

### GET /api/messages?qqId=&q=&from=&to=&page=1&size=50
- `qqId`：省略 = 当前账号默认用户；传 `0` = **全部用户混合**；传具体 QQ 号 = 只看该用户
- `from` / `to`：`YYYY-MM-DD` 或 `YYYY-MM-DD HH:mm:ss`（含边界）
- `q`：内容模糊匹配
```json
{ "items": [{ "id": "m1", "qqId": "100000000", "role": "user", "content": "…", "kind": "text", "createdAt": "…" }], "total": 1200 }
```

### GET /api/messages/users
返回当前机器人账号下出现过的用户，用于聊天记录页的「全部用户 / 指定 QQ」筛选。
```json
[ { "qqId": 100000000 } ]
```

### POST /api/memory/extract
响应：`{ "ok": true, "jobId": "..." }`（异步，前端可轮询 status）

---

## 5. 表情包

### GET /api/emoji?page=&size=
```json
{ "items": [{ "id": "e1", "url": "/files/emoji/e1.png", "emoTags": ["开心"], "sceneTags": ["打招呼"], "enabled": true, "source": "upload" }], "total": 36 }
```

`source`：`upload`（后台手动上传）| `auto`（用户聊天中发来的表情包，自动入库）

### POST /api/emoji （multipart/form-data）
字段：`file`（图片）、`emoTags`（逗号分隔）、`sceneTags`

### PUT /api/emoji/{id}
请求：`{ "emoTags": ["开心", "得意"], "sceneTags": ["晚安"], "enabled": true }`

### DELETE /api/emoji/{id}

### POST /api/emoji/{id}/test-send
响应：`{ "ok": true }`

---

## 6. 语音

### GET /api/voice/config
```json
{
  "provider": "mimo",
  "voice": "茉莉",
  "speed": 1.0,
  "enabled": true,
  "chatProbability": 0.2,
  "dailyLimit": 3
}
```

`chatProbability`：聊天中发语音的概率（0–1）。
`dailyLimit`：每天最多主动发几条语音，0 表示当天不发。

### PUT /api/voice/config
请求同上字段（部分更新）

### POST /api/voice/test
请求：`{ "text": "测试一下声音" }` → 返回 `{ "fileUrl": "/files/tts/test-xxx.silk" }`

### 音色克隆（多音色库）

`GET /api/voice/config` 的 `clone` 字段：

```json
{
  "enabled": true,
  "hasRef": true,
  "name": "我的音色",
  "activeId": "vp_01",
  "profiles": [
    { "id": "vp_01", "name": "深夜版", "durationMs": 9200, "active": true, "createdAt": "2026-10-23 15:20:00" }
  ]
}
```

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/voice/clone` | 上传参考音（multipart `file` + `name`），返回质检结果与 `previewUrl` |
| GET | `/api/voice/clones` | 音色列表 `{ ok, profiles }` |
| POST | `/api/voice/clones/{id}/activate` | 设为当前音色 |
| DELETE | `/api/voice/clones/{id}` | 删除（删当前时自动落到下一条） |
| POST | `/api/voice/preview` | 用当前音色合成一句试听 |

`POST /api/voice/clone` 质检失败返回 400，`message` 说明原因（如“只有 2.0 秒，太短”“没有识别到清晰人声”“平均音量 -52 dB，太小声”）。

---

## 7. 主动消息

### GET /api/push/config
```json
{
  "enabled": true,
  "morning": { "start": "08:30", "end": "09:30" },
  "night": { "start": "22:30", "end": "23:30" },
  "idleHours": 6,
  "dailyLimit": 3,
  "quietStart": "23:30",
  "quietEnd": "08:00"
}
```

### PUT /api/push/config
### GET /api/push/history?page=&size=
```json
{ "items": [{ "id": "x1", "type": "followup", "content": "好点了吗？药吃了吗", "sentAt": "…", "replied": true }], "total": 12 }
```
`type`：`schedule | followup | idle`

### POST /api/push/test
请求：`{ "type": "followup" }` → 立即发一条

---

## 8. 设置与日志

### GET /api/settings
```json
{
  "model": "deepseek-flash",
  "temperature": 0.8,
  "dailyBudget": 1.0,
  "contextRounds": 20,
  "vision": {
    "enabled": true,
    "maxPerMessage": 3,
    "maxSize": 1024,
    "dailyLimit": 30,
    "emojiAutoImport": true
  }
}
```

`vision.emojiAutoImport`：用户发来的表情包自动入素材库。

### PUT /api/settings

### GET /api/topic-block
话题屏蔽配置。命中后机器人按 `action` 回避；配置会拼成一段「话题边界」注入 system prompt。
```json
{
  "enabled": true,
  "action": "deflect",
  "replyTemplate": "这个话题我不太想聊欸，我们换个别的吧～",
  "customTopics": ["前任", "体重"],
  "presets": [
    {
      "id": "porn",
      "label": "色情低俗",
      "desc": "性行为、裸露、色情资源、擦边挑逗",
      "enabled": true,
      "keywords": ["色情", "裸照", "约炮"]
    }
  ]
}
```

- `action`：`deflect`（委婉转移）| `refuse`（温和拒绝）| `silent`（不作回复）
- `presets[].keywords`：命中方式为「消息包含关键词」，忽略大小写
- `customTopics`：用户自定义话题，同样按「消息包含该词」匹配
- `replyTemplate`：给模型的参考话术，支持 `{topic}` 占位符（替换为命中的话题名）

### PUT /api/topic-block
请求体同上，返回保存后的完整配置。`enabled=false` 时不注入 prompt。

### GET /api/logs?level=&tail=200
```json
[{ "ts": "…", "level": "info", "msg": "连接 NapCat 成功" }]
```

---

## 9. 页面 ↔ 接口映射（给 Qoder 的导航）

| 页面 | 用到的接口 |
|---|---|
| 登录 | `auth/login` |
| 仪表盘 | `status`、`usage` |
| 人设 | `personas`、`personas/{id}/activate`、`prompt/preview` |
| 记忆 | `facts`、`facts/pending`、`summaries`、`messages`、`memory/extract` |
| 表情包 | `emoji`（列表/上传/标签/试发） |
| 语音 | `voice/config`、`voice/test` |
| 主动消息 | `push/config`、`push/history`、`push/test` |
| 话题屏蔽 | `topic-block` |
| 设置 | `settings` |
| 日志 | `logs` |

---

## 10. 多机器人账号（2026-10 新增）

所有业务接口通过请求头 `X-Bot-Id` 指定机器人账号；缺省为 `1`（默认账号）。

### 认证（扫码登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/qr/new` | 创建扫码会话，返回 `{token, url, ttlSeconds}` |
| GET | `/api/auth/qr/{token}` | `pending` / `approved` / `expired` |
| POST | `/api/auth/qr/{token}/approve` | 手机提交 `{password}` |
| POST | `/api/auth/qr/{token}/claim` | PC 领取会话 cookie（只能一次） |

> 二维码地址必须是 hash 路由：`https://<host>/#/m/<token>`。

### 机器人账号

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/account/list` | `{items:[{id,qqId,name,container,wsPort,online,qrcode,...}]}` |
| POST | `/api/account/create` | 新建独立 NapCat 实例，`{name}` |
| GET | `/api/account/{id}` | 单个账号详情 |
| GET | `/api/account/{id}/qrcode` | 登录二维码（PNG） |
| POST | `/api/account/{id}/logout` | 下线（停容器） |
| DELETE | `/api/account/{id}` | 删除账号（容器 + 数据库；id=1 不可删） |
| GET | `/api/account/{id}/whitelist` | `{ids:[...], allAllowed}` |
| PUT | `/api/account/{id}/whitelist` | `{ids:[...]}`，空数组=不限制 |

### 系统状态

`GET /api/system`：

```json
{
  "memory": { "totalMb": 3654, "usedMb": 1113, "availableMb": 2541, "percent": 30 },
  "jvm": { "maxMb": 494, "usedMb": 33 },
  "uptimeSec": 240,
  "java": "21.0.12.1",
  "os": "Linux 7.0.0-30-generic",
  "instances": [{ "botId": 1, "qq": 100000000, "online": true }],
  "onlineCount": 2
}
```

### 知识库解析类型

`POST /api/knowledge/upload` 新增表单字段 `parseType`：

| 值 | 行为 |
|---|---|
| `persona` | 从文本抽取角色，生成人设并自动命名，写入当前账号人设列表 |
| `style` | 拆成台词/风格样本（默认） |
| `raw` | 仅入库，不处理 |

- 解析只在每天 **00:05–06:05** 运行，窗口外 `POST /api/knowledge/process` 返回 `OUT_OF_WINDOW`；
- 每篇处理前检查可用内存，低于 500MB 自动暂停。
