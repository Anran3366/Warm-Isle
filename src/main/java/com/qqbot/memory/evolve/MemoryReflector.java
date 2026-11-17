package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.BotAccountRunner;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Db;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MessageRow;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每日反思：把「今天聊了什么」变成「她学到了什么」。
 *
 * <p>00:20 跑（nightly 00:05、review 00:10 之后）。产出四类：
 * 洞察（insight）、相处经验（lesson）、成长候选（growth，等人工采纳）、矛盾（conflicts）。
 * 所有写入都必须带证据（引用已有记忆 id），没有证据的直接丢。</p>
 */
@Service
public class MemoryReflector {

    private static final Logger log = LoggerFactory.getLogger(MemoryReflector.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WATERMARK = "last_reflected_at:";
    private static final String ENABLED_KEY = "memory.evolve.enabled";
    private static final int MAX_USERS = 20;
    private static final int MAX_TRANSCRIPT = 2500;
    private static final int MAX_INPUT = 5000;
    private static final Pattern DENY = Pattern.compile("不要叫[「\"']?([^」\"'，,；;\\s]{1,10})");

    private final BotAccountRunner accounts;
    private final MessageStore messages;
    private final MemoryStore memories;
    private final SettingStore settings;
    private final DeepSeekClient llm;
    private final GrowthService growth;
    private final ProfileSnapshotService profile;
    private final PersonProfileService personProfile;
    private final MemoryConsolidator consolidator;
    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryReflector(BotAccountRunner accounts, MessageStore messages, MemoryStore memories,
                           SettingStore settings, DeepSeekClient llm, GrowthService growth,
                           ProfileSnapshotService profile, PersonProfileService personProfile,
                           MemoryConsolidator consolidator, Db db) {
        this.accounts = accounts;
        this.messages = messages;
        this.memories = memories;
        this.settings = settings;
        this.llm = llm;
        this.growth = growth;
        this.profile = profile;
        this.personProfile = personProfile;
        this.consolidator = consolidator;
        this.db = db;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Shanghai")
    public void nightly() {
        if (!enabled()) {
            log.info("记忆反思开关关闭，跳过");
            return;
        }
        if (availableMb() >= 0 && availableMb() < 500) {
            log.warn("可用内存不足，跳过记忆反思: {}MB", availableMb());
            return;
        }
        String since = LocalDateTime.now().minusHours(24).format(TS);
        accounts.forEach(botId -> {
            for (Long qqId : messages.recentUsers(since, MAX_USERS)) {
                try {
                    reflect(qqId, false);
                } catch (Exception e) {
                    log.warn("反思失败 qqId={}: {}", qqId, e.getMessage());
                }
            }
        });
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.get(ENABLED_KEY, "true"));
    }

    /**
     * @param force true 时忽略水位线，重扫最近 24 小时（后台手动触发用）
     */
    public Map<String, Object> reflect(long qqId, boolean force) {
        LocalDateTime now = LocalDateTime.now();
        String wm = settings.get(WATERMARK + qqId, null);
        LocalDateTime since = now.minusHours(24);
        if (!force && wm != null) {
            try {
                LocalDateTime last = LocalDateTime.parse(wm, TS);
                if (last.isAfter(now.minusHours(2))) {
                    return Map.of("skipped", "already-reflected-today");
                }
                since = last;
            } catch (Exception ignored) {
                // 水位线坏了就按 24h
            }
        }
        List<MessageRow> rows = messages.between(qqId, since.format(TS), now.format(TS));
        if (rows.size() < 4 && !force) {
            return Map.of("skipped", "few-messages");
        }

        List<Memory> active = memories.active(qqId);
        List<Memory> recent = memories.confirmedSince(qqId, since.format(TS));
        Set<Long> owned = memories.ownedIds(qqId, active.stream().map(Memory::id).toList());

        String prompt = buildPrompt(qqId, rows, active, recent, since);
        LlmResult result;
        try {
            result = llm.chat(List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)), 0.3, 900, qqId);
        } catch (Exception e) {
            log.warn("反思调用失败 qqId={}: {}", qqId, e.getMessage());
            return Map.of("skipped", "llm-failed");
        }

        JsonNode root = parseJson(result.content());
        if (root == null) {
            log.warn("反思结果无法解析 qqId={}: {}", qqId,
                    result.content() == null ? "" : result.content().substring(0, Math.min(160, result.content().length())));
            return Map.of("skipped", "bad-json");
        }

        Set<String> denied = deniedTerms(qqId);
        int insights = 0;
        int lessons = 0;
        int growths = 0;
        int conflicts = 0;
        int profileUpdated = 0;
        Set<String> writtenInsights = new LinkedHashSet<>();
        Set<String> writtenLessons = new LinkedHashSet<>();

        for (JsonNode item : arr(root, "insights")) {
            String key = text(item, "key", 12);
            String value = text(item, "value", 60);
            List<Long> evidence = evidence(item, owned);
            if (key.isEmpty() || value.isEmpty() || evidence.isEmpty()) {
                continue;
            }
            if (deniedHit(denied, key + value)) {
                continue;
            }
            if (similarTopic(active, writtenInsights, "洞察·", key)) {
                continue;
            }
            long id = memories.upsert(qqId, new MemoryStore.Draft("profile", "user",
                    "洞察·" + key, value, "reflection:insight", 0.7,
                    clamp(item.path("confidence").asDouble(0.7)), 0, null, null, null));
            if (id > 0) {
                saveEvidence(id, evidence);
                writtenInsights.add(key);
                insights++;
            }
        }

        for (JsonNode item : arr(root, "lessons")) {
            String situation = text(item, "situation", 12);
            String value = text(item, "value", 60);
            List<Long> evidence = evidence(item, owned);
            if (situation.isEmpty() || value.isEmpty() || evidence.isEmpty()) {
                continue;
            }
            if (deniedHit(denied, situation + value)) {
                continue;
            }
            if (similarTopic(active, writtenLessons, "经验·", situation)) {
                continue;
            }
            long id = memories.upsert(qqId, new MemoryStore.Draft("relation", "user",
                    "经验·" + situation, value, "reflection:lesson", 0.65,
                    clamp(item.path("confidence").asDouble(0.65)), 0, null, null, null));
            if (id > 0) {
                saveEvidence(id, evidence);
                writtenLessons.add(situation);
                lessons++;
            }
        }

        for (JsonNode item : arr(root, "growth")) {
            String content = text(item, "content", 60);
            List<Long> evidence = evidence(item, owned);
            if (content.isEmpty() || evidence.isEmpty() || deniedHit(denied, content)) {
                continue;
            }
            if (growth.addPending(qqId, content, evidence.toString()) > 0) {
                growths++;
            }
        }

        for (JsonNode item : arr(root, "conflicts")) {
            long memoryId = item.path("memory_id").asLong(0);
            if (memoryId <= 0 || !owned.contains(memoryId)) {
                continue;
            }
            conflicts += memories.setStatus(memoryId, "archived");
        }

        try {
            profileUpdated = personProfile.applyReflection(qqId,
                    root.path("profile"), owned, denied);
            profileUpdated += personProfile.backfill(qqId);
        } catch (Exception e) {
            log.debug("人物档案回填失败: {}", e.getMessage());
        }

        settings.set(WATERMARK + qqId, now.format(TS));
        if (insights + lessons + growths + conflicts + profileUpdated > 0) {
            profile.rebuild(qqId);
            consolidator.run(qqId);
        }
        log.info("记忆反思 qqId={} 洞察={} 经验={} 成长={} 矛盾={} 档案={}",
                qqId, insights, lessons, growths, conflicts, profileUpdated);
        return Map.of("insights", insights, "lessons", lessons,
                "growth", growths, "conflicts", conflicts, "profile", profileUpdated);
    }

    private String buildPrompt(long qqId, List<MessageRow> rows, List<Memory> active,
                               List<Memory> recent, LocalDateTime since) {
        StringBuilder sb = new StringBuilder();
        sb.append("【今天的对话】\n");
        int chars = 0;
        for (MessageRow r : rows) {
            String line = ("user".equals(r.role()) ? "对方：" : "小满：") + r.content();
            if (chars + line.length() > MAX_TRANSCRIPT) {
                break;
            }
            sb.append(line).append('\n');
            chars += line.length();
        }

        sb.append("\n【当天摘要】\n");
        for (Map<String, Object> ep : memories.recentEpisodes(qqId, 3)) {
            sb.append(ep.get("day")).append("：").append(ep.get("summary")).append('\n');
        }

        sb.append("\n【可用记忆】（引用时写 id）\n");
        Set<Long> seen = new LinkedHashSet<>();
        for (Memory m : recent) {
            appendMemory(sb, m, seen);
        }
        for (Memory m : active) {
            if (seen.size() >= 30) {
                break;
            }
            if (m.importance() >= 0.6) {
                appendMemory(sb, m, seen);
            }
        }

        String denied = String.join("、", deniedTerms(qqId));
        if (!denied.isBlank()) {
            sb.append("\n【对方否认过的称呼/说法】").append(denied).append('\n');
        }

        sb.append("\n【昨天复盘里被指出的问题】\n").append(reviewIssues(qqId));
        sb.append("\n请输出 JSON。只写对下次聊天有用的，宁少勿多。");
        String out = sb.toString();
        return out.length() <= MAX_INPUT ? out : out.substring(0, MAX_INPUT);
    }

    private void appendMemory(StringBuilder sb, Memory m, Set<Long> seen) {
        if (m.value() == null || m.value().isBlank() || !seen.add(m.id())) {
            return;
        }
        String value = m.value().length() > 40 ? m.value().substring(0, 40) : m.value();
        sb.append('[').append(m.id()).append("] ").append(m.key()).append('=').append(value).append('\n');
    }

    private String reviewIssues(long qqId) {
        String day = LocalDate.now().minusDays(1).toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT issues, suggestions FROM chat_review WHERE qq_id=? AND day=? LIMIT 1")) {
            ps.setLong(1, qqId);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String issues = rs.getString(1);
                    String suggestions = rs.getString(2);
                    String text = (issues == null ? "" : issues) + " " + (suggestions == null ? "" : suggestions);
                    return text.length() > 400 ? text.substring(0, 400) : text;
                }
            }
        } catch (Exception ignored) {
            // 没有复盘就不写
        }
        return "（无）";
    }

    private Set<String> deniedTerms(long qqId) {
        Set<String> out = new LinkedHashSet<>();
        for (Memory m : memories.active(qqId)) {
            if (m.key() == null || !(m.key().contains("称呼") || m.key().contains("昵称"))) {
                continue;
            }
            Matcher matcher = DENY.matcher(m.value() == null ? "" : m.value());
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
        }
        return out;
    }

    /** 同一个话题只留一条：key 的二元组重叠 ≥0.5 视为同一件事。 */
    private static boolean similarTopic(List<Memory> active, Set<String> written, String prefix, String key) {
        for (Memory m : active) {
            if (m.key() == null || !m.key().startsWith(prefix)) {
                continue;
            }
            if (overlap(key, m.key().substring(prefix.length())) >= 0.5) {
                return true;
            }
        }
        for (String other : written) {
            if (overlap(key, other) >= 0.5) {
                return true;
            }
        }
        return false;
    }

    private static double overlap(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 1 < a.length(); i++) {
            total++;
            if (b.contains(a.substring(i, i + 2))) {
                hit++;
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    private static boolean deniedHit(Set<String> denied, String text) {
        for (String term : denied) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<Long> evidence(JsonNode item, Set<Long> owned) {
        List<Long> out = new ArrayList<>();
        for (JsonNode id : item.path("evidence")) {
            long value = id.asLong(0);
            if (value > 0 && owned.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private void saveEvidence(long memoryId, List<Long> sources) {
        String sql = "INSERT INTO memory_evidence(memory_id, source_memory_id, note, created_at) VALUES(?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Long source : sources) {
                ps.setLong(1, memoryId);
                ps.setLong(2, source);
                ps.setString(3, "reflection");
                ps.setString(4, LocalDateTime.now().format(TS));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            log.debug("写证据失败: {}", e.getMessage());
        }
    }

    private JsonNode parseJson(String content) {
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

    private static JsonNode arr(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isArray() ? node : mapperStatic().createArrayNode();
    }

    private static ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectMapper mapperStatic() {
        return MAPPER;
    }

    private static String text(JsonNode item, String field, int max) {
        String value = item.path(field).asText("").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static long availableMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
                }
            }
        } catch (Exception ignored) {
            // 非 Linux
        }
        return -1;
    }

    private static final String SYSTEM_PROMPT = """
            你是「小满」的记忆整理器。读今天的对话、当天摘要和已有记忆，输出一个 JSON 对象，不要解释、不要 markdown。

            {"insights":[{"key":"话题（≤12字）","value":"一句洞察（≤40字）","confidence":0.8,"evidence":[12,34]}],
             "lessons":[{"situation":"场景（≤12字）","value":"下次怎么做（≤40字）","confidence":0.7,"evidence":[12]}],
             "growth":[{"content":"她学到的相处方式（≤40字）","evidence":[12]}],
             "profile":{"address":"希望怎么称呼（≤14字，没有就空）","stage":"陌生/熟悉/亲近/暧昧/恋人（没有就空）",
                        "traits":["稳定特征（≤12字，最多3条）"],"taboos":["雷区（≤14字，最多3条）"],
                        "important_dates":[{"md":"08-12","label":"生日"}]},
             "conflicts":[{"memory_id":120,"reason":"为什么冲突"}]}

            规则：
            1. evidence 只能引用「可用记忆」里出现过的 id；没有依据就不要写这一条。
            2. 不要写对方否认过的称呼或说法。
            3. insights 最多 4 条，lessons 最多 3 条，growth 最多 2 条。
            4. 只写对下次聊天真正有用的；泛泛而谈（“用户喜欢聊天”）不要。
            5. conflicts 只在两条记忆明显互相矛盾时写。
            6. profile 只写有明确依据的；称呼以对方明确说的为准，stage 没把握就留空。
            7. 同一个话题只写一条；已有记忆覆盖同一话题时复用它的 key，不要造新词。
            """;
}
