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

/** 好友申请（每个机器人账号各自的库） */
@Component
public class FriendRequestStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;

    public FriendRequestStore(Db db) {
        this.db = db;
    }

    public void insert(String flag, long userId, String nickname, String comment) {
        if (flag == null || flag.isBlank() || userId <= 0) {
            return;
        }
        String sql = "INSERT OR IGNORE INTO friend_request(flag, user_id, nickname, comment, status, created_at) "
                + "VALUES(?,?,?,?,'pending',?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, flag);
            ps.setLong(2, userId);
            ps.setString(3, nickname);
            ps.setString(4, comment);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 记录失败不影响主流程
        }
    }

    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, flag, user_id, nickname, comment, created_at FROM friend_request "
                + "WHERE status='pending' ORDER BY id DESC";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("flag", rs.getString("flag"));
                m.put("userId", rs.getLong("user_id"));
                m.put("nickname", rs.getString("nickname"));
                m.put("comment", rs.getString("comment"));
                m.put("createdAt", rs.getString("created_at"));
                list.add(m);
            }
        } catch (Exception ignored) {
            return list;
        }
        return list;
    }

    public Map<String, Object> byId(long id) {
        String sql = "SELECT id, flag, user_id, nickname FROM friend_request WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("flag", rs.getString("flag"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("nickname", rs.getString("nickname"));
                    return m;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public void updateNickname(long id, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE friend_request SET nickname=? WHERE id=?")) {
            ps.setString(1, nickname);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    public void markByFlag(String flag, String status) {
        if (flag == null || flag.isBlank()) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE friend_request SET status=? WHERE flag=?")) {
            ps.setString(1, status);
            ps.setString(2, flag);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    public void mark(long id, String status) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE friend_request SET status=?, handled_at=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, LocalDateTime.now().format(TS));
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
