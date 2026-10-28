package com.qqbot.proactive;

import com.qqbot.memory.Db;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * followups 表：
 * status: open（待发）→ sent（已发，等回复）→ replied / closed
 */
@Component
public class FollowupStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;

    public FollowupStore(Db db) {
        this.db = db;
    }

    public void create(long qqId, String topic, String detail, LocalDateTime dueAt) {
        String sql = "INSERT INTO followups(qq_id, topic, detail, due_at, priority, status, created_at) "
                + "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, topic);
            ps.setString(3, detail);
            ps.setString(4, dueAt.format(TS));
            ps.setInt(5, 1);
            ps.setString(6, "open");
            ps.setString(7, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("创建跟进失败: " + e.getMessage(), e);
        }
    }

    /** 取最早一条到期的待发跟进 */
    public Followup nextDue(long qqId, LocalDateTime now) {
        String sql = "SELECT id, qq_id, topic, detail, due_at, status, sent_at FROM followups "
                + "WHERE qq_id=? AND status='open' AND due_at <= ? ORDER BY due_at ASC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, now.format(TS));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 所有还没发的跟进（后台展示用） */
    public java.util.List<Followup> openList(long qqId) {
        String sql = "SELECT id, qq_id, topic, detail, due_at, status, sent_at FROM followups "
                + "WHERE qq_id=? AND status='open' ORDER BY due_at ASC";
        java.util.List<Followup> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }
    /** 最近一条已发但还没被回复的跟进 */
    public Followup lastSent(long qqId) {
        String sql = "SELECT id, qq_id, topic, detail, due_at, status, sent_at FROM followups "
                + "WHERE qq_id=? AND status='sent' ORDER BY sent_at DESC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public void markSent(long id) {
        exec("UPDATE followups SET status='sent', sent_at=? WHERE id=?",
                LocalDateTime.now().format(TS), id);
    }

    /** 用户回话后，把所有已发跟进标记为已回应 */
    public void resolveAll(long qqId) {
        exec("UPDATE followups SET status='replied', resolved_at=? WHERE qq_id=? AND status='sent'",
                LocalDateTime.now().format(TS), qqId);
    }

    /** 停止打扰时关闭所有跟进 */
    public void closeAll(long qqId) {
        exec("UPDATE followups SET status='closed', resolved_at=? WHERE qq_id=? AND status IN ('sent','open')",
                LocalDateTime.now().format(TS), qqId);
    }

    /** 是否已有同主题的待发/已发跟进，避免重复登记 */
    public boolean hasOpen(long qqId, String topic) {
        String sql = "SELECT COUNT(*) FROM followups WHERE qq_id=? AND topic=? AND status IN (\u0027open\u0027,\u0027sent\u0027)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, topic);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public int openCount(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM followups WHERE qq_id=? AND status IN ('open','sent')")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 调试用：清空该用户所有跟进 */
    public void deleteAll(long qqId) {
        exec("DELETE FROM followups WHERE qq_id=?", qqId);
    }

    private Followup map(ResultSet rs) throws java.sql.SQLException {
        return new Followup(rs.getLong("id"), rs.getLong("qq_id"), rs.getString("topic"),
                rs.getString("detail"), rs.getString("due_at"), rs.getString("status"),
                rs.getString("sent_at"));
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新跟进失败: " + e.getMessage(), e);
        }
    }
}
