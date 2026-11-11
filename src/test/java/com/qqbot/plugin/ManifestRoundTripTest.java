package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.IntentIndex;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** manifest 走一遍「序列化进库 → 反序列化出来」后，意图索引必须还在（安装后索引为 0 的回归） */
class ManifestRoundTripTest {

    @Test
    void intentSurvivesJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PluginManifest original = mapper.readValue(
                Files.readAllBytes(Path.of("packs/menran.sing/manifest.json")), PluginManifest.class);
        String stored = mapper.writeValueAsString(original);
        PluginManifest restored = mapper.readValue(stored, PluginManifest.class);

        assertNotNull(restored.intent(), "往返后 intent 丢了");
        assertEquals("music.sing", restored.intent().id());
        assertTrue(restored.intent().examples().size() >= 6, "示例不应丢失");
        assertNotNull(restored.catalog(), "往返后 catalog 丢了");
        assertEquals(3, restored.catalog().entries().size());

        InstalledPlugin p = new InstalledPlugin("i", "menran.sing", "1.0.0", "data", "upload",
                "sha", "installed", null, restored, Path.of("packs/menran.sing"));
        IntentIndex index = IntentIndex.build(List.of(p));
        assertTrue(index.size() >= 6, "索引条数 = " + index.size());
        assertFalse(index.match("唱首歌").isEmpty());
    }
}
