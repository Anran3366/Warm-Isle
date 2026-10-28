package com.qqbot.voice;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 上传样本的体检结果。
 * verdict: ok（可直接用）/ warn（能用但建议改进）/ reject（不能用，已拒绝入库）
 */
public record AudioAnalysis(
        double seconds,
        int sampleRate,
        int channels,
        String codec,
        double meanDb,
        double maxDb,
        double silenceRatio,
        String transcript,
        String verdict,
        List<String> issues) {

    @JsonIgnore
    public boolean rejected() {
        return "reject".equals(verdict);
    }
}
