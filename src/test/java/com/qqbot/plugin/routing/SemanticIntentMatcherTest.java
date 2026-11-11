package com.qqbot.plugin.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.EmbeddingClient;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.IntentIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L3 语义召回：阈值、降级、前置过滤、关闭开关。 */
class SemanticIntentMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static InstalledPlugin plugin(String examplesJson, String version) throws Exception {
        String json = """
                {"id":"menran.sing","name":"唱歌","version":"%s","runtime":"data",
                 "intent":{"id":"music.sing","examples":%s}}
                """.formatted(version, examplesJson);
        PluginManifest manifest = MAPPER.readValue(json, PluginManifest.class);
        return new InstalledPlugin("i1", "menran.sing", version, "data", "store",
                null, "installed", null, manifest, null);
    }

    private static SemanticIntentMatcher matcher(PluginProperties props, EmbeddingClient embeddings) {
        return new SemanticIntentMatcher(embeddings, props);
    }

    @Test
    void hitsAboveThreshold() throws Exception {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedOne(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.contains("周杰伦") || text.equals("唱首歌")
                    ? new float[]{1f, 0f} : new float[]{0f, 1f};
        });
        IntentIndex.Hit hit = matcher(new PluginProperties(), embeddings)
                .match("来一段周杰伦的", List.of(plugin("[\"唱首歌\"]", "1.0.0")));
        assertNotNull(hit);
        assertEquals("music.sing", hit.intentId());
        assertEquals(3, hit.level());
    }

    @Test
    void belowThresholdIsIgnored() throws Exception {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(embeddings.embedOne("唱首歌")).thenReturn(new float[]{0f, 1f});
        assertNull(matcher(new PluginProperties(), embeddings)
                .match("来一段周杰伦的", List.of(plugin("[\"唱首歌\"]", "1.0.0"))));
    }

    @Test
    void embeddingFailureFallsBack() throws Exception {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedOne(anyString())).thenReturn(null);
        assertNull(matcher(new PluginProperties(), embeddings)
                .match("来一段周杰伦的", List.of(plugin("[\"唱首歌\"]", "1.0.0"))));
    }

    @Test
    void prefilterSkipsPlainChat() throws Exception {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        assertNull(matcher(new PluginProperties(), embeddings)
                .match("今天天气不错", List.of(plugin("[\"唱首歌\"]", "1.0.0"))));
    }

    @Test
    void disabledSwitchDoesNothing() throws Exception {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        PluginProperties props = new PluginProperties();
        props.setSemanticEnabled(false);
        assertNull(matcher(props, embeddings)
                .match("来一段周杰伦的", List.of(plugin("[\"唱首歌\"]", "1.0.0"))));
    }
}
