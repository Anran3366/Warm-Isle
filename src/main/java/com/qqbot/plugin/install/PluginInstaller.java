package com.qqbot.plugin.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.Db;
import com.qqbot.memory.SharedDb;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.pkg.PackageVerifier;
import com.qqbot.plugin.registry.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 插件安装 / 卸载（实例级）。
 *
 * <p>安装必须先过「结构校验 → 语义校验」两关；解压到临时目录、全部通过后再原子 rename，
 * 失败不会留下半个插件。卸载要遍历所有 bot 库，否则账号 2、3 的状态与记忆永远清不掉。
 */
@Component
public class PluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PluginRegistry registry;
    private final PluginProperties props;
    private final SharedDb shared;
    private final Db db;
    private final ObjectMapper mapper;

    public PluginInstaller(PluginRegistry registry, PluginProperties props,
                           SharedDb shared, Db db, ObjectMapper mapper) {
        this.registry = registry;
        this.props = props;
        this.shared = shared;
        this.db = db;
        this.mapper = mapper;
    }

    public record InstallResult(boolean ok, String pluginId, String version,
                                List<ManifestIssue> issues) {
    }

    public record InspectResult(boolean ok, PluginManifest manifest, List<ManifestIssue> issues) {
    }

    /**
     * 本地磁盘上的一版插件。
     *
     * @param current  是不是当前生效的版本
     * @param previous 是不是备着回滚的上一版
     */
    public record VersionInfo(String version, long sizeBytes, long modifiedAt,
                              boolean current, boolean previous) {
    }

    public record RollbackResult(boolean ok, String from, String to, String message,
                                 int pausedBots) {
    }

    /** 只检查、不落盘（安装预览：用户看到的差异必须和实际写入一致） */
    public InspectResult inspect(byte[] zip) {
        List<ManifestIssue> issues = new ArrayList<>();
        PackageVerifier.Result pkg = PackageVerifier.inspect(zip);
        issues.addAll(pkg.issues());
        if (!pkg.ok()) {
            return new InspectResult(false, null, issues);
        }
        ManifestParser.Result parsed = ManifestParser.parse(mapper, pkg.manifestJson());
        issues.addAll(parsed.issues());
        return new InspectResult(parsed.ok(), parsed.manifest(), issues);
    }

    public InstallResult install(byte[] zip, String source) {
        List<ManifestIssue> issues = new ArrayList<>();
        PackageVerifier.Result pkg = PackageVerifier.inspect(zip);
        issues.addAll(pkg.issues());
        if (!pkg.ok()) {
            return new InstallResult(false, null, null, issues);
        }
        ManifestParser.Result parsed = ManifestParser.parse(mapper, pkg.manifestJson());
        issues.addAll(parsed.issues());
        if (!parsed.ok()) {
            return new InstallResult(false, null, null, issues);
        }
        PluginManifest m = parsed.manifest();
        Path root = registry.pluginsRoot();
        Path target = root.resolve(m.id()).resolve(m.version());

        try {
            Files.createDirectories(root);
            Path tmp = root.resolve(".tmp").resolve(UUID.randomUUID().toString());
            PackageVerifier.extract(zip, tmp);
            if (!Files.isRegularFile(tmp.resolve("manifest.json"))) {
                issues.add(ManifestIssue.of("manifest.json", "MANIFEST_MISSING", "解压后找不到 manifest.json"));
                deleteQuietly(tmp);
                return new InstallResult(false, null, null, issues);
            }
            if (m.usesPrompt()) {
                // 安装时静态扫描：注入话术 / 未声明的占位符 / 模板大小编码（§10.6）
                List<ManifestIssue> scan = com.qqbot.plugin.pkg.PromptTemplateScanner.scan(m, tmp);
                if (!scan.isEmpty()) {
                    issues.addAll(scan);
                    deleteQuietly(tmp);
                    return new InstallResult(false, null, null, issues);
                }
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Path trash = root.resolve(".trash")
                        .resolve(m.id() + "-" + m.version() + "-" + System.currentTimeMillis());
                Files.createDirectories(trash.getParent());
                Files.move(target, trash, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            boolean upgraded = upsertRow(m, sha256(zip), source);
            registry.reload();
            if (upgraded) {
                // 升级后权限有变就先停掉，等用户在后台重新确认（文档 §7.4 / §7.7）
                int paused = suspendIfPermissionsChanged(m);
                if (paused > 0) {
                    log.warn("插件 {} v{} 权限有变化，已暂停 {} 个账号，等待重新确认", m.id(), m.version(), paused);
                }
            }
            pruneQuietly(m.id());
            log.info("插件已安装: {} v{}（来源 {}）", m.id(), m.version(), source);
            return new InstallResult(true, m.id(), m.version(), issues);
        } catch (Exception e) {
            log.error("安装插件失败: {}", e.getMessage());
            issues.add(ManifestIssue.of("package", "INSTALL_FAILED", "安装失败: " + e.getMessage()));
            return new InstallResult(false, null, null, issues);
        }
    }

    /** 卸载：停用 → 移目录 → 删记录 → 清每个 bot 库 → 归档记忆（不物理删） */
    public void uninstall(String pluginId) {
        List<Long> botIds = registry.allBotIds();
        for (Long botId : botIds) {
            registry.setEnabled(botId, pluginId, false, null, null);
        }
        registry.reload();

        Path root = registry.pluginsRoot();
        try {
            Path dir = root.resolve(pluginId);
            if (Files.exists(dir)) {
                Path trash = root.resolve(".trash").resolve(pluginId + "-" + System.currentTimeMillis());
                Files.createDirectories(trash.getParent());
                Files.move(dir, trash);
            }
        } catch (Exception e) {
            log.warn("移动插件目录失败: {}", e.getMessage());
        }

        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM installed_plugins WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("删除安装记录失败: {}", e.getMessage());
        }

        for (Long botId : botIds) {
            try (Connection c = db.open(botId)) {
                for (String sql : new String[]{
                        "DELETE FROM plugin_state WHERE plugin_id=?",
                        "DELETE FROM plugin_runs WHERE plugin_id=?",
                        "DELETE FROM plugin_proposals WHERE plugin_id=?"}) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, pluginId);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE memories SET status='archived' WHERE source LIKE ? AND status='active'")) {
                    ps.setString(1, "plugin:" + pluginId + "%");
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                log.warn("清理账号 {} 的插件数据失败: {}", botId, e.getMessage());
            }
        }
        registry.reload();
        log.info("插件已卸载: {}（影响 {} 个账号）", pluginId, botIds.size());
    }

    /**
     * 写安装记录；同一 plugin_id 再次安装即升级。
     *
     * @return true 表示版本号变了（是升级，不是同版本重装）
     */
    private boolean upsertRow(PluginManifest m, String sha, String source) throws Exception {
        String existingId = null;
        String oldVersion = null;
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT install_id, version FROM installed_plugins WHERE plugin_id=?")) {
            ps.setString(1, m.id());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    existingId = rs.getString(1);
                    oldVersion = rs.getString(2);
                }
            }
        }
        boolean upgraded = oldVersion != null && !oldVersion.equals(m.version());
        String installId = existingId == null ? UUID.randomUUID().toString() : existingId;
        String now = LocalDateTime.now().format(TS);
        // 升级：把「当前版本」顺位挪到 prev_*；同版本重装则原样保留上一版，别把回滚点冲掉
        String sql = "INSERT INTO installed_plugins(install_id, plugin_id, version, runtime, source, "
                + "sha256, manifest, status, installed_at, updated_at, prev_version, prev_sha256) "
                + "VALUES(?,?,?,?,?,?,?,'installed',?,?,?,?) "
                + "ON CONFLICT(plugin_id) DO UPDATE SET "
                + "prev_version=CASE WHEN installed_plugins.version<>excluded.version "
                + "THEN installed_plugins.version ELSE installed_plugins.prev_version END, "
                + "prev_sha256=CASE WHEN installed_plugins.version<>excluded.version "
                + "THEN installed_plugins.sha256 ELSE installed_plugins.prev_sha256 END, "
                + "version=excluded.version, runtime=excluded.runtime, source=excluded.source, "
                + "sha256=excluded.sha256, manifest=excluded.manifest, status='installed', "
                + "updated_at=excluded.updated_at";
        try (Connection c = shared.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, installId);
            ps.setString(2, m.id());
            ps.setString(3, m.version());
            ps.setString(4, m.runtime());
            ps.setString(5, source);
            ps.setString(6, sha);
            ps.setString(7, mapper.writeValueAsString(m));
            ps.setString(8, now);
            ps.setString(9, now);
            ps.setString(10, upgraded ? oldVersion : null);
            ps.setString(11, upgraded ? sha : null);
            ps.executeUpdate();
        }
        return upgraded;
    }

    /**
     * 权限守卫：版本变化后若权限摘要与用户确认过的不一致，就把该账号的插件停掉，
     * 强制走一次「重新确认」。data 型插件没有权限项，摘要恒为 {@code none}，升级不会被打断。
     *
     * @return 被暂停的账号数
     */
    private int suspendIfPermissionsChanged(PluginManifest m) {
        String digest = com.qqbot.plugin.permission.PermissionCatalog.digest(m);
        int paused = 0;
        for (Long botId : registry.allBotIds()) {
            try {
                PluginRegistry.AckState st = registry.stateOf(botId, m.id());
                if (st.enabled() && !digest.equals(st.manifestHash())) {
                    registry.setEnabled(botId, m.id(), false, null, null);
                    paused++;
                }
            } catch (Exception e) {
                log.warn("检查账号 {} 的插件 {} 权限状态失败: {}", botId, m.id(), e.getMessage());
            }
        }
        return paused;
    }

    /** 本地磁盘上有哪些版本（当前 / 上一版 / 其余待清理的） */
    public List<VersionInfo> versions(String pluginId) {
        Path dir = registry.pluginsRoot().resolve(pluginId);
        List<VersionInfo> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        String current = null;
        String previous = null;
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version, prev_version FROM installed_plugins WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    current = rs.getString(1);
                    previous = rs.getString(2);
                }
            }
        } catch (Exception e) {
            log.warn("读取 {} 版本记录失败: {}", pluginId, e.getMessage());
        }
        try (var list = Files.list(dir)) {
            for (Path p : list.filter(Files::isDirectory).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                try {
                    out.add(new VersionInfo(name, sizeOf(p),
                            Files.getLastModifiedTime(p).toMillis(),
                            name.equals(current), name.equals(previous)));
                } catch (Exception ignored) {
                    // 单个目录读不到就跳过，不影响列表
                }
            }
        } catch (Exception e) {
            log.warn("列出 {} 的版本目录失败: {}", pluginId, e.getMessage());
        }
        out.sort((a, b) -> com.qqbot.plugin.util.Versions.compare(b.version(), a.version()));
        return out;
    }

    /**
     * 回滚到上一版：只切版本号与目录指针，<b>不动记忆</b>。
     *
     * <p>文档 §7.4 明确「记忆不回滚」——回滚的是代码，不是发生过的事。
     */
    public RollbackResult rollback(String pluginId) {
        String version = null;
        String prevVersion = null;
        String sha = null;
        String prevSha = null;
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version, prev_version, sha256, prev_sha256 FROM installed_plugins WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new RollbackResult(false, null, null, "插件未安装", 0);
                }
                version = rs.getString(1);
                prevVersion = rs.getString(2);
                sha = rs.getString(3);
                prevSha = rs.getString(4);
            }
        } catch (Exception e) {
            return new RollbackResult(false, null, null, "读取安装记录失败: " + e.getMessage(), 0);
        }
        if (prevVersion == null || prevVersion.isBlank()) {
            return new RollbackResult(false, version, null, "没有可回滚的上一版（只装过一次）", 0);
        }
        Path dir = registry.pluginsRoot().resolve(pluginId).resolve(prevVersion);
        if (!Files.isDirectory(dir)) {
            return new RollbackResult(false, version, prevVersion,
                    "上一版目录已被清理（超过保留天数），只能重新安装旧包", 0);
        }
        PluginManifest prevManifest;
        try {
            prevManifest = mapper.readValue(Files.readString(dir.resolve("manifest.json")), PluginManifest.class);
        } catch (Exception e) {
            return new RollbackResult(false, version, prevVersion, "上一版 manifest 读取失败: " + e.getMessage(), 0);
        }
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE installed_plugins SET version=?, prev_version=?, sha256=?, prev_sha256=?, "
                             + "manifest=?, updated_at=? WHERE plugin_id=?")) {
            ps.setString(1, prevVersion);
            ps.setString(2, version);
            ps.setString(3, prevSha);
            ps.setString(4, sha);
            ps.setString(5, mapper.writeValueAsString(prevManifest));
            ps.setString(6, LocalDateTime.now().format(TS));
            ps.setString(7, pluginId);
            ps.executeUpdate();
        } catch (Exception e) {
            return new RollbackResult(false, version, prevVersion, "写回滚记录失败: " + e.getMessage(), 0);
        }
        registry.reload();
        int paused = suspendIfPermissionsChanged(prevManifest);
        log.warn("插件 {} 已回滚: {} → {}（暂停 {} 个账号）", pluginId, version, prevVersion, paused);
        return new RollbackResult(true, version, prevVersion, "已回滚到 " + prevVersion, paused);
    }

    /**
     * 清理旧版本目录：既不是当前版、也不是上一版，且超过保留天数才删。
     *
     * @return 删掉的目录数
     */
    public int pruneOldVersions(String pluginId) {
        String current = null;
        String previous = null;
        try (Connection c = shared.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version, prev_version FROM installed_plugins WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    current = rs.getString(1);
                    previous = rs.getString(2);
                }
            }
        } catch (Exception e) {
            return 0;
        }
        Path root = registry.pluginsRoot();
        long cutoff = System.currentTimeMillis() - Math.max(1, props.getKeepOldVersionsDays()) * 86_400_000L;
        int removed = 0;
        Path dir = root.resolve(pluginId);
        if (Files.isDirectory(dir)) {
            try (var list = Files.list(dir)) {
                for (Path p : list.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".") || name.equals(current) || name.equals(previous)) {
                        continue;
                    }
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        deleteQuietly(p);
                        removed++;
                    }
                }
            } catch (Exception e) {
                log.warn("清理 {} 旧版本失败: {}", pluginId, e.getMessage());
            }
        }
        // .trash 里的是卸载/覆盖留下的，同样按天数清
        Path trash = root.resolve(".trash");
        if (Files.isDirectory(trash)) {
            try (var list = Files.list(trash)) {
                for (Path p : list.toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith(pluginId + "-")) {
                        continue;
                    }
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        deleteQuietly(p);
                        removed++;
                    }
                }
            } catch (Exception ignored) {
                // 清理失败不影响主流程
            }
        }
        if (removed > 0) {
            log.info("清理插件 {} 的 {} 个旧版本目录", pluginId, removed);
        }
        return removed;
    }

    private void pruneQuietly(String pluginId) {
        try {
            pruneOldVersions(pluginId);
        } catch (Exception e) {
            log.debug("清理旧版本失败: {}", e.getMessage());
        }
    }

    private static long sizeOf(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
