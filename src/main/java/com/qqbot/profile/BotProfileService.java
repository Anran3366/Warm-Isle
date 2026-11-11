package com.qqbot.profile;

import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
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

/** 机器人形象：主人 QQ、改昵称、改头像、修改历史 */
@Service
public class BotProfileService {

    private static final Logger log = LoggerFactory.getLogger(BotProfileService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String OWNER_KEY = "owner.qqId";
    private static final int NICK_MAX = 20;

    private final SettingStore settings;
    private final OneBotRegistry registry;
    private final Db db;

    public BotProfileService(SettingStore settings, OneBotRegistry registry, Db db) {
        this.settings = settings;
        this.registry = registry;
        this.db = db;
    }

    public long ownerQq() {
        String raw = settings.get(OWNER_KEY, null);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setOwner(long qq) {
        settings.set(OWNER_KEY, qq > 0 ? String.valueOf(qq) : "");
    }

    /** 主人说要换头像但还没发图 */
    public boolean pendingAvatar() {
        return "1".equals(settings.get("profile.avatarPending", "0"));
    }

    public void setPendingAvatar(boolean pending) {
        settings.set("profile.avatarPending", pending ? "1" : "0");
    }

    public boolean isOwner(long qq) {
        long owner = ownerQq();
        return owner > 0 && owner == qq;
    }

    /** 改昵称；成功返回新昵称 */
    public String changeNickname(long byQq, String nickname) {
        String name = nickname == null ? "" : nickname.trim();
        if (name.isEmpty() || name.length() > NICK_MAX) {
            throw new IllegalArgumentException("昵称要 1-" + NICK_MAX + " 个字");
        }
        String lower = name.toLowerCase();
        for (String banned : new String[]{"官方", "客服", "腾讯", "系统", "管理员", "ai"}) {
            if (lower.contains(banned)) {
                throw new IllegalArgumentException("昵称里不能带「" + banned + "」");
            }
        }
        OneBotClient client = client();
        String old = client.selfNickname();
        client.setNickname(name);
        logChange("nickname", old, name, byQq);
        return name;
    }

    /** 改头像 */
    public void changeAvatar(long byQq, byte[] image) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("图片是空的");
        }
        if (image.length > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("图片不能超过 2MB");
        }
        client().setAvatar(image);
        logChange("avatar", "", "(已更新)", byQq);
    }

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, field, old_value, new_value, by_qq, created_at "
                             + "FROM bot_profile_log ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("field", rs.getString("field"));
                    m.put("oldValue", rs.getString("old_value"));
                    m.put("newValue", rs.getString("new_value"));
                    m.put("byQq", rs.getLong("by_qq"));
                    m.put("createdAt", rs.getString("created_at"));
                    list.add(m);
                }
            }
        } catch (Exception ignored) {
            // 返回空
        }
        return list;
    }

    /** 恢复某次修改之前的昵称（头像无法恢复旧图） */
    public Map<String, Object> restore(long logId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT field, old_value FROM bot_profile_log WHERE id=?")) {
            ps.setLong(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("ok", false, "message", "记录不存在");
                }
                String field = rs.getString("field");
                String old = rs.getString("old_value");
                if (!"nickname".equals(field)) {
                    return Map.of("ok", false, "message", "头像无法自动恢复");
                }
                if (old == null || old.isBlank()) {
                    return Map.of("ok", false, "message", "没有可恢复的旧值");
                }
                client().setNickname(old);
                logChange("nickname", "", old, 0);
                return Map.of("ok", true, "nickname", old);
            }
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private OneBotClient client() {
        Long botId = BotContext.get();
        OneBotClient client = botId == null ? null : registry.get(botId);
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("机器人未连接");
        }
        return client;
    }

    private void logChange(String field, String oldValue, String newValue, long byQq) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bot_profile_log(field, old_value, new_value, by_qq, created_at) "
                             + "VALUES(?,?,?,?,?)")) {
            ps.setString(1, field);
            ps.setString(2, oldValue);
            ps.setString(3, newValue);
            ps.setLong(4, byQq);
            ps.setString(5, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("记录形象修改失败: {}", e.getMessage());
        }
    }
}
