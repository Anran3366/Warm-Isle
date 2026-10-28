package com.qqbot.voice;

import com.qqbot.bot.OneBotClient;
import com.qqbot.config.BotProperties;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 聊天里偶尔用语音回：你明确要语音时一定发；夜间按可配置概率发；其余发文字。
 * 频控：两次语音至少 90 分钟，每天最多 3 条；对方明确要语音时跳过间隔限制。
 */
@Service
public class ChatVoiceReplier {

    private static final Logger log = LoggerFactory.getLogger(ChatVoiceReplier.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 对方主动要语音时的每日上限（不受日常概率额度的 3 条限制） */
    private static final int EXPLICIT_DAILY_CAP = 6;

    private final VoiceService voice;
    private final SettingStore settings;
    private final MessageStore messages;
    private final BotProperties props;
    private final com.qqbot.chat.MoodService moods;
    private final VoiceStyleGenerator styleGenerator;
    private final com.qqbot.memory.SharedSettings sharedSettings;
    private final VoiceFileService voiceFiles;

    public ChatVoiceReplier(VoiceService voice, SettingStore settings, MessageStore messages,
                            BotProperties props,
                            com.qqbot.chat.MoodService moods,
                            VoiceStyleGenerator styleGenerator,
                            com.qqbot.memory.SharedSettings sharedSettings,
                            VoiceFileService voiceFiles) {
        this.voice = voice;
        this.settings = settings;
        this.messages = messages;
        this.props = props;
        this.moods = moods;
        this.styleGenerator = styleGenerator;
        this.sharedSettings = sharedSettings;
        this.voiceFiles = voiceFiles;
    }

    public boolean maybeSend(long userId, String userText, String reply, OneBotClient client) {
        return maybeSend(userId, userText, reply, client, false);
    }

    /**
     * @param markedVoice 模型自己在回复末尾加了 [[语音]]，表达「我想发语音」
     * @return true 表示已经用语音发出（调用方就不要再发文字）
     *
     * <p>发不发是概率问题，和真人一样：对方点名要 → 0.9；模型主动想发 → 0.7；
     * 都没有 → 日常概率（夜间/情绪低落会加成）。掷不中就发文字，标记已经被剥掉。
     */
    public boolean maybeSend(long userId, String userText, String reply, OneBotClient client,
                             boolean markedVoice) {
        try {
            if (client == null || !client.isConnected() || !voice.configured()) {
                return false;
            }
            if (!Boolean.parseBoolean(sharedSettings.get("voice.chatEnabled", "true"))) {
                return false;
            }
            // 上一条已经是语音就不要再发了，避免连续轰炸
            try {
                if ("voice".equals(messages.lastKind(userId))) {
                    return false;
                }
            } catch (Exception ignored) {
                // 读失败就继续
            }
            boolean wantsVoice = wantsVoice(userId, userText);
            double probability = probability(userId, wantsVoice, markedVoice);
            if (ThreadLocalRandom.current().nextDouble() >= probability) {
                log.debug("语音掷骰未中 userId={} p={} 点名={} 模型标记={}",
                        userId, probability, wantsVoice, markedVoice);
                return false;
            }
            if (!withinLimits(wantsVoice, userId)) {
                return false;
            }

            String text = reply;
            if (text.length() > 120) {
                text = text.substring(0, 120);
            }
            String style = null;
            if (Boolean.parseBoolean(sharedSettings.get("voice.styleLlm", "true"))) {
                style = styleGenerator.generate(userId, userText, text);
            }
            if (style == null || style.isBlank()) {
                style = styleFor(text, userId);
            }
            byte[] wav = voice.synthesizeStyled(text, style);
            client.sendPrivateRecord(userId, wav);
            markUsed(userId);
            messages.markLastAsVoice(userId);
            messages.setLastVoiceFile(userId, "assistant", voiceFiles.save(wav, "wav"));
            log.info("聊天中发送语音 userId={} 字数={}", userId, text.length());
            return true;
        } catch (Exception e) {
            log.warn("聊天语音发送失败，回退文字: {}", e.getMessage());
            return false;
        }
    }

    /** 根据时间、心情和文本内容生成语气指令，减少“念稿感” */
    private String styleFor(String text, long userId) {
        StringBuilder sb = new StringBuilder(
                "用真人发语音的自然口语语气说下面这句话，带一点情绪，不要播音腔，不要拖长音，"
                        + "音高保持平稳自然，不要忽高忽低。");
        try {
            double speed = voice.speed();
            if (speed < 0.95) {
                sb.append("语速稍慢一点。");
            } else if (speed > 1.05) {
                sb.append("语速稍快一点。");
            }
        } catch (Exception ignored) {
            // 语速读取失败按默认
        }
        try {
            int hour = java.time.LocalTime.now().getHour();
            if (hour >= 23 || hour < 6) {
                sb.append("现在是深夜，声音放轻、语速稍慢。");
            } else if (hour < 9) {
                sb.append("现在是早上，语气清爽一点。");
            }
            if (moods.mood(userId) <= -0.2 || text.contains("难过") || text.contains("累")) {
                sb.append("对方情绪不太好，语气温柔、慢一点，别太欢快。");
            }
        } catch (Exception ignored) {
            // 心情读取失败不影响
        }
        if (text.contains("！") || text.contains("哈哈") || text.contains("啦")) {
            sb.append("带点笑意和活力。");
        }
        if (text.contains("？")) {
            sb.append("句尾自然上扬，像在问对方。");
        }
        return sb.toString();
    }

    /**
     * 这一段话发成语音的概率。
     * <ul>
     *   <li>对方点名要语音：0.9（几乎必发，仍需过频控）</li>
     *   <li>模型自己加了 [[语音]]：0.7（像人一样，想发也不一定每次都发）</li>
     *   <li>都没有：日常概率，深夜/清晨/对方情绪低落时加成</li>
     * </ul>
     */
    private double probability(long userId, boolean explicitRequest, boolean markedVoice) {
        if (explicitRequest) {
            return clamp01(props.getVoice().getExplicitProbability());
        }
        if (markedVoice) {
            return clamp01(props.getVoice().getMarkProbability());
        }
        double probability = clamp01(props.getVoice().getChatProbability());
        if (probability <= 0) {
            return 0;
        }
        int hour = java.time.LocalTime.now().getHour();
        if (hour >= 22 || hour < 1) {
            probability = Math.min(1, probability * 1.8);
        } else if (hour < 7) {
            probability = Math.min(1, probability * 1.3);
        }
        try {
            if (moods.mood(userId) <= -0.3) {
                probability = Math.min(1, probability * 1.5);
            }
        } catch (Exception ignored) {
            // 心情读取失败不影响
        }
        return probability;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 对方明确要语音（含上一两句里提过） */
    private boolean wantsVoice(long userId, String userText) {
        if (hasVoiceIntent(userText)) {
            return true;
        }
        try {
            java.util.List<com.qqbot.chat.ChatMessage> recent = messages.recent(userId, 2);
            for (com.qqbot.chat.ChatMessage m : recent) {
                if ("user".equals(m.role()) && hasVoiceIntent(m.content())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 读历史失败就只看当前这句
        }
        return false;
    }

    private boolean hasVoiceIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String kw : new String[]{"语音", "发段", "发条", "听你说话", "听你说", "说句话",
                "说一句", "声音", "录一段", "唱两句", "念一段"}) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param explicitRequest 对方明确要语音：单独给额度（至少 6 条/天），且不看 90 分钟间隔，
     *                        避免「有能力却一直说发不出来」
     */
    private boolean withinLimits(boolean explicitRequest, long userId) {
        int limit = props.getVoice().getDailyLimit();
        int cap = explicitRequest ? Math.max(limit, EXPLICIT_DAILY_CAP) : limit;
        int used = dailyCount(userId);
        if (cap <= 0 || used >= cap) {
            if (explicitRequest) {
                log.info("对方要语音但已达到当日上限 userId={} 已发={} 上限={}", userId, used, cap);
            }
            return false;
        }
        if (explicitRequest) {
            return true;
        }
        String last = settings.get("voice.lastChatAt:" + userId, null);
        if (last == null || last.isBlank()) {
            return true;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(last, TS);
            return Duration.between(t, LocalDateTime.now()).toMinutes() >= 90;
        } catch (Exception e) {
            return true;
        }
    }

    private int dailyCount(long userId) {
        String date = settings.get("voice.countDate:" + userId, "");
        if (!LocalDate.now().toString().equals(date)) {
            return 0;
        }
        try {
            return Integer.parseInt(settings.get("voice.count:" + userId, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private void markUsed(long userId) {
        settings.set("voice.lastChatAt:" + userId, LocalDateTime.now().format(TS));
        String today = LocalDate.now().toString();
        int count = settings.get("voice.countDate:" + userId, "").equals(today) ? dailyCount(userId) + 1 : 1;
        settings.set("voice.countDate:" + userId, today);
        settings.set("voice.count:" + userId, String.valueOf(count));
    }
}
