package com.qqbot.plugin.routing;

import com.qqbot.memory.EmbeddingClient;
import com.qqbot.memory.MemoryVectorService;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.IntentIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 语义意图匹配（设计文档 §8.2）。
 *
 * <p>只有 L1 全等 / L2 包含都没命中时才走这里：把用户这句话和已启用插件的 intent.examples
 * 做向量相似度比较，超过阈值（默认 0.80）才算命中。一次 embedding，示例向量按
 * pluginId#version 缓存；向量服务不可用就返回 null，自动退回 L1/L2，不影响主链路。
 *
 * <p>前面还有一层便宜的前置过滤：只有像“点单/请求”的短句才值得付这一次 embedding，
 * 普通闲聊（“你在干嘛”）直接跳过。
 */
@Component
public class SemanticIntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(SemanticIntentMatcher.class);

    /** 请求语气线索：命中任意一个才做语义召回，避免给普通聊天加延迟 */
    private static final List<String> REQUEST_HINTS = List.of(
            "给我", "帮我", "来一首", "来一段", "来一个", "想听", "想让你",
            "能不能", "可以", "有没有", "唱", "放", "查", "算", "讲", "画", "提醒");

    private final EmbeddingClient embeddings;
    private final PluginProperties props;
    /** key = pluginId#version#example → 向量；示例变了版本号会变，缓存自然失效 */
    private final Map<String, float[]> exampleVectors = new ConcurrentHashMap<>();

    public SemanticIntentMatcher(EmbeddingClient embeddings, PluginProperties props) {
        this.embeddings = embeddings;
        this.props = props;
    }

    /**
     * @return 命中的最高分意图；未开启 / 不像请求 / 服务不可用 / 低于阈值都返回 null
     */
    public IntentIndex.Hit match(String text, List<InstalledPlugin> active) {
        if (!props.isSemanticEnabled() || text == null || active == null || active.isEmpty()) {
            return null;
        }
        String t = text.trim();
        if (t.length() < 3 || t.length() > Math.max(8, props.getSemanticMaxChars())) {
            return null;
        }
        if (props.isSemanticPrefilter() && !looksLikeRequest(t)) {
            return null;
        }
        float[] query;
        try {
            query = embeddings.embedOne(t);
        } catch (Exception e) {
            log.debug("语义召回 embedding 失败，退回关键词: {}", e.getMessage());
            return null;
        }
        if (query == null || query.length == 0) {
            return null;
        }

        IntentIndex.Hit best = null;
        double threshold = props.getSemanticThreshold();
        for (InstalledPlugin plugin : active) {
            PluginManifest.Intent intent = plugin.manifest() == null ? null : plugin.manifest().intent();
            if (intent == null || intent.id() == null || intent.examples() == null) {
                continue;
            }
            for (String example : intent.examples()) {
                if (example == null || example.trim().length() < 3) {
                    continue;
                }
                float[] vec = vectorOf(plugin, example.trim());
                if (vec == null) {
                    continue;
                }
                double score = MemoryVectorService.cosine(query, vec);
                if (score >= threshold && (best == null || score > best.score())) {
                    best = new IntentIndex.Hit(plugin.pluginId(), intent.id(), 3, score, example);
                }
            }
        }
        if (best != null) {
            log.debug("语义命中 intent={} plugin={} score={}",
                    best.intentId(), best.pluginId(), String.format("%.3f", best.score()));
        }
        return best;
    }

    private float[] vectorOf(InstalledPlugin plugin, String example) {
        String key = plugin.pluginId() + "#" + plugin.version() + "#" + example;
        float[] cached = exampleVectors.get(key);
        if (cached != null) {
            return cached;
        }
        float[] v;
        try {
            v = embeddings.embedOne(example);
        } catch (Exception e) {
            return null;
        }
        if (v != null) {
            exampleVectors.put(key, v);
            if (exampleVectors.size() > 512) {
                exampleVectors.clear();
            }
        }
        return v;
    }

    static boolean looksLikeRequest(String text) {
        for (String hint : REQUEST_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return text.endsWith("吗") || text.endsWith("呗") || text.endsWith("吧");
    }

    /** 供后台状态展示：当前生效的阈值 */
    public double threshold() {
        return props.getSemanticThreshold();
    }

    /** 已缓存示例向量数，排查用 */
    public int cachedVectors() {
        return exampleVectors.size();
    }
}
