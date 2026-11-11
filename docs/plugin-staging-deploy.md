# 插件能力 · staging 部署与测试手册

> 目标：在**线上同一台机器**跑一份带插件能力的暖屿，用来真实聊天测试；
> **生产容器一个都不碰**，生产数据一条都不动。

## 1. 隔离是怎么做的

| 维度 | 生产 | staging |
|---|---|---|
| compose project | `ai-companion` | `ai-companion-staging` |
| 目录 | `/srv/qqbot/deploy/linux` | `/opt/qqbot-staging` |
| 数据 | `data/qqbot`（real) | `data-staging/qqbot`（独立库） |
| 后台端口 | 127.0.0.1:8080 | **127.0.0.1:8082** |
| OneBot WS | 127.0.0.1:3001 | **127.0.0.1:3101** |
| NapCat WebUI | 127.0.0.1:6099 | **127.0.0.1:6199** |
| QQ 账号 | 小满 | **测试号（单独扫码）** |
| docker.sock | 已挂 | **不挂** |
| 插件开关 | `PLUGIN_ENABLED` 未设（=false） | **true** |
| 图片预筛 | guard 容器 | 复用 guard（只读调用） |

关键：staging 只通过外部网络 `ai-companion_default` 借 guard，不加入生产的生命周期。
`docker compose -f docker-compose.staging.yml down` 只会删 staging 自己的三个容器。

## 2. 部署（两条命令）

本地（仓库根目录）：

```bash
./deploy/staging/build-bundle.sh            # 构建前端 + jar，产出 dist-staging/
./deploy/staging/deploy-remote.sh user@<服务器>
```

服务器侧会做：建 `/opt/qqbot-staging` → 上传 jar/Dockerfile/compose/packs → `docker compose up -d --build`。
不会 `docker compose down` 任何生产服务，不会重启 qqbot/napcat/wsproxy/guard/caddy。

## 3. 第一次登录测试号

1. 隧道：`ssh -L 8082:127.0.0.1:8082 -L 6199:127.0.0.1:6199 user@<服务器>`
2. 打开 `http://127.0.0.1:6199`（NapCat WebUI）扫码登录**测试 QQ 号**
3. 在 NapCat 里把 OneBot v11 反向/正向 WS 指向 `ws://wsproxy-staging:3002`
   （容器内地址，不要填宿主 127.0.0.1）
4. 打开 `http://127.0.0.1:8082` → 「机器人账号」确认在线
5. 「能力插件」→ 安装本地 zip → 选 `menran.sing-1.0.0.zip` → 预览确认 → 打开测试号开关

## 4. 测试清单（对应设计文档 §11.4）

```
1. 说"唱首歌"            → 只收到语音条，无多余文字
2. 说"唱首小星星"        → 收到的就是小星星
3. 说"唱首不存在的歌"    → "这首我这儿没有，换一首？"，不写记忆
4. 说"今天好累啊"        → 正常文字回复，不触发插件
5. 说"你会唱歌吗"        → 自然回答会，不直接开唱
6. 隔天说"上次那首再来一遍" → 能接上（recentDeeds 回填）
7. 连点 4 次唱歌          → 第 4 次提示"今天唱得有点多啦"（与日常语音共用 3 条额度）
8. 后台卸载插件          → 记忆 source LIKE 'plugin:menran.sing%' 全部归档
9. 关掉插件开关          → 行为与线上完全一致
```

## 5. 内存与回滚

- staging 限额：napcat 800m + wsproxy 64m + qqbot 640m ≈ 1.5G **上限**（不是占用）。
  宿主机 3.6G，生产 compose 的 mem_limit 合计已接近上限。**上线前先看 `free -m`**，
  可用内存 < 900MB 时不要起 staging，或把 napcat-staging 的 mem_limit 降到 600m。
- 回滚：`cd /opt/qqbot-staging && docker compose down`，生产完全不受影响。
- staging 数据随时可删：`rm -rf /opt/qqbot-staging/data-staging`（只影响测试号记忆）。

## 6. 注意

- 服务器配了 `qqbot-autodeploy.timer`（每 5 分钟拉 Gitee 重建**生产**）。
  **不要把本分支推到 Gitee 的 main**，否则生产会自动升级成插件版。
- 插件的音频是公版旋律合成音（小星星/两只老虎/生日快乐），不是真人音色；
  `voice.strategy=lyrics` + 宿主 TTS 在 M2 提供。
- 生产侧唯一可见变化：`qqbot.jar` 里多了插件代码，但 `PLUGIN_ENABLED` 未设置时全部空实现。
