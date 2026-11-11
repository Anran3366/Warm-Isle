# 开源发布规则（双仓库）

> 现状：主仓库私有；计划另建 **Gitee 开源仓** 与 **GitHub 开源仓**。
> 原则：**任何一次向开源仓的推送，都必须经仓库所有者明确同意后才可执行。**

## 1. 仓库划分

| 仓库 | 用途 | 可见性 | 谁能推 |
|---|---|---|---|
| 主仓（当前 `warm_isle`） | 生产代码、运维数据、商业方案 | 私有 | 所有者 + 授权会话 |
| 开源仓（Gitee） | <https://gitee.com/menran/warm_isle> | 公开 | **仅所有者手动推送** |
| 开源仓（GitHub） | <https://github.com/Anran3366/Warm-Isle> | 公开 | 由 **Gitee 镜像推送**，本地不直连 |

> 开源仓不是主仓的远程分支，而是**独立的一次性快照 + 后续手动同步**。
> 不设置自动部署、不设置 mirror/CI 自动推送。

## 2. 硬性规则

1. **禁止自动推送**：任何脚本、定时任务、CI 都不得向开源仓 push；
2. **禁止直接 add remote 后顺手 push**：开源仓的 remote 只在同步时临时使用；
3. 每次同步前必须：
   - 跑 `scripts/sanitize-oss.sh` 生成脱敏副本；
   - 人工检查 diff；
   - **获得所有者口头/文字确认**；
   - 才执行 push。
4. 所有者未确认时，宁可不同步，也不推。

## 3. 不能进入开源仓的内容

| 类别 | 具体 |
|---|---|
| 密钥 | `.env`、API Key、邮箱授权码、后台密码、NapCat token、SMTP 密码 |
| 数据 | `data/`、`*.db`、聊天记录、人设、表情包素材、日志、二维码 |
| 隐私 | 服务器 IP、域名、QQ 号、邮箱、主机名、真实姓名 |
| 商业 | `docs/saas-plan.md`（定价与商业策略） |
| 版权 | 小说/剧本文本、第三方表情包、受版权保护的素材 |
| 内部 | `docs/HANDOFF.md`（含生产地址与运维细节）、`deploy/linux/.env*` 的真实值 |
| 站点 | `site/`（个人主页、官网、形象图与表情包素材） |
| 商业代码 | `saas/`、`ProvisionerClient`、`billing/`、多租户/客户管理/额度、`deploy/linux/provisioner/`、`docker-compose.saas.yml`、`docs/saas-*.md` |

## 4. 脱敏替换表（同步时必做）

| 原值 | 替换为 |
|---|---|
| `203.0.113.10` | `203.0.113.10`（文档示例 IP） |
| `100000000`、`100000000` 等真实 QQ | `100000000` |
| `user@`、`user@` | `user@` |
| `/srv/qqbot` | `/srv/qqbot` |
| 真实邮箱、授权码、token | `REDACTED` |
| 服务器主机名 `your-server` | `your-server` |

## 5. 同步流程（每次）

```bash
# 1) 生成脱敏快照到临时目录
bash scripts/sanitize-oss.sh /tmp/ai-companion-oss

# 2) 人工检查
cd /tmp/ai-companion-oss
git status
grep -rnE "110\.42\.203\.207|100000000|sk-[a-zA-Z0-9]{10,}" . | head

# 3) 确认无误后（所有者同意）再推送
git remote add gitee https://gitee.com/<owner>/<oss-repo>.git
git push gitee main --force

git remote add github git@github.com:<owner>/<oss-repo>.git
git push github main --force
```

## 6. 贡献与版权

- 采用 **AGPL-3.0**：修改后通过网络提供服务，也必须公开源码；
- 保留版权声明；外部贡献建议要求签署 **CLA**（贡献者许可协议），
  否则所有者无法对自己的 SaaS 版本进行商业授权（双许可）；
- 不接受纯 AI 生成的大段代码，避免版权来源不明。

## 7. 权限模型（Fork 模型）

**目标：任何人都不能直接向我们的开源仓推送代码。**

| 角色 | 能做什么 |
|---|---|
| 所有者 | 唯一有 write 权限，负责所有合并与发布 |
| 其他人 | 只能 **Fork 到自己仓库** → 在自己的仓库里改 → 想合并时提 PR（拉取请求） |
| 未授权者 | 连分支都推不了（远端拒绝） |

### Gitee 设置

1. 仓库 →「管理」→「仓库成员管理」：**只保留自己，其他人一律不加**；
2. 「管理」→「分支设置」→ 保护分支 `main`：
   - 禁止强制推送（force push）
   - 仅管理员可推送
   - 可关闭「允许 PR 自动合并」
3. 「管理」→「Pull Requests」：按需关闭（见下）

### GitHub 设置

1. Settings → Manage access：只有自己；
2. Settings → Branches → Add branch protection rule（`main`）：
   - Require pull request before merging
   - Do not allow force pushes
   - Restrict who can push（仅自己）
3. Settings → General → 关闭 Wiki/Projects 等不需要的功能。

### 是否接受 PR

| 选择 | 适用 | 代价 |
|---|---|---|
| **接受 PR** | 想积累社区 | 必须处理 CLA，否则你失去双许可能力 |
| **不接受 PR**，只接受 issue | 想保持完全控制 | 别人只能自己 fork，改动不会回流 |

建议初期：**不接 PR，只收 issue**。等自己节奏稳了再决定要不要 CLA。

### 别人 fork 后的义务

- Fork 出来的仓库必须保留 **AGPL-3.0** 许可证与版权声明；
- 如果他把修改版通过网络对外提供服务，**必须公开他的源码**；
- 他不能把你的代码改成闭源再分发。

## 8. GitHub 同步（镜像方式）

Mac 与云服务器都连不上 github.com，因此**不用本地 push**，改为 Gitee 仓库镜像：

1. Gitee 仓库 →「管理」→「仓库镜像管理」→「添加镜像」；
2. 方向选 **Push**（把 Gitee 推到 GitHub）；
3. 填 GitHub 仓库地址：`https://github.com/Anran3366/Warm-Isle.git`；
4. 认证填 GitHub **Personal Access Token**（classic，勾选 `repo`）；
5. 保存后可手动「同步」，也可开启自动同步。

> Token 只填在 Gitee 后台，不要写进仓库或文档。

## 9. 首次开源前检查清单

- [ ] 所有线上密钥已轮换（旧 key 作废）
- [ ] 历史中无真实密钥（建议新建干净仓库，不带历史）
- [ ] `docs/saas-plan.md`、`docs/HANDOFF.md` 未包含在开源快照
- [ ] 文档中无真实 IP / QQ / 邮箱 / 主机名
- [ ] `LICENSE`（AGPL-3.0）已放好
- [ ] README 含免责声明与平台合规说明
- [ ] 无版权素材
