package com.qqbot.bot;

import com.qqbot.memory.GlobalDb;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前查看的机器人账号由 X-Bot-Id 指定。
 * 只有一个账号时允许省略；多账号必须显式传，否则返回 400，不再静默落到某个账号。
 */
@Component
public class BotContextFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Bot-Id";

    private final GlobalDb globalDb;

    public BotContextFilter(GlobalDb globalDb) {
        this.globalDb = globalDb;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        Long id = null;
        if (raw != null && !raw.isBlank()) {
            try {
                id = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                // 非法值按缺失处理
            }
        }
        if (id != null && id > 0 && !exists(id)) {
            id = null;
        }

        String path = request.getRequestURI();
        // 只对接口生效：静态资源与前端路由（/、/assets/**、/index.html）不需要账号上下文，
        // 否则多账号时连后台首页都会被 400 挡住
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean publicPath = path.startsWith("/api/auth/") || path.equals("/api/ping")
                || path.equals("/api/health");
        // 二维码是 <img src> 请求，浏览器没法带 X-Bot-Id；账号 id 已在路径里，不需要上下文
        boolean noContextNeeded = path.matches("/api/account/\\d+/qrcode");

        if (id == null && !publicPath && !noContextNeeded) {
            List<Long> ids = accountIds();
            if (ids.size() == 1) {
                id = ids.get(0);
            } else if (ids.isEmpty()) {
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"NO_ACCOUNT\",\"message\":\"还没有任何机器人账号\"}");
                return;
            } else {
                response.setStatus(400);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"BOT_ID_REQUIRED\",\"message\":\"请指定 X-Bot-Id\"}");
                return;
            }
        }

        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        try {
            if (id != null && id > 0) {
                BotContext.set(id);
            }
            chain.doFilter(request, response);
        } finally {
            BotContext.clear();
        }
    }

    private boolean exists(long id) {
        try (var c = globalDb.open();
             var ps = c.prepareStatement("SELECT 1 FROM bot_accounts WHERE id=?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private List<Long> accountIds() {
        List<Long> list = new ArrayList<>();
        try (var c = globalDb.open();
             var ps = c.prepareStatement("SELECT id FROM bot_accounts ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getLong(1));
            }
        } catch (Exception ignored) {
            // 返回空
        }
        return list;
    }
}
