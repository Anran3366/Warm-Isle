package com.qqbot.bot;

/**
 * 当前机器人账号上下文。
 * HTTP 请求由过滤器按 X-Bot-Id 头设置；WebSocket 事件由各 OneBotClient 设置；
 * 异步任务需要在提交前捕获 botId，在线程内重新 set。
 */
public final class BotContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private BotContext() {
    }

    public static void set(long botId) {
        CURRENT.set(botId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static long require() {
        Long v = CURRENT.get();
        if (v == null) {
            throw new IllegalStateException("缺少机器人账号上下文（X-Bot-Id）");
        }
        return v;
    }

    /** 在指定账号上下文里执行，结束后恢复原值 */
    public static <T> T call(long botId, java.util.function.Supplier<T> action) {
        Long previous = CURRENT.get();
        CURRENT.set(botId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(long botId, Runnable action) {
        call(botId, () -> {
            action.run();
            return null;
        });
    }
}
