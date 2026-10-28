package com.qqbot.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.BotAccountRunner;
import com.qqbot.config.BotProperties;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆 v2 夜间整理（00:05，与知识库共用一个低峰窗口）。
 *
 * <p>流程（设计稿第 6 节）：
 * 昨天的消息 → 生成 episode → 抽取候选记忆并按可行动性打分
 * （0.4×能否关心 + 0.25×情绪 + 0.2×时效 + 0.15×稳定性）
 * → ≥0.65 写入并安排主动回忆（宁可多问一句），0.4~0.65 只写入，&lt;0.4 丢弃
 * → 冲突合并（upsert 内部处理）→ 过期/90 天衰减（{@link MemoryStore#maintain}）。
 *
 * <p>内存保护：可用内存 &lt;500MB 直接跳过本轮，避免把协议端挤掉。
 */
@Service
public class NightlyMemoryService {

    private static final Logger log = LoggerFactory.getLogger(NightlyMemoryService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WATERMARK_PREFIX = "last_nightly_at:";
    private static final double ACTIONABLE_WRITE = 0.4;
    /**
     * 排主动回忆的阈值。设计稿原定 0.7，但实测模型给的分普遍偏保守（最高 0.68），
     * 导致「该关心」的事件永远排不出来。所有者选择「宁可多问一句」，下调到 0.65。
     */
    private static final double ACTIONABLE_FOLLOW_UP = 0.65;
    private static final int MIN_MESSAGES = 2;
    private static final int MAX_TRANSCRIPT_CHARS = 6000;
    private static final int EVENT_TTL_DAYS = 7;

    private final BotAccountRunner accountRunner;
    private final MessageStore messages;
    private final MemoryStore memories;
    private final SettingStore settings;
    private final DeepSeekClient llm;
    private final UsageStore usage;
    private final ObjectMapper mapper;
    private final BotProperties props;

    public NightlyMemoryService(BotAccountRunner accountRunner, MessageStore messages,
                                MemoryStore memories, SettingStore settings, DeepSeekClient llm,
                                UsageStore usage, ObjectMapper mapper, BotProperties props) {
        this.accountRunner = accountRunner;
        this.messages = messages;
        this.memories = memories;
        this.settings = settings;
        this.llm = llm;
        this.usage = usage;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void nightly() {
        run(false);
    }

    /**
     * @param force true 时忽略内存保护与「窗口内消息不足」，用于后台手动补跑
     */
    public synchronized Map<String, Object> run(boolean force) {
        Map<String, Object> summary = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        long avail = availableMb();
        if (!force && avail >= 0 && avail < 500) {
            log.warn("可用内存仅 {}MB，跳过夜间记忆整理", avail);
            summary.put("ok", false);
            summary.put("skipped", "low-memory");
            summary.put("availableMb", avail);
            return summary;
        }
        int[] totals = new int[4]; // accounts, users, written, dropped
        List<Map<String, Object>> detail = new ArrayList<>();
        accountRunner.forEach(botId -> {
            totals[0]++;
            for (Long qqId : messages.users()) {
                try {
                    Map<String, Object> r = nightlyOne(qqId, now, force);
                    if (Boolean.TRUE.equals(r.get("processed"))) {
                        totals[1]++;
                        totals[2] += ((Number) r.getOrDefault("written", 0)).intValue();
                        totals[3] += ((Number) r.getOrDefault("dropped", 0)).intValue();
                        r.put("botId", botId);
                        detail.add(r);
                    }
                } catch (Exception e) {
                    log.error("夜间整理失败 botId={} qqId={}", botId, qqId, e);
                }
            }
        });
        summary.put("ok", true);
        summary.put("accounts", totals[0]);
        summary.put("users", totals[1]);
        summary.put("written", totals[2]);
        summary.put("dropped", totals[3]);
        summary.put("detail", detail);
        log.info("夜间记忆整理完成 账号={} 用户={} 写入={} 丢弃={}", totals[0], totals[1], totals[2], totals[3]);
        return summary;
    }

    /** 整理单个用户；返回 processed/written/dropped/episode */
    public Map<String, Object> nightlyOne(long qqId, LocalDateTime now, boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("qqId", qqId);
        out.put("processed", false);

        // 昨天整天（含今天凌晨一点点，避免 00:00–00:05 的消息漏掉）
        LocalDate eventDay = now.toLocalDate().minusDays(1);
        String from = eventDay.atStartOfDay().format(TS);
        String to = now.format(TS);
        List<MessageRow> rows = messages.between(qqId, from, to);
        if (rows.size() < MIN_MESSAGES && !force) {
            out.put("skipped", "few-messages");
            return out;
        }
        if (rows.isEmpty()) {
            out.put("skipped", "no-messages");
            return out;
        }

        List<Map<String, String>> prompt = List.of(
                Map.of("role", "system", "content", systemPrompt(eventDay)),
                Map.of("role", "user", "content", transcript(rows)));

        LlmResult result;
        try {
            result = llm.chat(prompt, 0.2, 1600);
        } catch (Exception e) {
            log.error("夜间整理调用失败 qqId={}: {}", qqId, e.getMessage());
            out.put("skipped", "llm-failed");
            return out;
        }

        JsonNode root = parseJsonObject(result.content());
        if (root == null) {
            log.warn("夜间整理结果无法解析 qqId={}", qqId);
            out.put("skipped", "bad-json");
            return out;
        }

        JsonNode episode = root.path("episode");
        String episodeSummary = episode.path("summary").asText("").trim();
        double episodeEmotion = clamp11(episode.path("emotion").asDouble(0));
        String eventsJson = episode.path("events").isArray() ? episode.path("events").toString() : "[]";
        if (!episodeSummary.isEmpty()) {
            memories.saveEpisode(qqId, eventDay.toString(), episodeSummary, eventsJson, episodeEmotion, rows.size());
        }

        int written = 0;
        int dropped = 0;
        int followUps = 0;
        JsonNode arr = root.path("memories");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String type = normalizeType(item.path("type").asText("profile"));
                String key = item.path("key").asText("").trim();
                String value = item.path("value").asText("").trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                double care = clamp01(item.path("care").asDouble(0.5));
                double emotion = clamp11(item.path("emotion").asDouble(0));
                double timeliness = clamp01(item.path("timeliness").asDouble(0.5));
                double stability = clamp01(item.path("stability").asDouble(0.3));
                double confidence = clamp01(item.path("confidence").asDouble(0.85));
                double actionable = 0.4 * care + 0.25 * Math.abs(emotion)
                        + 0.2 * timeliness + 0.15 * stability;
                if (actionable < ACTIONABLE_WRITE) {
                    dropped++;
                    continue;
                }
                String subject = item.path("subject").asText("user").trim();
                if (subject.isEmpty()) {
                    subject = "user";
                }
                String storedKey = "event".equals(type) ? key + "@" + eventDay : key;
                String expiresAt = item.path("expiresAt").asText("").trim();
                if (expiresAt.isEmpty() && "event".equals(type)) {
                    expiresAt = eventDay.plusDays(EVENT_TTL_DAYS).toString();
                }
                String followUpAt = null;
                String actionHint = item.path("actionHint").asText("").trim();
                if (actionable >= ACTIONABLE_FOLLOW_UP) {
                    FollowUpPlanner.Plan plan = FollowUpPlanner.plan(now, type,
                            item.path("kind").asText(""), value);
                    if (plan != null) {
                        followUpAt = plan.atText();
                        if (actionHint.isEmpty()) {
                            actionHint = plan.hint();
                        }
                        followUps++;
                    }
                }
                try {
                    memories.upsert(qqId, new MemoryStore.Draft(type, subject, storedKey, value, "chat",
                            actionable, confidence, emotion,
                            expiresAt.isEmpty() ? null : expiresAt, followUpAt,
                            actionHint.isEmpty() ? null : actionHint));
                    written++;
                } catch (Exception e) {
                    log.warn("写入夜间记忆失败 qqId={}: {}", qqId, e.getMessage());
                }
            }
        }

        int[] maintained = memories.maintain(qqId, now);
        settings.set(WATERMARK_PREFIX + qqId, now.format(TS));
        usage.add(props.getLlm().getModel(), result.promptTokens(), result.completionTokens(),
                llm.cost(result.promptTokens(), result.completionTokens()));

        out.put("processed", true);
        out.put("written", written);
        out.put("dropped", dropped);
        out.put("followUps", followUps);
        out.put("episode", episodeSummary);
        out.put("expired", maintained[0]);
        out.put("archived", maintained[1]);
        log.info("夜间整理 qqId={} 消息={} 写入={} 丢弃={} 主动回忆={} 过期={} 归档={}",
                qqId, rows.size(), written, dropped, followUps, maintained[0], maintained[1]);
        return out;
    }

    private String transcript(List<MessageRow> rows) {
        StringBuilder sb = new StringBuilder("以下是昨天的 QQ 私聊记录：\n");
        for (MessageRow r : rows) {
            if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                sb.append("…（后面还有，已截断）\n");
                break;
            }
            String who = "user".equals(r.role()) ? "用户" : "我";
            sb.append('[').append(r.createdAt()).append("] ").append(who).append("：")
                    .append(r.content()).append('\n');
        }
        sb.append("\n请输出 JSON。");
        return sb.toString();
    }

    private static String systemPrompt(LocalDate eventDay) {
        return """
                你是陪聊机器人的记忆整理器。读昨天的 QQ 私聊，输出一个 JSON 对象，不要解释、不要 markdown。

                格式：
                {"episode":{"summary":"1-2 句话概括昨天聊了什么、对方状态如何（≤120字）","emotion":-1~1 的整体情绪,
                            "events":["昨天发生的事，简短"]},
                 "memories":[{"type":"profile|event|relation|world","subject":"user|bot|third:名字",
                              "key":"归一化主题，简短，如 工作/城市/加班/面试/生病",
                              "value":"具体内容，不带主语",
                              "emotion":-1~1,"care":0~1,"timeliness":0~1,"stability":0~1,
                              "confidence":0~1,"kind":"overtime|interview|sick|fight|coldwar|promise|other",
                              "expiresAt":"yyyy-MM-dd 或空","actionHint":"怎么关心，或空"}]}

                打分维度（0~1，如实给，总分由程序算，你不用算）：
                - care：这条能不能支撑一次关心/追问/延续话题（最重要）
                - timeliness：时效性，越近期越高；长期属性给低分
                - stability：长期稳定属性给高分，一次性事件给低分
                - emotion：事件的情绪色彩，负面为负、正面为正

                记（高价值）：
                - 生活状态：加班、熬夜、出差、生病、生理期
                - 情绪事件：吵架、被骂、面试、考试、家人健康
                - 稳定属性：工作、城市、家人构成、长期目标
                - 关系信息：称呼、约定、共同话题
                不记（给低分或不要输出）：
                - 一次性消费：今天吃了什么、买了什么
                - 无法行动的偏好：不爱吃香菜、喜欢蓝色
                - 天气、时事、泛泛而谈的感慨
                例外：「不爱吃香菜」若出现在「一起吃饭」的语境里，可记为 relation（饮食约定）。

                规则：
                1. 只依据「用户」说的话，不要从机器人的回复里推断。
                2. type=event 的 key 不要带日期，程序会按事件日 %s 追加。
                3. 同一件事只输出一条；同 key 取最新说法。
                4. 没有值得记的就输出 "memories":[]，episode 仍要写。
                5. confidence：明确陈述 0.9，上下文推断 0.6，含糊 0.4。
                """.formatted(eventDay);
    }

    // ---------------- 工具 ----------------

    /** 可用内存（MB），非 Linux 返回 -1 */
    static long availableMb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
        } catch (Exception ignored) {
            // 非 Linux 或读不到
        }
        return -1;
    }

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

    private static String normalizeType(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase()) {
            case "event", "relation", "world" -> type.trim().toLowerCase();
            default -> "profile";
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clamp11(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
