package com.qqbot.auth;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SharedSettings;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首次部署初始化：没配密码时，前台跳到这里设置管理员密码和 DeepSeek。
 *
 * <p>只在 {@link AuthService#configured()} 为 false 时可用；设置完成后立即失效。
 * 如果部署方在 .env 里设置了 SETUP_TOKEN，页面必须带同一个 token，避免公网被人抢先认领。
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final AuthService auth;
    private final SharedSettings settings;
    private final BotProperties props;

    @Value("${SETUP_TOKEN:}")
    private String setupToken;

    public SetupController(AuthService auth, SharedSettings settings, BotProperties props) {
        this.auth = auth;
        this.settings = settings;
        this.props = props;
    }

    public record SetupRequest(String password, String confirmPassword,
                               String deepseekApiKey, String deepseekBaseUrl,
                               String deepseekModel, String setupToken) {
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required", !auth.configured());
        out.put("hasPassword", auth.configured());
        out.put("llmConfigured", !effectiveLlmKey().isBlank());
        out.put("model", effective("llm.model", props.getLlm().getModel()));
        out.put("baseUrl", effective("llm.baseUrl", props.getLlm().getBaseUrl()));
        out.put("tokenRequired", setupToken != null && !setupToken.isBlank());
        return out;
    }

    @PostMapping
    public ResponseEntity<?> setup(@RequestBody(required = false) SetupRequest body,
                                   HttpServletResponse response) {
        if (auth.configured()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "ALREADY_SETUP", "message", "已经初始化过了，请直接登录"));
        }
        if (setupToken != null && !setupToken.isBlank()
                && (body == null || !setupToken.equals(body.setupToken()))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "BAD_SETUP_TOKEN", "message", "部署令牌不正确"));
        }
        if (body == null || body.password() == null || body.password().length() < 8) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_PASSWORD", "message", "密码至少 8 位"));
        }
        if (!body.password().equals(body.confirmPassword())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "PASSWORD_MISMATCH", "message", "两次输入的密码不一致"));
        }
        String baseUrl = blank(body.deepseekBaseUrl()) ? "" : body.deepseekBaseUrl().trim();
        if (!baseUrl.isEmpty()) {
            try {
                URI uri = URI.create(baseUrl);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null) {
                    throw new IllegalArgumentException();
                }
            } catch (Exception e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "BAD_BASE_URL", "message", "DeepSeek 地址必须是 http(s) URL"));
            }
        }

        auth.changePassword(body.password());
        if (!blank(body.deepseekApiKey())) {
            settings.set("llm.apiKey", body.deepseekApiKey().trim());
        }
        if (!baseUrl.isEmpty()) {
            settings.set("llm.baseUrl", baseUrl);
        }
        if (!blank(body.deepseekModel())) {
            settings.set("llm.model", body.deepseekModel().trim());
        }

        String token = auth.issueToken();
        long ttlSeconds = Duration.ofDays(props.getAdmin().getSessionTtlDays()).toSeconds();
        ResponseCookie cookie = ResponseCookie.from(AuthController.COOKIE_NAME, token)
                .httpOnly(true).path("/").maxAge(Duration.ofSeconds(ttlSeconds)).sameSite("Lax").build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true, "redirect", "/dashboard"));
    }

    private String effective(String key, String fallback) {
        String v = settings.get(key, null);
        return v == null || v.isBlank() ? fallback : v;
    }

    private String effectiveLlmKey() {
        return effective("llm.apiKey", props.getLlm().getApiKey());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
