package com.qqbot.meme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 「要一张奶龙的表情包」这类话里抽出干净的实体名 */
class MemeEntityTest {

    @Test
    void stripsPolitenessAndSuffix() {
        assertEquals("奶龙", MemeService.extractEntity("我想要一张奶龙的表情包[表情]"));
        assertEquals("奶龙", MemeService.extractEntity("给我发张奶龙表情包"));
        assertEquals("猫猫", MemeService.extractEntity("来一张猫猫的表情包吧"));
    }

    @Test
    void rejectsTooShort() {
        assertNull(MemeService.extractEntity("发个表情包"));
    }
}
