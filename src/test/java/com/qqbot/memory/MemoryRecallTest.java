package com.qqbot.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆 v2 检索打分的纯函数测试（不碰 SQLite） */
class MemoryRecallTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Memory mem(String type, String key, String value, double importance, String confirmed) {
        return new Memory(1, 10001, type, "user", key, value, "chat", importance, 0.9,
                0, confirmed, confirmed, null, null, "none", null, "active", null);
    }

    @Test
    void tokensUseChineseBigrams() {
        Set<String> t = MemoryRecall.tokens("我昨天加班到十点");
        assertTrue(t.contains("加班"));
        assertTrue(t.contains("昨天"));
        // 单字不单独成词，避免噪声
        assertTrue(!t.contains("我"));
    }

    @Test
    void relevanceHitsSameTopic() {
        Set<String> q = MemoryRecall.tokens("昨天又加班到十点了吧");
        Memory m = mem("event", "加班", "加班到 22:00", 0.8, "2026-10-27 22:00:00");
        assertTrue(MemoryRecall.relevance(q, m) > 0.2);

        Memory unrelated = mem("profile", "城市", "杭州", 0.8, "2026-10-27 22:00:00");
        assertEquals(0, MemoryRecall.relevance(q, unrelated));
    }

    @Test
    void freshnessDecaysWithAge() {
        String today = LocalDateTime.now().format(TS);
        String monthAgo = LocalDateTime.now().minusDays(30).format(TS);
        assertTrue(MemoryRecall.freshness(today) > 0.9);
        assertTrue(MemoryRecall.freshness(monthAgo) < 0.6);
        assertTrue(MemoryRecall.freshness(monthAgo) > 0.3);
    }

    @Test
    void groupsMatchDesign() {
        assertEquals("【最近发生】", MemoryRecall.groupOf("event"));
        assertEquals("【你们之间】", MemoryRecall.groupOf("relation"));
        assertEquals("【关于对方】", MemoryRecall.groupOf("profile"));
        assertEquals("【关于对方】", MemoryRecall.groupOf(null));
    }
}
