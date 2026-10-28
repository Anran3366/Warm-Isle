package com.qqbot.settings;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时可改的设置：持久化在 settings 表（cfg.*），启动时回填到 BotProperties。
 * 改动立即对话路生效（DeepSeekClient / PromptBuilder 持有同一个 props 引用）。
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String PREFIX = "cfg.";

    private final BotProperties props;
    private final SettingStore store;
    private final com.qqbot.memory.SharedSettings shared;

    public SettingsService(BotProperties props, SettingStore store,
                           com.qqbot.memory.SharedSettings shared) {
        this.props = props;
        this.store = store;
        this.shared = shared;
    }

    @PostConstruct
    public void load() {
        try {
            props.getLlm().setModel(store.get(PREFIX + "model", props.getLlm().getModel()));
            props.getLlm().setTemperature(parseDouble(store.get(PREFIX + "temperature", null),
                    props.getLlm().getTemperature()));
            props.getLlm().setMaxTokens(parseInt(store.get(PREFIX + "maxTokens", null),
                    props.getLlm().getMaxTokens()));
            String wl = store.get("whitelist.ids", null);
            if (wl != null && !wl.isBlank()) {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                for (String part : wl.split(",")) {
                    try {
                        ids.add(Long.parseLong(part.trim()));
                    } catch (NumberFormatException ignored) {
                        // 跳过非法项
                    }
                }
                if (!ids.isEmpty()) {
                    props.setWhitelist(ids);
                }
            }
            props.getVoice().setChatProbability(parseDouble(shared.get("voice.chatProbability", null),
                    props.getVoice().getChatProbability()));
            props.getVoice().setDailyLimit(parseInt(shared.get("voice.dailyLimit", null),
                    props.getVoice().getDailyLimit()));
            props.getReply().setMaxChars(parseInt(store.get(PREFIX + "reply.maxChars", null),
                    props.getReply().getMaxChars()));
            props.getReply().setSplitDelayMs(parseInt(store.get(PREFIX + "reply.splitDelayMs", null),
                    (int) props.getReply().getSplitDelayMs()));
            props.getReply().setTypingMs(parseInt(store.get(PREFIX + "reply.typingMs", null),
                    (int) props.getReply().getTypingMs()));
            String adaptive = store.get(PREFIX + "reply.adaptive", null);
            if (adaptive != null) {
                props.getReply().setAdaptive(Boolean.parseBoolean(adaptive));
            }
            props.getAdmin().setDailyBudget(parseDouble(store.get(PREFIX + "dailyBudget", null),
                    props.getAdmin().getDailyBudget()));
            props.getLlm().setHistoryRounds(parseInt(store.get(PREFIX + "contextRounds", null),
                    props.getLlm().getHistoryRounds()));
            log.info("运行时设置已加载");
        } catch (Exception e) {
            log.warn("加载运行时设置失败: {}", e.getMessage());
        }
    }

    /**
     * 按当前机器人账号重新读取设置。
     * 设置是按账号存库的，而 props 是全局对象，启动时只能读到默认账号，
     * 所以每次读/写设置前都按当前上下文刷一次，避免显示成默认值。
     */
    private void refresh() {
        try {
            props.getLlm().setModel(store.get(PREFIX + "model", props.getLlm().getModel()));
            props.getLlm().setTemperature(parseDouble(store.get(PREFIX + "temperature", null),
                    props.getLlm().getTemperature()));
            props.getLlm().setMaxTokens(parseInt(store.get(PREFIX + "maxTokens", null),
                    props.getLlm().getMaxTokens()));
            props.getLlm().setHistoryRounds(parseInt(store.get(PREFIX + "contextRounds", null),
                    props.getLlm().getHistoryRounds()));
            props.getAdmin().setDailyBudget(parseDouble(store.get(PREFIX + "dailyBudget", null),
                    props.getAdmin().getDailyBudget()));
            props.getReply().setMaxChars(parseInt(store.get(PREFIX + "reply.maxChars", null),
                    props.getReply().getMaxChars()));
            props.getReply().setSplitDelayMs(parseInt(store.get(PREFIX + "reply.splitDelayMs", null),
                    (int) props.getReply().getSplitDelayMs()));
            props.getReply().setTypingMs(parseInt(store.get(PREFIX + "reply.typingMs", null),
                    (int) props.getReply().getTypingMs()));
            String adaptive = store.get(PREFIX + "reply.adaptive", null);
            if (adaptive != null) {
                props.getReply().setAdaptive(Boolean.parseBoolean(adaptive));
            }
            props.getVoice().setChatProbability(parseDouble(shared.get("voice.chatProbability", null),
                    props.getVoice().getChatProbability()));
            props.getVoice().setDailyLimit(parseInt(shared.get("voice.dailyLimit", null),
                    props.getVoice().getDailyLimit()));
        } catch (Exception e) {
            log.warn("刷新设置失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> current() {
        refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getLlm().getModel());
        body.put("temperature", props.getLlm().getTemperature());
        body.put("dailyBudget", props.getAdmin().getDailyBudget());
        body.put("contextRounds", props.getLlm().getHistoryRounds());
        body.put("maxTokens", props.getLlm().getMaxTokens());

        Map<String, Object> vision = new LinkedHashMap<>();
        vision.put("enabled", props.getImage().isEnabled());
        vision.put("maxPerMessage", props.getImage().getMaxPerMessage());
        vision.put("maxSize", props.getImage().getMaxEdge());
        vision.put("dailyLimit", props.getImage().getDailyLimit());
        vision.put("emojiAutoImport", Boolean.parseBoolean(store.get("emoji.autoCollect", "true")));
        body.put("vision", vision);

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("maxChars", props.getReply().getMaxChars());
        reply.put("splitDelayMs", props.getReply().getSplitDelayMs());
        reply.put("typingMs", props.getReply().getTypingMs());
        reply.put("adaptive", props.getReply().isAdaptive());
        body.put("reply", reply);
        return body;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        if (body == null) {
            return current();
        }
        refresh();
        apply(body, "model", v -> props.getLlm().setModel(String.valueOf(v)));
        apply(body, "temperature", v -> props.getLlm().setTemperature(toDouble(v, 1.2)));
        apply(body, "maxTokens", v -> props.getLlm().setMaxTokens((int) toDouble(v, 400)));
        apply(body, "contextRounds", v -> props.getLlm().setHistoryRounds((int) toDouble(v, 20)));
        apply(body, "dailyBudget", v -> props.getAdmin().setDailyBudget(toDouble(v, 1.0)));

        Object replyObj = body.get("reply");
        if (replyObj instanceof Map<?, ?> reply) {
            if (reply.get("maxChars") instanceof Number n) {
                props.getReply().setMaxChars(Math.max(20, Math.min(200, n.intValue())));
                store.set(PREFIX + "reply.maxChars", String.valueOf(props.getReply().getMaxChars()));
            }
            if (reply.get("splitDelayMs") instanceof Number n) {
                props.getReply().setSplitDelayMs(Math.max(0, Math.min(5000, n.longValue())));
                store.set(PREFIX + "reply.splitDelayMs", String.valueOf(props.getReply().getSplitDelayMs()));
            }
            if (reply.get("typingMs") instanceof Number n) {
                props.getReply().setTypingMs(Math.max(0, Math.min(5000, n.longValue())));
                store.set(PREFIX + "reply.typingMs", String.valueOf(props.getReply().getTypingMs()));
            }
            if (reply.get("adaptive") instanceof Boolean b) {
                props.getReply().setAdaptive(b);
                store.set(PREFIX + "reply.adaptive", b.toString());
            }
        }

        Object visionObj = body.get("vision");
        if (visionObj instanceof Map<?, ?> vision) {
            Object v = vision.get("enabled");
            if (v instanceof Boolean b) {
                props.getImage().setEnabled(b);
                store.set(PREFIX + "vision.enabled", b.toString());
            }
            if (vision.get("maxPerMessage") instanceof Number n) {
                props.getImage().setMaxPerMessage(n.intValue());
                store.set(PREFIX + "vision.maxPerMessage", n.toString());
            }
            if (vision.get("dailyLimit") instanceof Number n) {
                props.getImage().setDailyLimit(n.intValue());
                store.set(PREFIX + "vision.dailyLimit", n.toString());
            }
            if (vision.get("emojiAutoImport") instanceof Boolean b) {
                store.set("emoji.autoCollect", b.toString());
            }
        }
        return current();
    }

    private void apply(Map<String, Object> body, String key, java.util.function.Consumer<Object> setter) {
        Object value = body.get(key);
        if (value == null) {
            return;
        }
        try {
            setter.accept(value);
            store.set(PREFIX + key, String.valueOf(value));
        } catch (Exception e) {
            log.warn("应用设置 {} 失败: {}", key, e.getMessage());
        }
    }

    private double toDouble(Object v, double fallback) {
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }

    private double parseDouble(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}
