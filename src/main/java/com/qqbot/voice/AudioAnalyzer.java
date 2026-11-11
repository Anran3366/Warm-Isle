package com.qqbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传样本质检：时长、采样率、声道、平均/峰值音量、静音占比，最后用 ASR 确认「确实有人在说话」。
 * 目的不是炫技，而是把「学习成功但合成不像」的样本挡在入库之前。
 */
@Component
public class AudioAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalyzer.class);

    private static final Pattern MEAN_DB = Pattern.compile("mean_volume:\\s*(-?\\d+(?:\\.\\d+)?) dB");
    private static final Pattern MAX_DB = Pattern.compile("max_volume:\\s*(-?\\d+(?:\\.\\d+)?) dB");
    private static final Pattern SILENCE = Pattern.compile("silence_duration:\\s*(\\d+(?:\\.\\d+)?)");

    private final MimoAsr asr;
    private final ObjectMapper mapper;

    public AudioAnalyzer(MimoAsr asr, ObjectMapper mapper) {
        this.asr = asr;
        this.mapper = mapper;
    }

    public AudioAnalysis analyze(Path wav) {
        List<String> issues = new ArrayList<>();
        boolean reject = false;

        double seconds = 0;
        int sampleRate = 0;
        int channels = 0;
        String codec = "";

        try {
            Ffmpeg.Exec probe = Ffmpeg.run(20, Ffmpeg.ffprobe(),
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name,sample_rate,channels:format=duration",
                    "-of", "json", wav.toString());
            if (probe.ok()) {
                JsonNode root = mapper.readTree(probe.output());
                JsonNode stream = root.path("streams").path(0);
                codec = stream.path("codec_name").asText("");
                sampleRate = stream.path("sample_rate").asInt(0);
                channels = stream.path("channels").asInt(0);
                seconds = root.path("format").path("duration").asDouble(0);
            } else {
                // ffprobe 缺失时不让整条链路挂掉：转码产物固定是 24k 单声道 16bit PCM，按时长估算足够准
                seconds = estimateSeconds(wav);
                sampleRate = 24000;
                channels = 1;
                codec = "pcm_s16le";
                if (seconds > 0) {
                    issues.add("ffprobe 不可用，已按时长估算，建议检查 ffmpeg 安装");
                }
            }
        } catch (Exception e) {
            log.warn("ffprobe 失败: {}", e.getMessage());
            seconds = estimateSeconds(wav);
            sampleRate = 24000;
            channels = 1;
            codec = "pcm_s16le";
        }

        if (seconds <= 0) {
            return new AudioAnalysis(0, sampleRate, channels, codec, 0, 0, 0, null,
                    "reject", issues);
        }

        if (seconds < 3) {
            issues.add(String.format("音频只有 %.1f 秒，太短，请提供 5–15 秒的连续说话", seconds));
            reject = true;
        } else if (seconds > 60) {
            issues.add(String.format("音频 %.0f 秒，太长，请截取 5–15 秒（每次合成都要重传，越长越慢）", seconds));
            reject = true;
        } else if (seconds > 30) {
            issues.add(String.format("音频 %.0f 秒，偏长，建议 5–15 秒", seconds));
        }
        if (sampleRate > 0 && sampleRate < 16000) {
            issues.add(String.format("采样率只有 %d Hz，建议 24 kHz 以上", sampleRate));
        }
        if (channels > 1) {
            issues.add("检测到多声道，已自动混成单声道");
        }

        double meanDb = -99;
        double maxDb = -99;
        double silenceRatio = 0;

        if (seconds >= 3) {
            try {
                Ffmpeg.Exec vol = Ffmpeg.run(60, Ffmpeg.ffmpeg(), "-hide_banner", "-nostats",
                        "-i", wav.toString(), "-af", "volumedetect", "-f", "null", "-");
                meanDb = firstMatch(MEAN_DB, vol.output(), -99);
                maxDb = firstMatch(MAX_DB, vol.output(), -99);

                Ffmpeg.Exec sil = Ffmpeg.run(60, Ffmpeg.ffmpeg(), "-hide_banner", "-nostats",
                        "-i", wav.toString(), "-af", "silencedetect=noise=-40dB:d=0.4",
                        "-f", "null", "-");
                double silence = 0;
                Matcher m = SILENCE.matcher(sil.output());
                while (m.find()) {
                    silence += Double.parseDouble(m.group(1));
                }
                silenceRatio = Math.min(1, silence / seconds);
            } catch (Exception e) {
                log.warn("音量/静音检测失败: {}", e.getMessage());
            }

            if (meanDb < -45) {
                issues.add(String.format("平均音量只有 %.0f dB，太小声，请靠近麦克风重录", meanDb));
                reject = true;
            } else if (meanDb > -12) {
                issues.add(String.format("平均音量 %.0f dB 偏大，可能有爆音", meanDb));
            }
            if (maxDb >= -0.1) {
                issues.add("峰值已经削波，建议降低录音音量后重录");
            }
            if (silenceRatio > 0.6) {
                issues.add(String.format("约 %.0f%% 的时间是静音，建议只保留说话片段", silenceRatio * 100));
            }
        }

        String transcript = null;
        if (!reject) {
            transcript = asr.transcribe(wav);
            if (transcript == null) {
                issues.add("未能做人声校验（ASR 暂时不可用），请自行确认样本里只有一个人在说话");
            } else if (cleanLength(transcript) < 2) {
                issues.add("没有识别到清晰人声，请确认这是纯说话录音，而不是纯音乐/静音");
                reject = true;
            }
        }

        String verdict = reject ? "reject" : (issues.isEmpty() ? "ok" : "warn");
        return new AudioAnalysis(seconds, sampleRate, channels, codec, meanDb, maxDb,
                round(silenceRatio), transcript, verdict, issues);
    }

    /** 只读时长，用于上传后、转码前的"太长"预检，避免把 1 小时音频转成几百 MB 的 WAV */
    public double probeSeconds(java.nio.file.Path file) {
        try {
            Ffmpeg.Exec probe = Ffmpeg.run(20, Ffmpeg.ffprobe(), "-v", "error",
                    "-show_entries", "format=duration", "-of", "json", file.toString());
            if (!probe.ok()) {
                return 0;
            }
            return mapper.readTree(probe.output()).path("format").path("duration").asDouble(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 转码产物固定 24 kHz / 16 bit / 单声道 = 48000 字节/秒 */
    private static double estimateSeconds(java.nio.file.Path wav) {
        try {
            long bytes = java.nio.file.Files.size(wav);
            return bytes <= 44 ? 0 : Math.round((bytes - 44) / 48000.0 * 10) / 10.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double firstMatch(Pattern p, String text, double fallback) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    private static int cleanLength(String text) {
        return text.replaceAll("[\\s\\p{Punct}，。！？、；：\"\"''（）《》…—]", "").length();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
