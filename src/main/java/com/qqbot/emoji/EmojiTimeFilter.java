package com.qqbot.emoji;

import com.qqbot.memory.Emoji;

import java.time.LocalTime;

/**
 * 表情包的时段适配：凌晨不要发「早安」，上午不要发「晚安」。
 */
public final class EmojiTimeFilter {

    private EmojiTimeFilter() {
    }

    public static boolean allowed(Emoji emoji, LocalTime now) {
        if (emoji == null) {
            return false;
        }
        String text = (emoji.description() == null ? "" : emoji.description())
                + " " + (emoji.emoTags() == null ? "" : emoji.emoTags())
                + " " + (emoji.sceneTags() == null ? "" : emoji.sceneTags());
        int hour = now.getHour();

        boolean morning = text.contains("早安") || text.contains("早上好") || text.contains("上午好")
                || text.contains("起床");
        if (morning && (hour < 5 || hour >= 11)) {
            return false;
        }
        boolean night = text.contains("晚安") || text.contains("好梦") || text.contains("睡觉")
                || text.contains("睡啦");
        if (night && hour >= 3 && hour < 21) {
            return false;
        }
        boolean noon = text.contains("中午好") || text.contains("午安");
        if (noon && (hour < 11 || hour >= 14)) {
            return false;
        }
        return true;
    }

    /** 给模型提示当前时段用 */
    public static String hint(LocalTime now) {
        int hour = now.getHour();
        if (hour < 5) {
            return "现在是凌晨，不要发早安、早安表情包，也不要提起床。";
        }
        if (hour < 11) {
            return "现在是上午，可以发早安，但不要发晚安。";
        }
        if (hour < 14) {
            return "现在是中午，发中性或轻松的表情。";
        }
        if (hour < 18) {
            return "现在是下午，不要发早安或晚安。";
        }
        if (hour < 22) {
            return "现在是晚上，不要发早安。";
        }
        return "现在是深夜，不要发早安，可以发晚安。";
    }
}
