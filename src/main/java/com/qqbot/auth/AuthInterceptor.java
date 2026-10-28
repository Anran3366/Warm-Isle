package com.qqbot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 除登录与探活外的 /api/** 都需要有效会话。
 * 未登录返回 401，body 与契约一致：{"code":"UNAUTHORIZED","message":"请先登录"}。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;
    private final ObjectMapper mapper;

    public AuthInterceptor(AuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/qr")
                || path.equals("/api/ping") || path.equals("/api/health")) {
            return true;
        }
        if (!auth.configured()) {
            // 没配密码时不锁死，避免自己把自己关在门外
            return true;
        }
        if (auth.valid(AuthController.readToken(request))) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(
                Map.of("code", "UNAUTHORIZED", "message", "请先登录")));
        return false;
    }
}
