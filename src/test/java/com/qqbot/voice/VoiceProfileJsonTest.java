package com.qqbot.voice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 音色库是靠 JSON 存进 settings 的，序列化/反序列化不能悄悄丢字段 */
class VoiceProfileJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void profileRoundTrip() throws Exception {
        VoiceProfile profile = new VoiceProfile("abc123", "我的音色", "/tmp/ref.wav", 10.5, 24000,
                "warn", List.of("音频 35 秒，偏长"), "你好呀", "2026-10-23 15:00");

        String json = mapper.writeValueAsString(List.of(profile));
        List<VoiceProfile> back = mapper.readValue(json, new TypeReference<List<VoiceProfile>>() {
        });

        assertEquals(1, back.size());
        assertEquals(profile, back.get(0));
        assertEquals("音频 35 秒，偏长", back.get(0).issues().get(0));
    }

    @Test
    void analysisRoundTripKeepsVerdict() throws Exception {
        AudioAnalysis analysis = new AudioAnalysis(8.2, 24000, 1, "pcm_s16le", -21.5, -2.1, 0.12,
                "测试文本", "ok", List.of());

        String json = mapper.writeValueAsString(analysis);
        AudioAnalysis back = mapper.readValue(json, AudioAnalysis.class);

        assertEquals("ok", back.verdict());
        assertEquals(8.2, back.seconds());
    }
}
