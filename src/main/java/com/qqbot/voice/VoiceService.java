package com.qqbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqbot.memory.SharedSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * MiMo TTS：走 OpenAI 兼容的 /v1/chat/completions，modalities=["audio"]，
 * 待合成文本放在 assistant 角色的消息里，返回 base64 WAV。
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private static final String DEFAULT_BASE = "https://api.xiaomimimo.com";
    private static final String DEFAULT_MODEL = "mimo-v2.5-tts";
    private static final String DEFAULT_VOICE = "茉莉";

    private final SharedSettings settings;
    private final ObjectMapper mapper;
    private final Environment env;
    private final VoiceCloneService clone;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public VoiceService(SharedSettings settings, ObjectMapper mapper, Environment env,
                        VoiceCloneService clone) {
        this.settings = settings;
        this.mapper = mapper;
        this.env = env;
        this.clone = clone;
    }

    private String prop(String key, String fallback) {
        String value = env.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean configured() {
        String key = env.getProperty("MIMO_API_KEY");
        return key != null && !key.isBlank();
    }

    public String model() {
        return prop("MIMO_TTS_MODEL", DEFAULT_MODEL);
    }

    public String baseUrl() {
        return prop("MIMO_BASE_URL", DEFAULT_BASE).replaceAll("/+$", "");
    }

    public String voice() {
        return settings.get("voice.name", DEFAULT_VOICE);
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.get("voice.enabled", "true"));
    }

    public double speed() {
        try {
            return Double.parseDouble(settings.get("voice.speed", "1.0"));
        } catch (Exception e) {
            return 1.0;
        }
    }

    /** 把合成结果存到 data/voice，返回文件名 */
    public String saveAudio(byte[] wav) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("data/voice").toAbsolutePath();
        java.nio.file.Files.createDirectories(dir);
        String name = java.util.UUID.randomUUID().toString().replace("-", "") + ".wav";
        java.nio.file.Files.write(dir.resolve(name), wav);
        return name;
    }

    public void update(Map<String, Object> body) {
        if (body.get("enabled") instanceof Boolean b) {
            settings.set("voice.enabled", String.valueOf(b));
        }
        if (body.get("voice") instanceof String s && !s.isBlank()) {
            settings.set("voice.name", s);
        }
    }

    /** 合成语音，返回 WAV 字节；失败抛异常 */
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, clone.enabled() ? clone.refDataUrl() : null);
    }

    /** 带语气指令的合成（自动使用当前克隆音色，若有） */
    public byte[] synthesizeStyled(String text, String style) throws Exception {
        return synthesize(text, clone.enabled() ? clone.refDataUrl() : null, style);
    }

    /** 指定参考音色合成（上传后立刻试听时用，不依赖"当前音色"是否已切换） */
    public byte[] synthesize(String text, String cloneRef) throws Exception {
        return synthesize(text, cloneRef, null);
    }

    /**
     * @param style 语气指令（user 消息）。MiMo TTS 是对话式接口，user 消息会被当作
     *              语气/场景上下文，能明显改善“念稿感”。传 null 则沿用旧行为。
     */
    public byte[] synthesize(String text, String cloneRef, String style) throws Exception {
        String key = env.getProperty("MIMO_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TTS_NOT_CONFIGURED");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cloneRef != null ? "mimo-v2.5-tts-voiceclone" : model());
        ArrayNode messages = body.putArray("messages");
        if (style != null && !style.isBlank()) {
            ObjectNode hint = messages.addObject();
            hint.put("role", "user");
            hint.put("content", style);
        }
        ObjectNode msg = messages.addObject();
        msg.put("role", "assistant");
        msg.put("content", cleanForTts(text));
        body.putArray("modalities").add("audio");
        ObjectNode audio = body.putObject("audio").put("format", "wav");
        audio.put("voice", cloneRef != null ? cloneRef : voice());

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("TTS 返回 " + response.statusCode() + ": "
                    + truncate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        String data = root.path("choices").path(0).path("message").path("audio").path("data").asText("");
        if (data.isBlank()) {
            throw new IllegalStateException("TTS 未返回音频数据");
        }
        byte[] wav = Base64.getDecoder().decode(data);
        log.info("TTS 合成完成 {} 字符 -> {} 字节", text.length(), wav.length);
        return wav;
    }

    /** 去掉播音腔和不适合朗读的符号 */
    private String cleanForTts(String text) {
        if (text == null) {
            return "";
        }
        String t = text
                .replaceAll("[（(][^）)]{0,20}[）)]", "")   // 括号里的动作/备注
                .replaceAll("[*#`_>]+", "")                  // markdown
                .replaceAll("…{2,}", "…")
                .replaceAll("[。！？!?]{2,}", "。")
                .replaceAll("~+", "～")
                .replaceAll("\\s+", " ")
                .trim();
        return t.isEmpty() ? text : t;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
