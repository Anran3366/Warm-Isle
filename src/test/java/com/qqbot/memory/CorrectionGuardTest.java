package com.qqbot.memory;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 「我不叫X」要当场把旧称呼作废，不能等到第二天还叫错。 */
class CorrectionGuardTest {

    private static final long QQ = 10001L;

    @TempDir
    Path tmp;

    private FactStore facts;
    private MemoryStore memories;
    private CorrectionGuard guard;

    @BeforeEach
    void setUp() {
        BotContext.set(1);
        BotProperties props = new BotProperties();
        props.getDb().setPath(tmp.resolve("qqbot.db").toString());
        GlobalDb global = new GlobalDb(props);
        global.init();
        Db db = new Db(props, global);
        db.init();
        facts = new FactStore(db);
        memories = new MemoryStore(db);
        guard = new CorrectionGuard(facts, memories);
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    @Test
    void denialDeactivatesFactAndMemory() {
        facts.upsert(QQ, "profile", "昵称", "超威", 0.9);
        memories.upsert(QQ, new MemoryStore.Draft("profile", "user", "昵称", "超威", "chat",
                0.65, 0.9, 0, null, null, null));

        guard.observe(QQ, "呃？我都说了，我不叫超威");

        List<Fact> activeFacts = facts.active(QQ);
        assertTrue(activeFacts.stream().noneMatch(f -> "超威".equals(f.value())),
                "旧昵称事实应被停用: " + activeFacts);
        List<Memory> activeMemories = memories.active(QQ);
        assertTrue(activeMemories.stream().noneMatch(m -> "超威".equals(m.value())),
                "旧昵称记忆应被归档: " + activeMemories);
        assertTrue(activeMemories.stream().anyMatch(m -> m.value().contains("不要叫")),
                "应留下一条纠正记忆: " + activeMemories);
    }

    @Test
    void unrelatedSentenceDoesNothing() {
        facts.upsert(QQ, "profile", "昵称", "超威", 0.9);

        guard.observe(QQ, "我不叫外卖");

        assertTrue(facts.active(QQ).stream().anyMatch(f -> "超威".equals(f.value())),
                "「我不叫外卖」不应误伤现有昵称");
        assertTrue(memories.active(QQ).isEmpty());
    }

    @Test
    void nicknameNeedsExplicitSelfIntro() {
        assertFalse(CorrectionGuard.explicitlyIntroduced("我超威，昨天喝多了，难受死了", "超威"));
        assertFalse(CorrectionGuard.explicitlyIntroduced("呃？我都说了，我不叫超威", "超威"));
        assertFalse(CorrectionGuard.explicitlyIntroduced("以后别叫我超威了", "超威"));
        assertTrue(CorrectionGuard.explicitlyIntroduced("我叫安冉", "安冉"));
        assertTrue(CorrectionGuard.explicitlyIntroduced("你可以叫我小满", "小满"));
        assertTrue(CorrectionGuard.explicitlyIntroduced("我的名字是阿岚", "阿岚"));
        assertTrue(CorrectionGuard.isNameKey("昵称"));
        assertTrue(CorrectionGuard.isNameKey("称呼"));
        assertFalse(CorrectionGuard.isNameKey("职业"));
    }
}
