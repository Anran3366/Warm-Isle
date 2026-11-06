package com.qqbot.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 遗忘曲线 / 复习 / 周期日的纯函数测试 */
class MemoryStrengthTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 2, 9, 0);

    private static Memory memory(String type, double importance, boolean pinned, String strengthAt) {
        return new Memory(1, 10001, type, "user", "小狗", "喜欢小狗", "chat",
                importance, 0.8, 0, strengthAt, strengthAt, null, null, "none", null,
                "active", null, 1.0, strengthAt, pinned, null, null, 0);
    }

    @Test
    void strangerChatIsForgottenInAboutFiveDays() {
        Memory m = memory("event", 0.4, false, "2026-11-02 09:00:00");
        double day1 = MemoryStrength.effective(m, T0.plusDays(1));
        double day3 = MemoryStrength.effective(m, T0.plusDays(3));
        double day5 = MemoryStrength.effective(m, T0.plusDays(5));

        assertTrue(day1 < 0.75, "一天后应该已经明显变淡，实际 " + day1);
        assertTrue(MemoryStrength.fading(day3), "三天后应该开始模糊，实际 " + day3);
        assertTrue(day5 < MemoryStrength.FORGET, "五天后应该忘掉，实际 " + day5);
    }

    @Test
    void profileDecaysSlowerThanEvent() {
        Memory profile = memory("profile", 0.8, false, "2026-11-02 09:00:00");
        Memory event = memory("event", 0.8, false, "2026-11-02 09:00:00");
        LocalDateTime later = T0.plusDays(4);
        assertTrue(MemoryStrength.effective(profile, later) > MemoryStrength.effective(event, later));
    }

    @Test
    void pinnedNeverForgets() {
        Memory birthday = memory("profile", 0.5, true, "2020-01-01 00:00:00");
        assertEquals(1.0, MemoryStrength.effective(birthday, T0.plusYears(5)), 1e-9);
        assertFalse(MemoryStrength.forgotten(MemoryStrength.effective(birthday, T0.plusYears(5))));
    }

    @Test
    void rehearsalRestoresFullStrength() {
        // 周一说过、周五快忘了（0.2），周六又说了一次 → 回满
        Memory m = memory("profile", 0.4, false, "2026-11-02 09:00:00");
        double friday = MemoryStrength.effective(m, T0.plusDays(4));
        assertTrue(friday < 0.4, "周五应该已经落到 0.2 附近，实际 " + friday);
        assertEquals(1.0, MemoryStrength.afterRehearsal(), 1e-9);
    }

    @Test
    void recallBumpsOnlySlightly() {
        double after = MemoryStrength.onRecall(0.2);
        assertTrue(after > 0.2 && after < 0.3);
        assertTrue(MemoryStrength.onRecall(after) > after);
        assertEquals(1.0, MemoryStrength.onRecall(1.0), 1e-9);
    }

    @Test
    void birthdayMonthDayIsParsed() {
        assertEquals("10-26", RecurringDate.monthDay("生日", "生日是10月26日"));
        assertEquals("03-05", RecurringDate.monthDay("生日", "3月5号"));
        assertEquals("12-01", RecurringDate.monthDay("生日", "12-01"));
        assertEquals(null, RecurringDate.monthDay("生日", "13月40日"));
        assertTrue(RecurringDate.looksBirthday("生日", "10月26日"));
        assertFalse(RecurringDate.looksBirthday("城市", "杭州"));
    }
}
