package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.MessageRow;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表达方式挖掘：从「她说过、对方接住了」的句子里抽候选说法。
 *
 * <p>每周日 02:10 跑；只产 pending，等人工审核。每条 ≤60 字，最多 5 条/人/周。</p>
 */
@Service
public class ExpressionMiner {

    private static final Logger log = LoggerFactory.getLogger(ExpressionMiner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_USERS = 20;
    private static final int MAX_PAIRS = 30;

    private final MessageStore messages;
    private final ExpressionService expressions;
    private final DeepSeekClient llm;
    private final SettingStore settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExpressionMiner(MessageStore messages, ExpressionService expressions,
                           DeepSeekClient llm, SettingStore settings) {
        this.messages = messages;
        this.expressions = expressions;
        this.llm = llm;
        this.settings = settings;
    }

    @Scheduled(cron = "0 10 2 * * 0", zone = "Asia/Shanghai")
    public void weekly() {
        if (!"true".equalsIgnoreCase(settings.get("memory.evolve.enabled", "true"))) {
            return;
        }
        int n = 0;
        for (Long qqId : messages.users()) {
            if (n++ >= MAX_USERS) {
                break;
            }
            try {
                mine(qqId, 7);
            } catch (Exception e) {
                log.warn("表达挖掘失败 qqId={}: {}", qqId, e.getMessage());
            }
        }
    }

    /** @return 新写入的候选条数 */
    public int mine(long qqId, int days) {
        LocalDateTime now = LocalDateTime.now();
        List<MessageRow> rows = messages.between(qqId,
                now.minusDays(Math.max(1, days)).format(TS), now.format(TS));
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < rows.size() && pairs.size() < MAX_PAIRS; i++) {
            MessageRow a = rows.get(i);
            MessageRow b = rows.get(i + 1);
            if (!"assistant".equals(a.role()) || !"user".equals(b.role())) {
                continue;
            }
            if (!positiveFollowUp(a.createdAt(), b.createdAt(), b.content())) {
                continue;
            }
            pairs.add("小满：" + a.content() + "\n对方：" + b.content());
        }
        if (pairs.size() < 4) {
            return 0;
        }
        StringBuilder input = new StringBuilder();
        for (String pair : pairs) {
            input.append(pair).append("\n---\n");
        }
        try {
            LlmResult result = llm.chat(List.of(
                    Map.of("role", "system", "content", """
                            你从小满的聊天记录里，挑出「她说得自然、对方接住了」的说法。
                            输出 JSON 数组，最多 5 条：
                            [{"situation":"什么场景（≤12字）","text":"她当时的说法（≤40字）"}]
                            只学语气和句式，不要照抄整段，不要敏感内容，不要解释。
                            """),
                    Map.of("role", "user", "content", input.toString())), 0.4, 500, qqId);
            JsonNode root = parse(result.content());
            if (root == null) {
                return 0;
            }
            int n = 0;
            for (JsonNode item : root) {
                String situation = item.path("situation").asText("").trim();
                String text = item.path("text").asText("").trim();
                if (situation.isEmpty() || text.isEmpty() || text.length() > 60) {
                    continue;
                }
                if (expressions.addPending(qqId, situation, text) > 0) {
                    n++;
                }
                if (n >= 5) {
                    break;
                }
            }
            if (n > 0) {
                log.info("表达挖掘 qqId={} 新增 {} 条候选", qqId, n);
            }
            return n;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static boolean positiveFollowUp(String aAt, String bAt, String userText) {
        if (userText == null || userText.length() < 2 || userText.length() > 40) {
            return false;
        }
        if (userText.contains("不") || userText.contains("别") || userText.contains("错")
                || userText.contains("烦") || userText.contains("无语") || userText.contains("？")
                || userText.contains("?")) {
            return false;
        }
        try {
            LocalDateTime a = LocalDateTime.parse(aAt, TS);
            LocalDateTime b = LocalDateTime.parse(bAt, TS);
            return Duration.between(a, b).toSeconds() <= 180;
        } catch (Exception e) {
            return true;
        }
    }

    private JsonNode parse(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
