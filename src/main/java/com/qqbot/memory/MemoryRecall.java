package com.qqbot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆 v2 的被动回忆（检索）。
 *
 * <p>不再把全部事实塞进 prompt，而是按当前这句话检索：
 * score = 相关度×0.5 + 重要度×0.3 + 新鲜度×0.2，取 top 8~12 条、总长 ≤1200 字。
 *
 * <p>检索纯本地计算，不花 token。命中时按三块注入：
 * 【关于对方】profile ·【你们之间】relation/world ·【最近发生】event。
 * 完全没命中时只带最近 1~2 条事件（再退化为几条长期属性）。
 */
@Component
public class MemoryRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecall.class);
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_ITEMS = 12;
    private static final int MIN_ITEMS = 8;
    private static final int MAX_CHARS = 1200;
    private static final int MAX_LINE_CHARS = 90;
    /** 置信度 <0.6 的记忆不注入（设计稿第 10 节） */
    private static final double MIN_CONFIDENCE = 0.6;
    /** 新鲜度半衰期（天） */
    private static final double FRESH_HALF_LIFE = 30.0;

    private final MemoryStore store;
    /** 已做过 facts 迁移的 (账号:QQ)，懒迁移只跑一次 */
    private final Set<String> migrated = ConcurrentHashMap.newKeySet();

    public MemoryRecall(MemoryStore store) {
        this.store = store;
    }

    /** 检索结果：记忆 + 各分项，便于后台/日志核对 */
    public record Scored(Memory memory, double relevance, double freshness, double score) {
    }

    // ---------------- 对外 ----------------

    /**
     * 检索一段对话可用的记忆。
     *
     * @param userText 用户当前这句话
     * @param limit    最多返回条数（8~12）
     */
    public List<Scored> recall(long qqId, String userText, int limit) {
        int cap = Math.max(1, Math.min(MAX_ITEMS, limit <= 0 ? MAX_ITEMS : limit));
        ensureMigrated(qqId);

        List<Memory> all;
        try {
            all = store.active(qqId);
        } catch (Exception e) {
            log.warn("读取记忆失败 qqId={}: {}", qqId, e.getMessage());
            return List.of();
        }
        if (all.isEmpty()) {
            return List.of();
        }

        Set<String> tokens = tokens(userText);
        List<Scored> scored = new ArrayList<>();
        double bestRelevance = 0;
        for (Memory m : all) {
            if (m.confidence() < MIN_CONFIDENCE) {
                continue;
            }
            double rel = relevance(tokens, m);
            bestRelevance = Math.max(bestRelevance, rel);
            double fresh = freshness(m.lastConfirmed());
            double score = 0.5 * rel + 0.3 * clamp(m.importance()) + 0.2 * fresh;
            scored.add(new Scored(m, rel, fresh, score));
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        if (bestRelevance <= 0) {
            return noHitFallback(qqId, scored);
        }

        List<Scored> picked = new ArrayList<>();
        int chars = 0;
        for (Scored s : scored) {
            if (picked.size() >= cap) {
                break;
            }
            int len = lineLength(s.memory());
            if (!picked.isEmpty() && chars + len > MAX_CHARS) {
                break;
            }
            picked.add(s);
            chars += len;
        }
        // 命中太少时按分数补齐到 8 条，但同样受 1200 字限制
        if (picked.size() < MIN_ITEMS) {
            for (Scored s : scored) {
                if (picked.size() >= MIN_ITEMS) {
                    break;
                }
                if (picked.contains(s)) {
                    continue;
                }
                int len = lineLength(s.memory());
                if (chars + len > MAX_CHARS) {
                    break;
                }
                picked.add(s);
                chars += len;
            }
        }
        return picked;
    }

    /** 拼成 prompt 里的三块；没有可用记忆时返回空串 */
    public String promptBlock(long qqId, String userText) {
        List<Scored> picked;
        try {
            picked = recall(qqId, userText, MAX_ITEMS);
        } catch (Exception e) {
            log.warn("记忆检索失败 qqId={}: {}", qqId, e.getMessage());
            return "";
        }
        if (picked.isEmpty()) {
            return "";
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("【关于对方】", new ArrayList<>());
        groups.put("【你们之间】", new ArrayList<>());
        groups.put("【最近发生】", new ArrayList<>());
        for (Scored s : picked) {
            String line = format(s.memory());
            if (line.isEmpty()) {
                continue;
            }
            groups.get(groupOf(s.memory().type())).add(line);
        }
        StringBuilder sb = new StringBuilder();
        groups.forEach((title, lines) -> {
            if (lines.isEmpty()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(title).append('\n');
            for (String line : lines) {
                sb.append("- ").append(line).append('\n');
            }
        });
        if (sb.length() == 0) {
            return "";
        }
        sb.append("以上是印象，自然带入对话，不要逐条复述，也不要说「根据我的记忆」。");
        return sb.toString();
    }

    /** 后台/验收用：返回检索明细 */
    public List<Map<String, Object>> explain(long qqId, String userText, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored s : recall(qqId, userText, limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Memory mem = s.memory();
            m.put("id", mem.id());
            m.put("type", mem.type());
            m.put("key", mem.key());
            m.put("value", mem.value());
            m.put("importance", mem.importance());
            m.put("confidence", mem.confidence());
            m.put("lastConfirmed", mem.lastConfirmed());
            m.put("relevance", round(s.relevance()));
            m.put("freshness", round(s.freshness()));
            m.put("score", round(s.score()));
            out.add(m);
        }
        return out;
    }

    // ---------------- 打分 ----------------

    /** 相关度：当前这句话的二元组/关键词被这条记忆覆盖的比例 */
    static double relevance(Set<String> tokens, Memory m) {
        if (tokens.isEmpty()) {
            return 0;
        }
        String haystack = (m.key() == null ? "" : m.key()) + " " + (m.value() == null ? "" : m.value());
        int hit = 0;
        for (String t : tokens) {
            if (haystack.contains(t)) {
                hit++;
            }
        }
        if (hit == 0) {
            return 0;
        }
        double ratio = (double) hit / tokens.size();
        // key 命中说明就是同一件事，额外加权
        String baseKey = m.baseKey();
        if (!baseKey.isEmpty() && containsAnyKey(tokens, baseKey)) {
            ratio = Math.min(1.0, ratio * 1.4);
        }
        return Math.min(1.0, ratio * 2.2);
    }

    private static boolean containsAnyKey(Set<String> tokens, String key) {
        for (String t : tokens) {
            if (key.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 新鲜度：指数衰减，今天≈1，30 天≈0.5 */
    static double freshness(String lastConfirmed) {
        LocalDateTime t = parse(lastConfirmed);
        if (t == null) {
            return 0.5;
        }
        long days = Math.max(0, Duration.between(t, LocalDateTime.now()).toDays());
        return Math.exp(-Math.log(2) * days / FRESH_HALF_LIFE);
    }

    /**
     * 分词：中文按二元组 + 整段短词，英文/数字按词。
     * 例：「我昨天加班到十点」→ 我昨/昨天/天加/加班/班到/到十/十点 …
     */
    static Set<String> tokens(String text) {
        Set<String> set = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return set;
        }
        for (String seg : text.split("[\\s\\p{Punct}，。！？、；：「」『』（）【】…—]+")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.matches("[A-Za-z0-9]+")) {
                if (seg.length() >= 2) {
                    set.add(seg.toLowerCase());
                }
                continue;
            }
            if (seg.length() == 1) {
                continue;
            }
            for (int i = 0; i + 1 < seg.length(); i++) {
                set.add(seg.substring(i, i + 2));
            }
            if (seg.length() <= 4) {
                set.add(seg);
            }
        }
        return set;
    }

    // ---------------- 内部 ----------------

    private List<Scored> noHitFallback(long qqId, List<Scored> scored) {
        List<Scored> out = new ArrayList<>();
        for (Memory m : store.recentEvents(qqId, 7, 2)) {
            out.add(new Scored(m, 0, freshness(m.lastConfirmed()),
                    0.5 * 0 + 0.3 * clamp(m.importance()) + 0.2 * freshness(m.lastConfirmed())));
        }
        if (!out.isEmpty()) {
            return out;
        }
        // 连最近事件都没有：给几条最稳定的长期属性，别让模型完全不知道对方是谁
        for (Scored s : scored) {
            if (out.size() >= 3) {
                break;
            }
            if ("profile".equals(s.memory().type()) || "relation".equals(s.memory().type())) {
                out.add(s);
            }
        }
        return out;
    }

    private void ensureMigrated(long qqId) {
        Long botId = com.qqbot.bot.BotContext.get();
        String key = (botId == null ? "?" : botId) + ":" + qqId;
        if (!migrated.add(key)) {
            return;
        }
        try {
            store.migrateFromFacts(qqId);
        } catch (Exception e) {
            log.warn("facts 懒迁移失败 qqId={}: {}", qqId, e.getMessage());
        }
    }

    static String groupOf(String type) {
        if (type == null) {
            return "【关于对方】";
        }
        return switch (type) {
            case "relation", "world" -> "【你们之间】";
            case "event" -> "【最近发生】";
            default -> "【关于对方】";
        };
    }

    private String format(Memory m) {
        String value = m.value() == null ? "" : m.value().trim();
        if (value.isEmpty()) {
            return "";
        }
        String key = m.baseKey();
        String line = (key.isEmpty() || value.contains(key)) ? value : key + "：" + value;
        if ("event".equals(m.type())) {
            LocalDateTime t = parse(m.lastConfirmed());
            if (t != null) {
                line = t.format(MD) + " " + line;
            }
        }
        return truncate(line, MAX_LINE_CHARS);
    }

    private static int lineLength(Memory m) {
        int n = (m.key() == null ? 0 : m.baseKey().length()) + (m.value() == null ? 0 : m.value().length()) + 8;
        return Math.min(n, MAX_LINE_CHARS + 8);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), TS);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
