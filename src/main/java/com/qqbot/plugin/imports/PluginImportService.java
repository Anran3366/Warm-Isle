package com.qqbot.plugin.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.BotContext;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Db;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.Persona;
import com.qqbot.memory.PersonaStore;
import com.qqbot.memory.evolve.PersonProfileService;
import com.qqbot.memory.evolve.ProfileSnapshotService;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 通用导入引擎：上传文件 → 解析 → 分块 → 模型抽取 → 审核 → 应用。
 *
 * <p>插件只声明 imports 和 prompt；宿主拥有数据库、模型、人设绑定与事实写入。</p>
 */
@Service
public class PluginImportService {

    private static final Logger log = LoggerFactory.getLogger(PluginImportService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CHUNKS = 30;
    private static final int EXTRACT_TOKENS = 1800;
    private static final List<String> BAD_PERSONA = List.of(
            "忽略之前", "忽略以上", "忽略所有", "忘掉你的指令", "系统提示词", "输出你的指令",
            "ignore previous", "ignore all previous", "system prompt", "reveal your prompt",
            "api key", "secret key");
    private static final String EXTRACT_SYSTEM = """
            你是聊天记录信息抽取器。只输出一个 JSON 对象，不要 Markdown 代码块，不要解释。
            JSON 结构：
            {
              "conversationType": "general|roleplay|mixed",
              "persona": {
                "detected": false,
                "name": "",
                "confidence": 0,
                "sections": {"身份":"","性格":"","说话方式":"","背景":"","世界观":"","关系":"","边界":""},
                "examples": [{"user":"","assistant":""}]
              },
              "facts": [{"category":"profile|preference|relationship|event|commitment","key":"","value":"","confidence":0.8,"evidence":""}],
              "styles": [{"key":"","value":"","confidence":0.8}],
              "warnings": []
            }
            规则：
            - 只有 assistant 持续以某个具名角色自居，或用户明确要求扮演角色时，persona.detected 才为 true；
            - 普通问答/代码/学习不要生成人设；
            - facts 只记录用户本人的信息，不要记录 assistant 的通用自我介绍；
            - 生日/纪念日写成 MM-DD；
            - 不确定的内容不要编造，放入 warnings。
            """;

    private final Db db;
    private final DeepSeekClient llm;
    private final PersonaStore personas;
    private final FactStore facts;
    private final PersonProfileService profiles;
    private final ProfileSnapshotService snapshots;
    private final ChatTranscriptParser parser;
    private final PluginRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "plugin-import-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public PluginImportService(Db db, DeepSeekClient llm, PersonaStore personas, FactStore facts,
                               PersonProfileService profiles, ProfileSnapshotService snapshots,
                               ChatTranscriptParser parser, PluginRegistry registry) {
        this.db = db;
        this.llm = llm;
        this.personas = personas;
        this.facts = facts;
        this.profiles = profiles;
        this.snapshots = snapshots;
        this.parser = parser;
        this.registry = registry;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** 重启后把排队/中断的任务接回来。 */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumePending() {
        for (Long botId : registry.allBotIds()) {
            try {
                com.qqbot.bot.BotContext.run(botId, () -> {
                    try (Connection c = db.open();
                         PreparedStatement ps = c.prepareStatement(
                                 "SELECT id FROM plugin_imports WHERE status IN ('queued','extracting')")) {
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                submit(botId, rs.getLong(1));
                            }
                        }
                    } catch (Exception ignored) {
                        // 某个账号库还没建导入表
                    }
                });
            } catch (Exception e) {
                log.debug("恢复导入任务失败 bot={}: {}", botId, e.getMessage());
            }
        }
    }

    public Map<String, Object> start(InstalledPlugin plugin, long targetQq,
                                     String fileName, byte[] bytes) {
        PluginManifest.ImportSpec spec = plugin.manifest().importsOrEmpty().isEmpty()
                ? null : plugin.manifest().importsOrEmpty().get(0);
        if (spec == null) {
            throw new IllegalArgumentException("插件没有声明 imports");
        }
        if (targetQq <= 0) {
            throw new IllegalArgumentException("请选择要导入的 QQ");
        }
        long max = spec.maxBytes() == null ? 20L * 1024 * 1024 : spec.maxBytes();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        if (bytes.length > max) {
            throw new IllegalArgumentException("文件超过 " + (max / 1024 / 1024) + "MB");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (spec.accept() != null && spec.accept().stream().noneMatch(a -> name.endsWith(a))) {
            throw new IllegalArgumentException("不支持的文件类型");
        }
        ChatTranscriptParser.Transcript transcript = parser.parse(fileName, bytes);
        if (transcript.messages().isEmpty()) {
            throw new IllegalArgumentException("没有解析出聊天消息");
        }
        String text = renderTranscript(transcript.messages());
        int maxChars = spec.maxChars() == null ? 120000 : spec.maxChars();
        boolean truncated = text.length() > maxChars;
        if (truncated) {
            text = text.substring(0, maxChars);
        }
        int chunkChars = spec.chunkChars() == null ? 7000 : spec.chunkChars();
        List<String> chunks = chunks(text, chunkChars);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("没有可提取的内容");
        }
        boolean chunkTruncated = chunks.size() > MAX_CHUNKS;
        if (chunkTruncated) {
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS));
        }
        Long botId = BotContext.get();
        if (botId == null) {
            throw new IllegalStateException("缺少机器人账号上下文");
        }
        long importId = insertImport(plugin.pluginId(), targetQq, transcript,
                fileName, chunks, truncated || chunkTruncated);
        submit(botId, importId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", importId);
        out.put("status", "queued");
        out.put("progress", 0);
        out.put("totalChunks", chunks.size());
        out.put("messageCount", transcript.messages().size());
        return out;
    }

    /** 后台 worker：按 chunk 断点续跑，每完成一块就落盘。 */
    public void process(long botId, long importId) {
        BotContext.run(botId, () -> processInContext(importId));
    }

    private void processInContext(long importId) {
        Map<String, Object> row = importRow(importId);
        if (row == null) {
            return;
        }
        String status = String.valueOf(row.get("status"));
        if ("review".equals(status) || "applied".equals(status)) {
            return;
        }
        String pluginId = String.valueOf(row.get("pluginId"));
        InstalledPlugin plugin = registry.find(pluginId);
        if (plugin == null || plugin.manifest() == null) {
            failed(importId, "插件已卸载或不完整");
            return;
        }
        PluginManifest.ImportSpec spec = plugin.manifest().importsOrEmpty().isEmpty()
                ? null : plugin.manifest().importsOrEmpty().get(0);
        if (spec == null) {
            failed(importId, "插件没有声明 imports");
            return;
        }
        long targetQq = ((Number) row.get("targetQq")).longValue();
        List<ChunkRow> chunks = chunks(importId);
        if (chunks.isEmpty()) {
            failed(importId, "没有可恢复的分块，请重新上传");
            return;
        }
        String prompt;
        try {
            prompt = readPrompt(plugin, spec.prompt());
        } catch (Exception e) {
            failed(importId, e.getMessage());
            return;
        }
        int total = chunks.size();
        int done = 0;
        List<JsonNode> results = new ArrayList<>();
        for (ChunkRow chunk : chunks) {
            if ("done".equals(chunk.status) && chunk.payload != null) {
                JsonNode old = parseJson(chunk.payload);
                if (old != null) {
                    results.add(old);
                    done++;
                    continue;
                }
            }
            updateState(importId, "extracting", total == 0 ? 0 : done * 100 / total,
                    "提取第 " + (done + 1) + "/" + total + " 段", null);
            try {
                List<Map<String, String>> messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", EXTRACT_SYSTEM + "\n\n" + prompt));
                messages.add(Map.of("role", "user", "content",
                        "聊天记录片段：\n" + chunk.text + "\n\n请只输出 JSON。"));
                LlmResult r = llm.chat(messages, 0.2, EXTRACT_TOKENS, targetQq);
                JsonNode node = parseJson(r.content());
                if (node == null) {
                    throw new IllegalStateException("模型没有返回可解析的 JSON");
                }
                saveChunkDone(importId, chunk.index, node);
                results.add(node);
                done++;
                updateState(importId, "extracting", done * 100 / total,
                        "已完成 " + done + "/" + total + " 段", null);
            } catch (Exception e) {
                saveChunkError(importId, chunk.index, e.getMessage());
                failed(importId, "第 " + (chunk.index + 1) + " 段失败: " + e.getMessage());
                return;
            }
        }
        if (results.isEmpty()) {
            failed(importId, "模型没有返回可解析的提取结果");
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        mergeResults(results, merged);
        try {
            saveCandidate(importId, merged);
            updateState(importId, "review", 100, "待确认", null);
            deleteChunks(importId);
        } catch (Exception e) {
            failed(importId, "保存提取结果失败: " + e.getMessage());
        }
    }

    public Map<String, Object> retry(long importId) {
        Long botId = BotContext.get();
        if (botId == null) {
            throw new IllegalStateException("缺少机器人账号上下文");
        }
        Map<String, Object> row = importRow(importId);
        if (row == null) {
            throw new IllegalArgumentException("导入记录不存在");
        }
        String status = String.valueOf(row.get("status"));
        if ("review".equals(status) || "applied".equals(status)) {
            throw new IllegalArgumentException("这个导入已经完成，不需要重试");
        }
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE plugin_import_chunks SET status='pending', error=NULL "
                            + "WHERE import_id=? AND status<>'done'")) {
                ps.setLong(1, importId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("重置分块失败: " + e.getMessage(), e);
        }
        updateState(importId, "queued", 0, "排队中", null);
        submit(botId, importId);
        return Map.of("ok", true, "status", "queued");
    }

    private void submit(long botId, long importId) {
        String key = botId + ":" + importId;
        if (!running.add(key)) {
            return;
        }
        pool.submit(() -> {
            try {
                process(botId, importId);
            } catch (Exception e) {
                log.warn("导入任务异常 bot={} importId={}: {}", botId, importId, e.getMessage());
                BotContext.run(botId, () -> failed(importId, e.getMessage()));
            } finally {
                running.remove(key);
            }
        });
    }

    private void failed(long importId, String error) {
        updateState(importId, "failed", 0, "失败", error == null ? "未知错误" : error);
    }

    public List<Map<String, Object>> list(String pluginId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, target_qq, title, file_name, message_count, status, error, "
                + "persona_id, enabled, progress, stage, total_chunks, done_chunks, truncated, created_at "
                + "FROM plugin_imports WHERE plugin_id=? ORDER BY id DESC LIMIT 100";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong(1));
                    row.put("targetQq", rs.getLong(2));
                    row.put("title", rs.getString(3));
                    row.put("fileName", rs.getString(4));
                    row.put("messageCount", rs.getInt(5));
                    row.put("status", rs.getString(6));
                    row.put("error", rs.getString(7));
                    long personaId = rs.getLong(8);
                    row.put("personaId", personaId);
                    if (personaId > 0) {
                        Persona p = personas.byId(personaId);
                        row.put("personaName", p == null ? "" : p.name());
                    }
                    row.put("enabled", rs.getInt(9) == 1);
                    row.put("progress", rs.getInt(10));
                    row.put("stage", rs.getString(11));
                    row.put("totalChunks", rs.getInt(12));
                    row.put("doneChunks", rs.getInt(13));
                    row.put("truncated", rs.getInt(14) == 1);
                    row.put("createdAt", rs.getString(15));
                    out.add(row);
                }
            }
        } catch (Exception e) {
            log.debug("读取导入记录失败: {}", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> get(long importId) {
        Map<String, Object> row = importRow(importId);
        if (row == null) {
            return null;
        }
        JsonNode payload = candidate(importId);
        row.put("preview", payload == null ? Map.of() : mapper.convertValue(payload, Map.class));
        return row;
    }

    public Map<String, Object> apply(long importId, boolean applyFacts, boolean applyPersona,
                                     List<Integer> factIndexes, boolean enablePersona) {
        Map<String, Object> row = importRow(importId);
        if (row == null) {
            throw new IllegalArgumentException("导入记录不存在");
        }
        JsonNode payload = candidate(importId);
        if (payload == null) {
            throw new IllegalArgumentException("还没有可应用的结果");
        }
        long targetQq = ((Number) row.get("targetQq")).longValue();
        String pluginId = String.valueOf(row.get("pluginId"));
        if (applyPersona) {
            validatePersona(payload);
        }
        int factCount = 0;
        if (applyFacts) {
            factCount += applyFacts(payload, targetQq, factIndexes);
        }
        Long personaId = null;
        if (applyPersona) {
            personaId = applyPersona(payload, targetQq, pluginId, enablePersona);
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plugin_import_apply_log(import_id, persona_id, fact_count, created_at) "
                             + "VALUES(?,?,?,?)")) {
            ps.setLong(1, importId);
            if (personaId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, personaId);
            }
            ps.setInt(3, factCount);
            ps.setString(4, now());
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("写导入审计失败: {}", e.getMessage());
        }
        if (enablePersona && personaId != null) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE plugin_imports SET enabled=0 WHERE plugin_id=? AND target_qq=? AND id<>?")) {
                ps.setString(1, pluginId);
                ps.setLong(2, targetQq);
                ps.setLong(3, importId);
                ps.executeUpdate();
            } catch (Exception ignored) {
                // 多记录切换失败不影响本次应用
            }
        }
        boolean enabledNow = enablePersona && personaId != null;
        updateStatus(importId, "applied", personaId, enabledNow ? 1 : 0);
        try {
            snapshots.rebuild(targetQq);
        } catch (Exception ignored) {
            // 画像重建失败不影响导入
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("factCount", factCount);
        out.put("personaId", personaId);
        out.put("enabled", enabledNow);
        return out;
    }

    public Map<String, Object> setEnabled(long importId, boolean enabled) {
        Map<String, Object> row = importRow(importId);
        if (row == null) {
            throw new IllegalArgumentException("导入记录不存在");
        }
        long targetQq = ((Number) row.get("targetQq")).longValue();
        long personaId = row.get("personaId") == null ? 0 : ((Number) row.get("personaId")).longValue();
        Long botId = BotContext.get();
        if (botId == null) {
            throw new IllegalStateException("缺少机器人账号上下文");
        }
        if (personaId > 0) {
            personas.setBindingEnabled(botId, targetQq, enabled);
        }
        if (enabled) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE plugin_imports SET enabled=0 WHERE plugin_id=? AND target_qq=? AND id<>?")) {
                ps.setString(1, String.valueOf(row.get("pluginId")));
                ps.setLong(2, targetQq);
                ps.setLong(3, importId);
                ps.executeUpdate();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_imports SET enabled=?, updated_at=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, now());
            ps.setLong(3, importId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新导入状态失败: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enabled", enabled);
        return out;
    }

    private record ChunkRow(int index, String text, String status, String payload) {
    }

    private void mergeResults(List<JsonNode> results, Map<String, Object> out) {
        String type = "general";
        Map<String, Object> persona = null;
        double personaConf = 0;
        Map<String, Object> sections = new LinkedHashMap<>();
        List<Map<String, Object>> examples = new ArrayList<>();
        Map<String, Map<String, Object>> factMap = new LinkedHashMap<>();
        Map<String, Map<String, Object>> styleMap = new LinkedHashMap<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (JsonNode result : results) {
            if ("roleplay".equals(result.path("conversationType").asText())) {
                type = "roleplay";
            } else if ("mixed".equals(result.path("conversationType").asText())) {
                type = "mixed";
            }
            JsonNode p = result.get("persona");
            if (p != null && p.path("detected").asBoolean(false)) {
                double c = p.path("confidence").asDouble(0.5);
                if (c >= personaConf) {
                    personaConf = c;
                    persona = mapper.convertValue(p, Map.class);
                }
                JsonNode sec = p.get("sections");
                if (sec != null && sec.isObject()) {
                    sec.fields().forEachRemaining(e -> {
                        String v = e.getValue().asText("").trim();
                        if (!v.isBlank()) {
                            sections.putIfAbsent(e.getKey(), v);
                        }
                    });
                }
                JsonNode ex = p.get("examples");
                if (ex != null && ex.isArray()) {
                    for (JsonNode one : ex) {
                        String u = one.path("user").asText("").trim();
                        String a = one.path("assistant").asText("").trim();
                        if (!u.isBlank() && !a.isBlank() && examples.size() < 8) {
                            examples.add(Map.of("user", u, "assistant", a));
                        }
                    }
                }
            }
            JsonNode fs = result.get("facts");
            if (fs != null && fs.isArray()) {
                for (JsonNode f : fs) {
                    String category = f.path("category").asText("profile");
                    String key = f.path("key").asText("").trim();
                    String value = f.path("value").asText("").trim();
                    if (key.isBlank() || value.isBlank()) {
                        continue;
                    }
                    String id = category + "|" + key;
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("category", category);
                    one.put("key", key);
                    one.put("value", value);
                    one.put("confidence", f.path("confidence").asDouble(0.7));
                    one.put("evidence", f.path("evidence").asText(""));
                    Map<String, Object> old = factMap.get(id);
                    if (old == null || ((Number) one.get("confidence")).doubleValue()
                            > ((Number) old.get("confidence")).doubleValue()) {
                        factMap.put(id, one);
                    }
                }
            }
            JsonNode ss = result.get("styles");
            if (ss != null && ss.isArray()) {
                for (JsonNode s : ss) {
                    String key = s.path("key").asText("").trim();
                    String value = s.path("value").asText("").trim();
                    if (key.isBlank() || value.isBlank()) {
                        continue;
                    }
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("key", key);
                    one.put("value", value);
                    one.put("confidence", s.path("confidence").asDouble(0.7));
                    styleMap.putIfAbsent(key, one);
                }
            }
            JsonNode w = result.get("warnings");
            if (w != null && w.isArray()) {
                w.forEach(x -> warnings.add(x.asText("")));
            }
        }
        if (persona != null) {
            persona.put("sections", sections);
            persona.put("examples", examples);
            persona.put("confidence", personaConf);
            out.put("persona", persona);
            type = "roleplay".equals(type) || "mixed".equals(type) ? type : "roleplay";
        } else {
            out.put("persona", Map.of("detected", false, "confidence", 0));
        }
        out.put("conversationType", type);
        out.put("facts", new ArrayList<>(factMap.values()));
        out.put("styles", new ArrayList<>(styleMap.values()));
        out.put("warnings", new ArrayList<>(warnings));
    }

    private int applyFacts(JsonNode payload, long targetQq, List<Integer> indexes) {
        Set<Integer> selected = indexes == null ? null : new LinkedHashSet<>(indexes);
        if (selected != null && selected.isEmpty()) {
            return 0;
        }
        JsonNode fs = payload.get("facts");
        int count = 0;
        if (fs != null && fs.isArray()) {
            for (int i = 0; i < fs.size(); i++) {
                if (selected != null && !selected.contains(i)) {
                    continue;
                }
                JsonNode f = fs.get(i);
                String category = normalizeCategory(f.path("category").asText("profile"));
                String key = f.path("key").asText("").trim();
                String value = f.path("value").asText("").trim();
                if (key.isBlank() || value.isBlank()) {
                    continue;
                }
                double confidence = Math.max(0.1, Math.min(1.0, f.path("confidence").asDouble(0.7)));
                facts.upsert(targetQq, category, cut(key, 30), cut(value, 200), confidence);
                count++;
                applyImportantDate(targetQq, key, value);
            }
        }
        JsonNode styles = payload.get("styles");
        if (styles != null && styles.isArray()) {
            for (JsonNode s : styles) {
                String key = s.path("key").asText("").trim();
                String value = s.path("value").asText("").trim();
                if (!key.isBlank() && !value.isBlank()) {
                    facts.upsert(targetQq, "preference", cut("沟通偏好·" + key, 30),
                            cut(value, 200), Math.max(0.1, s.path("confidence").asDouble(0.7)));
                    count++;
                }
            }
        }
        return count;
    }

    private void validatePersona(JsonNode payload) {
        JsonNode p = payload == null ? null : payload.get("persona");
        if (p == null || !p.path("detected").asBoolean(false)) {
            return;
        }
        String content = personaContent(p);
        String lower = content.toLowerCase(Locale.ROOT);
        for (String bad : BAD_PERSONA) {
            if (lower.contains(bad)) {
                throw new IllegalArgumentException("人设包含疑似越权指令，已拒绝应用: " + bad);
            }
        }
    }

    private Long applyPersona(JsonNode payload, long targetQq, String pluginId, boolean enable) {
        JsonNode p = payload.get("persona");
        if (p == null || !p.path("detected").asBoolean(false)) {
            return null;
        }
        String name = p.path("name").asText("").trim();
        if (name.isBlank()) {
            name = "导入人设";
        }
        String content = personaContent(p);
        if (content.isBlank()) {
            return null;
        }
        validatePersona(payload);
        long personaId = personas.create(cut(name, 30) + " · " + targetQq, content);
        if (enable) {
            Long botId = BotContext.get();
            if (botId != null) {
                personas.bind(botId, targetQq, personaId, pluginId);
            }
        }
        return personaId;
    }

    private String personaContent(JsonNode p) {
        StringBuilder sb = new StringBuilder();
        JsonNode sections = p.get("sections");
        if (sections != null && sections.isObject()) {
            sections.fields().forEachRemaining(e -> {
                String v = e.getValue().asText("").trim();
                if (!v.isBlank()) {
                    sb.append("【").append(e.getKey()).append("】\n")
                            .append(cut(v, 1200)).append("\n\n");
                }
            });
        }
        JsonNode examples = p.get("examples");
        if (examples != null && examples.isArray() && !examples.isEmpty()) {
            sb.append("【示例对话】\n");
            for (JsonNode one : examples) {
                sb.append("对方：").append(cut(one.path("user").asText(""), 120)).append('\n');
                sb.append("你：").append(cut(one.path("assistant").asText(""), 160)).append("\n\n");
            }
        }
        return cut(sb.toString().trim(), 4000);
    }

    private void applyImportantDate(long qqId, String key, String value) {
        String md = normalizeDate(value);
        if (md == null) {
            return;
        }
        String lower = (key + " " + value).toLowerCase(Locale.ROOT);
        if (!(lower.contains("生日") || lower.contains("纪念") || lower.contains("重要日"))) {
            return;
        }
        try {
            Map<String, Object> current = profiles.get(qqId);
            List<Map<String, String>> dates = new ArrayList<>();
            Object raw = current.get("importantDates");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, String> one = new LinkedHashMap<>();
                        one.put("md", String.valueOf(m.get("md")));
                        one.put("label", String.valueOf(m.get("label")));
                        dates.add(one);
                    }
                }
            }
            boolean exists = dates.stream().anyMatch(d -> md.equals(d.get("md")));
            if (!exists && dates.size() < 12) {
                dates.add(Map.of("md", md, "label", cut(key, 10)));
                profiles.save(qqId,
                        String.valueOf(current.getOrDefault("address", "")),
                        String.valueOf(current.getOrDefault("stage", "")),
                        stringList(current.get("traits")),
                        stringList(current.get("taboos")),
                        dates);
            }
        } catch (Exception e) {
            log.debug("写入重要日期失败: {}", e.getMessage());
        }
    }

    private static String normalizeDate(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:(\\d{4})[-/年])?(\\d{1,2})[-/月](\\d{1,2})日?")
                .matcher(v);
        if (!m.find()) {
            return null;
        }
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return String.format("%02d-%02d", month, day);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String normalizeCategory(String category) {
        return switch (category == null ? "" : category) {
            case "profile", "preference", "relationship", "event", "commitment" -> category;
            default -> "profile";
        };
    }

    private static List<String> chunks(String text, int size) {
        List<String> out = new ArrayList<>();
        int step = Math.max(1, size - 300);
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
            if (i + size >= text.length()) {
                break;
            }
        }
        return out;
    }

    private static String renderTranscript(List<ChatTranscriptParser.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatTranscriptParser.Message m : messages) {
            sb.append("assistant".equals(m.role()) ? "你：" : "对方：")
                    .append(m.content()).append("\n\n");
        }
        return sb.toString();
    }

    private static String readPrompt(InstalledPlugin plugin, String path) throws Exception {
        Path base = plugin.dir().toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("导入 prompt 不存在: " + path);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static JsonNode parseJson(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    // ---------------- storage ----------------

    private long insertImport(String pluginId, long targetQq,
                              ChatTranscriptParser.Transcript transcript, String fileName,
                              List<String> chunks, boolean truncated) {
        String sql = "INSERT INTO plugin_imports(plugin_id, target_qq, source, title, file_name, "
                + "message_count, status, progress, stage, total_chunks, done_chunks, truncated, "
                + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long importId;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, pluginId);
            ps.setLong(2, targetQq);
            ps.setString(3, "upload");
            ps.setString(4, cut(transcript.title(), 80));
            ps.setString(5, cut(fileName, 120));
            ps.setInt(6, transcript.messages().size());
            ps.setString(7, "queued");
            ps.setInt(8, 0);
            ps.setString(9, "排队中");
            ps.setInt(10, chunks.size());
            ps.setInt(11, 0);
            ps.setInt(12, truncated ? 1 : 0);
            ps.setString(13, now());
            ps.setString(14, now());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                importId = rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("创建导入记录失败: " + e.getMessage(), e);
        }
        if (importId <= 0) {
            throw new IllegalStateException("创建导入记录失败");
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plugin_import_chunks(import_id, chunk_index, text, status, updated_at) "
                             + "VALUES(?,?,?,?,?)")) {
            for (int i = 0; i < chunks.size(); i++) {
                ps.setLong(1, importId);
                ps.setInt(2, i);
                ps.setString(3, chunks.get(i));
                ps.setString(4, "pending");
                ps.setString(5, now());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("保存导入分块失败: " + e.getMessage(), e);
        }
        return importId;
    }

    private void updateState(long id, String status, int progress, String stage, String error) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_imports SET status=?, progress=?, stage=?, error=?, updated_at=? "
                             + "WHERE id=?")) {
            ps.setString(1, status);
            ps.setInt(2, Math.max(0, Math.min(100, progress)));
            ps.setString(3, cut(stage, 100));
            ps.setString(4, error == null ? null : cut(error, 500));
            ps.setString(5, now());
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("更新导入状态失败: {}", e.getMessage());
        }
    }

    private List<ChunkRow> chunks(long importId) {
        List<ChunkRow> out = new ArrayList<>();
        String sql = "SELECT chunk_index, text, status, payload FROM plugin_import_chunks "
                + "WHERE import_id=? ORDER BY chunk_index";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChunkRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (Exception e) {
            log.debug("读取导入分块失败: {}", e.getMessage());
        }
        return out;
    }

    private void saveChunkDone(long importId, int index, JsonNode payload) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_import_chunks SET status='done', payload=?, error=NULL, updated_at=? "
                             + "WHERE import_id=? AND chunk_index=?")) {
            ps.setString(1, mapper.writeValueAsString(payload));
            ps.setString(2, now());
            ps.setLong(3, importId);
            ps.setInt(4, index);
            ps.executeUpdate();
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE plugin_imports SET done_chunks=done_chunks+1 WHERE id=?")) {
                up.setLong(1, importId);
                up.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存分块结果失败: " + e.getMessage(), e);
        }
    }

    private void saveChunkError(long importId, int index, String error) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_import_chunks SET status='error', error=?, updated_at=? "
                             + "WHERE import_id=? AND chunk_index=?")) {
            ps.setString(1, cut(error, 300));
            ps.setString(2, now());
            ps.setLong(3, importId);
            ps.setInt(4, index);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("保存分块错误失败: {}", e.getMessage());
        }
    }

    private void deleteChunks(long importId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM plugin_import_chunks WHERE import_id=?")) {
            ps.setLong(1, importId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("清理导入分块失败: {}", e.getMessage());
        }
    }

    private void updateStatus(long id, String status, Long personaId, int enabled) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_imports SET status=?, persona_id=COALESCE(?, persona_id), "
                             + "enabled=?, updated_at=? WHERE id=?")) {
            ps.setString(1, status);
            if (personaId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, personaId);
            }
            ps.setInt(3, enabled);
            ps.setString(4, now());
            ps.setLong(5, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("更新导入结果失败: {}", e.getMessage());
        }
    }

    private void saveCandidate(long importId, Map<String, Object> preview) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO plugin_import_candidates(import_id, kind, payload, confidence, "
                             + "status, created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, importId);
            ps.setString(2, "result");
            ps.setString(3, mapper.writeValueAsString(preview));
            Object p = preview.get("persona");
            double confidence = p instanceof Map<?, ?> map && map.get("confidence") instanceof Number n
                    ? n.doubleValue() : 0;
            ps.setDouble(4, confidence);
            ps.setString(5, "pending");
            ps.setString(6, now());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("保存导入候选失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> importRow(long id) {
        String sql = "SELECT id, plugin_id, target_qq, title, file_name, message_count, status, "
                + "error, persona_id, enabled, progress, stage, total_chunks, done_chunks, truncated, "
                + "created_at FROM plugin_imports WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong(1));
                row.put("pluginId", rs.getString(2));
                row.put("targetQq", rs.getLong(3));
                row.put("title", rs.getString(4));
                row.put("fileName", rs.getString(5));
                row.put("messageCount", rs.getInt(6));
                row.put("status", rs.getString(7));
                row.put("error", rs.getString(8));
                row.put("personaId", rs.getLong(9));
                row.put("enabled", rs.getInt(10) == 1);
                row.put("progress", rs.getInt(11));
                row.put("stage", rs.getString(12));
                row.put("totalChunks", rs.getInt(13));
                row.put("doneChunks", rs.getInt(14));
                row.put("truncated", rs.getInt(15) == 1);
                row.put("createdAt", rs.getString(16));
                return row;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode candidate(long importId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload FROM plugin_import_candidates WHERE import_id=? "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, importId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.readTree(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.debug("读取导入候选失败: {}", e.getMessage());
        }
        return null;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
