package com.qqbot.chat;

import com.qqbot.memory.Db;
import com.qqbot.proactive.IntentDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 情感记忆：记住「让对方难过的具体事」，过两天主动关心一次。
 *
 * 写：用户消息含负面情绪 + 能识别出话题（感冒/面试/加班…）时落一条。
 * 读：到期且白天时交给主动消息系统发出去，只问一次。
 */
@Service
public class EmotionalMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EmotionalMemoryService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RECALL_AFTER_DAYS = 2;

    private final Db db;
    private final MoodService moods;
    private final IntentDetector intents;

    public EmotionalMemoryService(Db db, MoodService moods, IntentDetector intents) {
        this.db = db;
        this.moods = moods;
        this.intents = intents;
    }

    public record Memory(long id, String topic, String detail) {
    }

    /** 用户说了带负面情绪的话题，记下来 */
    public void observe(long qqId, String text) {
        if (text == null || text.isBlank() || !moods.isNegative(text)) {
            return;
        }
        IntentDetector.Intent intent = intents.detect(text);
        String topic = intent != null ? intent.topic() : "情绪";
        String detail = intent != null ? intent.detail() : text;
        if (detail.length() > 80) {
            detail = detail.substring(0, 80);
        }
        try {
            if (recentDuplicate(qqId, topic)) {
                return;
            }
            String sql = "INSERT INTO emotional_memory(qq_id, topic, detail, sentiment, created_at, followed_up) VALUES(?,?,?,?,?,0)";
            try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, qqId);
                ps.setString(2, topic);
                ps.setString(3, detail);
                ps.setDouble(4, -1);
                ps.setString(5, LocalDateTime.now().format(TS));
                ps.executeUpdate();
            }
            log.info("情感记忆已记录 qqId={} topic={}", qqId, topic);
        } catch (Exception e) {
            log.warn("记录情感记忆失败: {}", e.getMessage());
        }
    }

    /** 取一条到期可追问的记忆（白天才返回），取到即标记已跟进 */
    public Memory takeDue(long qqId, LocalDateTime now) {
        if (now.getHour() < 10 || now.getHour() >= 21) {
            return null;
        }
        String sql = "SELECT id, topic, detail FROM emotional_memory "
                + "WHERE qq_id=? AND followed_up=0 AND created_at <= ? ORDER BY id LIMIT 1";
        String cutoff = now.minusDays(RECALL_AFTER_DAYS).format(TS);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long id = rs.getLong("id");
                Memory m = new Memory(id, rs.getString("topic"), rs.getString("detail"));
                markFollowed(id);
                return m;
            }
        } catch (Exception e) {
            log.warn("读取情感记忆失败: {}", e.getMessage());
            return null;
        }
    }

    private void markFollowed(long id) throws Exception {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE emotional_memory SET followed_up=1 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private boolean recentDuplicate(long qqId, String topic) throws Exception {
        String sql = "SELECT COUNT(*) FROM emotional_memory WHERE qq_id=? AND topic=? AND created_at >= ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, topic);
            ps.setString(3, LocalDateTime.now().minusDays(3).format(TS));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
