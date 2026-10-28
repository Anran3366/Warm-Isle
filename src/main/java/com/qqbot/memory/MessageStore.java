package com.qqbot.memory;

import com.qqbot.chat.ChatMessage;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消息落库与读取：重启后还能接着上文聊。
 */
@Component
public class MessageStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;
    private final MessageEventBus events;

    public MessageStore(Db db, MessageEventBus events) {
        this.db = db;
        this.events = events;
    }

    public void add(long qqId, String role, String content, String kind) {
        add(qqId, role, content, kind, null);
    }

    /** 带表情包 id 的消息（后台可显示原图） */
    public void add(long qqId, String role, String content, String kind, Long emojiId) {
        String sql = "INSERT INTO messages(qq_id, role, content, kind, emoji_id, created_at) VALUES(?,?,?,?,?,?)";
        String createdAt = LocalDateTime.now().format(TS);
        long id = 0;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, qqId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, kind == null ? "text" : kind);
            if (emojiId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, emojiId);
            }
            ps.setString(6, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入消息失败: " + e.getMessage(), e);
        }
        try {
            Long botId = com.qqbot.bot.BotContext.get();
            if (id > 0) {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("id", id);
                payload.put("qqId", qqId);
                payload.put("role", role);
                payload.put("content", content);
                payload.put("kind", kind == null ? "text" : kind);
                payload.put("emojiId", emojiId);
                payload.put("createdAt", createdAt);
                if (botId != null) { events.publish(botId, payload); }
            }
        } catch (Exception ignored) {
            // 推送失败不影响落库
        }
    }

    /**
     * 最近 limit 条消息，按时间正序返回（可直接喂给模型）。
     */
    /** 把该用户最后一条文字回复标记为语音（跳过表情包等非文字消息） */
    public void markLastAsVoice(long qqId) {
        String sql = "UPDATE messages SET kind='voice', content='（语音）' || content "
                + "WHERE id = (SELECT id FROM messages WHERE qq_id=? AND role='assistant' "
                + "AND kind='text' ORDER BY id DESC LIMIT 1)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.executeUpdate();
        } catch (Exception e) {
            // 标记失败不影响发送
        }
    }

    /** 最近 N 条机器人回复（用于复读检测） */
    public java.util.List<String> lastAssistantTexts(long qqId, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String sql = "SELECT content FROM messages WHERE qq_id=? AND role='assistant' ORDER BY id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            // 读取失败按无历史处理
        }
        return out;
    }

    /** 最近一条机器人回复内容（用于去重） */
    public String lastAssistantText(long qqId) {
        String sql = "SELECT content FROM messages WHERE qq_id=? AND role='assistant' ORDER BY id DESC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 最后一条消息时间（yyyy-MM-dd HH:mm:ss），没有返回 null */
    public String lastCreatedAt(long qqId) {
        String sql = "SELECT created_at FROM messages WHERE qq_id=? ORDER BY id DESC LIMIT 1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public List<ChatMessage> recent(long qqId, int limit) {
        String sql = "SELECT role, content FROM messages WHERE qq_id=?"
                + " AND kind NOT IN ('voice-test','emoji-test') ORDER BY id DESC LIMIT ?";
        List<ChatMessage> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ChatMessage(rs.getString("role"), rs.getString("content")));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取历史消息失败: " + e.getMessage(), e);
        }
        Collections.reverse(list);
        return list;
    }

    /** 后台检索：关键词 + 时间范围 + 分页 */
    public java.util.List<MessageItem> searchPage(long qqId, String q, String from, String to,
                                                  int offset, int limit) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        String where = searchWhere(qqId, q, from, to, args);
        args.add(limit);
        args.add(offset);
        String sql = "SELECT id, qq_id, role, content, kind, emoji_id, voice_file, created_at FROM messages WHERE " + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        java.util.List<MessageItem> list = new java.util.ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new MessageItem(rs.getLong("id"), rs.getLong("qq_id"), rs.getString("role"),
                            rs.getString("content"), rs.getString("kind"),
                            emojiIdOf(rs), rs.getString("voice_file"), rs.getString("created_at")));
                }
            }
        } catch (Exception e) {
            return list;
        }
        return list;
    }

    private Long emojiIdOf(ResultSet rs) {
        try {
            Object raw = rs.getObject("emoji_id");
            return raw == null ? null : ((Number) raw).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    public long countSearch(long qqId, String q, String from, String to) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        String where = searchWhere(qqId, q, from, to, args);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM messages WHERE " + where)) {
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

    private String searchWhere(long qqId, String q, String from, String to, java.util.List<Object> args) {
        StringBuilder sb = new StringBuilder("1=1");
        if (qqId > 0) {
            sb.append(" AND qq_id=?");
            args.add(qqId);
        }
        if (q != null && !q.isBlank()) {
            sb.append(" AND content LIKE ?");
            args.add("%" + q + "%");
        }
        if (from != null && !from.isBlank()) {
            sb.append(" AND created_at >= ?");
            args.add(from.length() == 10 ? from + " 00:00:00" : from);
        }
        if (to != null && !to.isBlank()) {
            sb.append(" AND created_at <= ?");
            args.add(to.length() == 10 ? to + " 23:59:59" : to);
        }
        return sb.toString();
    }

    public long countAll() {
        return scalar("SELECT COUNT(*) FROM messages");
    }

    /** 给最近一条指定角色的消息补上语音文件名 */
    public void setLastVoiceFile(long qqId, String role, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE messages SET voice_file=? WHERE id=("
                             + "SELECT id FROM messages WHERE qq_id=? AND role=? AND kind='voice' "
                             + "ORDER BY id DESC LIMIT 1)")) {
            ps.setString(1, fileName);
            ps.setLong(2, qqId);
            ps.setString(3, role);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入语音路径失败: " + e.getMessage(), e);
        }
    }

    /** 最近一条消息的 kind */
    public String lastKind(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind FROM messages WHERE qq_id=? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 该用户累计消息数 */
    public int countUser(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM messages WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 有聊天的天数（按日期去重） */
    public int activeDays(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(DISTINCT substr(created_at,1,10)) FROM messages WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 第一次聊天到现在的天数（没有记录返回 0） */
    public int daysSinceFirst(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MIN(created_at) FROM messages WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String first = rs.getString(1);
                    if (first != null) {
                        java.time.LocalDate d = java.time.LocalDate.parse(first.substring(0, 10));
                        return (int) java.time.temporal.ChronoUnit.DAYS.between(d, java.time.LocalDate.now());
                    }
                }
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    /** 指定时间窗口内的消息（按时间正序），用于事实抽取与摘要 */
    public List<MessageRow> between(long qqId, String from, String to) {
        String sql = "SELECT created_at, role, content FROM messages "
                + "WHERE qq_id=? AND created_at >= ? AND created_at < ? "
                + "AND kind NOT IN ('voice-test','emoji-test','test') ORDER BY id ASC";
        List<MessageRow> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, from);
            ps.setString(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new MessageRow(rs.getString("created_at"), rs.getString("role"), rs.getString("content")));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("按时间读取消息失败: " + e.getMessage(), e);
        }
        return list;
    }

    /** 所有有聊天记录的用户 */
    public List<Long> users() {
        List<Long> ids = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT qq_id FROM messages ORDER BY qq_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (Exception e) {
            return ids;
        }
        return ids;
    }

    public long countToday() {
        String sql = "SELECT COUNT(*) FROM messages WHERE created_at >= ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, LocalDate.now() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public long countUsers() {
        return scalar("SELECT COUNT(DISTINCT qq_id) FROM messages");
    }

    private long scalar(String sql) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
