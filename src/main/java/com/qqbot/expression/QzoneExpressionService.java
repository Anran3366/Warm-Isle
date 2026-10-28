package com.qqbot.expression;

import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.chat.ChatMessage;
import com.qqbot.chat.MoodService;
import com.qqbot.config.BotProperties;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.memory.Db;
import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.MessageStore;
import com.qqbot.proactive.ProactiveState;
import com.qqbot.proactive.ProactiveStore;
import com.qqbot.topic.TopicBlockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 空间说说：生成草稿、判断受众、发布与历史。
 * 默认关闭，且默认不自动发布；先通过后台预览确认。
 */
@Service
public class QzoneExpressionService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(QzoneExpressionService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExpressionConfig config;
    private final Db db;
    private final OneBotRegistry registry;
    private final MoodService moods;
    private final ProactiveStore proactive;
    private final MessageStore messages;
    private final FactStore facts;
    private final DeepSeekClient llm;
    private final QzoneAudienceDecider audienceDecider;
    private final BotProperties props;
    private final TopicBlockService topicBlock;

    public QzoneExpressionService(ExpressionConfig config, Db db, OneBotRegistry registry,
                                  MoodService moods, ProactiveStore proactive, MessageStore messages,
                                  FactStore facts, DeepSeekClient llm,
                                  QzoneAudienceDecider audienceDecider, BotProperties props,
                                  TopicBlockService topicBlock) {
        this.config = config;
        this.db = db;
        this.registry = registry;
        this.moods = moods;
        this.proactive = proactive;
        this.messages = messages;
        this.facts = facts;
        this.llm = llm;
        this.audienceDecider = audienceDecider;
        this.props = props;
        this.topicBlock = topicBlock;
    }

    public Map<String, Object> draft(long qqId, String hint) {
        long inspiration = qqId > 0 ? qqId : pickWeightedUser();
        String content = generate(inspiration, hint);
        ProactiveState state = proactive.get(inspiration);
        double mood = moods.mood(inspiration);
        String moodText = describeMood(mood, state);
        boolean hasTarget = inspiration > 0;
        QzoneAudienceDecider.QzoneDecision decision = audienceDecider.decide(content, inspiration, hasTarget, moodText);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("mood", mood);
        result.put("moodText", moodText);
        result.put("audience", decision.audience().name().toLowerCase());
        result.put("ugcRight", decision.ugcRight());
        result.put("targetUins", decision.targetUins());
        result.put("reason", decision.reason());
        result.put("inspirationQq", inspiration);
        return result;
    }

    /** 目标为空时：按聊天量加权随机挑一个用户作为灵感来源 */
    private long pickWeightedUser() {
        try {
            java.util.List<Long> users = messages.users();
            if (users.isEmpty()) {
                return 0;
            }
            Map<Long, Double> weights = new LinkedHashMap<>();
            double total = 0;
            for (Long u : users) {
                double w = Math.max(1, messages.countUser(u));
                weights.put(u, w);
                total += w;
            }
            double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * total;
            for (Map.Entry<Long, Double> e : weights.entrySet()) {
                r -= e.getValue();
                if (r <= 0) {
                    return e.getKey();
                }
            }
            return users.get(0);
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> publish(String content, long qqId, String requestedAudience,
                                       List<String> images) {
        if (!config.qzoneEnabled()) {
            throw new IllegalStateException("说说功能未开启");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("说说内容不能为空");
        }
        if (topicBlock.check(content) != null) {
            throw new IllegalArgumentException("内容命中话题屏蔽，不能发布");
        }
        if (isQuiet()) {
            throw new IllegalStateException("当前是免打扰时段");
        }
        if (todayCount() >= config.maxPerDay()) {
            throw new IllegalStateException("今天已经发过说说了");
        }

        ProactiveState state = proactive.get(qqId);
        String moodText = describeMood(moods.mood(qqId), state);
        QzoneAudienceDecider.QzoneDecision decision;
        if (requestedAudience != null && !requestedAudience.isBlank()
                && !"auto".equalsIgnoreCase(requestedAudience)) {
            decision = override(requestedAudience, qqId, moodText);
        } else {
            decision = audienceDecider.decide(content, qqId, qqId > 0, moodText);
        }

        if (decision.audience() == QzoneAudienceDecider.QzoneAudience.SKIP) {
            save(qqId, content, decision, "skipped", null);
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("ok", false);
            skipped.put("status", "skipped");
            skipped.put("reason", decision.reason());
            return skipped;
        }

        OneBotClient client = currentClient();
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("NapCat 未连接");
        }
        String tid = client.sendQzoneMsg(content, images, decision.ugcRight(), decision.targetUins());
        save(qqId, content, decision, "published", tid);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("status", "published");
        result.put("tid", tid);
        result.put("audience", decision.audience().name().toLowerCase());
        result.put("ugcRight", decision.ugcRight());
        result.put("targetUins", decision.targetUins());
        result.put("reason", decision.reason());
        return result;
    }

    /** 每小时检查一次：按配置每天自动生成若干条说说草稿，等待人工审核 */
    @Scheduled(cron = "0 20 10-21 * * *")
    public void autoDraftTick() {
        accountRunner.forEach(id -> {
            if (!config.qzoneEnabled() || config.autoDraftPerDay() <= 0) {
                return;
            }
            if (isQuiet()) {
                return;
            }
            if (draftsToday() >= config.autoDraftPerDay()) {
                return;
            }
            try {
                String content = generate(0, null);
                if (content == null || content.isBlank()) {
                    return;
                }
                QzoneAudienceDecider.QzoneDecision decision =
                        audienceDecider.decide(content, 0, false, "平静");
                save(0, content, decision, "draft", null);
                log.info("自动生成说说草稿: {}", content);
            } catch (Exception e) {
                log.warn("自动生成说说失败: {}", e.getMessage());
            }
    
        });
    }

    /** 定向说说显示成「QQ（昵称）」 */
    private String nicknameOf(String qq) {
        if (qq == null || qq.isBlank()) {
            return null;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT nickname FROM friend_profile WHERE qq_id=?")) {
            ps.setLong(1, Long.parseLong(qq.trim()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String n = rs.getString(1);
                    return n == null || n.isBlank() ? null : n;
                }
            }
        } catch (Exception ignored) {
            // 没有资料就返回 null
        }
        return null;
    }

    private int draftsToday() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM qzone_post WHERE status='draft' AND date(created_at)=date('now','localtime')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 待审核的说说草稿 */
    public Map<String, Object> drafts() {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, content, audience, ugc_right, target_uins, reason, created_at "
                             + "FROM qzone_post WHERE status='draft' ORDER BY id DESC LIMIT 50");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getLong("id"));
                item.put("content", rs.getString("content"));
                item.put("audience", rs.getString("audience"));
                item.put("ugcRight", rs.getInt("ugc_right"));
                String targetUins = rs.getString("target_uins");
                item.put("targetUins", targetUins);
                item.put("targetNickname", nicknameOf(targetUins));
                item.put("reason", rs.getString("reason"));
                item.put("createdAt", rs.getString("created_at"));
                items.add(item);
            }
        } catch (Exception e) {
            log.warn("读取说说草稿失败: {}", e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    /** 修改草稿受众 */
    public Map<String, Object> updateDraftAudience(long id, String audience) {
        QzoneAudienceDecider.QzoneDecision decision = override(audience, 0, "平静");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE qzone_post SET audience=?, ugc_right=?, target_uins=?, reason=? "
                             + "WHERE id=? AND status='draft'")) {
            ps.setString(1, decision.audience().name().toLowerCase());
            ps.setInt(2, decision.ugcRight());
            ps.setString(3, decision.targetUins().isEmpty() ? null : decision.targetUins().get(0).toString());
            ps.setString(4, decision.reason());
            ps.setLong(5, id);
            ps.executeUpdate();
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        return Map.of("ok", true, "audience", decision.audience().name().toLowerCase());
    }

    /** 发布草稿：成功后删掉草稿记录 */
    public Map<String, Object> publishDraft(long id) {
        String content = null;
        String audience = "auto";
        long qqId = 0;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT content, audience, qq_id FROM qzone_post WHERE id=? AND status='draft'")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    content = rs.getString("content");
                    audience = rs.getString("audience");
                    qqId = rs.getLong("qq_id");
                }
            }
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        if (content == null) {
            return Map.of("ok", false, "message", "草稿不存在");
        }
        try {
            Map<String, Object> result = publish(content, qqId, audience, List.of());
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM qzone_post WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return result;
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> deleteDraft(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM qzone_post WHERE id=? AND status='draft'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        return Map.of("ok", true);
    }

    public Map<String, Object> history(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, qq_id, content, audience, ugc_right, target_uins, tid, status, reason, "
                             + "created_at, published_at FROM qzone_post WHERE status <> 'draft' ORDER BY id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, s);
            ps.setInt(2, (p - 1) * s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("qqId", rs.getLong("qq_id"));
                    item.put("content", rs.getString("content"));
                    item.put("audience", rs.getString("audience"));
                    item.put("ugcRight", rs.getInt("ugc_right"));
                    String targetUins = rs.getString("target_uins");
                    item.put("targetUins", targetUins);
                    item.put("targetNickname", nicknameOf(targetUins));
                    item.put("tid", rs.getString("tid"));
                    item.put("status", rs.getString("status"));
                    item.put("reason", rs.getString("reason"));
                    item.put("createdAt", rs.getString("created_at"));
                    item.put("publishedAt", rs.getString("published_at"));
                    items.add(item);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取说说历史失败: " + e.getMessage(), e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", countAll());
        return result;
    }

    private String generate(long qqId, String hint) {
        if (qqId <= 0) {
            qqId = pickWeightedUser();
        }
        List<ChatMessage> recent = messages.recent(qqId, 6);
        List<Fact> active = facts.active(qqId);
        StringBuilder context = new StringBuilder();
        if (!recent.isEmpty()) {
            context.append("最近聊天：\n");
            for (ChatMessage m : recent) {
                context.append("user".equals(m.role()) ? "对方：" : "我：").append(m.content()).append('\n');
            }
        }
        if (!active.isEmpty()) {
            context.append("关于对方的事实（不要直接暴露隐私）：\n");
            int n = 0;
            for (Fact f : active) {
                if (n++ >= 8) {
                    break;
                }
                context.append("- ").append(f.key()).append("：").append(f.value()).append('\n');
            }
        }
        String prompt = """
                你是 QQ 空间说说作者。根据下面背景写一条简短说说。
                
                要求：
                1. 40-80 个汉字，口语化，像真人随手发的，不要标题、不要引号、不要解释。
                2. 可以写生活小事、兴趣、天气、最近的心情；不要提 AI、不要报时间、不要列清单。
                3. 不要直接写对方的姓名、QQ 号、住址、工作单位、病症等隐私。
                4. 如果这条内容明显只和某个人有关，写成“只有 TA 能看懂”的程度，但不要出现隐私细节。
                5. 只输出说说正文。
                
                背景：
                %s
                额外提示：%s
                """.formatted(context, hint == null ? "无" : hint);
        try {
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content", props.getPersona().getPrompt()),
                    Map.of("role", "user", "content", prompt)
            ), 1.0, 160);
            String text = result.content() == null ? "" : result.content().trim()
                    .replaceAll("^[\"'“”]+|[\"'“”]+$", "");
            if (text.isBlank()) {
                throw new IllegalStateException("模型没有生成内容");
            }
            return text.length() > 120 ? text.substring(0, 120) : text;
        } catch (Exception e) {
            log.warn("生成说说失败，使用兜底文案: {}", e.getMessage());
            return "今天有点想发呆，先这样吧。";
        }
    }

    private QzoneAudienceDecider.QzoneDecision override(String requested, long qqId, String mood) {
        return switch (requested.toLowerCase()) {
            case "public" -> new QzoneAudienceDecider.QzoneDecision(
                    QzoneAudienceDecider.QzoneAudience.PUBLIC, config.publicUgcRight(), List.of(), "手动指定公开");
            case "targeted" -> qqId > 0
                    ? new QzoneAudienceDecider.QzoneDecision(
                    QzoneAudienceDecider.QzoneAudience.TARGETED, 16, List.of(qqId), "手动指定只给该用户")
                    : new QzoneAudienceDecider.QzoneDecision(
                    QzoneAudienceDecider.QzoneAudience.PRIVATE, 64, List.of(), "没有目标用户，改为仅自己");
            case "private" -> new QzoneAudienceDecider.QzoneDecision(
                    QzoneAudienceDecider.QzoneAudience.PRIVATE, 64, List.of(), "手动指定仅自己");
            default -> audienceDecider.decide("", qqId, qqId > 0, mood);
        };
    }

    private void save(long qqId, String content, QzoneAudienceDecider.QzoneDecision decision,
                      String status, String tid) {
        String now = LocalDateTime.now().format(TS);
        String target = decision.targetUins().isEmpty() ? null : decision.targetUins().get(0).toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO qzone_post(qq_id, content, audience, ugc_right, target_uins, tid, status, "
                             + "reason, created_at, published_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, qqId);
            ps.setString(2, content);
            ps.setString(3, decision.audience().name().toLowerCase());
            ps.setInt(4, decision.ugcRight());
            ps.setString(5, target);
            ps.setString(6, tid);
            ps.setString(7, status);
            ps.setString(8, decision.reason());
            ps.setString(9, now);
            ps.setString(10, "published".equals(status) ? now : null);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存说说记录失败: {}", e.getMessage());
        }
    }

    private int todayCount() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM qzone_post WHERE status='published' AND created_at>=?")) {
            ps.setString(1, LocalDate.now() + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private int countAll() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM qzone_post");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isQuiet() {
        LocalTime now = LocalTime.now();
        LocalTime from = config.quietFrom();
        LocalTime to = config.quietTo();
        if (from.equals(to)) {
            return false;
        }
        if (from.isBefore(to)) {
            return !now.isBefore(from) && now.isBefore(to);
        }
        return !now.isBefore(from) || now.isBefore(to);
    }

    private String describeMood(double mood, ProactiveState state) {
        if (state != null && state.mood() != null && !state.mood().isBlank()) {
            return state.mood();
        }
        if (mood >= 0.4) {
            return "开心";
        }
        if (mood <= -0.4) {
            return "低落";
        }
        return "平静";
    }

    private OneBotClient currentClient() {
        Long botId = BotContext.get();
        return botId == null ? null : registry.get(botId);
    }
}
