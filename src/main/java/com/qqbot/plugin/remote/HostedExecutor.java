package com.qqbot.plugin.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * hosted / local 型插件的 HTTP 调用执行器（设计文档 §10.4 / §10.5）。
 *
 * <p>两种 runtime 走同一份协议，差别只有两处：地址来源、以及回取产物时是否允许私网。
 * <ul>
 *   <li>hosted：地址取 manifest 的 {@code hosted.endpoint}（管理员可覆盖），产物只允许公网 HTTPS；</li>
 *   <li>local：地址取部署者在后台登记的 {@code endpoint}，产物允许私网但拒绝云元数据。</li>
 * </ul>
 *
 * <p>宿主绝对不会发送：QQ 号、昵称、群号、聊天原文、历史消息。上下文里只有语言、时区、
 * 时间（截到分钟）和超时预算。要发原文必须 manifest 声明 {@code accepts.text}。
 */
@Component
public class HostedExecutor {

    private static final Logger log = LoggerFactory.getLogger(HostedExecutor.class);

    /** 插件回了「这事我得慢慢做」，宿主需要转异步轮询 */
    public static class PendingTask extends RuntimeException {
        private final String taskId;
        private final String ackText;
        private final long deadlineMs;

        public PendingTask(String taskId, String ackText, long deadlineMs) {
            super("pending");
            this.taskId = taskId;
            this.ackText = ackText;
            this.deadlineMs = deadlineMs;
        }

        public String taskId() {
            return taskId;
        }

        public String ackText() {
            return ackText;
        }

        public long deadlineMs() {
            return deadlineMs;
        }
    }

    private final PluginProperties props;
    private final RemoteFetch fetch;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HostedExecutor(PluginProperties props, RemoteFetch fetch) {
        this.props = props;
        this.fetch = fetch;
    }

    public PluginOutput execute(InstalledPlugin plugin, String actionName,
                                Map<String, String> params, String invokeId) {
        PluginManifest manifest = plugin.manifest();
        boolean local = manifest.usesLocal();
        String base = checkedBase(plugin);
        long timeout = timeoutOf(manifest, actionName);
        URI target = append(base, "/v1/invoke");
        Map<String, Object> payload = payload(plugin, actionName, params, timeout);
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new DataExecutor.PluginException("INTERNAL", "构造请求失败");
        }

        HttpResponse<String> res;
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofMillis(timeout))
                    .header("Content-Type", "application/json")
                    .header("X-Plugin-Protocol", "1")
                    .header("X-Install-Id", plugin.installId() == null ? "" : plugin.installId())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String auth = manifest.hosted() == null ? null : manifest.hosted().auth();
            if (auth != null && !auth.isBlank() && !local) {
                b.header("Authorization", auth);
            }
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DataExecutor.PluginException("TIMEOUT", "插件调用超时");
        } catch (Exception e) {
            log.debug("插件 {} 调用失败: {}", plugin.pluginId(), e.getMessage());
            throw new DataExecutor.PluginException("UNAVAILABLE",
                    "插件服务连不上：" + shortMsg(e));
        }
        long cost = System.currentTimeMillis() - t0;
        log.debug("插件 {} invoke {} -> {} ({}ms)", plugin.pluginId(), actionName,
                res.statusCode(), cost);

        int code = res.statusCode();
        if (code == 429) {
            throw new DataExecutor.PluginException("RATE_LIMITED", "插件侧限流", "这个太火啦，等会儿再试。");
        }
        if (code >= 500) {
            throw new DataExecutor.PluginException("UNAVAILABLE", "插件服务返回 " + code);
        }
        if (code < 200 || code >= 300) {
            throw new DataExecutor.PluginException("INTERNAL", "插件服务返回 " + code);
        }
        if (res.body() != null && res.body().length() > 5 * 1024 * 1024) {
            throw new DataExecutor.PluginException("OUTPUT_TOO_LARGE", "插件响应超过 5MB");
        }
        return parse(plugin, res.body(), local, invokeId);
    }

    PluginOutput parse(InstalledPlugin plugin, String body, boolean local, String invokeId) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件返回的不是 JSON");
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件返回空响应");
        }
        if (root.path("ok").isBoolean() && !root.path("ok").asBoolean()) {
            JsonNode err = root.path("error");
            String code = text(err, "code", "INTERNAL");
            String message = text(err, "message", "插件执行失败");
            String hint = text(err, "userHint", null);
            throw new DataExecutor.PluginException(normalizeCode(code), message, hint);
        }
        if (root.path("pending").asBoolean(false)) {
            String taskId = text(root, "taskId", null);
            if (taskId == null || taskId.isBlank()) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", "插件说在处理但没给 taskId");
            }
            String ack = text(root, "ackText", "好，等我一下下");
            throw new PendingTask(taskId, ack, System.currentTimeMillis() + 90_000);
        }

        JsonNode outputs = root.path("outputs");
        String caption = text(root, "caption", null);
        Map<String, Object> vars = readVars(root.path("memory").path("vars"));
        if (!outputs.isArray() || outputs.isEmpty()) {
            // 纯文字结果：允许只有 caption
            String fallback = caption == null ? "做完了" : caption;
            return PluginOutput.text(fallback, fallback, vars);
        }
        JsonNode first = outputs.get(0);
        String kind = text(first, "kind", "text");
        String mime = text(first, "mime", null);
        long duration = first.path("durationMs").asLong(0);

        byte[] bytes;
        String dataBase64 = text(first, "dataBase64", null);
        String url = text(first, "url", null);
        if (dataBase64 != null && !dataBase64.isBlank()) {
            long limit = RemoteFetch.limitOf(kind);
            if (dataBase64.length() > limit * 4 / 3 + 16) {
                throw new DataExecutor.PluginException("OUTPUT_TOO_LARGE", "插件内联产物超过上限");
            }
            try {
                bytes = Base64.getDecoder().decode(dataBase64);
            } catch (Exception e) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", "插件内联产物不是合法 base64");
            }
            if (bytes.length > limit) {
                throw new DataExecutor.PluginException("OUTPUT_TOO_LARGE", "插件内联产物超过上限");
            }
            if (!RemoteFetch.looksLike(kind, bytes)) {
                throw new DataExecutor.PluginException("BAD_OUTPUT", "插件返回的媒体格式校验未通过");
            }
        } else if (url != null && !url.isBlank()) {
            bytes = fetch.fetch(url, plugin.manifest().outboundHosts(), local, kind);
        } else {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "插件产物既没有 dataBase64 也没有 url");
        }

        return switch (kind) {
            case "audio" -> PluginOutput.audio(bytes, mime == null ? "audio/mpeg" : mime, duration,
                    caption == null ? defaultAudioCaption(vars) : caption, vars);
            case "image" -> PluginOutput.image(bytes, mime == null ? "image/png" : mime,
                    caption == null ? "（发了一张图）" : caption);
            case "text" -> PluginOutput.text(new String(bytes, StandardCharsets.UTF_8),
                    caption == null ? "做完了" : caption, vars);
            default -> throw new DataExecutor.PluginException("BAD_OUTPUT",
                    "插件返回了不支持的类型：" + kind);
        };
    }

    /** 异步任务轮询用：把一个 task 的最终结果按同一套规则解析 */
    PluginOutput parseTaskPayload(InstalledPlugin plugin, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new DataExecutor.PluginException("BAD_OUTPUT", "任务结果不是 JSON");
        }
        String status = text(root, "status", "done");
        if ("failed".equalsIgnoreCase(status)) {
            JsonNode err = root.path("error");
            throw new DataExecutor.PluginException(normalizeCode(text(err, "code", "INTERNAL")),
                    text(err, "message", "任务失败"), text(err, "userHint", null));
        }
        return parse(plugin, body, plugin.manifest().usesLocal(), null);
    }

    Map<String, Object> payload(InstalledPlugin plugin, String actionName,
                                Map<String, String> params, long timeoutMs) {
        PluginManifest manifest = plugin.manifest();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("locale", "zh-CN");
        ctx.put("timezone", "Asia/Shanghai");
        ctx.put("time", OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                .withSecond(0).withNano(0).toString());
        ctx.put("timeoutMs", timeoutMs);
        ctx.put("output", Map.of(
                "kinds", manifest.returnsAudio() ? java.util.List.of("audio")
                        : manifest.returnsImage() ? java.util.List.of("image")
                        : java.util.List.of("text"),
                "maxBytes", RemoteFetch.MAX_AUDIO));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", 1);
        body.put("invokeId", UUID.randomUUID().toString());
        body.put("pluginId", plugin.pluginId());
        body.put("pluginVersion", plugin.version());
        body.put("action", actionName);
        body.put("intentId", manifest.intent() == null ? null : manifest.intent().id());
        body.put("params", params == null ? Map.of() : params);
        body.put("context", ctx);
        return body;
    }

    /** 校验后的入口地址：hosted 必须是公网 HTTPS:443，local 允许私网但拒绝元数据。 */
    public String checkedBase(InstalledPlugin plugin) {
        String base = baseUrl(plugin);
        boolean local = plugin.manifest().usesLocal();
        if (base == null || base.isBlank()) {
            throw new DataExecutor.PluginException("UNAVAILABLE",
                    local ? "这个插件还没填本地地址，请先在插件页配置" : "插件没有声明服务地址");
        }
        String reason = RemoteFetch.checkEndpoint(base, local, plugin.manifest().outboundHosts());
        if (reason != null) {
            throw new DataExecutor.PluginException("UNAVAILABLE", reason);
        }
        return base;
    }

    String baseUrl(InstalledPlugin plugin) {
        if (plugin.endpoint() != null && !plugin.endpoint().isBlank()) {
            return plugin.endpoint().trim();
        }
        PluginManifest.Hosted hosted = plugin.manifest().hosted();
        if (hosted != null && hosted.endpoint() != null && !hosted.endpoint().isBlank()) {
            return hosted.endpoint().trim();
        }
        return null;
    }

    long timeoutOf(PluginManifest manifest, String actionName) {
        long hard = props.getMaxSyncMs();
        PluginManifest.Action action = manifest.action(actionName);
        if (action != null && action.timeoutMs() != null) {
            return Math.max(1000, Math.min(hard, action.timeoutMs()));
        }
        return hard;
    }

    static URI append(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(b + path);
    }

    private Map<String, Object> readVars(JsonNode vars) {
        if (!vars.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        vars.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            out.put(e.getKey(), v.isNumber() ? v.asLong() : v.asText());
        });
        return out;
    }

    private String defaultAudioCaption(Map<String, Object> vars) {
        Object song = vars.get("song");
        return song == null ? "（唱了一段）" : "（唱了《" + song + "》）";
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "INTERNAL";
        }
        String up = code.trim().toUpperCase(Locale.ROOT);
        return switch (up) {
            case "BAD_PARAMS", "NOT_FOUND", "UNAVAILABLE", "TIMEOUT",
                 "RATE_LIMITED", "INTERNAL", "UNSUPPORTED", "BAD_OUTPUT",
                 "OUTPUT_TOO_LARGE" -> up;
            default -> "INTERNAL";
        };
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 80 ? m.substring(0, 80) : m;
    }
}
