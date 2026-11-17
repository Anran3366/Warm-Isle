package com.qqbot.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定规则回归：能力声明、媒体标记、体积上限 */
class PromptRulesTest {

    private final String rules = PromptBuilder.staticRules();

    @Test
    void statesCapabilitiesInsteadOfDenyingThem() {
        assertTrue(rules.contains("你会发语音条"));
        assertTrue(rules.contains("看得懂"));
        assertTrue(rules.contains("[[语音]]"));
        assertTrue(rules.contains("不许说「我发不出来」"));
    }

    @Test
    void memoryHintsTellItToAskInsteadOfGuessing() {
        assertTrue(rules.contains("不要念具体日期"));
        assertTrue(rules.contains("记不准就自然问一句"));
    }

    @Test
    void noStrayQuoteArtifacts() {
        // 老版本开头三行被单引号包住，直接发给模型的是 '- 像发微信…
        assertFalse(rules.startsWith("'"));
        assertFalse(rules.contains("'"));
    }

    @Test
    void staysCompact() {
        // 2026-10-28 合并后 1206 字符；超过 1600 说明又在往上堆规则
        assertTrue(rules.length() < 1600, "rules 长度 " + rules.length());
    }

    @Test
    void doesNotTellItToSelfDeprecate() {
        // 旧规则里写死了「先认一句（行吧，是我嘴欠）」，模型会照抄，和人格卡的「不卑微」打架
        assertFalse(rules.contains("嘴欠"), "规则里不应再出现嘴欠这种自贬示范");
        assertTrue(rules.contains("别自贬"));
        assertTrue(rules.contains("不反问"));
    }
}
