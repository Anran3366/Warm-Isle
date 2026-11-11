package com.qqbot.voice;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import com.qqbot.plugin.exec.VoiceBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 插件语音与聊天语音共用同一组频控计数（设计文档 §8.6 / P1-1）。 */
class VoiceBudgetTest {

    private final Map<String, String> store = new HashMap<>();
    private VoiceBudget budget;

    @BeforeEach
    void setUp() {
        SettingStore settings = mock(SettingStore.class);
        when(settings.get(anyString(), nullable(String.class)))
                .thenAnswer(inv -> store.containsKey(inv.getArgument(0))
                        ? store.get(inv.getArgument(0)) : inv.getArgument(1));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(settings).set(anyString(), anyString());

        BotProperties props = mock(BotProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(props.getVoice().getDailyLimit()).thenReturn(3);
        budget = new VoiceBudget(settings, props);
    }

    @Test
    void intervalBlocksSecondNonExplicitVoice() {
        assertTrue(budget.allow(1L, false));
        budget.record(1L);
        assertFalse(budget.allow(1L, false), "90 分钟内不能再发非点播语音");
        assertTrue(budget.allow(1L, true), "明确点播不受间隔限制");
    }

    @Test
    void explicitCapIsSixPerDay() {
        for (int i = 0; i < 6; i++) {
            assertTrue(budget.allow(2L, true), "第 " + (i + 1) + " 条应允许");
            budget.record(2L);
        }
        assertFalse(budget.allow(2L, true), "明确点播每天最多 6 条");
    }

    @Test
    void reserveConsumesAndReleaseRollsBack() {
        assertTrue(budget.reserve(3L, true));
        // 预占后算 1 条；失败释放后回到 0
        budget.release(3L);
        assertTrue(budget.allow(3L, true));
    }
}
