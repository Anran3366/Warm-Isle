package com.qqbot.plugin.routing;

import com.qqbot.plugin.memory.PluginMemoryWriter;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.IntentIndex;
import com.qqbot.plugin.registry.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两段式路由的第一段（零 token）：意图命中 → 参数抽取 → 执行或反问。
 *
 * <p>硬规则（设计文档 §8.3，写死在宿主、作者不可关）：
 * <ul>
 *   <li>纯情绪倾诉且没有点单动词时，永不触发插件；</li>
 *   <li>作者自填的 priority 不参与排序；</li>
 *   <li>追问（「上次那首」）缺参时用 recentDeeds 回填，不靠相似度。</li>
 * </ul>
 */
@Component
public class PluginRouter {

    private static final Logger log = LoggerFactory.getLogger(PluginRouter.class);

    /** 情绪词：命中且无点单动词 → 交回正常对话（「好累啊」被回一句「要不要唱首歌」最败好感） */
    private static final List<String> EMOTION = List.of(
            "累", "烦", "难受", "难过", "想哭", "被骂", "吵架", "崩溃", "压力", "不开心", "emo",
            "心情不好", "委屈", "焦虑", "失眠", "痛苦", "郁闷", "生气", "伤心");

    /** 明确点单动词：有它就不算纯倾诉 */
    private static final List<String> ORDER_VERBS = List.of(
            "唱", "来一首", "来一段", "放", "查", "给我", "讲个", "算", "画", "提醒我");

    private static final Pattern FOLLOW_UP = Pattern.compile("上次|那首|刚才|再来|又|还要|还是那");

    private final PluginRegistry registry;
    private final PluginMemoryWriter memoryWriter;
    private final SemanticIntentMatcher semantic;

    public PluginRouter(PluginRegistry registry, PluginMemoryWriter memoryWriter,
                        SemanticIntentMatcher semantic) {
        this.registry = registry;
        this.memoryWriter = memoryWriter;
        this.semantic = semantic;
    }

    public RouteDecision route(long botId, long qqId, String text) {
        if (text == null || text.isBlank()) {
            return RouteDecision.none();
        }
        if (isEmotionalVent(text)) {
            return RouteDecision.none();
        }
        List<InstalledPlugin> active = registry.activeFor(botId);
        if (active.isEmpty()) {
            return RouteDecision.none();
        }
        Set<String> activeIds = active.stream().map(InstalledPlugin::pluginId)
                .collect(java.util.stream.Collectors.toSet());

        List<IntentIndex.Hit> hits = new java.util.ArrayList<>(registry.intentIndex().match(text).stream()
                .filter(h -> activeIds.contains(h.pluginId()))
                .toList());
        if (hits.isEmpty()) {
            // L1/L2 都没中，才付一次 embedding（设计文档 §8.2 L3）
            IntentIndex.Hit semanticHit = semantic.match(text, active);
            if (semanticHit == null) {
                return RouteDecision.none();
            }
            hits.add(semanticHit);
        }
        IntentIndex.Hit hit = hits.get(0);
        InstalledPlugin plugin = registry.find(hit.pluginId());
        if (plugin == null || !plugin.usable()) {
            return RouteDecision.none();
        }
        PluginManifest.Action action = plugin.manifest().primaryAction();
        if (action == null) {
            return RouteDecision.none();
        }

        Map<String, String> params = extractParams(plugin, action, text);
        if (action.hasRequiredParams()) {
            List<String> missing = action.requiredParams().stream()
                    .filter(name -> !params.containsKey(name))
                    .toList();
            if (!missing.isEmpty()) {
                if (FOLLOW_UP.matcher(text).find()) {
                    String deed = memoryWriter.lastEntity(qqId);
                    if (deed != null && !deed.isBlank()) {
                        for (String name : missing) {
                            params.put(name, deed);
                        }
                        log.debug("追问回填 {}={}（插件 {}）", missing, deed, plugin.pluginId());
                        return new RouteDecision(RouteDecision.Kind.EXECUTE, plugin, action.name(),
                                params, 0.85, null);
                    }
                }
                return new RouteDecision(RouteDecision.Kind.FILL_PARAMS, plugin, action.name(),
                        params, hit.score(), fillHint(plugin, missing));
            }
        }
        return new RouteDecision(RouteDecision.Kind.EXECUTE, plugin, action.name(), params,
                hit.score(), null);
    }

    /** 纯情绪倾诉：有情绪词且没有点单动词 */
    boolean isEmotionalVent(String text) {
        boolean emotional = EMOTION.stream().anyMatch(text::contains);
        if (!emotional) {
            return false;
        }
        boolean ordering = ORDER_VERBS.stream().anyMatch(text::contains);
        return !ordering;
    }

    /**
     * 参数抽取：catalog 别名最长匹配 → enum 值匹配。
     * 正则槽位（manifest 的 param.pattern）在 M1 不启用，避免作者写错正则拖慢主链路。
     */
    private Map<String, String> extractParams(InstalledPlugin plugin, PluginManifest.Action action,
                                              String text) {
        Map<String, String> params = new LinkedHashMap<>();
        PluginManifest.Catalog catalog = plugin.manifest().catalog();
        if (catalog != null && catalog.entries() != null && action.params() != null) {
            String bestValue = null;
            int bestLen = 0;
            for (PluginManifest.Entry entry : catalog.entries()) {
                if (entry == null || entry.value() == null) {
                    continue;
                }
                List<String> names = new java.util.ArrayList<>();
                names.add(entry.value());
                if (entry.aliases() != null) {
                    names.addAll(entry.aliases());
                }
                for (String name : names) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    if (text.contains(name) && name.length() > bestLen) {
                        bestLen = name.length();
                        bestValue = entry.value();
                    }
                }
            }
            if (bestValue != null) {
                String key = catalog.key() == null ? "item" : catalog.key();
                if (action.params().containsKey(key)) {
                    params.put(key, bestValue);
                }
            }
        }
        if (action.params() != null) {
            action.params().forEach((name, p) -> {
                if (p == null || params.containsKey(name)) {
                    return;
                }
                if ("enum".equals(p.type()) && p.values() != null) {
                    for (String v : p.values()) {
                        if (v != null && text.contains(v)) {
                            params.put(name, v);
                            return;
                        }
                    }
                }
                if (p.pattern() != null && !p.pattern().isBlank()) {
                    try {
                        Matcher m = Pattern.compile(p.pattern()).matcher(text);
                        if (m.find()) {
                            params.put(name, m.groupCount() >= 1 ? m.group(1) : m.group(0));
                        }
                    } catch (Exception e) {
                        log.debug("参数 {} 的正则无效: {}", name, e.getMessage());
                    }
                }
            });
        }
        return params;
    }

    private String fillHint(InstalledPlugin plugin, List<String> missing) {
        String intent = plugin.manifest().intent() == null ? "" : plugin.manifest().intent().id();
        if ("music.sing".equals(intent) || "music.play".equals(intent)) {
            return "想听哪首呀？";
        }
        if (missing.contains("when") || missing.contains("time")) {
            return "什么时候呀？";
        }
        return "你再说具体点，我没太听明白。";
    }
}
