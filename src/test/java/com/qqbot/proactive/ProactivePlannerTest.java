package com.qqbot.proactive;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProactivePlannerTest {

    @Test
    void silenceDelayWithinBoundsAndVaries() {
        Random r = new Random(42);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long v = ProactivePlanner.silenceDelayMinutes(240, r);
            assertTrue(v >= 90 && v <= 540, "延迟应在 1.5–9 小时内: " + v);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(max - min > 30, "延迟应有抖动");
    }

    @Test
    void quietWindowIsRespected() {
        LocalTime from = LocalTime.of(23, 30);
        LocalTime to = LocalTime.of(8, 0);
        assertTrue(ProactivePlanner.inWindow(LocalDateTime.of(2026, 10, 23, 23, 45), from, to));
        assertTrue(ProactivePlanner.inWindow(LocalDateTime.of(2026, 10, 23, 3, 0), from, to));
        assertFalse(ProactivePlanner.inWindow(LocalDateTime.of(2026, 10, 23, 15, 0), from, to));
    }

    @Test
    void alignToActiveWindowMovesForward() {
        LocalDateTime night = LocalDateTime.of(2026, 10, 23, 2, 0);
        LocalDateTime aligned = ProactivePlanner.alignToWindow(night, LocalTime.of(9, 0), LocalTime.of(23, 0));
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 0), aligned);
        LocalDateTime afternoon = LocalDateTime.of(2026, 10, 23, 15, 0);
        assertEquals(afternoon, ProactivePlanner.alignToWindow(afternoon, LocalTime.of(9, 0), LocalTime.of(23, 0)));
    }

    @Test
    void backoffGrowsWithStage() {
        Random r = new Random(7);
        int first = ProactivePlanner.backoffDays(0, 2, 5, r);
        int second = ProactivePlanner.backoffDays(1, 2, 5, r);
        int third = ProactivePlanner.backoffDays(2, 2, 5, r);
        assertTrue(first >= 2 && first <= 5);
        assertTrue(second >= 7 && second <= 14);
        assertTrue(third >= 21 && third <= 30);
    }

    @Test
    void bubblesAreLimitedAndShort() {
        String text = "刚看到个东西。想起你昨天说的面试。怎么样啦？要不要说说。";
        List<String> bubbles = ProactivePlanner.splitBubbles(text, 3);
        assertFalse(bubbles.isEmpty());
        assertTrue(bubbles.size() <= 3);
        for (String b : bubbles) {
            assertTrue(b.length() <= 40);
        }
    }

    @Test
    void needinessIsDetected() {
        assertTrue(ProactivePlanner.hasNeediness("怎么不回我呀"));
        assertTrue(ProactivePlanner.hasNeediness("你是不是又忙忘了"));
        assertFalse(ProactivePlanner.hasNeediness("刚看到个东西，想到你了"));
    }

    @Test
    void activityAndStopDetection() {
        assertEquals("洗澡", ProactivePlanner.activityHint("我去洗澡了"));
        assertEquals("吃饭", ProactivePlanner.activityHint("先吃饭"));
        assertNotNull(ProactivePlanner.activityHint("在忙"));
        assertTrue(ProactivePlanner.asksStop("别发了"));
        assertFalse(ProactivePlanner.asksBusy("我去洗澡了"));
        assertTrue(ProactivePlanner.asksBusy("在忙，等会聊"));
    }
}
