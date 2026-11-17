package com.qqbot.chat;

import com.qqbot.config.BotProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 门控回归：提问/媒体必须回，单字/纯表情允许已读不回，连发必须回。 */
class ReplyGateTest {

    private ReplyGate gate(double skip) {
        BotProperties props = new BotProperties();
        props.getReply().setGate(true);
        props.getReply().setGateSkip(skip);
        return new ReplyGate(props);
    }

    @Test
    void questionsAlwaysReply() {
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("在吗", false, false, 1));
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("今天吃什么？", false, false, 1));
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("为啥", false, false, 1));
    }

    @Test
    void mediaAlwaysReply() {
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("", true, false, 1));
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("", false, true, 1));
    }

    @Test
    void shortFillerCanBeSkippedOrShort() {
        assertEquals(ReplyGate.Decision.SKIP, gate(1.0).decide("嗯", false, false, 1));
        assertEquals(ReplyGate.Decision.SKIP, gate(1.0).decide("哈哈", false, false, 1));
        assertEquals(ReplyGate.Decision.SHORT, gate(0.0).decide("嗯", false, false, 1));
    }

    @Test
    void consecutiveMessagesAlwaysReply() {
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("嗯", false, false, 2));
    }

    @Test
    void normalSentencesReply() {
        assertEquals(ReplyGate.Decision.REPLY, gate(1.0).decide("今天加班到十点，累死了", false, false, 1));
    }
}
