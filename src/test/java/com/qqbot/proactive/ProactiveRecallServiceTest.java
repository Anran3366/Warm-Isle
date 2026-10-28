package com.qqbot.proactive;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.Db;
import com.qqbot.memory.GlobalDb;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主动回忆的门槛与状态流转（设计稿第 4 节）。
 * 用临时 SQLite + 假文案生成，不依赖模型、不发真实消息。
 */
class ProactiveRecallServiceTest {

    private static final long QQ = 20002L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @TempDir
    Path tmp;

    private Db db;
    private MemoryStore memories;
    private MessageStore messages;
    private ProactiveStore store;
    private ProactiveRecallService service;
    private final StringBuilder prompts = new StringBuilder();

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
        messages = new MessageStore(db, new com.qqbot.memory.MessageEventBus(new com.fasterxml.jackson.databind.ObjectMapper()));
        store = new ProactiveStore(db);
        service = new ProactiveRecallService(memories, messages, store, (qqId, situation, topic) -> {
            prompts.append(situation).append('\n');
            return "昨晚又忙到那么晚，今天早点睡吧";
        });
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    private long seedDue(String type, String key, String value, int minutesAgo) {
        long id = memories.upsert(QQ, new MemoryStore.Draft(type, "user", key, value, "chat", 0.8, 0.9, 0,
                null, LocalDateTime.now().minusMinutes(minutesAgo).format(TS), "问问后来怎么样"));
        memories.markFollowUp(id, "pending");
        return id;
    }

    private ProactiveState state(LocalDateTime lastUser) {
        return new ProactiveState(QQ, lastUser == null ? null : lastUser.format(TS), null, 0, false,
                0, false, ProactiveMode.CALM, null, null, null, null, 0, null, null);
    }

    @Test
    void sendsDueEventOnce() {
        long id = seedDue("event", "加班", "加班到 22:00", 5);
        Optional<ProactiveRecallService.Recall> r = service.next(QQ, state(null), LocalDateTime.now());
        assertTrue(r.isPresent());
        assertEquals(id, r.get().memoryId());
        assertEquals(ProactiveRecallService.TYPE_EVENT, r.get().channelType());
        assertTrue(r.get().text().contains("早点睡"));

        service.markSent(r.get());
        Memory m = memories.byId(id);
        assertEquals("done", m.followUpState());
        assertTrue(service.next(QQ, state(null), LocalDateTime.now()).isEmpty(), "同一事件只提一次");
    }

    @Test
    void respectsTwoHourIdleRule() {
        seedDue("event", "加班", "加班到 22:00", 5);
        Optional<ProactiveRecallService.Recall> r =
                service.next(QQ, state(LocalDateTime.now().minusMinutes(30)), LocalDateTime.now());
        assertTrue(r.isEmpty(), "2 小时内说过话不发");
    }

    @Test
    void skippedWhenUserAlreadyRaisedTopic() {
        long id = seedDue("event", "告白", "纠结要不要跟喜欢的人告白", 5);
        messages.add(QQ, "user", "我决定了，还是想去告白", "text");
        Optional<ProactiveRecallService.Recall> r = service.next(QQ, state(null), LocalDateTime.now());
        assertTrue(r.isEmpty());
        assertEquals("skipped", memories.byId(id).followUpState());
    }

    @Test
    void textWithForbiddenWordsIsRejected() {
        seedDue("event", "加班", "加班到 22:00", 5);
        ProactiveRecallService bad = new ProactiveRecallService(memories, messages, store,
                (qqId, situation, topic) -> "我记得你说过加班到十点，记录一下");
        Optional<ProactiveRecallService.Recall> r = bad.next(QQ, state(null), LocalDateTime.now());
        assertTrue(r.isPresent(), "有兜底句，仍然会发");
        assertFalse(r.get().text().contains("记忆"));
        assertFalse(r.get().text().contains("记录"));
        assertTrue(r.get().text().contains("早点睡"), "用的是兜底文案");
    }

    @Test
    void relationQuotaIsOnePerDay() {
        // 关系类额度 1：同一天第二条关系类不再发
        long first = seedDue("relation", "冷战", "你们有点冷着", 5);
        Optional<ProactiveRecallService.Recall> a = service.next(QQ, state(null), LocalDateTime.now());
        assertTrue(a.isPresent());
        service.markSent(a.get());
        store.recordProactive(QQ, ProactiveRecallService.TYPE_RELATION, "服软", "text", "冷战", 1);

        seedDue("relation", "约定", "说好周末聊", 5);
        Optional<ProactiveRecallService.Recall> b = service.next(QQ, state(null), LocalDateTime.now());
        assertTrue(b.isEmpty(), "关系类每天最多 1 条");
        assertNotNull(memories.byId(first));
    }

    @Test
    void staleFollowUpIsSkipped() {
        long id = seedDue("event", "加班", "加班到 22:00", 60 * 24 * 5);
        Optional<ProactiveRecallService.Recall> r = service.next(QQ, state(null), LocalDateTime.now());
        assertTrue(r.isEmpty(), "超过 3 天的旧事件不补发");
        assertEquals("skipped", memories.byId(id).followUpState());
    }

    @Test
    void sanitizeRemovesMemoryWords() {
        String out = ProactiveRecallService.sanitize("我记得你说过要早点睡");
        assertFalse(out.contains("记忆"));
        assertEquals("之前你说要早点睡", out);
    }
}
