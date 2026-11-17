package com.qqbot.memory.evolve;

import com.qqbot.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 观测台时间线：把洞察、相处经验、成长候选、未完成跟进摊在一条时间轴上。
 * 只读。
 */
@Service
public class MemoryTimelineService {

    private static final Logger log = LoggerFactory.getLogger(MemoryTimelineService.class);

    private final Db db;

    public MemoryTimelineService(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> timeline(long qqId, int limit) {
        List<Map<String, Object>> events = new ArrayList<>();

        String insightSql = "SELECT id, key, value, source, first_seen, status FROM memories "
                + "WHERE qq_id=? AND source LIKE 'reflection:%' ORDER BY first_seen DESC LIMIT 60";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(insightSql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String source = rs.getString("source");
                    boolean lesson = source != null && source.contains("lesson");
                    events.add(event(rs.getString("first_seen"),
                            lesson ? "lesson" : "insight",
                            strip(rs.getString("key")),
                            rs.getString("value"), rs.getString("status"),
                            rs.getLong("id"), "memory"));
                }
            }
        } catch (Exception e) {
            log.debug("读取洞察时间线失败: {}", e.getMessage());
        }

        String growthSql = "SELECT id, content, status, created_at, reviewed_at FROM growth_notes "
                + "WHERE qq_id=? ORDER BY id DESC LIMIT 40";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(growthSql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String at = rs.getString("reviewed_at");
                    if (at == null || at.isBlank()) {
                        at = rs.getString("created_at");
                    }
                    events.add(event(at, "growth", "成长候选",
                            rs.getString("content"), rs.getString("status"),
                            rs.getLong("id"), "growth"));
                }
            }
        } catch (Exception e) {
            log.debug("读取成长时间线失败: {}", e.getMessage());
        }

        String followSql = "SELECT id, key, value, follow_up_at FROM memories "
                + "WHERE qq_id=? AND follow_up_state='pending' AND status='active' "
                + "ORDER BY follow_up_at ASC LIMIT 20";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(followSql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(event(rs.getString("follow_up_at"), "followup",
                            "未完成", rs.getString("value"), "open",
                            rs.getLong("id"), "memory"));
                }
            }
        } catch (Exception e) {
            log.debug("读取跟进时间线失败: {}", e.getMessage());
        }

        events.sort(Comparator.comparing((Map<String, Object> e) -> String.valueOf(e.get("at"))).reversed());
        int max = Math.max(1, Math.min(200, limit));
        return events.size() <= max ? events : new ArrayList<>(events.subList(0, max));
    }

    private static Map<String, Object> event(String at, String kind, String title,
                                             String detail, String status, long id, String refType) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at == null ? "" : at);
        e.put("kind", kind);
        e.put("title", title == null ? "" : title);
        e.put("detail", detail == null ? "" : detail);
        e.put("status", status == null ? "" : status);
        e.put("id", id);
        e.put("refType", refType);
        return e;
    }

    private static String strip(String key) {
        if (key == null) {
            return "";
        }
        return key.startsWith("洞察·") ? key.substring(3)
                : key.startsWith("经验·") ? key.substring(3) : key;
    }
}
