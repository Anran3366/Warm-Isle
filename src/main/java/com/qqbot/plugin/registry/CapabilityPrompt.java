package com.qqbot.plugin.registry;

import com.qqbot.bot.BotContext;
import com.qqbot.plugin.PluginProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力清单的注入决策（设计文档 §8.1）。
 *
 * <p>为什么不是每次都注入：8 条 × 40 字 ≈ 320 字，每一次请求都要付 token。
 * 只在这两种情况下注入：①对方明确问「你会什么/会不会唱歌」；②某插件刚启用的 20 分钟内
 * （帮模型建立印象）。其余闲聊 0 token，prompt 与改造前完全一致。
 */
@Component
public class CapabilityPrompt {

    private static final long NEW_ENABLE_WINDOW_MS = 20 * 60 * 1000L;

    private final PluginProperties props;
    private final PluginRegistry registry;
    private final Map<Long, Long> lastEnableAt = new ConcurrentHashMap<>();

    public CapabilityPrompt(PluginProperties props, PluginRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /** 插件被启用时调用，用于「刚启用」窗口 */
    public void markEnabled(long botId) {
        lastEnableAt.put(botId, System.currentTimeMillis());
    }

    /** @return 要追加到 system 的文本；空串表示不注入 */
    public String forMessage(long userId, String userText) {
        if (!props.isEnabled()) {
            return "";
        }
        Long botId = BotContext.get();
        if (botId == null) {
            return "";
        }
        List<InstalledPlugin> active = registry.activeFor(botId);
        if (active.isEmpty()) {
            return "";
        }
        boolean ask = CapabilityCatalog.asksCapability(userText);
        boolean fresh = System.currentTimeMillis() - lastEnableAt.getOrDefault(botId, 0L)
                < NEW_ENABLE_WINDOW_MS;
        if (!ask && !fresh) {
            return "";
        }
        return CapabilityCatalog.buildText(active);
    }
}
