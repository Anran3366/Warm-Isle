package com.qqbot.meme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发图之后那句要像人说的：不解释梗、不描述画面、不带括号 */
class MemeLineTest {

    @Test
    void linesAreShortAndNatural() {
        for (int i = 0; i < 50; i++) {
            String line = MemeService.pickLine("奶龙");
            assertFalse(line.isBlank());
            assertTrue(line.length() <= 30, line);
            assertFalse(line.contains("（"), line);
            assertFalse(line.contains("表情包"), line);
            assertFalse(line.contains("梗"), line);
            assertFalse(line.contains("奶龙的图") && line.contains("奶龙的图的图"), line);
        }
    }

    @Test
    void mentionsEntityInSomeVariants() {
        boolean mentions = false;
        for (int i = 0; i < 50; i++) {
            if (MemeService.pickLine("奶龙").contains("奶龙")) {
                mentions = true;
                break;
            }
        }
        assertTrue(mentions, "至少有一部分写法会带上名字");
    }
}
