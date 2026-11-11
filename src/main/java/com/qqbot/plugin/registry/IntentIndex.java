package com.qqbot.plugin.registry;

import com.qqbot.plugin.model.PluginManifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 意图倒排索引（零 token 的第一段路由，设计文档 §8.2）。
 *
 * <p>L1 全等、L2 包含。3 字示例默认只做全等（v2.2 N1），避免短句在正常聊天里到处命中；
 * 但以请求动词开头的 3 字示例（来/唱/放/给/帮/查/算/讲/画/提醒）允许包含匹配——
 * 否则「来一段周杰伦的」这种最自然的点歌说法会漏掉。第三人称（他/她）仍然只做全等，
 * 避免“他昨天唱首歌就走了”误触发。
 */
public final class IntentIndex {

    public record Entry(String pluginId, String intentId, String example, int length) {
    }

    public record Hit(String pluginId, String intentId, int level, double score, String matched) {
    }

    private final List<Entry> entries;

    private IntentIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public static IntentIndex empty() {
        return new IntentIndex(List.of());
    }

    public int size() {
        return entries.size();
    }

    /** 请求动词开头：这类 3 字示例允许包含匹配 */
    private static final java.util.List<String> COMMAND_PREFIX = java.util.List.of(
            "来", "唱", "放", "给", "帮", "查", "算", "讲", "画", "提醒", "想听", "能不能");

    private static boolean isCommandPhrase(String example) {
        for (String p : COMMAND_PREFIX) {
            if (example.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static IntentIndex build(List<InstalledPlugin> plugins) {
        List<Entry> list = new ArrayList<>();
        for (InstalledPlugin p : plugins) {
            if (!p.usable() || p.manifest() == null || p.manifest().intent() == null) {
                continue;
            }
            PluginManifest.Intent intent = p.manifest().intent();
            if (intent.id() == null || intent.examples() == null) {
                continue;
            }
            for (String e : intent.examples()) {
                if (e == null) {
                    continue;
                }
                String t = e.trim();
                if (t.length() >= 3) {
                    list.add(new Entry(p.pluginId(), intent.id(), t, t.length()));
                }
            }
        }
        return new IntentIndex(List.copyOf(list));
    }

    /** 返回全部命中（一个插件只保留它的最高分那条），按分数降序 */
    public List<Hit> match(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = text.trim();
        List<Hit> hits = new ArrayList<>();
        for (Entry e : entries) {
            if (t.equals(e.example())) {
                hits.add(new Hit(e.pluginId(), e.intentId(), 1, 1.0, e.example()));
                continue;
            }
            boolean command3 = e.length() == 3 && isCommandPhrase(e.example())
                    && t.length() <= 20 && !t.contains("他") && !t.contains("她");
            if ((e.length() >= 4 || command3) && t.contains(e.example())) {
                double score = command3 ? 0.85
                        : Math.min(0.9, 0.80 + 0.02 * (e.length() - 4));
                hits.add(new Hit(e.pluginId(), e.intentId(), 2, score, e.example()));
            }
        }
        // 每个插件只留最高分
        List<Hit> best = new ArrayList<>();
        for (Hit h : hits) {
            Hit existing = null;
            for (Hit b : best) {
                if (b.pluginId().equals(h.pluginId())) {
                    existing = b;
                }
            }
            if (existing == null) {
                best.add(h);
            } else if (h.score() > existing.score()) {
                best.remove(existing);
                best.add(h);
            }
        }
        best.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> -h.matched().length()));
        return best;
    }
}
