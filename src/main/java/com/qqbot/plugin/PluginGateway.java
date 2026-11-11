package com.qqbot.plugin;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
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
import com.qqbot.plugin.permission.PermissionCatalog;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.remote.HostedExecutor;
import com.qqbot.plugin.remote.PluginTaskRunner;
import com.qqbot.plugin.routing.PluginRouter;
import com.qqbot.plugin.routing.RouteDecision;
import com.qqbot.plugin.store.PluginStoreClient;
import com.qqbot.voice.VoiceFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 插件网关：分发器只需调用 {@link #tryHandle}，返回 true 表示「我处理完了，别再发文字」。
 *
 * <p>结果落点顺序（设计文档 §8.6，不能反）：校验 → 剥元数据 → 发送 → 落盘 → 落历史 → 记文件 → 写记忆。
 * 先发后写，发送失败就不写记忆（否则「记忆里唱过、用户没收到」）。
 *
 * <p>未启用插件时 {@link #tryHandle} 第一行就返回 false，主链路零额外开销。
 */
@Component
public class PluginGateway {

    private static final Logger log = LoggerFactory.getLogger(PluginGateway.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long SEND_TIMEOUT_MS = 10_000;

    private final PluginProperties props;
    private final PluginRouter router;
    private final DataExecutor dataExecutor;
    private final PromptExecutor promptExecutor;
    private final HostedExecutor hostedExecutor;
    private final PluginTaskRunner taskRunner;
    private final QuotaService quota;
    private final VoiceBudget voiceBudget;
    private final CircuitBreaker breaker;
    private final PluginMemoryWriter memoryWriter;
    private final OneBotRegistry registry;
    private final MessageStore messages;
    private final VoiceFileService voiceFiles;
    private final ImageGuardClient imageGuard;
    private final com.qqbot.chat.ChatService chatService;
    private final PluginStoreClient storeClient;
    private final Db db;

    public PluginGateway(PluginProperties props, PluginRouter router, DataExecutor dataExecutor,
                         PromptExecutor promptExecutor,
                         HostedExecutor hostedExecutor, PluginTaskRunner taskRunner,
                         QuotaService quota, VoiceBudget voiceBudget, CircuitBreaker breaker,
                         PluginMemoryWriter memoryWriter, OneBotRegistry registry,
                         MessageStore messages, VoiceFileService voiceFiles,
                         ImageGuardClient imageGuard, com.qqbot.chat.ChatService chatService,
                         PluginStoreClient storeClient,
                         Db db) {
        this.props = props;
        this.router = router;
        this.dataExecutor = dataExecutor;
        this.promptExecutor = promptExecutor;
        this.hostedExecutor = hostedExecutor;
        this.taskRunner = taskRunner;
        this.quota = quota;
        this.voiceBudget = voiceBudget;
        this.breaker = breaker;
        this.memoryWriter = memoryWriter;
        this.registry = registry;
        this.messages = messages;
        this.voiceFiles = voiceFiles;
        this.imageGuard = imageGuard;
        this.chatService = chatService;
        this.storeClient = storeClient;
        this.db = db;
    }

    /**
     * @return true = 插件已经处理（含反问/降级提示），调用方必须 return，不再走模型；
     * false = 没插件的事，正常对话
     */
    public boolean tryHandle(long botId, long qqId, String text, String userKind) {
        if (!props.isEnabled() || text == null || text.isBlank()) {
            return false;
        }
        RouteDecision decision;
        try {
            decision = router.route(botId, qqId, text);
        } catch (Exception e) {
            log.warn("插件路由异常（忽略，走正常对话）: {}", e.getMessage());
            return false;
        }
        if (!decision.handled()) {
            return false;
        }
        OneBotClient client = registry.get(botId);
        if (client == null) {
            return false;
        }
        InstalledPlugin plugin = decision.plugin();
        // 隐私模式在运行期同样生效：只在启用时拦截不够，开关打开后已启用的 hosted/local 也不能触发
        if (props.isPrivacyMode() && plugin.manifest() != null
                && PermissionCatalog.blockedInPrivacyMode(plugin.manifest())) {
            log.info("隐私模式开启，跳过联网/本机服务插件 {}", plugin.pluginId());
            return false;
        }
        if (breaker.isOpen(plugin.pluginId())) {
            log.debug("插件 {} 熔断中，交回正常对话", plugin.pluginId());
            return false;
        }
        // 到这里才确定插件真的处理（或至少会回一句话）；privacy/熔断返回 false 时
        // 交给 ChatService 记用户消息，不能在这里先记一遍造成重复。
        try {
            chatService.recordUserOnly(qqId, text, userKind);
        } catch (Exception e) {
            log.debug("记录用户消息失败: {}", e.getMessage());
        }
        // 吊销名单（§6.8）：验签生效后命中即停止触发（不卸载、不占配额）；未生效只告警
        try {
            if (storeClient.isRevoked(plugin.pluginId(), plugin.version())) {
                if (storeClient.signatureActive()) {
                    var rev = storeClient.revocationOf(plugin.pluginId(), plugin.version());
                    log.warn("插件 {} v{} 已被商店吊销（{}），停止触发",
                            plugin.pluginId(), plugin.version(), rev == null ? "" : rev.reason());
                    client.sendPrivate(qqId, "这个能力已经被商店下架处理了，暂时用不了啦。");
                    logRun(plugin, decision, "revoked", null, 0);
                    return true;
                }
                log.warn("插件 {} v{} 在商店吊销名单上，仍在触发（验签未生效，只提示）",
                        plugin.pluginId(), plugin.version());
            }
        } catch (Exception ignored) {
            // 名单读不到不影响主链路
        }
        if (decision.kind() == RouteDecision.Kind.FILL_PARAMS) {
            client.sendPrivate(qqId, decision.hint());
            return true;
        }
        if (!quota.allow(qqId, plugin.manifest())) {
            client.sendPrivate(qqId, "今天用得有点多啦，明天再弄这个好不好。");
            return true;
        }
        boolean audio = plugin.manifest().returnsAudio();
        if (audio && !voiceBudget.reserve(qqId, true)) {
            client.sendPrivate(qqId, "今天唱得有点多啦，明天再唱给你听。");
            return true;
        }

        quota.consume(qqId, plugin.manifest());
        long started = System.currentTimeMillis();
        PluginOutput output;
        try {
            output = execute(plugin, decision.action(), decision.params(), qqId);
        } catch (HostedExecutor.PendingTask pending) {
            // 长任务：先回一句「好，等我一下」，再后台轮询；不占消息线程
            client.sendPrivate(qqId, pending.ackText());
            logRun(plugin, decision, "pending", null, System.currentTimeMillis() - started);
            scheduleAsync(botId, client, qqId, plugin, decision, pending, audio);
            return true;
        } catch (DataExecutor.PluginException e) {
            if (audio) {
                voiceBudget.release(qqId);
            }
            quota.release(qqId, plugin.manifest());
            if (countsForBreaker(e.code())) {
                breaker.failure(plugin.pluginId());
            }
            client.sendPrivate(qqId, hintOf(e));
            logRun(plugin, decision, "error", e.code(), System.currentTimeMillis() - started);
            return true;
        } catch (Exception e) {
            if (audio) {
                voiceBudget.release(qqId);
            }
            quota.release(qqId, plugin.manifest());
            breaker.failure(plugin.pluginId());
            client.sendPrivate(qqId, "这次没成，等下再试试。");
            logRun(plugin, decision, "error", "INTERNAL", System.currentTimeMillis() - started);
            log.warn("插件 {} 执行失败: {}", plugin.pluginId(), e.getMessage());
            return true;
        }

        boolean sent = send(client, qqId, output);
        long latency = System.currentTimeMillis() - started;
        if (!sent) {
            if (audio) {
                voiceBudget.release(qqId);
            }
            quota.release(qqId, plugin.manifest());
            breaker.failure(plugin.pluginId());
            client.sendPrivate(qqId, "这次没发出去，等下再试试。");
            logRun(plugin, decision, "error", "SEND_FAILED", latency);
            return true;
        }

        breaker.success(plugin.pluginId());
        // 先发后写：只有真的发出去了，才落历史/文件/记忆
        recordHistory(qqId, output);
        memoryWriter.write(qqId, plugin.pluginId(), plugin.manifest(), output.memoryVars());
        logRun(plugin, decision, "ok", null, latency);
        return true;
    }

    private PluginOutput execute(InstalledPlugin plugin, String action, Map<String, String> params,
                                 long qqId) {
        if (plugin.manifest().usesData()) {
            return dataExecutor.execute(plugin, action, params);
        }
        if (plugin.manifest().usesHosted() || plugin.manifest().usesLocal()) {
            return hostedExecutor.execute(plugin, action, params, null);
        }
        if (plugin.manifest().usesPrompt()) {
            return promptExecutor.execute(plugin, action, params, qqId);
        }
        throw new DataExecutor.PluginException("UNSUPPORTED", "这个能力还没接通");
    }

    /** 长任务收尾：结果照样「先发后写」，失败只回一句，不刷屏 */
    private void scheduleAsync(long botId, OneBotClient client, long qqId, InstalledPlugin plugin,
                               RouteDecision decision, HostedExecutor.PendingTask pending,
                               boolean audio) {
        long started = System.currentTimeMillis();
        taskRunner.schedule(botId, plugin, pending.taskId(), pending.deadlineMs(),
                output -> {
                    long latency = System.currentTimeMillis() - started;
                    boolean sent;
                    try {
                        sent = send(client, qqId, output);
                    } catch (Exception e) {
                        sent = false;
                        log.warn("异步插件结果发送异常: {}", e.getMessage());
                    }
                    if (!sent) {
                        if (audio) {
                            voiceBudget.release(qqId);
                        }
                        quota.release(qqId, plugin.manifest());
                        breaker.failure(plugin.pluginId());
                        client.sendPrivate(qqId, "这次没发出去，等下再试试。");
                        logRun(plugin, decision, "error", "SEND_FAILED", latency);
                        return;
                    }
                    breaker.success(plugin.pluginId());
                    recordHistory(qqId, output);
                    memoryWriter.write(qqId, plugin.pluginId(), plugin.manifest(), output.memoryVars());
                    logRun(plugin, decision, "ok", null, latency);
                },
                (code, hint) -> {
                    if (audio) {
                        voiceBudget.release(qqId);
                    }
                    quota.release(qqId, plugin.manifest());
                    if (countsForBreaker(code)) {
                        breaker.failure(plugin.pluginId());
                    }
                    String message = hint != null && !hint.isBlank() ? hint : hintOf(code);
                    if (message != null) {
                        client.sendPrivate(qqId, message);
                    }
                    logRun(plugin, decision, "error", code, System.currentTimeMillis() - started);
                });
    }

    private boolean send(OneBotClient client, long qqId, PluginOutput output) {
        return switch (output.kind()) {
            case "audio" -> client.sendPrivateRecordSync(qqId, output.bytes(), SEND_TIMEOUT_MS);
            case "image" -> {
                ImageGuardClient.Decision d = imageGuard.check(output.bytes());
                if (d.sensitive()) {
                    log.info("插件图片被预筛拦截: {}", d.reason());
                    yield false;
                }
                client.sendPrivateImage(qqId, output.bytes());
                yield true;
            }
            default -> {
                client.sendPrivate(qqId, output.text());
                yield true;
            }
        };
    }

    private void recordHistory(long qqId, PluginOutput output) {
        String caption = captionOf(output);
        switch (output.kind()) {
            case "audio" -> {
                String file = null;
                try {
                    file = voiceFiles.save(output.bytes(), "mp3");
                } catch (Exception e) {
                    log.debug("保存语音文件失败: {}", e.getMessage());
                }
                messages.add(qqId, "assistant", caption, "voice");
                if (file != null) {
                    messages.setLastVoiceFile(qqId, "assistant", file);
                }
            }
            case "image" -> messages.add(qqId, "assistant", caption, "image");
            default -> messages.add(qqId, "assistant", output.text(), "text");
        }
    }

    private String captionOf(PluginOutput output) {
        if (output.caption() != null && !output.caption().isBlank()) {
            return output.caption();
        }
        Map<String, Object> vars = output.memoryVars() == null ? Map.of() : output.memoryVars();
        String song = firstNonBlank(vars.get("song"), vars.get("title"), vars.get("value"));
        long seconds = output.durationMs() / 1000;
        return switch (output.kind()) {
            case "audio" -> song == null ? "（唱了一首歌）"
                    : "（唱了《" + song + "》" + (seconds > 0 ? "，" + seconds + " 秒" : "") + "）";
            case "image" -> "（发了一张图）";
            default -> output.text() == null ? "（做了一件事）"
                    : output.text().substring(0, Math.min(30, output.text().length()));
        };
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private String hintOf(DataExecutor.PluginException e) {
        // 作者写了 userHint 就用作者的（设计文档 §8.8）
        if (e.userHint() != null && !e.userHint().isBlank()) {
            return e.userHint();
        }
        return hintOf(e.code());
    }

    private String hintOf(String code) {
        return switch (code == null ? "" : code) {
            case "NOT_FOUND" -> "这个我这儿没有，换一个？";
            case "BAD_PARAMS" -> "没太听懂，再说一遍？";
            case "RATE_LIMITED" -> "这个太火啦，等会儿再试。";
            case "UNSUPPORTED" -> "这个能力还没接通。";
            case "TIMEOUT", "UNAVAILABLE" -> "这次没成，等下再试试。";
            case "OUTPUT_TOO_LARGE", "BAD_OUTPUT" -> "这次的结果有点问题，晚点再试？";
            default -> "这次没成，等下再试试。";
        };
    }

    /** 这几类失败才计熔断；作者的业务错误（参数不对、没这个资源、限流）不该拖垮插件 */
    private static boolean countsForBreaker(String code) {
        return "UNAVAILABLE".equals(code) || "TIMEOUT".equals(code) || "INTERNAL".equals(code);
    }

    /** plugin_runs 只记匿名运行信息，不落 QQ 号 */
    private void logRun(InstalledPlugin plugin, RouteDecision decision, String status,
                        String errorCode, long latencyMs) {
        String sql = "INSERT INTO plugin_runs(plugin_id, plugin_version, invoke_id, intent_id, action, "
                + "status, error_code, latency_ms, params, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, plugin.pluginId());
            ps.setString(2, plugin.version());
            ps.setString(3, UUID.randomUUID().toString());
            ps.setString(4, plugin.manifest().intent() == null ? null : plugin.manifest().intent().id());
            ps.setString(5, decision.action());
            ps.setString(6, status);
            ps.setString(7, errorCode);
            ps.setLong(8, latencyMs);
            ps.setString(9, decision.params().toString());
            ps.setString(10, LocalDateTime.now().format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("写 plugin_runs 失败: {}", e.getMessage());
        }
    }
}
