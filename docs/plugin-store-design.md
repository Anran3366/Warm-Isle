# 插件商店设计方案（v1，已被取代）

> **已由 [`plugin-store-v2.3.md`](plugin-store-v2.3.md) 取代。** 本文记录 2026-11-06 的早期动机推演，
> 其中「`.wplugin` 内容包」「安装记录放 SharedDb」「商店 P0 不做」等结论已被 v2.x 修正。
> 新实现请以 v2.2 为准。


> 状态：**设计稿，未实现**
> 作者：与所有者共同确定 · 2026-11-06
> 关键决策：商店挂在 `web.example.com/store/`；第一版只做「内容包」，不做可执行代码插件

---

## 1. 定位

一句话：**让任何部署了本项目的实例，都能装上别人写的能力和内容**——装一个「唱歌」插件，机器人就真的会唱歌。

插件分两类，一套安装机制、一套商店：

| 类型 | 是什么 | 例子 | 运行方式 |
|---|---|---|---|
| **能力插件** | 独立进程，通过 HTTP 协议提供动作 | 唱歌、点歌、查快递、塔罗 | 插件作者自己起一个服务，宿主调它 |
| **内容包** | 纯数据，写进现有配置表 | 人设、话题词表、语料、表情 | 宿主直接写库 |

两类都在同一个商店里、同一套 manifest、同一个「安装」按钮。

| 是 | 不是 |
|---|---|
| 开放插件市场：任何人都能写、能发 | Java 类插件：不往主程序里编译任何第三方代码 |
| 装完即生效（写库或起进程） | 不需要重新编译 jar、不需要重启主容器 |
| 插件可跑在别的机器（有 GPU 的那台） | 不是 SaaS 多租户（商店不碰实例的业务数据） |

### 为什么插件必须是「独立进程」

如果插件是 `implements BotPlugin` 的 Java 类，"别人写一个唱歌插件"就变成：他得有你的源码、会 Maven、能编译、能替换 jar、能重启容器。这不是插件生态，是让别人帮你改代码。

**改成独立进程后**：插件作者用 Python / Node / Go 都行，只要实现一个 `POST /invoke`；宿主按 manifest 发现它、调用它、把结果交回发送链路。主程序不再需要为每个新能力改代码。

代价是**要动一次主链路**（加插件宿主 + 决策路由 + 工具调用支持），但只动这一次——之后加任何能力都是「装个包 + 起个进程」，不再碰 Java。

而本项目刚好满足：下面这些东西全在数据库/配置文件里，不在代码里。

| 内容类型 | 现有存储 | 安装后效果 |
|---|---|---|
| 人设 | `persona` 表（name + content） | 换人格：傲娇学姐、毒舌好友、温柔姐姐 |
| 人格表达 | `ExpressionConfig` | 说说/朋友圈的口吻与受众设定 |
| 话题屏蔽 | 词表 + 处置策略（refuse/deflect/silent） | 自带一套回避话题 |
| 知识库语料 | 切片 + 向量 | 带一批设定、小说片段、对话语料 |
| 表情包 | 图片 + 情绪标签 | 一整套表情（P2 才做，体积大） |
| 语音参数 | 音色配置 | TTS 语气预设（P2） |

安装动作调用的是已有服务（`PersonaStore.create()`、`SettingStore.set()`、`TopicBlockService` 等），`ChatService` 主链路**零改动**——这正是所有者要求的「框架不动」。

---

## 2. 插件包规范（`.wplugin`）

本质是一个 zip，扩展名只为辨识。

```
gentle-sister.wplugin
├── manifest.json           必需
├── README.md               必需，商店详情页直接渲染
├── preview.png             可选，封面（1200×630，≤300KB）
└── payload/                必需，内容文件
    ├── persona.md
    ├── expression.json
    └── topicblock.txt
```

### 2.1 manifest.json

```json
{
  "schemaVersion": 1,
  "id": "gentle-sister",
  "name": "温柔姐姐",
  "version": "1.0.0",
  "type": "persona",
  "author": { "name": "menran", "url": "https://example.com" },
  "description": "慢语速、会哄人、不用颜文字，适合深夜聊天",
  "tags": ["人设", "温柔", "女性"],
  "license": "CC-BY-NC-4.0",
  "minAppVersion": "1.0.0",
  "contents": {
    "persona": "payload/persona.md"
  },
  "writes": ["persona"],
  "createdAt": "2026-11-06"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `schemaVersion` | ✅ | 固定 `1`，将来格式升级用 |
| `id` | ✅ | `^[a-z0-9][a-z0-9-]{2,40}$`，全局唯一，发布后不可改 |
| `name` | ✅ | 展示名，2–20 字 |
| `version` | ✅ | 语义化版本 `x.y.z` |
| `type` | ✅ | `persona` / `expression` / `topicblock` / `knowledge` / `bundle`（`emoji`、`voice` 留待 P2） |
| `author.name` | ✅ | 作者署名 |
| `description` | ✅ | 一句话，≤80 字 |
| `tags` | ✅ | ≤6 个，用于商店筛选 |
| `license` | ✅ | SPI 标识，缺省不允许发布 |
| `minAppVersion` | ✅ | 低于此版本的应用拒绝安装 |
| `contents` | ✅ | 键为用户类型，值为包内相对路径 |
| `writes` | ✅ | 声明会改动哪些数据，安装前逐项展示 |
| `preview` | ❌ | 封面图相对路径 |

`type` 与 `contents` 的对应关系（校验时强制）：

| type | 必须包含 | 可选包含 |
|---|---|---|
| `persona` | `persona` | `expression`、`topicBlock` |
| `expression` | `expression` | — |
| `topicblock` | `topicBlock` | — |
| `knowledge` | `knowledge` | — |
| `bundle` | 至少 2 项 | 全部 |

### 2.2 安全校验（解压前后各一轮）

解压**前**（只看 zip 中央目录）：
- 条目数 ≤ 200，解压后总大小 ≤ 5 MB（`knowledge` 类型放宽到 20 MB）
- 拒绝绝对路径、`..` 路径穿越、符号链接
- 扩展名白名单：`.json .md .txt .png .jpg .webp`
- 出现 `.sh .js .jar .class .exe .so .py` 一律拒绝——**包里不允许有任何可执行物**

解压**后**：
- manifest 字段与正则校验
- `contents` 声明的路径必须真实存在且在 `payload/` 内
- 人设正文长度 50–20000 字
- 文本内容扫一遍常见 prompt injection 模式（如「忽略以上所有指令」），命中则标红交人工审核

---

## 3. 商店服务

### 3.1 部署形态

独立 Node 进程，和 blog 服务同构，**不复用 Spring Boot**（商店要公网开放，隔离更安全）。

| 项 | 值 |
|---|---|
| 域名 | `https://web.example.com/store/` |
| 监听 | `127.0.0.1:3301` |
| 代码 | `site/plugin-store/`（同 `site/` 惯例，不入 git） |
| 线上目录 | `/opt/warm-isle-store/{app,data}` |
| 服务用户 | `storeapp`（`systemd` 加固模板同 `car-file-library`） |
| 数据 | `/opt/warm-isle-store/data/{packages,index.json,stats.json}` |

Caddy 改动（`web.example.com` 块内，与 `/blog/*` 并列；用 `handle` 而非 `handle_path`，保持前缀）：

```caddyfile
web.example.com {
	encode zstd gzip
	redir /home* https://example.com/ permanent
	redir /xiaoman* / permanent

	redir /blog /blog/ 308
	handle /blog/* {
		reverse_proxy 127.0.0.1:3300
	}

	redir /store /store/ 308
	handle /store/* {
		reverse_proxy 127.0.0.1:3301
	}

	handle {
		root * /srv/static/xiaoman
		try_files {path} /index.html
		file_server
	}
	# header 块不变
}
```

> 注意：`handle` 不剥离前缀，服务内部路由必须自带 `/store` 前缀（与 blog 服务同样的写法）。
> Caddyfile 是单文件 bind mount，改完在服务器上必须 `sudo docker restart caddy` 才生效（见 runbook §14）。

### 3.2 路由表

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/store/` | 商店首页：卡片列表、分类、搜索、排序 |
| GET | `/store/p/:id` | 详情页：README（Markdown 渲染）、版本历史、下载量、作者 |
| GET | `/store/publish` | 发布页：上传表单 + 密码 |
| GET | `/store/api/index.json` | **公开索引**（实例拉取用），带 ETag + 5 分钟缓存 |
| GET | `/store/api/plugins/:id` | 单个插件详情（含全部版本） |
| GET | `/store/dl/:id/:version` | 下载 `.wplugin`，计一次下载量 |
| POST | `/store/api/publish` | 发布（密码校验 + 自动体检） |
| GET | `/store/sitemap.xml` | 商店页面的 sitemap（便于搜索引擎收录） |

### 3.3 索引结构

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-06T07:00:00Z",
  "plugins": [
    {
      "id": "gentle-sister",
      "name": "温柔姐姐",
      "latest": "1.0.0",
      "type": "persona",
      "author": "menran",
      "description": "慢语速、会哄人、不用颜文字",
      "tags": ["人设", "温柔"],
      "downloads": 128,
      "updatedAt": "2026-11-06"
    }
  ]
}
```

索引里**不含 README 与包体**，只给列表；详情按需请求，避免索引膨胀。

### 3.4 发布与审核状态机

```
draft ──上传──> pending ──所有者通过──> published ──作者撤回──> yanked
                    └──驳回──> rejected
```

- 上传需要**发布密码**（独立于后台密码，存 `/opt/warm-isle-store/.env`，root 600）
- 新版本同 id 追加版本记录，`latest` 指向最高版本；旧版本保留，支持回退
- 已 `published` 的 id 不能被他人抢占（先到先得，作者凭 token 才能发新版本）
- **作者 token**：首次发布时服务端生成一次性 `authorToken`，作者自行保存；后续发版凭 token

### 3.5 自动体检（上传时立即反馈）

| 检查 | 处理 |
|---|---|
| zip 结构 / 路径穿越 / 扩展名 | 硬拒绝 |
| manifest 字段缺失或格式错 | 硬拒绝 |
| 大小超限 | 硬拒绝 |
| 人设正文过短或过长 | 硬拒绝 |
| 命中 prompt injection 关键词 | 转入 pending 并标红 |
| 与已上架插件同名/同 id | 硬拒绝 |

---

## 4. 实例端接入

### 4.1 配置

```properties
# application.properties
plugin.store.url=https://web.example.com/store
plugin.store.cache-ttl=300s
```

自部署者只需改这一行就能连上公共商店；留空则「插件商店」页面显示「未配置商店地址」，其余功能不受影响。

### 4.2 后端接口（新建 `PluginStoreController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/store/plugins` | 代理商店索引，本地缓存 + 支持 `?q=&tag=&type=` 过滤 |
| GET | `/api/store/plugins/{id}` | 插件详情 + 已安装版本对比（可升级/已最新） |
| POST | `/api/store/preview` | `{id, version}` → 下载 + 校验 + 返回**影响预览**，不写库 |
| POST | `/api/store/install` | 用户确认后真正写库，返回 `installId` |
| POST | `/api/store/rollback` | 按 `installId` 回滚到安装前快照 |
| POST | `/api/store/export` | 把本地人设/词表打包成 `.wplugin` 下载（发布用） |

新增 3 个类：
```
src/main/java/com/qqbot/plugin/store/
  PluginStoreClient.java      // 拉索引、下载包（带超时与大小上限）
  PluginPackage.java          // 解压 + manifest 解析 + 校验（不依赖第三方，用 java.util.zip）
  PluginInstaller.java        // 类型 → 现有服务的映射与写库、快照、回滚
```

### 4.3 安装流程

```
① 浏览（/api/store/plugins）
      ↓ 点「安装」
② 预览（/api/store/preview）：下载 → 校验 → 算差异
   弹窗展示：将新增 1 个人设「温柔姐姐」/ 将向话题词表追加 12 词
      ↓ 用户确认
③ 应用（/api/store/install）：快照 → 写库 → 记录 installed_plugins
      ↓
④ 生效：当前账号自动激活人设（可选开关），无需重启
```

关键约束：**预览与应用必须分两次请求**。预览阶段绝不写库，用户看到的差异和实际写入必须一致——这是防止恶意插件悄悄改配置的唯一有效手段。

### 4.4 类型 → 现有服务映射

| `contents` 键 | 调用 | 备注 |
|---|---|---|
| `persona` | `PersonaStore.create(name, content)` → 返回新 id | 人设存 SharedDb（全局），**仅对当前账号 activate** |
| `expression` | `ExpressionConfig.apply(json)` | 只覆盖插件声明的字段，其余保留 |
| `topicBlock` | `TopicBlockService.importWords(lines, 处置策略)` | 追加不覆盖，重复词跳过 |
| `knowledge` | `KnowledgeService.ingest(...)` | 走现有切片+向量流水线，夜间队列（P2） |

`writes` 与实际写操作不符时**以 `writes` 为准并报错终止**——插件不能声明 A 却写 B。

### 4.5 数据表（`SharedDb` 新增）

```sql
CREATE TABLE IF NOT EXISTS installed_plugins (
  plugin_id     TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  version       TEXT NOT NULL,
  type          TEXT NOT NULL,
  source_url    TEXT,
  manifest      TEXT NOT NULL,   -- 原始 manifest JSON
  applied_refs  TEXT,            -- 装出来的引用：{"personaId":12,"topicWords":["..."]}
  backup        TEXT,            -- 安装前的快照（被修改项的原值）
  enabled       INTEGER DEFAULT 1,
  installed_at  TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);
```

放 `SharedDb`（跨账号），因为人设、词表本身就是全局的；`enabled` 的**启用范围**记在每账号的 `SettingStore`（`plugin.<id>.enabled`），和现有 `persona.activeId` 的按账号激活逻辑一致。

### 4.6 卸载与回滚

- 卸载：按 `applied_refs` 删除新建的人设、从词表移除导入的词；`backup` 里有原值的先恢复
- 回滚：`backup` 就是为这个准备的，装上发现不合适可一键退回
- 被用户手动改过的数据不静默覆盖——检测到内容与 `applied_refs` 记录不一致时提示「该人设已被修改，卸载将保留当前内容」

---

## 5. 前端页面（`PluginsView.vue`）

路由：`/plugins`，侧边栏菜单在「知识库」下方加一项「插件商店」。

三个 Tab：

**① 商店**
```
┌ 搜索框 ────────────────┬ 类型 ▾ ┬ 排序：最新 ▾ ┐
│ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│ │ 封面     │ │ 封面     │ │ 封面     │        │
│ │ 温柔姐姐  │ │ 毒舌好友  │ │ 深夜电台  │        │
│ │ 慢语速…  │ │ 毒舌…    │ │ …        │        │
│ │ menran   │ │ …        │ │ …        │        │
│ │ 128 次   │ │ 86 次    │ │ 42 次    │        │
│ └──────────┘ └──────────┘ └──────────┘        │
```

**② 已安装**
列表 + 每行：状态（启用/停用）、当前版本（有新版显示「可升级」）、「停用 / 卸载 / 回滚」操作，以及「查看它改了什么」。

**③ 发布**
- 上传 `.wplugin` 或直接填表单（名称、类型、人设正文、标签）
- 实时显示 manifest 预览与体检结果
- 提交后给出 `authorToken`（提示「请自行保存，发新版本需要它」）
- 支持「从我的实例导出」：选一个人设 / 一份词表 → 后端打包 → 下载

### 安装确认弹窗（最关键的一屏）

```
安装「温柔姐姐」 v1.0.0
─────────────────────────────
将新增
  · 人设「温柔姐姐」（1,240 字）        [预览全文]
将修改
  · 话题屏蔽词表：+12 个词              [查看]

来源  menran · 商店页
许可  CC-BY-NC-4.0
─────────────────────────────
☑ 安装后立即为当前账号启用
              [取消]  [确认安装]
```

---

## 6. 安全模型

| 威胁 | 对策 |
|---|---|
| 恶意插件覆盖人设，植入「忽略以上指令」 | 预览+确认两步、`writes` 强校验、injection 关键词扫描、人工审核上架 |
| 上传 zip 炸弹 / 路径穿越 | 解压前校验条目数与声明大小、拒绝 `..` 与绝对路径、解压到临时目录后逐个白名单校验 |
| 商店被打成分发木马的通道 | 包里禁止任何可执行扩展名；只允许 json/md/txt/图片 |
| 实例被商店 DDoS 拖慢 | 索引本地缓存 5 分钟、下载超时 10s、单包 5MB 上限、失败即降级为「商店暂不可用」 |
| 别人冒名发新版本 | `authorToken` 校验；`id` 一经发布不可转让 |
| 商店被墙 / 下线 | 商店是可选能力，配置为空即隐藏入口；已装插件完全不依赖商店 |

**明确不做的事**：商店不接收任何来自实例的用户数据、不收账号信息、不做遥测回传。下载量按 IP 哈希日去重（同 blog 的阅读量做法）。

---

## 7. 工作量与里程碑

| 阶段 | 内容 | 交付物 |
|---|---|---|
| **P0**（1 天） | 包格式 + 商店服务骨架：列表/详情/下载/索引 API，静态种子 2 个插件 | 能在 `store.example.com` 浏览和下载 |
| **P1**（1 天） | 实例端 `PluginStoreClient` + `PluginInstaller`（persona / topicblock / expression）+ 预览确认 | 后台能装、能卸、能回滚 |
| **P2**（1 天） | 发布链路：上传、体检、审核、authorToken、导出打包 | 作者可发布，你后台可审核 |
| **P3**（半天） | 部署 + Caddy + systemd + 文档（runbook 新增一节） | 上线 |
| P4（可选） | 表情包 / 知识库语料类型、评分与评论、商店 sitemap 与 SEO | — |

先做 P0+P1 就能跑通「浏览 → 安装 → 生效」主链路，P2 才有「别人发布」的能力。

---

## 8. 未决问题

1. **审核由谁做**：所有发布都进 pending 等你人工点通过，还是给可信作者（带 token 名单）免审？
2. **人设安装后是否自动激活**：建议默认「是，但仅当前账号」，多账号场景下其余账号保持原人设。
3. **是否允许付费插件**：当前设计只有下载统计，没有任何支付能力；若要做，需要单开设计（涉及订单、授权校验、退款）。
4. **商店是否需要账号体系**：P1 只用发布密码 + authorToken，不做注册登录；如果将来要做评分/评论，就必须引入账号。

---

## 9. 触发路由与历史一致性

> 2026-11-06 补充。这三件事决定插件「能不能被用上」和「用了之后记不记得」。
> 插件装进去只是有了一双手，会不会用、用完记不记得，是另外两件事。

### 9.1 决策优先级与短路

消息到达后按以下顺序判定，任一层命中即短路后续：

| 顺序 | 层 | 命中后的行为 |
|---|---|---|
| 1 | 话题屏蔽 | 拦截，不进模型 |
| 2 | **插件触发（新增）** | 执行插件，**跳过第 3、4 层** |
| 3 | 模型对话 | DeepSeek 生成文本 |
| 4 | 表达决策 | 文字 / 语音 / 表情（现有 `ChatVoiceReplier`） |

第 2 层必须短路，否则用户会同时收到「唱歌语音条 + 一段文字回复 + 可能还有一条概率触发的语音条」。

落点（`ChatService.reply()`，话题屏蔽之后、组装提示词之前）：

```java
PluginResult pr = pluginRouter.tryHandle(ctx, userText, client);
if (pr.handled()) return;   // 插件该发的已经发完了，整条链路到此为止
```

这是照抄 `ChatVoiceReplier.maybeSend()` 的契约——返回 `true` 表示「我已经处理了，你别再发文字」。同一个模式，不用发明新的。

### 9.2 TriggerRouter 两段式判定

不能只靠 function calling（把所有插件的工具描述都塞进 system prompt），代价太大：

| 问题 | 后果 |
|---|---|
| token 成本 | 20 个插件能把 system 从 1000 字涨到 3000 字以上，前面压缩提示词的努力全白费 |
| 误判 | 「我今天好累」可能被理解成想听歌，然后突然唱歌 |
| 延迟 | 每次多一轮模型调用 +1.5~3s，叠上拆条发送和打字延迟，用户明显觉得慢 |

所以是「规则优先，模型兜底」：

```
第一段（零成本）：manifest.triggers 关键词匹配
   命中 → 参数完整？ → 直接执行，不调模型
        → 参数不全？ → 进第二段
   未命中 → handled=false，零 token 开销

第二段（偶尔发生）：只把命中的那一条插件的工具描述注入，让模型补参数
   「唱首七里香」→ 缺风格 → 模型补 {"song":"七里香","style":"温柔"}
```

99% 的消息在第一段就被放行，只有真的点歌时才付工具描述那点 token。

### 9.3 manifest 扩展字段

在 §2.1 基础上，能力插件额外声明：

```json
{
  "kind": "capability",
  "endpoint": "http://127.0.0.1:3400",
  "priority": 100,
  "triggers": ["唱首歌", "来一首", "唱个"],
  "actions": [{
    "name": "sing",
    "description": "为用户唱一首歌",
    "params": {
      "song":  { "type": "string", "description": "歌名，留空表示随便唱" },
      "style": { "type": "string", "description": "温柔 / 欢快 / 伤感" }
    },
    "returns": ["audio", "text"],
    "timeoutMs": 60000
  }],
  "quota": { "perUserPerDay": 10 }
}
```

| 字段 | 作用 |
|---|---|
| `kind` | `capability`（独立进程）/ `content`（纯数据） |
| `priority` | 多个插件命中同一意图时谁先接，数字大优先 |
| `triggers` | 第一段关键词，命中即候选，不调模型 |
| `quota` | 独立额度，**不与主动语音额度混用**（否则点第 4 首歌就发不出去） |

插件进程只需实现一个接口：

```
POST /invoke
← {"action":"sing","params":{"song":"七里香"},"ctx":{"userId":123,"nickname":"小明"}}
→ {"audio":"<base64 或可下载 URL>","text":"给你唱一段"}
```

插件不需要知道 QQ 协议、不需要知道怎么发消息——那是宿主的事。

### 9.4 历史落库：机器人怎么「记得」自己唱过

现有代码已经给出了答案。`MessageStore.markLastAsVoice()` 是这么做的：

```sql
UPDATE messages SET kind='voice', content='（语音）' || content
```

**给 content 加一个 `（语音）` 前缀**——模型下一轮读历史时看到「（语音）好呀我在」，就知道那次是用语音说的。这就是「机器人知道自己发过语音」的全部机制，没有任何额外状态。

插件照同一模式：

```java
// 发送成功之后
client.sendPrivateRecord(userId, audio);                     // 1. 先发
String file = voiceFiles.save(audio, "mp3");                 // 2. 存文件（不进库）
String desc = "（唱了《" + song + "》，" + sec + " 秒）";      // 3. 语义化描述
messages.add(userId, "assistant", desc, "voice");            // 4. 落库 + 发事件
messages.setLastVoiceFile(userId, "assistant", file);        // 5. 记文件名
```

下一轮 prompt 里的历史就是：

```
user:      唱首歌
assistant: （唱了《七里香》，32 秒）
```

用户第二天说「再唱一遍上次那首」，模型从上下文里直接能找到《七里香》——**不需要额外的记忆表**。

### 9.5 记忆与事件总线

`MessageStore.add()` 内部会 `events.publish(botId, payload)`，payload 含 `kind`。所以只要插件走 `add()` 落库：

- 后台聊天记录实时刷新，这一条能看到（带播放器）
- 推送 / 统计 / 复盘自动包含
- 夜间记忆抽取扫 `messages` 表时自动读到，能提炼出「用户喜欢周杰伦」

**不要自己写 SQL 插消息**，否则上面这些链路全部断掉。

### 9.6 四个必须处理的坑

**① 绝对不能把音频或 base64 写进 content**
只存文件名（`voice_file` 列），前端用现有 `VoiceFileService` 拼 URL。一条 base64 音频就能把 prompt 撑爆。

**② 模型会伪造「（唱了…）」**
历史里既然有这个格式，模型在普通对话里可能自己编一句「（唱了《晴天》）」而根本没调插件。现有 `ReplyMarkers` 里的 `FAKE_MEDIA` 正是处理同类问题（「（我发了一张表情包：…）」），要加一条对应的：

```java
/** 模型编造的唱歌描述：本次没调插件，却说自己唱了 */
private static final Pattern FAKE_SING =
        Pattern.compile("（(?:我)?(?:唱了|给你唱|唱一首)[^）]{0,40}）");
```

清洗后如果正文变空，回退成一句「嗯」。

**③ 幂等**
插件超时重试按 `taskId` 去重，不能重复落两条历史。

**④ 长任务的时序**
唱歌要 30–60 秒，用户会以为机器人死了。协议上给插件可选的 `progress` 回调：

- 受理即返回 `{"status":"pending","taskId":"..."}`
- 宿主可先发一句短话（如「好，等我一下」），但这句用 `kind='progress'`，**不进 prompt 历史**（否则模型下次看到会觉得自己话多）
- 完成时按 §9.4 落库
- 期间用户又发新消息时，宿主在 system 里追加一句「你正在为他唱《七里香》，还没唱完」，避免模型答非所问
