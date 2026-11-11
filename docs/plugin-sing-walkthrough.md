# 唱歌插件全流程走查

> 场景：用户从后台装上「唱歌」插件并开启，然后在私聊里点了一首歌。
> 目的：把 `plugin-io-contract.md` 的抽象契约，落成一步一步能对着调试的报文。
> 所有 QQ 号、id、时间都是示例值。

---

## 0. 这个插件长什么样

### 0.1 目录

```
data/plugins/menran.sing/
├── plugin.json
├── README.md
└── assets/
    └── voice/
        ├── qilixiang.mp3      412 KB
        ├── qingtian.mp3       361 KB
        └── daoxiang.mp3       389 KB
```

### 0.2 `plugin.json` 全文

```json
{
  "id": "menran.sing",
  "version": "1.0.0",
  "apiVersion": 1,
  "name": "唱歌",
  "author": "menran",
  "description": "点歌，返回一段演唱音频",
  "license": "MIT",

  "runtime": { "type": "data", "entry": "assets/voice" },

  "intent": {
    "id": "music.sing",
    "examples": ["唱首歌", "来一段", "唱个七里香", "想听你唱"],
    "accepts": { "song": "string" }
  },

  "actions": [
    {
      "id": "sing",
      "params": { "song": { "type": "string", "required": true } },
      "output": { "kind": "audio", "mime": "audio/mpeg" }
    }
  ],

  "catalog": {
    "key": "song",
    "ambiguousHint": "想听哪首？",
    "entries": [
      { "title": "七里香", "aliases": ["七里香", "周杰伦那个"],
        "file": "assets/voice/qilixiang.mp3", "seconds": 32 },
      { "title": "晴天",   "aliases": ["晴天"],
        "file": "assets/voice/qingtian.mp3",  "seconds": 28 },
      { "title": "稻香",   "aliases": ["稻香"],
        "file": "assets/voice/daoxiang.mp3",  "seconds": 30 }
    ]
  },

  "memory": {
    "policy": "episodic",
    "keyTemplate": "唱过:{song}",
    "valueTemplate": "给他唱了《{song}》，{seconds} 秒"
  },

  "proposal": { "enabled": false },
  "quota": { "perUserPerDay": 10, "perUserPerHour": 3 },
  "permissions": { "network": false, "memoryWrite": true }
}
```

**包里没有一行可执行代码。** 加一首歌 = 加一行 JSON + 一个 mp3，不用改宿主。

---

## 1. 阶段一：下载安装

### 1.1 用户在后台做什么

「插件」页 → 上传 zip → 点「安装」。

前端发出：

```http
POST /api/plugins/install
Content-Type: multipart/form-data; boundary=----X

------X
Content-Disposition: form-data; name="file"; filename="menran.sing-1.0.0.zip"
Content-Type: application/zip

<3.87 MB 二进制>
------X
```

### 1.2 后端七步

| # | 动作 | 失败时 |
|---|---|---|
| 1 | 收流到 `plugins/.staging/<uuid>/`，边写边算 sha256 | 磁盘不足 → `DISK_FULL` |
| 2 | 解压（拒绝 `..` 路径、符号链接、绝对路径） | `BAD_ZIP` |
| 3 | 解析 `plugin.json` → `PluginManifest` | `BAD_MANIFEST` |
| 4 | **三道本地检查**（见 1.3） | `REFERENCES_MISSING` / `EXTERNAL_LINK` / `SELF_TEST_FAILED` |
| 5 | 原子 `rename` 到 `data/plugins/menran.sing/` | 已存在同 id → 先 rename 旧目录到 `.trash/` |
| 6 | 写 `installed_plugins`（`enabled=0`） | 回滚目录 |
| 7 | 写 `.installed.json` 溯源 | 只告警，不失败 |

第 4 步的三道检查：

```java
// ① 引用完整性
manifest.catalog().entries()
    .forEach(e -> requireExists(root, e.file()));      // 3/3 存在

// ② 禁止外链（data 型）
String json = Files.readString(root.resolve("plugin.json"));
if (json.matches(".*https?://.*")) throw ...;          // 只允许出现在 description 里，实现上是逐字段查

// ③ 自检
for (entry : catalog) {
    AudioMeta meta = AudioAnalysis.probe(root.resolve(entry.file()));
    if (meta == null) throw new PluginException(SELF_TEST_FAILED, "…解不开");
    if (Math.abs(meta.seconds() - entry.seconds()) > entry.seconds() * 0.2) {
        throw new PluginException(SELF_TEST_FAILED, "声明的时长与实际不符");
    }
}
```

### 1.3 成功响应

```json
{
  "ok": true,
  "plugin": {
    "id": "menran.sing",
    "version": "1.0.0",
    "name": "唱歌",
    "author": "menran",
    "runtime": "data",
    "enabled": false,
    "sizeBytes": 3874201,
    "entries": 3,
    "checks": [
      { "name": "manifest",        "ok": true },
      { "name": "references",      "ok": true, "detail": "3/3 文件存在" },
      { "name": "externalLinks",   "ok": true },
      { "name": "selfTest",        "ok": true, "detail": "3 段音频全部可解码，时长误差 < 1s" }
    ],
    "notice": {
      "memoryWrite": "会向记忆库写入记录（类型：我做过的事）",
      "network": false
    }
  }
}
```

`enabled` 是 `false`。**下载 ≠ 授权**，这是全流程最重要的一处分界。

### 1.4 失败响应（三种典型）

```json
// 缺文件
{ "ok": false, "stage": "references",
  "error": "包里缺少 assets/voice/daoxiang.mp3",
  "hint": "检查 plugin.json 里 catalog.entries[].file 与实际文件是否一致" }

// 音频损坏
{ "ok": false, "stage": "selfTest",
  "error": "assets/voice/qingtian.mp3 解不开",
  "hint": "重新导出一次，确认是标准 MP3 而不是改了扩展名" }

// manifest 写错
{ "ok": false, "stage": "manifest",
  "error": "memory.keyTemplate 必须包含一个冒号，实际是「唱过{song}」",
  "hint": "格式是「实体类型:实体名」，例如 唱过:{song}" }
```

第三条不是吹毛求疵：`key` 里的冒号是指代回填的定位符（见 §4.3），没有它"上次那首"就失效。

### 1.5 落库结果

```sql
-- installed_plugins（每 bot 库）
id            version  enabled  installed_at          last_ok_at  last_error
menran.sing   1.0.0    0        2026-11-06 16:52:10   NULL        NULL
```

```json
// data/plugins/menran.sing/.installed.json（只溯源，运行时不读）
{
  "id": "menran.sing",
  "version": "1.0.0",
  "sha256": "9c1f3a7e5b2d…",
  "source": "upload:menran.sing-1.0.0.zip",
  "installedAt": "2026-11-06T16:52:10+08:00"
}
```

---

## 2. 阶段二：开启

### 2.1 请求

```http
POST /api/plugins/menran.sing/enable
Content-Type: application/json

{ "config": {} }
```

### 2.2 后端三步

```java
// ① 改状态
stateStore.setEnabled("menran.sing", true);        // UPDATE installed_plugins SET enabled=1

// ② 重建内存索引（幂等，可重复调用）
registry.reload();

// ③ 返回新增的意图
```

`registry.reload()` 做的事：

```java
// 扫 data/plugins/*/plugin.json，只保留 enabled=1 且校验通过的
Map<String, List<PluginRef>> byIntent = new HashMap<>();
byIntent.put("music.sing", List.of(
    new PluginRef("menran.sing", "sing", Path.of("data/plugins/menran.sing"))
));
// 关键词倒排：所有 title + aliases → pluginId
Map<String, String> kwIndex = Map.of(
    "七里香", "menran.sing", "周杰伦那个", "menran.sing",
    "晴天",   "menran.sing", "稻香",     "menran.sing"
);
```

### 2.3 响应

```json
{
  "ok": true,
  "plugin": { "id": "menran.sing", "enabled": true },
  "intents": ["music.sing"],
  "keywords": 4,
  "loadedMs": 18
}
```

从这一刻起，`PluginRouter` 的 `registry.isEmpty()` 返回 false，路由开始参与每次对话。

**但成本仍然接近零**：没命中关键词时，判定是一次 HashMap 查找 + 一次长度过滤，实测量级 < 0.1ms。

---

## 3. 阶段三：第一次调用

### 3.1 时序

```
用户           MessageDispatcher      PluginRouter        DataExecutor       OneBot
 │  唱首七里香        │                     │                   │                │
 ├───────────────────▶│                     │                   │                │
 │                    │  RouteRequest       │                   │                │
 │                    ├────────────────────▶│                   │                │
 │                    │                     │ ①catalog 命中      │                │
 │                    │                     │ ②打分 0.94         │                │
 │                    │                     │ ③门控 通过         │                │
 │                    │   Decision(EXECUTE) │ ④EXECUTE           │                │
 │                    │◀────────────────────┤                   │                │
 │                    │                     │                   │                │
 │                    │  execute(decision)  │                   │                │
 │                    ├─────────────────────┼──────────────────▶│                │
 │                    │                     │  PluginResult     │                │
 │                    │◀────────────────────┼───────────────────┤                │
 │                    │                                    读 mp3 │                │
 │                    │  sendPrivateRecord ────────────────────────────────▶│    │
 │                    │                                                     │ 语音条
 │                    │  recordExchange / setLastVoiceFile / memoryWriter   │
 │                    │  return（不再走 LLM）                                │
```

### 3.2 入参（边界①）

`MessageDispatcher` 在 `:282`（`emotional.observe` 之后）构造：

```java
new RouteRequest(
    botId      = "1",
    qqId       = 10001L,
    text       = "唱首七里香",
    userKind   = "text",
    gapMinutes = 42,
    now        = LocalDateTime.of(2026, 11, 6, 16, 58, 20)
)
```

### 3.3 路由四级判定

**① 候选召回**

```java
// 意图索引里 music.sing 有 1 个实现
// 关键词倒排扫描："唱首七里香" 里含 "七里香" → 命中 menran.sing
candidates = [PluginRef("menran.sing", "sing")]
```

**② 打分**（宿主算，插件无权参与）

```
语义相似度 0.92      "唱首七里香" vs examples["唱个七里香", "唱首歌"]
关键词强度 1.00      完整实体命中（4 字，等于 alias 全长）
历史采纳率 0.90      近 30 天触发 11 次，用户没有一次当场纠正

score = 0.5×0.92 + 0.3×1.00 + 0.2×0.90
      = 0.46 + 0.30 + 0.18
      = 0.94
```

**③ 门控**

```java
cooldown   : 距上次执行 3 小时，通过（冷却只对同一 action 的连续触发生效）
quota      : 今日 2/10，本小时 0/3，通过
breaker    : 未熔断，通过
privacy    : 非远程插件，通过
```

**④ 分级**

```
0.94 ≥ 0.75 且 song 已填 → EXECUTE
```

### 3.4 出的 Decision

```json
{
  "pluginId": "menran.sing",
  "actionId": "sing",
  "intentId": "music.sing",
  "params": { "song": "七里香" },
  "score": 0.94,
  "tier": "EXECUTE",
  "reason": "catalog alias 全等 + 语义 0.92"
}
```

### 3.5 执行（data 型不走 HTTP）

`DataPluginExecutor.execute(ctx)` 收到：

```java
PluginContext(
    qqId        = 10001L,
    botId       = "1",
    pluginId    = "menran.sing",
    actionId    = "sing",
    intentId    = "music.sing",
    params      = {"song": "七里香"},
    rawText     = null,          // manifest 没声明 accepts.text，宿主不传原文
    installId   = "b41f0c9e-2d7a-4e55-9c31-8a6f2b7d1e04",
    timeout     = Duration.ofMillis(200)
)
```

内部做三件事：

```java
CatalogEntry e = catalog.find("七里香");        // → qilixiang.mp3, 32s
Path file = root.resolve(e.file());
byte[] mp3 = Files.readAllBytes(file);         // 412 KB
return new PluginResult.Audio(mp3, "audio/mpeg", 32,
        "唱了《七里香》，32 秒",
        Map.of("song", "七里香", "seconds", "32"));
```

**等价于下面这个报文**（process 型插件就是收到它、返回它）：

```json
{
  "ok": true,
  "invokeId": "inv_3f81c0a7",
  "output": { "kind": "audio", "mime": "audio/mpeg",
              "path": "data/plugins/menran.sing/assets/voice/qilixiang.mp3",
              "seconds": 32 },
  "display": { "caption": "唱了《七里香》，32 秒" },
  "memory": { "song": "七里香", "seconds": "32" },
  "elapsedMs": 6
}
```

### 3.6 宿主五步落点

```java
// ① 剥元数据（ID3 里可能有作者机器名、路径）
byte[] clean = AudioSanitizer.stripId3(result.bytes());

// ② 先发（失败就到此为止，不写任何库）
client.sendPrivateRecord(10001L, clean);

// ③ 落库：用户消息 + 助手语音消息
String saved = voiceFiles.save(clean, "mp3");   // vf_20261106_165820_a3f2.mp3
chatService.recordExchange(10001L, "唱首七里香",
        "（唱了《七里香》，32 秒）", "voice", "text");
messages.setLastVoiceFile(10001L, "assistant", saved);

// ④ 记忆
memoryWriter.onSuccess(10001L, manifest, result);

// ⑤ 操作流水
stateStore.addRun(10001L, "menran.sing", "sing", "inv_3f81c0a7",
        "{\"song\":\"七里香\"}", "ok", 32, 6);

// ⑥ 短路
return;
```

`return` 之后不会再有 `chatService.reply()`，所以**不会**出现"语音条 + 一段文字 + 又一条语音"三条齐发。

### 3.7 用户实际看到

一条 32 秒的语音条。没有文字。

### 3.8 落库后的真实数据

```sql
-- messages
id     qq_id  role       content                     kind   voice_file
8842   10001  user       唱首七里香                   text   NULL
8843   10001  assistant  （唱了《七里香》，32 秒）      voice  vf_20261106_165820_a3f2.mp3
```

```sql
-- memories
id     qq_id  type      subject  key          value                    source                importance
1207   10001  relation  bot      唱过:七里香   给他唱了《七里香》，32 秒   plugin:menran.sing    0.85

-- 同一行其余列
confidence=1.0  emotion=0.3  first_seen=last_confirmed=2026-11-06 16:58:20
expires_at=NULL  follow_up_at=NULL  follow_up_state=none
status=active   strength=1.0  strength_at=2026-11-06 16:58:20  pinned=0  recall_count=0
```

```sql
-- plugin_runs
id  qq_id  plugin_id    action_id  task_id       params_json       status  seconds  elapsed_ms
1   10001  menran.sing  sing       inv_3f81c0a7  {"song":"七里香"}  ok      32       6
```

### 3.9 写记忆时插件的身份

```java
new MemoryStore.Draft(
    "relation",                 // 不是 event：event 有 7 天 TTL，会被 maintain 标 expired
    "bot",                      // 主体是"我做过"，不是"他是什么人"
    "唱过:七里香",               // key 带实体名，每首独立
    "给他唱了《七里香》，32 秒",
    "plugin:menran.sing",       // 命名空间，卸载时按前缀清
    0.85,                       // τ≈8.4 天，约 16 天后自然归档
    1.0,                        // 我自己做的事，确定
    0.3,
    null, null, null);
```

宿主会在 `upsert` 之前拦一道，这几条 manifest 覆盖不了：

```java
if (!Set.of("relation", "event").contains(type)) throw;   // 不能落到 profile
if (!"bot".equals(subject)) subject = "bot";              // 强制覆盖
if (importance > 0.85) importance = 0.85;                 // episodic 封顶
if (value.length() > 80) return;                          // 不写半截
```

最后一条尤其重要：`MemoryStore.normalizeType()` 的 `default` 分支落到 `profile`，插件只要写错一个类型字符串，就会变成"用户画像"。

---

## 4. 阶段四：两天后再点

### 4.1 输入

```
用户：上次那首再来一遍
时间：2026-11-08 21:14
距上次：2 天 4 小时（gapMinutes 已超 360，历史窗口降到 6 条）
```

### 4.2 三级抽取全失败

| 级别 | 结果 |
|---|---|
| ① catalog 扫描 | ❌ "上次那首再来一遍" 不含任何 title/alias |
| ② 槽位正则 | ❌ 不以"唱/来/放/整"开头，不匹配 |
| ③ 模型补参 | ⚠️ 语义 0.62，低于 0.75，本来会被判 `FILL_PARAMS` |

如果按常规走，这一步会问"想听哪首？"——而用户刚刚说了"上次那首"。

### 4.3 指代回填

```
原文命中指代词：上次、那首、再来
  ↓
recentDeeds(10001, days=7, limit=5)
```

实际 SQL：

```sql
SELECT * FROM memories
 WHERE qq_id=10001 AND status='active' AND subject='bot' AND type='relation'
   AND last_confirmed >= '2026-11-01 21:14'
 ORDER BY last_confirmed DESC LIMIT 5;
```

命中一行：

```
id=1207  key='唱过:七里香'  value='给他唱了《七里香》，32 秒'  last_confirmed='2026-11-06 16:58:20'
```

取 `key.indexOf(':')` 之后的部分 → `song = "七里香"`。回填完成。

**这一步为什么要单独做**：`MemoryRecall.relevance()` 用二元组分词，`"再唱一遍上次那首"` 切出来是 `再唱/唱一/一遍/遍上/上次/次那/那首`，没有一个能在 `"唱过 七里香"` 里命中，相关度是 **0.00**，`if (rel <= 0) continue` 会在打分前就把它排除。

### 4.4 一条必要的加分规则

回填成功后如果照常算分：

```
0.5×0.62（语义） + 0.3×0（无关键词） + 0.2×0.90 = 0.49
```

**低于 0.75，还是不会执行。** 所以补一条规则：

> **指代回填命中时 `score := max(score, 0.85)`。**

理由：指代词命中 + 同意图 7 天内有过记录，是两个独立条件的交集，比语义相似度更可靠。单靠语义永远救不回"上次那首"，因为它本身没有信息量。

### 4.5 第二次的 Decision

```json
{
  "pluginId": "menran.sing",
  "actionId": "sing",
  "intentId": "music.sing",
  "params": { "song": "七里香" },
  "score": 0.85,
  "tier": "EXECUTE",
  "reason": "指代回填: recentDeeds 命中 唱过:七里香"
}
```

### 4.6 记忆续命

执行后 `memoryWriter` 又写同一条：

```java
upsert(10001, Draft(..., "唱过:七里香", "给他唱了《七里香》，32 秒", ...));
```

`upsert` 走 `sameValue` 分支（`MemoryStore:110`），不是插新行：

```sql
UPDATE memories
   SET last_confirmed='2026-11-08 21:14:03',
       strength=1.0,            -- afterRehearsal()，复习效应回满
       strength_at='2026-11-08 21:14:03',
       recall_count=recall_count+1
 WHERE id=1207;
```

也就是说：**用户再点同一首歌，这条记忆自己续命**，不需要额外维护。反过来，两周没人提，它按 `τ≈8.4 天` 衰减到 0.15 以下被归档，用户再问"上次那首"时机器人答"记不清了，再点一次"——这比假装记得更自然。

---

## 5. 阶段五：卸载

### 5.1 请求

```http
DELETE /api/plugins/menran.sing
```

### 5.2 后端四步（顺序不能乱）

```java
// ① 先停用，让路由立即不再引用它
stateStore.setEnabled("menran.sing", false);
registry.reload();

// ② 取消在途任务（data 型没有，process 型有）
taskManager.cancelByPlugin("menran.sing");

// ③ 删目录（先 rename 到 .trash/，成功后再删）
Files.move(root, trash.resolve("menran.sing-" + epoch));

// ④ 清状态 + 归档记忆
stateStore.delete("menran.sing");
memoryStore.archiveBySource(qqId, "plugin:menran.sing%");
```

第 ④ 步的 SQL：

```sql
UPDATE memories
   SET status='archived'
 WHERE qq_id=? AND source LIKE 'plugin:menran.sing%' AND status='active';
```

**归档，不物理删。** 用户可能在后台翻"这个插件以前给我写过什么"，也可能过两天重新装回来——重新装时这是全新起点，旧记录留着但不再参与检索。

### 5.3 响应

```json
{
  "ok": true,
  "removed": { "files": 5, "bytes": 3874201, "memoriesArchived": 3, "runs": 27 }
}
```

---

## 6. 报文速查

| 阶段 | 方向 | 关键字段 |
|---|---|---|
| 安装 | 前端 → 后端 | `multipart: file` |
| 安装 | 后端 → 前端 | `plugin.enabled=false`、`checks[4]`、`notice.memoryWrite` |
| 开启 | 前端 → 后端 | `POST /enable`（可选 `config`） |
| 开启 | 后端 → 前端 | `intents:["music.sing"]`、`keywords:4` |
| 路由 | 内部 | `RouteRequest{botId,qqId,text,userKind,gapMinutes,now}` |
| 路由 | 内部 | `Decision{pluginId,actionId,intentId,params,score,tier,reason}` |
| 执行 | 插件 → 宿主（或内部） | `output{kind,mime,data/path,seconds}` |
| 执行 | | `display.caption`（进历史的那句话） |
| 执行 | | `memory{song,seconds}`（渲染模板用） |
| 落库 | 宿主 → DB | `messages` 2 行、`memories` 1 行、`plugin_runs` 1 行 |
| 卸载 | 后端 → 前端 | `removed{files,bytes,memoriesArchived,runs}` |

---

## 7. 如果这个插件是 process 型，差别在哪

同一首歌，宿主换成 `HttpPluginExecutor`：

```
① 路由：完全相同（Decision 一模一样）
② 执行：POST http://127.0.0.1:3401/invoke
   ├─ 先 GET /health（结果缓存 60s），不通直接降级，不重试到超时
   ├─ 连接 1s / 首次响应 3s / 总时长 3s
   └─ 失败 3 次 → 熔断 30 分钟，期间不参与召回
③ 出参：多一层 base64 或 path
④ 落点：完全相同（sendPrivateRecord → recordExchange → memoryWriter）
```

只有执行那一层替换，发送、记忆、落库、短路全部复用。这就是为什么 `PluginResult` 要做成一个 sealed interface——上层只认它，不认 runtime。

---

## 8. 这套流程里三个容易做错的地方

| 坑 | 后果 | 正确做法 |
|---|---|---|
| 先写记忆再发送 | 发送失败，但记忆里"唱过"，下次它会说"我上次给你唱过" | **先发后写**，发送抛异常就整段放弃 |
| `caption` 写成用户的事实 | "你喜欢七里香" 被 `MemoryRecall` 当画像吸进去 | 只写机器人做了什么，第一人称 |
| `key` 不带实体名（只写 `唱过`） | `upsert` 遇同 key 归档旧的，永远只记得最后一首 | `唱过:{song}`，安装时强校验必须含冒号 |

---

## 9. 一句话

> 装的时候校验三道、**不启用**；开的时候只多一份内存索引；
> 说一句话命中就发歌、写两行消息一行记忆、然后 `return`；
> 两天后说"上次那首"，从 `唱过:七里香` 里把歌名捞回来，记忆顺手续命一次。
