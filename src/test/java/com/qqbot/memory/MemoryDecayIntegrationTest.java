package com.qqbot.memory;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遗忘 / 复习 / 周期日的真实存取验收（临时 SQLite）。
 */
class MemoryDecayIntegrationTest {

    private static final long QQ = 10001L;
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @TempDir
    Path tmp;

    private Db db;
    private MemoryStore store;

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
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    private long add(String type, String key, String value, double importance) {
        return store.upsert(QQ, new MemoryStore.Draft(type, "user", key, value, "chat",
                importance, 0.9, 0, null, null, null));
    }

    /** 把这条记忆的强度基准挪到 N 天前，模拟「已经放了很久」。 */
    private void backdate(long id, int days) throws Exception {
        String at = LocalDateTime.now().minusDays(days)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE memories SET strength_at=?, last_confirmed=? WHERE id=?")) {
            ps.setString(1, at);
            ps.setString(2, at);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    @Test
    void chatFromAStrangerIsArchivedAfterAWeek() throws Exception {
        long id = add("event", "一起吃饭", "和一个刚认识的人吃了顿饭", 0.4);
        backdate(id, 6);

        int archived = store.sweep(QQ, LocalDateTime.now());
        assertEquals(1, archived);
        Memory m = store.byId(id);
        assertEquals("archived", m.status());
        assertTrue(store.active(QQ).isEmpty());
    }

    @Test
    void importantMemorySurvivesTheSameWeek() throws Exception {
        long id = add("relation", "约定", "约好下个月一起去看海", 0.9);
        backdate(id, 6);

        store.sweep(QQ, LocalDateTime.now());
        assertEquals("active", store.byId(id).status());
    }

    @Test
    void restatingTheSameThingRestoresFullStrength() throws Exception {
        long id = add("profile", "宠物", "我喜欢小狗", 0.4);
        backdate(id, 4);
        store.sweep(QQ, LocalDateTime.now());
        double faded = store.byId(id).strength();
        assertTrue(faded < 0.3 && faded > MemoryStrength.FORGET, "四天没提已经模糊但还没忘: " + faded);
        assertEquals("active", store.byId(id).status());

        // 周六又说了一次 → 强度回满
        add("profile", "宠物", "我喜欢小狗", 0.4);
        Memory m = store.byId(id);
        assertEquals(1.0, m.strength(), 1e-6);
        assertTrue(m.recallCount() >= 1);
    }

    @Test
    void birthdayIsPinnedAndCongratulatedOncePerYear() {
        long id = add("profile", "生日", "他的生日是10月26日", 0.6);
        Memory m = store.byId(id);
        assertTrue(m.pinned(), "生日应该自动钉住");
        assertEquals("10-26", m.recurMd());

        LocalDate day = LocalDate.of(2026, 10, 26);
        List<Memory> due = store.anniversaries(QQ, day);
        assertEquals(1, due.size());

        store.markAnniversary(id, day);
        assertTrue(store.anniversaries(QQ, day).isEmpty(), "同一年不该重复祝贺");
        assertEquals(1.0, store.byId(id).strength(), 1e-6, "祝贺也算一次复习");

        // 明年同一天还要再祝贺
        assertEquals(1, store.anniversaries(QQ, LocalDate.of(2027, 10, 26)).size());
    }

    @Test
    void recallBumpsStrengthWithoutRefillingIt() throws Exception {
        long id = add("event", "面试", "周五有个面试", 0.5);
        backdate(id, 3);
        double before = MemoryStrength.effective(store.byId(id), LocalDateTime.now());

        store.onRecalled(List.of(id), LocalDateTime.now());
        double after = store.byId(id).strength();
        assertTrue(after > before && after < 1.0, "被想起只回一点点: " + before + " -> " + after);
    }

    /**
     * 回归：「下个月提醒我交房租」在到期前被遗忘曲线归档，dueFollowUps 永远查不到。
     * sweep 只把强度压在遗忘线上，状态必须保持 active。
     */
    @Test
    void pendingCommitmentIsNotForgottenBeforeItIsDue() throws Exception {
        String dueAt = LocalDateTime.now().plusDays(30).format(TS);
        long id = store.upsert(QQ, new MemoryStore.Draft("relation", "user", "交房租",
                "每月 1 号提醒他交房租", "chat", 0.95, 0.9, 0, null, dueAt, "提醒交房租"));
        assertEquals("pending", store.byId(id).followUpState());
        backdate(id, 60);

        store.sweep(QQ, LocalDateTime.now());

        Memory m = store.byId(id);
        assertEquals("active", m.status(), "没兑现的承诺不该被归档");
        assertTrue(m.strength() >= MemoryStrength.FORGET, "强度压在遗忘线上: " + m.strength());
        assertEquals(1, store.dueFollowUps(QQ, LocalDateTime.now().plusDays(31)).size(),
                "到期时必须还能被主动回忆查到");
    }

    /** 兑现之后回到正常衰减：不会因为「曾经是承诺」就永久占着位置。 */
    @Test
    void commitmentFadesAgainAfterItIsDelivered() throws Exception {
        String dueAt = LocalDateTime.now().minusMinutes(1).format(TS);
        long id = store.upsert(QQ, new MemoryStore.Draft("relation", "user", "交房租",
                "提醒他交房租", "chat", 0.95, 0.9, 0, null, dueAt, null));
        store.sweep(QQ, LocalDateTime.now());
        assertEquals("active", store.byId(id).status());

        store.markFollowUp(id, "done");
        backdate(id, 30);
        store.sweep(QQ, LocalDateTime.now());
        assertEquals("archived", store.byId(id).status(), "说完了就该忘");
    }

    /** 回归：trim 挤掉的是插件写的流水，不是用户自己的记忆。 */
    @Test
    void trimEvictsPluginRecordsBeforeUserMemories() {
        for (int i = 0; i < 470; i++) {
            add("event", "琐事" + i, "第" + i + "件小事", 0.4);
        }
        for (int i = 0; i < 30; i++) {
            store.upsert(QQ, new MemoryStore.Draft("relation", "bot", "唱过:" + i,
                    "《歌" + i + "》", "plugin", 0.85, 1.0, 0.3, null, null, null));
        }
        assertEquals(500, store.countActive(QQ));

        add("event", "新事", "又一件小事", 0.4); // 第 501 条，挤出 1 条

        long pluginActive = store.all(QQ).stream()
                .filter(m -> "active".equals(m.status()) && "plugin".equals(m.source()))
                .count();
        long chatActive = store.all(QQ).stream()
                .filter(m -> "active".equals(m.status()) && "chat".equals(m.source()))
                .count();
        assertEquals(29, pluginActive, "先扔插件流水，用户的记忆一条不动");
        assertEquals(471, chatActive);
        assertEquals(500, store.countActive(QQ), "稳定在上限，不该一下砍到 480");
    }
}
