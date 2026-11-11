package com.qqbot.api;

import com.qqbot.bot.BotAccountRunner;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MemoryStrength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官网用的只读统计：只暴露聚合数量，不含任何记忆内容、昵称或 QQ 号。
 *
 * <p>多账号时会加总（同一个人在两个号上会被算两次，官网展示用，不需要精确去重）。
 * 结果缓存 60 秒，页面每次刷新不会打到数据库。
 */
@Service
public class PublicStatsService {

    private static final Logger log = LoggerFactory.getLogger(PublicStatsService.class);
    private static final long TTL_MS = 60_000;

    private final BotAccountRunner runner;
    private final MemoryStore store;

    private volatile Map<String, Object> cache;
    private volatile long cachedAt;

    public PublicStatsService(BotAccountRunner runner, MemoryStore store) {
        this.runner = runner;
        this.store = store;
    }

    public Map<String, Object> stats() {
        long now = System.currentTimeMillis();
        Map<String, Object> hit = cache;
        if (hit != null && now - cachedAt < TTL_MS) {
            return hit;
        }
        Map<String, Object> body = collect();
        cache = body;
        cachedAt = now;
        return body;
    }

    private Map<String, Object> collect() {
        int[] active = {0};
        int[] all = {0};
        int[] fading = {0};
        int[] pinned = {0};
        int[] users = {0};
        Map<String, Integer> byType = new ConcurrentHashMap<>();
        Map<String, Integer> byTypeAll = new ConcurrentHashMap<>();

        try {
            runner.forEach(botId -> {
                // 含已归档/已过期：星图要把「形成过的每一条」都画出来，只是老的会变暗
                List<Memory> list = store.allUsers(true);
                all[0] += list.size();
                users[0] += (int) list.stream().map(Memory::qqId).distinct().count();
                LocalDateTime now = LocalDateTime.now();
                for (Memory m : list) {
                    String type = m.type() == null ? "profile" : m.type();
                    byTypeAll.merge(type, 1, Integer::sum);
                    if (!"active".equals(m.status())) {
                        continue;
                    }
                    active[0]++;
                    byType.merge(type, 1, Integer::sum);
                    if (m.pinned()) {
                        pinned[0]++;
                        continue;
                    }
                    if (MemoryStrength.fading(MemoryStrength.effective(m, now))) {
                        fading[0]++;
                    }
                }
            });
        } catch (Exception e) {
            log.warn("统计公开记忆数量失败: {}", e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", active[0]);          // 还记着的
        body.put("allTotal", all[0]);          // 形成过的（含已忘记）
        body.put("archived", Math.max(0, all[0] - active[0]));
        body.put("users", users[0]);
        body.put("fading", fading[0]);
        body.put("pinned", pinned[0]);
        body.put("byType", new LinkedHashMap<>(byType));
        body.put("byTypeAll", new LinkedHashMap<>(byTypeAll));
        body.put("updatedAt", System.currentTimeMillis());
        return body;
    }
}
