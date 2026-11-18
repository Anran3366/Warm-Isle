package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.data.PluginDataStore;
import com.qqbot.plugin.manifest.ManifestParser;
import com.qqbot.plugin.model.ManifestIssue;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 周报插件 manifest 必须能过校验，声明式表能建、能写、能读。 */
class WeeklyPluginTest {

    @TempDir
    Path tmp;

    @Test
    void weeklyManifestIsValid() throws Exception {
        byte[] json = Files.readAllBytes(Path.of("packs/menran.weekly/manifest.json"));
        ManifestParser.Result r = ManifestParser.parse(new ObjectMapper(), json);
        List<ManifestIssue> errors = r.issues().stream().filter(ManifestIssue::isError).toList();
        assertTrue(r.ok(), "周报 manifest 校验失败: " + errors);
        assertEquals(3, r.manifest().configFieldsOrEmpty().size());
        assertEquals("reports", r.manifest().tables().get(0).name());
    }

    @Test
    void perPluginTableCanBeCreatedAndQueried() throws Exception {
        byte[] json = Files.readAllBytes(Path.of("packs/menran.weekly/manifest.json"));
        var manifest = ManifestParser.parse(new ObjectMapper(), json).manifest();
        PluginProperties props = new PluginProperties();
        props.setDir(tmp.resolve("data/plugins").toString());
        PluginDataStore store = new PluginDataStore(props);
        InstalledPlugin plugin = new InstalledPlugin("i1", "menran.weekly", "1.0.0", "prompt",
                "upload", "sha", "installed", null, manifest, tmp.resolve("data/plugins/menran.weekly/1.0.0"));

        long id = store.insert(plugin, "reports", 1L, Map.of(
                "user_id", 100000000L, "period", "2026-W47",
                "content", "这周你加班很多，但也记得吃饭。", "style", "温柔",
                "created_at", "2026-11-18 21:00:00"));
        assertTrue(id > 0);
        assertEquals(1, store.count(plugin, "reports", 1L, 100000000L));
        var rows = store.list(plugin, "reports", 1L, 100000000L, 1, 20);
        assertEquals(1, rows.size());
        assertEquals("2026-W47", rows.get(0).get("period"));
        assertEquals(0, store.list(plugin, "reports", 2L, null, 1, 20).size(), "别的账号看不到");
    }
}
