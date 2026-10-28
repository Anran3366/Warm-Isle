package com.qqbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * 语音识别：小米 MiMo-V2.5-ASR（OpenAI 兼容接口，input_audio 传 base64）。
 * 用于听懂对方发来的语音消息。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);
    private static final String DEFAULT_BASE = "https://api.xiaomimimo.com";

    private final ObjectMapper mapper;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AsrService(ObjectMapper mapper, Environment env) {
        this.mapper = mapper;
        this.env = env;
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
        return prop("MIMO_ASR_MODEL", "mimo-v2.5-asr");
    }

    /** @param audio mp3 字节；识别失败返回空字符串 */
    public String transcribe(byte[] audio) {
        if (!configured() || audio == null || audio.length == 0) {
            return "";
        }
        if (audio.length > 9 * 1024 * 1024) {
            log.warn("语音文件过大，跳过识别: {} bytes", audio.length);
            return "";
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(audio);
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model());
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            ObjectNode part = content.addObject();
            part.put("type", "input_audio");
            part.putObject("input_audio").put("data", "data:audio/mpeg;base64," + base64);

            ObjectNode options = body.putObject("asr_options");
            options.put("language", "zh");

            String url = prop("MIMO_BASE_URL", DEFAULT_BASE).replaceAll("/+$", "") + "/v1/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + env.getProperty("MIMO_API_KEY"))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("语音识别失败 HTTP {}: {}", response.statusCode(), abbreviate(response.body()));
                return "";
            }
            JsonNode node = mapper.readTree(response.body());
            String text = node.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                text = node.path("text").asText("");
            }
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("语音识别异常: {}", e.getMessage());
            return "";
        }
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
