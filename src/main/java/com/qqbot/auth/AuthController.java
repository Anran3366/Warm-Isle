package com.qqbot.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** 管理后台登录 / 退出 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String COOKIE_NAME = "qqbot_session";

    private final AuthService auth;
    private final QrLoginService qr;
    private final com.qqbot.config.BotProperties props;
    private final org.springframework.core.env.Environment env;

    public AuthController(AuthService auth, QrLoginService qr,
                          com.qqbot.config.BotProperties props,
                          org.springframework.core.env.Environment env) {
        this.auth = auth;
        this.qr = qr;
        this.props = props;
        this.env = env;
    }

    public record LoginRequest(String password) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    /** 修改后台授权码（需要已登录） */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody PasswordChangeRequest body,
                                            HttpServletResponse response) {
        if (body == null || body.newPassword() == null || body.newPassword().length() < 6) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_PASSWORD", "message", "新密码至少 6 位"));
        }
        if (!auth.checkPassword(body.currentPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "AUTH_FAILED", "message", "当前密码不正确"));
        }
        try {
            auth.changePassword(body.newPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_PASSWORD", "message", e.getMessage()));
        }
        // 旧会话已全部失效，给当前浏览器换一个新会话
        String token = auth.issueToken();
        long ttlSeconds = Duration.ofDays(props.getAdmin().getSessionTtlDays()).toSeconds();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).path("/").maxAge(Duration.ofSeconds(ttlSeconds)).sameSite("Lax").build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        if (!auth.configured()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "AUTH_NOT_CONFIGURED",
                            "message", "后台密码未配置：请在 .env 里设置 ADMIN_PASSWORD 并重启服务"));
        }
        String token = auth.login(body == null ? null : body.password());
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "AUTH_FAILED", "message", "密码错误"));
        }
        long ttlSeconds = Duration.ofDays(props.getAdmin().getSessionTtlDays()).toSeconds();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofSeconds(ttlSeconds))
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---------- 扫码登录 ----------

    /** PC 端申请二维码：返回 token 与手机可打开的地址 */
    @PostMapping("/qr/new")
    public ResponseEntity<?> qrNew(HttpServletRequest request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>(qr.create());
            String publicBase = env.getProperty("PUBLIC_BASE_URL", "");
            if (publicBase.isBlank()) {
                String scheme = request.getScheme();
                int port = request.getServerPort();
                String host = request.getServerName();
                boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
                publicBase = scheme + "://" + host + (defaultPort ? "" : ":" + port);
            }
            body.put("url", publicBase.replaceAll("/+$", "") + "/#/m/" + body.get("token"));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "QR_FAILED", "message", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/qr/{token}")
    public ResponseEntity<?> qrStatus(@PathVariable String token) {
        try {
            return ResponseEntity.ok(Map.of("status", qr.status(token)));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "expired"));
        }
    }

    /** 手机端提交授权码 */
    @PostMapping("/qr/{token}/approve")
    public ResponseEntity<?> qrApprove(@PathVariable String token, @RequestBody LoginRequest body) {
        try {
            if (qr.approve(token, body == null ? null : body.password())) {
                return ResponseEntity.ok(Map.of("ok", true));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "AUTH_FAILED", "message", "授权码错误"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "QR_FAILED", "message", String.valueOf(e.getMessage())));
        }
    }

    /** PC 端确认后领取会话 cookie */
    @PostMapping("/qr/{token}/claim")
    public ResponseEntity<?> qrClaim(@PathVariable String token, HttpServletResponse response) {
        try {
            String session = qr.claim(token);
            if (session == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "NOT_APPROVED", "message", "尚未确认或已被领取"));
            }
            long ttlSeconds = Duration.ofDays(props.getAdmin().getSessionTtlDays()).toSeconds();
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session)
                    .httpOnly(true).path("/").maxAge(Duration.ofSeconds(ttlSeconds)).sameSite("Lax").build();
            response.addHeader("Set-Cookie", cookie.toString());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "QR_FAILED", "message", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = readToken(request);
        auth.logout(token);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    static String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
