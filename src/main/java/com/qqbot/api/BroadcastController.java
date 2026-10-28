package com.qqbot.api;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次性通知：给好友逐个发私聊（后台手动触发） */
@RestController
@RequestMapping("/api/admin")
public class BroadcastController {

    private final OneBotRegistry registry;

    public BroadcastController(OneBotRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@RequestBody(required = false) Map<String, Object> body) {
        String text = body == null || body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (text.isBlank()) {
            return Map.of("ok", false, "message", "内容不能为空");
        }
        OneBotClient client = registry.current();
        if (client == null || !client.isConnected()) {
            return Map.of("ok", false, "message", "机器人当前离线");
        }

        List<Long> targets = new ArrayList<>();
        if (body.get("qqIds") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    targets.add(n.longValue());
                }
            }
        }
        if (targets.isEmpty()) {
            for (Map<String, Object> f : client.getFriendList()) {
                Object id = f.get("qqId");
                if (id instanceof Number n && n.longValue() > 0) {
                    targets.add(n.longValue());
                }
            }
        }

        int ok = 0;
        int failed = 0;
        for (Long qq : targets) {
            try {
                client.sendPrivate(qq, text);
                ok++;
                Thread.sleep(300);
            } catch (Exception e) {
                failed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", targets.size());
        out.put("sent", ok);
        out.put("failed", failed);
        return out;
    }
}
