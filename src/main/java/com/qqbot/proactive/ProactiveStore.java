package com.qqbot.proactive;

import com.qqbot.memory.Db;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 主动消息状态存储。
 *
 * 旧字段（suspended / grievance_level / tease_next）保留兼容，
 * 新逻辑主要使用 mode / next_eligible_at / dnd_until / reentry_stage。
 */
@Component
public class ProactiveStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;

    public ProactiveStore(Db db) {
        this.db = db;
    }

    public ProactiveState get(long qqId) {
        String sql = "SELECT last_user_at, last_proactive_at, unanswered_count, suspended, grievance_level, tease_next,"
                + " mode, next_eligible_at, dnd_until, mood, mood_until, reentry_stage, activity_hint, activity_until "
                + "FROM proactive_state WHERE qq_id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ProactiveState(qqId,
                            rs.getString("last_user_at"),
                            rs.getString("last_proactive_at"),
                            rs.getInt("unanswered_count"),
                            rs.getInt("suspended") == 1,
                            rs.getInt("grievance_level"),
                            rs.getInt("tease_next") == 1,
                            parseMode(rs.getString("mode")),
                            rs.getString("next_eligible_at"),
                            rs.getString("dnd_until"),
                            rs.getString("mood"),
                            rs.getString("mood_until"),
                            rs.getInt("reentry_stage"),
                            rs.getString("activity_hint"),
                            rs.getString("activity_until"));
                }
            }
        } catch (Exception e) {
            // 读不到按初始状态处理
        }
        return new ProactiveState(qqId, null, null, 0, false, 0, false,
                ProactiveMode.CALM, null, null, null, null, 0, null, null);
    }

    private ProactiveMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProactiveMode.CALM;
        }
        try {
            return ProactiveMode.valueOf(raw);
        } catch (Exception e) {
            return ProactiveMode.CALM;
        }
    }

    /** 用户说话：回到 CALM，清空未回复计数，清掉短时活动状态 */
    public void touchUser(long qqId) {
        String now = LocalDateTime.now().format(TS);
        String sql = "INSERT INTO proactive_state(qq_id, last_user_at, unanswered_count, suspended, mode, updated_at) "
                + "VALUES(?,?,0,0,'CALM',?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET last_user_at=excluded.last_user_at, "
                + "unanswered_count=0, suspended=0, mode='CALM', next_eligible_at=NULL, "
                + "activity_hint=NULL, activity_until=NULL, updated_at=excluded.updated_at";
        exec(sql, qqId, now, now);
        markLastReplied(qqId);
    }

    public void recordProactive(long qqId, String type, String content) {
        recordProactive(qqId, type, content, "text", null, 1);
    }

    public void recordProactive(long qqId, String type, String content,
                                String channel, String hook, int bubbleCount) {
        String now = LocalDateTime.now().format(TS);
        exec("INSERT INTO proactive_log(qq_id, type, content, sent_at, channel, hook, bubble_count) "
                + "VALUES(?,?,?,?,?,?,?)", qqId, type, content, now, channel, hook, bubbleCount);

        String update = "INSERT INTO proactive_state(qq_id, last_proactive_at, unanswered_count, updated_at) "
                + "VALUES(?,?,1,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET last_proactive_at=excluded.last_proactive_at, "
                + "unanswered_count=unanswered_count+1, updated_at=excluded.updated_at";
        exec(update, qqId, now, now);
    }

    /** 把最近一条未回复的主动消息标记为已回复，并记录延迟 */
    public void markLastReplied(long qqId) {
        String now = LocalDateTime.now().format(TS);
        Long id = null;
        String sentAt = null;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, sent_at FROM proactive_log WHERE qq_id=? AND replied_at IS NULL ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getLong(1);
                    sentAt = rs.getString(2);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        if (id == null) {
            return;
        }
        long latency = 0;
        try {
            latency = Duration.between(LocalDateTime.parse(sentAt, TS), LocalDateTime.now()).getSeconds();
        } catch (Exception ignored) {
            // 时间解析失败就记 0
        }
        exec("UPDATE proactive_log SET replied_at=?, reply_latency_sec=? WHERE id=?", now, latency, id);
    }

    public void setMode(long qqId, ProactiveMode mode) {
        String now = LocalDateTime.now().format(TS);
        exec("INSERT INTO proactive_state(qq_id, mode, updated_at) VALUES(?,?,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET mode=excluded.mode, updated_at=excluded.updated_at",
                qqId, mode.name(), now);
    }

    public void setNextEligible(long qqId, LocalDateTime when) {
        String now = LocalDateTime.now().format(TS);
        exec("INSERT INTO proactive_state(qq_id, next_eligible_at, updated_at) VALUES(?,?,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET next_eligible_at=excluded.next_eligible_at, "
                + "updated_at=excluded.updated_at",
                qqId, when == null ? null : when.format(TS), now);
    }

    public void setDnd(long qqId, LocalDateTime until) {
        String now = LocalDateTime.now().format(TS);
        exec("INSERT INTO proactive_state(qq_id, dnd_until, mode, updated_at) VALUES(?,?,'DND',?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET dnd_until=excluded.dnd_until, mode='DND', "
                + "updated_at=excluded.updated_at",
                qqId, until == null ? null : until.format(TS), now);
    }

    public void clearDnd(long qqId) {
        String now = LocalDateTime.now().format(TS);
        exec("UPDATE proactive_state SET dnd_until=NULL, updated_at=? WHERE qq_id=?", now, qqId);
    }

    /** 记录「她说去洗澡/吃饭/忙」这类短时活动 */
    public void setActivity(long qqId, String hint, LocalDateTime until) {
        String now = LocalDateTime.now().format(TS);
        exec("INSERT INTO proactive_state(qq_id, activity_hint, activity_until, updated_at) VALUES(?,?,?,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET activity_hint=excluded.activity_hint, "
                + "activity_until=excluded.activity_until, updated_at=excluded.updated_at",
                qqId, hint, until == null ? null : until.format(TS), now);
    }

    public void setMood(long qqId, String mood, LocalDateTime until) {
        String now = LocalDateTime.now().format(TS);
        exec("UPDATE proactive_state SET mood=?, mood_until=?, updated_at=? WHERE qq_id=?",
                mood, until == null ? null : until.format(TS), now, qqId);
    }

    public void setReentryStage(long qqId, int stage) {
        String now = LocalDateTime.now().format(TS);
        exec("UPDATE proactive_state SET reentry_stage=?, updated_at=? WHERE qq_id=?", stage, now, qqId);
    }

    public void suspend(long qqId) {
        String now = LocalDateTime.now().format(TS);
        String sql = "UPDATE proactive_state SET suspended=1, grievance_level=grievance_level+1, mode='BACKOFF', updated_at=? WHERE qq_id=?";
        exec(sql, now, qqId);
    }

    public void setTeaseNext(long qqId, boolean value) {
        String now = LocalDateTime.now().format(TS);
        exec("UPDATE proactive_state SET tease_next=?, updated_at=? WHERE qq_id=?", value ? 1 : 0, now, qqId);
    }

    public void clearTease(long qqId) {
        setTeaseNext(qqId, false);
    }

    /** 发送历史（后台展示） */
    public java.util.List<java.util.Map<String, Object>> historyPage(long qqId, String type,
                                                                     int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, content, sent_at, channel, replied_at FROM proactive_log WHERE qq_id=?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(qqId);
        if (type != null && !type.isBlank()) {
            sql.append(" AND type=?");
            args.add(type);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("type", rs.getString("type"));
                    m.put("content", rs.getString("content"));
                    m.put("sentAt", rs.getString("sent_at"));
                    m.put("channel", rs.getString("channel"));
                    m.put("replied", rs.getString("replied_at") != null);
                    list.add(m);
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }

    public long historyCount(long qqId, String type) {
        String sql = "SELECT COUNT(*) FROM proactive_log WHERE qq_id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 最近一条主动消息的内容，用于去重 */
    public String lastContent(long qqId) {
        return recentContents(qqId, 1).stream().findFirst().orElse(null);
    }

    /** 最近若干条主动消息内容（新的在前） */
    public java.util.List<String> recentContents(long qqId, int limit) {
        String sql = "SELECT content FROM proactive_log WHERE qq_id=? ORDER BY id DESC LIMIT ?";
        java.util.List<String> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }

    /** 回复统计：总条数、被回复条数、平均回复延迟（秒） */
    public java.util.Map<String, Object> stats(long qqId) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) AS total, "
                             + "SUM(CASE WHEN replied_at IS NOT NULL THEN 1 ELSE 0 END) AS replied, "
                             + "AVG(reply_latency_sec) AS latency FROM proactive_log WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int total = rs.getInt("total");
                    int replied = rs.getInt("replied");
                    m.put("total", total);
                    m.put("replied", replied);
                    m.put("replyRate", total == 0 ? 0.0 : (double) replied / total);
                    m.put("avgLatencySec", rs.getDouble("latency"));
                }
            }
        } catch (Exception e) {
            m.put("total", 0);
            m.put("replied", 0);
            m.put("replyRate", 0.0);
            m.put("avgLatencySec", 0.0);
        }
        return m;
    }
    /** 今天某类型发了几条 */
    public int sentToday(long qqId, String type) {
        String sql = "SELECT COUNT(*) FROM proactive_log WHERE qq_id=? AND type=? AND sent_at >= ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, type);
            ps.setString(3, LocalDate.now() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 今天已发了几条主动消息 */
    public int todayCount(long qqId) {
        String sql = "SELECT COUNT(*) FROM proactive_log WHERE qq_id=? AND sent_at >= ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, LocalDate.now() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public int totalCount(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM proactive_log WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 调试用：清空状态（回到从未主动发过消息的样子） */
    public void reset(long qqId) {
        exec("DELETE FROM proactive_state WHERE qq_id=?", qqId);
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入主动消息状态失败: " + e.getMessage(), e);
        }
    }
}
