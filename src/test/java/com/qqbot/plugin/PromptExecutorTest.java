package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.BudgetExceededException;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.exec.PromptExecutor;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** prompt 执行器：模板渲染、变量白名单、token 封顶、预算映射（用子类替身，不走 Mockito） */
class PromptExecutorTest {

    private final Path dir = Path.of("packs/demo.tarot").toAbsolutePath();

    /** 录下调用参数的假 LLM */
    static class FakeLlm extends DeepSeekClient {
        LlmResult result = new LlmResult("今天适合慢慢来。", 200, 50);
        RuntimeException failure;
        List<Map<String, String>> seenMessages;
        double seenTemperature;
        int seenMaxTokens;

        FakeLlm() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        public LlmResult chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
            if (failure != null) {
                throw failure;
            }
            this.seenMessages = messages;
            this.seenTemperature = temperature;
            this.seenMaxTokens = maxTokens;
            return result;
        }
    }

    static class EmptyMemories extends MemoryStore {
        EmptyMemories() {
            super(null);
        }

        @Override
        public List<Memory> topByType(long qqId, String type, int limit) {
            return List.of();
        }
    }

    private InstalledPlugin plugin() throws Exception {
        return pluginWith(fixtureManifest());
    }

    private PluginManifest fixtureManifest() throws Exception {
        return new ObjectMapper().readValue(
                Files.readAllBytes(dir.resolve("manifest.json")), PluginManifest.class);
    }

    private InstalledPlugin pluginWith(PluginManifest m) {
        return new InstalledPlugin("i", "demo.tarot", "1.0.0", "prompt", "upload", "sha",
                "installed", null, m, dir);
    }

    @Test
    void rendersTemplateIntoUserMessageOnly() throws Exception {
        FakeLlm llm = new FakeLlm();
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());

        PluginOutput out = executor.execute(pluginWith(fixtureManifest()), "draw",
                Map.of("topic", "工作"), 12345L);

        assertEquals("text", out.kind());
        assertEquals("今天适合慢慢来。", out.text());

        assertEquals("system", llm.seenMessages.get(0).get("role"));
        assertEquals("user", llm.seenMessages.get(1).get("role"));
        String user = llm.seenMessages.get(1).get("content");
        assertTrue(user.contains("工作"), "模板变量应被渲染");
        assertTrue(user.contains("命运之轮"), "data 素材应被注入");
        assertFalse(user.contains("{topic}"), "占位符不应残留");
        assertEquals(0.7, llm.seenTemperature);
        assertEquals(600, llm.seenMaxTokens);
        // 作者模板绝不能进 system
        assertFalse(llm.seenMessages.get(0).get("content").contains("塔罗"));
        // 记忆变量来自路由参数
        assertEquals("工作", out.memoryVars().get("topic"));
    }

    @Test
    void optionalParamFallsBackToEmpty() throws Exception {
        FakeLlm llm = new FakeLlm();
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());
        executor.execute(pluginWith(fixtureManifest()), "draw", Map.of(), 1L);
        String user = llm.seenMessages.get(1).get("content");
        assertFalse(user.contains("{topic}"), "可选参数没给也该渲染成空串");
    }

    @Test
    void maxTokensClampedTo1000() throws Exception {
        PluginManifest raw = fixtureManifest();
        PluginManifest m = new PluginManifest(
                raw.schemaVersion(), raw.id(), raw.name(), raw.version(), raw.apiVersion(),
                raw.runtime(), raw.description(), raw.author(), raw.license(), raw.tags(),
                raw.icon(), raw.readme(), raw.compat(), raw.intent(), raw.actions(),
                raw.hosted(), raw.local(), raw.permissions(), raw.catalog(),
                new PluginManifest.PromptSpec("prompts/tarot.md", 9999, 0.7),
                raw.data(), raw.variables(), raw.memory(), raw.quota(), raw.voice(),
                raw.config(), raw.changelog());
        FakeLlm llm = new FakeLlm();
        new PromptExecutor(llm, new EmptyMemories()).execute(pluginWith(m), "draw", Map.of(), 1L);
        assertEquals(1000, llm.seenMaxTokens);
    }

    @Test
    void unknownPlaceholderIsBadParams() {
        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> PromptExecutor.render("你好 {nope}", Map.of(), "demo.tarot"));
        assertEquals("BAD_PARAMS", e.code());
    }

    @Test
    void budgetExceededBecomesRateLimited() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.failure = new BudgetExceededException("今日预算用尽");
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(pluginWith(fixtureManifest()), "draw", Map.of(), 1L));
        assertEquals("RATE_LIMITED", e.code());
        assertNotNull(e.userHint());
    }

    @Test
    void llmFailureBecomesUnavailable() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.failure = new RuntimeException("连接超时");
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());

        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(pluginWith(fixtureManifest()), "draw", Map.of(), 1L));
        assertEquals("UNAVAILABLE", e.code());
    }

    @Test
    void outputCappedAt800Chars() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.result = new LlmResult("长".repeat(1200), 10, 500);
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());
        PluginOutput out = executor.execute(pluginWith(fixtureManifest()), "draw", Map.of(), 1L);
        assertEquals(800, out.text().length());
    }

    @Test
    void emptyOutputIsBadOutput() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.result = new LlmResult("   ", 10, 0);
        PromptExecutor executor = new PromptExecutor(llm, new EmptyMemories());
        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(pluginWith(fixtureManifest()), "draw", Map.of(), 1L));
        assertEquals("BAD_OUTPUT", e.code());
    }

    @Test
    void dataPathTraversalIsRejected() throws Exception {
        PluginManifest raw = fixtureManifest();
        PluginManifest m = new PluginManifest(
                raw.schemaVersion(), raw.id(), raw.name(), raw.version(), raw.apiVersion(),
                raw.runtime(), raw.description(), raw.author(), raw.license(), raw.tags(),
                raw.icon(), raw.readme(), raw.compat(), raw.intent(), raw.actions(),
                raw.hosted(), raw.local(), raw.permissions(), raw.catalog(), raw.prompt(),
                Map.of("deck", "../escape.json"), raw.variables(), raw.memory(), raw.quota(),
                raw.voice(), raw.config(), raw.changelog());
        PromptExecutor executor = new PromptExecutor(new FakeLlm(), new EmptyMemories());
        DataExecutor.PluginException e = assertThrows(DataExecutor.PluginException.class,
                () -> executor.execute(pluginWith(m), "draw", Map.of(), 1L));
        assertEquals("INTERNAL", e.code());
    }
}
