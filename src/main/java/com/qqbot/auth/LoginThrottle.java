package com.qqbot.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台登录/扫码授权失败节流：同一 IP 5 次失败锁 15 分钟，另加全局 30 次兜底。
 * 单实例内存实现；重启清零，但足以挡住在线爆破。
 */
@Component
public class LoginThrottle {

    private static final int MAX_PER_IP = 5;
    private static final int MAX_GLOBAL = 30;
    private static final long WINDOW_MS = 15 * 60 * 1000L;

    private record Attempt(int fails, long resetAt) {
    }

    private final Map<String, Attempt> byIp = new ConcurrentHashMap<>();
    private final Deque<Long> globalFails = new ArrayDeque<>();

    /** @return 还要等多少秒；0 表示可以尝试 */
    public synchronized long retryAfterSeconds(String ip) {
        long now = System.currentTimeMillis();
        Attempt a = byIp.get(ip);
        if (a != null && now >= a.resetAt()) {
            byIp.remove(ip);
            a = null;
        }
        pruneGlobal(now);
        if (a != null && a.fails() >= MAX_PER_IP) {
            return Math.max(1, (a.resetAt() - now) / 1000);
        }
        if (globalFails.size() >= MAX_GLOBAL) {
            long oldest = globalFails.peekFirst();
            return Math.max(1, (oldest + WINDOW_MS - now) / 1000);
        }
        return 0;
    }

    public synchronized void failure(String ip) {
        long now = System.currentTimeMillis();
        pruneGlobal(now);
        Attempt old = byIp.get(ip);
        int fails = (old == null || now >= old.resetAt()) ? 1 : old.fails() + 1;
        byIp.put(ip, new Attempt(fails, now + WINDOW_MS));
        globalFails.addLast(now);
    }

    public synchronized void success(String ip) {
        byIp.remove(ip);
    }

    private void pruneGlobal(long now) {
        while (!globalFails.isEmpty() && now - globalFails.peekFirst() > WINDOW_MS) {
            globalFails.pollFirst();
        }
    }
}
