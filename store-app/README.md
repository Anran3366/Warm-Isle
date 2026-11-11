# 暖屿插件商店服务端（store-app）

设计文档 §6 的实现：独立 Node 服务，只管**分发和审批**，不碰实例业务数据，运行期插件也不会回连它。

```
store-app/
├── src/db.js        数据模型（authors / plugins / versions / downloads / revocations / intents / audit_log）
├── src/verify.js    zip 安全 + manifest 语义校验（文档 §5.3 / §5.4 / §6.9）
├── src/indexer.js   index.json 重建、ETag、按 hostVersion 过滤（§6.3 / §6.7）
├── src/server.js    公开 API + 发布 API + 管理 API
├── admin/           审核后台（原生 HTML/JS，无构建步骤）
└── data/            store.db、packages/、index.json（运行时生成）
```

## 本地跑起来

```bash
cd store-app
npm install
STORE_ADMIN_TOKEN=dev-admin-token-1234567890 \
npm start
```

- 商店：<http://127.0.0.1:3500/api/v1/index.json>
- 端到端冒烟（发布→审批→索引验签→下载校验）：`npm run test:e2e`
- 审核后台：<http://127.0.0.1:3500/admin/>（填上面的 token）

## 生产部署

机器按文档 §6.1 是**独立最小实例**（2C2G），不要和主程序同机（主程序 compose 的 mem_limit 合计约 4.9G，宿主只有 3.6G）。

```bash
sudo useradd -r -s /usr/sbin/nologin storeapp
sudo mkdir -p /opt/nuanyu-store/{app,data}
sudo chown -R storeapp:storeapp /opt/nuanyu-store

# 代码 + 依赖（在本地或服务器上）
rsync -a --exclude node_modules . storeapp@<host>:/opt/nuanyu-store/app/
sudo -u storeapp bash -c 'cd /opt/nuanyu-store/app && npm ci --omit=dev'

sudo cp deploy/nuanyu-store.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now nuanyu-store
```

Caddy 用 `deploy/Caddyfile`（文档 §6.1 原样）。

## API

公开（部署者客户端用）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/index.json` | 已上架索引，ETag + 5 分钟缓存，支持 `?hostVersion=&apiVersion=` |
| GET | `/api/v1/plugins/:id` | 详情（manifest、版本列表、下载量） |
| GET | `/api/v1/plugins/:id/versions/:version` | 单版本 manifest |
| GET | `/api/v1/download/:id/:version` | 下载 zip，带 `X-Checksum-Sha256`，单 IP 30 次/分 |
| GET | `/api/v1/intents.json` | 意图注册表快照 |
| GET | `/api/v1/revocations.json` | 吊销名单（签名位已预留） |
| GET | `/api/v1/keys/rotation` | 密钥轮换链（旧私钥逐条签名，宿主自动采纳） |
| GET | `/healthz` | 健康检查 |

发布（任何部署实例都可调用，**不需要注册**）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/publish/upload` | 上传 zip（`file` 字段），体检通过后进 `pending`；单 IP 5 次/时、20 次/日，待审队列上限 100 |
| 头 | `X-Publisher` | 可选，实例名，审核时用来识别来源 |

> 发布不等于上架。只有管理员审批通过后，`index.json` 才会出现这个插件，其他部署者才看得到。

管理（`X-Admin-Token`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/admin/pending` | 待审队列（含 `publisher` 来源） |
| POST | `/api/v1/admin/review/:submissionId` | `{action: approve\|reject, note}` |
| POST | `/api/v1/admin/upload` | （可选）管理员直接投递，同样进 pending |
| POST | `/api/v1/admin/yank/:pluginId` | 下架（可指定版本） |
| POST | `/api/v1/admin/revoke` | 加入吊销名单 |
| GET | `/api/v1/admin/stats` | 统计 |
| GET | `/api/v1/admin/audit` | 审计日志 |

## 状态机（文档 §6.6）

```
上传 → pending ──approve──→ approved ──yank──→ yanked ──重新上架──→ approved
          │                     └──blocked（进吊销名单，不自动卸载）
          └──reject──→ rejected ──改完重传──→ pending
```

- 一个 `id` 只能有一个 owner，先到先得。
- `approved` / `yanked` 的版本不能覆盖，必须升版本号。
- 下架只停新下载与索引展示，已装实例继续可用。

## 还没做的

- Ed25519 签名（`/revocations.json` 里 `signature: null` 占位），宿主当前只提示不阻断。
- 审核后台 2FA（TOTP），现在只有 token。
- README 的 Markdown 渲染（现在后台按原文展示，避免 XSS）。
- 自动冒烟调用（「实际调用一次」按钮）。

这四项按文档 §11.3 属于 M2 收尾 / M4 治理，接口位置已经留好。
