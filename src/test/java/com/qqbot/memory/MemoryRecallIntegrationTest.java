package com.qqbot.memory;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用临时 SQLite 跑一遍真实存取，验证 P1 的验收点：
 * 注入的是检索结果、三块分组正确、top-k 与字数上限、没命中时只带最近事件。
 */
class MemoryRecallIntegrationTest {

    private static final long QQ = 10001L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @TempDir
    Path tmp;

    private Db db;
    private MemoryStore store;
    private MemoryRecall recall;

    @BeforeEach
    void setUp() {
        BotContext.set(1);
        BotProperties props = new BotProperties();
        props.getDb().setPath(tmp.resolve("qqbot.db").toString());
        GlobalDb global = new GlobalDb(props);
        global.init();
        db = new Db(props, global);
        db.init();
        store = new MemoryStore(db);
        recall = new MemoryRecall(store);
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    @Test
    void injectsRetrievedMemoriesGroupedByType() {
        store.upsert(QQ, new MemoryStore.Draft("profile", "user", "工作", "在字节做后端", "chat",
                0.7, 0.9, 0, null, null, null));
        store.upsert(QQ, new MemoryStore.Draft("relation", "user", "称呼", "她叫你老板", "chat",
                0.8, 0.9, 0, null, null, null));
        store.upsert(QQ, new MemoryStore.Draft("event", "user", "加班", "加班到 22:00", "chat",
                0.9, 0.9, -0.5, LocalDateTime.now().plusDays(5).toString(), null, null));

        String block = recall.promptBlock(QQ, "昨天又加班到十点，累死了");
        assertTrue(block.contains("【最近发生】"), block);
        assertTrue(block.contains("加班到 22:00"), block);
        // 命中时三块都应有内容（8 条补齐会把 profile/relation 拉进来）
        assertTrue(block.contains("【关于对方】"), block);
        assertTrue(block.contains("【你们之间】"), block);
        // 提示词里明确要求别复述、别背书
        assertTrue(block.contains("不要逐条复述"), block);
    }

    @Test
    void noHitOnlyBringsRecentEvents() {
        store.upsert(QQ, new MemoryStore.Draft("profile", "user", "城市", "杭州", "chat",
                0.8, 0.9, 0, null, null, null));
        store.upsert(QQ, new MemoryStore.Draft("event", "user", "面试", "昨天面试了", "chat",
                0.8, 0.9, 0, null, null, null));

        String block = recall.promptBlock(QQ, "嗯");
        assertTrue(block.contains("【最近发生】"), block);
        assertTrue(block.contains("面试"), block);
        assertFalse(block.contains("【关于对方】"), block);
    }

    @Test
    void capsItemsAndChars() {
        for (int i = 0; i < 30; i++) {
            String value = "第" + i + "条加班记录，" + "很长的内容".repeat(6);
            store.upsert(QQ, new MemoryStore.Draft("event", "user", "加班" + i, value, "chat",
                    0.9, 0.9, 0, null, null, null));
        }
        List<MemoryRecall.Scored> picked = recall.recall(QQ, "加班好累", 12);
        assertTrue(picked.size() <= 12, "最多 12 条");
        int chars = 0;
        for (MemoryRecall.Scored s : picked) {
            chars += s.memory().value().length();
        }
        assertTrue(chars <= 1400, "总长应受 1200 字限制，实际 " + chars);
    }

    @Test
    void lowConfidenceNotInjected() {
        store.upsert(QQ, new MemoryStore.Draft("profile", "user", "职业", "学生", "chat",
                0.9, 0.4, 0, null, null, null));
        String block = recall.promptBlock(QQ, "你在做什么工作");
        assertFalse(block.contains("学生"), block);
    }

    @Test
    void sameKeyKeepsSingleActiveAndArchivesOld() {
        store.upsert(QQ, new MemoryStore.Draft("profile", "user", "城市", "杭州", "chat",
                0.7, 0.9, 0, null, null, null));
        long newId = store.upsert(QQ, new MemoryStore.Draft("profile", "user", "城市", "上海", "chat",
                0.7, 0.9, 0, null, null, null));

        List<Memory> active = store.active(QQ);
        long activeSameKey = active.stream()
                .filter(m -> "城市".equals(m.key()))
                .count();
        assertEquals(1, activeSameKey);
        assertEquals("上海", active.stream().filter(m -> m.id() == newId).findFirst().orElseThrow().value());
        Memory old = store.page(QQ, null, "archived", "杭州", 0, 10).get(0);
        assertEquals(newId, old.supersededBy());
    }

    @Test
    void migrationFromFactsIsIdempotent() throws Exception {
        try (var c = db.open();
             var ps = c.prepareStatement("INSERT INTO facts(qq_id, category, key, value, confidence, "
                     + "status, first_seen, last_confirmed) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, QQ);
            ps.setString(2, "profile");
            ps.setString(3, "城市");
            ps.setString(4, "杭州");
            ps.setDouble(5, 0.9);
            ps.setString(6, "active");
            ps.setString(7, LocalDateTime.now().format(TS));
            ps.setString(8, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        }
        assertEquals(1, store.migrateFromFacts(QQ));
        assertEquals(0, store.migrateFromFacts(QQ), "第二次迁移应跳过");
        assertTrue(Files.exists(tmp.resolve("bots/1.db")));
    }
}
