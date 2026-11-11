package com.qqbot.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 新消息推送：后台聊天记录页用 SSE 订阅，消息一落库就推给浏览器。
 */
@Component
public class MessageEventBus {

    private static final Logger log = LoggerFactory.getLogger(MessageEventBus.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> clients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public MessageEventBus(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(long botId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        clients.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.info("SSE 订阅 botId={} 当前连接数={}", botId, clients.get(botId).size());
        Runnable remove = () -> {
            List<SseEmitter> list = clients.get(botId);
            if (list != null) {
                list.remove(emitter);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{\"ok\":true}"));
        } catch (Exception ignored) {
            // 连接立刻断开时忽略
        }
        return emitter;
    }

    public void publish(long botId, Map<String, Object> payload) {
        List<SseEmitter> list = clients.get(botId);
        if (list == null || list.isEmpty()) {
            log.info("SSE 推送跳过：botId={} 没有订阅者", botId);
            return;
        }
        log.info("SSE 推送 botId={} 连接数={} qqId={}", botId, list.size(), payload.get("qqId"));
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("message").data(json));
                }
            } catch (Exception e) {
                list.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已断开
                }
            }
        }
        if (list.isEmpty()) {
            log.debug("SSE 客户端已全部断开 botId={}", botId);
        }
    }
}
