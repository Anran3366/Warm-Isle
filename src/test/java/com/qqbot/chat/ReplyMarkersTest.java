package com.qqbot.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** [[语音]] / [[表情:…]] 标记解析：既要能表达意图，又不能漏进正文或历史 */
class ReplyMarkersTest {

    @Test
    void parsesVoiceMarker() {
        ReplyMarkers.Parsed p = ReplyMarkers.parse("早点睡吧，别熬了。[[语音]]");
        assertEquals("早点睡吧，别熬了。", p.text());
        assertTrue(p.wantsVoice());
        assertNull(p.emojiIntent());
    }

    @Test
    void parsesVoiceHintAndEmojiTogether() {
        ReplyMarkers.Parsed p = ReplyMarkers.parse("抱抱你[[表情:想抱抱]][[语音:哄哄她]]");
        assertEquals("抱抱你", p.text());
        assertEquals("想抱抱", p.emojiIntent());
        assertEquals("哄哄她", p.voiceHint());
        assertTrue(p.wantsVoice());
    }

    @Test
    void plainTextUntouched() {
        ReplyMarkers.Parsed p = ReplyMarkers.parse("今天好累啊");
        assertEquals("今天好累啊", p.text());
        assertFalse(p.wantsVoice());
        assertNull(p.emojiIntent());
    }

    @Test
    void stripRemovesMarkersForHistory() {
        assertEquals("早点睡", ReplyMarkers.strip("早点睡[[语音]]"));
        assertEquals("", ReplyMarkers.strip("[[语音]]"));
    }

    @Test
    void fakeMediaMarkerBecomesPlainText() {
        ReplyMarkers.Parsed p = ReplyMarkers.parse("（我发了一张表情包：哼，不理你）");
        assertEquals("哼，不理你", p.text());
        assertFalse(p.text().contains("表情包"));
    }

    @Test
    void inlineFakeMediaMarkerIsStripped() {
        ReplyMarkers.Parsed p = ReplyMarkers.parse("好的呀（我发了一张表情包：一只猫）你说呢");
        assertEquals("好的呀你说呢", p.text());
    }

    @Test
    void duplicateGuardIgnoresMarkers() {
        assertTrue(ChatService.isDuplicateReply("早点睡吧，别熬夜了[[语音]]", "早点睡吧，别熬夜了"));
    }
}
