package com.qqbot.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 主动回忆时间规则（设计稿第 4 节） */
class FollowUpPlannerTest {

    /** 夜间整理时刻：10-28 00:05，处理 10-27 的事 */
    private final LocalDateTime nightly = LocalDateTime.of(2026, 10, 28, 0, 5);

    @Test
    void overtimeGoesToTonight2100To2230() {
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "event", "overtime", "加班到22:00");
        assertNotNull(p);
        assertEquals(LocalDate.of(2026, 10, 28), p.at().toLocalDate());
        assertFalse(p.at().isBefore(LocalDateTime.of(2026, 10, 28, 21, 0)));
        assertFalse(p.at().isAfter(LocalDateTime.of(2026, 10, 28, 22, 30)));
    }

    @Test
    void interviewGoesTo1900To2100() {
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "event", "interview", "面试");
        assertNotNull(p);
        assertTrue(p.at().getHour() >= 19 && p.at().getHour() <= 21);
    }

    @Test
    void sickGoesToMorning10() {
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "event", "sick", "感冒了");
        assertNotNull(p);
        assertEquals(10, p.at().getHour());
        assertEquals(0, p.at().getMinute());
    }

    @Test
    void fightGoesTwoDaysLaterAt20() {
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "event", "fight", "吵架");
        assertNotNull(p);
        assertEquals(LocalDate.of(2026, 10, 29), p.at().toLocalDate());
        assertEquals(20, p.at().getHour());
    }

    @Test
    void coldwarGoesTomorrow20() {
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "relation", "coldwar", "冷战");
        assertNotNull(p);
        assertEquals(LocalDate.of(2026, 10, 28), p.at().toLocalDate());
        assertEquals(20, p.at().getHour());
    }

    @Test
    void promiseParsesWeekend() {
        // 10-28 是周三 → 下一个周六是 10-31
        FollowUpPlanner.Plan p = FollowUpPlanner.plan(nightly, "relation", "promise", "说好周末聊");
        assertNotNull(p);
        assertEquals(LocalDate.of(2026, 10, 31), p.at().toLocalDate());
        assertEquals(20, p.at().getHour());
    }

    @Test
    void otherEventHasNoFollowUp() {
        assertNull(FollowUpPlanner.plan(nightly, "event", "other", "今天吃了牛肉面"));
    }

    @Test
    void inferKindFromText() {
        assertEquals("overtime", FollowUpPlanner.inferKind("event", "又加班到十点"));
        assertEquals("sick", FollowUpPlanner.inferKind("event", "胃疼得厉害"));
        assertEquals("promise", FollowUpPlanner.inferKind("relation", "说好周末一起看电影"));
        assertEquals("other", FollowUpPlanner.inferKind("event", "今天天气不错"));
    }
}
