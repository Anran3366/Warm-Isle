package com.qqbot.api;

import com.qqbot.chat.FamiliarityService;
import com.qqbot.chat.MoodService;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 关系面板：熟悉度 + 当前心情 */
@RestController
@RequestMapping("/api/relation")
public class RelationController {

    private final MessageStore messages;
    private final FamiliarityService familiarity;
    private final MoodService moods;
    private final Db db;

    public RelationController(MessageStore messages, FamiliarityService familiarity,
                              MoodService moods, Db db) {
        this.messages = messages;
        this.familiarity = familiarity;
        this.moods = moods;
        this.db = db;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long qqId : messages.users()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("qqId", qqId);
            item.put("nickname", nickname(qqId));
            item.put("lastMessageAt", lastAt(qqId));
            item.put("familiarity", familiarity.levelInfo(qqId));
            item.put("mood", moods.info(qqId));
            items.add(item);
        }
        items.sort((a, b) -> {
            int la = ((Map<?, ?>) a.get("familiarity")).get("level") instanceof Number n ? n.intValue() : 0;
            int lb = ((Map<?, ?>) b.get("familiarity")).get("level") instanceof Number n ? n.intValue() : 0;
            return Integer.compare(lb, la);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    private String nickname(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT nickname FROM friend_profile WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String n = rs.getString(1);
                    return n == null || n.isBlank() ? null : n;
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    private String lastAt(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(created_at) FROM messages WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
