package com.qqbot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆星图的数据层：把记忆摊成「点 + 边」。
 *
 * <p>两种视角：单个用户（{@link #graph}）和**整个账号下所有聊过天的人**（{@link #graphAll}，
 * 后台默认）。节点带 qqId 和昵称，前端可以按人筛选。
 *
 * <p>边的三种来源（都只在同一个用户内部连，避免把两个陌生人的记忆连成一片）：
 * <ul>
 *   <li>subject：同一个人的记忆连到该人的「锚点」（最重要的一条），w 固定 0.45；</li>
 *   <li>baseKey：同一件事发生过两次，w 0.6；</li>
 *   <li>vector：余弦 ≥0.55 的前 3 条，w=余弦。</li>
 * </ul>
 *
 * <p>只读不写，供 {@code GET /api/memories/graph} 使用。
 */
@Service
public class MemoryGraphService {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphService.class);

    private static final double VECTOR_MIN = 0.55;
    private static final int VECTOR_TOP = 3;
    private static final int SUBJECT_EDGE_MAX = 8;

    private final MemoryStore store;
    private final MemoryVectorService vectors;
    private final Db db;

    public MemoryGraphService(MemoryStore store, MemoryVectorService vectors, Db db) {
        this.store = store;
        this.vectors = vectors;
        this.db = db;
    }

    /** 单个用户 */
    public Map<String, Object> graph(long qqId, boolean includeArchived) {
        List<Memory> list = includeArchived ? store.all(qqId) : store.active(qqId);
        return build(list);
    }

    /** 这个账号下所有聊过天的人（后台默认视角） */
    public Map<String, Object> graphAll(boolean includeArchived) {
        return build(store.allUsers(includeArchived));
    }

    /** 星图顶部的「按人筛选」下拉：每人多少条 */
    public List<Map<String, Object>> users() {
        Map<Long, String> names = names();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : store.userStats()) {
            long qq = Long.parseLong(String.valueOf(row.get("qqId")));
            row.put("name", names.getOrDefault(qq, String.valueOf(qq)));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> build(List<Memory> memories) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, String> names = names();

        List<Map<String, Object>> nodes = new ArrayList<>(memories.size());
        Map<Long, Double> strengthById = new HashMap<>();
        int fading = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Set<Long> userIds = new HashSet<>();
        for (Memory m : memories) {
            double strength = m.pinned() ? 1.0 : MemoryStrength.effective(m, now);
            strengthById.put(m.id(), strength);
            userIds.add(m.qqId());
            if (!m.pinned() && MemoryStrength.fading(strength) && "active".equals(m.status())) {
                fading++;
            }
            byType.merge(m.type() == null ? "profile" : m.type(), 1, Integer::sum);
            nodes.add(node(m, strength, names.getOrDefault(m.qqId(), String.valueOf(m.qqId()))));
        }

        List<Map<String, Object>> edges = edges(memories, strengthById);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", memories.size());
        meta.put("fading", fading);
        meta.put("pinned", memories.stream().filter(Memory::pinned).count());
        meta.put("users", userIds.size());
        meta.put("byType", byType);
        meta.put("edges", edges.size());
        meta.put("generatedAt", System.currentTimeMillis());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        body.put("edges", edges);
        body.put("meta", meta);
        body.put("layers", layers());
        return body;
    }

    /** qqId → 展示名：优先备注，其次昵称，都没有就用 QQ 号 */
    private Map<Long, String> names() {
        Map<Long, String> out = new HashMap<>();
        String sql = "SELECT qq_id, remark, nickname FROM friend_profile";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String remark = rs.getString(2);
                String nick = rs.getString(3);
                String name = remark != null && !remark.isBlank() ? remark
                        : (nick != null && !nick.isBlank() ? nick : null);
                if (name != null) {
                    out.put(rs.getLong(1), name);
                }
            }
        } catch (Exception e) {
            log.debug("读取好友昵称失败: {}", e.getMessage());
        }
        return out;
    }

    /** 前端图例：层 → 名字/颜色，保持和画布一致。 */
    public static List<Map<String, Object>> layers() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(layer("relation", "你们之间", "#ff8fb8"));
        out.add(layer("profile", "关于对方", "#7ee8fa"));
        out.add(layer("event", "最近发生", "#9dffc9"));
        out.add(layer("world", "设定", "#c9a7ff"));
        return out;
    }

    private static Map<String, Object> layer(String type, String label, String color) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("label", label);
        m.put("color", color);
        return m;
    }

    private Map<String, Object> node(Memory m, double strength, String name) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", String.valueOf(m.id()));
        n.put("qqId", String.valueOf(m.qqId()));
        n.put("name", name);
        n.put("type", m.type());
        n.put("subject", m.subject());
        n.put("key", m.baseKey());
        n.put("value", m.value());
        n.put("importance", m.importance());
        n.put("confidence", m.confidence());
        n.put("emotion", m.emotion());
        n.put("strength", round(strength));
        n.put("pinned", m.pinned());
        n.put("status", m.status());
        n.put("recallCount", m.recallCount());
        n.put("firstSeen", m.firstSeen());
        n.put("lastConfirmed", m.lastConfirmed());
        n.put("fading", !m.pinned() && MemoryStrength.fading(strength));
        n.put("recurMd", m.recurMd());
        return n;
    }

    private List<Map<String, Object>> edges(List<Memory> memories, Map<Long, Double> strengthById) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) 同一个人（qqId + subject）→ 连到锚点
        Map<String, List<Memory>> bySubject = new HashMap<>();
        for (Memory m : memories) {
            String key = m.qqId() + "|" + (m.subject() == null ? "user" : m.subject());
            bySubject.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        for (List<Memory> group : bySubject.values()) {
            if (group.size() < 2) {
                continue;
            }
            Memory anchor = group.stream()
                    .max(Comparator.comparingDouble(m -> strengthById.getOrDefault(m.id(), 0.0)
                            + 0.3 * m.importance()))
                    .orElse(group.get(0));
            int added = 0;
            for (Memory m : group) {
                if (m.id() == anchor.id()) {
                    continue;
                }
                if (add(out, seen, m.id(), anchor.id(), 0.45, "subject")) {
                    added++;
                }
                if (added >= SUBJECT_EDGE_MAX) {
                    break;
                }
            }
        }

        // 2) 同一个人、同一个 baseKey：同一件事发生过不止一次
        Map<String, List<Memory>> byKey = new HashMap<>();
        for (Memory m : memories) {
            if (m.baseKey() != null && !m.baseKey().isBlank()) {
                byKey.computeIfAbsent(m.qqId() + "|" + m.baseKey(), k -> new ArrayList<>()).add(m);
            }
        }
        for (List<Memory> group : byKey.values()) {
            for (int i = 1; i < group.size() && i <= 4; i++) {
                add(out, seen, group.get(0).id(), group.get(i).id(), 0.6, "topic");
            }
        }

        // 3) 向量近邻（只在同一个用户内部找，避免把 A 的隐私连到 B）
        Map<Long, float[]> vecs;
        try {
            vecs = vectors == null ? Map.of() : vectors.vectorsAll();
        } catch (Exception e) {
            log.debug("向量不可用，星图只连结构边: {}", e.getMessage());
            vecs = Map.of();
        }
        if (vecs.size() > 1) {
            for (Memory m : memories) {
                float[] a = vecs.get(m.id());
                if (a == null) {
                    continue;
                }
                List<double[]> scored = new ArrayList<>();
                for (Memory other : memories) {
                    if (other.id() == m.id() || other.qqId() != m.qqId()) {
                        continue;
                    }
                    float[] b = vecs.get(other.id());
                    if (b == null) {
                        continue;
                    }
                    double cos = MemoryVectorService.cosine(a, b);
                    if (cos >= VECTOR_MIN) {
                        scored.add(new double[]{cos, other.id()});
                    }
                }
                scored.sort((x, y) -> Double.compare(y[0], x[0]));
                for (int i = 0; i < Math.min(VECTOR_TOP, scored.size()); i++) {
                    add(out, seen, m.id(), (long) scored.get(i)[1], round(scored.get(i)[0]), "vector");
                }
            }
        }
        return out;
    }

    private static boolean add(List<Map<String, Object>> out, Set<String> seen,
                               long a, long b, double w, String kind) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        String key = lo + "-" + hi;
        if (!seen.add(key)) {
            return false;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("a", String.valueOf(lo));
        e.put("b", String.valueOf(hi));
        e.put("w", round(w));
        e.put("kind", kind);
        out.add(e);
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
