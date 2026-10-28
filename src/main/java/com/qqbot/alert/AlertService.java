package com.qqbot.alert;

import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.memory.Db;
import com.qqbot.memory.GlobalDb;
import com.qqbot.profile.BotProfileService;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无回复告警：用户发了消息超过 15 分钟仍没有回复时，提醒主人。
 * 同一个用户 2 小时内只提醒一次。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long ALERT_AFTER_MINUTES = 15;
    private static final long REPEAT_HOURS = 2;

    private final Db db;
    private final GlobalDb globalDb;
    private final OneBotRegistry registry;
    private final BotProfileService profile;
    private final Map<Long, Long> lastAlertAt = new ConcurrentHashMap<>();

    public AlertService(Db db, GlobalDb globalDb, OneBotRegistry registry, BotProfileService profile) {
        this.db = db;
        this.globalDb = globalDb;
        this.registry = registry;
        this.profile = profile;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void check() {
        Long botId = globalDb.firstAccountId();
        if (botId == null) {
            return;
        }
        try {
            BotContext.run(botId, this::checkCurrent);
        } catch (Exception e) {
            log.warn("无回复告警检查失败: {}", e.getMessage());
        }
    }

    private void checkCurrent() {
        OneBotClient client = registry.requireCurrent();
        long owner = profile.ownerQq();
        if (owner <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT qq_id, MAX(id) AS last_id, MAX(created_at) AS last_at "
                             + "FROM messages WHERE role='user' "
                             + "AND created_at >= datetime('now','localtime','-3 hours') "
                             + "GROUP BY qq_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long qq = rs.getLong("qq_id");
                    long lastId = rs.getLong("last_id");
                    String lastAt = rs.getString("last_at");
                    if (qq <= 0 || qq == owner || hasReplyAfter(c, qq, lastId)) {
                        continue;
                    }
                    long last = LocalDateTime.parse(lastAt, TS)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    long minutes = Duration.ofMillis(now - last).toMinutes();
                    if (minutes < ALERT_AFTER_MINUTES) {
                        continue;
                    }
                    long prev = lastAlertAt.getOrDefault(qq, 0L);
                    if (now - prev < REPEAT_HOURS * 3600_000L) {
                        continue;
                    }
                    client.sendPrivate(owner,
                            "【小满告警】用户 " + qq + " 的消息已经 " + minutes + " 分钟没有回复，请检查。");
                    lastAlertAt.put(qq, now);
                    log.warn("已发送无回复告警 qq={} 分钟={}", qq, minutes);
                }
            }
        } catch (Exception e) {
            log.warn("无回复告警查询失败: {}", e.getMessage());
        }
    }

    private boolean hasReplyAfter(Connection c, long qq, long lastId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM messages WHERE qq_id=? AND role='assistant' AND id>? LIMIT 1")) {
            ps.setLong(1, qq);
            ps.setLong(2, lastId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
