package com.qqbot.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.remote.HostedExecutor;
import com.qqbot.plugin.remote.RemoteFetch;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** hosted / local 的 HTTP 契约（设计文档 §10.4） */
class HostedExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private HostedExecutor executor;
    private InstalledPlugin plugin;
    private volatile String lastBody;
    private volatile String response;
    private volatile int status;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/invoke", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        PluginProperties props = new PluginProperties();
        executor = new HostedExecutor(props, new RemoteFetch());
        plugin = new InstalledPlugin("install-abc", "demo.echo", "1.0.0", "local", "upload",
                "sha", "installed", "http://127.0.0.1:" + port,
                manifest("local"), Path.of("/tmp"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static PluginManifest manifest(String runtime) {
        String json = """
                {
                  "schemaVersion": 2, "id": "demo.echo", "name": "回声",
                  "version": "1.0.0", "apiVersion": 1, "runtime": "%s",
                  "description": "测试用", "readme": "README.md",
                  "intent": {"id": "demo.echo", "examples": ["回声一下", "测试回声"]},
                  "actions": [{"name": "echo", "description": "回一句", "returns": ["text"]}],
                  "hosted": {"endpoint": "http://127.0.0.1:1"},
                  "permissions": {"network": {"outbound": ["api.example.com"]}}
                }
                """.formatted(runtime);
        try {
            return MAPPER.readValue(json, PluginManifest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void textResultIsParsed() {
        response = """
                {"ok":true,"outputs":[{"kind":"text","dataBase64":"%s"}],"caption":"回声：你好",
                 "memory":{"vars":{"word":"你好"}}}
                """.formatted(Base64.getEncoder().encodeToString("你好".getBytes(StandardCharsets.UTF_8)));
        status = 200;

        PluginOutput out = executor.execute(plugin, "echo", Map.of("word", "你好"), null);

        assertEquals("text", out.kind());
        assertEquals("你好", out.text());
        assertEquals("回声：你好", out.caption());
        assertEquals("你好", out.memoryVars().get("word"));
    }

    @Test
    void requestCarriesNoIdentity() throws Exception {
        response = "{\"ok\":true,\"outputs\":[{\"kind\":\"text\",\"dataBase64\":\"aGk=\"}]}";
        status = 200;

        executor.execute(plugin, "echo", Map.of("word", "hi"), null);

        JsonNode sent = MAPPER.readTree(lastBody);
        assertEquals("demo.echo", sent.path("pluginId").asText());
        assertEquals("echo", sent.path("action").asText());
        assertEquals("hi", sent.path("params").path("word").asText());
        String raw = lastBody;
        assertFalse(raw.contains("qq"), "请求体不能带 QQ 号字段");
        assertFalse(raw.contains("userId"));
        assertFalse(raw.contains("群"));
        assertEquals("zh-CN", sent.path("context").path("locale").asText());
        assertTrue(sent.path("context").path("timeoutMs").asLong() > 0);
    }

    @Test
    void pendingResponseAsksForAsyncPolling() {
        response = "{\"ok\":true,\"pending\":true,\"taskId\":\"t_9\",\"ackText\":\"好，等我一下\"}";
        status = 200;

        HostedExecutor.PendingTask pending = assertThrows(HostedExecutor.PendingTask.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("t_9", pending.taskId());
        assertEquals("好，等我一下", pending.ackText());
        assertTrue(pending.deadlineMs() > System.currentTimeMillis());
    }

    @Test
    void businessErrorKeepsAuthorHint() {
        response = """
                {"ok":false,"error":{"code":"BAD_PARAMS","message":"缺参数",
                 "userHint":"你想听哪首呀？","retryable":false}}
                """;
        status = 200;

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("BAD_PARAMS", e.code());
        assertEquals("你想听哪首呀？", e.userHint());
    }

    @Test
    void serverErrorBecomesUnavailable() {
        response = "{}";
        status = 500;

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("UNAVAILABLE", e.code());
    }

    @Test
    void rateLimitedIsDistinguished() {
        response = "{}";
        status = 429;

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("RATE_LIMITED", e.code());
    }

    @Test
    void oversizeInlineAudioIsRejected() {
        byte[] big = new byte[(int) RemoteFetch.MAX_AUDIO + 1024];
        big[0] = 'I';
        big[1] = 'D';
        big[2] = '3';
        response = "{\"ok\":true,\"outputs\":[{\"kind\":\"audio\",\"dataBase64\":\""
                + Base64.getEncoder().encodeToString(big) + "\"}]}";
        status = 200;

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("OUTPUT_TOO_LARGE", e.code());
    }

    @Test
    void htmlPretendingToBeAudioIsRejected() {
        response = "{\"ok\":true,\"outputs\":[{\"kind\":\"audio\",\"dataBase64\":\""
                + Base64.getEncoder().encodeToString("<html>x</html>".getBytes(StandardCharsets.UTF_8))
                + "\"}]}";
        status = 200;

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(plugin, "echo", Map.of(), null));
        assertEquals("BAD_OUTPUT", e.code());
    }

    @Test
    void acceptsInlineAudio() {
        byte[] wav = new byte[64];
        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        response = "{\"ok\":true,\"outputs\":[{\"kind\":\"audio\",\"mime\":\"audio/wav\","
                + "\"durationMs\":3200,\"dataBase64\":\""
                + Base64.getEncoder().encodeToString(wav) + "\"}],\"caption\":\"唱了《小星星》\"}";
        status = 200;

        PluginOutput out = executor.execute(plugin, "echo", Map.of(), null);
        assertEquals("audio", out.kind());
        assertEquals("audio/wav", out.mime());
        assertEquals(3200, out.durationMs());
        assertEquals("唱了《小星星》", out.caption());
    }

    @Test
    void missingEndpointFailsClearly() {
        InstalledPlugin noEndpoint = new InstalledPlugin("i2", "demo.echo", "1.0.0", "local",
                "upload", "sha", "installed", null, manifest("local"), Path.of("/tmp"));
        // manifest 里给的 hosted.endpoint 是 127.0.0.1:1，本机没有服务
        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(noEndpoint, "echo", Map.of(), null));
        assertTrue("UNAVAILABLE".equals(e.code()) || "TIMEOUT".equals(e.code()));
    }
}
