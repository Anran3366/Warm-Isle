# 插件框架 v3/v4：配置、数据表、定时与导入

> v4 新增 `imports` 通用导入能力与按 QQ 人设绑定，见 [plugin-imports.md](plugin-imports.md)。
>
> 目标：作者只写 manifest + 模板/数据，宿主自动生成配置页、独立数据表、定时任务和历史页。
> 状态：2026-11-18 第一版上线（周报插件验证）。

---

## 1. 一个插件能声明什么

| 声明 | 作用 | 谁执行 |
| --- | --- | --- |
| `config` | 作者定义输入项，后台自动渲染表单 | 宿主 |
| `storage.tables` | 作者定义数据表，宿主建独立 SQLite | 宿主建表，插件读写 |
| `schedules` | 定时任务（cron / 接收人 / 存档映射） | 宿主 tick |
| `actions` | 用户消息触发的动作（原有） | 宿主/插件 |
| `intent` | 关键词/语义路由（原有） | 宿主 |

v3 插件必须写 `"schemaVersion": 3`；旧宿主会拒绝安装，避免“装了但不执行”。

---

## 2. 配置：`config`

```json
"config": [
  { "key": "targetUsers", "label": "周报对象", "type": "users",
    "description": "选择要收到周报的人，可多选" },
  { "key": "style", "label": "口吻", "type": "select", "default": "温柔",
    "options": [{ "value": "温柔", "label": "温柔" }, { "value": "俏皮", "label": "俏皮" }] },
  { "key": "enabled", "label": "启用周报", "type": "switch", "default": true }
]
```

支持类型：

| type | 控件 | 值 |
| --- | --- | --- |
| `text` | 单行输入 | string |
| `textarea` | 多行 | string |
| `number` | 数字 | number |
| `switch` | 开关 | boolean |
| `select` | 下拉 | string |
| `time` | HH:mm | string |
| `user` | 选一个人 | QQ 号 |
| `users` | 选多个人 | QQ 号数组 |

规则：

- 配置按“机器人账号 × 插件”保存在 `plugin_state.config`；
- 保存时校验类型、必填、范围、`select` 选项、`users` 必须是聊过天的人；
- 插件升级按 `key` 保留旧值，新字段取默认值；
- 配置值可在 prompt 模板里用 `{config.<key>}` 引用（需写进 `variables`）。

---

## 3. 数据表：`storage.tables`

每个插件一个独立文件：`data/plugin-data/<plugin_id>.db`。
宿主自动加 `id` 和 `bot_id`，所有查询按当前机器人账号隔离。

```json
"storage": {
  "version": 1,
  "tables": [{
    "name": "reports",
    "label": "周报",
    "retentionDays": 365,
    "columns": [
      { "name": "user_id", "type": "integer", "label": "对象", "index": true },
      { "name": "period", "type": "text", "label": "周期" },
      { "name": "content", "type": "text", "label": "内容", "ui": "longtext" },
      { "name": "style", "type": "text", "label": "口吻" },
      { "name": "created_at", "type": "text", "label": "生成时间", "ui": "datetime" }
    ],
    "indexes": [{ "columns": ["user_id", "period"], "unique": false }]
  }]
}
```

限制：

- 表名/列名 `^[a-z][a-z0-9_]{0,30}$`，不能叫 `bot_id`；
- 类型只允许 `integer` / `text` / `real`；
- ≤8 表、每表 ≤24 列、≤8 索引；
- 只允许 `CREATE TABLE IF NOT EXISTS` / 建索引，不接受裸 SQL；
- 插件拿不到数据库连接，只通过宿主 API 操作。

后台 API：

| 方法 | 路径 |
| --- | --- |
| GET | `/api/plugins/{id}/tables` |
| GET | `/api/plugins/{id}/tables/{table}/rows?page=&size=&qqId=` |
| GET | `/api/plugins/{id}/tables/{table}/rows/{rowId}` |
| DELETE | `/api/plugins/{id}/tables/{table}/rows/{rowId}` |

后台页面按 `columns[].label` 自动出列表，按 `ui=longtext` 出详情。

---

## 4. 定时任务：`schedules`

```json
"schedules": [{
  "id": "weekly",
  "cron": "0 0 21 * * 0",
  "timezone": "Asia/Shanghai",
  "target": "dm",
  "scope": "config",
  "recipientsKey": "targetUsers",
  "action": "weekly",
  "contentSource": "model",
  "catchUp": "skip",
  "graceMinutes": 10,
  "save": {
    "table": "reports",
    "fields": {
      "user_id": "$recipient",
      "period": "$period",
      "content": "$output",
      "style": "$config.style",
      "created_at": "$now"
    }
  }
}]
```

| 字段 | 说明 |
| --- | --- |
| `scope` | `config`（从配置读接收人）/ `per-user`（最近 7 天活跃）/ `global` |
| `target` | 第一版只支持 `dm`；group/qzone 在 P1 |
| `contentSource` | `model`（调插件 action）/ `template`（静态文本） |
| `save` | 生成后写哪张表、字段怎么填 |

占位符：

| 占位符 | 值 |
| --- | --- |
| `$recipient` | 接收人 QQ |
| `$output` | 生成文本 |
| `$period` | ISO 周，如 `2026-W47` |
| `$now` | 当前时间 |
| `$date` | 今天 |
| `$schedule` | 定时 id |
| `$config.<key>` | 配置值 |

执行：宿主每 30 秒 tick，按 cron 计算 occurrence，写 `plugin_schedule_runs` 抢占防重；
成功发送后按 `save` 映射写表；失败只记 error，不发送、不存档。

---

## 5. Prompt 变量

| 变量 | 内容 | 预算 |
| --- | --- | --- |
| `{date}` | 今天 | — |
| `{profile}` | 3 条 relation 记忆摘要 | ≤60 字 |
| `{memory_digest}` | 7 天 episode/洞察/成长/跟进 | ≤500 字 |
| `{config.<key>}` | 配置值 | — |
| action 参数 / data 键 | 原有 | — |

变量必须在 `variables` 里声明，安装时扫描模板；未声明的占位符拒装。

---

## 6. 周报插件（示例）

见 `packs/menran.weekly/`：

- `config`：周报对象、口吻、启用开关；
- `storage`：`reports` 表；
- `schedules`：周日 21:00，按 `targetUsers` 发私聊，生成后写 `reports`；
- `prompt`：使用 `{date}` / `{profile}` / `{memory_digest}` / `{config.style}`。

流程：

```
安装 → 配置对象 → 周日 21:00 生成 → 私聊发送 → 写 reports → 后台历史
```

手动验收：

```
POST /api/plugins/menran.weekly/schedules/weekly/run
     ?qqId=100000000&dryRun=true&save=false
```

---

## 7. 安全边界

- 独立 `.db`，不碰主库；
- schema 白名单，不接受裸 SQL；
- 所有查询参数化，自动带 `bot_id`；
- 配置里 `users` 只能是聊过天的人；
- 定时任务宿主托管，有 grace/catch-up/静默时段；
- 卸载保留数据 7 天，可导出后删除；
- 第一版不开放 group/qzone，避免公开副作用。

---

## 8. 还没做（P1）

- `tools` + 模型 tool calling；
- `context` / `turn` 钩子；
- 表结构迁移（加列/改索引）；
- 群聊/朋友圈（`bot.send_message` / `bot.post_qzone`）；
- 数据导出/保留期自动清理；
- hosted/local 的存储 Hook API。
