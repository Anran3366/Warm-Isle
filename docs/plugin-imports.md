# 插件导入能力（imports）与按用户人设

> 状态：2026-11-19 最新版。宿主通用，插件只声明。
> 完整开发手册见 [plugin-dev-manual-v4.md](plugin-dev-manual-v4.md)。
> 插件：`menran.deepseek-import`（schemaVersion 4 / apiVersion 3）

---

## 1. 宿主负责什么

```
上传 → 解析 → 分块 → 模型提取 → 人工审核 → 写 facts / 人设绑定
```

- 上传接口、文件校验、ZIP 解压限制；
- 聊天记录解析（JSON / Markdown / TXT / HTML / ZIP）；
- 模型调用、JSON 校验、多块合并；
- 审核页、应用、开关、审计；
- 每插件独立数据库中的 `plugin_imports` 等内部表；
- 按 `bot_id + qq_id` 绑定人设；只对目标 QQ 生效；
- 插件停用后绑定自动失效，重新启用恢复。

插件不写 Java、不写 Vue、不写 SQL，只提供 manifest 与提取 prompt。

---

## 2. manifest 声明

```json
{
  "schemaVersion": 4,
  "apiVersion": 3,
  "id": "menran.deepseek-import",
  "runtime": "prompt",
  "prompt": { "system": "prompts/extract.md", "maxTokens": 1000 },
  "imports": [{
    "id": "deepseek",
    "label": "DeepSeek 聊天记录",
    "perUser": true,
    "accept": [".json", ".md", ".markdown", ".txt", ".html", ".htm", ".zip"],
    "maxBytes": 20971520,
    "parser": "chat",
    "prompt": "prompts/extract.md",
    "chunkChars": 7000,
    "maxChars": 150000,
    "targets": ["facts", "style", "persona"],
    "review": true
  }]
}
```

`imports` 要求 `schemaVersion 4`、`apiVersion 3`，旧宿主会拒装。

---

## 3. 提取结果

```json
{
  "conversationType": "general | roleplay | mixed",
  "persona": {
    "detected": true,
    "name": "林深",
    "confidence": 0.95,
    "sections": {
      "身份": "...", "性格": "...", "说话方式": "...",
      "背景": "...", "世界观": "...", "关系": "...", "边界": "..."
    },
    "examples": [{ "user": "...", "assistant": "..." }]
  },
  "facts": [{
    "category": "profile|preference|relationship|event|commitment",
    "key": "生日",
    "value": "08-12",
    "confidence": 0.95,
    "evidence": "原话..."
  }],
  "styles": [{ "key": "语气", "value": "像老朋友", "confidence": 0.9 }],
  "warnings": []
}
```

普通聊天 `persona.detected=false`，只写 facts/style，不会替换小满。

---

## 4. 应用结果

- `facts` → `facts` 表（目标 QQ）；
- 生日/纪念日 → `friend_profile.important_dates` + facts；
- `styles` → `preference` 事实；
- 角色人设 → 新建 persona，写入 `persona_bindings(bot_id, qq_id)`；
- 重建 L0 常驻画像；
- 一个 QQ 同时只有一个启用记录；旧人设保留可回滚。

运行时 `PromptBuilder`：

```
有启用绑定 → 用绑定人设
没有/关闭/插件停用 → 回退全局小满
```

---

## 5. API

```
POST   /api/plugins/{id}/imports?qqId=           multipart file
GET    /api/plugins/{id}/imports                 记录列表
GET    /api/plugins/{id}/imports/{importId}      预览
POST   /api/plugins/{id}/imports/{importId}/apply
POST   /api/plugins/{id}/imports/{importId}/enable?enabled=
```

管理后台第一版；以后用户网站上传走同一服务层，服务端强制 `targetQq = 登录用户 QQ`。

---

## 6. 安全

- 只解析上传文件，不登录 DeepSeek、不抓网页；
- 原始聊天不长期保存；
- 文件大小/解压大小/字符数限制；
- 人设注入扫描、4000 字上限；
- 插件只声明，不能直接写人设/数据库；
- 导入只对目标 QQ 生效；
- 管理员审核后才应用。

---

## 7. 还没做

- 用户网站上传与登录；
- 多文件批量导入；
- 多角色候选选择；
- 导入数据删除/保留期任务；
- 角色卡（PNG/JSON）解析。
