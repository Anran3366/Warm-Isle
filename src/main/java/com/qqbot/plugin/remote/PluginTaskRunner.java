package com.qqbot.plugin.remote;

import com.qqbot.bot.BotContext;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 长任务轮询（设计文档 §10.4 异步响应）。
 *
 * <p>插件先回 {@code {"pending":true,"taskId":"t_1","ackText":"好，等我一下"}}，宿主立刻发
 * {@code ackText}（标记 {@code kind='progress'}，不进 prompt 历史），然后每 2 秒
 * {@code GET /v1/tasks/:taskId}，上限 90 秒。超时或失败只发一条提示，不刷屏。
 *
 * <p>轮询跑在独立的单线程调度器上，不占消息处理线程；同一插件的并发任务上限 8，
 * 超过直接拒绝（宁可少回一句，也不要把宿主线程池打满）。
 */
@Component
public class PluginTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginTaskRunner.class);
    private static final long POLL_INTERVAL_MS = 2_000;
    private static final int MAX_CONCURRENT = 8;

    private final HostedExecutor executor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "plugin-task-runner");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> running = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public PluginTaskRunner(HostedExecutor executor) {
        this.executor = executor;
    }

    /**
     * @param botId  发起任务的账号；回调在调度线程上执行，必须在这里补回 BotContext，
     *               否则 logRun / 记忆 / 配额会写到默认账号（设计文档 P0-5）
     * @param onDone 成功回调
     * @param onFail 失败回调，参数是 (错误码, 用户可见提示)，提示可能为 null
     */
    public void schedule(long botId, InstalledPlugin plugin, String taskId, long deadlineMs,
                         Consumer<PluginOutput> onDone, BiConsumer<String, String> onFail) {
        String key = botId + "#" + plugin.pluginId() + "#" + taskId;
        if (running.size() >= MAX_CONCURRENT) {
            fail(botId, onFail, "UNAVAILABLE", "现在排队有点多，等会儿再试？");
            return;
        }
        if (running.containsKey(key)) {
            fail(botId, onFail, "UNAVAILABLE", "这个任务已经在处理了");
            return;
        }
        String base;
        try {
            base = executor.checkedBase(plugin);
        } catch (DataExecutor.PluginException e) {
            fail(botId, onFail, e.code(), e.userHint());
            return;
        }
        URI target = HostedExecutor.append(base, "/v1/tasks/" + taskId);
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() > deadlineMs) {
                    finish(key);
                    fail(botId, onFail, "TIMEOUT", "这个做得有点久，先不等啦");
                    return;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<String> res = http.send(req,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int code = res.statusCode();
                    if (code == 404) {
                        finish(key);
                        fail(botId, onFail, "NOT_FOUND", null);
                        return;
                    }
                    if (code == 429) {
                        finish(key);
                        fail(botId, onFail, "RATE_LIMITED", "这个太火啦，等会儿再试。");
                        return;
                    }
                    if (code >= 300) {
                        scheduleNext(botId, key, this, deadlineMs, onFail, "UNAVAILABLE");
                        return;
                    }
                    String body = res.body() == null ? "" : res.body();
                    if (body.contains("\"pending\"") && body.contains("true")) {
                        scheduleNext(botId, key, this, deadlineMs, onFail, null);
                        return;
                    }
                    PluginOutput output = executor.parseTaskPayload(plugin, body);
                    finish(key);
                    ok(botId, onDone, output);
                } catch (DataExecutor.PluginException e) {
                    finish(key);
                    fail(botId, onFail, e.code(), e.userHint());
                } catch (Exception e) {
                    log.debug("轮询任务 {} 失败: {}", key, e.getMessage());
                    scheduleNext(botId, key, this, deadlineMs, onFail, "UNAVAILABLE");
                }
            }
        };
        ScheduledFuture<?> f = scheduler.schedule(poll, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        running.put(key, f);
    }

    private void scheduleNext(long botId, String key, Runnable poll, long deadlineMs,
                              BiConsumer<String, String> onFail, String failCode) {
        if (failCode != null && System.currentTimeMillis() > deadlineMs) {
            finish(key);
            fail(botId, onFail, failCode, null);
            return;
        }
        try {
            running.put(key, scheduler.schedule(poll, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            finish(key);
            fail(botId, onFail, "UNAVAILABLE", null);
        }
    }

    private static void ok(long botId, Consumer<PluginOutput> cb, PluginOutput out) {
        try {
            BotContext.run(botId, () -> cb.accept(out));
        } catch (Exception e) {
            // 回调里的发送/落库异常不能反过来触发再次轮询，否则同一结果可能发两次
            log.error("插件异步结果回调失败 botId={}: {}", botId, e.getMessage(), e);
        }
    }

    private static void fail(long botId, BiConsumer<String, String> cb, String code, String hint) {
        try {
            BotContext.run(botId, () -> cb.accept(code, hint));
        } catch (Exception e) {
            log.error("插件异步失败回调异常 botId={}: {}", botId, e.getMessage(), e);
        }
    }

    private void finish(String key) {
        ScheduledFuture<?> f = running.remove(key);
        if (f != null) {
            f.cancel(false);
        }
    }

    /** 关停时清理（Spring 生命周期不一定调用，daemon 线程不影响退出） */
    public void shutdown() {
        scheduler.shutdownNow();
        running.clear();
    }
}
