package com.qqbot.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqbot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 视觉模型调用：OpenAI 兼容的 chat/completions，content 里放 image_url。
 * DeepSeek 的 deepseek-chat 目前会把图片请求路由到 deepseek-flash 视觉版本。
 */
@Component
public class VisionClient {

    private static final Logger log = LoggerFactory.getLogger(VisionClient.class);

    /** 模型判定为敏感内容时的标记 */
    public static final String SENSITIVE = "[SENSITIVE]";

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public VisionClient(BotProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * @return 图片描述；判定敏感时返回 {@link #SENSITIVE}；失败返回 null
     */
    public String describe(String base64Jpeg, String mime) {
        String apiKey = props.getLlm().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getImage().getVisionModel());
            body.put("temperature", 0.3);
            body.put("max_tokens", 200);
            body.put("stream", false);

            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", SYSTEM_PROMPT);

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            content.addObject().put("type", "text")
                    .put("text", "请按规则处理这张图片：如果是敏感内容只输出 [SENSITIVE]，否则用一到两句中文客观描述。");
            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            img.putObject("image_url").put("url", "data:" + mime + ";base64," + base64Jpeg);

            String url = props.getLlm().getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("视觉模型返回 {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            log.info("视觉模型完成 tokens={}", root.path("usage").path("total_tokens").asInt());
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            log.warn("调用视觉模型失败: {}", e.getMessage());
            return null;
        }
    }

    private static final String SYSTEM_PROMPT = """
            你在帮一个聊天机器人看懂对方发来的图片。
            规则：
            1. 如果图片包含证件、银行卡、身份证、护照、私密照、裸露、血腥暴力等敏感内容，只输出 [SENSITIVE]，不要做任何描述。
            2. 否则用一到两句中文客观描述图片：主体是什么、在做什么、大致氛围。
            3. 不要评价、不要猜测地点或身份、不要输出多余解释。
            4. 如果是表情包：写出上面的文字，说明它表达的情绪，并指出文字可能是玩梗或谐音（例如“有鱼片”其实在问“有片吗”），不要按字面意思理解。
            """;
}
