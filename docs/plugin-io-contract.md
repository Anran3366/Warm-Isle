# 插件出入参契约 v1

> 版本：v1（2026-11-06）
> 适用对象：插件作者（看 §1–§4、§6）、宿主实现者（看 §5、§7）
> 上游文档：`docs/plugin-refactor-plan.md`（改造方案）、`docs/plugin-dev-spec.md`（作者规范）
>
> 这份文档只回答一个问题：**插件收到什么、返回什么、每个字段最后变成什么。**

---

## 0. 三个边界，三套形状

容易混的地方在于"入参"有两个完全不同的对象。先分清：

```
用户消息
   │
   │ ① 对话层入参（宿主内部，Java 对象，非 HTTP）
   ▼
PluginRouter.route(botId, userId, text) → Decision
   │
   │ ② 插件调用入参（HTTP JSON；data 型不走 HTTP，直接查表）
   ▼
插件进程 / 本地 catalog
   │
   │ ③ 插件出参（HTTP JSON；data 型直接返回 bytes）
   ▼
宿主 → 发送 → 写记忆 → 落库
```

| 边界 | 形状 | 谁定义 | 本文档章节 |
|---|---|---|---|
| ① 对话 → 路由 | Java record | 宿主 | §1 |
| ② 宿主 → 插件 | HTTP JSON | 本文档 | §2 |
| ③ 插件 → 宿主 | HTTP JSON | 本文档 | §3 |
| data 型特例 | Java 查表 | 本文档 | §4 |
| 出参 → 框架 | Java 调用序列 | 宿主 | §5 |

**一条贯穿原则**：插件永远不接触 QQ 协议。它只交内容，发不发、怎么发、发给谁，全部由宿主决定。

---

## 1. 边界①：对话层入参

宿主拿到一条私聊消息后，先构造这个：

```java
public record RouteRequest(
    String botId,          // 多账号隔离用
    long qqId,
    String text,           // 原始消息
    String userKind,       // text | image | voice
    long gapMinutes,       // 距上一条消息的分钟数，-1 未知
    LocalDateTime now
) {}
```

路由返回：

```java
public record Decision(
    String pluginId,
    String actionId,
    String intentId,
    Map<String, String> params,   // 已填好的业务参数
    double score,                 // 0–1
    Tier tier,                    // EXECUTE | FILL_PARAMS | PROPOSE | IGNORE
    String reason                 // 命中原因，调试台展示用
) {}

public enum Tier { EXECUTE, FILL_PARAMS, PROPOSE, IGNORE }
```

`params` 在进入边界②之前必须**已经填好**。填参是宿主的事，不是插件的事——插件不该看到原始句子。

---

## 2. 边界②：宿主 → 插件（调用入参）

### 2.1 信封

`POST {runtime.endpoint}/invoke`

```json
{
  "apiVersion": 1,
  "invokeId": "inv_7f3a9c21",
  "pluginId": "menran.sing",
  "actionId": "sing",
  "intentId": "music.sing",
  "params": {
    "song": "七里香"
  },
  "context": {
    "installId": "b41f0c9e-2d7a-4e55-9c31-8a6f2b7d1e04",
    "locale": "zh-CN",
    "now": "2026-11-06T16:40:00+08:00",
    "timezone": "Asia/Shanghai",
    "timeoutMs": 3000
  },
  "output": {
    "kinds": ["audio"],
    "maxBytes": 2097152
  }
}
```

### 2.2 字段说明

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `apiVersion` | int | ✅ | 当前为 `1` | 插件按此分支兼容 |
| `invokeId` | string | ✅ | `^inv_[a-z0-9]{8,32}$` | **幂等键**，重试时复用同一个值 |
| `pluginId` | string | ✅ | 与 manifest `id` 一致 | 插件应校验，不符直接拒 |
| `actionId` | string | ✅ | 必须在 `actions[].id` 里 | |
| `intentId` | string | ✅ | 注册表里的意图 | 仅供插件做分支，不要用它做匹配 |
| `params` | object | ✅ | 只含 `intent.accepts` 声明过的键 | 已由宿主填好，插件不负责解析自然语言 |
| `context.installId` | string | ✅ | UUID v4 | **唯一可用的标识**，与 QQ 号无映射，用户可一键轮换 |
| `context.locale` | string | ✅ | BCP-47 | |
| `context.now` | string | ✅ | ISO-8601 带时区 | 插件不要自己取系统时间 |
| `context.timeoutMs` | int | ✅ | 见 §8.1 | 插件应在此时间内返回 |
| `output.kinds` | string[] | ✅ | `audio`/`image`/`text`/`card` | 宿主接受哪些类型 |
| `output.maxBytes` | int | ✅ | 音频 2MB / 图片 5MB | 超了宿主拒收 |

### 2.3 `params` 从哪来（三级抽取）

这是整套机制能否在自然语言下成立的关键。宿主按顺序尝试，任一成功即停：

**第一级：catalog 精确命中（零 token，覆盖大多数）**

```java
// 1. title 全等
// 2. alias 全等
// 3. title/alias 出现在原文中，取最长命中；长度 < 2 不算
"唱首七里香"     → song = "七里香"      （alias 命中）
"来一段晴天吧"   → song = "晴天"        （子串命中）
"周杰伦那个"     → song = "七里香"      （alias「周杰伦那个」命中）
```

**第二级：槽位抽取（正则，零 token）**

manifest 的 `intent.accepts.song.pattern` 可选，不写则用内置模板：

```
^(唱|来|放|整)(一)?(首|段|个)?\s*(?<song>.+?)\s*(吧|呗|啊|呀)?$
```

**第三级：模型补参（只在分数 ≥ 0.5 且前两级都失败时）**

单次小调用，输入只有"这句话 + 该意图的可选值清单"，**不带对话历史**，超时 1.5 秒：

```json
{ "song": "七里香" }
```

失败或超时 → 放弃本次执行，按 `FILL_PARAMS` 处理（问一句"想听哪首？"），**绝不瞎猜**。

### 2.4 指代回填（"上次那首"能工作的原因）

用户说"上次那首再来一遍"时，`params` 是空的。这条规则接上记忆辅路：

```
若必填参数缺失，且原文命中指代词（上次 / 那首 / 刚才 / 再来 / 又 / 还是）
  → 查 recentDeeds(qqId, 7, 5) 里 intent 相同的最新一条记忆
  → 取其 key 冒号后半段回填 params
```

因为写入时 key 就是 `唱过:七里香`，回填时取 `:` 后面那段即可，不需要额外字段。

**回填成功后必须加分**：

```
score = max(score, 0.85)
```

否则救不回来。实测数字：`"上次那首再来一遍"` 的语义相似度只有 0.62，代入公式 `0.5×0.62 + 0.3×0 + 0.2×0.9 = 0.49`，低于 0.75 的执行线，还是不会执行。

理由是指代词本身没有信息量，语义永远算不高；而"指代 + 7 天内同意图有记录"是两个独立条件的交集，比语义更可靠。

这是 `plugin-refactor-plan.md` §7.2「按时间取」辅路的兑现点。没有它，"上次那首"只能靠模型猜。

### 2.5 明确禁止出现在入参里的字段

| 禁止 | 原因 |
|---|---|
| `qq_id` / `uin` / `nickname` | 唯一身份标识，给了就能关联全平台的这个人 |
| `group_id` | 群场景的社交关系 |
| `raw_text`（原文） | 只在 manifest 声明 `accepts.text` 时，作为**业务参数** `params.text` 出现 |
| `history[]` | 最近 N 条上下文，任何情况都不带 |
| `timestamp`（精确到秒） | 能推断作息。只给 `context.now`，且可通过配置模糊到小时 |
| `.env` / 配置项 | 宿主永不传递 |

> `params.text` 是唯一可能带原文的字段，且必须由 manifest 显式声明。安装页会据此提示："该插件会收到你发送的原始文字"。

### 2.6 不同 runtime 的差异

| runtime | 请求体 | 额外要求 |
|---|---|---|
| `data` | **不发 HTTP** | 走 §4 的本地查表 |
| `prompt` | **不发 HTTP** | 宿主编排：抽牌/渲染模板/调模型/后处理，见 `plugin-prompt-runtime.md` |
| `process` | 同 §2.1 | 必须实现 `GET /health` |
| `inference` | 同 §2.1 + `async` | 必须实现异步契约（§3.2） |

`prompt` 型与 `data` 型一样，入参出参退化成一次本地调用，区别是 `data` 返回素材文件，`prompt` 返回模型生成的文本。它的 `params.text`（用户原句）默认可用，但仅当 manifest 声明 `prompt.acceptsText: true`——因为模板里没有原句时，塔罗只能给通用牌义。

---

## 3. 边界③：插件 → 宿主（调用出参）

### 3.1 同步成功

```json
{
  "ok": true,
  "invokeId": "inv_7f3a9c21",
  "output": {
    "kind": "audio",
    "mime": "audio/mpeg",
    "data": "SUQzBAAAAAAAI1RTU0UAAAAP...",
    "seconds": 32
  },
  "display": {
    "caption": "唱了《七里香》，32 秒"
  },
  "memory": {
    "song": "七里香",
    "seconds": "32"
  },
  "elapsedMs": 1200
}
```

| 字段 | 类型 | 必填 | 约束 | 去向 |
|---|---|---|---|---|
| `ok` | bool | ✅ | | |
| `invokeId` | string | ✅ | 与入参一致 | 幂等校验 |
| `output.kind` | enum | ✅ | `audio`/`image`/`text`/`card` | 决定宿主的发送方式 |
| `output.mime` | string | ✅ | 必须与 kind 匹配 | 不符直接拒收 |
| `output.data` | string | 二选一 | base64，≤ `maxBytes` | 内容本体 |
| `output.path` | string | 二选一 | 同机绝对路径；仅 `data` / `process` 型可用 | 大文件走路径，省一次 base64 |
| `output.seconds` | int | audio 必填 | 1–600 | 记忆模板 `{seconds}`、`caption` 生成 |
| `output.text` | string | text 必填 | ≤ 300 字 | 直接发送 |
| `display.caption` | string | ❌ | ≤ 60 字 | **进入对话历史的那句话**，见 §5.3 || `memory` | object | ❌ | 渲染 `keyTemplate`/`valueTemplate` 的变量 | 不返回则不写记忆 |
| `elapsedMs` | int | ❌ | | 观测用 |

### 3.2 异步（`async: true` 的插件必须实现）

**第一次调用立即返回：**

```json
{
  "ok": true,
  "invokeId": "inv_7f3a9c21",
  "state": "pending",
  "taskId": "t_9a21c",
  "estimateSeconds": 45,
  "ackText": "在合成了，等我一下"
}
```

`ackText` 会立刻作为一条文字发给用户。**没有这个字段，用户会以为机器人没反应。**

**宿主动作**：`messages.add(qqId, "assistant", ackText, "progress")`，`kind='progress'` 不进 prompt 历史（见 §5.4）。

**轮询：**

`GET {endpoint}/task/{taskId}`

```json
{ "state": "running", "progress": 0.4 }
{ "state": "done", "output": { "kind": "audio", "mime": "audio/mpeg", "data": "...", "seconds": 32 },
  "display": { "caption": "唱了《七里香》" }, "memory": { "song": "七里香", "seconds": "32" } }
{ "state": "failed", "error": { "code": "TIMEOUT", "message": "合成超时", "userHint": "这次没合成出来，再试一次？" } }
```

`done` 的 `output` 结构与同步成功完全一致——宿主用同一个处理函数，不写两套。

轮询间隔 1 秒，总时长超 `typicalSeconds × 3 + 30` 判定失败。

### 3.3 失败

```json
{
  "ok": false,
  "invokeId": "inv_7f3a9c21",
  "error": {
    "code": "SONG_NOT_FOUND",
    "message": "catalog 里没有这首歌",
    "userHint": "这首我这儿没有，换一首？",
    "retryable": false
  }
}
```

| code | 含义 | 宿主行为 | 计入熔断 |
|---|---|---|---|
| `NOT_FOUND` | 没这个内容 | 发 `userHint` | ❌ |
| `INVALID_PARAMS` | 参数不合法 | 发 `userHint` | ❌ |
| `QUOTA_EXCEEDED` | 作者额度用尽 | 发 `userHint` | ❌ |
| `UNAVAILABLE` | 服务不可用 | 发 `userHint` | ✅ |
| `TIMEOUT` | 超时 | 发 `userHint` | ✅ |
| `INTERNAL` | 其他 | 发兜底话术 | ✅ |

三条硬规则：

1. **`userHint` 必填且必须是能直接发出去的话**。写 `HTTP 500` 和写"这首我这儿没有，换一首？"是两种产品。
2. **失败不写记忆**。发送成功才写，否则会出现"记忆里唱过、用户没收到"。
3. **失败也要短路**。已经回了话，就不要再走一遍 LLM 生成第二条回复。

### 3.4 宿主侧的出参校验（不可协商）

| 校验 | 不通过时 |
|---|---|
| `invokeId` 与请求一致 | 拒收 |
| `output.mime` 与 `kind` 匹配 | 拒收 |
| `data` 解码后 ≤ `maxBytes` | 拒收 |
| 音频可被 `AudioAnalysis` 解码 | 拒收，按 `INTERNAL` |
| `caption` ≤ 60 字 | 截断，不拒收 |
| 记忆渲染后 `value` ≤ 80 字 | 不写记忆，不报错 |
| 剥离 ID3 / EXIF 元数据 | 总是执行 |

**宿主不加工 `caption`**，只按 kind 包一层全角括号后落库（`（` + caption + `）`）。所以 `caption` 本身必须是一句完整、能独立看懂的话，不要写「已完成」这种半句。

---

## 4. data 型插件的特例（没有 HTTP）

`runtime.type = "data"` 的插件不跑进程、不发请求。"入参出参"退化成一次本地查表：

```
入参：params.song = "七里香"
  ↓  查 manifest.catalog.entries
出参：{ bytes: <mp3 内容>, mime: "audio/mpeg", seconds: 32,
        caption: "唱了《七里香》", memory: {song:"七里香", seconds:"32"} }
```

### 4.1 catalog 结构

```json
"catalog": {
  "key": "song",
  "entries": [
    { "title": "七里香", "aliases": ["七里香", "周杰伦那个"], "file": "assets/voice/qilixiang.mp3", "seconds": 32 },
    { "title": "晴天",   "aliases": ["晴天"],                 "file": "assets/voice/qingtian.mp3",  "seconds": 28 }
  ],
  "ambiguousHint": "想听哪首？"
}
```

### 4.2 匹配算法（宿主实现，作者不用管）

```java
1. title 全等           → 唯一命中
2. alias 全等           → 唯一命中
3. title/alias 是原文子串，取最长命中，长度 < 2 忽略
4. 多命中且分数相同      → 歧义，返回 FILL_PARAMS
```

歧义时宿主拼一句 `ambiguousHint + "《A》还是《B》？"`，并把候选存进 pending proposal（5 分钟），用户下一句说"第一个"或"A"直接执行。

### 4.3 为什么 data 型能"下载即用"

包里没有可执行文件，宿主不认识"唱歌"这个词，只认识 `catalog` 这种数据结构。加一首歌 = 加一行 JSON + 一个 mp3，不用改任何代码。

安全模型要说清楚：**data 型的安全靠的是"包里没有代码"，不是沙箱。** 一旦允许 `plugin.json` 里写脚本，这个前提就破了。

---

## 5. 出参 → 框架落点

这一节是"适配现有框架"的核心。每种 `kind` 对应一串确定的调用。

### 5.1 audio

```java
// ① 剥离元数据 + 校验（见 §3.4）
byte[] audio = AudioSanitizer.strip(result.bytes());

// ② 先发
client.sendPrivateRecord(qqId, audio);

// ③ 发送成功才落库（顺序不能反）
String file = voiceFiles.save(audio, "mp3");
chatService.recordExchange(qqId, userText, "（" + caption + "）", "voice", userKind);
messages.setLastVoiceFile(qqId, "assistant", file);

// ④ 写记忆
memoryWriter.onSuccess(qqId, manifest, result);
```

注意**不用 `markLastAsVoice`**。那个方法的作用是把已有的 `kind='text'` 消息改写成语音（`content='（语音）'||content`），走的是"文字转语音"路径。插件是直接以 `kind='voice'` 落库，不需要二次修改。

### 5.2 image

```java
client.sendPrivateImage(qqId, image);
chatService.recordExchange(qqId, userText, "（发了一张图：" + caption + "）", "image", userKind);
memoryWriter.onSuccess(qqId, manifest, result);
```

### 5.3 text / card

```java
List<String> parts = composer.split(result.text());   // 复用现有分段
for (String part : parts) {
    composer.sleep(composer.delayFor(part));
    client.sendPrivate(qqId, part);
}
chatService.recordExchange(qqId, userText, result.text(), "text", userKind);
```

`caption` 是进历史的那句话，语义上必须完成两件事：**说清做了什么** + **不冒充用户的事实**。

| 场景 | ✅ 好 | ❌ 差 |
|---|---|---|
| 唱歌 | `唱了《七里香》，32 秒` | `你喜欢七里香对吧` |
| 抖音 | `发了一个抖音：外卖小哥的100种死法` | `你刚才在看抖音` |
| 提醒 | `答应 11-08 09:00 提醒他交房租` | `他要交房租` |

**`prompt` 型例外：`caption` 恒为 `null`。** 它输出的本身就是一段话，直接以 `kind='text'` 全文进 `messages`——用户追问"第二张什么意思"时，模型靠这段历史接着讲，而不是重新触发插件。`caption` 机制是为"音频/图片无法进历史"设计的，文本不需要。

第二列的问题都是**把机器人的动作写成了用户的事实**，会被 `MemoryRecall` 误当成用户画像。

### 5.4 落库的四种 kind

| kind | 用途 | 进 prompt 历史 | 寿命 |
|---|---|---|---|
| `text` | 文字回复 | ✅ | 20 条窗口 |
| `voice` | 语音条 | ❌ 折叠进 `mediaNotes` | 20 条窗口 |
| `image` | 图片 | ❌ 折叠 | 20 条窗口 |
| `progress` | 异步中间态（"在合成了"） | ❌ 完全跳过 | 只作日志 |

`progress` 是新增的，`MessageStore.recent()` 已有排除 `voice-test`/`emoji-test` 的先例，加一个 `kind='progress'` 排除即可。

插件产生的 `voice` / `image` 会被 `PromptBuilder.isMediaMarker()` 折叠成：
`"系统记录：你最近发过这些内容（原样已隐藏，不要复述、不要输出括号标记）：（唱了《七里香》）"`

### 5.5 记忆写入

```java
// key = render(manifest.memory.keyTemplate, result.memory)
//     = "唱过:七里香"
// value = render(manifest.memory.valueTemplate, result.memory)
//       = "给他唱了《七里香》，32 秒"
store.upsert(qqId, new MemoryStore.Draft(
        "relation", "bot", key, value,
        "plugin:" + manifest.id(),      // 命名空间
        importance, 1.0, 0.3, null, null, null));
```

`key` 的格式有约束：**`实体类型:实体名`**。因为 §2.4 的指代回填靠 `indexOf(':')` 取后半段。`keyTemplate` 里必须含一个 `:`，由 `PluginInstaller` 在安装时校验。

---

## 6. Java 内部接口

`data` 型在宿主进程内执行，接口就是这个：

```java
public interface PluginExecutor {
    String runtimeType();                                    // "data" | "process" | "inference"
    PluginResult execute(PluginContext ctx) throws PluginException;
}

public record PluginContext(
        long qqId,
        String botId,
        String pluginId,
        String actionId,
        String intentId,
        Map<String, String> params,
        String rawText,          // 仅当 manifest 声明 accepts.text 时非空，其余情况传 null
        String installId,
        Duration timeout
) {}

public sealed interface PluginResult {
    record Audio(byte[] bytes, String mime, int seconds, String caption,
                 Map<String, String> memoryParams) implements PluginResult {}
    record Image(byte[] bytes, String mime, String caption,
                 Map<String, String> memoryParams) implements PluginResult {}
    record Text(String text, String caption,
                Map<String, String> memoryParams) implements PluginResult {}
    record Pending(String taskId, int estimateSeconds, String ackText) implements PluginResult {}
}

public class PluginException extends Exception {
    public enum Code { NOT_FOUND, INVALID_PARAMS, QUOTA_EXCEEDED,
                       UNAVAILABLE, TIMEOUT, INTERNAL }
    private final Code code;
    private final String userHint;
    private final boolean retryable;
}
```

`HttpPluginExecutor` 负责 JSON ↔ record 的转换，两类 runtime 共用同一个 `PluginResult`，上层（发送、记忆、落库）只写一份。

---

## 7. 三个完整示例

### 7.1 唱歌（data，episodic）

**用户说**：`唱首七里香`

**边界①**
```java
Decision{ pluginId:"menran.sing", actionId:"sing", intentId:"music.sing",
          params:{song:"七里香"}, score:1.0, tier:EXECUTE, reason:"catalog alias 全等" }
```

**边界②**：不发 HTTP，本地查表

**边界③**（等价结构）
```json
{ "bytes":"<mp3>", "mime":"audio/mpeg", "seconds":32,
  "caption":"唱了《七里香》", "memory":{"song":"七里香","seconds":"32"} }
```

**落点**
```java
sendPrivateRecord(qqId, mp3);
voiceFiles.save(mp3, "mp3");
recordExchange(qqId, "唱首七里香", "（唱了《七里香》，32 秒）", "voice", "text");
memoryWriter.onSuccess(...);   // key=唱过:七里香, value=给他唱了《七里香》，32 秒
```

**manifest 片段**
```json
{
  "id": "menran.sing", "version": "1.0.0", "apiVersion": 1,
  "runtime": { "type": "data", "entry": "assets/voice" },
  "intent": { "id": "music.sing", "examples": ["唱首歌","来一段","唱个七里香"],
              "accepts": { "song": "string" } },
  "actions": [{ "id": "sing", "params": {"song": {"type":"string","required":true}},
                "output": {"kind":"audio","mime":"audio/mpeg"} }],
  "catalog": { "key": "song", "ambiguousHint": "想听哪首？", "entries": [ ... ] },
  "memory": { "policy": "episodic", "keyTemplate": "唱过:{song}",
              "valueTemplate": "给他唱了《{song}》，{seconds} 秒" }
}
```

### 7.2 查抖音（process，episodic）

**用户说**：`帮我看看这个 https://v.douyin.com/xxx`

**边界②**
```json
{ "apiVersion":1, "invokeId":"inv_8c21", "pluginId":"someone.douyin",
  "actionId":"parse", "intentId":"media.douyin",
  "params":{ "url":"https://v.douyin.com/xxx" },
  "context":{ "installId":"...", "locale":"zh-CN", "now":"2026-11-06T16:40:00+08:00",
              "timezone":"Asia/Shanghai", "timeoutMs":10000 },
  "output":{ "kinds":["card","image"], "maxBytes":5242880 } }
```

**边界③**
```json
{ "ok":true, "invokeId":"inv_8c21",
  "output":{ "kind":"card", "mime":"text/plain",
             "text":"外卖小哥的100种死法 https://v.douyin.com/xxx" },
  "display":{ "caption":"发了一个抖音：外卖小哥的100种死法" },
  "memory":{ "title":"外卖小哥的100种死法", "url":"https://v.douyin.com/xxx" } }
```

**manifest 片段**
```json
"memory": { "policy":"episodic", "keyTemplate":"发过:抖音",
            "valueTemplate":"{title}（{url}）" }
```

`value` 里**必须带标题**。只写"发了一个视频"，用户说"刚才那个外卖小哥的视频"时一个 token 都命中不了。

### 7.3 提醒（commitment）

**用户说**：`后天早上九点提醒我交房租`

**边界②**
```json
"params": { "what":"交房租", "at":"2026-11-08T09:00:00+08:00" }
```

**边界③**
```json
{ "ok":true,
  "output":{ "kind":"text", "mime":"text/plain", "text":"行，后天早上九点提醒你交房租" },
  "display":{ "caption":"答应 11-08 09:00 提醒他交房租" },
  "memory":{ "what":"交房租", "at":"2026-11-08 09:00" },
  "followUp":{ "at":"2026-11-08T09:00:00+08:00", "actionHint":"提醒他交房租" } }
```

**manifest 片段**
```json
"memory": { "policy":"commitment", "keyTemplate":"答应过:{what}",
            "valueTemplate":"{at} 提醒他{what}" }
```

`followUp` 触发 `Draft.withFollowUp(at, hint)`，进入现有 `dueFollowUps()` 的主动兑现链路。

**注意**：`importance` 由宿主按 policy 定（commitment = 0.95），插件不能自报。这条加上 `f1d2920` 修的 `sweep()` 豁免，承诺才撑得到到期那天。

---

## 8. 超时、幂等、额度

### 8.1 超时默认值

| runtime | 连接 | 首次响应 | 总时长 | 重试 |
|---|---|---|---|---|
| `data` | — | 200ms | 200ms | 0 |
| `process` 同步 | 1s | 3s | 3s | 1（复用 `invokeId`） |
| `process` 异步 | 1s | 3s | `typicalSeconds×3+30` | 0 |
| `inference` 同步 | 3s | 10s | 10s | 1 |
| `inference` 异步 | 3s | 3s | `typicalSeconds×3+30` | 0 |

超时一律转 `TIMEOUT` + `userHint`，不重试到用户等 30 秒。

### 8.2 幂等

- 同一个 `invokeId` 在 **10 分钟**内重复调用，插件必须返回相同结果，不得重复执行
- 宿主只在"连接失败"时重试，且复用同一个 `invokeId`
- 异步任务的 `taskId` 由插件生成，宿主按 `invokeId` 去重

### 8.3 额度（宿主侧计数，插件说了不算）

| 层级 | 默认值 | 超出行为 |
|---|---|---|
| 单用户单插件/天 | `quota.perUserPerDay`（默认 10） | 回"今天这个用得太多了，明天再来？" |
| 单用户单插件/小时 | `quota.perUserPerHour`（默认 3） | 同上 |
| 记忆写入/插件/天 | 5 条 | 静默丢弃，后台告警 |
| 全库 episodic 占比 | ≤ 20% | `trim()` 优先淘汰插件流水（已实现） |

插件额度**独立于**主动语音额度（`ChatVoiceReplier` 那套 3 条/天），否则唱两首歌就把语音额度用光了。

### 8.4 熔断

连续 3 次 `UNAVAILABLE` / `TIMEOUT` / `INTERNAL` → 熔断 30 分钟，期间不参与召回。
`NOT_FOUND` / `INVALID_PARAMS` / `QUOTA_EXCEEDED` **不计入**——用户点了一首不存在的歌，不是插件坏了。

---

## 9. 版本演进

`apiVersion: 1` 的兼容承诺：

| 变更 | 是否破坏兼容 |
|---|---|
| 新增可选字段 | ✅ 兼容 |
| 新增 `output.kind` 取值 | ✅ 兼容（插件按 `output.kinds` 分支） |
| 新增 `error.code` 取值 | ✅ 兼容（宿主未知 code 按 `INTERNAL` 处理） |
| 改字段名 / 改语义 / 删字段 | ❌ 必须升 `apiVersion` |
| 改 `params` 的必填性 | ❌ 必须升 |

插件侧必须做的两件事：

1. 忽略不认识的字段（不要用严格反序列化）
2. 校验 `apiVersion`，不支持就直接返回 `{ "ok": false, "error": { "code": "INTERNAL", "userHint": "这个插件版本太旧了，去更新一下" } }`

---

## 10. 自测清单（作者发布前跑一遍）

```
□ /health 返回 200，body 含 {"ok":true}
□ /invoke 收到 apiVersion=1，字段全在
□ 同一个 invokeId 调两次，返回一致，不重复执行
□ params 缺必填项时返回 INVALID_PARAMS + 能直接发出去的 userHint
□ 不存在的歌返回 NOT_FOUND，不是 500
□ 音频 ≤ 2MB、能正常播放、时长与 seconds 误差 < 20%
□ caption ≤ 60 字，且完成"说清做了什么"
□ memory 字段能渲染出 keyTemplate 和 valueTemplate，key 含一个冒号
□ 断网情况下 /invoke 返回 UNAVAILABLE，不是挂住
□ 进程 kill 后重启，/health 在 5 秒内恢复
□ 包里没有任何 http(s):// 外部地址（data 型）
□ 全局搜一遍代码，没有任何地方读取 qq_id / 聊天记录
```

---

## 11. 一句话总结

> **入参只给业务参数和 installId，不给身份和原文。**
> **出参只交内容，不碰协议；失败必带能直接发出去的 userHint。**
> **发送成功才写记忆，写的是机器人的动作，不是用户的事实。**
