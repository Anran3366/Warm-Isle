package com.qqbot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实卡：关于用户的长期记忆。
 * category 与契约一致：profile / preference / relationship / event / commitment
 * status：active / pending / outdated / disputed
 */
@Component
public class FactStore {

    private static final Logger log = LoggerFactory.getLogger(FactStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PER_USER = 200;

    private final Db db;

    public FactStore(Db db) {
        this.db = db;
    }

    public List<Fact> active(long qqId) {
        return query("qq_id=? AND status='active'", new Object[]{qqId}, "category, confidence DESC");
    }

    public List<Fact> pending(long qqId) {
        return query("qq_id=? AND status='pending'", new Object[]{qqId}, "id DESC");
    }

    public List<Fact> all(long qqId) {
        return query("qq_id=?", new Object[]{qqId}, "category, id DESC");
    }

    /** 分页 + 关键词 + 类目过滤（后台记忆页用） */
    public List<Fact> page(long qqId, String category, String q, String status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(qqId, category, q, status, args);
        args.add(limit);
        args.add(offset);
        return queryRaw(where + " ORDER BY category, id DESC LIMIT ? OFFSET ?", args);
    }

    public long count(long qqId, String category, String q, String status) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(qqId, category, q, status, args);
        String sql = "SELECT COUNT(*) FROM facts WHERE " + where;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public Fact byId(long id) {
        List<Fact> list = queryRaw("id=?", List.of(id));
        return list.isEmpty() ? null : list.get(0);
    }

    public void update(long id, String category, String key, String value, double confidence) {
        String sql = "UPDATE facts SET category=?, key=?, value=?, confidence=?, last_confirmed=? WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.setDouble(4, confidence);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新事实失败: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM facts WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("删除事实失败: " + e.getMessage(), e);
        }
    }

    /** 自动审核：改状态并记录原因 */
    public void setStatusWithNote(long id, String status, String note) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE facts SET status=?, review_note=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, note);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 所有用户的待确认事实 */
    public java.util.List<Fact> pendingAll() {
        java.util.List<Fact> list = new java.util.ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, qq_id, category, key, value, confidence, status, last_confirmed "
                             + "FROM facts WHERE status='pending' ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Fact(rs.getLong("id"), rs.getLong("qq_id"), rs.getString("category"),
                        rs.getString("key"), rs.getString("value"), rs.getDouble("confidence"),
                        rs.getString("status"), rs.getString("last_confirmed")));
            }
        } catch (Exception ignored) {
            // 返回空
        }
        return list;
    }

    public void setStatus(long id, String status) {
        String now = LocalDateTime.now().format(TS);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE facts SET status=?, last_confirmed=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, now);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新状态失败: " + e.getMessage(), e);
        }
    }

    public long countActive(long qqId) {
        return countByStatus(qqId, "active");
    }

    public long countPending(long qqId) {
        return countByStatus(qqId, "pending");
    }

    public long countAllActive() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM facts WHERE status='active'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long countByStatus(long qqId, String status) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM facts WHERE qq_id=? AND status=?")) {
            ps.setLong(1, qqId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 写入或更新一条事实；低置信度落 pending */
    public boolean upsert(long qqId, String category, String key, String value, double confidence) {
        String now = LocalDateTime.now().format(TS);
        String status = confidence >= 0.7 ? "active" : "pending";

        Fact existing = find(qqId, category, key);
        if (existing != null) {
            boolean keep = "active".equals(existing.status()) && confidence + 0.15 < existing.confidence();
            if (keep) {
                try (Connection c = db.open();
                     PreparedStatement ps = c.prepareStatement("UPDATE facts SET last_confirmed=? WHERE id=?")) {
                    ps.setString(1, now);
                    ps.setLong(2, existing.id());
                    ps.executeUpdate();
                } catch (Exception e) {
                    log.warn("刷新事实时间失败: {}", e.getMessage());
                }
                return false;
            }
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE facts SET value=?, confidence=?, status=?, last_confirmed=? WHERE id=?")) {
                ps.setString(1, value);
                ps.setDouble(2, confidence);
                ps.setString(3, status);
                ps.setString(4, now);
                ps.setLong(5, existing.id());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("更新事实失败: " + e.getMessage(), e);
            }
            return false;
        }

        String sql = "INSERT INTO facts(qq_id, category, key, value, confidence, status, first_seen, last_confirmed) "
                + "VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, category);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setDouble(5, confidence);
            ps.setString(6, status);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入事实失败: " + e.getMessage(), e);
        }
        trim(qqId);
        return true;
    }

    private Fact find(long qqId, String category, String key) {
        Fact f = null;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM facts WHERE qq_id=? AND category=? AND key=? LIMIT 1")) {
            ps.setLong(1, qqId);
            ps.setString(2, category);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    f = byId(rs.getLong(1));
                }
            }
        } catch (Exception ignored) {
            // 查不到按新增处理
        }
        return f;
    }

    private void trim(long qqId) {
        if (countActive(qqId) <= MAX_PER_USER) {
            return;
        }
        String sql = "UPDATE facts SET status='outdated' WHERE id IN ("
                + "SELECT id FROM facts WHERE qq_id=? AND status='active' "
                + "ORDER BY confidence ASC, last_confirmed ASC LIMIT ?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, 10);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 淘汰失败不影响主流程
        }
    }

    private String buildWhere(long qqId, String category, String q, String status, List<Object> args) {
        StringBuilder sb = new StringBuilder("qq_id=?");
        args.add(qqId);
        if (category != null && !category.isBlank()) {
            sb.append(" AND category=?");
            args.add(category);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            sb.append(" AND (key LIKE ? OR value LIKE ?)");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        return sb.toString();
    }

    private List<Fact> query(String where, Object[] args, String order) {
        List<Object> list = new ArrayList<>(List.of(args));
        return queryRaw(where + " ORDER BY " + order, list);
    }

    private List<Fact> queryRaw(String whereOrSql, List<Object> args) {
        String sql = whereOrSql.startsWith("SELECT") ? whereOrSql
                : "SELECT id, qq_id, category, key, value, confidence, status, "
                + "first_seen, last_confirmed FROM facts WHERE " + whereOrSql;
        List<Fact> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Fact(
                            rs.getLong("id"),
                            rs.getLong("qq_id"),
                            rs.getString("category"),
                            rs.getString("key"),
                            rs.getString("value"),
                            rs.getDouble("confidence"),
                            rs.getString("status"),
                            rs.getString("last_confirmed")));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询事实失败: " + e.getMessage(), e);
        }
        return list;
    }
}
