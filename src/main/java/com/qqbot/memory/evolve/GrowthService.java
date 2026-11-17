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
 * 成长档案：她和你相处学到的「一句话经验」。
 *
 * <p>只追加、不覆盖人格卡；必须人工采纳（approved）才会进 prompt，最多 3 条 / 120 字。</p>
 */
@Service
public class GrowthService {

    private static final Logger log = LoggerFactory.getLogger(GrowthService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ENABLED_KEY = "memory.evolve.enabled";

    private final Db db;
    private final SettingStore settings;

    public GrowthService(Db db, SettingStore settings) {
        this.db = db;
        this.settings = settings;
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.get(ENABLED_KEY, "true"));
    }

    public long addPending(long qqId, String content, String evidenceJson) {
        if (content == null || content.isBlank()) {
            return -1;
        }
        String normalized = cut(content, 60);
        if (exists(qqId, normalized) || similarExists(qqId, normalized)) {
            return -1;
        }
        String sql = "INSERT INTO growth_notes(qq_id, content, evidence, status, created_at) VALUES(?,?,?,'pending',?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, normalized);
            ps.setString(3, evidenceJson);
            ps.setString(4, LocalDateTime.now().format(TS));
            ps.executeUpdate();
            return 1;
        } catch (Exception e) {
            log.warn("写入成长候选失败: {}", e.getMessage());
            return -1;
        }
    }

    public List<Map<String, Object>> list(long qqId, String status, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, content, evidence, status, created_at, reviewed_at FROM growth_notes "
                + "WHERE qq_id=?" + (status == null || status.isBlank() ? "" : " AND status=?")
                + " ORDER BY id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, qqId);
            if (status != null && !status.isBlank()) {
                ps.setString(i++, status);
            }
            ps.setInt(i, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("content", rs.getString("content"));
                    m.put("evidence", rs.getString("evidence"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("reviewedAt", rs.getString("reviewed_at"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("读取成长档案失败: {}", e.getMessage());
        }
        return out;
    }

    public int review(long id, boolean approve) {
        return update("UPDATE growth_notes SET status=?, reviewed_at=? WHERE id=?",
                approve ? "approved" : "rejected", LocalDateTime.now().format(TS), id);
    }

    /** 注入 prompt 的成长条：approved、最多 3 条、总长 ≤120 字。 */
    public String approvedBlock(long qqId) {
        if (!enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        String sql = "SELECT content FROM growth_notes WHERE qq_id=? AND status='approved' "
                + "ORDER BY reviewed_at DESC, id DESC LIMIT 3";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString(1);
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    if (chars + content.length() > 120) {
                        break;
                    }
                    sb.append("- ").append(content).append('\n');
                    chars += content.length();
                }
            }
        } catch (Exception e) {
            log.debug("读取成长档案失败: {}", e.getMessage());
        }
        return sb.length() == 0 ? "" : "【你学到的相处方式】\n" + sb;
    }

    /** 近似重复也算重复：模型换个说法不该每天多一条。 */
    private boolean similarExists(long qqId, String content) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content FROM growth_notes WHERE qq_id=? AND status IN ('pending','approved')")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (similar(content, rs.getString(1))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean similar(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 1 < a.length(); i++) {
            total++;
            if (b.contains(a.substring(i, i + 2))) {
                hit++;
            }
        }
        return total > 0 && (double) hit / total >= 0.6;
    }

    private boolean exists(long qqId, String content) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM growth_notes WHERE qq_id=? AND content=? "
                             + "AND status IN ('pending','approved') LIMIT 1")) {
            ps.setLong(1, qqId);
            ps.setString(2, content);
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
            log.warn("成长档案写操作失败: {}", e.getMessage());
            return 0;
        }
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
