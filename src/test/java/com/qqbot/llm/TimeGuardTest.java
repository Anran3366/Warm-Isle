package com.qqbot.llm;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimeGuardTest {

    @Test
    void rewritesNightWordsDuringDaytime() {
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(llm.chat(anyList(), anyDouble(), anyInt()))
                .thenReturn(new LlmResult("这会儿还早呢，晚点再说", 0, 0));

        TimeGuard guard = new TimeGuard(llm);
        String out = guard.fix("晚安，早点睡", LocalDateTime.of(2026, 10, 25, 17, 30));

        assertNotNull(out);
        assertFalse(out.contains("晚安"), "17:30 不应出现晚安: " + out);
        assertFalse(out.contains("早点睡"), "18:30 不应说早点睡: " + out);
    }

    @Test
    void keepsNightTalkAtNight() {
        TimeGuard guard = new TimeGuard(mock(DeepSeekClient.class));
        String text = "晚安，早点睡";
        String out = guard.fix(text, LocalDateTime.of(2026, 10, 25, 23, 30));
        assertNotNull(out);
    }
}
