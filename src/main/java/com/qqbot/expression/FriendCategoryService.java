package com.qqbot.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.chat.MoodService;
import com.qqbot.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好友画像与自动分类。
 *
 * 说明：NapCat OneBot 目前只暴露了 get_friends_with_category（读取原生分组），
 * 没有把内核的 setBuddyCategory 包装成 OneBot action，所以这里先做本系统内的自动分类，
 * 并在页面上同时展示 QQ 原生分组。等 NapCat 暴露写接口后，可在 refresh 末尾加同步。
 */
@Service
public class FriendCategoryService {

    private static final Logger log = LoggerFactory.getLogger(FriendCategoryService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;
    private final OneBotRegistry registry;
    private final ExpressionConfig config;
    private final MoodService moods;

    public FriendCategoryService(Db db, OneBotRegistry registry, ExpressionConfig config, MoodService moods) {
        this.db = db;
        this.registry = registry;
        this.config = config;
        this.moods = moods;
    }

    /** 按 refreshHours 周期性刷新各账号的好友画像；NapCat 离线时跳过 */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 120_000)
    public void autoRefresh() {
        if (!config.friendAutoCategory()) {
            return;
        }
        for (OneBotClient client : registry.all()) {
            if (!client.isConnected()) {
                continue;
            }
            BotContext.run(client.getBotId(), () -> {
                try {
                    if (stale()) {
                        refresh();
                    }
                } catch (Exception e) {
                    log.debug("自动刷新好友分类失败 bot={}: {}", client.getBotId(), e.getMessage());
                }
            });
        }
    }

    private boolean stale() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT MAX(updated_at) FROM friend_profile");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || rs.getString(1) == null) {
                return true;
            }
            LocalDateTime last = LocalDateTime.parse(rs.getString(1), TS);
            return Duration.between(last, LocalDateTime.now()).toHours() >= config.friendRefreshHours();
        } catch (Exception e) {
            return true;
        }
    }

    public Map<String, Object> refresh() {        OneBotClient client = currentClient();
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("NapCat 未连接");
        }
        JsonNode categories = client.getFriendsWithCategory();
        if (categories == null || !categories.isArray()) {
            throw new IllegalStateException("get_friends_with_category 返回格式异常");
        }

        int friends = 0;
        int changed = 0;
        for (JsonNode category : categories) {
            String categoryName = category.path("categoryName").asText("");
            int categoryId = category.path("categoryId").asInt(0);
            for (JsonNode buddy : category.path("buddyList")) {
                long qqId = buddy.path("user_id").asLong(0);
                if (qqId <= 0) {
                    continue;
                }
                friends++;
                String nickname = buddy.path("nickname").asText("");
                String remark = buddy.path("remark").asText(nickname);
                Stats stats = stats(qqId);
                String autoCategory = classify(stats);
                String reason = reason(stats);
                if (upsert(qqId, nickname, remark, categoryId, categoryName, autoCategory, stats, reason)) {
                    changed++;
                }
            }
        }
        if (config.friendNativeSync()) {
            log.info("friends.nativeSync 已开启，但当前 NapCat OneBot 未暴露 set_friend_category，跳过原生同步");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("friends", friends);
        result.put("updated", changed);
        result.put("nativeSync", false);
        result.put("note", "当前只更新本系统分类；QQ 原生分组读取自 NapCat，暂不支持写回");
        return result;
    }

    public List<Map<String, Object>> list(String category, String q) {
        StringBuilder sql = new StringBuilder(
                "SELECT qq_id, nickname, remark, native_category_id, native_category_name, auto_category, "
                        + "message_count, user_message_count, last_message_at, mood, reason, updated_at "
                        + "FROM friend_profile WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND auto_category=?");
            args.add(category);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (nickname LIKE ? OR remark LIKE ? OR CAST(qq_id AS TEXT) LIKE ?)");
            String like = "%" + q + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY CASE auto_category "
                + "WHEN '特别关心' THEN 1 WHEN '常聊' THEN 2 WHEN '普通' THEN 3 "
                + "WHEN '新朋友' THEN 4 ELSE 5 END, last_message_at DESC, qq_id");
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("qqId", rs.getLong("qq_id"));
                    item.put("nickname", rs.getString("nickname"));
                    item.put("remark", rs.getString("remark"));
                    item.put("nativeCategoryId", rs.getInt("native_category_id"));
                    item.put("nativeCategoryName", rs.getString("native_category_name"));
                    item.put("autoCategory", rs.getString("auto_category"));
                    item.put("messageCount", rs.getInt("message_count"));
                    item.put("userMessageCount", rs.getInt("user_message_count"));
                    item.put("lastMessageAt", rs.getString("last_message_at"));
                    item.put("mood", rs.getDouble("mood"));
                    item.put("reason", rs.getString("reason"));
                    item.put("updatedAt", rs.getString("updated_at"));
                    list.add(item);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取好友画像失败: " + e.getMessage(), e);
        }
        return list;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT auto_category, COUNT(*) FROM friend_profile GROUP BY auto_category");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        } catch (Exception e) {
            // 空库直接返回空统计
        }
        return counts;
    }

    private OneBotClient currentClient() {
        Long botId = BotContext.get();
        return botId == null ? null : registry.get(botId);
    }

    private Stats stats(long qqId) {
        String sql = "SELECT COUNT(*) total, "
                + "SUM(CASE WHEN role='user' THEN 1 ELSE 0 END) user_count, "
                + "MAX(created_at) last_at FROM messages WHERE qq_id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Stats(rs.getInt("total"), rs.getInt("user_count"), rs.getString("last_at"));
                }
            }
        } catch (Exception e) {
            log.debug("读取好友消息统计失败 qq={}: {}", qqId, e.getMessage());
        }
        return new Stats(0, 0, null);
    }

    private String classify(Stats stats) {
        if (stats.total() >= config.closeThreshold()) {
            return "特别关心";
        }
        if (stats.total() >= config.frequentThreshold()) {
            return "常聊";
        }
        if (stats.lastAt() == null) {
            return "安静";
        }
        try {
            LocalDateTime last = LocalDateTime.parse(stats.lastAt(), TS);
            if (Duration.between(last, LocalDateTime.now()).toDays() >= config.quietDays()) {
                return "安静";
            }
        } catch (Exception ignored) {
            // 时间格式异常时按普通处理
        }
        if (stats.total() <= 4) {
            return "新朋友";
        }
        return "普通";
    }

    private String reason(Stats stats) {
        if (stats.total() == 0) {
            return "还没有聊天记录";
        }
        return "消息 " + stats.total() + " 条，最近 " + (stats.lastAt() == null ? "未知" : stats.lastAt());
    }

    private boolean upsert(long qqId, String nickname, String remark, int categoryId, String categoryName,
                           String autoCategory, Stats stats, String reason) {
        String now = LocalDateTime.now().format(TS);
        String sql = "INSERT INTO friend_profile(qq_id, nickname, remark, native_category_id, "
                + "native_category_name, auto_category, message_count, user_message_count, "
                + "last_message_at, mood, reason, updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET nickname=excluded.nickname, remark=excluded.remark, "
                + "native_category_id=excluded.native_category_id, "
                + "native_category_name=excluded.native_category_name, auto_category=excluded.auto_category, "
                + "message_count=excluded.message_count, user_message_count=excluded.user_message_count, "
                + "last_message_at=excluded.last_message_at, mood=excluded.mood, "
                + "reason=excluded.reason, updated_at=excluded.updated_at";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, nickname);
            ps.setString(3, remark);
            ps.setInt(4, categoryId);
            ps.setString(5, categoryName);
            ps.setString(6, autoCategory);
            ps.setInt(7, stats.total());
            ps.setInt(8, stats.userCount());
            ps.setString(9, stats.lastAt());
            ps.setDouble(10, moods.mood(qqId));
            ps.setString(11, reason);
            ps.setString(12, now);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            log.warn("保存好友画像失败 qq={}: {}", qqId, e.getMessage());
            return false;
        }
    }

    private record Stats(int total, int userCount, String lastAt) {
    }
}
