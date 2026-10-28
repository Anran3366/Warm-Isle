package com.qqbot.image;

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
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 本地图片预筛客户端：调用 guard 服务（127.0.0.1:8081）判断图片是否敏感。
 * 服务不可用时按 fail-closed 处理（视为敏感，不送云端）。
 */
@Component
public class ImageGuardClient {

    private static final Logger log = LoggerFactory.getLogger(ImageGuardClient.class);

    public record Decision(boolean sensitive, String reason, double score) {
        public static Decision clean() {
            return new Decision(false, "clean", 0);
        }

        public static Decision block(String reason) {
            return new Decision(true, reason, 1.0);
        }
    }

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            // guard 是 uvicorn，不支持 h2c 升级；Java 默认 HTTP/2 会先发 upgrade 请求，
            // 导致 body 被丢弃、guard 返回 422。这里强制 HTTP/1.1。
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ImageGuardClient(BotProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public Decision check(byte[] jpeg) {
        if (!props.getImage().isGuardEnabled()) {
            return Decision.clean();
        }
        try {
            String body = mapper.writeValueAsString(
                    Map.of("image_base64", Base64.getEncoder().encodeToString(jpeg)));
            String url = props.getImage().getGuardUrl().replaceAll("/+$", "") + "/check_json";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getImage().getGuardTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("预筛服务返回 {}，按敏感处理", response.statusCode());
                return Decision.block("guard_http_" + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            boolean sensitive = root.path("sensitive").asBoolean(false);
            String reason = root.path("reason").asText("unknown");
            double score = root.path("score").asDouble(0);
            if (sensitive) {
                log.info("本地预筛拦截: reason={} score={}", reason, score);
            }
            return new Decision(sensitive, reason, score);
        } catch (Exception e) {
            log.warn("预筛服务不可用（{}），按敏感处理", e.getMessage());
            return Decision.block("guard_unavailable");
        }
    }
}
