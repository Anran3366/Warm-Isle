package com.qqbot.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 媒体预判的 JSON 解析与降级 */
class MediaPlannerTest {

    @Test
    void parsesEmojiPlan() {
        MediaPlanner.Plan p = MediaPlanner.parse(
                "{\"media\":\"emoji\",\"intent\":\"想抱抱\",\"reason\":\"对方在撒娇\"}");
        assertEquals(MediaPlanner.Media.EMOJI, p.media());
        assertEquals("想抱抱", p.intent());
        assertTrue(p.wantsEmoji());
    }

    @Test
    void parsesVoicePlan() {
        MediaPlanner.Plan p = MediaPlanner.parse(
                "{\"media\":\"voice\",\"intent\":\"\",\"reason\":\"对方想听声音\"}");
        assertEquals(MediaPlanner.Media.VOICE, p.media());
        assertTrue(p.wantsVoice());
    }

    @Test
    void parsesNoneAndUnknown() {
        assertEquals(MediaPlanner.Media.NONE,
                MediaPlanner.parse("{\"media\":\"none\"}").media());
        assertEquals(MediaPlanner.Media.NONE,
                MediaPlanner.parse("{\"media\":\"whatever\"}").media());
    }

    @Test
    void emojiWithoutIntentFallsBackToNone() {
        assertEquals(MediaPlanner.Media.NONE,
                MediaPlanner.parse("{\"media\":\"emoji\",\"intent\":\"\"}").media());
    }

    @Test
    void brokenJsonFallsBackToNone() {
        assertEquals(MediaPlanner.Media.NONE, MediaPlanner.parse("我觉得应该发个表情").media());
        assertEquals(MediaPlanner.Media.NONE, MediaPlanner.parse(null).media());
        assertEquals(MediaPlanner.Media.NONE, MediaPlanner.parse("{not json}").media());
    }

    @Test
    void acceptsJsonWrappedInText() {
        MediaPlanner.Plan p = MediaPlanner.parse(
                "好的：\n```json\n{\"media\":\"emoji\",\"intent\":\"得意\",\"reason\":\"斗嘴赢了一句\"}\n```");
        assertEquals(MediaPlanner.Media.EMOJI, p.media());
        assertEquals("得意", p.intent());
    }
}
