# 更新日志

> **规则见 `AGENTS.md` 的「更新日志规则」**：任何人拉取代码并修改后，提交前必须在本文件追加一条记录。
> 只追加、不删改历史；提交哈希可后补。

## 记录模板

```
## YYYY-MM-DD HH:mm
- 设备：<主机名> · <系统版本> · <用户> · <工具/Agent>
- 变更：<一句话说清改了什么、为什么>
- 影响：<前端 / 后端 / 部署 / 文档 / 配置>
- 提交：<commit hash>
```

---

## 2026-10-28 13:42

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：官方机器人版改为独立项目方案——独立私有仓 `warm_isle_official`、无历史快照、upstream 手动同步、不合并回 main；补充不合回 main 的风险、Caddy 共享处理与独立发布规则
- 影响：文档 / 部署规划
- 提交：见本次提交

## 2026-10-28 13:40

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增《暖屿·小满官方版开发计划》——纯私聊 C2C、4 被动 + 1 主动、官方 API 适配、记忆 v2/知识库/表情/语音/主动消息/心情全量移植、独立部署 official.example.com
- 影响：文档 / 部署规划
- 提交：见本次提交

## 2026-10-28 11:15

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：HANDOFF 增加「部署注意」——必须用 build-jar.sh（否则前后端版本错位）、部署后自检前端字符串、npm cache 权限处理、部署顺序
- 影响：文档
- 提交：见本次提交

## 2026-10-28 11:00

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆设计补充关系类主动回忆（吵架软化/约定提醒/长期冷淡低压力试探），确认加班跟进时间为次日 21:00–22:30
- 影响：文档
- 提交：见本次提交

## 2026-10-28 10:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增《记忆系统设计 v2》docs/memory-design.md——记忆与知识库合并、可行动性打分的记/不记规则（加班记、香菜不记）、主动回忆（follow_up_at + 主动消息）、被动检索与分期计划
- 影响：文档
- 提交：见本次提交

## 2026-10-28 10:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：历史轮数默认 20 → 10（降低 token 与复读概率）；复读检测改为与最近 2 条机器人回复比较，相似度（二元组 Jaccard）≥ 0.8 自动重写一次
- 影响：后端 / 配置 / 前端默认值
- 提交：见本次提交

## 2026-10-28 10:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复时间观念与复读——① 距上条消息 >2 小时时提示词明确“这是新的一段对话”，>6 小时只带 6 条历史；② 回复与上一条高度相似时自动重写一次
- 影响：后端 / 系统提示词
- 提交：见本次提交

## 2026-10-28 09:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：表情包点名修复——用户说「发一张 XX 的表情包」时，只在库里按关键词匹配；找不到就直接说明「没有这张」，不再随机发不相关表情
- 影响：后端（表情选择）
- 提交：见本次提交





## 2026-10-26 14:23

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：基于官方 API v2 2026-10 文档整理《QQ 官方机器人能力地图》，覆盖消息、事件、群/频道管理、Agent 接入、配额与边界，输出 Markdown 与带目录/超链接的 DOCX
- 影响：文档（新增 `docs/qqbot-official-capabilities.md` / `.docx`）
- 提交：3867a85







## 2026-10-26 16:08

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：README 增加「命名说明」，明确与 Gitee 上同名 FastAPI 项目无关；随后同步开源快照
- 影响：文档 / 开源发布
- 提交：见本次提交
## 2026-10-26 16:02

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：品牌名统一为「暖屿 · 小满」——后台标题/登录页/侧栏/关于页/PWA 名称、项目介绍页与个人主页（含隐私页）、README、隐私说明、SaaS 方案与教程稿全部更新；原因是 Gitee 上已有同名「暖屿 / Warm Isle」项目（2026-06 创建），加机器人名做区分
- 影响：前端 / 站点 / 文档
- 提交：见本次提交
## 2026-10-26 15:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：首页两个按钮（看看它能做什么 / 怎么用）在部分客户端上挤在一起——把 `.cta` 的 flex `gap` 换成子元素 `margin`（旧内核如微信/QQ 内置浏览器不支持 flex gap），按钮改 `inline-flex + white-space:nowrap`；Caddy 给 `/home/*`、`/xiaoman/*` 加 `Cache-Control: no-cache`，避免浏览器启发式缓存一直看到旧页面
- 影响：站点 / 部署
- 提交：见本次提交
## 2026-10-26 14:52

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增教程稿 `docs/posts/deepseek-qq-tutorial.md`（如何用 DeepSeek + 暖屿把 AI 接到 QQ），含架构图、八步部署、SnowLuma 可选方案、5 个真实踩坑与合规声明，对外链接指向开源仓 warm_isle
- 影响：文档
- 提交：见本次提交
## 2026-10-26 14:36

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：SnowLuma 实例内存上限从 1.2G 提到 2G，并显式给 3G memory-swap（原 1.2G 长期跑在 98%，宿主机在靠 swap 硬撑）；存量容器已用 `docker update` 热调，后续新建的按新默认值
- 影响：部署 / 配置
- 提交：见本次提交
## 2026-10-26 14:26

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：已有账号可以原地切换接入方式——账号卡片新增「切换接入」菜单，调用 `POST /api/account/{id}/backend`：停掉并删除旧协议端容器，按新协议端重建（端口/卷/权限随之变化），账号数据库不动，切换后回到扫码登录
- 影响：后端 / 前端
- 提交：见本次提交
## 2026-10-26 14:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复多账号时后台首页直接返回 `{"code":"BOT_ID_REQUIRED"}`——`BotContextFilter` 是全局过滤器，对 `/`、`/assets/**` 也要求 `X-Bot-Id`；改为只对 `/api/**` 生效，静态资源与前端路由直接放行（首页此前靠浏览器缓存才显得正常）
- 影响：后端
- 提交：见本次提交
## 2026-10-26 14:16

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：SnowLuma 的 token 清理改为覆盖 `onebot*.json` 全部配置——账号级配置 `onebot_<uin>.json` 会覆盖全局配置，只清 `onebot.json` 时登录后仍被拒（`rejected unauthorized WebSocket upgrade`）
- 影响：后端 / 部署
- 提交：见本次提交
## 2026-10-26 14:14

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复 SnowLuma 账号登录成功但后台一直显示离线——它的 OneBot WS 会自动生成 accessToken，而后端连的是 NapCat 那套（空 token）被拒；改为实例启动后由后端把容器内 `onebot.json` 的 token 清空（端口仅回环/内部网络），架构上先不做「每账号 token」的持久化
- 影响：后端 / 部署
- 提交：见本次提交
## 2026-10-26 14:08

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修正 SnowLuma 截图截到整个桌面的问题——原来的 `grep '"QQ"' | head -1` 会取到 1x1 的小工具窗（甚至取不到），改为按面积挑最大的 QQ 窗口，且找不到时不再退回整屏截图，而是让前端提示「还没截到二维码」；前端给二维码图片加加载失败兜底
- 影响：后端 / 前端
- 提交：见本次提交
## 2026-10-26 14:02

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增 `qqbot-api-demo` 本地 Demo，验证 QQ 官方机器人 API v2 的主动文本、富媒体表情包、文字转 Tencent SILK 语音三条链路；内置 WebSocket 监听自动捕获 openid，完成真实账号三链路联调
- 影响：新增本地 Demo / 文档（不影响现有构建与部署）
- 提交：见本次提交

## 2026-10-26 14:02

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：SnowLuma 二维码截图调通——修正取窗口尺寸的正则（xwininfo 的窗口 id `0x600003` 会误匹配导致 ffmpeg 失败、后台一直显示旧图）；二维码图片请求不再要求 X-Bot-Id（`<img>` 带不了请求头）；提示文案补充「已过期点刷新」；清理诊断日志与未用方法
- 影响：后端 / 部署
- 提交：见本次提交
## 2026-10-26 13:58

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：SnowLuma 账号的扫码体验补齐——后台请求二维码时，由后端在容器内用 xwininfo 定位 QQ 登录窗、ffmpeg 抓图并以 Docker archive 取回 PNG，前端与 NapCat 一致直接显示图片；截图带 5 秒缓存，失败时回退到「远程桌面扫码」提示
- 影响：后端 / 部署
- 提交：见本次提交
## 2026-10-26 13:52

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：账号管理支持「接入方式」可选——新增 ProtocolBackend 抽象与 NapCat / SnowLuma 两套实现，后台「添加账号」时可选协议端；`bot_accounts` 增加 backend 列（默认 napcat，老数据行为不变）；新增 `GET /api/account/backends`；前端账号卡片显示协议端徽标，SnowLuma 账号的扫码弹窗改为展示远程桌面地址与端口（其二维码不落盘）
- 影响：后端 / 前端 / 数据库 / 部署
- 提交：见本次提交

## 2026-10-26 12:15

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：个人主页与机器人官方页脚增加开源仓链接（Gitee / GitHub）；后台「关于」页仓库地址由私有仓改为开源仓并补 GitHub 链接
- 影响：前端 / 站点
- 提交：见本次提交

## 2026-10-26 12:00

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：README 增强可发现性——补 GitHub 镜像链接、首段写清 DeepSeek / NapCat / OneBot v11 / 多账号 / 长期记忆等关键词，新增「相关关键词」段；同步更新开源快照
- 影响：文档 / 开源发布
- 提交：见本次提交

## 2026-10-26 11:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：确认 GitHub 镜像同步成功（Gitee → GitHub，同提交 92c1725，AGPL-3.0 已被 GitHub 识别，仓库公开）；文档状态更新为已同步
- 影响：文档 / 发布
- 提交：见本次提交

## 2026-10-26 11:40

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：登记 GitHub 镜像仓 https://github.com/Anran3366/Warm-Isle；因 Mac 与服务器均无法直连 github.com，改由 Gitee「仓库镜像（Push）」同步，文档补充配置步骤
- 影响：文档 / 发布流程
- 提交：见本次提交

## 2026-10-26 11:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：把开源仓信息同步进私有仓文档——AGENTS（地址与同步规则）、HANDOFF（进度与待办）、STATUS（能力表），确保新会话/其他模型能读到
- 影响：文档
- 提交：见本次提交

## 2026-10-26 11:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：脱敏脚本增加开源版 README 段落改写——把「当前形态（生产环境）」替换为「部署要求」，开源仓不再暴露服务器规格与目录结构
- 影响：文档 / 开源发布
- 提交：见本次提交

## 2026-10-26 11:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：开源快照 v0.1.0 已推送到 Gitee warm_isle（单提交、无历史），并验证：提交数 1、无内部文档、无真实 IP/QQ/密钥
- 影响：对外发布
- 提交：见本次提交

## 2026-10-26 11:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：明确开源权限模型为 Fork 模型——开源仓只有所有者可推送，其他人只能 fork 到自己的仓库；补充 Gitee/GitHub 分支保护配置步骤与「是否接受 PR」的取舍（初期建议不接 PR、只收 issue）
- 影响：文档
- 提交：见本次提交

## 2026-10-26 10:50

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：确立开源流程——新增 LICENSE（AGPL-3.0）、docs/open-source-policy.md（双仓库、手动同步、脱敏清单）、scripts/sanitize-oss.sh（生成脱敏快照并自检）；README 与 AGENTS 增加「开源仓只能所有者手动推送」的硬规则
- 影响：文档 / 协作流程 / 许可证
- 提交：见本次提交

## 2026-10-26 10:46

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复「掉线但没人知道」——探活只要 WS 能通就算在线，NapCat 停在扫码页时 /api/status 仍报 online=true，看门狗因此从不告警。改为 get_login_info 拿到真实 QQ 号才算在线；/api/status 拆出 loggedIn（已登录）与 napcat（WS 通）两个字段；看门狗据此区分「连不上 → 重启」与「连上但未登录 → 只抓二维码发信，不重启」，避免重启把二维码反复冲掉
- 影响：后端 / 部署
- 提交：见本次提交

## 2026-10-26 10:15

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：NapCat 稳定性加固（无新功能）：①OneBotClient 探活连续失败 3 次主动断链重连，解决半开连接永不重试；②接住 meta_event 心跳，心跳静默 3 分钟判为假在线并重连，同时不再刷 INFO 日志；③私聊文本在断线时进 20 条/60 秒重试队列，重连后按序补发；④docker-compose 的 napcat 支持用 NAPCAT_IMAGE 钉版本并加 1G 内存上限（多账号容器同样加 Memory 限制）；⑤看门狗改为 5/10/20/40 分钟指数退避，避免重启后还在扫码就被下一轮打断
- 影响：后端 / 部署 / 配置
- 提交：见本次提交

## 2026-10-26 10:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增会话交接机制——docs/HANDOFF.md（当前进度/上手顺序/新会话开场提示/交接清单）、scripts/handoff.sh（一键打印状态）、AGENTS.md 与 README 增加交接入口
- 影响：文档 / 协作流程
- 提交：见本次提交

## 2026-10-23 20:10

- 设备：WIN-20260808MQO · Windows 10 Pro 10.0.19045 · Administrator · DSH Agent
- 变更：新增「人格表达」分支功能：QQ 空间说说草稿与模型受众判断（公开/只给目标用户/仅自己/不发）、发布历史；好友画像按聊天频率与最近活跃自动分类，读取 QQ 原生分组；全部能力默认关闭，发布前需人工确认
- 影响：后端 / 前端 / 数据库
- 提交：8ebc8a2

## 2026-10-23 21:42

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：关于页系统状态改为每 5 秒自动刷新（内存/JVM/实例在线状态），离开页面自动停止轮询
- 影响：前端
- 提交：见本次提交

## 2026-10-23 21:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：关于页合并重复的「系统状态」卡片——删除旧的机器人/NapCat 在线状态卡片，记忆条数并入新卡片
- 影响：前端
- 提交：见本次提交

## 2026-10-23 21:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复机器人账号页重复提示——「登录成功」同时由轮询和列表刷新各弹一次，改为单次；删除加防重入，避免重复确认
- 影响：前端
- 提交：见本次提交

## 2026-10-23 21:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 知识库上传支持「解析类型」：生成人设 / 台词风格 / 仅入库；人设类型由模型从小说抽取角色并自动命名，写入当前账号的人设列表可直接选用；② 所有模型类解析限定每天 00:05–06:05，窗口外手动触发直接拒绝；③ 解析前检查可用内存（<500MB 暂停）、每篇间隔 1.5s，避免挤掉 NapCat。实测从测试文本生成人设「萧炎」成功
- 影响：后端 / 前端 / 数据库
- 提交：见本次提交

## 2026-10-23 20:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 修复新账号 QQ 号解析错误（onebot11_ 前缀里的数字被算进 QQ 号）；② 新增删除账号（停连接、删容器、删账号库，默认账号不可删）；③ 看门狗只在登录成功（出现 onebot11_*.json）后才补 WS 配置并重启，避免把扫码中的二维码顶掉
- 影响：后端 / 前端
- 提交：见本次提交

## 2026-10-23 20:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：移除设置页的「能聊天的 QQ」卡片（与机器人账号页按账号配置重复，且只作用于默认账号）；白名单统一在「机器人账号」里配置
- 影响：前端
- 提交：见本次提交

## 2026-10-23 20:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 语音配置改为公用（音色、语速、概率、每日上限、AI 语气开关读 shared.db，所有机器人一致；每日计数仍按账号）；② Prompt 预览在没有真实对话时返回人设示意预览（demo=true），不再空白
- 影响：后端
- 提交：见本次提交

## 2026-10-23 20:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 日志修复——新增 logback-spring.xml 落盘 logs/service.log，按天切分、保留 30 天并 gzip 归档到 logs/archive；日志页可正常查看。② 关于页新增系统状态：服务器内存占用条、JVM 内存、运行时长、系统版本、已开启实例列表（在线/离线）
- 影响：后端 / 前端 / 运维
- 提交：见本次提交

## 2026-10-23 19:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复「人设/记忆/主动消息看起来没隔离」——根因是前端未告知当前机器人。新增 BotContextFilter（读 X-Bot-Id），前端所有请求带该头，顶栏加机器人切换器（多账号时显示）。验证：bot1 人设=小满，bot2 人设为空
- 影响：后端 / 前端
- 提交：见本次提交

## 2026-10-23 19:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增公用库 data/shared.db——知识库、表情包、话题屏蔽改为所有机器人账号共享，首次自动从默认账号库复制；话题屏蔽预置色情/赌博/毒品/政治敏感四类关键词
- 影响：后端 / 数据库
- 提交：见本次提交

## 2026-10-23 19:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：白名单改为「按机器人账号」配置——新增 WhitelistService（读当前账号的库），MessageDispatcher 按 botId 校验；账号页每个账号显示并编辑「可聊天的 QQ」；移除顶栏 QQ 号切换器（多机器人后已无意义）。线上验证两个账号白名单互相隔离
- 影响：后端 / 前端
- 提交：见本次提交

## 2026-10-23 18:50

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号阶段 3b——实现「添加账号=新建独立 NapCat 实例」：Docker Engine API 创建容器（独立端口 3002/6102、独立数据目录），共享二维码目录；新增 /api/account/list|create|{id}/qrcode|{id}/logout；登录后自动补齐 onebot11 的 WebSocket 配置并重启连接、回填 QQ 号；后台账号页改为多账号列表
- 影响：后端 / 前端 / 部署
- 提交：见本次提交

## 2026-10-23 18:30

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号阶段 3 前置——bot_accounts 改为自增 id（扫码前拿不到 QQ 号），新增 ws_url/cache_dir 字段并自动迁移旧表；账号库 bots/<QQ>.db 重命名为 bots/1.db；Registry 按账号 id 连接。线上验证迁移成功、46 条消息完整、机器人在线
- 影响：后端 / 数据库
- 提交：见本次提交

## 2026-10-23 18:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号阶段 2——OneBotClient 改为按账号实例化（botId + wsUrl），新增 OneBotRegistry 管理多条 WebSocket；MessageDispatcher 按 botId 分发并用 BotContext 包裹异步任务；各控制器/主动消息改用 registry；启动时自动把现有配置登记为默认账号；线上验证 1 条连接正常、状态接口 online
- 影响：后端 / 部署
- 提交：见本次提交

## 2026-10-23 17:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复手机扫码后仍看到登录页——前端是 hash 路由，二维码地址需带 /#/m/<token>；已改并验证返回 http://.../#/m/xxx
- 影响：后端（二维码地址生成）
- 提交：见本次提交

## 2026-10-23 17:40

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号阶段 1 完成——Db 按 BotContext 切到 data/bots/<qq>.db，首次打开懒建表；启动时把旧 qqbot.db 自动迁移为 bots/100000000.db（含 WAL），线上验证迁移后 44 条消息完整、登录正常
- 影响：后端 / 数据库 / 部署
- 提交：见本次提交

## 2026-10-23 17:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号阶段 1 第一步——新增 GlobalDb（data/global.db），认证会话、扫码 token、bot_accounts 从业务库拆出并迁移；登录/扫码已验证正常（老会话失效需重新登录一次）
- 影响：后端 / 数据库
- 提交：见本次提交

## 2026-10-23 17:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：多账号方案（B）启动：确定「每账号一个独立 SQLite + 每账号一个 NapCat 容器」架构，新增 docs/multi-account-design.md 与 BotContext 上下文基础设施（本阶段不改变现有行为）
- 影响：文档 / 后端基础
- 提交：见本次提交

## 2026-10-23 16:50

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复扫码授权页白屏——Vite base 从 './' 改为 '/'。此前 /m/:token 下相对资源被解析成 /m/assets/xxx.js 导致 404
- 影响：前端构建
- 提交：见本次提交

## 2026-10-23 16:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：启用 Caddy 反向代理（host 网络，监听 80 → 127.0.0.1:8080），并设置 PUBLIC_BASE_URL=http://203.0.113.10；扫码登录二维码现在手机可打开
- 影响：部署（compose/Caddyfile）/ 配置
- 提交：见本次提交

## 2026-10-23 16:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 登录页重做：粒子背景 + 毛玻璃卡片 + 扫码/密码双模式；② 新增扫码登录后端（login_qr 表、/api/auth/qr/new|status|approve|claim）与手机确认页 /m/:token（二维码 5 分钟有效、确认后 PC 轮询自动登录）；③ 新增「机器人账号」页：显示 QQ 号/在线状态/容器状态，扫码登录（重启 NapCat 取二维码）与下线（停容器并暂停看门狗）；④ 补 SPA 深链接回退，刷新 /m/xxx 不再 404；⑤ NapCat cache 目录挂载、qqbot 挂载 docker.sock 用于容器控制
- 影响：后端 / 前端 / 部署（compose）
- 提交：见本次提交

## 2026-10-23 16:00

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：语音语气指令改为“读上下文生成”——新增 VoiceStyleGenerator，用模型根据最近 6 条对话 + 本轮提问 + 回复内容，生成一句 ≤40 字的语气指令传给 MiMo TTS；失败自动回退规则版；结果 LRU 缓存 200 条；后台语音页新增「AI 语气指令」开关（默认开）
- 影响：后端 / 前端 / 语音
- 提交：见本次提交

## 2026-10-23 15:50

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：TTS 增加情感/语气控制。MiMo TTS 是对话式接口，之前只传 assistant 文本，所以“念稿感”重。现在在 assistant 前加 user 语气指令（口语化/不要播音腔/音高平稳/按时间与心情调整/语速设置生效），并清洗不适合朗读的符号（括号注释、markdown、重复省略号）。实测同一句时长 3.52s → 4.96s，明显更自然
- 影响：后端 / 语音
- 提交：见本次提交

## 2026-10-23 15:40

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复语音/表情包的上下文连续性：① 后台“试听/测试发送”产生的消息改为 voice-test / emoji-test 并从聊天历史中排除（此前会污染对话）；② markLastAsVoice 只标记 assistant 消息，避免误标用户消息；③ 表情包记录改为“（表情包：标签）”，提示词增加“不要模仿（语音）/（表情包）标记”
- 影响：后端 / 提示词
- 提交：见本次提交

## 2026-10-23 15:30

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：核对并合并音色克隆改动（`bcea669` + `b2078ae`），确认已在 `main`；`feat/voice-clone-qc` 与 `main` 无差异（0/0）；重新构建并部署到云服务器，校验 jar 哈希一致（`149d39ae…`）；补全契约中的语音克隆接口
- 影响：文档 / 部署
- 提交：见本次提交

## 2026-10-23 15:25（事件记录）

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent（两个会话共用同一工作目录）
- 事件：15:03 本会话执行 `git add -A`，把另一会话写到一半的 6 个音色克隆文件一起提交为 `7f7f60c`，导致 `main` 短暂编译失败
- 处理：另一会话把完整改动推到 `feat/voice-clone-qc` 后 ff-only 合并进 `main`（`b2078ae`），现已恢复正常
- 改进：本会话后续提交前先 `git status` 核对文件清单，不再无脑 `git add -A`；多会话应使用独立克隆目录

## 2026-10-23 15:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：音色克隆增强——上传后自动质检（时长/采样率/音量/静音 + MiMo ASR 人声校验），不合格直接拒绝并说明原因；支持多音色库（命名、切换当前、单条删除、单条试听），旧版单样本自动迁移；学习完成立即用新音色合成一句试听；参考音 DataURL 增加缓存
- 影响：后端 / 前端 / 配置（接口向后兼容）
- 提交：bcea669

## 2026-10-23 15:15

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：拆条规则细化——长句按逗号再拆一次（≥16 字且含逗号），单条上限默认 60→40 字，更接近真人连发两条
- 影响：后端 / 回复形态
- 提交：见本次提交

## 2026-10-23 15:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：情感记忆（P2）——用户表达负面情绪且能识别出话题时落一条 emotional_memory；两天后白天由主动消息系统追问一次（只问一次，同话题 3 天内去重）；跟进文案会带上具体细节，避免客服腔
- 影响：后端 / 数据库 / 主动消息
- 提交：见本次提交

## 2026-10-23 15:00

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：让聊天更像人（P0/P1/P3/P4）：
  1) 提示词加“说话方式”硬规则（短句/口语/不用书面语/AI 味词表），当前时间移到末尾减少缓存失效；
  2) 回复拆条发送（按句切分、合并碎句、最多 3 条、带打字延迟与条间间隔）；
  3) 新增心情状态机 user_mood（正负向词更新、随时间衰减、注入提示词）；
  4) 语音概率按深夜/低落情绪上调；
  5) 参数可在后台「回复形态」调整（maxChars/splitDelayMs/typingMs/adaptive）
- 影响：后端 / 前端 / 数据库
- 提交：见本次提交

## 2026-10-23 14:48

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复自动部署顺序——先判断是否有代码变更，再停服务；构建前停 qqbot+guard（此前“只停 guard”导致内存不足、qqbot 被 OOM 杀，退出码 137）
- 影响：部署脚本
- 提交：见本次提交

## 2026-10-23 14:42

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：自动部署改为只暂停 guard，qqbot 全程在线，停机从约 2 分钟降到几秒（此前用户消息撞上重启窗口丢失）
- 影响：部署脚本
- 提交：见本次提交

## 2026-10-23 14:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：接入知识检索——聊天时按问题关键词/二元组检索知识库 content 切片，命中（score≥6）才把 top-3 注入 prompt；二元组权重调为 2 提升中文召回
- 影响：后端 / 提示词
- 提交：见本次提交

## 2026-10-23 14:30

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增知识库（上传 md/txt/docx → 清洗切片 → 模型自动分类 → 凌晨 00:00 入库）；台词自动识别为风格样本，每 6 小时轮换注入 prompt（缓存友好）；后台新增「知识库」页面
- 影响：后端 / 前端 / 数据库
- 提交：见本次提交

## 2026-10-23 14:10

- 设备：your-server · Ubuntu 26.04 LTS · root · 运维
- 变更：启用掉线看门狗 timer 与自动部署 timer；配置 SMTP（QQ 邮箱）并验证发信；自动部署增加变更过滤（纯文档提交不重建）
- 影响：运维
- 提交：见本次提交

## 2026-10-23 14:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：① 图片消息改为 kind=image 且内容加「【图片】」前缀，历史回看可见；② 新增 NapCat 掉线看门狗（重启 + 二维码邮件告警，5 分钟一次、最多 5 次）
- 影响：后端 / 运维脚本
- 提交：见本次提交

## 2026-10-23 14:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：图片预筛超时从 8s 放宽到 20s（CPU 推理冷启动 4–6s 易超时），并给 guard 加 healthcheck（兼作模型预热）；新增 GUARD_TIMEOUT_MS 配置项
- 影响：后端配置 / 部署
- 提交：见本次提交

## 2026-10-23 13:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复所有图片被判敏感（回复"这个我不看"）。根因是 Java HttpClient 默认走 HTTP/2，对 guard（uvicorn）先发 h2c upgrade，uvicorn 不支持并丢弃 body，导致 guard 返回 422、Java 按 fail-closed 拦截。改为强制 HTTP/1.1
- 影响：后端 / 图片链路
- 提交：见本次提交

## 2026-10-23 11:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增 `CHANGELOG.md` 与 `AGENTS.md` 的强制更新日志规则（含设备信息要求）
- 影响：文档
- 提交：见本次提交

## 2026-10-23 11:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：白名单环境变量改为 `QQBOT_WHITELIST`（带点的 `qqbot.whitelist` 在容器里不生效，导致白名单为空、任何人都能聊）；用户切换器改为白名单非空即显示
- 影响：配置 / 前端 / 部署文档
- 提交：8fc8d5b

## 2026-10-23 11:42

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：每日语音条数改为可配置（`voice.dailyLimit`，默认 3，后台可调 0–50），后端 4 处 + 前端语音页 + 契约同步
- 影响：后端 / 前端 / API 契约
- 提交：f7cc2c1

## 2026-10-23 11:37

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增 `web/.env.production` 关闭 Mock；修复后台显示假数据、语音试听不生效的问题
- 影响：前端构建 / 部署
- 提交：a00eee5

## 2026-10-23 11:30

- 设备：your-server · Ubuntu 26.04 LTS · root · 云端部署
- 变更：在服务器上用 CPU 导出了 guard 的三个 ONNX 模型（clip_vision / clip_text / nsfw，共 905MB）并重启服务，图片预筛生效
- 影响：运行环境（`deploy/linux/data/guard/`）
- 提交：无代码变更

## 2026-10-23 11:20

- 设备：your-server · Ubuntu 26.04 LTS · root · 云端部署
- 变更：新服务器完成首次部署：Docker、swap、NapCat 扫码登录、OneBot WS（3001）、DeepSeek Key、白名单 `100000000`，端到端对话跑通
- 影响：运行环境
- 提交：无代码变更

## 2026-10-23 10:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：Linux 部署包改用腾讯云内网镜像（apt 3MB/s、PyPI 12MB/s）；修复 guard 构建上下文（`app.py` 不在 `guard/` 子目录）
- 影响：部署脚本 / Dockerfile
- 提交：c9264c8 / 555efe2

## 2026-10-23 10:15

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增 Linux 全云部署方案（`deploy/linux/`）：docker-compose、Dockerfile、Caddy、systemd、构建脚本、README；guard 推理后端按平台自适应
- 影响：部署 / 文档
- 提交：f1b988e

## 2026-10-23 09:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：新增自动部署（Windows 一键上传 `scripts/deploy-to-server.ps1`；服务器 systemd timer 定时拉取 `deploy/linux/auto-deploy.sh`）
- 影响：部署 / 脚本
- 提交：d52baae

## 2026-10-22（历史）

早期里程碑（M0–M6、前端 P1–P3）的提交记录以 `git log` 为准，尚未逐条回填。

## 2026-10-23 18:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：后台「设置」页新增登录密码修改（当前密码 + 新密码 + 确认），密码以加盐 SHA-256 存到 shared.db，改完踢掉所有旧会话并给当前浏览器换新 cookie
- 影响：后端（AuthService/AuthController）/ 前端（SettingsView）
- 提交：18cf651

## 2026-10-23 18:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：聊天记录页显示每条消息来自哪个 QQ，并新增「全部用户 / 指定 QQ」筛选；后端 /api/messages 支持 qqId=0 查全部，新增 /api/messages/users
- 影响：后端（MemoryAdminController/MessageStore/MessageItem）/ 前端（ChatView）
- 提交：18f1ab5

## 2026-10-23 19:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：聊天记录页新增时间范围筛选（今天/近3天/近7天/近30天快捷项 + 自定义起止时间），与「全部用户/指定 QQ」筛选联动，支持清空筛选
- 影响：前端（ChatView）
- 提交：8109bfb

## 2026-10-23 19:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复「进入可编辑页面就弹未保存提示」——markClean 后加 800ms 静默窗口，忽略加载赋值触发的 watch；只有真正改动才标记未保存
- 影响：前端（useUnsavedGuard，影响人设/语音/话题屏蔽/设置等页）
- 提交：44fc083

## 2026-10-23 19:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复机器人把「（表情包）开心,撒娇」这类内部记录当成回复发出去——历史里的表情包/语音记录不再进入对话上下文，改为一条系统提示；并加强规则禁止输出括号标记
- 影响：后端（PromptBuilder）
- 提交：4efb401

## 2026-10-23 19:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：主动消息调度改为遍历所有机器人账号，在各自 BotContext 内判断/发送（此前只跑默认账号的库，且固定用默认连接发消息）
- 影响：后端（ProactiveService）
- 提交：db01c3d

## 2026-10-23 19:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：同步文档——API 契约补 /api/auth/password、/api/messages 的 qqId/from/to、/api/messages/users；STATUS 更新接口与限制；runbook 补改密码/忘记密码流程与「聊天记录与主动消息」章节
- 影响：文档
- 提交：6392b99

## 2026-10-23 19:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：按云端现状重写 README（生产在 203.0.113.10，Docker/Linux 部署为主、本地只做构建）与 runbook（容器运维、备份、改密码、排障）；AGENTS 环境事实改为云端 + 构建机；PLAN 进度表更新到 2026-10-23；多账号设计补运行现状
- 影响：文档
- 提交：d4262c0

## 2026-10-23 19:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：知识库新增「切片」页（列表/搜索/分类/来源过滤/分页），支持切片新增、编辑、删除；删除文件改为软删除，只标记 deleted 并保留切片，切片仍可查看与编辑
- 影响：后端（KnowledgeStore/KnowledgeController）/ 前端（KnowledgeView）
- 提交：d350f8a

## 2026-10-23 20:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情包新增「描述」字段（自动入库时直接存视觉模型的描述，后台上传/编辑可改）；发送表情时用描述做二元组相似度挑选；后台表情卡片展示描述
- 影响：后端（Emoji/EmojiStore/EmojiService/EmojiAutoCollector/EmojiPicker）/ 前端（EmojiView）
- 提交：980275e

## 2026-10-23 21:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：主动消息 v2——随机延迟 + 活跃时段对齐、CALM/PING/SULK/BACKOFF/REENTRY/DND 状态机、「去洗澡/吃饭」1 小时短时跟进、两次不回改退避几天后再出现、低成本 ping（表情/拍一拍）、1–3 条气泡、内容去索取感与黑名单过滤、回复追踪（replied_at/latency）、每日上限与追问/退避/早晚概率可配；新增 ProactivePlanner 单元测试
- 影响：后端（proactive 包、MessageDispatcher、OneBotClient）/ 前端（推送页新增配置项）
- 提交：8297554

## 2026-10-23 21:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情包按优先级全做——模型从候选（带描述）中挑表情；回复可内嵌 [[表情:意图]] 由后端发图；自动入库改为待审核（通过后才会被使用）；同一张每天最多 2 次、同情绪不连发；记录发送次数/最近发送/是否被回应；后台支持状态筛选与通过/拒绝
- 影响：后端（emoji 包 / EmojiStore / EmojiController / PromptBuilder / MessageDispatcher / Db）/ 前端（EmojiView）
- 提交：6184e07

## 2026-10-23 21:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复 shared.db 的表情表未执行新列迁移（status/sent_count/last_sent_at 与 emoji_send_log），导致列表为空、审核接口 500
- 影响：后端（SharedDb）
- 提交：843f25e

## 2026-10-23 22:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：人格表达页两个开关改为切换即自动保存；免打扰窗口增加校验（覆盖超过 20 小时视为无效，回落 23:30–08:00），避免「永远不能发布」
- 影响：后端（ExpressionConfig）/ 前端（ExpressionView）
- 提交：7e8b25b

## 2026-10-23 22:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：机器人账号扫码弹窗新增「刷新二维码」按钮；后端新增 POST /api/account/{id}/qrcode/refresh（离线时重启该账号 NapCat 实例并等待新二维码）
- 影响：后端（AccountService/AccountController）/ 前端（AccountView）
- 提交：d884849

## 2026-10-23 22:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：扫码弹窗增加二维码有效期倒计时与过期提示（NapCat 约 2 分钟换码），过期自动清空旧图并提示点刷新，避免扫到失效二维码
- 影响：前端（AccountView）
- 提交：d133306

## 2026-10-23 23:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：后台机器人账号页新增「好友申请」表格（机器人账号/好友账号/好友名称/同意/拒绝）；后端捕获 OneBot request 事件入库、提供跨账号汇总与同意/拒绝接口（set_friend_add_request）
- 影响：后端（MessageDispatcher/OneBotClient/FriendRequestStore/FriendRequestController/Db）/ 前端（AccountView）
- 提交：49d4385

## 2026-10-23 23:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：主动消息页新增「关系状态看板」（每 5 秒刷新）：汇总人数/今日已发/等回复/退避数/平均回复率，逐用户显示状态机阶段、回复率进度条、未回复数、下次可发时间、最近一条；后端新增 /api/push/dashboard 与回复统计
- 影响：后端（PushController/ProactiveStore）/ 前端（PushView）
- 提交：89f2c34

## 2026-10-23 23:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：手动上传表情改为自动识别——视觉模型生成描述（画面/情绪/场景），标签模型补情绪与场景标签，失败不影响上传；前端上传提示与结果提示同步
- 影响：后端（EmojiAnalyzer/EmojiController）/ 前端（EmojiView）
- 提交：aa9782d

## 2026-10-24 00:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：知识库切片页新增「类型」筛选（全部/内容/风格样本），后端 /api/knowledge/chunks 支持 kind 参数，风格样本可直接筛出查看与编辑
- 影响：后端（KnowledgeStore/KnowledgeController）/ 前端（KnowledgeView）
- 提交：c26ea7e

## 2026-10-24 00:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：可聊天 QQ 改为「点添加即保存」，保存后从服务器重新读取并刷新账号卡片，统一 id 为数字，避免出现「提示成功但不显示」
- 影响：前端（AccountView）
- 提交：a338cd0

## 2026-10-24 00:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：可聊天 QQ 保存时自动收下输入框里未点「添加」的号码，弹窗提示文案同步说明
- 影响：前端（AccountView）
- 提交：56753d7

## 2026-10-24 01:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：记忆表增加「用户 QQ / 昵称」列并支持跨用户汇总；聊天记录按消息 id 正序（用户在上、回复在下）并每 4 秒自动刷新；事实抽取窗口排除 voice-test/emoji-test/test 消息，测试语音不再进记忆
- 影响：后端（MessageStore/MemoryAdminController）/ 前端（MemoryView/ChatView/types）
- 提交：b6fe406

## 2026-10-24 01:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：话题屏蔽改为「关键词命中 + 模型语义判断」，职业/课程等正常提及不再被掐断，拒绝话术软化；聊天历史不再保存 [[表情:…]] 标记；表情占位改为「（我发了一张表情包：…）」；提示词新增不顶撞、主动推进、别自贬三组规则
- 影响：后端（TopicBlockService/MessageDispatcher/ChatService/ChatEmojiReplier/PromptBuilder）
- 提交：686ba44

## 2026-10-24 02:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：设置页「聊天表情自动入库」开关接上真实配置（emoji.autoCollect），去掉「后端暂未支持」的只读提示
- 影响：后端（SettingsService）/ 前端（SettingsView）
- 提交：f0dfba7

## 2026-10-24 02:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：聊天记录改为 SSE 实时推送（消息落库即推送到后台），轮询降为 15 秒兜底；单页最多条数由 100 提到 500，前端一次取 300 条
- 影响：后端（MessageEventBus/MessageStore/MemoryAdminController）/ 前端（ChatView）
- 提交：dd91baf

## 2026-10-24 02:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：PWA 由「等待确认更新」改为「自动更新」，避免部署后浏览器一直跑旧缓存（表情自动入库开关看不到变化就是缓存导致）
- 影响：前端（vite.config.ts）
- 提交：2c43fd7

## 2026-10-24 02:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增 scripts/deploy-now.ps1，push 后立即触发云端部署并等待结果，直接输出「已上线 + 提交号 + 容器状态」，不再等 5 分钟定时器
- 影响：脚本 / 部署
- 提交：f0183ae

## 2026-10-24 03:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：临时增加 /api/account/{id}/debug/action 调试端点，用于确认 NapCat 是否支持好友申请相关 action（排查后台收不到好友申请）
- 影响：后端（AccountController/AccountService）
- 提交：8b1017f

## 2026-10-24 03:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：记录所有非 message 事件（post_type/request_type/notice_type/user_id/flag），用于定位好友申请事件为何没进后台
- 影响：后端（OneBotClient）
- 提交：84535ad

## 2026-10-24 00:25

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：非消息事件日志增加原始 JSON（截断 800 字），用于确认好友申请事件的真实结构
- 影响：后端（OneBotClient）
- 提交：2dac4f2

## 2026-10-24 00:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：SSE 订阅/推送增加日志，用于排查聊天记录实时刷新不生效
- 影响：后端（MessageEventBus）
- 提交：d73c8a7

## 2026-10-24 00:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：聊天记录兜底轮询从 15 秒改为 2 秒，即使 SSE 不可用也能接近实时
- 影响：前端（ChatView）
- 提交：a397f56

## 2026-10-24 00:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复 ChatView 未在 onMounted 调用 startRealtime/startStream 的问题——之前 SSE 与轮询代码写了但从未启动，所以聊天记录不会自动刷新
- 影响：前端（ChatView）
- 提交：f4d02b2

## 2026-10-24 01:25

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：手动「抽取记忆」改为抽取所有聊过天的用户（不再只抽当前用户），返回用户数与总条数
- 影响：后端（MemoryAdminController）/ 前端（MemoryView）
- 提交：aeb47de

## 2026-10-24 01:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复设置「保存后仍显示默认值」：读取设置前按当前机器人账号重新从库刷新；语音页的语音概率/每日上限改为直接读库
- 影响：后端（SettingsService/VoiceController）
- 提交：1aa4c22

## 2026-10-24 01:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：语音概率/每日上限改从公用库（shared.db）读取，和语音页保存位置一致，重启后不再回落到 20%
- 影响：后端（SettingsService）
- 提交：e309e07

## 2026-10-24 02:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：添加账号时可选「迁移旧账号数据」——新建后把旧账号的聊天记录/记忆/人设/配置复制到新库，再删除旧账号；取消「默认账号不可删除」，改为至少保留一个账号
- 影响：后端（AccountService/AccountController）/ 前端（AccountView）
- 提交：ccad7b6

## 2026-10-24 02:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：可聊天 QQ 配置改为从机器人好友列表模糊搜索选择（保留手动输入），新增 /api/account/{id}/friends 接口
- 影响：后端（OneBotClient/AccountController）/ 前端（AccountView）
- 提交：86d9893

## 2026-10-24 02:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：添加账号支持三种模式：全新 / 复制旧账号数据（保留旧账号）/ 迁移旧账号数据（删除旧账号）；复制用于旧号加好友受限、让用户加新号继续聊同一份数据
- 影响：后端（AccountService/AccountController）/ 前端（AccountView）
- 提交：1a3e459

## 2026-10-24 03:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：人设改为全局共享库，各机器人账号用 settings.persona.activeId 选择自己用哪个人设；人设抽取改为开头/中间/结尾采样，并在入库前让模型润色成完整提示词；知识库新增「人设」页可查看、编辑、启用
- 影响：后端（PersonaStore/KnowledgeService）/ 前端（KnowledgeView）/ 数据迁移（shared.db personas + 各账号 activeId）
- 提交：be3ec02

## 2026-10-24 03:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：回滚「共用同一份数据」，恢复为「复制旧账号数据」——旧账号保留，新账号是独立快照，之后各聊各的
- 影响：后端（GlobalDb/BotDataResolver/Db/AccountService/AccountController）/ 前端（AccountView）
- 提交：64158d1

## 2026-10-24 04:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：人设页新增「配置账号」选择器，可指定给哪个机器人账号启用人设；personas 接口支持 botId 参数
- 影响：后端（PersonaController）/ 前端（PersonaView）
- 提交：306c0e2

## 2026-10-24 04:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：删除账号时可选「保留数据」或「同时删除数据」；保留的数据按 QQ 记入 kept_data，以后用同一 QQ 登录新账号时自动接回
- 影响：后端（GlobalDb/AccountService/AccountController）/ 前端（AccountView）
- 提交：6e3e811

## 2026-10-24 13:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：提示词新增「时间感」规则并按白天/夜间分档，新增 TimeGuard 兜底改写夜间用语；聊天记录移动端排版适配；人设页新增只读「系统提示词」查看；新增 docs/prompt-changelog.md 与 AGENTS 规则（改提示词必须记日志）
- 影响：后端（PromptBuilder/ChatService/TimeGuard/PersonaController）/ 前端（PersonaView/ChatView）/ 文档
- 提交：a89c2db

## 2026-10-24 14:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：机器人账号页新增主人 QQ 设置；主人可在聊天里让机器人改昵称（set_qq_profile）/换头像（set_qq_avatar）；记录形象修改历史，昵称可一键恢复
- 影响：后端（BotProfileService/MessageDispatcher/OneBotClient/AccountController/Db/ImageService）/ 前端（AccountView）
- 提交：69a9a3f

## 2026-10-24 14:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增「每日复盘」——每天 00:10 自动复盘前一天聊天，按用户给出评分/问题/建议，可在页面手动运行并把建议以【复盘补充】追加到当前人设
- 影响：后端（DailyReviewService/ReviewController/Db）/ 前端（ReviewView/路由/菜单）
- 提交：84ba190

## 2026-10-24 14:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复主动消息时间错乱——提示词禁止写具体时间点、按时段判断状态；新增 sanitize 去掉「都9.55了」并把白天的「没睡醒」改为「在忙」
- 影响：后端（ProactiveComposer）/ 文档（prompt-changelog）
- 提交：e7f5ef4

## 2026-10-24 15:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：主动消息区分 reach/silence——用户回过后不再用「沉默/你没回我」话术，改为分享自己的小事；时间规则禁止引用旧时间点
- 影响：后端（ProactiveService/ProactiveComposer）/ 文档
- 提交：e527d00

## 2026-10-24 15:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：人格表达新增「每日自动生成说说」配置（0-5 条/天，每小时检查）；新增待审核说说表格，可改受众、发布、删除
- 影响：后端（ExpressionConfig/QzoneExpressionService/ExpressionController）/ 前端（ExpressionView）
- 提交：5bceab8

## 2026-10-24 16:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：说说草稿与发布历史显示定向受众的 QQ 和昵称（仅TA可见时不再只有一个标签）
- 影响：后端（QzoneExpressionService）/ 前端（ExpressionView）
- 提交：bfd106a

## 2026-10-24 16:25

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：语音回复新增「明确要语音」检测（当前句或最近 4 条内出现语音/发段/听你说话等词时强制发语音，跳过间隔限制）；两次语音最小间隔从 4 小时降到 90 分钟
- 影响：后端（ChatVoiceReplier）
- 提交：83c493f

## 2026-10-24 16:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：语音每日上限与最小间隔改为按用户独立计算，避免别人的语音额度把我的用掉
- 影响：后端（ChatVoiceReplier）
- 提交：0e78583

## 2026-10-24 16:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：待确认记忆显示用户 QQ 与昵称；新增记忆自动审核（每天 06:30 结合前一天聊天判断，通过/拒绝/保留），后台可手动触发
- 影响：后端（FactReviewService/MemoryAdminController/FactStore/Db）/ 前端（MemoryView）
- 提交：74a86f1

## 2026-10-24 17:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：提示词新增「别赶人走」：禁止主动结束对话、禁止用「先歇会儿/明天再找你」收尾，只在对方明确要睡或忙时才收
- 影响：后端（PromptBuilder）/ 文档（prompt-changelog）
- 提交：0f3dcb7

## 2026-10-24 17:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：拟人化四项——熟悉度分级注入提示词；每日生活小事库；回复防重复改写；每日复盘新增「像真人」评分
- 影响：后端（FamiliarityService/LifeEventService/PromptBuilder/ChatService/DailyReviewService/Db）/ 前端（ReviewView）
- 提交：7f3357d

## 2026-10-24 18:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增「关系与心情」页面，按用户展示熟悉度等级/进度、聊天条数、认识天数、当前心情和语气说明
- 影响：后端（RelationController/FamiliarityService/MoodService）/ 前端（RelationView/路由/菜单）
- 提交：63661f3

## 2026-10-24 18:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：提升表情包理解——视觉描述要求识别文字与谐音梗；聊天提示词新增「看懂表情包」，不按字面追问
- 影响：后端（VisionClient/PromptBuilder）/ 文档
- 提交：d3e05e8

## 2026-10-24 19:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：机器人发出的表情包现在记录表情 id 和画面描述，聊天记录里直接显示表情原图；模型也能看到自己发过哪张表情
- 影响：后端（Db/MessageStore/MessageItem/ChatEmojiReplier）/ 前端（ChatView/types）
- 提交：d40cdc9

## 2026-10-24 19:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：换头像意图识别扩展到「把头像设置成/用这张」等说法；支持两步——先答应、再发图自动替换，不再只是嘴上答应
- 影响：后端（MessageDispatcher/BotProfileService）
- 提交：c6b092f

## 2026-10-25 00:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复移动端数据不变——后端按 X-Bot-Id 校验账号，已删除账号的残留 id 自动回退默认账号；所有 /api 响应加 no-store，GET 加时间戳防缓存
- 影响：后端（BotContextFilter）/ 前端（client.ts）
- 提交：70a4938

## 2026-10-25 01:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：时间分段调整——18:00-20:00 为傍晚，禁止“大晚上/这么晚/该睡了”，20:00 后才算晚上；主动消息同步调整并清洗
- 影响：后端（PromptBuilder/TimeGuard/ProactiveComposer）/ 文档
- 提交：0fd2857

## 2026-10-25 01:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情包按 SHA-256 去重，自动收集时重复图片直接跳过；启动时补齐哈希并清理已存在的重复文件；审核「拒绝」改为直接删除表情
- 影响：后端（SharedDb/EmojiStore/EmojiService/EmojiController）/ 前端（EmojiView）
- 提交：0d364bc

## 2026-10-25 02:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：支持听懂语音——收到语音消息时用 NapCat get_record 取音频，走小米 MiMo-V2.5-ASR 转文字后再交给模型回复
- 影响：后端（AsrService/OneBotClient/MessageDispatcher）
- 提交：93a3cfa

## 2026-10-25 02:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增「试运行·一键放通」开关：开启后自动通过好友申请，并允许所有好友直接聊天（绕过白名单）；关闭恢复原限制
- 影响：后端（AccessService/AccessController/MessageDispatcher/FriendRequestStore）/ 前端（SettingsView）
- 提交：aed2b12

## 2026-10-25 03:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复只有语音的消息被丢弃（不回复）；收到的语音和发出的语音都落盘，聊天记录里显示可播放的音频
- 影响：后端（MessageDispatcher/MessageStore/VoiceFileService/VoiceFileController/ChatVoiceReplier/Db）/ 前端（ChatView/types/adapters）
- 提交：cdca570

## 2026-10-25 03:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：回复拆分支持 1-5 条并按长度/随机决定条数；提示词放宽到最多 5 句，要求每句完整、不留半句
- 影响：后端（ReplyComposer/PromptBuilder）/ 文档
- 提交：e2302c0

## 2026-10-25 04:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增新好友引导——通过申请后自动自我介绍；3 小时无回复补一个话题；再 6 小时仍无回复则停止主动，不再打扰
- 影响：后端（OnboardingService/Db/MessageDispatcher/FriendRequestController）
- 提交：d1322bc

## 2026-10-25 04:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情包按时段过滤——凌晨/白天不会发「早安」类表情，上午不会发「晚安」类；选表情时也会提示当前时段
- 影响：后端（EmojiTimeFilter/EmojiPicker/EmojiSelector/ChatEmojiReplier）
- 提交：03ef42f

## 2026-10-25 04:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：语音回复后可以再补表情；熟悉度改为「消息数 + 活跃天数×30」加权，避免一天刷很多条直接跳级
- 影响：后端（MessageDispatcher/FamiliarityService/MessageStore）
- 提交：4a4775f

## 2026-10-25 05:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情包增加感知哈希（aHash）去重，压缩/转格式后的同一张图也能识别，汉明距离 ≤6 视为重复
- 影响：后端（SharedDb/EmojiStore/EmojiService）
- 提交：f2ada47

## 2026-10-25 05:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：usage 记录增加 qq_id，新增按用户汇总花费查询（成本表后端第一步）
- 影响：后端（Db/UsageStore/ChatService）
- 提交：51448da

## 2026-10-25 06:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增「成本」页面：今日花费/Token、近 7 天花费、按用户花费排行（含昵称）
- 影响：前端（CostView/路由/菜单）
- 提交：8735d17

## 2026-10-25 06:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：主动消息增加重复检测；新增旧表情批量重识别接口（补“文字”描述）；心情改为模型异步判断（-1~1，按 0.4 权重并入）
- 影响：后端（ProactiveService/EmojiController/MoodService/MoodJudge/MessageDispatcher）
- 提交：2de4b0e

## 2026-10-25 06:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复语音连发——语音意图只看当前句和最近 2 条；上一条消息已是语音时不再发送
- 影响：后端（ChatVoiceReplier/MessageStore）
- 提交：760fcb0

## 2026-10-25 07:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增 SearXNG 元搜索容器（启用百度/必应/搜狗，JSON 输出），供机器人自助搜梗与找表情
- 影响：部署（docker-compose/searxng 配置）
- 提交：4067668

## 2026-10-25 07:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增梗学习与自助搜表情——每天抓热梗入库；用户要「X 的表情包」时先联网理解梗，再搜图、审核、入库、发送
- 影响：后端（SearxClient/MemeService/Db/ImageService/MessageDispatcher）
- 提交：ccb567c

## 2026-10-25 08:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：关于页系统状态新增容器内存表（含 SearXNG/qqbot/napcat 等），并显示后端 JVM 实际占用
- 影响：后端（SystemStatusController）/ 前端（AboutView）
- 提交：e8fb1fd

## 2026-10-25 08:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：梗理解改为两步——先查人物/事件背景再解释梗；图片搜索多关键词兜底（原句/去虚词/主体）
- 影响：后端（MemeService）
- 提交：5f5c61f

## 2026-10-25 09:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：一键放通开启时自动通过已堆积的好友申请，并每 5 分钟补扫一次，修复“开关开着却还能看到待处理申请”
- 影响：后端（AccessService）
- 提交：8f8b949

## 2026-10-25 10:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：补齐部署文档——新增 docs/deploy-guide.md（最低配置、.env 逐项说明、扫码加账号、使用说明、运维指令、FAQ）；新增 scripts/install-server.sh 与 scripts/setup-windows-all.ps1 一键脚本；补全 .env.example 的 SearXNG/ASR 配置
- 影响：文档 / 脚本 / 配置示例
- 提交：e942712

## 2026-10-25 10:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：登录页改为与后台一致的暖色主题（去掉蓝黑粒子背景），并适配手机
- 影响：前端（LoginView）
- 提交：c651468

## 2026-10-25 10:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：登录页保留粒子特效，改为暖色粒子与暖色光晕，与后台配色统一
- 影响：前端（LoginView）
- 提交：5d6884a

## 2026-10-25 11:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增两个静态站点——个人主页 /home/（个人项目展示，无商业内容）与小满项目介绍 /xiaoman/；Caddy 增加静态路由，后台 / 与 /api 行为不变
- 影响：部署（Caddyfile/compose/site）、页面
- 提交：33006c2

## 2026-10-25 12:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：个人主页升级炫技版——WebGL 暖色流体背景、粒子网络、自定义光标、3D 倾斜卡片、滚动进度、文字入场与跑马灯
- 影响：站点（site/home）
- 提交：e6746c9

## 2026-10-25 20:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复刷新二维码用错容器名的问题（默认账号容器是 napcat，不是 napcat-1），改为从账号记录读取实际容器
- 影响：后端（AccountService）
- 提交：1c9f7da

## 2026-10-25 21:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：移除“默认账号”概念——删除 default-id 配置与 1L 兜底；无上下文时按实际账号（唯一账号或第一个真实账号）处理；SSE 必须显式传 botId；二维码路径按账号实际容器判断；前端不再默认账号 1
- 影响：后端（Db/GlobalDb/OneBotRegistry/BotContextFilter/MessageStore/BotProfileService/AccountService/SSE）/ 前端（MainLayout/PersonaView/ChatView）
- 提交：d12d1cb

## 2026-10-25 20:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：预算兜底话术改为「我这会儿有点卡，等下再回你哈」，不再说“聊得有点多/先歇会儿”；线上预算从 0.1 元临时调到 3 元
- 影响：后端（BudgetGuard/ChatService）/ 配置
- 提交：a753376

## 2026-10-25 21:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：每个账号每日预算统一设为 5 元；新增账号默认预算 5 元（DAILY_BUDGET 可覆盖）
- 影响：配置 / 后端（application.yml）
- 提交：a753376

## 2026-10-25 21:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：表情选择加严——语境不贴切不选，禁止在非打招呼场景发「在呢/收到/你好」类回应表情
- 影响：后端（EmojiSelector/ChatEmojiReplier）
- 提交：a753376

## 2026-10-25 18:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复 NapCat 重登后 WebSocket 假死——超过 120 秒无事件主动重连；/api/status 离线时返回 online:false 不再 500
- 影响：后端（OneBotClient/StatusController）
- 提交：4f38d0d

## 2026-10-25 18:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增 wsproxy 缓冲代理——qqbot 与 NapCat 之间加一层 WebSocket 代理，部署重启时事件先缓存，新实例连上后补发，NapCat 侧不断开
- 影响：部署（docker-compose/wsproxy）/ 配置
- 提交：0666820

## 2026-10-25 19:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：P0 修复——过滤器不再静默回落到第一个账号（单账号自动、多账号必须传 X-Bot-Id、无账号 503）；离线接口不再 500；外部搜图接入 imageguard。P1 部分——消息按用户分队列处理，语音/搜图不再阻塞其他人；表情包意图排除「别发/少发」
- 影响：后端（BotContextFilter/OneBotRegistry/ImageService/MemeService/MessageDispatcher/EmojiController/VoiceController/StatusController）
- 提交：bf84387

## 2026-10-25 19:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复说说的「待审核」不显示草稿（页面没加载草稿列表）；「发布历史」不再混入草稿，只显示已发布/失败记录
- 影响：后端（QzoneExpressionService）/ 前端（ExpressionView）
- 提交：0eb3add

## 2026-10-25 20:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：说说草稿目标为空时按聊天量加权从所有用户里选灵感来源；预览显示来源 QQ；发布时带上目标账号，避免“只给 TA”实际发成公开
- 影响：后端（QzoneExpressionService/ExpressionController）/ 前端（ExpressionView）
- 提交：3da0c55

## 2026-10-25 19:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增一次性私聊群发接口 /api/admin/broadcast（默认发给全部好友，逐条限速），用于故障道歉通知
- 影响：后端（BroadcastController）
- 提交：a6470c9

## 2026-10-25 19:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复语音记录挂错消息——只标记最后一条文字回复，不再把表情包记录改成语音；语音文件也挂到语音记录上
- 影响：后端（MessageStore）
- 提交：7e827c5

## 2026-10-25 20:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：wsproxy 缓存落盘并在 qqbot 断开超过 5 分钟时给主人 QQ 发告警；补充表情时段单元测试，测试总数 16 全部通过
- 影响：部署（wsproxy）/ 测试
- 提交：45e9cac + 本次

## 2026-10-25 20:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增用户消息 15 分钟无回复告警（每用户 2 小时最多一次）；数据库迁移增加 schema_migrations 版本表，不再靠吞异常判断
- 影响：后端（AlertService/Db）
- 提交：328f089

## 2026-10-25 20:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：补充回复拆分、时间感改写、说说受众判断测试；测试总数 19，全部通过
- 影响：测试
- 提交：2c8e891

## 2026-10-25 20:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：项目命名为「暖屿 / Warm Isle」，更新 README 标题与命名说明
- 影响：文档
- 提交：bf4cb43

## 2026-10-25 20:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：官网、个人主页、后台品牌统一为「暖屿」；机器人名字保持「小满」不变
- 影响：前端（site/home、site/xiaoman、web）/ 文档
- 提交：8809696

## 2026-10-25 20:35

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：开源准备——补 MIT LICENSE、隐私说明（docs + 站点页面）；新增用户数据删除：聊天发送「删除我的数据」两步确认，或管理接口 DELETE /api/privacy/users/{qqId}
- 影响：后端（UserDataService/PrivacyController/MessageDispatcher）/ 站点 / 文档
- 提交：7e87f31

## 2026-10-25 21:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：修复「QQ 已登录但页面显示离线」——下线会真正禁用账号，避免自动重连；新增每 30 秒连接自愈；刷新二维码会重新启用并启动连接；账号页每 5 秒自动刷新状态
- 影响：后端（AccountService/OneBotRegistry）/ 前端（AccountView）
- 提交：a1d84ef

## 2026-10-25 21:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：在线状态改为端到端探测——每 20 秒通过 WS 调 get_login_info，60 秒无成功响应标记离线，避免「页面在线但 QQ 掉线」
- 影响：后端（OneBotClient）
- 提交：e4883f0

## 2026-10-25 22:05

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：定时任务改为遍历 bot_accounts，逐账号在 BotContext 中执行——每日复盘、记忆审核、生活小事、一键放通、新好友引导、说说草稿、记忆抽取、每日学梗；单账号异常不影响其他账号
- 影响：后端（新增 BotAccountRunner，8 个调度器）
- 提交：e24f27d

## 2026-10-26 00:10

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：掉线看门狗改进——重启后先等快速登录（最多 120 秒），成功则不抓二维码不发信；10 分钟内已重启且二维码存在则不重复重启，避免二维码反复失效；达到告警上限后仍会重启但不发信
- 影响：部署（napcat-watchdog.py）
- 提交：95f9241

## 2026-10-27 20:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：域名上线——example.com 个人主页、web.example.com 官网、bot.example.com 后台；三站页脚悬挂备案号；新增 docs/UNFINISHED.md 交接未完成事项
- 影响：前端 / 部署 / 文档
- 分支：main
- 提交：6a8c768

## 2026-10-27 20:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：个人主页按钮改为跳转官网 web.example.com；修复三站跨域链接与旧路径 /home /xiaoman 重定向
- 影响：站点 / 部署
- 分支：main
- 提交：61e1dec

## 2026-10-27 21:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：官网静态资源路径从 /xiaoman/assets 改为 /assets，修复 web.example.com 图片 404
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 21:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：静态站资源改为相对路径，兼容域名根目录与 IP 的 /home /xiaoman 旧路径；修复 three.js 404
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 19:44

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增面向多平台发布的《从零搭一个会记事、会主动找你的 QQ AI 陪聊机器人》教程，含官网 web.example.com、Gitee/GitHub 地址、完整部署步骤、避坑与平台发布信息
- 影响：文档
- 分支：main
- 提交：459c286

## 2026-10-27 21:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：个人主页与官网补强 SEO——title/description/keywords、canonical、Open Graph、JSON-LD、robots.txt、sitemap.xml；隐私页 noindex
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 21:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：开源发布规则排除 site/（个人主页与官网源码不再进入开源仓）
- 影响：脚本 / 文档
- 分支：main
- 提交：待填

## 2026-10-27 22:00

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：开源仓移除 site/（27 个文件，个人主页与官网源码），Gitee warm_isle 已推送 ddb6ca`n- 影响：开源仓
- 分支：main
- 提交：待填

## 2026-10-27 22:15

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：新增「SaaS 为商业代码，不得开源」规则；脱敏脚本排除 saas/、provisioner、billing、ProvisionerClient 与 SaaS 文档
- 影响：规则 / 脚本
- 分支：main
- 提交：待填

## 2026-10-27 22:30

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：个人主页页脚改为居中三段式，备案号与版权/链接分层展示
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 22:40

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：个人主页与官网增加百度站点验证 meta
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 22:45

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：两个站点补充第二个百度站点验证码
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 20:04

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：开源脱敏脚本补齐生产域名、备案信息与 docs/UNFINISHED.md 的清理，并支持 MinGit（无 tar）先解压再脱敏；修复替换阶段未切到快照目录的问题
- 影响：部署 / 文档
- 分支：main
- 提交：034ed6e

## 2026-10-27 20:20

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：开源快照去掉后台备案页脚与生产域名 Caddy 配置，README 保留交流群，并把仓库名统一为 warm_isle
- 影响：开源仓 / 文档
- 分支：main
- 提交：3907bfd

## 2026-10-27 22:50

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：按百度后台归属拆分验证码——example.com 用 qrBCWavbcl，web.example.com 用 SCIEjKdHfM
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-27 22:55

- 设备：WIN-20260808MQO · Windows 10 专业版 · Administrator · DSH Agent
- 变更：官网百度验证码更新为 codeva-txWJRprA5i
- 影响：站点
- 分支：main
- 提交：待填

## 2026-10-28 12:03

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆系统 v2 P1——新增 memories / episodes 表；facts 幂等迁移进 memories（facts 只读保留）；新增 MemoryRecall 检索（关键词+二元组，score=相关度×0.5+重要度×0.3+新鲜度×0.2，top 8~12、≤1200 字，置信度 <0.6 不注入）；PromptBuilder 改为注入【关于对方】【你们之间】【最近发生】，仅在 memories 为空时回退旧事实卡；聊天抽取双写 memories；新增 /api/memories 管理接口与检索调试接口；隐私删除覆盖 memories/episodes
- 影响：后端 / 提示词
- 分支：main
- 提交：928971f

## 2026-10-28 12:06

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆系统 v2 P2——新增 NightlyMemoryService（00:05 与知识库同窗口）：昨天消息 → episode（episodes 表）→ 抽取候选记忆，可行动性=0.4×能否关心+0.25×情绪+0.2×时效+0.15×稳定性，≥0.7 写入并排 follow_up，0.4~0.7 只写入，<0.4 丢弃；冲突合并（同 subject+key 旧记录 archived+superseded_by）、过期归档、90 天衰减；可用内存 <500MB 跳过本轮；新增 FollowUpPlanner（加班/面试/生病/吵架/冷战/约定 → follow_up_at）；后台可手动补跑 /api/memories/nightly，可看 /api/memories/episodes
- 影响：后端 / 定时任务
- 分支：main
- 提交：f772464

## 2026-10-28 12:09

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆系统 v2 P3——新增 ProactiveRecallService 并接入 ProactiveService 的每分钟 tick：到点的 event/relation 记忆生成一条关心，独立额度（每天 2 条、关系类 1 条，不占早安/晚安），同一事件只发一次，2 小时内说过话不发，免打扰时段不发，用户当天提过该话题则 skipped，文案禁止出现「记忆/记录」（含 sanitize 兜底）；用户说「别问了」→ 全部待主动回忆置 skipped
- 影响：后端 / 主动消息
- 分支：main
- 提交：c0f8d5a

## 2026-10-28 12:25

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：文档同步记忆 v2——STATUS 补 memories/episodes 数据布局与 00:05 夜间整理任务，HANDOFF 补 P1–P3 已完成项与 P4 待办，memory-design 状态改为已实现 P1–P3
- 影响：文档
- 分支：main
- 提交：6ed4163
- 备注：本机（Mac）SSH 到生产 203.0.113.10 被拒（主机密钥已变、原 `deploy-key` 公钥未在 authorized_keys 中），部署与线上验证待拿到访问权限后补做

## 2026-10-28 13:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆 v2 上线并验证——服务器备份 `data/qqbot/backup/bots-20261028-1322` 后重建 qqbot；启动迁移 23 个用户写入 58 条记忆；手动试跑夜间整理（14 用户 / 27 条写入 / 4 条丢弃 / 14 个 episode）；P3 补充可注入文案与「记忆/记录」兜底，新增 25 个记忆相关单测（检索打分、分组、top-k/字数、迁移幂等、主动回忆门槛与状态流转）
- 影响：后端 / 部署
- 分支：main
- 提交：2d502fe
- 备注：本机到服务器的原 SSH 公钥已失效（主机密钥变更），本次用初始凭据登录完成部署；临时验证会话已从 global.db 删除

## 2026-10-28 13:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：HANDOFF 补本机构建注意事项（npm/maven 缓存目录改写、build-jar.sh 必须、SSH 公钥失效说明）
- 影响：文档
- 分支：main
- 提交：待填

## 2026-10-28 14:55

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：记忆 v2 交接文档全面同步——HANDOFF 新增「3.1 记忆 v2 交接」（已完成/线上数据/未完成/验收命令/回滚），STATUS 补 5 条已知限制，memory-design 新增 §11 实现状态清单（含与设计稿的有意偏离、上线观察），UNFINISHED 新增 §2.3 记忆 v2 剩余项；同时记录自动部署 timer 实测 inactive、服务器 SSH 公钥失效
- 影响：文档
- 分支：main
- 提交：61c063c

## 2026-10-28 15:05

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：把工作区公钥 `deploy-key` 重新装回服务器 `user` 的 authorized_keys（主机重装后丢失），免密登录与免密 sudo 恢复；HANDOFF 环境事实同步登录方式。密码未写入任何文件，建议轮换
- 影响：部署 / 文档
- 分支：main
- 提交：4a4da38

## 2026-10-28 15:20

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：同步所有者已把主动消息免打扰改为 `00:30–07:00` 的事实——HANDOFF/STATUS/UNFINISHED 去掉「全天静默」结论，改为记录解除静默后积压跟进会立刻补发（14:59 给 6 个用户各发 1 条，属既有 followup 逻辑）；主动回忆真实链路验收仍待真实高情绪事件
- 影响：文档
- 分支：main
- 提交：待填

## 2026-10-28 15:12

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修「后台聊天记录不刷新」——根因不是 SSE（`event:ready` 正常），而是表情包快速通道 `MemeService.tryHandle()` 只发图不落库：用户「我想要一张奶龙的表情包」和机器人回的图片都没写进 messages，后台自然看不到。现在该通道会记录用户消息 + 助手「（我发了一张表情包：xxx）」标记并触发 SSE；顺带修主动消息每分钟重复 `进入退避`、把 `next_eligible` 反复往后推（退避中的账号永远等不到复联）的问题
- 影响：后端 / 后台
- 分支：main
- 提交：03a2ce7

## 2026-10-28 15:32

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修复读守卫——上一条是语音时，历史里带「（语音）」前缀会把二元组相似度稀释到 0.8 以下，导致同一句被文字重发一遍（线上 100000000 的 1759/1761）。现在先剥掉「（语音）」「（我发了一张表情包：…）」等系统标记再做相似度与包含判断，并补 5 个回归测试
- 影响：后端 / 聊天
- 分支：main
- 提交：65d5cc5

## 2026-10-28 15:36

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：提示词新增「别聊自己的能力」——不讨论能否发语音/看图，语音被限流时不解释原因直接改文字，没内容可看时不许说「你发的什么呀/我这边没瞧见」。原因是 100000000 的聊天里出现「一边发语音一边说语音发不出来」（10-27 的 1301/1303/1305）和「对文字问句回没瞧见」（1906），同属提示词没写能力边界；已同步 docs/prompt-changelog.md
- 影响：提示词 / 聊天
- 分支：main
- 提交：7267ab0

## 2026-10-28 15:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：按所有者要求把「有能力就要用」落到实处——①提示词改为正向声明：会发语音条/表情包、看得懂对方发的图片和表情包，对方要语音就照常回答，禁止说「我发不出来/这个是文字聊天/你发的什么呀/我这边没瞧见」；②对方明确要语音时单独给额度（每天至少 6 条，不受日常 3 条与 90 分钟间隔限制）；③图片额度/预算用尽时注入的提示改为「按气氛接一句，不要解释原因」，不再把「额度已用完」写进对话；④application.yml 暴露 VOICE_CHAT_PROBABILITY / VOICE_DAILY_LIMIT 便于调；⑤补 PromptRulesTest 回归
- 影响：提示词 / 聊天 / 语音
- 分支：main
- 提交：f71f742

## 2026-10-28 16:00

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：语音改成「模型意图 + 概率」——新增 [[语音]] 标记（模型想发语音时写在末尾，系统剥掉并掷骰），概率表：对方点名 0.9 / 模型标记 0.7 / 日常 0.2（夜间等加成）；新增 ReplyMarkers 统一解析 [[表情:…]] 与 [[语音]]，标记不进正文也不进历史；修 ChatVoiceReplier.dailyCount 读错 key（voice.count 少了 :QQ，导致每日语音上限从未生效）；application.yml 暴露 VOICE_EXPLICIT_PROBABILITY / VOICE_MARK_PROBABILITY；补 ReplyMarkersTest 等 11 个测试
- 影响：提示词 / 聊天 / 语音
- 分支：main
- 提交：6e09846

## 2026-10-28 16:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：修与 100000000 的聊天问题——①根因：history 把「（我发了一张表情包：…）」当普通对话喂回去，模型照格式写、把括号当台词发给用户（id 1945/1955，kind=text）；现在 isMediaMarker 识别该类记录，ReplyMarkers 兜底把伪标记还原成正文；②表情包实体清洗，`我想要一张奶龙的表情包[表情]` 不再记成「我想奶龙表情」；③表情包记录去掉重复的「这是一张表情包：」前缀；④「不顶撞」补：被说阴阳/敷衍时不许反问「谁…了」
- 影响：提示词 / 聊天 / 表情包
- 分支：main
- 提交：2743be6

## 2026-10-28 16:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：按所有者要求改表情包命中后的说话方式——不再解释奶龙是什么梗（原来会在图后面补一句「（梗解释）」），改成「找到啦，给你！/ 给你找了张奶龙，喜欢吗？」这类一句人话；没找到图时回「这个我还没找到图，等我翻翻再给你」，同样不解释；历史里补记用户实际看到的那句话
- 影响：后端 / 聊天 / 表情包
- 分支：main
- 提交：01e0add

## 2026-10-28 17:10

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：①固定规则重写：2303→1206 字符（-48%），6 块结构，清掉开头三行的单引号垃圾、删掉已失效的媒体括号提醒、合并看懂表情包/别自贬/别赶人走，新增「不复述不总结、不换皮重说、被说阴阳先认一句、记忆不念日期不编细节」；②按所有者「宁可多问一句」，主动回忆阈值 0.7→0.65（`ACTIONABLE_FOLLOW_UP`）；③同步 memory-design / HANDOFF / STATUS / UNFINISHED 里的阈值说法；④PromptRulesTest 增加体积上限（<1600 字符）防再膨胀
- 影响：提示词 / 记忆 / 文档
- 分支：main
- 提交：929c808

## 2026-10-28 17:35

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：文档收尾并准备开源快照——HANDOFF 新增 §3.2「聊天体验修复」（8 个问题的根因/修复/提交），STATUS 补语音概率策略；sanitize-oss.sh 增加排除官方版内部计划文档（官方版独立项目的规划，含真实域名与仓库安排）
- 影响：文档 / 开源同步
- 分支：main
- 提交：待填

## 2026-10-28 17:45

- 设备：AnRandeMacBook-Pro.local · macOS 15.1.1 · anran · DSH Agent
- 变更：脱敏脚本加固——新增 SSH 用户名 `user@/user@` → `user@`、密钥别名 `deploy-key` → `deploy-key`、登录方式措辞替换；官方版计划文档改用 `docs/official-*.md` 通配排除。重新生成快照并推送 Gitee 开源仓（单提交快照，force push）
- 影响：开源同步 / 脚本
- 分支：main
- 提交：待填
