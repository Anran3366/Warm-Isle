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

/** 意图索引：L1 全等、L2 包含、3 字示例只全等（v2.2 N1） */
class IntentIndexTest {

    private final IntentIndex index = build();

    private static IntentIndex build() {
        PluginManifest m = readManifest();
        InstalledPlugin p = new InstalledPlugin("i1", "menran.sing", "1.0.0", "data", "upload",
                "sha", "installed", null, m, Path.of("packs/menran.sing"));
        return IntentIndex.build(List.of(p));
    }

    private static PluginManifest readManifest() {
        try {
            return new ObjectMapper().readValue(
                    Files.readAllBytes(Path.of("packs/menran.sing/manifest.json")),
                    PluginManifest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void exactThreeCharExampleMatches() {
        List<IntentIndex.Hit> hits = index.match("唱首歌");
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).level());
        assertEquals(1.0, hits.get(0).score());
    }

    @Test
    void threeCharRequestPhraseMatchesByContainment() {
        // 请求动词开头的 3 字示例允许包含匹配，否则最自然的点歌说法会漏
        assertEquals(1, index.match("你能不能唱首歌啊").size());
        assertEquals(1, index.match("来一段周杰伦的").size());
        // 4 字以上照常包含匹配
        assertFalse(index.match("你能不能给我唱一段吧").isEmpty());
    }

    @Test
    void thirdPersonStillRequiresExactMatch() {
        assertTrue(index.match("他昨天唱首歌就走了").isEmpty());
    }

    @Test
    void longExampleMatchesByContainment() {
        List<IntentIndex.Hit> hits = index.match("想听你唱一首嘛");
        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).level());
        assertTrue(hits.get(0).score() >= 0.75);
    }

    @Test
    void unrelatedTextDoesNotMatch() {
        assertTrue(index.match("今天下班好晚，地铁挤死了").isEmpty());
    }
}
