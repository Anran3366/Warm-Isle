package com.qqbot.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆抽取：把一段对话交给模型，抽成 事实卡 + 当日摘要 写入 SQLite。
 *
 * <p>记忆 v2 起同时双写 memories（facts 只读保留，双写一周后再切），
 * 这样新抽取的内容当天就能被 {@link MemoryRecall} 检索到，不必等夜间整理。
 *
 * 触发方式：
 * 1. 每天 12:00:05 定时跑（谷价起点）
 * 2. 启动时补跑：上次抽取超过 36 小时
 * 3. 手动：POST /api/memory/extract?qqId=...
 *
 * 水位线存在 settings.last_extracted_at:<qqId>，抽取失败不推进。
 */
@Service
public class MemoryExtractor {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WATERMARK_PREFIX = "last_extracted_at:";

    private final MessageStore messages;
    private final FactStore facts;
    private final MemoryStore memories;
    private final SummaryStore summaries;
    private final SettingStore settings;
    private final DeepSeekClient llm;
    private final UsageStore usage;
    private final ObjectMapper mapper;
    private final BotProperties props;

    public MemoryExtractor(MessageStore messages, FactStore facts, MemoryStore memories,
                           SummaryStore summaries, SettingStore settings, DeepSeekClient llm,
                           UsageStore usage, ObjectMapper mapper, BotProperties props) {
        this.messages = messages;
        this.facts = facts;
        this.memories = memories;
        this.summaries = summaries;
        this.settings = settings;
        this.llm = llm;
        this.usage = usage;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(cron = "5 0 12 * * *")
    public void dailyExtract() {
        accountRunner.forEach(id -> {
            LocalDateTime now = LocalDateTime.now();
            log.info("每日记忆抽取开始");
            for (Long qqId : messages.users()) {
                try {
                    extract(qqId, now);
                } catch (Exception e) {
                    log.error("抽取失败 qqId={}", qqId, e);
                }
            }
    
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        LocalDateTime now = LocalDateTime.now();
        for (Long qqId : messages.users()) {
            String wm = settings.get(WATERMARK_PREFIX + qqId, null);
            if (wm == null) {
                continue;
            }
            try {
                LocalDateTime last = LocalDateTime.parse(wm, TS);
                if (Duration.between(last, now).toHours() >= 36) {
                    log.info("补跑抽取 qqId={} 上次={}", qqId, wm);
                    extract(qqId, now);
                }
            } catch (Exception e) {
                log.warn("补跑判断失败 qqId={}: {}", qqId, e.getMessage());
            }
        }
    }

    /**
     * 抽取某用户自水位线以来的新消息。
     *
     * @return 本次写入/更新的事实条数
     */
    public synchronized int extract(long qqId, LocalDateTime now) {
        return extract(qqId, now, false);
    }

    /**
     * @param full true 时忽略水位线，重扫最近 24 小时（修数据用）
     */
    public synchronized int extract(long qqId, LocalDateTime now, boolean full) {
        String key = WATERMARK_PREFIX + qqId;
        String wmRaw = settings.get(key, null);
        LocalDateTime from = (full || wmRaw == null) ? now.minusHours(24) : LocalDateTime.parse(wmRaw, TS);

        List<MessageRow> rows = messages.between(qqId, from.format(TS), now.format(TS));
        if (rows.size() < 2) {
            log.debug("qqId={} 窗口内消息不足，跳过抽取", qqId);
            return 0;
        }

        List<Map<String, String>> prompt = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildTranscript(rows)));

        LlmResult result;
        try {
            result = llm.chat(prompt, 0.2, 1200);
        } catch (Exception e) {
            log.error("抽取调用失败 qqId={}: {}", qqId, e.getMessage());
            return 0;
        }

        List<JsonNode> items = new ArrayList<>();
        String summary = "";
        JsonNode root = parseJsonObject(result.content());
        if (root != null) {
            JsonNode arr = root.path("facts");
            if (arr.isArray()) {
                arr.forEach(items::add);
            }
            summary = root.path("summary").asText("").trim();
        } else {
            items = parseFactsArray(result.content());
        }

        int count = 0;
        for (JsonNode item : items) {
            String category = item.path("category").asText("其他").trim();
            String factKey = item.path("key").asText("").trim();
            String value = item.path("value").asText("").trim();
            double confidence = item.path("confidence").asDouble(0.8);
            if (factKey.isEmpty() || value.isEmpty()) {
                continue;
            }
            try {
                facts.upsert(qqId, category, factKey, value, confidence);
                // 双写：facts 继续写（回滚依据），同时进 memories
                memories.upsert(qqId, new MemoryStore.Draft(
                        MemoryStore.mapFactType(category), "user", factKey, value, "chat",
                        MemoryStore.mapFactImportance(category), confidence, 0,
                        null, null, null));
                count++;
            } catch (Exception e) {
                log.warn("写入事实失败: {}", e.getMessage());
            }
        }

        if (!summary.isEmpty()) {
            try {
                summaries.upsert(qqId, now.toLocalDate().toString(), summary);
            } catch (Exception e) {
                log.warn("写入摘要失败: {}", e.getMessage());
            }
        }

        // 成功才推进水位线
        settings.set(key, now.format(TS));
        usage.add(props.getLlm().getModel(), result.promptTokens(), result.completionTokens(),
                llm.cost(result.promptTokens(), result.completionTokens()));

        log.info("抽取完成 qqId={} 消息数={} 事实数={} 摘要={}字", qqId, rows.size(), count, summary.length());
        return count;
    }

    private String buildTranscript(List<MessageRow> rows) {
        StringBuilder sb = new StringBuilder("以下是最近一段 QQ 私聊记录：\n");
        for (MessageRow r : rows) {
            String who = "user".equals(r.role()) ? "用户" : "我";
            sb.append('[').append(r.createdAt()).append("] ").append(who).append("：")
                    .append(r.content()).append('\n');
        }
        sb.append("\n请抽取事实并写一段摘要。");
        return sb.toString();
    }

    /** 取第一个 { 到最后一个 } 解析 */
    private JsonNode parseJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 兜底：模型只返回了数组 */
    private List<JsonNode> parseFactsArray(String content) {
        List<JsonNode> list = new ArrayList<>();
        if (content == null) {
            return list;
        }
        String text = content.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("抽取结果无法解析: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return list;
        }
        try {
            JsonNode arr = mapper.readTree(text.substring(start, end + 1));
            if (arr.isArray()) {
                arr.forEach(list::add);
            }
        } catch (Exception e) {
            log.warn("解析事实数组失败: {}", e.getMessage());
        }
        return list;
    }

    private static final String SYSTEM_PROMPT = """
            你是记忆抽取器。从 QQ 对话记录中抽取关于「用户」的稳定事实，并写一段当日摘要。

            只输出一个 JSON 对象，不要解释文字，不要 markdown 代码块：
            {"facts":[{"category":"分类","key":"键","value":"值","confidence":0.9}],"summary":"..."}

            规则：
            1. category 只能是这五个英文值之一：profile（基本信息/职业/城市/健康）、preference（喜好/习惯/忌口）、relationship（人际/称呼）、event（日程/事件/近况）、commitment（答应过的事）。
            2. key 简短稳定，例如：称呼、城市、职业、生日、爱好、忌口、作息、宠物名。
            3. value 简洁，不带主语。注意表述准确性：
               - 用户说「从 A 转 B」「以前做 A 现在做 B」→ key=职业、value=A转B
               - 只是顺带提到的地方不要当作居住城市，只有明确说「我在/住在某地」才记录
            4. 只依据「用户」说的话，绝对不要从助手的回复里推断事实。
            5. 只记录用户明确说过的内容，不要推测；同一 key 只出现一次，取最新说法。
            6. confidence：明确陈述 0.9，上下文推断 0.6，含糊 0.4。
            7. summary 用 1-2 句话概括这次聊天的主要内容与用户状态，不超过 120 字，不要列举事实。
            8. 没有可记录的事实时 facts 输出 []，summary 仍然要写。
            """;
}
