package com.qqbot.plugin.remote;

import com.qqbot.bot.BotContext;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 异步轮询必须把发起任务的 botId 带回回调线程（设计文档 P0-5）。
 * 丢了上下文就会把 plugin_runs / 记忆写到默认账号。
 */
class PluginTaskRunnerTest {

    @Test
    void callbacksRunInCapturedBotContext() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tasks/t1", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HostedExecutor executor = mock(HostedExecutor.class);
            InstalledPlugin plugin = new InstalledPlugin("install-1", "menran.sing", "1.0.0",
                    "hosted", "store", null, "installed", null, null, null);
            when(executor.checkedBase(plugin))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(executor.parseTaskPayload(eq(plugin), anyString()))
                    .thenReturn(PluginOutput.text("唱完了", "唱完了", Map.of()));

            PluginTaskRunner runner = new PluginTaskRunner(executor);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Long> seenBot = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();

            runner.schedule(42L, plugin, "t1", System.currentTimeMillis() + 8_000,
                    out -> {
                        seenBot.set(BotContext.get());
                        done.countDown();
                    },
                    (code, hint) -> {
                        failure.set(code);
                        seenBot.set(BotContext.get());
                        done.countDown();
                    });

            assertTrue(done.await(10, TimeUnit.SECONDS), "轮询回调没有按时执行");
            assertEquals(null, failure.get(), "不应走失败分支");
            assertEquals(42L, seenBot.get(), "回调线程必须带着发起任务的 botId");
        } finally {
            server.stop(0);
        }
    }
}
