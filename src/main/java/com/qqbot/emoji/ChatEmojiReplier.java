package com.qqbot.emoji;

import com.qqbot.bot.OneBotClient;
import com.qqbot.memory.Emoji;
import com.qqbot.memory.EmojiStore;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 聊天里偶尔补一张表情：优先让模型从候选里挑（看描述），失败回退标签匹配。
 * 频控：每 N 条回复最多一张、同一张每天不超过 2 次、不连续发同一种情绪。
 */
@Service
public class ChatEmojiReplier {

    private static final Logger log = LoggerFactory.getLogger(ChatEmojiReplier.class);
    private static final int MIN_REPLIES_BETWEEN = 8;
    private static final int PER_EMOJI_DAILY_LIMIT = 2;

    private final EmojiPicker picker;
    private final EmojiSelector selector;
    private final EmojiService emojiService;
    private final EmojiStore store;
    private final SettingStore settings;
    private final MessageStore messages;

    public ChatEmojiReplier(EmojiPicker picker, EmojiSelector selector, EmojiService emojiService,
                            EmojiStore store, SettingStore settings, MessageStore messages) {
        this.picker = picker;
        this.selector = selector;
        this.emojiService = emojiService;
        this.store = store;
        this.settings = settings;
        this.messages = messages;
    }

    /** 回复文字发出后调用；条件不满足就什么也不做 */
    public void maybeSend(long userId, String userText, OneBotClient client) {
        try {
            if (client == null || !client.isConnected()) {
                return;
            }
            if (!Boolean.parseBoolean(settings.get("emoji.chatEnabled", "true"))) {
                return;
            }
            int since = parseInt(settings.get("emoji.sinceLast", "0")) + 1;
            if (since < MIN_REPLIES_BETWEEN) {
                settings.set("emoji.sinceLast", String.valueOf(since));
                return;
            }
            Emoji emoji = choose(userId, userText);
            if (emoji == null) {
                String kw = EmojiPicker.requestKeyword(userText);
                if (kw != null && client.isConnected()) {
                    // 点名要的表情没有，就直说，别拿别的糊弄
                    client.sendPrivate(userId, "我这儿没有「" + kw + "」的表情包，回头我找一张存进去");
                    messages.add(userId, "assistant", "（表情包缺失）没有「" + kw + "」", "emoji");
                }
                settings.set("emoji.sinceLast", String.valueOf(since));
                return;
            }
            byte[] bytes = emojiService.read(emoji);
            client.sendPrivateImage(userId, bytes);
            store.markSent(emoji.id(), userId, emoji.primaryEmotion());
            messages.add(userId, "assistant", "（我发了一张表情包：" + emojiText(emoji) + "）", "emoji", emoji.id());
            settings.set("emoji.sinceLast", "0");
            log.info("发出表情 id={} 情绪={} 给 {}", emoji.id(), emoji.primaryEmotion(), userId);
        } catch (Exception e) {
            log.warn("发表情失败: {}", e.getMessage());
        }
    }

    /** 供「回复里带 [[表情:…]]」的路径使用 */
    public boolean sendChosen(long userId, String intent, OneBotClient client) {
        try {
            if (client == null || !client.isConnected()) {
                return false;
            }
            Emoji emoji = choose(userId, intent);
            if (emoji == null) {
                return false;
            }
            client.sendPrivateImage(userId, emojiService.read(emoji));
            store.markSent(emoji.id(), userId, emoji.primaryEmotion());
            messages.add(userId, "assistant", "（我发了一张表情包：" + emojiText(emoji) + "）", "emoji", emoji.id());
            return true;
        } catch (Exception e) {
            log.warn("发表情失败: {}", e.getMessage());
            return false;
        }
    }

    /** 记录里尽量写画面内容，方便模型记得自己发过什么 */
    private String emojiText(Emoji emoji) {
        String desc = emoji.description();
        if (desc != null && !desc.isBlank()) {
            String clean = desc.replaceAll("^（?这是一张表情包[:：]?", "")
                    .replace("画面：", "").replace("文字：", "，文字：").trim();
            return clean.length() > 40 ? clean.substring(0, 40) : clean;
        }
        return safe(emoji.emoTags());
    }

    private Emoji choose(long userId, String userText) {
        Emoji chosen = null;
        EmojiSelector.Choice choice = selector.choose(userId, userText);
        if (choice != null) {
            chosen = choice.emoji();
            log.info("模型挑表情 id={} 理由={}", chosen.id(), choice.reason());
        }
        if (chosen != null && !EmojiTimeFilter.allowed(chosen, java.time.LocalTime.now())) {
            log.info("表情时段不合适，改选 id={}", chosen.id());
            chosen = null;
        }
        if (chosen == null) {
            chosen = picker.pick(userText);
        }
        if (chosen == null) {
            return null;
        }
        // 回应型的“在呢/收到/你好”只在打招呼语境里发
        String desc = chosen.description() == null ? "" : chosen.description();
        boolean genericReply = desc.contains("在呢") || desc.contains("收到") || desc.contains("你好");
        String ctx = (userText == null ? "" : userText);
        boolean greeting = ctx.contains("你好") || ctx.contains("在吗") || ctx.contains("哈喽") || ctx.contains("嗨");
        if (genericReply && !greeting) {
            log.info("表情与语境不贴切，跳过 id={}", chosen.id());
            return null;
        }
        if (store.sentToday(chosen.id()) >= PER_EMOJI_DAILY_LIMIT) {
            return null;
        }
        String lastEmotion = store.lastEmotionTo(userId);
        if (lastEmotion != null && !lastEmotion.isBlank()
                && lastEmotion.equals(chosen.primaryEmotion())) {
            return null;
        }
        return chosen;
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "表情" : s;
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
