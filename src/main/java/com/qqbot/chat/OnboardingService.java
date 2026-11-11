package com.qqbot.chat;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.memory.Db;
import com.qqbot.proactive.ProactiveComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 新好友引导：通过好友申请后先自我介绍；
 * 对方一直不回，就再主动找一个话题；找完还没回，就不再打扰。
 *
 * 阶段：INTRO（已发介绍）→ TOPIC（已发话题）→ DONE（不再主动）
 */
@Service
public class OnboardingService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalTime ACTIVE_FROM = LocalTime.of(9, 0);
    private static final LocalTime ACTIVE_TO = LocalTime.of(22, 0);

    private final Db db;
    private final OneBotRegistry registry;
    private final ProactiveComposer composer;

    public OnboardingService(Db db, OneBotRegistry registry, ProactiveComposer composer) {
        this.db = db;
        this.registry = registry;
        this.composer = composer;
    }

    /** 通过好友申请后调用：立刻发一条自我介绍 */
    public void start(long qqId) {
        if (qqId <= 0 || stage(qqId) != null) {
            return;
        }
        String now = LocalDateTime.now().format(TS);
        exec("INSERT OR IGNORE INTO onboarding(qq_id, stage, created_at, updated_at) VALUES(?,?,?,?)",
                qqId, "INTRO", now, now);
        if (!inWindow()) {
            return;
        }
        String text = composeIntro(qqId);
        if (send(qqId, text)) {
            exec("UPDATE onboarding SET intro_at=?, updated_at=? WHERE qq_id=?", now, now, qqId);
            log.info("新好友自我介绍已发送 qq={}", qqId);
        }
    }

    /** 对方回消息了：结束引导，不再主动补话题 */
    public void onUserMessage(long qqId) {
        if (stage(qqId) == null) {
            return;
        }
        exec("UPDATE onboarding SET stage='DONE', updated_at=? WHERE qq_id=?",
                LocalDateTime.now().format(TS), qqId);
    }

    /** 每 10 分钟检查：该补话题的补话题，该放弃的放弃 */
    @Scheduled(fixedDelay = 600_000)
    public void tick() {
        accountRunner.forEach(id -> {
            if (!inWindow()) {
                return;
            }
            for (Row row : rows()) {
                try {
                    LocalDateTime now = LocalDateTime.now();
                    if ("INTRO".equals(row.stage())) {
                        LocalDateTime introAt = parse(row.introAt());
                        if (introAt == null || Duration.between(introAt, now).toHours() < 3) {
                            continue;
                        }
                        String text = composeTopic(row.qqId());
                        if (send(row.qqId(), text)) {
                            exec("UPDATE onboarding SET stage='TOPIC', topic_at=?, updated_at=? WHERE qq_id=?",
                                    now.format(TS), now.format(TS), row.qqId());
                            log.info("新好友补话题 qq={}", row.qqId());
                        }
                    } else if ("TOPIC".equals(row.stage())) {
                        LocalDateTime topicAt = parse(row.topicAt());
                        if (topicAt != null && Duration.between(topicAt, now).toHours() >= 6) {
                            exec("UPDATE onboarding SET stage='DONE', updated_at=? WHERE qq_id=?",
                                    now.format(TS), row.qqId());
                            log.info("新好友仍无回应，停止主动 qq={}", row.qqId());
                        }
                    }
                } catch (Exception e) {
                    log.warn("新好友引导失败 qq={}: {}", row.qqId(), e.getMessage());
                }
            }
    
        });
    }

    private String composeIntro(long qqId) {
        try {
            String text = composer.compose(qqId,
                    "你刚加了一个新朋友，这是第一次说话。简单介绍一下自己：叫什么、平时喜欢做什么，"
                            + "语气轻松，别太长，别连续提问", "打招呼");
            return text == null || text.isBlank() ? "嗨，我是小满，刚加到你～" : text;
        } catch (Exception e) {
            return "嗨，我是小满，刚加到你～";
        }
    }

    private String composeTopic(long qqId) {
        try {
            String text = composer.compose(qqId,
                    "刚加的新朋友还没回你。主动说一个轻松的话题，比如今天遇到的小事，"
                            + "不要问“在吗”“怎么不回”，也不要连续追问", null);
            return text == null || text.isBlank() ? "对了，今天路过一家店，闻到刚出炉的面包味" : text;
        } catch (Exception e) {
            return "对了，今天路过一家店，闻到刚出炉的面包味";
        }
    }

    private boolean send(long qqId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            Long botId = com.qqbot.bot.BotContext.get();
            OneBotClient client = botId == null ? null : registry.get(botId);
            if (client == null || !client.isConnected()) {
                return false;
            }
            client.sendPrivate(qqId, text);
            return true;
        } catch (Exception e) {
            log.warn("发送新好友引导失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean inWindow() {
        LocalTime t = LocalTime.now();
        return !t.isBefore(ACTIVE_FROM) && t.isBefore(ACTIVE_TO);
    }

    private String stage(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT stage FROM onboarding WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private record Row(long qqId, String stage, String introAt, String topicAt) {
    }

    private List<Row> rows() {
        List<Row> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT qq_id, stage, intro_at, topic_at FROM onboarding WHERE stage IN ('INTRO','TOPIC')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return list;
    }

    private LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, TS);
        } catch (Exception e) {
            return null;
        }
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("onboarding 写入失败: {}", e.getMessage());
        }
    }
}
