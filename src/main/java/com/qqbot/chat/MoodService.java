package com.qqbot.chat;

import com.qqbot.memory.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户情绪状态：-1（低落）~ +1（开心）。
 * 根据对方消息里的正负向词做轻量更新，并随时间衰减回 0。
 * 不需要模型，成本为零。
 */
@Service
public class MoodService {

    private static final Logger log = LoggerFactory.getLogger(MoodService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] POSITIVE = {
            "开心", "高兴", "喜欢", "爱你", "想你", "好棒", "厉害", "谢谢", "哈哈", "嘿嘿",
            "抱抱", "亲亲", "可爱", "太好了", "舒服", "期待", "嘿嘿", "嘻嘻", "好呀", "行呀"
    };
    private static final String[] NEGATIVE = {
            "烦", "累", "难过", "伤心", "生气", "讨厌", "滚", "别烦", "无语", "操",
            "郁闷", "焦虑", "崩溃", "算了", "不想", "没意思", "痛苦", "委屈", "难受"
    };

    private final Db db;

    public MoodService(Db db) {
        this.db = db;
    }

    /** 是否含明显负面情绪词 */
    public boolean isNegative(String text) {
        if (text == null) {
            return false;
        }
        for (String w : NEGATIVE) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 读取当前心情（已做时间衰减） */
    public double mood(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT mood, updated_at FROM user_mood WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                double value = rs.getDouble("mood");
                String updated = rs.getString("updated_at");
                return decayed(value, updated);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 根据用户这条消息更新心情 */
    public void observe(long qqId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        double delta = 0;
        for (String w : POSITIVE) {
            if (text.contains(w)) {
                delta += 0.12;
            }
        }
        for (String w : NEGATIVE) {
            if (text.contains(w)) {
                delta -= 0.15;
            }
        }
        if (delta == 0) {
            return;
        }
        double current = mood(qqId);
        double next = Math.max(-1, Math.min(1, current + delta));
        save(qqId, next);
        log.debug("mood 更新 qqId={} {} -> {}", qqId, current, next);
    }

    /** 模型判断出的情绪倾向（-1..1），按 0.4 权重并入当前心情 */
    public void applyModel(long qqId, double score) {
        if (Double.isNaN(score)) {
            return;
        }
        double normalized = Math.max(-1, Math.min(1, score));
        double current = mood(qqId);
        double next = Math.max(-1, Math.min(1, current + normalized * 0.4));
        save(qqId, next);
        log.debug("mood 模型更新 qqId={} {} -> {}", qqId, current, next);
    }

    /** 后台展示用：-1..1 的值 + 标签 */
    public java.util.Map<String, Object> info(long qqId) {
        double m = mood(qqId);
        String label;
        if (m >= 0.45) {
            label = "很好";
        } else if (m >= 0.15) {
            label = "不错";
        } else if (m <= -0.45) {
            label = "很低落";
        } else if (m <= -0.15) {
            label = "有点低落";
        } else {
            label = "一般";
        }
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("value", Math.round(m * 100) / 100.0);
        r.put("label", label);
        return r;
    }

    /** 用一句话描述心情，供 prompt 使用 */
    public String describe(long qqId) {
        double m = mood(qqId);
        if (m >= 0.45) {
            return "你现在心情很好，语气轻快一点。";
        }
        if (m >= 0.15) {
            return "你现在心情还不错。";
        }
        if (m <= -0.45) {
            return "你现在有点低落，话可以少一点，别装作没事。";
        }
        if (m <= -0.15) {
            return "你现在心情一般，语气别太热情。";
        }
        return "";
    }

    private double decayed(double value, String updatedAt) {
        if (updatedAt == null) {
            return value;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(updatedAt, TS);
            long hours = Duration.between(t, LocalDateTime.now()).toHours();
            if (hours <= 0) {
                return value;
            }
            double decay = Math.min(0.6, hours * 0.03);
            return value > 0 ? Math.max(0, value - decay) : Math.min(0, value + decay);
        } catch (Exception e) {
            return value;
        }
    }

    private void save(long qqId, double value) {
        String sql = "INSERT INTO user_mood(qq_id, mood, updated_at) VALUES(?,?,?) "
                + "ON CONFLICT(qq_id) DO UPDATE SET mood=excluded.mood, updated_at=excluded.updated_at";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setDouble(2, value);
            ps.setString(3, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存心情失败: {}", e.getMessage());
        }
    }
}
