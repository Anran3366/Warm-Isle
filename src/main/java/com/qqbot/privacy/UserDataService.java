package com.qqbot.privacy;

import com.qqbot.memory.Db;
import com.qqbot.memory.SharedDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/** 删除某个用户的全部聊天、记忆、关系与行为数据（隐私删除权）。 */
@Service
public class UserDataService {

    private static final Logger log = LoggerFactory.getLogger(UserDataService.class);

    /** 表名 + 该表里代表用户的列；不能统一假设列名都叫 qq_id。 */
    private record Target(String table, String column) {
    }

    private static final Target[] BOT_TABLES = {
            new Target("messages", "qq_id"),
            new Target("facts", "qq_id"),
            new Target("followups", "qq_id"),
            new Target("user_mood", "qq_id"),
            new Target("emotional_memory", "qq_id"),
            new Target("summaries", "qq_id"),
            new Target("onboarding", "qq_id"),
            new Target("friend_profile", "qq_id"),
            new Target("proactive_state", "qq_id"),
            new Target("proactive_log", "qq_id"),
            new Target("chat_review", "qq_id"),
            new Target("memories", "qq_id"),
            new Target("episodes", "qq_id"),
            new Target("memory_vectors", "qq_id"),
            new Target("qzone_post", "qq_id"),
            // 这两张不是 qq_id：好友申请是 user_id，头像/昵称变更日志是 by_qq
            new Target("friend_request", "user_id"),
            new Target("bot_profile_log", "by_qq"),
    };

    /** 公用库里的用户行为数据。 */
    private static final Target[] SHARED_TABLES = {
            new Target("emoji_send_log", "qq_id"),
    };

    public record Result(int rows, List<String> failedTables) {
        public boolean complete() {
            return failedTables.isEmpty();
        }
    }

    private final Db db;
    private final SharedDb shared;

    public UserDataService(Db db, SharedDb shared) {
        this.db = db;
        this.shared = shared;
    }

    public Result deleteAll(long qqId) {
        int total = 0;
        List<String> failed = new ArrayList<>();
        try (Connection c = db.open()) {
            total += delete(c, BOT_TABLES, qqId, failed);
        } catch (Exception e) {
            log.warn("打开账号库删除用户数据失败 qq={}: {}", qqId, e.getMessage());
            failed.add("bots.db");
        }
        try (Connection c = shared.open()) {
            total += delete(c, SHARED_TABLES, qqId, failed);
        } catch (Exception e) {
            log.warn("打开公用库删除用户数据失败 qq={}: {}", qqId, e.getMessage());
            failed.add("shared.db");
        }
        log.info("已删除用户数据 qq={} 行数={} 失败表={}", qqId, total, failed);
        return new Result(total, List.copyOf(failed));
    }

    private int delete(Connection c, Target[] targets, long qqId, List<String> failed) {
        int total = 0;
        for (Target t : targets) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + t.table() + " WHERE " + t.column() + "=?")) {
                ps.setLong(1, qqId);
                total += ps.executeUpdate();
            } catch (Exception e) {
                if (missingTableOrColumn(e)) {
                    log.debug("跳过旧库不存在的表/列 {}.{}", t.table(), t.column());
                } else {
                    failed.add(t.table());
                    log.warn("删除 {}.{} 失败 qq={}: {}", t.table(), t.column(), qqId, e.getMessage());
                }
            }
        }
        return total;
    }

    private static boolean missingTableOrColumn(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("no such table") || m.contains("no such column");
    }
}
