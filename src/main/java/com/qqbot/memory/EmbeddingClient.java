package com.qqbot.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文本向量客户端：调用 imageguard 的 {@code POST /embed}（bge-small-zh-v1.5，512 维，已归一化）。
 *
 * <p>检索是增强项：服务不可用、超时、返回异常时一律返回空列表，调用方自动退回关键词检索。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int MAX_BATCH = 32;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String url;

    public EmbeddingClient(BotProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        String base = props.getImage().getGuardUrl();
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:8081";
        }
        this.url = base.replaceAll("/+$", "") + "/embed";
        // uvicorn 对 h2c 升级支持不完整，强制 HTTP/1.1
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /** 批量向量化；失败返回空列表（调用方按无向量处理） */
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return out;
        }
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            int to = Math.min(texts.size(), from + MAX_BATCH);
            out.addAll(embedBatch(texts.subList(from, to)));
            if (out.size() < to) {
                // 中途失败，已拿到的部分也作废，避免下标错位
                return new ArrayList<>();
            }
        }
        return out;
    }

    /** 单条向量化；失败返回 null */
    public float[] embedOne(String text) {
        List<float[]> v = embed(List.of(text == null ? "" : text));
        return v.isEmpty() ? null : v.get(0);
    }

    private List<float[]> embedBatch(List<String> batch) {
        List<float[]> out = new ArrayList<>();
        try {
            String body = mapper.writeValueAsString(Map.of("texts", batch));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("向量服务返回 {}: {}", resp.statusCode(), abbreviate(resp.body()));
                return out;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode vectors = root.path("vectors");
            if (!vectors.isArray()) {
                return out;
            }
            for (JsonNode arr : vectors) {
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                out.add(v);
            }
        } catch (Exception e) {
            log.debug("向量服务不可用: {}", e.getMessage());
        }
        return out;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120);
    }
}
