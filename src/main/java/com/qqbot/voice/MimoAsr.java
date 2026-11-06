package com.qqbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * MiMo ASR：/v1/chat/completions + input_audio，用来校验上传样本里到底有没有清晰人声。
 * 失败一律返回 null（质检降级为“无法验证”，绝不因为 ASR 挂了就拒绝用户上传）。
 */
@Component
public class MimoAsr {

    private static final Logger log = LoggerFactory.getLogger(MimoAsr.class);
    private static final String MODEL = "mimo-v2.5-asr";
    private static final long MAX_BASE64 = 9L * 1024 * 1024;

    private final Environment env;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MimoAsr(Environment env, ObjectMapper mapper) {
        this.env = env;
        this.mapper = mapper;
    }

    /** @return 识别文本；不可用或失败返回 null */
    public String transcribe(Path wav) {
        String key = env.getProperty("MIMO_API_KEY");
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(wav);
            if (bytes.length * 4L / 3L > MAX_BASE64) {
                return null;
            }
            String base = env.getProperty("MIMO_BASE_URL", "https://api.xiaomimimo.com")
                    .replaceAll("/+$", "");
            String dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes);

            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            ObjectNode part = content.addObject();
            part.put("type", "input_audio");
            part.putObject("input_audio").put("data", dataUrl);
            body.putObject("asr_options").put("language", "auto");

            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("ASR 返回 {}: {}", response.statusCode(), truncate(response.body()));
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String text = contentNode.isTextual() ? contentNode.asText() : "";
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("ASR 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
