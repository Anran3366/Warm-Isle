# 暖屿能力插件改造方案

> 版本：v1（2026-11-06）
> 状态：M0–M4 已实现并上线（2026-11-10）；本文保留为改造记录，最新契约以 `docs/plugin-store-v2.3.md` 和 `docs/plugin-dev-manual-v2.3.md` 为准
> 关联文档：
> - `docs/plugin-io-contract.md` —— **出入参契约**（插件收什么、返回什么、每个字段的框架落点），写代码时对照这份
> - `docs/plugin-store-v2.3.md` —— 当前线上实现：开放发布、集中审核、四类 runtime、密钥轮换
> - `docs/plugin-dev-manual-v2.3.md` —— 面向作者的完整开发手册
> - `docs/plugin-store-design.md` —— 早期设计动机与推演（部分结论已被修正）
> - `docs/plugin-dev-spec.md` —— 面向第三方作者的强制性规范（本方案是其宿主侧实现依据）

---

## 0. 这份方案解决什么

三件事，按顺序：

1. **把插件变成包**：一个 zip，下载解压就能用，装完之后与任何服务器无关。
2. **让机器人会用**：对话里该触发时触发，不该触发时不打扰。
3. **让它记得做过**：做完的事写进记忆，下次提起来能接上。

前两份文档分别回答"为什么这么设计"和"作者该怎么写"。本方案回答**改哪些文件、按什么顺序、怎么验收**。

---

## 1. 定位与非目标

### 1.1 一句话定位

> 插件是**能力包**，不是代码托管。
> 商店负责分发，宿主负责执行与约束，作者只声明意图和产出。

### 1.2 明确的非目标

| 不做 | 原因 |
|---|---|
| 不做插件市场社区（评分、评论、订阅） | 冷启动阶段没人用，先做机制 |
| 不做多租户 SaaS 版商店 | 分发服务只服务本框架的部署实例，量级在几十 |
| 不做插件沙箱（P0） | 2C/3.6G 机器跑不动容器编排；靠"只收数据型包"兜住 |
| 不做插件内 JS/脚本引擎 | 引入解释器等于引入任意代码执行，收益不抵风险 |
| 不改现有对话主链路的输出格式 | 插件命中才短路，未命中路径必须与今天完全一致 |

### 1.3 一条贯穿全文的原则

**未装插件时，一切行为与今天逐字节相同。**
所有改动必须有"空实现快速返回"的分支，否则就是拿线上稳定性换新功能。

---

## 2. 现状盘点（代码事实）

### 2.1 可直接复用的

| 能力 | 位置 | 用途 |
|---|---|---|
| 发语音条 | `OneBotClient.sendPrivateRecord(long, byte[])` `:514` | 插件音频输出 |
| 语音文件落盘 | `VoiceFileService.save(byte[], ext)` `:31` | 生成可回放的 URL |
| 记忆写入 | `MemoryStore.upsert(qqId, Draft)` `:96` | 插件直写记忆 |
| 记忆强度模型 | `MemoryStrength` 全套 | 衰减、复习、归档 |
| 语义向量 | `EmbeddingClient.embedOne()` / `MemoryVectorService.cosine()` | 意图召回（P2） |
| 对话记录 | `ChatService.recordExchange(...)` `:316` | 短路时补历史 |
| 多账号库 | `Db`（每 bot）/ `SharedDb`（跨 bot）/ `GlobalDb` | 插件状态按账号隔离 |
| 配置类范式 | `BotProperties` + `application.yml` | `PluginProperties` 照抄 |

### 2.2 关键接入点

`MessageDispatcher.handleEvent()` 里，消息处理的实际顺序是：

```
:263  话题屏蔽 topicBlock.checkSemantic
:278  proactive.onUserMessage / maybeCreateFollowup
:280  moods.observe / moodJudge.judge / emotional.observe
:284  mediaPlanner.plan          ← 插件钩子插在这之前
:286  chatService.reply
:314  chatVoice.maybeSend
:321  client.sendPrivate（文字）
```

**插件钩子的位置：`:282` 之后、`:284` 之前。**

选这里的三个理由：

1. **能拿到 `OneBotClient`**。`ChatService` 里没有 client，把 client 注进去会污染这个纯文本服务的边界。
2. **短路就是 `return`**，不需要给 `reply()` 加 `handled` 返回值，不改变现有方法签名（线上 5 处调用点零改动）。
3. **状态更新（心情、熟悉度、主动消息）仍然执行**。插件回完歌，情绪观察不该漏掉。

> 修正：早期设计写的是"在 `ChatService.reply()` 里 `handled=true` 短路"。按代码实际情况，改为在 `MessageDispatcher` 短路更干净。

### 2.3 今天已有的两个前提（已修复）

commit `f1d2920` 修掉的两处，是插件记忆能成立的地基：

- `sweep()` 不再归档 `follow_up_state='pending'` 的记忆 → `commitment` 类插件答应的事撑得到到期
- `trim()` 优先淘汰 `source LIKE 'plugin%'` → 插件流水不会挤掉用户真实记忆

### 2.4 还缺的

| 缺什么 | 影响 |
|---|---|
| 没有 `com.qqbot.plugin` 包 | 一切从零 |
| `PromptBuilder.isMediaMarker()` `:202` 只认「（语音」「（表情包」 | 「（唱了《七里香》）」会作为完整 assistant 台词进历史，模型会照抄 |
| `MemoryRecall` 只有相关性一条通路 | 「上次那首」相关度 0.00，检索不到 |
| `MemoryRecall.promptBlock()` 不区分 subject | `subject='bot'` 的记录会污染用户画像 |
| `normalizeType()` 的 `default` 落到 `profile` | 类型拼错就变成"用户画像"，插件接入前必须收口 |

---

## 3. 总体架构

### 3.1 四层

```
┌─────────────────────────────────────────────────┐
│ 分发层  store.example.com                          │
│ 上传 / 审核 / index.json / zip 下载               │
│ 挂掉不影响任何已装插件                             │
└───────────────────┬─────────────────────────────┘
                    │ 安装时下载一次
┌───────────────────▼─────────────────────────────┐
│ 包层  data/plugins/<id>/                         │
│ plugin.json + assets/ (+ 可选可执行实现)          │
│ 自足、离线可运行、sha256 可校验                    │
└───────────────────┬─────────────────────────────┘
                    │ 宿主扫描 + 校验 + 注册
┌───────────────────▼─────────────────────────────┐
│ 运行时层  com.qqbot.plugin                       │
│ PluginRegistry → PluginRouter → PluginExecutor  │
│ 意图注册表 / 打分仲裁 / 分级 / 提议节流 / 熔断     │
└───────────────────┬─────────────────────────────┘
                    │ 命中才短路
┌───────────────────▼─────────────────────────────┐
│ 对话层  MessageDispatcher → ChatService          │
│ 未命中：与今天完全一致                            │
└─────────────────────────────────────────────────┘
```

### 3.2 一次调用的生命周期

```
用户消息
  │
  ├─ 话题屏蔽（现有，优先级最高）
  │
  ├─ PluginRouter.route(botId, userId, text)
  │     ① 候选召回：意图注册表 + 关键词/示例
  │     ② 仲裁打分：语义 + 关键词强度 + 历史采纳率
  │     ③ 门控：冷却 / 熔断 / 额度 / 隐私模式
  │     ④ 分级：EXECUTE / FILL_PARAMS / PROPOSE / IGNORE
  │
  ├─ EXECUTE → PluginExecutor.execute()
  │     ├─ 成功 → 发送（音频/图片/文字）
  │     │        → PluginMemoryWriter.onSuccess()
  │     │        → messages.add(..., "voice")
  │     │        → return（短路，不再走 LLM）
  │     └─ 失败 → 用 userHint 回一句 → 不写记忆 → return
  │
  └─ 其余三档 → 继续走原有链路
```

---

## 4. 包格式

### 4.1 目录结构

```
data/plugins/
  menran.sing/                  # 目录名 = 插件 id
    plugin.json
    assets/
      voice/qilixiang.mp3
    README.md
    .installed.json             # 宿主写的安装溯源，运行时不读
```

`data/plugins/` 必须位于现有备份卷内（`tar data/qqbot`），否则换机器插件全丢而记忆还在。

### 4.2 `plugin.json` 全字段

```jsonc
{
  "id": "menran.sing",                    // 必填，^[a-z0-9][a-z0-9._-]{2,48}$，发布后不可改
  "version": "1.0.0",                     // 必填，semver
  "apiVersion": 1,                        // 必填，宿主契约版本
  "minHostVersion": "1.2.0",              // 可选
  "name": "唱歌",
  "author": "menran",
  "description": "点歌，返回一段演唱音频",
  "license": "MIT",

  "runtime": {
    "type": "data",                       // data | process | inference
    "entry": "assets/voice",              // data 型的素材根目录
    "endpoint": "http://127.0.0.1:3401",  // process 型
    "async": false,                       // inference 型必须显式声明
    "typicalSeconds": 2
  },

  "intent": {
    "id": "music.sing",                   // 必须存在于意图注册表
    "examples": ["唱首歌", "来一段", "唱个七里香"],  // 只用于生成向量，不做字符串匹配
    "accepts": { "song": "string", "style": "enum?" }
  },

  "actions": [
    {
      "id": "sing",
      "params": { "song": { "type": "string", "required": true } },
      "output": { "kind": "audio", "mime": "audio/mpeg" }
    }
  ],

  "catalog": {                            // data 型独有：宿主直接查表
    "key": "song",
    "entries": [
      { "title": "七里香", "aliases": ["七里香", "周杰伦那个"],
        "file": "assets/voice/qilixiang.mp3", "seconds": 32 }
    ]
  },

  "memory": {
    "policy": "episodic",                 // none（默认）| episodic | commitment
    "keyTemplate": "唱过:{song}",
    "valueTemplate": "给他唱了《{song}》，{seconds} 秒"
  },

  "proposal": { "enabled": false },       // 是否允许主动提议，默认关闭
  "quota": { "perUserPerDay": 10, "perUserPerHour": 3 },
  "resources": [],                        // 安装时下载的外部资源（模型/音色）
  "permissions": { "network": false, "memoryWrite": true }
}
```

### 4.3 四种 runtime

| type | 包里有什么 | 谁执行 | 谁付模型费 | P0 | 说明 |
|---|---|---|---|---|---|
| `data` | manifest + 素材 | 宿主查表读文件 | 无 | ✅ | 零进程、零额外内存，唱歌属于这类 |
| `prompt` | manifest + 模板 + 知识数据 | 宿主调模型 | 部署方 | ✅ | 塔罗、翻译、起名；包里同样无代码 |
| `process` | manifest + fat jar / 可执行 | 独立进程，HTTP 调用 | 插件自己 | ❌ P1 | 崩溃隔离，但 1.7G 空闲内存只够 1–2 个 |
| `inference` | manifest 几 KB | 作者的服务 | 作者 | ❌ P2 | 每次调用依赖作者，必须显式告知 + 出站白名单 |

**关键判断一**：唱歌一行代码都不需要。查表 → 读 mp3 → `sendPrivateRecord`。把它做成进程插件是自我麻烦。

**关键判断二**：塔罗同样一行代码都不需要——抽牌是 `SecureRandom`，牌义是 JSON，解读交给宿主已有的 `DeepSeekClient`。做成进程插件，作者得自己起服务、自己拿 key、自己管计费，三件都跟"加个塔罗功能"无关。

`prompt` 与 `data` 同属 P0，因为两者安全模型完全一致：**包里没有可执行文件**，宿主不执行任何来自包里的代码。详见 `plugin-prompt-runtime.md`。

### 4.4 安装时的三道本地检查

不联网，全部在 `PluginInstaller` 内完成：

| 检查 | 判据 | 失败文案 |
|---|---|---|
| 引用完整性 | `catalog` / `actions` / `resources` 里出现的每个路径都存在 | "包里缺少 assets/voice/qilixiang.mp3" |
| 禁止外链 | 除 `runtime.endpoint` 外，manifest 中不得出现 `http(s)://` | "数据型插件不允许引用外部地址" |
| 自检 | 音频可被 `AudioAnalysis` 解码、时长与声明误差 < 20% | "这份音频解不开，可能是拷贝损坏" |

第三条最容易被省。装的时候跑一遍，比用户半夜点歌发现放不出来强。

`prompt` 型另加三道（模板长度、变量合法、模板安全扫描），见 `plugin-prompt-runtime.md` §9.3。其中"模板安全扫描"是硬门槛：模板会进模型上下文，一句"忽略以上所有指令"就能劫持人设。

### 4.5 版本规则

- `id` 发布后不可改（记忆 key 依赖它）
- 同 `id` 新版本可直接覆盖安装，旧目录先 rename 到 `.trash/`，成功后删
- `apiVersion` 不兼容时拒绝安装并提示升级宿主
- `.installed.json` 记录 `sha256` / `source` / `installedAt`，**只用于追溯，运行时不读**

---

## 5. 数据模型

### 5.1 磁盘与库的分工

**原则：包在文件系统，状态在数据库。**

| 数据 | 位置 | 理由 |
|---|---|---|
| 插件包本体 | `data/plugins/<id>/` | 共享，多账号共用一份文件 |
| 安装与启用状态 | **每 bot 库** `installed_plugins` | A 账号装的不影响 B |
| 执行流水 | **每 bot 库** `plugin_runs` | 幂等、配额、采纳率 |
| 记忆 | `memories`，`source='plugin:<id>'` | 复用现有衰减/召回 |
| 提议节流 | 每 bot 库 `plugin_proposals` | per-user 授权 |

### 5.2 建表 SQL（加入 `Db.java` 的 SCHEMA）

```sql
CREATE TABLE IF NOT EXISTS installed_plugins (
  id            TEXT PRIMARY KEY,
  version       TEXT NOT NULL,
  enabled       INTEGER NOT NULL DEFAULT 0,   -- 默认不启用
  config_json   TEXT,
  installed_at  TEXT NOT NULL,
  last_ok_at    TEXT,
  last_error    TEXT
);

CREATE TABLE IF NOT EXISTS plugin_runs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  qq_id        INTEGER NOT NULL,
  plugin_id    TEXT NOT NULL,
  action_id    TEXT NOT NULL,
  task_id      TEXT,                -- 幂等键
  params_json  TEXT,
  status       TEXT NOT NULL,       -- ok | failed | timeout
  seconds      INTEGER,
  elapsed_ms   INTEGER,
  created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plugin_runs_user
  ON plugin_runs(qq_id, plugin_id, created_at);

CREATE TABLE IF NOT EXISTS plugin_proposals (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  qq_id       INTEGER NOT NULL,
  plugin_id   TEXT NOT NULL,
  offered_at  TEXT NOT NULL,
  accepted    INTEGER,              -- NULL=未回应 0=拒绝 1=接受
  expires_at  TEXT NOT NULL
);
```

`enabled` 默认 **0**。下载 ≠ 授权。用户装了十个只开两个，机器人就不该被十个插件抢话。

### 5.3 记忆的命名空间

`source` 从笼统的 `plugin` 改为 **`plugin:<id>`**：

```
plugin:menran.sing
plugin:someone.douyin
```

三个刚需：

1. **卸载能清干净**：`DELETE WHERE source LIKE 'plugin:menran.sing%'`
2. **按插件配额**：「5 条/天」是 per-plugin 的，笼统一个 `plugin` 算不出来
3. **可追溯**：后台能回答"这条记忆是谁写的"

今天 `trim()` 里的 `source='plugin'` 判定要同步改成 `source LIKE 'plugin%'`（已兼容，无需迁移）。

---

## 6. 后端改造清单

### 6.1 新增包 `com.qqbot.plugin`（12 个类）

| # | 类 | 职责 | 估行数 |
|---|---|---|---|
| 1 | `PluginManifest` | `plugin.json` 的 record 映射，含嵌套 record | 120 |
| 2 | `PluginPackage` | 包元信息：路径、sha256、体积、解析后的 manifest | 40 |
| 3 | `PluginInstaller` | 解压 → 三道检查 → 原子 rename → 写 `.installed.json` | 200 |
| 4 | `PluginRegistry` | 启动扫描 `plugins/`，合并 DB 状态，内存索引 `<id, PluginPackage>` | 150 |
| 5 | `PluginStateStore` | `installed_plugins` 读写 | 90 |
| 6 | `PluginRouter` | 召回 → 打分 → 门控 → 分级，返回 `Decision` | 260 |
| 7 | `PluginExecutor` | 接口 `execute(ctx) → PluginResult` | 30 |
| 8 | `DataPluginExecutor` | 数据型：查 catalog → 读文件 → 返回 bytes | 90 |
| 9 | `PluginResult` | record：kind / bytes / mime / text / seconds / params / userHint | 40 |
| 10 | `PluginMemoryWriter` | 渲染模板 → 硬约束校验 → `upsert` | 90 |
| 11 | `PluginProperties` | `plugin.enabled` / `plugin.dir` / `plugin.store.*` | 40 |
| 12 | `PluginController` | 后台 API：列表 / 安装 / 启停 / 卸载 / 详情 | 180 |

P1 再加：`HttpPluginExecutor`、`PluginStoreClient`、`PluginProposalStore`、`PluginQuotaStore`。

### 6.2 改动现有文件（5 处）

| 文件 | 位置 | 改什么 | 风险 |
|---|---|---|---|
| `MessageDispatcher.java` | `:282` 之后 | 插入 `pluginRouter.route()` + `execute()` + 短路 | 中 |
| `PromptBuilder.java` | `:202` `isMediaMarker` | 增加「（唱了」「（播放了」前缀 | 低 |
| `MemoryRecall.java` | `:160` `promptBlock` | 加第四块【我最近做过的】 | 低 |
| `MemoryRecall.java` | `groupOf` | `subject='bot'` 不进【你们之间】 | 低 |
| `Db.java` | SCHEMA | 加 3 张表 | 低 |
| `application.yml` | 新增段 | `plugin.*` | 无 |

### 6.3 接入代码（`MessageDispatcher`）

```java
// :282 emotional.observe 之后
com.qqbot.plugin.PluginRouter.Decision decision =
        pluginRouter.route(botId, userId, userText);

if (decision != null && decision.tier() == PluginRouter.Tier.EXECUTE) {
    OneBotClient client = registry.get(botId);
    if (client != null && pluginRouter.execute(decision, userId, client)) {
        return;                       // 短路：语音/图片已发，记忆已写
    }
    // 执行失败不 return，用兜底话术回一句后继续正常回复会更怪，
    // 所以失败时也 return，但先发 userHint
}
```

`route()` 的第一行必须是：

```java
if (!props.isEnabled() || registry.isEmpty()) {
    return null;                      // 未装插件时零开销
}
```

### 6.4 明确不动的

| 不动 | 原因 |
|---|---|
| `ChatService` 签名与内部逻辑 | 5 处调用点、回复后处理（防重复/时间感）都很敏感 |
| `ReplyMarkers` | 插件不走标记机制，走独立短路 |
| `MediaPlanner` | 插件命中在它之前，不参与 |
| 现有 `facts` 表 | 懒迁移保持原样 |

---

## 7. 记忆改造（四步）

### 7.1 写入：插件直写，不经过抽取器

```java
@Component
public class PluginMemoryWriter {

    public void onSuccess(long qqId, PluginManifest m, PluginResult r) {
        Memory mem = m.memory();
        if (mem == null || "none".equals(mem.policy())) {
            return;
        }
        String key = render(mem.keyTemplate(), r.memoryParams());
        String value = render(mem.valueTemplate(), r.memoryParams());

        // 硬约束，manifest 覆盖不了
        if (key.isBlank() || value.isBlank() || value.length() > 80) return;

        double importance = "commitment".equals(mem.policy()) ? 0.95 : 0.85;
        MemoryStore.Draft d = new MemoryStore.Draft(
                "relation",              // 不用 event：event 有 7 天 TTL
                "bot",                   // 主体是"我做过"，不是"他是什么人"
                key,
                value,
                "plugin:" + m.id(),
                importance,
                1.0,                     // 我自己做的事，确定
                0.3,
                null, null, null);

        if ("commitment".equals(mem.policy()) && r.followUpAt() != null) {
            d = d.withFollowUp(r.followUpAt(), r.actionHint());
        }
        store.upsert(qqId, d);
    }
}
```

三个细节：

- **key 带实体名**（`唱过:七里香`，不是 `唱过`）。`upsert` 遇同 key 会归档旧记录，只写 `唱过` 就永远只记得最后一首。
- **不用 `event`**。`maintain()` 会按 `expires_at` 标 `expired`，7 天后查不到；`relation` 只走强度衰减。
- **importance 0.85** → `τ = 5.0 × (0.4 + 1.5×0.85) ≈ 8.4 天`，跌破 0.15 约 16 天后自然归档。这个尺度是故意的：一个月后说"上次那首"，答"记不清了，再点一次"比假装记得更自然。

### 7.2 召回：加一条"按时间取"的辅路

这是解决"上次那首"的核心。现有 `recall()` 回答的是"这句话和哪条记忆相关"，而"我最近干过什么"问的是"最近发生过吗"——两者正交，`if (rel <= 0) continue` 会在打分前就把它排除。

```java
/** 我最近做过的事：按时间倒序，不参与相关性打分 */
public List<Memory> recentDeeds(long qqId, int days, int limit) {
    return query("qq_id=? AND status='active' AND subject='bot' AND type='relation' "
            + "AND last_confirmed >= ? ORDER BY last_confirmed DESC LIMIT ?",
            qqId, ts(LocalDateTime.now().minusDays(days)), limit);
}
```

`promptBlock()` 在三块之后拼第四块，**最多 3 条、封顶 150 字**：

```
【我最近做过的】
- 11-06 给他唱了《七里香》，32 秒
- 11-03 给他唱了《晴天》，28 秒
```

这一块**不经过 `rel <= 0` 过滤**，否则永远是空的。

### 7.3 隔离：`subject='bot'` 一律不进【你们之间】

`relevance()` 只比 `key + value`，完全不看 subject。不处理的话，用户问"我喜欢七里香吗"，`唱过:七里香` 会被命中，模型就以为**用户**喜欢七里香。

改 `groupOf()` 让它能拿到整条记录：

```java
private String groupOf(Memory m) {
    if ("bot".equals(m.subject())) return "【我最近做过的】";
    return switch (m.type()) {
        case "profile" -> "【关于对方】";
        case "event"   -> "【最近发生】";
        default        -> "【你们之间】";
    };
}
```

第四块用独立 formatter，只输出 `日期 + value`，不要 `key：value` 拼在一起（会渲染成"唱过:七里香：《七里香》，32秒"，啰嗦且重复）。

### 7.4 折叠：把「（唱了…）」归入 `isMediaMarker`

```java
return c.startsWith("（表情包") || c.startsWith("（我发了一张表情包")
        || c.startsWith("（语音") || c.startsWith("（表情包缺失）")
        || c.startsWith("（唱了") || c.startsWith("（播放了");
```

理由不只是防模仿：它本来就不该混进 assistant 的对话历史。折叠进 `mediaNotes`（"你最近发过这些内容，不要复述"）后模型照样知道，但不会当台词照抄。

配套：`ReplyMarkers` 加一条 `FAKE_SING` 正则，拦掉模型自己编的"（唱了《x》）"。

### 7.5 manifest 的三种 policy

| policy | 适用 | 宿主行为 | importance 上限 |
|---|---|---|---|
| `none`（默认） | 天气、翻译、计算 | 不写 | — |
| `episodic` | 唱歌、画图、查抖音 | 写一条，进【我最近做过的】 | 0.85 |
| `commitment` | 提醒、日程 | 写一条 + 排 `follow_up_at` | 0.95 |

判断标准一句话：**用户会不会在另一个话题里主动回头提它？**

选错 `none` 没有惩罚；选错 `episodic` 会污染整个记忆库。

### 7.6 记忆改造的验收

| 说法 | 通路 | 期望 |
|---|---|---|
| 再唱一次七里香 | 主路（相关度 0.73） | 召回 |
| 你唱过七里香吗 | 主路（1.00） | 召回 |
| 上次那首再来一遍 | **辅路（按时间）** | 召回 |
| 你还记得给我唱过什么吗 | 主路 0.28 + 辅路兜底 | 召回 |
| 我喜欢七里香吗 | 主路，但 bot 记录被隔离 | 不误答 |

---

## 8. 触发治理

### 8.1 为什么关键词必然会失控

插件作者有三个天然动机，任一成立就破坏全局秩序：希望自己被触发（词写得越来越宽）、无法预知别人的词表（只能多写）、`priority: 100` 没有成本（于是所有人都是 100）。

**加字段解决不了**。300 个插件、3 个作者，人肉协调不动。

### 8.2 三级流水线

| 阶段 | 做什么 | 成本 |
|---|---|---|
| ① 召回 | 意图注册表向量 + 关键词命中 | 纯 CPU < 1ms；embedding 200ms 超时降级 |
| ② 仲裁 | 宿主侧打分，**不信 plugin 自报的 priority** | 一次排序 |
| ③ 分级 | 按分数决定执行 / 补参 / 提议 / 忽略 | 一次查表 |

打分公式（宿主算，插件无权参与）：

```
score = 0.5 × 语义相似度(消息, 意图向量)
      + 0.3 × 关键词命中强度        # 命中长度、是否短语边界
      + 0.2 × 该插件历史采纳率       # 触发后用户没立刻纠正
      × 冷却与额度门控              # 熔断中直接出局
```

### 8.3 分级阈值

| 分数 | 行为 | 例子 |
|---|---|---|
| ≥ 0.75 且参数齐 | **EXECUTE**：直接执行，短路 | "唱首七里香" |
| 0.5–0.75 或缺参数 | **FILL_PARAMS**：注入单条描述让模型补参 | "唱首那个…周杰伦的" |
| 0.35–0.5 | **PROPOSE**：不执行，让模型自然问一句 | "今天好累啊" |
| < 0.35 | **IGNORE**：完全不参与，零开销 | "明天几号" |

第四条特例：**指代回填命中时 `score := max(score, 0.85)`**。"上次那首再来一遍"的语义只有 0.62，按公式算是 0.49，永远够不到执行线；而"指代词 + 7 天内同意图有记录"是两个独立条件的交集，比语义可靠。细节见 `plugin-io-contract.md` §2.4。

第三条需要一个 **pending proposal 状态**，存活 5 分钟。用户回"好啊"，直接执行上次的提议——否则"好啊"这种两个字的消息永远匹配不上任何意图，提议就成了空头支票。

### 8.4 两条硬规则

- **一条消息最多执行 1 个能力插件**，第二个降级为提议、不排队
- **连续失败 3 次熔断 30 分钟**，期间不参与召回

### 8.5 提议节流（写死在宿主，不靠提示词）

| 约束 | 值 |
|---|---|
| 同一插件 24h 内提议 | ≤ 1 次 |
| 全部插件 24h 内合计 | ≤ 2 次 |
| 被拒绝后静默 | 7 天 |
| 一次会话内 | ≤ 1 次 |
| 采纳率 < 20%（样本 ≥ 5） | 自动关闭该插件的提议权限 |

**情绪倾诉永不提议**，这条写死在代码里，不给模型判断空间：

```java
private static final String[] VENTING = {
    "累", "烦", "难受", "难过", "想哭", "被骂", "吵架", "崩溃", "压力"
};
```

用户说"好累啊"时机器人回"要不要我给你唱首歌"——这是插件生态最容易翻车的地方，而且不是模型调皮，是 over-eager tool use 的必然结果。分界线在于：**倾诉需要的是共情，不是服务**。

### 8.6 演进顺序

| 阶段 | 内容 | 扛得住 |
|---|---|---|
| P0 | 关键词 + 单插件 + 完整 manifest 契约 | 3–5 个官方插件 |
| P1 | 意图注册表 + 宿主打分 + 冷却熔断 | 几十个 |
| P2 | 意图向量替换关键词 + 提议节流 | 上百个 |
| P3 | 发布冲突检测 + 调试台 | 生态自净 |

不用一步到位。P1 的意图注册表只是"一张表 + 安装时校验 + 一个打分函数"，不涉及向量。

> 参考：NoneBot2 的 942 个插件不打架，靠的是 `command_start`（默认 `/`）这个命令前缀，而不是 priority。暖屿是拟人陪伴，用户不会打 `/唱首歌`，所以那层保护伞不存在——必须自建意图命名空间。

---

## 9. 分发

### 9.1 拓扑

```
作者 ──POST /api/upload──▶ store.example.com ──审核──▶ published
                                    │
别人的机器人 ──GET index.json───────┤
             ──GET <id>/<ver>.zip───┘
                                    │
                            装完即断开，运行期不回连
```

### 9.2 服务端最小实现（约 400 行 Node）

独立服务，**不要塞进 blog-app**（一个是内容站，一个是分发服务，生命周期和访问量都不同）：

```
/opt/plugin-store/
  index.json                    # 由 DB 重建，可缓存
  packages/menran.sing/1.0.0.zip

服务：127.0.0.1:3500
站点：store.example.com（Caddy 反代）
```

| 接口 | 谁调 | 干什么 |
|---|---|---|
| `POST /api/upload` | 作者 | 收 zip → 校验 → `pending`。鉴权走 `X-Upload-Token` 请求头 |
| `GET /api/plugins/index.json` | 别人的机器人 | **按客户端 apiVersion 过滤**后返回列表 |
| `GET /api/plugins/<id>/<ver>.zip` | 别人的机器人 | 下载包 |
| `POST /api/admin/publish` | 只有你 | 审核通过才上架 / 下架 |

### 9.3 五条必须提前定的

1. **`plugin.store.url` 可配置**，别人 fork 后能指到自己的商店或关掉
2. **商店挂了不能崩**：客户端本地缓存索引，拉不到用上次的；已装插件完全不受影响；**绝不在启动路径上同步拉索引**
3. **sha256 从第一天就要校验**（签名放 P1）
4. **版本兼容双向**：插件声明 `minHostVersion`，索引按客户端版本过滤
5. **下架 ≠ 远程卸载**：只断掉新下载，已装的继续能用，后台标"已下架"

### 9.4 一句要写进规范的话

> 插件运行期不得回连商店。分发是取包，运行必须自足。

否则你的服务器一挂，所有人装的东西一起失效——那就不是"加功能"，是把整个生态吊在一台 2C 的轻量云上。

### 9.5 责任边界

做了中心分发，你就从"作者"变成了**分发者**。三条最低成本防线：

1. **P0 只收数据型包**（包里禁止任何可执行文件）——收不了代码，就没有"这插件把我号搞封了"这种事
2. **人工过一眼**，一分钟一个
3. **下架按钮好用**，出事能立刻断掉新下载

---

## 10. 隐私与安全边界

### 10.1 先纠正一个错误说法

`docs/plugin-dev-spec.md` 里写过"`endpoint` 强制 127.0.0.1，插件连不上外网"——**这是错的**。`127.0.0.1` 只限制谁能访问插件，完全不限制插件能访问谁。

同机部署的真实含义是：**装一个进程型插件 = 把聊天记录、DeepSeek key、QQ 号、NapCat 的发送能力一并交给作者。**

这不是校验字段能解决的，是产品契约。

### 10.2 两类泄漏，防护手段完全不同

| 泄漏通道 | 能挡吗 | 手段 |
|---|---|---|
| 读本地数据库 | **能** | 独立系统用户 + 文件权限（`chmod 600`） |
| 读 `.env` 里的 key | **能** | 同上 |
| 请求体夹带 QQ 号/昵称 | **能** | 宿主构造请求体，不由插件决定 |
| 源 IP | 部分 | 公共 relay / 代理出口 |
| 调用时间与频率 | **不能** | 只能减少调用次数 |
| 返回内容隐写 | **不能** | 剥离元数据 + 抽样检查 |

### 10.3 出站白名单（写死在宿主）

```
允许的请求体字段：业务参数（歌名/图片提示词/查询词）、installId、语言、格式
禁止：QQ 号、昵称、群号、聊天原文、精确时间戳、最近 N 条上下文
```

`installId` 是安装时随机生成的 UUID，与 QQ 号无映射，可一键轮换。

**这条落实之后，"插件作者能拿到什么"就由宿主代码决定，而不是由作者良心决定。**

### 10.4 回来的东西也要处理

音频/图片可能带 ID3 / EXIF / 自定义字段。宿主收到后**先剥离元数据再转码**，再发给用户。

### 10.5 三个产品开关

| 开关 | 作用 |
|---|---|
| 隐私模式 | 只允许 `data` 型插件，远程一律不触发 |
| 安装时告知 | "该插件会把歌名发送到 api.someone.com，不发送聊天内容" |
| 调用日志 | 后台可看最近 7 天每次远程调用的目标域名 + 脱敏请求体 |

### 10.6 插件绝对不能碰 OneBot

如果插件能直接调 NapCat，它就能群发、加好友、刷图、@全体——**封的是你的号**。

宿主必须独占协议层：插件只能返回"内容"，发不发、怎么发由宿主决定。这条比任何沙箱都重要，而且现在就能写死。

---

## 11. 前端

新增 `web/src/views/PluginsView.vue`（约 400 行），沿用现有页面的表格 + 抽屉范式。

| 区域 | 内容 |
|---|---|
| 列表 | 名称 / 作者 / 版本 / 状态（启用·停用·已下架）/ 调用次数 / 成功率 |
| 操作 | 启用、停用、卸载、查看详情、查看写入的记忆 |
| 安装 | 上传 zip（本地）/ 从商店安装（P1） |
| 详情抽屉 | manifest 全字段、素材体积、最近 20 次调用流水、写入的记忆列表 |
| 告警 | 插件声明了 `network` 或 `memoryWrite` 时，启用前必须二次确认 |

安装确认弹窗是全套流程最关键的一屏，必须写清三件事：

```
这个插件会：
  ✓ 读取它自己目录下的音频文件（12 个，共 48MB）
  ✓ 向记忆库写入记录（类型：我做过的事）
  ✗ 不会访问网络
  ✗ 不会读取聊天记录以外的任何数据

[ 取消 ]  [ 装上，但先不启用 ]
```

一个细节：**建议在插件详情里显示该插件写过的记忆条数和最近几条内容**。用户能看到"唱歌插件给我写了 37 条记忆"，才能判断要不要留。

---

## 12. 里程碑

### P0：能唱（约 3 天）

| # | 任务 | 产出 | 验收 |
|---|---|---|---|
| 1 | `PluginManifest` + `PluginRegistry` | 扫目录、解析、索引 | 放一个包进 `plugins/`，日志打印出解析结果 |
| 2 | `PluginInstaller` + 三道检查 | 上传 zip 能装、能拒 | 坏包被拒且提示到具体哪一项 |
| 3 | `PluginRouter`（仅关键词档） | 命中判定 + 分级 | 单测覆盖 20 条语料 |
| 4 | `DataPluginExecutor` | 查表 → 读文件 → 返回 | 直接调用能拿到 bytes |
| 5 | `MessageDispatcher` 钩子 | 短路接入 | 说"唱首七里香"收到语音条，且没有多余文字 |
| 6 | `PluginMemoryWriter` + 记忆四步 | 写入 + 辅路 + 隔离 + 折叠 | 隔一天说"上次那首"能接上 |
| 7 | 官方唱歌包（10 首） | 素材 + manifest | 真实听到 |
| 8 | `PluginsView.vue` | 列表 / 启停 / 卸载 | 关掉再打开，状态持久 |

**P0 明确不做**：商店、进程型、远程推理、提议。

**P0 的验收脚本**（手动，5 分钟）：

```
1. 说"唱首七里香"           → 收到语音条，回复里没有文字
2. 说"唱首不存在的歌"        → 收到 userHint，不写记忆
3. 说"今天好累啊"            → 正常文字回复，不提唱歌
4. 清空 messages 表，重启  → 说"上次那首再来一遍" → 仍能接上（验证辅路）
5. 后台卸载插件             → 记忆里 source LIKE 'plugin:menran.sing%' 全部归档
6. 不装任何插件重启          → 100 条消息，耗时与改造前同量级
```

### P1：能装别人的（约 5 天）

- 触发治理第二级：意图注册表 + 宿主打分 + 冷却熔断
- `HttpPluginExecutor` + 超时 / 熔断 / 离线提示
- `PluginStoreClient`（安装时下载 + sha256 校验 + 本地索引缓存）
- 商店服务端（上传 / 索引 / 下载 / 列表页）
- 插件调试台：输入一句话，显示候选、分数、分流档位

### P2：能跑远程推理（约 5 天）

- `inference` 型：异步任务契约（`taskId` + `GET /task/<id>`）+ pending 机制
- 出站白名单 + 调用日志
- 提议节流 + 负反馈自动关权限
- 意图向量替换关键词为主
- 结果缓存（`cacheKey = hash(模型版本 + 音色 + 歌词 + 参数)`，LRU 500MB）

### P3：生态自净

- 发布冲突检测（词表重复 / 意图合法性 / 同类区分度）
- `kill switch`：本地拉黑 `id + version`
- 内容安全过滤

---

## 13. 回归与风险

### 13.1 必跑的回归

| 项 | 命令 / 方式 | 通过标准 |
|---|---|---|
| 全量单测 | `JAVA_HOME=…/openjdk-23.0.2 mvn -o test` | 100 个通过 |
| 记忆衰减 | `MemoryDecayIntegrationTest` | 含新增 3 个用例 |
| 空插件开销 | 未装插件跑 100 条消息 | 与改造前差异 < 5% |
| 现有能力 | 语音 / 表情包 / 图片 / 主动消息各测一次 | 行为不变 |
| 多账号 | 两个 bot 各装不同插件 | 互不串 |

> 环境坑：本机 JDK 25 下 Mockito/ByteBuddy 无法 instrument，`TimeGuardTest` 和 `QzoneAudienceDeciderTest` 会假失败。必须用 JDK 23。

### 13.2 风险登记

| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| 钩子位置放错，导致插件抢掉正常对话 | 中 | 高 | 先用白名单账号灰度；`plugin.enabled` 可一键关 |
| 记忆被插件写爆 | 中 | 高 | policy 默认 `none`；5 条/天；`trim()` 优先淘汰插件流水（已修） |
| 「（唱了…）」被模型当台词 | 中 | 中 | `isMediaMarker` 折叠 + `FAKE_SING` 正则双保险 |
| 素材包过大拖垮磁盘 | 中 | 中 | 单包 ≤ 20MB，后台显示占用 |
| 2C/3.6G 装不下进程型插件 | 高 | 中 | P0 只做 data 型；进程型限 1–2 个并显示内存 |
| 音色不一致（外挂 mp3 不是她的声音） | 高 | 高 | 见下 §13.3 |
| 商店被当成代码托管，出事找你 | 低 | 高 | 只收数据型包，人工审核 |

### 13.3 一个产品层的坑：音色

机器人平时说话是克隆音色，插件返回的是外挂 mp3——**用户一听就知道"这不是她"**。这比触发错误更伤。

更好的形态：插件返回「歌词 + 曲调参数」，宿主用自己的 TTS 合成。能力是插件给的，声音还是她的。

P0 用预合成音频是妥协（要先把链路跑通），但规范里要写清这条方向，否则第一批插件就会把它做歪。

---

## 14. 待决策（需要拍板）

| # | 问题 | 选项 A | 选项 B | 建议 |
|---|---|---|---|---|
| 1 | P0 是否只做 `data` 型 | 只做 data（3 天） | 同时做 process（+3 天） | **A**，机器装不下 |
| 2 | 装完是否自动启用 | 不启用，需手动开 | 自动启用 | **不启用** |
| 3 | `endpoint` 是否只允许 127.0.0.1 | 只允许本机 | 允许公网 + 强制 HTTPS | **只允许本机**（P1 再放宽） |
| 4 | 商店是否 P0 就做 | P1 再做 | 现在做 | **P1**，先把包和宿主跑通 |
| 5 | 首批意图注册表 12 个是否够 | 按附录 A | 增删 | 按附录 A 起步 |
| 6 | 唱歌用预合成还是 TTS 合成 | 预合成 mp3 | 歌词 + 宿主 TTS | **P0 预合成，P2 转 TTS** |

---

## 15. 一句话总结

> **包自足、商店可选、装完不问服务器。**
> **触发靠意图不靠关键词，打分归宿主不归作者。**
> **记忆默认不写，写了就按遗忘曲线活着。**
> **插件碰不到 OneBot，出站请求由宿主拼。**

按 P0 的 8 个任务顺序做，第一步是 `PluginManifest` + `PluginRegistry`——它不依赖任何其他改动，可以单独跑通、单独验证。
