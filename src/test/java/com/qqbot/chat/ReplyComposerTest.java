package com.qqbot.chat;

import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplyComposerTest {

    private final ReplyComposer composer = new ReplyComposer(new BotProperties());

    @Test
    void splitKeepsNonEmptyParts() {
        List<String> parts = composer.split("今天下班路上看到一只猫，蹲在便利店门口。它一直盯着我手里的关东煮，我就分了一点给它。结果它吃完就走了，好现实啊。");
        assertFalse(parts.isEmpty());
        assertTrue(parts.size() <= 5);
        for (String p : parts) {
            assertFalse(p.isBlank());
        }
    }

    @Test
    void splitNeverExceedsFive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句话，随便说点什么。");
        }
        assertTrue(composer.split(sb.toString()).size() <= 5);
    }

    @Test
    void adaptiveHintIsBounded() {
        String hint = composer.adaptiveHint("你好呀");
        assertNotNull(hint);
        assertTrue(hint.length() < 200);
    }
}
