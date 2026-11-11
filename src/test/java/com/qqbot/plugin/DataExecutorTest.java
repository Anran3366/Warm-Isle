package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** data 执行器：按歌名取素材、随机取、找不到时报 NOT_FOUND */
class DataExecutorTest {

    private final DataExecutor executor = new DataExecutor();
    private final Path dir = Path.of("packs/menran.sing");

    private InstalledPlugin plugin() throws Exception {
        PluginManifest m = new ObjectMapper().readValue(
                Files.readAllBytes(dir.resolve("manifest.json")), PluginManifest.class);
        return new InstalledPlugin("i", "menran.sing", "1.0.0", "data", "upload", "sha",
                "installed", null, m, dir.toAbsolutePath());
    }

    @Test
    void picksEntryBySongName() throws Exception {
        PluginOutput out = executor.execute(plugin(), "sing", Map.of("song", "小星星"));
        assertEquals("audio", out.kind());
        assertTrue(out.bytes().length > 10_000);
        assertTrue(out.durationMs() > 0);
        assertEquals("小星星", out.memoryVars().get("song"));
    }

    @Test
    void randomWhenNoParam() throws Exception {
        PluginOutput out = executor.execute(plugin(), "sing", Map.of());
        assertEquals("audio", out.kind());
        assertNotNull(out.memoryVars().get("song"));
    }

    @Test
    void unknownSongIsNotFound() throws Exception {
        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin(), "sing", Map.of("song", "不存在的歌名")));
        assertEquals("NOT_FOUND", e.code());
    }
}
