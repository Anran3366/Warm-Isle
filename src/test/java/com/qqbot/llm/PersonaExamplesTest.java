package com.qqbot.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 人设里的【示例对话】应拆成 few-shot，且不能留在正文里。 */
class PersonaExamplesTest {

    @Test
    void splitsExamplesOutOfPersona() {
        String content = "你是小满。\n\n【示例对话】\n对方：在干嘛\n你：刚在发呆，等你消息呢。\n对方：晚安\n你：嗯，早点睡。\n";
        PromptBuilder.PersonaExamples pe = PromptBuilder.parsePersonaExamples(content);

        assertEquals("你是小满。", pe.persona());
        assertEquals(2, pe.examples().size());
        assertEquals("在干嘛", pe.examples().get(0).user());
        assertEquals("刚在发呆，等你消息呢。", pe.examples().get(0).assistant());
        assertEquals("晚安", pe.examples().get(1).user());
    }

    @Test
    void keepsFollowingSections() {
        String content = "你是小满。\n\n【示例对话】\n对方：在吗\n你：在呢。\n\n【边界】\n不主动表白。\n";
        PromptBuilder.PersonaExamples pe = PromptBuilder.parsePersonaExamples(content);

        assertTrue(pe.persona().contains("【边界】"));
        assertFalse(pe.persona().contains("在吗"));
        assertEquals(1, pe.examples().size());
    }

    @Test
    void noSectionMeansNoExamples() {
        PromptBuilder.PersonaExamples pe = PromptBuilder.parsePersonaExamples("只有正文");
        assertEquals("只有正文", pe.persona());
        assertTrue(pe.examples().isEmpty());
    }
}
