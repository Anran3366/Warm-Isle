package com.qqbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.Persona;
import com.qqbot.memory.PersonaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日复盘：把当天每个用户的聊天记录交给模型，找出聊得不好的地方并给改进建议。
 * 默认每天 00:10 跑（自动部署/备份都在凌晨，错开）。
 */
@Service
public class DailyReviewService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(DailyReviewService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MIN_MESSAGES = 6;
    private static final int MAX_CHARS = 6000;

    private final MessageStore messages;
    private final Db db;
    private final DeepSeekClient llm;
    private final ObjectMapper mapper;
    private final PersonaStore personas;

    public DailyReviewService(MessageStore messages, Db db, DeepSeekClient llm,
                              ObjectMapper mapper, PersonaStore personas) {
        this.messages = messages;
        this.db = db;
        this.llm = llm;
        this.mapper = mapper;
        this.personas = personas;
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void daily() {
        accountRunner.forEach(id -> {
            try {
                run(LocalDate.now().minusDays(1).format(DAY));
            } catch (Exception e) {
                log.warn("每日复盘失败: {}", e.getMessage());
            }
    
        });
    }

    /** 复盘某一天（yyyy-MM-dd），返回生成条数 */
    public int run(String day) {
        LocalDate date;
        try {
            date = LocalDate.parse(day);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
        String from = date.format(DAY) + " 00:00:00";
        String to = date.plusDays(1).format(DAY) + " 00:00:00";
        int done = 0;
        for (Long qqId : messages.users()) {
            try {
                List<com.qqbot.memory.MessageRow> rows = messages.between(qqId, from, to);
                if (rows.size() < MIN_MESSAGES) {
                    continue;
                }
                Map<String, Object> review = reviewOne(qqId, rows);
                if (review == null) {
                    continue;
                }
                save(day, qqId, rows.size(), review);
                done++;
            } catch (Exception e) {
                log.warn("复盘失败 qq={}: {}", qqId, e.getMessage());
            }
        }
        log.info("每日复盘完成 day={} 条数={}", day, done);
        return done;
    }

    private Map<String, Object> reviewOne(long qqId, List<com.qqbot.memory.MessageRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (com.qqbot.memory.MessageRow r : rows) {
            sb.append("user".equals(r.role()) ? "用户：" : "我：")
                    .append(r.content()).append('\n');
        }
        String transcript = sb.length() > MAX_CHARS
                ? sb.substring(sb.length() - MAX_CHARS) : sb.toString();

        String prompt = """
                下面是一段聊天记录（我 = 聊天机器人，用户 = 对方）。请复盘我这边表现得不好的地方。
                只输出 JSON：
                {
                  "score": 1-5 的整数（5 最好）,
                  "humanScore": 1-5 的整数（越像真人越高）,
                  "summary": "一句话总结这段对话",
                  "issues": ["具体问题，最多4条，每条不超过30字"],
                  "suggestions": ["可执行的改进建议，最多4条，每条不超过40字"]
                }
                另外找出「AI 味」的句子：总结式收尾、客服腔、排比、过度共情、每条都给落点。
                重点看：时间/场景是否对（比如上午说晚安）、是否重复、是否敷衍、是否答非所问、
                是否过度热情或说教、是否把话题踢回给对方。没有问题就给高分和空数组。

                聊天记录：
                %s
                """.formatted(transcript);
        try {
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content", "你只输出合法 JSON。"),
                    Map.of("role", "user", "content", prompt)
            ), 0.3, 500);
            String out = result.content() == null ? "" : result.content().trim();
            int s = out.indexOf('{');
            int e = out.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return null;
            }
            JsonNode node = mapper.readTree(out.substring(s, e + 1));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", Math.max(1, Math.min(5, node.path("score").asInt(3))));
            m.put("humanScore", Math.max(1, Math.min(5, node.path("humanScore").asInt(3))));
            m.put("summary", node.path("summary").asText(""));
            m.put("issues", mapper.convertValue(node.path("issues"), List.class));
            m.put("suggestions", mapper.convertValue(node.path("suggestions"), List.class));
            return m;
        } catch (Exception e) {
            log.warn("复盘模型失败: {}", e.getMessage());
            return null;
        }
    }

    private void save(String day, long qqId, int msgCount, Map<String, Object> review) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO chat_review(day, qq_id, score, human_score, summary, issues, suggestions, msg_count, created_at) "
                             + "VALUES(?,?,?,?,?,?,?,?,datetime('now','localtime'))")) {
            ps.setString(1, day);
            ps.setLong(2, qqId);
            ps.setInt(3, ((Number) review.get("score")).intValue());
            ps.setInt(4, ((Number) review.getOrDefault("humanScore", 3)).intValue());
            ps.setString(5, String.valueOf(review.get("summary")));
            ps.setString(6, mapper.writeValueAsString(review.get("issues")));
            ps.setString(7, mapper.writeValueAsString(review.get("suggestions")));
            ps.setInt(8, msgCount);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存复盘失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> list(String day, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, day, qq_id, score, human_score, summary, issues, suggestions, msg_count, created_at "
                             + "FROM chat_review WHERE day=? ORDER BY score ASC, id DESC LIMIT ?")) {
            ps.setString(1, day);
            ps.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("day", rs.getString("day"));
                    m.put("qqId", rs.getLong("qq_id"));
                    m.put("score", rs.getInt("score"));
                    m.put("humanScore", rs.getInt("human_score"));
                    m.put("summary", rs.getString("summary"));
                    m.put("issues", mapper.readValue(rs.getString("issues"), List.class));
                    m.put("suggestions", mapper.readValue(rs.getString("suggestions"), List.class));
                    m.put("msgCount", rs.getInt("msg_count"));
                    m.put("createdAt", rs.getString("created_at"));
                    list.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("读取复盘失败: {}", e.getMessage());
        }
        return list;
    }

    /** 把一条建议追加到当前账号生效的人设末尾 */
    public Map<String, Object> apply(long reviewId) {
        Persona active = personas.active();
        if (active == null) {
            return Map.of("ok", false, "message", "当前账号还没有生效的人设");
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT suggestions FROM chat_review WHERE id=?")) {
            ps.setLong(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("ok", false, "message", "复盘记录不存在");
                }
                List<String> suggestions = mapper.readValue(rs.getString(1), List.class);
                if (suggestions == null || suggestions.isEmpty()) {
                    return Map.of("ok", false, "message", "这条复盘没有建议");
                }
                StringBuilder add = new StringBuilder("\n\n【复盘补充】");
                for (String s : suggestions) {
                    add.append("\n- ").append(s);
                }
                personas.update(active.id(), active.name(), active.content() + add);
                return Map.of("ok", true, "appended", suggestions.size());
            }
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }
}
