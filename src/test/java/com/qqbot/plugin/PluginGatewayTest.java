package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.chat.ChatService;
import com.qqbot.image.ImageGuardClient;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import com.qqbot.plugin.exec.CircuitBreaker;
import com.qqbot.plugin.exec.DataExecutor;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.exec.PromptExecutor;
import com.qqbot.plugin.exec.QuotaService;
import com.qqbot.plugin.exec.VoiceBudget;
import com.qqbot.plugin.memory.PluginMemoryWriter;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.remote.HostedExecutor;
import com.qqbot.plugin.remote.PluginTaskRunner;
import com.qqbot.plugin.routing.PluginRouter;
import com.qqbot.plugin.routing.RouteDecision;
import com.qqbot.plugin.store.PluginStoreClient;
import com.qqbot.voice.VoiceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实例侧执行链路：路由命中 → 执行 → 发送 → 落历史 → 写记忆；
 * 发送失败时绝不能写记忆（设计文档 §8.6「先发后写」）。
 */
class PluginGatewayTest {

    private static final long BOT = 1L;
    private static final long USER = 100L;

    private PluginProperties props;
    private PluginRouter router;
    private DataExecutor dataExecutor;
    private OneBotRegistry registry;
    private OneBotClient client;
    private MessageStore messages;
    private VoiceFileService voiceFiles;
    private QuotaService quota;
    private VoiceBudget voiceBudget;
    private CircuitBreaker breaker;
    private PluginMemoryWriter memoryWriter;
    private PluginStoreClient storeClient;
    private ChatService chatService;
    private PluginGateway gateway;
    private InstalledPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        props = mock(PluginProperties.class);
        router = mock(PluginRouter.class);
        dataExecutor = mock(DataExecutor.class);
        registry = mock(OneBotRegistry.class);
        client = mock(OneBotClient.class);
        messages = mock(MessageStore.class);
        voiceFiles = mock(VoiceFileService.class);
        quota = mock(QuotaService.class);
        voiceBudget = mock(VoiceBudget.class);
        breaker = mock(CircuitBreaker.class);
        memoryWriter = mock(PluginMemoryWriter.class);
        storeClient = mock(PluginStoreClient.class);
        chatService = mock(ChatService.class);
        HostedExecutor hosted = mock(HostedExecutor.class);
        PromptExecutor prompt = mock(PromptExecutor.class);
        PluginTaskRunner taskRunner = mock(PluginTaskRunner.class);
        Db db = mock(Db.class);

        String json = """
                {"id":"menran.sing","name":"唱歌","version":"1.0.0","runtime":"data",
                 "actions":[{"name":"sing","returns":["audio"]}],
                 "intent":{"id":"music.sing","examples":["唱首歌"]},
                 "memory":{"policy":"episodic","type":"relation",
                           "keyTemplate":"唱过:{song}","valueTemplate":"《{song}》，32秒"}}
                """;
        PluginManifest manifest = new ObjectMapper().readValue(json, PluginManifest.class);
        plugin = new InstalledPlugin("i1", "menran.sing", "1.0.0", "data", "store",
                null, "installed", null, manifest, null);

        gateway = new PluginGateway(props, router, dataExecutor, prompt, hosted, taskRunner,
                quota, voiceBudget, breaker, memoryWriter, registry, messages, voiceFiles,
                mock(ImageGuardClient.class), chatService, storeClient, db);

        when(props.isEnabled()).thenReturn(true);
        when(props.isPrivacyMode()).thenReturn(false);
        when(props.getMaxSyncMs()).thenReturn(20_000);
        when(registry.get(BOT)).thenReturn(client);
        when(router.route(BOT, USER, "唱首七里香")).thenReturn(new RouteDecision(
                RouteDecision.Kind.EXECUTE, plugin, "sing", Map.of("song", "七里香"), 1.0, null));
        when(storeClient.isRevoked(anyString(), anyString())).thenReturn(false);
        when(breaker.isOpen(anyString())).thenReturn(false);
        when(quota.allow(USER, plugin.manifest())).thenReturn(true);
        when(voiceBudget.reserve(USER, true)).thenReturn(true);
        when(voiceFiles.save(any(byte[].class), anyString())).thenReturn("voice/1.mp3");
        when(memoryWriter.write(any(Long.class), anyString(), any(), anyMap())).thenReturn(true);
    }

    @Test
    void successfulAudioWritesHistoryAndMemory() {
        when(dataExecutor.execute(eq(plugin), eq("sing"), anyMap()))
                .thenReturn(PluginOutput.audio(new byte[]{1, 2, 3}, "audio/mpeg", 32_000,
                        "（唱了《七里香》，32 秒）", Map.of("song", "七里香")));
        when(client.sendPrivateRecordSync(eq(USER), any(byte[].class), any(Long.class)))
                .thenReturn(true);

        assertTrue(gateway.tryHandle(BOT, USER, "唱首七里香", "text"));

        verify(client).sendPrivateRecordSync(eq(USER), any(byte[].class), any(Long.class));
        verify(messages).add(eq(USER), eq("assistant"), contains("唱了"), eq("voice"));
        verify(memoryWriter).write(eq(USER), eq("menran.sing"), eq(plugin.manifest()), anyMap());
        verify(voiceBudget, never()).release(USER);
    }

    @Test
    void sendFailureDoesNotWriteMemory() {
        when(dataExecutor.execute(eq(plugin), eq("sing"), anyMap()))
                .thenReturn(PluginOutput.audio(new byte[]{1, 2, 3}, "audio/mpeg", 32_000,
                        "（唱了《七里香》，32 秒）", Map.of("song", "七里香")));
        when(client.sendPrivateRecordSync(eq(USER), any(byte[].class), any(Long.class)))
                .thenReturn(false);

        assertTrue(gateway.tryHandle(BOT, USER, "唱首七里香", "text"));

        verify(memoryWriter, never()).write(any(Long.class), anyString(), any(), anyMap());
        verify(quota).release(eq(USER), eq(plugin.manifest()));
        verify(voiceBudget).release(USER);
        verify(client).sendPrivate(eq(USER), contains("没发出去"));
    }
}
