package com.qqbot.memory.evolve;

import com.qqbot.memory.Db;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 7 天摘要：给 prompt 插件一个不泄露原始聊天、但足够具体的「本周」。 */
@Service
public class MemoryDigestService {

    private static final int MAX = 500;
    private final MemoryStore memories;
    private final Db db;

    public MemoryDigestService(MemoryStore memories, Db db) {
        this.memories = memories;
        this.db = db;
    }

    public String digest(long qqId) {
        List<String> parts = new ArrayList<>();
        try {
            List<Map<String, Object>> episodes = memories.recentEpisodes(qqId, 5);
            for (Map<String, Object> ep : episodes) {
                Object day = ep.get("day");
                Object summary = ep.get("summary");
                if (summary != null && !String.valueOf(summary).isBlank()) {
                    parts.add("· " + day + "：" + cut(String.valueOf(summary), 80));
                }
            }
        } catch (Exception ignored) {
            // 摘要失败不影响生成
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        try {
            for (Memory m : memories.active(qqId)) {
                if (m.source() == null || !m.source().startsWith("reflection:")) {
                    continue;
                }
                if (m.firstSeen() == null || MemoryStore.daysSince(m.firstSeen()) > 7) {
                    continue;
                }
                if (!seen.add(m.id())) {
                    continue;
                }
                String label = m.key() == null ? "" : m.key()
                        .replaceFirst("^洞察·", "").replaceFirst("^经验·", "");
                notes.add("· " + cut(label, 16) + "：" + cut(m.value(), 70));
                if (notes.size() >= 5) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 记忆读取失败不影响生成
        }
        if (!notes.isEmpty()) {
            parts.add("最近的洞察/经验：");
            parts.addAll(notes);
        }

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content, status FROM growth_notes WHERE qq_id=? "
                             + "AND created_at >= datetime('now','localtime','-7 days') "
                             + "ORDER BY id DESC LIMIT 3")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> growth = new ArrayList<>();
                while (rs.next()) {
                    growth.add("· " + cut(rs.getString(1), 70)
                            + ("approved".equals(rs.getString(2)) ? "（已采纳）" : "（待审）"));
                }
                if (!growth.isEmpty()) {
                    parts.add("成长候选：");
                    parts.addAll(growth);
                }
            }
        } catch (Exception ignored) {
            // 成长读取失败不影响
        }

        try {
            List<Memory> due = memories.dueFollowUps(qqId, LocalDateTime.now());
            if (!due.isEmpty()) {
                parts.add("还没跟进的：");
                for (int i = 0; i < Math.min(3, due.size()); i++) {
                    parts.add("· " + cut(due.get(i).value(), 60));
                }
            }
        } catch (Exception ignored) {
            // 跟进读取失败不影响
        }

        if (parts.isEmpty()) {
            return "这周没有太多可回顾的内容，可以写短一点、轻一点。";
        }
        return cut(String.join("\n", parts), MAX);
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
