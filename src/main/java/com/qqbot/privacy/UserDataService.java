package com.qqbot.privacy;

import com.qqbot.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;

/** 删除某个用户的全部聊天与画像数据（隐私删除权） */
@Service
public class UserDataService {

    private static final Logger log = LoggerFactory.getLogger(UserDataService.class);

    /** 只删“属于这个用户”的表；缺失的表会跳过 */
    private static final String[] TABLES = {
            "messages", "facts", "followups", "user_mood", "emotional_memory",
            "summaries", "onboarding", "friend_profile", "proactive_state",
            "proactive_log", "meme_card", "chat_review",
            // 记忆 v2
            "memories", "episodes"
    };

    private final Db db;

    public UserDataService(Db db) {
        this.db = db;
    }

    public int deleteAll(long qqId) {
        int total = 0;
        try (Connection c = db.open()) {
            for (String table : TABLES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + table + " WHERE qq_id=?")) {
                    ps.setLong(1, qqId);
                    total += ps.executeUpdate();
                } catch (Exception ignored) {
                    // 表不存在或没有 qq_id 字段
                }
            }
        } catch (Exception e) {
            log.warn("删除用户数据失败 qq={}: {}", qqId, e.getMessage());
        }
        log.info("已删除用户数据 qq={} 行数={}", qqId, total);
        return total;
    }
}
