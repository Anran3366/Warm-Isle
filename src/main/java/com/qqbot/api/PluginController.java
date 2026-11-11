package com.qqbot.api;

import com.qqbot.memory.Db;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.exec.PromptExecutor;
import com.qqbot.plugin.install.PluginInstaller;
import com.qqbot.plugin.local.LocalEndpointTester;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.PluginRegistry;
import com.qqbot.plugin.remote.HostedExecutor;
import com.qqbot.plugin.store.PluginStoreClient;
import com.qqbot.plugin.store.StoreIndex;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 插件后台：本地 zip 安装、启停、卸载、运行记录（商店 Tab 在 M2 接） */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginRegistry registry;
    private final PluginInstaller installer;
    private final PluginProperties props;
    private final com.qqbot.plugin.registry.CapabilityPrompt capabilityPrompt;
    private final com.qqbot.plugin.local.LocalEndpointTester endpointTester;
    private final DataExecutor dataExecutor;
    private final PromptExecutor promptExecutor;
    private final HostedExecutor hostedExecutor;
    private final PluginStoreClient storeClient;
    private final com.qqbot.plugin.PluginHealthMonitor healthMonitor;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final Db db;

    public PluginController(PluginRegistry registry, PluginInstaller installer,
                            PluginProperties props,
                            com.qqbot.plugin.registry.CapabilityPrompt capabilityPrompt,
                            com.qqbot.plugin.local.LocalEndpointTester endpointTester,
                            DataExecutor dataExecutor, PromptExecutor promptExecutor,
                            HostedExecutor hostedExecutor, PluginStoreClient storeClient,
                            com.qqbot.plugin.PluginHealthMonitor health,
                            com.fasterxml.jackson.databind.ObjectMapper mapper, Db db) {
        this.registry = registry;
        this.installer = installer;
        this.props = props;
        this.capabilityPrompt = capabilityPrompt;
        this.endpointTester = endpointTester;
        this.dataExecutor = dataExecutor;
        this.promptExecutor = promptExecutor;
        this.hostedExecutor = hostedExecutor;
        this.storeClient = storeClient;
        this.healthMonitor = health;
        this.mapper = mapper;
        this.db = db;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pluginEnabled", props.isEnabled());
        List<Long> botIds = props.isEnabled() ? registry.allBotIds() : List.of();
        out.put("bots", botIds);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, com.qqbot.plugin.PluginHealthMonitor.Status> healthStatuses = healthMonitor.statuses();
        for (InstalledPlugin p : registry.installed()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.pluginId());
            m.put("name", p.manifest() == null ? p.pluginId() : p.manifest().name());
            m.put("version", p.version());
            m.put("runtime", p.runtime());
            m.put("source", p.source());
            m.put("status", p.status());
            m.put("description", p.manifest() == null ? "" : p.manifest().description());
            m.put("endpoint", p.endpoint());
            com.qqbot.plugin.PluginHealthMonitor.Status hs = healthStatuses.get(p.pluginId());
            if (hs != null) {
                m.put("remoteVersion", hs.remoteVersion());
                m.put("remoteStatus", hs.status());
                m.put("remoteCheckedAt", hs.checkedAt());
            }
            // 吊销名单（§6.8）：签名上线前只做提示，不阻断触发
            StoreIndex.Revocation rev = storeClient.revocationOf(p.pluginId(), p.version());
            if (rev != null) {
                m.put("revoked", true);
                m.put("revokedReason", rev.reason());
            }
            List<Long> enabledFor = new ArrayList<>();
            for (Long botId : botIds) {
                if (registry.enabled(botId, p.pluginId())) {
                    enabledFor.add(botId);
                }
            }
            m.put("enabledFor", enabledFor);
            if (p.manifest() != null && p.manifest().intent() != null) {
                m.put("intent", p.manifest().intent().id());
            }
            items.add(m);
        }
        out.put("installed", items);
        return out;
    }

    /** 手动触发一轮 hosted/local 服务版本巡检（后台「检查服务版本」） */
    @PostMapping("/health-check")
    public Map<String, Object> healthCheck() {
        Map<String, com.qqbot.plugin.PluginHealthMonitor.Status> result = healthMonitor.checkNow();
        return Map.of("ok", true, "checked", result.size());
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> inspect(@RequestParam("file") MultipartFile file) throws Exception {
        PluginInstaller.InspectResult r = installer.inspect(file.getBytes());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("issues", r.issues());
        if (r.manifest() != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.manifest().id());
            m.put("name", r.manifest().name());
            m.put("version", r.manifest().version());
            m.put("runtime", r.manifest().runtime());
            m.put("description", r.manifest().description());
            m.put("intent", r.manifest().intent() == null ? null : r.manifest().intent().id());
            m.put("outbound", r.manifest().outboundHosts());
            m.put("memoryWrite", r.manifest().memoryPolicy());
            m.put("returnsAudio", r.manifest().returnsAudio());
            out.put("manifest", m);
        }
        return out;
    }

    @PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> install(@RequestParam("file") MultipartFile file) throws Exception {
        if (!props.isEnabled()) {
            return Map.of("ok", false, "message", "插件功能未启用（qqbot.plugin.enabled=false）");
        }
        PluginInstaller.InstallResult r = installer.install(file.getBytes(), "upload");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("pluginId", r.pluginId());
        out.put("version", r.version());
        out.put("issues", r.issues());
        return out;
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id, @RequestParam long botId,
                                      @RequestParam(required = false) String ack) {
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            return Map.of("ok", false, "message", "插件未安装");
        }
        if (p.manifest() == null) {
            return Map.of("ok", false, "message", "插件 manifest 缺失（可能已被标记为 broken）");
        }
        // 隐私模式：hosted / local 都会把数据带出本机，直接不允许启用（文档 §7.7）
        if (props.isPrivacyMode()
                && com.qqbot.plugin.permission.PermissionCatalog.blockedInPrivacyMode(p.manifest())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("privacyBlocked", true);
            out.put("message", "已开启隐私模式：联网型和本机服务型插件不会启用");
            return out;
        }

        // 独占意图（设计文档 §8.3）：同一意图只允许一个启用，避免两个提醒/游戏插件同时写状态
        String conflict = exclusiveConflict(botId, p);
        if (conflict != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("conflict", true);
            out.put("message", conflict);
            return out;
        }

        List<com.qqbot.plugin.permission.PermissionCatalog.Item> perms =
                com.qqbot.plugin.permission.PermissionCatalog.describe(p.manifest());
        String digest = com.qqbot.plugin.permission.PermissionCatalog.digest(p.manifest());
        PluginRegistry.AckState st = registry.stateOf(botId, id);

        if (!digest.equals(st.manifestHash())) {
            // 没确认过，或升级/回滚后权限变了：先回清单，等前端带 ack 再来一次
            if (ack == null || ack.isBlank()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("needConfirm", true);
                out.put("digest", digest);
                out.put("permissions", perms);
                out.put("message", perms.isEmpty()
                        ? "确认后启用" : "这个插件要用到下面这些能力，确认后才会启用");
                return out;
            }
            if (!ack.equals(digest)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("needConfirm", true);
                out.put("digest", digest);
                out.put("permissions", perms);
                out.put("message", "插件权限已变化，请重新确认");
                return out;
            }
            registry.setEnabled(botId, id, true, toJson(perms), digest);
        } else {
            registry.setEnabled(botId, id, true, null, digest);
        }
        capabilityPrompt.markEnabled(botId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("permissions", perms);
        out.put("digest", digest);
        return out;
    }

    private String exclusiveConflict(long botId, InstalledPlugin candidate) {
        if (candidate.manifest() == null || candidate.manifest().intent() == null
                || !Boolean.TRUE.equals(candidate.manifest().intent().exclusive())) {
            return null;
        }
        String intentId = candidate.manifest().intent().id();
        if (intentId == null || intentId.isBlank()) {
            return null;
        }
        for (InstalledPlugin other : registry.activeFor(botId)) {
            if (other.pluginId().equals(candidate.pluginId())
                    || other.manifest() == null || other.manifest().intent() == null) {
                continue;
            }
            if (intentId.equals(other.manifest().intent().id())) {
                return "意图「" + intentId + "」已被「" + other.manifest().name()
                        + "」占用；独占意图同一账号只能启用一个";
            }
        }
        return null;
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id, @RequestParam long botId) {
        registry.setEnabled(botId, id, false, null, null);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> uninstall(@PathVariable String id) {
        installer.uninstall(id);
        return Map.of("ok", true);
    }

    /**
     * 试运行：走真实的执行器，但<b>不发给任何用户、不写记忆、不计配额</b>。
     *
     * <p>用来回答两个问题：插件到底能不能调通、作者返回的东西长什么样。
     * 装完插件不敢直接上线时先点这个。
     */
    @PostMapping("/{id}/try")
    public Map<String, Object> tryRun(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            return Map.of("ok", false, "message", "插件未安装");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String action = body == null ? null : String.valueOf(body.get("action"));
        if (action == null || action.isBlank() || "null".equals(action)) {
            com.qqbot.plugin.model.PluginManifest.Action primary = p.manifest().primaryAction();
            action = primary == null ? null : primary.name();
        }
        if (action == null) {
            return Map.of("ok", false, "message", "插件没有声明 action");
        }
        Map<String, String> params = new LinkedHashMap<>();
        Object raw = body == null ? null : body.get("params");
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                params.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            PluginOutput output;
            if (p.manifest().usesData()) {
                output = dataExecutor.execute(p, action, params);
            } else if (p.manifest().usesPrompt()) {
                // 试运行没有真实用户，profile 变量落「还不太了解」
                output = promptExecutor.execute(p, action, params, 0);
            } else {
                output = hostedExecutor.execute(p, action, params, null);
            }
            out.put("ok", true);
            out.put("action", action);
            out.put("kind", output.kind());
            out.put("caption", output.caption());
            out.put("sizeBytes", output.bytes() == null ? 0 : output.bytes().length);
            out.put("durationMs", output.durationMs());
            out.put("memoryVars", output.memoryVars());
            if (output.text() != null && !output.text().isBlank()) {
                String text = output.text();
                out.put("text", text.length() > 300 ? text.substring(0, 300) + "…" : text);
            }
            out.put("latencyMs", System.currentTimeMillis() - t0);
            return out;
        } catch (HostedExecutor.PendingTask pending) {
            out.put("ok", true);
            out.put("pending", true);
            out.put("taskId", pending.taskId());
            out.put("ackText", pending.ackText());
            out.put("latencyMs", System.currentTimeMillis() - t0);
            return out;
        } catch (DataExecutor.PluginException e) {
            out.put("ok", false);
            out.put("code", e.code());
            out.put("message", e.getMessage());
            out.put("userHint", e.userHint());
            out.put("latencyMs", System.currentTimeMillis() - t0);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("code", "INTERNAL");
            out.put("message", e.getMessage());
            out.put("latencyMs", System.currentTimeMillis() - t0);
            return out;
        }
    }

    /** 只测试连通性，不写库（先测再存，避免把错地址存进去） */
    @PostMapping("/{id}/endpoint/test")
    public Map<String, Object> testEndpoint(@PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            return Map.of("ok", false, "message", "插件未安装");
        }
        String url = body.getOrDefault("url", "").trim();
        LocalEndpointTester.Result r = endpointTester.test(url, healthPath(p));
        return Map.of(
                "ok", r.ok(),
                "status", r.status(),
                "latencyMs", r.latencyMs(),
                "message", r.message());
    }

    @PostMapping("/{id}/endpoint")
    public Map<String, Object> endpoint(@PathVariable String id, @RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Map.of("ok", false, "message", "地址需以 http:// 或 https:// 开头");
        }
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            return Map.of("ok", false, "message", "插件未安装");
        }
        // 文档 §7.6：保存前必须通过 /health 测试
        LocalEndpointTester.Result r = endpointTester.test(url, healthPath(p));
        if (!r.ok()) {
            return Map.of("ok", false, "message", "连接测试未通过：" + r.message(),
                    "status", r.status(), "latencyMs", r.latencyMs());
        }
        registry.setEndpoint(id, url);
        return Map.of("ok", true, "latencyMs", r.latencyMs(), "message", "已保存，连接正常");
    }

    private String healthPath(InstalledPlugin p) {
        if (p.manifest() != null && p.manifest().local() != null) {
            String h = p.manifest().local().health();
            if (h != null && !h.isBlank()) {
                return h;
            }
        }
        return "/health";
    }

    /**
     * 版本列表：当前版、上一版、以及磁盘上还留着的其它版本。
     * 前端据此决定能不能点「回滚」。
     */
    @GetMapping("/{id}/versions")
    public Map<String, Object> versions(@PathVariable String id) {
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            return Map.of("ok", false, "message", "插件未安装");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("current", p.version());
        out.put("keepDays", props.getKeepOldVersionsDays());
        out.put("versions", installer.versions(id));
        return out;
    }

    /** 回滚到上一版：只切版本，不动记忆（文档 §7.4） */
    @PostMapping("/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id) {
        PluginInstaller.RollbackResult r = installer.rollback(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("from", r.from());
        out.put("to", r.to());
        out.put("message", r.message());
        out.put("pausedBots", r.pausedBots());
        if (r.ok() && r.pausedBots() > 0) {
            out.put("hint", "回滚后权限有变化，已暂停 " + r.pausedBots() + " 个账号，需要在详情里重新确认并启用");
        }
        return out;
    }

    /** 手动清理旧版本目录（安装/回滚时也会自动清理一次） */
    @PostMapping("/{id}/prune")
    public Map<String, Object> prune(@PathVariable String id) {
        int removed = installer.pruneOldVersions(id);
        return Map.of("ok", true, "removed", removed, "keepDays", props.getKeepOldVersionsDays());
    }

    /**
     * 插件详情：把包内 manifest 原样吐给前端，由后台渲染成「这个插件要什么、连哪里、能做什么」。
     * 只读，不改任何状态。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        InstalledPlugin p = registry.find(id);
        if (p == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "插件未安装: " + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.pluginId());
        out.put("installId", p.installId());
        out.put("version", p.version());
        out.put("runtime", p.runtime());
        out.put("source", p.source());
        out.put("sha256", p.sha256());
        out.put("status", p.status());
        out.put("endpoint", p.endpoint());
        out.put("dir", p.dir() == null ? null : p.dir().toString());
        out.put("manifest", p.manifest());
        // 每个账号是否启用（后台按账号开关，详情里要能看出「给谁开了」）
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        if (props.isEnabled()) {
            for (Long botId : registry.allBotIds()) {
                enabled.put(String.valueOf(botId), registry.enabled(botId, id));
            }
        }
        out.put("enabledFor", enabled);

        StoreIndex.Revocation rev = storeClient.revocationOf(p.pluginId(), p.version());
        if (rev != null) {
            out.put("revoked", true);
            out.put("revokedReason", rev.reason());
            out.put("revokedAt", rev.createdAt());
        }

        // 权限说明（§7.7）：作者声明了什么、用户确认过没有
        List<com.qqbot.plugin.permission.PermissionCatalog.Item> perms =
                p.manifest() == null ? List.of()
                        : com.qqbot.plugin.permission.PermissionCatalog.describe(p.manifest());
        String digest = p.manifest() == null ? "none"
                : com.qqbot.plugin.permission.PermissionCatalog.digest(p.manifest());
        out.put("permissions", perms);
        out.put("permissionDigest", digest);
        out.put("privacyMode", props.isPrivacyMode());

        Map<String, Object> ackState = new LinkedHashMap<>();
        if (props.isEnabled()) {
            for (Long botId : registry.allBotIds()) {
                PluginRegistry.AckState st = registry.stateOf(botId, id);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("enabled", st.enabled());
                a.put("confirmed", digest.equals(st.manifestHash()));
                a.put("confirmedAt", st.updatedAt());
                ackState.put(String.valueOf(botId), a);
            }
        }
        out.put("ackState", ackState);
        return out;
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT plugin_id, plugin_version, status, error_code, latency_ms, created_at "
                + "FROM plugin_runs ORDER BY id DESC LIMIT ?";
        for (Long botId : registry.allBotIds()) {
            try (Connection c = db.open(botId); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, Math.min(200, Math.max(1, limit)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("botId", botId);
                        m.put("pluginId", rs.getString(1));
                        m.put("version", rs.getString(2));
                        m.put("status", rs.getString(3));
                        m.put("errorCode", rs.getString(4));
                        m.put("latencyMs", rs.getInt(5));
                        m.put("createdAt", rs.getString(6));
                        out.add(m);
                    }
                }
            } catch (Exception ignored) {
                // 某个账号库还没有 plugin_runs 就跳过
            }
        }
        return out;
    }

    private String manifestHash(String id) {
        InstalledPlugin p = registry.find(id);
        return p == null ? null : p.sha256();
    }

    /** 权限快照存成 JSON，方便以后换展示文案时还能对上当时的清单 */
    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
