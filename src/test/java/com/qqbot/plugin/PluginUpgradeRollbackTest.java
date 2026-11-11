package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.Db;
import com.qqbot.memory.GlobalDb;
import com.qqbot.memory.SharedDb;
import com.qqbot.plugin.install.PluginInstaller;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.permission.PermissionCatalog;
import com.qqbot.plugin.registry.PluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 升级与回滚（§7.4）。
 *
 * <p>三条必须守住的事实：升级要留下回滚点、回滚只切版本不动记忆、权限变了就得重新确认。
 */
class PluginUpgradeRollbackTest {

    @TempDir
    Path tmp;

    private PluginInstaller installer;
    private PluginRegistry registry;
    private Db db;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        BotProperties bot = new BotProperties();
        bot.getDb().setPath(tmp.resolve("bots").resolve("1.db").toString());

        GlobalDb globalDb = new GlobalDb(bot);
        globalDb.init();
        SharedDb shared = new SharedDb(bot);
        shared.init();
        db = new Db(bot, globalDb);
        db.init();

        PluginProperties props = new PluginProperties();
        props.setEnabled(true);
        props.setDir(tmp.resolve("plugins").toString());
        props.setKeepOldVersionsDays(7);

        registry = new PluginRegistry(shared, db, globalDb, props, mapper);
        registry.init();
        installer = new PluginInstaller(registry, props, shared, db, mapper);
    }

    @Test
    void upgradeKeepsRollbackPointAndRollbackSwapsBack() throws Exception {
        PluginInstaller.InstallResult v1 = installer.install(zip("1.0.0", null), "upload");
        assertTrue(v1.ok(), () -> "v1 安装失败: " + v1.issues());
        assertEquals("1.0.0", registry.find("demo.up").version());

        // 模拟「用户已确认权限并启用」
        PluginManifest m1 = registry.find("demo.up").manifest();
        registry.setEnabled(1, "demo.up", true, "[]", PermissionCatalog.digest(m1));
        assertTrue(registry.stateOf(1, "demo.up").enabled());

        // 升级：权限没变，开关不该被断
        PluginInstaller.InstallResult v2 = installer.install(zip("1.1.0", null), "store");
        assertTrue(v2.ok(), () -> "v2 安装失败: " + v2.issues());
        assertEquals("1.1.0", registry.find("demo.up").version());
        assertTrue(registry.stateOf(1, "demo.up").enabled(), "权限没变却被停用了");

        List<PluginInstaller.VersionInfo> versions = installer.versions("demo.up");
        assertEquals(2, versions.size());
        assertTrue(versions.stream().anyMatch(v -> v.version().equals("1.1.0") && v.current()));
        assertTrue(versions.stream().anyMatch(v -> v.version().equals("1.0.0") && v.previous()));

        // 回滚
        PluginInstaller.RollbackResult r = installer.rollback("demo.up");
        assertTrue(r.ok(), r.message());
        assertEquals("1.0.0", registry.find("demo.up").version());
        assertEquals("1.1.0", installer.versions("demo.up").stream()
                .filter(PluginInstaller.VersionInfo::previous).findFirst().orElseThrow().version());

        // 再点一次回滚 = 换回来，不该报错
        PluginInstaller.RollbackResult r2 = installer.rollback("demo.up");
        assertTrue(r2.ok(), r2.message());
        assertEquals("1.1.0", registry.find("demo.up").version());
    }

    @Test
    void permissionChangeOnUpgradeSuspendsEnabledBots() throws Exception {
        installer.install(zip("1.0.0", null), "upload");
        PluginManifest m1 = registry.find("demo.up").manifest();
        registry.setEnabled(1, "demo.up", true, "[]", PermissionCatalog.digest(m1));

        // 新版多要一个域名：摘要变化 → 必须重新确认
        PluginInstaller.InstallResult r = installer.install(
                zip("1.1.0", "api.example.com"), "store");
        assertTrue(r.ok(), () -> "升级失败: " + r.issues());
        assertFalse(registry.stateOf(1, "demo.up").enabled(),
                "权限变多仍然保持启用，等于绕过二次确认");
    }

    @Test
    void rollbackWithoutPreviousVersionFailsCleanly() throws Exception {
        installer.install(zip("1.0.0", null), "upload");
        PluginInstaller.RollbackResult r = installer.rollback("demo.up");
        assertFalse(r.ok());
        assertTrue(r.message().contains("上一版"), r.message());
        // 失败不能把当前版本弄丢
        assertEquals("1.0.0", registry.find("demo.up").version());
    }

    /** 老数据迁移：64 位 sha256 的旧摘要不该让已启用插件永远显示「待确认」 */
    @Test
    void legacyShaManifestHashIsRewritten() throws Exception {
        installer.install(zip("1.0.0", null), "upload");
        String legacy = "a".repeat(64);
        try (var c = db.open(1);
             var ps = c.prepareStatement(
                     "INSERT INTO plugin_state(plugin_id, enabled, permissions, manifest_hash, updated_at) "
                             + "VALUES('demo.up',1,'[]',?,'2026-01-01 00:00:00')")) {
            ps.setString(1, legacy);
            ps.executeUpdate();
        }
        registry.reload();
        PluginRegistry.AckState st = registry.stateOf(1, "demo.up");
        assertEquals(16, st.manifestHash().length(), "旧 sha256 没有被重算成权限摘要");
        assertTrue(st.enabled(), "迁移不该改动开关");
        assertEquals(PermissionCatalog.digest(registry.find("demo.up").manifest()), st.manifestHash());
    }

    /** 造一个能过体检的 local 型包：5 条示例句 + SETUP.md + 可选联网域名 */
    private byte[] zip(String version, String extraHost) throws Exception {
        String outbound = extraHost == null ? "" : "\"outbound\": [\"" + extraHost + "\"],";
        String manifest = """
                {
                  "schemaVersion": 2, "id": "demo.up", "name": "升级测试", "version": "%s",
                  "apiVersion": 1, "runtime": "local",
                  "description": "只用于测试升级与回滚", "license": "MIT",
                  "readme": "README.md", "tags": ["测试"],
                  "compat": {"minHostVersion": "1.0.0"},
                  "intent": {"id": "demo.up", "examples": ["升级测试一下", "试一下升级", "回滚测试来", "版本切换测试", "测测升级"]},
                  "actions": [{"name": "echo", "description": "回声", "returns": ["text"], "timeoutMs": 5000}],
                  "local": {"endpointHint": "http://127.0.0.1:3600", "health": "/health",
                            "invoke": "/v1/invoke", "setup": "SETUP.md"},
                  "permissions": {"network": {%s "purpose": "测试"}, "localService": {"allow": true}},
                  "quota": {"perUserPerDay": 20}
                }
                """.formatted(version, outbound);
        return zipOf(new String[][]{
                {"manifest.json", manifest},
                {"README.md", "# 升级测试"},
                {"SETUP.md", "# 部署\n\n起个本地服务即可。"}
        });
    }

    private static byte[] zipOf(String[][] entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
