package com.qqbot.plugin.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.Db;
import com.qqbot.memory.GlobalDb;
import com.qqbot.memory.SharedDb;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.model.PluginManifest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已装插件注册表。
 *
 * <p>安装记录在 shared.db（实例级），启用状态在每个 bot 库（账号级）；
 * 目录结构 {@code <plugin.dir>/<id>/<version>/}。
 * 所有读路径都不发网络请求，商店挂了不影响这里。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SharedDb shared;
    private final Db db;
    private final GlobalDb globalDb;
    private final PluginProperties props;
    private final ObjectMapper mapper;

    /** pluginId → 记录（全实例） */
    private volatile Map<String, InstalledPlugin> installed = Map.of();
    /** botId → (pluginId → enabled) */
    private final Map<Long, Map<String, Boolean>> enabledCache = new ConcurrentHashMap<>();
    private volatile IntentIndex intentIndex = IntentIndex.empty();

    public PluginRegistry(SharedDb shared, Db db, GlobalDb globalDb,
                          PluginProperties props, ObjectMapper mapper) {
        this.shared = shared;
        this.db = db;
        this.globalDb = globalDb;
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("插件功能未启用（qqbot.plugin.enabled=false），注册表空转");
            return;
        }
        reload();
    }

    public synchronized void reload() {
        Map<String, InstalledPlugin> loaded = new LinkedHashMap<>();
        Path root = pluginsRoot();
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT install_id, plugin_id, version, runtime, source, sha256, manifest, "
                             + "endpoint, status FROM installed_plugins ORDER BY plugin_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("plugin_id");
                String version = rs.getString("version");
                Path dir = root.resolve(id).resolve(version);
                PluginManifest manifest = null;
                try {
                    manifest = mapper.readValue(rs.getString("manifest"), PluginManifest.class);
                } catch (Exception e) {
                    log.warn("插件 {} 的 manifest 解析失败: {}", id, e.getMessage());
                }
                String status = rs.getString("status");
                if (!Files.isDirectory(dir)) {
                    status = "broken";
                    log.warn("插件 {} v{} 目录不存在: {}（标记 broken）", id, version, dir);
                }
                loaded.put(id, new InstalledPlugin(rs.getString("install_id"), id, version,
                        rs.getString("runtime"), rs.getString("source"), rs.getString("sha256"),
                        status, rs.getString("endpoint"), manifest, dir));
            }
        } catch (Exception e) {
            log.error("读取已装插件失败: {}", e.getMessage());
        }
        this.installed = Map.copyOf(loaded);
        this.enabledCache.clear();
        this.intentIndex = IntentIndex.build(new ArrayList<>(this.installed.values()));
        migrateLegacyAckHashes();
        log.info("插件注册表已加载: {} 个，意图索引 {} 条", this.installed.size(), this.intentIndex.size());
    }

    /**
     * 一次性迁移：早期版本把「整包 sha256」（64 位 hex）写进 {@code manifest_hash}，
     * 后来改成「权限摘要」（16 位）。旧值永远不可能匹配，会让所有老插件显示成
     * 「已启用但权限待确认」——这种矛盾状态没有意义，启动时按当前权限重算一次。
     *
     * <p>只改 manifest_hash，不动 enabled：用户当初点过启用，就是确认过当时的能力范围。
     */
    private void migrateLegacyAckHashes() {
        for (Long botId : allBotIds()) {
            for (InstalledPlugin p : installed.values()) {
                if (p.manifest() == null) {
                    continue;
                }
                try {
                    AckState st = stateOf(botId, p.pluginId());
                    if (st.manifestHash() != null && st.manifestHash().length() == 64) {
                        updateAckHash(botId, p.pluginId(),
                                com.qqbot.plugin.permission.PermissionCatalog.digest(p.manifest()));
                        log.info("迁移账号 {} 的插件 {} 权限摘要（旧格式 sha256）", botId, p.pluginId());
                    }
                } catch (Exception e) {
                    log.warn("迁移账号 {} 的插件 {} 权限摘要失败: {}", botId, p.pluginId(), e.getMessage());
                }
            }
        }
    }

    /** 只更新权限摘要，不动开关（迁移用） */
    public void updateAckHash(long botId, String pluginId, String digest) {
        try (Connection c = db.open(botId);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_state SET manifest_hash=? WHERE plugin_id=?")) {
            ps.setString(1, digest);
            ps.setString(2, pluginId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新权限摘要失败: " + e.getMessage(), e);
        }
    }

    public Path pluginsRoot() {
        return Path.of(props.getDir()).toAbsolutePath().normalize();
    }

    public List<InstalledPlugin> installed() {
        return List.copyOf(installed.values());
    }

    public InstalledPlugin find(String pluginId) {
        return installed.get(pluginId);
    }

    public IntentIndex intentIndex() {
        return intentIndex;
    }

    /** 该账号是否启用了某插件（只看本 bot 库，带内存缓存） */
    public boolean enabled(long botId, String pluginId) {
        return enabledMap(botId).getOrDefault(pluginId, false);
    }

    public List<InstalledPlugin> activeFor(long botId) {
        Map<String, Boolean> map = enabledMap(botId);
        List<InstalledPlugin> list = new ArrayList<>();
        for (InstalledPlugin p : installed.values()) {
            if (p.usable() && Boolean.TRUE.equals(map.get(p.pluginId()))) {
                list.add(p);
            }
        }
        return list;
    }

    private Map<String, Boolean> enabledMap(long botId) {
        return enabledCache.computeIfAbsent(botId, id -> {
            Map<String, Boolean> map = new LinkedHashMap<>();
            try (Connection c = db.open(id);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT plugin_id, enabled FROM plugin_state")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getInt(2) == 1);
                    }
                }
            } catch (Exception e) {
                log.warn("读取账号 {} 的插件启用状态失败: {}", id, e.getMessage());
            }
            return map;
        });
    }

    /**
     * 某个账号上这个插件的开关与权限确认状态。
     *
     * @param permissionsJson 用户确认过的权限快照
     * @param manifestHash    确认时的权限摘要；版本升级/回滚后与新摘要比对
     */
    public record AckState(boolean enabled, String permissionsJson, String manifestHash, String updatedAt) {
    }

    /** 读单个插件的账号级状态；没有记录时返回「未启用、未确认」 */
    public AckState stateOf(long botId, String pluginId) {
        try (Connection c = db.open(botId);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enabled, permissions, manifest_hash, updated_at FROM plugin_state WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new AckState(rs.getInt(1) == 1, rs.getString(2), rs.getString(3), rs.getString(4));
                }
            }
        } catch (Exception e) {
            log.warn("读取账号 {} 的插件 {} 状态失败: {}", botId, pluginId, e.getMessage());
        }
        return new AckState(false, null, null, null);
    }

    public void setEnabled(long botId, String pluginId, boolean enabled, String permissionsJson,
                           String manifestHash) {
        String sql = "INSERT INTO plugin_state(plugin_id, enabled, permissions, manifest_hash, updated_at) "
                + "VALUES(?,?,?,?,?) ON CONFLICT(plugin_id) DO UPDATE SET "
                + "enabled=excluded.enabled, "
                + "permissions=COALESCE(excluded.permissions, plugin_state.permissions), "
                + "manifest_hash=COALESCE(excluded.manifest_hash, plugin_state.manifest_hash), "
                + "updated_at=excluded.updated_at";
        try (Connection c = db.open(botId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, pluginId);
            ps.setInt(2, enabled ? 1 : 0);
            ps.setString(3, permissionsJson);
            ps.setString(4, manifestHash);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入插件启用状态失败: " + e.getMessage(), e);
        }
        enabledCache.remove(botId);
    }

    /** 所有账号 id（卸载要遍历，避免漏掉账号 2、3 的残留） */
    public List<Long> allBotIds() {
        List<Long> ids = new ArrayList<>();
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM bot_accounts ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (Exception e) {
            log.warn("读取账号列表失败: {}", e.getMessage());
        }
        return ids;
    }

    public void setEndpoint(String pluginId, String endpoint) {
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE installed_plugins SET endpoint=?, updated_at=? WHERE plugin_id=?")) {
            ps.setString(1, endpoint);
            ps.setString(2, LocalDateTime.now().format(TS));
            ps.setString(3, pluginId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存端点失败: " + e.getMessage(), e);
        }
        reload();
    }
}
