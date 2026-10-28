package com.qqbot.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 复读判定：线上 1759/1761「语音发的句子又被文字重发一遍」的回归测试 */
class ChatRepeatGuardTest {

    @Test
    void voicePrefixDoesNotHideDuplicate() {
        assertTrue(ChatService.isDuplicateReply(
                "在呢，喊这么大声干嘛",
                "（语音）在呢在呢，喊这么大声干嘛"));
    }

    @Test
    void emojiMarkerIsStripped() {
        // 纯媒体标记归一化后为空，不参与文字比对
        assertEquals("", ChatService.normalizeReply("（我发了一张表情包：气鼓鼓的小狗）"));
        assertEquals("哈哈哈", ChatService.normalizeReply("（我发了一张表情包：气鼓鼓的小狗）哈哈哈"));
        assertEquals("在呢", ChatService.normalizeReply("在呢。"));
    }

    @Test
    void shortAnswerIsNotTreatedAsDuplicate() {
        assertFalse(ChatService.isDuplicateReply("嗯", "今天下班路上看到一只猫，蹲在便利店门口"));
    }

    @Test
    void genuinelyDifferentRepliesPass() {
        assertFalse(ChatService.isDuplicateReply(
                "那你说，要我怎么做才行",
                "你这招也太狠了吧，我认输我认输"));
    }

    @Test
    void substringWithSimilarLengthIsDuplicate() {
        assertTrue(ChatService.isDuplicateReply("你说这个我就来气", "你说这个我就来气啊"));
    }
}
