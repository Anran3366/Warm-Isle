package com.qqbot.memory;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表情包素材库（shared.db） */
@Component
public class EmojiStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String COLS =
            "id, path, emo_tags, scene_tags, description, status, sent_count, last_sent_at, enabled, created_at";

    private final SharedDb db;

    public EmojiStore(SharedDb db) {
        this.db = db;
    }

    public record Page(List<Emoji> items, long total) {
    }

    public Page page(String q, Boolean enabled, int offset, int limit) {
        return page(q, enabled, null, offset, limit);
    }

    public Page page(String q, Boolean enabled, String status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(q, enabled, status, args);
        List<Emoji> items = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM emoji WHERE " + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, pageArgs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(map(rs));
                }
            }
        } catch (Exception ignored) {
            // 返回空页
        }
        return new Page(items, count(q, enabled, status));
    }

    public long count(String q, Boolean enabled) {
        return count(q, enabled, null);
    }

    public long count(String q, Boolean enabled, String status) {
        List<Object> args = new ArrayList<>();
        String where = where(q, enabled, status, args);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM emoji WHERE " + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public long insert(String path, String emoTags, String sceneTags, String description,
                       boolean enabled, String status, String hash, String ahash) {
        String sql = "INSERT INTO emoji(path, emo_tags, scene_tags, description, status, enabled, created_at, image_hash, image_ahash) "
                + "VALUES(?,?,?,?,?,?,datetime('now','localtime'),?,?)";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, path);
            ps.setString(2, emoTags);
            ps.setString(3, sceneTags);
            ps.setString(4, description);
            ps.setString(5, status == null ? "approved" : status);
            ps.setInt(6, enabled ? 1 : 0);
            ps.setString(7, hash);
            ps.setString(8, ahash);
            ps.executeUpdate();
            try (PreparedStatement q = c.prepareStatement("SELECT last_insert_rowid()");
                 ResultSet rs = q.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存表情失败: " + e.getMessage(), e);
        }
    }

    /** 全部表情（去重用） */
    public java.util.List<Emoji> all() {
        java.util.List<Emoji> list = new java.util.ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, path, emo_tags, scene_tags, description, status, sent_count, last_sent_at, enabled, created_at FROM emoji ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Emoji(rs.getLong("id"), rs.getString("path"), rs.getString("emo_tags"),
                        rs.getString("scene_tags"), rs.getString("description"), rs.getString("status"),
                        rs.getInt("sent_count"), rs.getString("last_sent_at"), rs.getInt("enabled") == 1,
                        rs.getString("created_at")));
            }
        } catch (Exception ignored) {
            // 返回空
        }
        return list;
    }

    public void setHash(long id, String hash) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE emoji SET image_hash=? WHERE id=?")) {
            ps.setString(1, hash);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 感知哈希：汉明距离 <= 6 视为同一张 */
    public long findByAhash(String ahash) {
        if (ahash == null || ahash.isBlank()) {
            return 0;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, image_ahash FROM emoji WHERE image_ahash IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            long target = Long.parseUnsignedLong(ahash, 16);
            while (rs.next()) {
                try {
                    long other = Long.parseUnsignedLong(rs.getString(2), 16);
                    if (Long.bitCount(target ^ other) <= 6) {
                        return rs.getLong(1);
                    }
                } catch (Exception ignored) {
                    // 跳过脏数据
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return 0;
    }

    public void setAhash(long id, String ahash) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE emoji SET image_ahash=? WHERE id=?")) {
            ps.setString(1, ahash);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    public void updateDescription(long id, String description) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE emoji SET description=? WHERE id=?")) {
            ps.setString(1, description);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 图片哈希是否已存在，返回已有 id（0 表示没有） */
    public long findByHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return 0;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM emoji WHERE image_hash=? LIMIT 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public long insert(String path, String emoTags, String sceneTags, String description, boolean enabled) {
        return insert(path, emoTags, sceneTags, description, enabled, "approved");
    }

    public long insert(String path, String emoTags, String sceneTags, String description,
                       boolean enabled, String status) {
        String sql = "INSERT INTO emoji(path, emo_tags, scene_tags, description, status, enabled, created_at) "
                + "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, path);
                ps.setString(2, emoTags);
                ps.setString(3, sceneTags);
                ps.setString(4, description);
                ps.setString(5, status == null || status.isBlank() ? "approved" : status);
                ps.setInt(6, enabled ? 1 : 0);
                ps.setString(7, LocalDateTime.now().format(TS));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT last_insert_rowid()");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存表情失败: " + e.getMessage(), e);
        }
    }

    public Emoji byId(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM emoji WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 可发送的表情：启用 + 已审核 */
    public List<Emoji> usable(int limit) {
        List<Emoji> list = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM emoji WHERE enabled=1 AND (status IS NULL OR status='approved') "
                + "ORDER BY sent_count ASC, RANDOM() LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (Exception ignored) {
            return list;
        }
        return list;
    }

    public void update(long id, String emoTags, String sceneTags, String description, Boolean enabled) {
        StringBuilder sql = new StringBuilder("UPDATE emoji SET ");
        List<Object> args = new ArrayList<>();
        if (emoTags != null) {
            sql.append("emo_tags=?, ");
            args.add(emoTags);
        }
        if (sceneTags != null) {
            sql.append("scene_tags=?, ");
            args.add(sceneTags);
        }
        if (description != null) {
            sql.append("description=?, ");
            args.add(description);
        }
        if (enabled != null) {
            sql.append("enabled=?, ");
            args.add(enabled ? 1 : 0);
        }
        if (args.isEmpty()) {
            return;
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE id=?");
        args.add(id);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新表情失败: " + e.getMessage(), e);
        }
    }

    public void setStatus(long id, String status) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE emoji SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新表情状态失败: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM emoji WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("删除表情失败: " + e.getMessage(), e);
        }
    }

    // ---------- 发送统计 ----------

    public void markSent(long emojiId, long qqId, String emotion) {
        String now = LocalDateTime.now().format(TS);
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE emoji SET sent_count=sent_count+1, last_sent_at=? WHERE id=?")) {
                ps.setString(1, now);
                ps.setLong(2, emojiId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO emoji_send_log(emoji_id, qq_id, emotion, sent_at) VALUES(?,?,?,?)")) {
                ps.setLong(1, emojiId);
                ps.setLong(2, qqId);
                ps.setString(3, emotion);
                ps.setString(4, now);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
            // 统计失败不影响发送
        }
    }

    /** 今天这张图发了多少次 */
    public int sentToday(long emojiId) {
        String sql = "SELECT COUNT(*) FROM emoji_send_log WHERE emoji_id=? AND sent_at >= ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, emojiId);
            ps.setString(2, LocalDateTime.now().toLocalDate() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 最近一次发给该用户的情绪（用于同情绪不连发） */
    public String lastEmotionTo(long qqId) {
        String sql = "SELECT emotion FROM emoji_send_log WHERE qq_id=? ORDER BY id DESC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 用户说话时，把最近一条没被回应的表情标记为已回应 */
    public void markLastReplied(long qqId) {
        String now = LocalDateTime.now().format(TS);
        String sql = "UPDATE emoji_send_log SET replied_at=? WHERE id=("
                + "SELECT id FROM emoji_send_log WHERE qq_id=? AND replied_at IS NULL ORDER BY id DESC LIMIT 1)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, now);
            ps.setLong(2, qqId);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** 各标签数量，供前端筛选器展示 */
    public Map<String, Integer> tagStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT emo_tags FROM emoji WHERE emo_tags IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                for (String tag : rs.getString(1).split("[,，\\s]+")) {
                    if (!tag.isBlank()) {
                        stats.merge(tag, 1, Integer::sum);
                    }
                }
            }
        } catch (Exception ignored) {
            // 空统计
        }
        return stats;
    }

    private String where(String q, Boolean enabled, String status, List<Object> args) {
        StringBuilder sb = new StringBuilder("1=1");
        if (q != null && !q.isBlank()) {
            sb.append(" AND (emo_tags LIKE ? OR scene_tags LIKE ? OR description LIKE ?)");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        if (enabled != null) {
            sb.append(" AND enabled=?");
            args.add(enabled ? 1 : 0);
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            sb.append(" AND status=?");
            args.add(status);
        }
        return sb.toString();
    }

    private Emoji map(ResultSet rs) throws java.sql.SQLException {
        return new Emoji(rs.getLong("id"), rs.getString("path"), rs.getString("emo_tags"),
                rs.getString("scene_tags"), rs.getString("description"), rs.getString("status"),
                rs.getInt("sent_count"), rs.getString("last_sent_at"),
                rs.getInt("enabled") == 1, rs.getString("created_at"));
    }

    private void bind(PreparedStatement ps, List<Object> args) throws java.sql.SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }
}