package com.qqbot.chat;

import com.qqbot.llm.DeepSeekClient;
import com.qqbot.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 「自己的生活」：每天生成几条小事，聊天/主动消息时偶尔拿出来说，
 * 形成连续的生活线，而不是每次现编。
 */
@Service
public class LifeEventService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(LifeEventService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Db db;
    private final DeepSeekClient llm;

    public LifeEventService(Db db, DeepSeekClient llm) {
        this.db = db;
        this.llm = llm;
    }

    @Scheduled(cron = "0 10 8 * * *")
    public void morning() {
        accountRunner.forEach(id -> {
            ensureToday();
    
        });
    }

    /** 今天还没生成就生成 2 条 */
    public void ensureToday() {
        String day = LocalDate.now().toString();
        if (countToday(day) >= 2) {
            return;
        }
        try {
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content",
                            "你在帮一个聊天机器人虚构“今天发生的小事”。只输出 2 行，每行一句，"
                                    + "20-35 个汉字，像真人随口说的日常：吃到什么、看到什么、遇到什么小麻烦。"
                                    + "不要写天气播报、不要写鸡汤、不要提到 AI。"),
                    Map.of("role", "user", "content", "给我今天的两件小事。")
            ), 0.9, 120);
            String out = result.content() == null ? "" : result.content().trim();
            for (String line : out.split("\\n")) {
                String text = line.replaceAll("^[0-9一二三四五六七八九十、.\\s]+", "").trim();
                if (text.length() >= 6 && text.length() <= 60) {
                    insert(day, text);
                }
            }
        } catch (Exception e) {
            log.warn("生成生活小事失败: {}", e.getMessage());
        }
    }

    /** 看一眼今天的小事（不标记已用） */
    public String peekToday() {
        ensureToday();
        String day = LocalDate.now().toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content FROM life_event WHERE day=? ORDER BY RANDOM() LIMIT 1")) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 取一条今天还没用过的小事；没有就返回 null */
    public String pickUnused() {
        ensureToday();
        String day = LocalDate.now().toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, content FROM life_event WHERE day=? AND used_at IS NULL ORDER BY id LIMIT 1")) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    String content = rs.getString(2);
                    markUsed(id);
                    return content;
                }
            }
        } catch (Exception e) {
            log.warn("读取生活小事失败: {}", e.getMessage());
        }
        return null;
    }

    private void markUsed(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE life_event SET used_at=? WHERE id=?")) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private int countToday(String day) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM life_event WHERE day=?")) {
            ps.setString(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void insert(String day, String content) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO life_event(day, content, created_at) VALUES(?,?,?)")) {
            ps.setString(1, day);
            ps.setString(2, content);
            ps.setString(3, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
