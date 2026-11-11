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
    private static final int MAX_CHARS = 1200;
    private static final int MAX_LINE_CHARS = 90;
    /** 置信度 <0.6 的记忆不注入（设计稿第 10 节） */
    private static final double MIN_CONFIDENCE = 0.6;
    /** 新鲜度半衰期（天） */
    private static final double FRESH_HALF_LIFE = 30.0;

    /** 语义召回阈值：bge-small 的无关句通常在 0.3 左右，0.45 以上才当命中 */
    private static final double VECTOR_MIN = 0.45;

    private final MemoryStore store;
    private final MemoryVectorService vectors;
    /** 已做过 facts 迁移的 (账号:QQ)，懒迁移只跑一次 */
    private final Set<String> migrated = ConcurrentHashMap.newKeySet();

    /** 测试用：不接向量服务，只走关键词 */
    MemoryRecall(MemoryStore store) {
        this(store, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryRecall(MemoryStore store, MemoryVectorService vectors) {
        this.store = store;
        this.vectors = vectors;
    }

    /** 检索结果：记忆 + 各分项，便于后台/日志核对 */
    public record Scored(Memory memory, double relevance, double strength, double score) {
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
        // 语义向量：失败/超时就是 null，自动退回纯关键词
        Map<Long, float[]> vecMap = Map.of();
        float[] queryVec = null;
        if (vectors != null) {
            try {
                queryVec = vectors.queryVector(userText);
                if (queryVec != null) {
                    vecMap = vectors.vectorsOf(qqId);
                }
            } catch (Exception e) {
                log.debug("向量召回不可用，退回关键词: {}", e.getMessage());
                queryVec = null;
                vecMap = Map.of();
            }
        }
        LocalDateTime nowT = LocalDateTime.now();
        List<Scored> scored = new ArrayList<>();
        for (Memory m : all) {
            if (m.confidence() < MIN_CONFIDENCE) {
                continue;
            }
            // 已经忘得差不多了就不再注入：被召回也算一次「想起来」，但只回一点点
            double strength = MemoryStrength.effective(m, nowT);
            if (MemoryStrength.forgotten(strength)) {
                continue;
            }
            double rel = relevance(tokens, m);
            if (queryVec != null && !vecMap.isEmpty()) {
                float[] mv = vecMap.get(m.id());
                double cos = MemoryVectorService.cosine(queryVec, mv);
                rel = blend(rel, cos);
            }
            if (rel <= 0) {
                continue; // 只收真命中，不再凑数
            }
            double score = 0.45 * rel + 0.35 * strength + 0.20 * clamp(m.importance());
            // 旧的负面事件：还能被召回，但降权，避免它抢到话题主导权
            if (m.emotion() <= -0.35 && daysSince(m.lastConfirmed()) > 5) {
                score *= 0.8;
            }
            scored.add(new Scored(m, rel, strength, score));
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Scored> picked = new ArrayList<>();
        int chars = 0;
        for (Scored s : scored) {
            if (picked.size() >= cap) {
                break;
            }
            if (isNearDuplicate(s, picked)) {
                continue; // 「情绪低落 / 情绪状态 / 近况」这种同一件事只留一条
            }
            int len = lineLength(s.memory());
            if (!picked.isEmpty() && chars + len > MAX_CHARS) {
                break;
            }
            picked.add(s);
            chars += len;
        }
        // 复习效应的被动版：这次真被想起来（会注入 prompt）的记忆，强度小幅回升
        store.onRecalled(picked.stream().map(s -> s.memory().id()).toList(), nowT);
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
        sb.append("以上只是背景：对方不提起，就别主动拿出来聊，尤其别翻旧矛盾；对方问起再自然接。");
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
            m.put("strength", round(s.strength()));
            m.put("freshness", round(freshness(mem.lastConfirmed())));
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

    /** 值几乎一样的记忆只保留分数最高的一条 */
    private static boolean isNearDuplicate(Scored candidate, List<Scored> picked) {
        String a = candidate.memory().value();
        if (a == null || a.isBlank()) {
            return false;
        }
        java.util.Set<String> sa = bigramSet(a);
        if (sa.isEmpty()) {
            return false;
        }
        for (Scored p : picked) {
            String b = p.memory().value();
            if (b == null || b.isBlank()) {
                continue;
            }
            java.util.Set<String> sb = bigramSet(b);
            if (sb.isEmpty()) {
                continue;
            }
            java.util.Set<String> inter = new java.util.HashSet<>(sa);
            inter.retainAll(sb);
            java.util.Set<String> union = new java.util.HashSet<>(sa);
            union.addAll(sb);
            if ((double) inter.size() / union.size() >= 0.6) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> bigramSet(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        String clean = s.replaceAll("[\\p{Punct}\\s，。！？、；：]", "");
        for (int i = 0; i + 1 < clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        return set;
    }

    static long daysSince(String ts) {
        LocalDateTime t = parse(ts);
        return t == null ? 0 : Math.max(0, java.time.Duration.between(t, LocalDateTime.now()).toDays());
    }

    /** 关键词相关度和语义相似度融合；余弦太低视为无贡献 */
    static double blend(double keyword, double cosine) {
        if (cosine < VECTOR_MIN) {
            return keyword;
        }
        return Math.max(keyword, 0.5 * keyword + 0.5 * cosine);
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
