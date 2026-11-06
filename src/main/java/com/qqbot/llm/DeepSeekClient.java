package com.qqbot.llm;

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
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Chat Completions 客户端（非流式）。
 * 只依赖 JDK HttpClient，避免额外 SDK。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final com.qqbot.budget.BudgetGuard budget;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DeepSeekClient(BotProperties props, ObjectMapper mapper, com.qqbot.budget.BudgetGuard budget) {
        this.props = props;
        this.mapper = mapper;
        this.budget = budget;
    }

    /**
     * 发起一次对话补全。
     *
     * @throws IllegalStateException 未配置 API Key
     * @throws RuntimeException      请求或解析失败
     */
    public LlmResult chat(List<Map<String, String>> messages) {
        return chat(messages, props.getLlm().getTemperature(), props.getLlm().getMaxTokens());
    }

    /** 指定温度与输出上限（抽取/摘要用低温） */
    public LlmResult chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
        String apiKey = props.getLlm().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 DeepSeek API Key（.env 里的 DEEPSEEK_API_KEY）");
        }
        if (budget.exceeded()) {
            throw new BudgetExceededException("今日预算用尽");
        }

        String model = props.getLlm().getModel();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);
            ArrayNode arr = body.putArray("messages");
            for (Map<String, String> m : messages) {
                ObjectNode item = arr.addObject();
                item.put("role", m.get("role"));
                item.put("content", m.get("content"));
            }

            String url = props.getLlm().getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("DeepSeek 返回 " + response.statusCode() + ": "
                        + truncate(response.body()));
            }

            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt();
            int completionTokens = usage.path("completion_tokens").asInt();
            log.info("LLM 完成 model={} tokens: prompt={} completion={}", model, promptTokens, completionTokens);
            return new LlmResult(content.trim(), promptTokens, completionTokens);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("调用 DeepSeek 被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("调用 DeepSeek 失败: " + e.getMessage(), e);
        }
    }

    /** 按配置单价估算本次花费（元） */
    public double cost(int promptTokens, int completionTokens) {
        return promptTokens / 1_000_000.0 * props.getLlm().getPriceIn()
                + completionTokens / 1_000_000.0 * props.getLlm().getPriceOut();
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
