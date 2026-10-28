package com.qqbot.llm;

import com.qqbot.chat.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「（我发了一张表情包：…）」不能被当成对话历史，否则模型会照着写并当台词发出去 */
class MediaMarkerTest {

    @Test
    void recognizesMediaRecords() {
        assertTrue(PromptBuilder.isMediaMarker(new ChatMessage("assistant", "（我发了一张表情包：一只猫）")));
        assertTrue(PromptBuilder.isMediaMarker(new ChatMessage("assistant", "（语音）早点睡")));
        assertTrue(PromptBuilder.isMediaMarker(new ChatMessage("assistant", "（表情包缺失）没有「奶龙」")));
    }

    @Test
    void keepsNormalReplies() {
        assertFalse(PromptBuilder.isMediaMarker(new ChatMessage("assistant", "早点睡吧")));
        assertFalse(PromptBuilder.isMediaMarker(new ChatMessage("user", "（我发了一张表情包：一只猫）")));
    }
}
