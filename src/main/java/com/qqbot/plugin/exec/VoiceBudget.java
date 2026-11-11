package com.qqbot.plugin.exec;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 语音额度（与 {@code ChatVoiceReplier} <b>共用同一组计数</b>，设计文档 §8.6 ①/§13.3）。
 *
 * <p>为什么不单开一套：不共用的话用户能连点 10 首，日常语音的「90 分钟 / 3 条」形同虚设。
 * 计数键与 ChatVoiceReplier 完全一致：{@code voice.lastChatAt:<uid>} / {@code voice.countDate:<uid>}
 * / {@code voice.count:<uid>}。
 *
 * <p>M0/M1 是「执行前预占、失败释放」：作者算力不白烧，用户也不用等生成完才被告知没额度。
 */
@Component
public class VoiceBudget {

    private static final Logger log = LoggerFactory.getLogger(VoiceBudget.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPLICIT_DAILY_CAP = 6;
    private static final int MIN_INTERVAL_MINUTES = 90;

    private final SettingStore settings;
    private final BotProperties props;

    public VoiceBudget(SettingStore settings, BotProperties props) {
        this.settings = settings;
        this.props = props;
    }

    /**
     * 预占一条语音额度。
     *
     * @param explicit 对方明确点播（「唱首七里香」算明确点播，跳过 90 分钟间隔，额度 6 条/天）
     * @return 是否拿到额度
     */
    public boolean reserve(long userId, boolean explicit) {
        if (!allow(userId, explicit)) {
            return false;
        }
        record(userId);
        return true;
    }

    /**
     * 只判断额度、不计数：给 {@code ChatVoiceReplier} 这类「先掷骰、合成成功后才记账」的路径用。
     * 与 {@link #reserve} 共用同一组计数键，插件语音和日常语音不会再各算各的。
     */
    public boolean allow(long userId, boolean explicit) {
        int cap = explicit ? Math.max(EXPLICIT_DAILY_CAP, dailyLimit()) : dailyLimit();
        if (cap <= 0 || dailyCount(userId) >= cap) {
            return false;
        }
        return explicit || minutesSinceLast(userId) >= MIN_INTERVAL_MINUTES;
    }

    /** 记账：+1 条并刷新最近发送时间。调用方必须已经过 {@link #allow}。 */
    public void record(long userId) {
        String today = LocalDate.now().toString();
        int count = today.equals(settings.get("voice.countDate:" + userId, ""))
                ? dailyCount(userId) + 1 : 1;
        settings.set("voice.countDate:" + userId, today);
        settings.set("voice.count:" + userId, String.valueOf(count));
        settings.set("voice.lastChatAt:" + userId, LocalDateTime.now().format(TS));
    }

    /** 发送/校验失败时释放（只回滚条数，不动 lastChatAt，避免把间隔改小） */
    public void release(long userId) {
        int count = dailyCount(userId);
        if (count > 0) {
            settings.set("voice.count:" + userId, String.valueOf(count - 1));
        }
    }

    public int dailyLimit() {
        return Math.max(1, props.getVoice().getDailyLimit());
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

    private long minutesSinceLast(long userId) {
        String last = settings.get("voice.lastChatAt:" + userId, null);
        if (last == null || last.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(last, TS);
            return Duration.between(t, LocalDateTime.now()).toMinutes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }
}
