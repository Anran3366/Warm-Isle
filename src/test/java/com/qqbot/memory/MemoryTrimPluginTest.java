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
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * trim 淘汰优先级回归测试（M0 / P0-1）。
 *
 * <p>插件写记忆时 source 是 {@code plugin:<id>}（如 {@code plugin:menran.sing}）。
 * 若 trim 用 {@code source='plugin'} 精确等值判断，这一行永远落到 ELSE 分支，
 * 插件流水就不再优先淘汰 —— 被挤掉的会是用户最老、最珍贵的记忆。
 * 本测试用真实 SQLite 压到阈值之上，断言被归档的全部是插件记录。
 */
class MemoryTrimPluginTest {

    private static final long QQ = 10002L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /** 直接插一条，绕过 upsert 的语义逻辑，模拟历史存量 */
    private void insert(String source, String key, double strength, int daysAgo) throws Exception {
        String at = LocalDateTime.now().minusDays(daysAgo).format(TS);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO memories(qq_id, type, subject, key, value, source, importance, "
                             + "confidence, strength, strength_at, first_seen, last_confirmed, status) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'active')")) {
            ps.setLong(1, QQ);
            ps.setString(2, "relation");
            ps.setString(3, "user");
            ps.setString(4, key);
            ps.setString(5, "值-" + key);
            ps.setString(6, source);
            ps.setDouble(7, 0.5);
            ps.setDouble(8, 0.9);
            ps.setDouble(9, strength);
            ps.setString(10, at);
            ps.setString(11, at);
            ps.setString(12, at);
            ps.executeUpdate();
        }
    }

    private int count(String where) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM memories WHERE qq_id=? AND " + where)) {
            ps.setLong(1, QQ);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    @Test
    void trimArchivesPluginRecordsFirst_evenWithScopedSource() throws Exception {
        // 499 条用户真实记忆（最老的 900 天前，strength=1.0）
        for (int i = 0; i < 499; i++) {
            insert("chat", "user-" + i, 1.0, 900 - i);
        }
        // 3 条插件流水，刚写入、strength 满值 —— 旧实现下它们永远不会被淘汰
        insert("plugin:menran.sing", "sing:七里香", 1.0, 0);
        insert("plugin:menran.sing", "sing:晴天", 1.0, 0);
        insert("plugin:menran.sing", "sing:稻香", 1.0, 0);

        assertEquals(502, count("status='active'"));

        // 再写一条用户记忆，触发 trim：503 - 500 = 3 条溢出
        store.upsert(QQ, new MemoryStore.Draft("event", "user", "新的事", "今天一起看了电影",
                "chat", 0.6, 0.9, 0, null, null, null));

        assertEquals(500, count("status='active'"));
        assertEquals(3, count("status='archived'"));
        // 核心断言：被归档的必须全是插件流水
        assertEquals(3, count("status='archived' AND source LIKE 'plugin%'"));
        assertEquals(0, count("status='archived' AND source NOT LIKE 'plugin%'"));
        // 插件记忆已一条不剩（按新写入顺序淘汰，先没的是最早那条）
        assertEquals(0, count("status='active' AND source LIKE 'plugin%'"));
    }

    @Test
    void trimNeverArchivesPendingCommitment() throws Exception {
        for (int i = 0; i < 500; i++) {
            insert("chat", "user-" + i, 1.0, 800 - i);
        }
        // 一条未兑现的承诺：strength 最低、最老，但 follow_up_state=pending，不能淘汰
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO memories(qq_id, type, subject, key, value, source, importance, confidence, "
                             + "strength, strength_at, first_seen, last_confirmed, follow_up_state, status) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'pending','active')")) {
            ps.setLong(1, QQ);
            ps.setString(2, "commitment");
            ps.setString(3, "user");
            ps.setString(4, "promise:交房租");
            ps.setString(5, "下个月提醒我交房租");
            ps.setString(6, "chat");
            ps.setDouble(7, 0.95);
            ps.setDouble(8, 1.0);
            ps.setDouble(9, 0.01);
            String at = LocalDateTime.now().minusDays(400).format(TS);
            ps.setString(10, at);
            ps.setString(11, at);
            ps.setString(12, at);
            ps.executeUpdate();
        }

        store.upsert(QQ, new MemoryStore.Draft("event", "user", "另一件事", "周末去爬山",
                "chat", 0.6, 0.9, 0, null, null, null));

        assertEquals(1, count("follow_up_state='pending' AND status='active'"));
    }
}
