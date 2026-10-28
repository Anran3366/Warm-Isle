package com.qqbot.voice;

import java.util.List;

/** 一个已入库的克隆音色。file 是绝对路径（转码后的 24k 单声道 WAV）。 */
public record VoiceProfile(
        String id,
        String name,
        String file,
        double seconds,
        int sampleRate,
        String verdict,
        List<String> issues,
        String transcript,
        String createdAt) {
}
