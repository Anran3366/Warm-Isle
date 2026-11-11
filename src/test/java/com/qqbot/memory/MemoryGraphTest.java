package com.qqbot.memory;

import com.qqbot.bot.BotContext;
import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星图必须是「所有聊过天的人」，而不是只有当前用户 */
class MemoryGraphTest {

    private static final long A = 100000000L;
    private static final long B = 1590909643L;

    @TempDir
    Path tmp;

    private Db db;
    private MemoryStore store;
    private MemoryGraphService graph;

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
        // 星图不接向量也能跑（结构边），这里省掉 embedding 服务
        graph = new MemoryGraphService(store, null, db);
    }

    @AfterEach
    void tearDown() {
        BotContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphAllCoversEveryUser() {
        store.upsert(A, new MemoryStore.Draft("profile", "user", "城市", "杭州", "chat", 0.8, 0.9, 0, null, null, null));
        store.upsert(A, new MemoryStore.Draft("event", "user", "加班", "加班到十点", "chat", 0.7, 0.9, 0, null, null, null));
        store.upsert(B, new MemoryStore.Draft("profile", "user", "宠物", "养了一只猫", "chat", 0.8, 0.9, 0, null, null, null));

        Map<String, Object> g = graph.graphAll(false);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) g.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) g.get("edges");

        assertEquals(3, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> String.valueOf(n.get("qqId")).equals(String.valueOf(A))));
        assertTrue(nodes.stream().anyMatch(n -> String.valueOf(n.get("qqId")).equals(String.valueOf(B))));

        // 边不能跨用户：A 的记忆不能连到 B 的记忆
        for (Map<String, Object> e : edges) {
            String a = String.valueOf(e.get("a"));
            String b = String.valueOf(e.get("b"));
            String ownerA = nodes.stream().filter(n -> n.get("id").equals(a)).findFirst()
                    .map(n -> String.valueOf(n.get("qqId"))).orElse("");
            String ownerB = nodes.stream().filter(n -> n.get("id").equals(b)).findFirst()
                    .map(n -> String.valueOf(n.get("qqId"))).orElse("");
            assertEquals(ownerA, ownerB, "跨用户的边必须被过滤掉");
        }

        Map<String, Object> meta = (Map<String, Object>) g.get("meta");
        assertEquals(2, ((Number) meta.get("users")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleUserGraphOnlyHasThatUser() {
        store.upsert(A, new MemoryStore.Draft("profile", "user", "城市", "杭州", "chat", 0.8, 0.9, 0, null, null, null));
        store.upsert(B, new MemoryStore.Draft("profile", "user", "宠物", "养了一只猫", "chat", 0.8, 0.9, 0, null, null, null));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graph(A, false).get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(String.valueOf(A), String.valueOf(nodes.get(0).get("qqId")));
    }
}
