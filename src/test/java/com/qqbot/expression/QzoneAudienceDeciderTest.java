package com.qqbot.expression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QzoneAudienceDeciderTest {

    private QzoneAudienceDecider decider(String json) {
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(llm.chat(anyList(), anyDouble(), anyInt())).thenReturn(new LlmResult(json, 0, 0));
        return new QzoneAudienceDecider(mock(ExpressionConfig.class), llm, new ObjectMapper());
    }

    @Test
    void targetedDecisionKeepsTarget() {
        QzoneAudienceDecider d = decider(
                "{\"audience\":\"targeted\",\"ugcRight\":16,\"targetUins\":[1251859460],\"reason\":\"只对一个人说\"}");
        QzoneAudienceDecider.QzoneDecision decision = d.decide("想起你了", 1251859460, true, "平静");
        assertEquals(QzoneAudienceDecider.QzoneAudience.TARGETED, decision.audience());
        assertNotNull(decision.reason());
    }

    @Test
    void emptyContentSkips() {
        QzoneAudienceDecider d = decider("{}");
        assertEquals(QzoneAudienceDecider.QzoneAudience.SKIP, d.decide("", 0, false, "平静").audience());
    }
}
