package com.qqbot.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 动态拼装：该常驻的常驻，该触发的触发 */
class PromptSectionsTest {

    private static final String RULES = PromptBuilder.staticRules();

    private PromptSections.Ctx ctx(String text) {
        return new PromptSections.Ctx(text, 0, 100, false, false, false);
    }

    @Test
    void keepsCoreRulesAlways() {
        String r = PromptSections.rulesFor(RULES, ctx("今天天气不错"));
        assertTrue(r.contains("说话方式"));
        assertTrue(r.contains("时间感"));
        assertFalse(r.contains("接住情绪"));
        assertFalse(r.contains("表情、语音、图片"));
    }

    @Test
    void activatesEmotionAndPushRules() {
        assertTrue(PromptSections.rulesFor(RULES, ctx("好烦啊，不想上班")).contains("接住情绪"));
        assertTrue(PromptSections.rulesFor(RULES, ctx("嗯")).contains("往前聊"));
    }

    @Test
    void activatesMediaRuleWhenPlanned() {
        PromptSections.Ctx c = new PromptSections.Ctx("发个表情", 0, 100, false, true, false);
        assertTrue(PromptSections.rulesFor(RULES, c).contains("表情、语音、图片"));
    }

    @Test
    void memoryRuleOnlyWithMemory() {
        PromptSections.Ctx withMem = new PromptSections.Ctx("加班", 0, 100, true, false, false);
        assertTrue(PromptSections.rulesFor(RULES, withMem).contains("记性与分寸"));
    }

    @Test
    void splitsPersonaIntoNamedSections() {
        String persona = "你是小满。\n【关于你自己】\n不主动介绍自己。\n【性格】\n甜。\n【边界】\n不预设恋爱。";
        List<PromptSections.Section> sections = PromptSections.personaSections(persona);
        assertTrue(sections.size() >= 4);
        Map<String, String> byTitle = new java.util.LinkedHashMap<>();
        sections.forEach(s -> byTitle.put(s.title(), s.body()));
        assertTrue(byTitle.get("性格").contains("甜"));
    }

    @Test
    void personaKeepsCoreAndTriggersOthers() {
        String persona = "你是小满。\n【关于你自己】\n不主动介绍自己。\n【性格】\n甜。\n"
                + "【说话方式】\n短句。\n【哄人的顺序】\n先接情绪。\n【边界】\n不预设恋爱。\n【绝对不要】\n不说教。";
        String normal = PromptSections.personaFor(persona, ctx("今天吃了什么"));
        assertTrue(normal.contains("性格"));
        assertTrue(normal.contains("绝对不要"));
        assertFalse(normal.contains("说话方式"), "人设里的说话方式和固定规则重复，动态模式下应去掉");
        assertFalse(normal.contains("关于你自己"));
        assertFalse(normal.contains("哄人的顺序"));

        String upset = PromptSections.personaFor(persona, ctx("我好难过"));
        assertTrue(upset.contains("哄人的顺序"));

        String who = PromptSections.personaFor(persona, ctx("你是谁呀"));
        assertTrue(who.contains("关于你自己"));

        String intimate = PromptSections.personaFor(persona, ctx("做我女朋友吧"));
        assertTrue(intimate.contains("边界"));
    }

    @Test
    void dynamicIsSmallerThanFull() {
        String full = PromptBuilder.staticRules();
        String dynamic = PromptSections.rulesFor(RULES, ctx("在吗"));
        assertTrue(dynamic.length() < full.length());
    }
}
