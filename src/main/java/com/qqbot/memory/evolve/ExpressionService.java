package com.qqbot.memory.evolve;

import com.qqbot.memory.Db;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达方式库：她「以前这样说过」的句子，按场景检索后作为语气参考注入。
 *
 * <p>只学被对方接住的句子；默认 pending，人工审核后才生效；每条 ≤60 字。</p>
 */
@Service
public class ExpressionService {

    private static final Logger log = LoggerFactory.getLogger(ExpressionService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ENABLED_KEY = "memory.evolve.enabled";

    private final Db db;
    private final SettingStore settings;

    public ExpressionService(Db db, SettingStore settings) {
        this.db = db;
        this.settings = settings;
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.get(ENABLED_KEY, "true"));
    }

    public long addPending(long qqId, String situation, String text) {
        if (situation == null || situation.isBlank() || text == null || text.isBlank()) {
            return -1;
        }
        String s = cut(situation, 30);
        String t = cut(text, 60);
        if (exists(qqId, s, t)) {
            return -1;
        }
        String sql = "INSERT INTO expressions(qq_id, situation, text, source, score, status, created_at) "
                + "VALUES(?,?,?,'chat',0,'pending',?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, s);
            ps.setString(3, t);
            ps.setString(4, LocalDateTime.now().format(TS));
            ps.executeUpdate();
            return 1;
        } catch (Exception e) {
            log.warn("写入表达方式失败: {}", e.getMessage());
            return -1;
        }
    }

    public List<Map<String, Object>> list(long qqId, String status, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, situation, text, source, score, status, use_count, last_used, created_at "
                + "FROM expressions WHERE qq_id=?"
                + (status == null || status.isBlank() ? "" : " AND status=?")
                + " ORDER BY id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, qqId);
            if (status != null && !status.isBlank()) {
                ps.setString(i++, status);
            }
            ps.setInt(i, Math.max(1, Math.min(200, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("situation", rs.getString("situation"));
                    m.put("text", rs.getString("text"));
                    m.put("source", rs.getString("source"));
                    m.put("score", rs.getDouble("score"));
                    m.put("status", rs.getString("status"));
                    m.put("useCount", rs.getInt("use_count"));
                    m.put("lastUsed", rs.getString("last_used"));
                    m.put("createdAt", rs.getString("created_at"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("读取表达方式失败: {}", e.getMessage());
        }
        return out;
    }

    public int review(long id, boolean approve) {
        return update("UPDATE expressions SET status=? WHERE id=?", approve ? "approved" : "rejected", id);
    }

    /** 按当前这句话挑 1–2 条 approved 说法；只做字面/二元组重叠，不花模型。 */
    public List<Map<String, Object>> suggest(long qqId, String userText, int limit) {
        if (!enabled() || userText == null || userText.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> all = list(qqId, "approved", 80);
        if (all.isEmpty()) {
            return List.of();
        }
        List<String> query = grams(userText);
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> item : all) {
            String situation = String.valueOf(item.get("situation"));
            double score = overlap(query, grams(situation));
            if (score > 0) {
                item.put("match", score);
                scored.add(item);
            }
        }
        scored.sort((a, b) -> Double.compare(
                ((Number) b.get("match")).doubleValue(), ((Number) a.get("match")).doubleValue()));
        List<Map<String, Object>> out = scored.subList(0, Math.min(Math.max(1, limit), scored.size()));
        if (!out.isEmpty()) {
            markUsed(out);
        }
        return out;
    }

    /** 回复前注入：最多 2 条，总长 ≤100 字。 */
    public String block(long qqId, String userText) {
        List<Map<String, Object>> items = suggest(qqId, userText, 2);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (Map<String, Object> item : items) {
            String text = String.valueOf(item.get("text"));
            if (chars + text.length() > 100) {
                break;
            }
            sb.append("- ").append(text).append('\n');
            chars += text.length();
        }
        return sb.length() == 0 ? "" : "【你以前这样说过（学语气，不要照抄）】\n" + sb;
    }

    private void markUsed(List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            update("UPDATE expressions SET use_count=use_count+1, last_used=? WHERE id=?",
                    LocalDateTime.now().format(TS), item.get("id"));
        }
    }

    private boolean exists(long qqId, String situation, String text) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM expressions WHERE qq_id=? AND situation=? AND text=? LIMIT 1")) {
            ps.setLong(1, qqId);
            ps.setString(2, situation);
            ps.setString(3, text);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private int update(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("表达方式写操作失败: {}", e.getMessage());
            return 0;
        }
    }

    private static List<String> grams(String text) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text.replaceAll("[\\s，。！？、,.!?~～]", "");
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        if (out.isEmpty() && !t.isEmpty()) {
            out.add(t);
        }
        return out;
    }

    private static double overlap(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String x : a) {
            if (b.contains(x)) {
                hit++;
            }
        }
        return (double) hit / Math.max(a.size(), b.size());
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
