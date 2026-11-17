package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.BotAccountRunner;
import com.qqbot.bot.BotContext;
import com.qqbot.chat.MoodService;
import com.qqbot.config.BotProperties;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Db;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.GlobalDb;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MessageEventBus;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SettingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆自进化：成长档案、表达方式、巩固、反思写库与过滤。 */
class MemoryEvolveTest {

    private static final long QQ = 10001L;

    @TempDir
    Path tmp;

    private Db db;
    private MemoryStore memories;
    private FactStore facts;
    private MessageStore messages;
    private SettingStore settings;
    private GrowthService growth;
    private ExpressionService expressions;
    private ProfileSnapshotService profile;
    private MemoryConsolidator consolidator;
    private MemoryAuditService audit;
    private PersonProfileService personProfile;
    private MemoryTimelineService timeline;

    @BeforeEach
    void setUp() {
        BotContext.set(1);
        BotProperties props = new BotProperties();
        props.getDb().setPath(tmp.resolve("qqbot.db").toString());
        GlobalDb global = new GlobalDb(props);
        global.init();
        db = new Db(props, global);
        db.init();
        memories = new MemoryStore(db);
        facts = new FactStore(db);
        messages = new MessageStore(db, new MessageEventBus(new ObjectMapper()));
        settings = new SettingStore(db);
        growth = new GrowthService(db, settings);
        expressions = new ExpressionService(db, settings);
        MoodService moods = new MoodService(db);
        profile = new ProfileSnapshotService(db, memories, settings, moods);
        consolidator = new MemoryConsolidator(memories, facts, profile);
        audit = new MemoryAuditService(memories, growth, expressions, db);
        personProfile = new PersonProfileService(db, memories, messages);
        timeline = new MemoryTimelineService(db);
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    @Test
    void growthNeedsApprovalBeforePrompt() {
        growth.addPending(QQ, "他加班时先别问吃什么，先让他歇会儿", "[1]");
        growth.addPending(QQ, "他加班时先别问吃什么，让他先歇会儿", "[1]");
        assertEquals(1, growth.list(QQ, "pending", 10).size(), "近似重复的成长条不该重复入库");
        assertEquals("", growth.approvedBlock(QQ), "未采纳的成长条不能进 prompt");

        long id = ((Number) growth.list(QQ, "pending", 10).get(0).get("id")).longValue();
        growth.review(id, true);
        String block = growth.approvedBlock(QQ);
        assertTrue(block.contains("歇会儿"), block);
    }

    @Test
    void expressionMatchingAndApproval() {
        expressions.addPending(QQ, "对方说好累", "那就先瘫一会儿，别硬撑");
        assertTrue(expressions.suggest(QQ, "好累啊", 2).isEmpty(), "pending 不该被用");
        long id = ((Number) expressions.list(QQ, "pending", 10).get(0).get("id")).longValue();
        expressions.review(id, true);
        List<Map<String, Object>> hit = expressions.suggest(QQ, "今天好累", 2);
        assertFalse(hit.isEmpty());
        assertTrue(expressions.block(QQ, "今天好累").contains("瘫一会儿"));
    }

    @Test
    void consolidatorKeepsNewestNameAndFixesFacts() {
        facts.upsert(QQ, "profile", "昵称", "超威", 0.9);
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "称呼", "超威", "chat",
                0.8, 0.9, 0, null, null, null));
        memories.upsert(QQ, new MemoryStore.Draft("relation", "user", "称呼", "安冉", "chat",
                0.9, 0.9, 0, null, null, null));

        Map<String, Object> result = consolidator.run(QQ);
        long activeNames = memories.active(QQ).stream()
                .filter(m -> m.key() != null && m.key().contains("称呼")).count();
        assertEquals(1, activeNames, "称呼只该留一条: " + memories.active(QQ));
        assertTrue(facts.active(QQ).stream().noneMatch(f -> "超威".equals(f.value())));
        assertTrue(((Number) result.get("factsOutdated")).intValue() >= 1);
    }

    @Test
    void profileAutoFillRespectsDeniedName() throws Exception {
        var node = new ObjectMapper().readTree("""
                {"address":"超威","stage":"熟悉","traits":["凌晨还不睡"],"taboos":["别说教"],
                 "important_dates":[{"md":"08-12","label":"生日"}]}
                """);
        personProfile.applyReflection(QQ, node, java.util.Set.of(1L), java.util.Set.of("超威"));
        personProfile.backfill(QQ);
        Map<String, Object> p = personProfile.get(QQ);
        assertEquals("", p.get("address"), "被否认的称呼不能写进档案");
        assertEquals("熟悉", p.get("stage"));
        assertTrue(((java.util.List<?>) p.get("traits")).contains("凌晨还不睡"));
        assertTrue(((java.util.List<?>) p.get("taboos")).contains("别说教"));
        assertTrue(((java.util.List<?>) p.get("importantDates")).stream()
                .anyMatch(d -> "08-12".equals(((Map<?, ?>) d).get("md"))));
    }

    @Test
    void timelineAggregatesInsightGrowthAndFollowUp() {
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "洞察·加班后", "不想被追问",
                "reflection:insight", 0.7, 0.8, 0, null, null, null));
        growth.addPending(QQ, "他累的时候先别讲道理", "[1]");
        var items = timeline.timeline(QQ, 50);
        assertTrue(items.stream().anyMatch(i -> "insight".equals(i.get("kind"))));
        assertTrue(items.stream().anyMatch(i -> "growth".equals(i.get("kind"))));
    }

    @Test
    void consolidatorDoesNotKillCorrectFactBecauseOfArchivedMemory() {
        facts.upsert(QQ, "profile", "昵称", "安冉", 0.9);
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "称呼", "超威", "chat",
                0.5, 0.9, 0, null, null, null));
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "称呼", "安冉", "chat",
                0.9, 0.9, 0, null, null, null));

        consolidator.run(QQ);
        assertTrue(facts.active(QQ).stream().anyMatch(f -> "安冉".equals(f.value())),
                "被归档的旧称呼不能反过来把正确 facts 停用");
    }

    @Test
    void auditFindsConflictAndPending() {
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "称呼", "A", "chat",
                0.8, 0.9, 0, null, null, null));
        memories.upsert(QQ, new MemoryStore.Draft("relation", "user", "称呼", "B", "chat",
                0.8, 0.9, 0, null, null, null));
        growth.addPending(QQ, "一条待审", "[]");
        Map<String, Object> report = audit.audit(QQ);
        assertFalse(((List<?>) report.get("conflicts")).isEmpty());
        assertEquals(1, ((Number) report.get("pendingGrowth")).intValue());
    }

    @Test
    void reflectorWritesEvidenceBackedInsightAndFiltersDeniedName() {
        long evidence = memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "工作", "经常加班到很晚",
                "chat", 0.8, 0.9, 0, null, null, null));
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "称呼", "不要叫「超威」",
                "chat", 0.9, 0.9, 0, null, null, null));
        messages.add(QQ, "user", "今天又加班到十点", "text");
        messages.add(QQ, "assistant", "那先别想工作了，歇会儿", "text");
        messages.add(QQ, "user", "嗯，累死了", "text");
        messages.add(QQ, "assistant", "先喝口水", "text");

        String json = """
                {"insights":[
                  {"key":"加班后","value":"不想被追问，先让他安静一会儿","confidence":0.8,"evidence":[%d]},
                  {"key":"称呼","value":"超威","confidence":0.9,"evidence":[%d]}],
                 "lessons":[{"situation":"说好累","value":"先接一句再给建议","confidence":0.7,"evidence":[%d]}],
                 "growth":[{"content":"他累的时候先别讲道理","evidence":[%d]}],
                 "conflicts":[]}
                """.formatted(evidence, evidence, evidence, evidence);
        DeepSeekClient llm = new DeepSeekClient(new BotProperties(), new ObjectMapper(), null) {
            @Override
            public LlmResult chat(java.util.List<Map<String, String>> msgs, double temperature,
                                  int maxTokens, long qqId) {
                return new LlmResult(json, 100, 100);
            }
        };

        MemoryReflector reflector = new MemoryReflector(new BotAccountRunner(new GlobalDb(new BotProperties())),
                messages, memories, settings, llm, growth, profile, new PersonProfileService(db, memories, messages), consolidator, db);
        Map<String, Object> result = reflector.reflect(QQ, true);

        assertEquals(1, ((Number) result.get("insights")).intValue(), "被否认的称呼应被过滤: " + result);
        assertTrue(memories.active(QQ).stream().anyMatch(m -> m.key() != null && m.key().startsWith("洞察·")));
        assertTrue(memories.active(QQ).stream().noneMatch(m -> m.value() != null && m.value().equals("超威")));
        assertEquals(1, growth.list(QQ, "pending", 10).size());

        int evidenceRows = 0;
        try (var c = db.open(); var ps = c.prepareStatement("SELECT count(*) FROM memory_evidence")) {
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    evidenceRows = rs.getInt(1);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(evidenceRows > 0, "证据必须落库");

        Map<String, Object> second = reflector.reflect(QQ, true);
        assertEquals(0, ((Number) second.get("insights")).intValue(), "同一话题重复反思不该再造一条: " + second);
        assertNotNull(result);
    }
}
