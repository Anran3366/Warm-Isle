package com.qqbot.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回复末尾的「意图标记」：模型用它们表达自己想发表情 / 发语音。
 *
 * <p>模型只负责表达意图，真正发不发由系统按概率与频控决定（像人一样，不是每次都发）。
 * 标记必须从正文里剥掉，既不能发给用户，也不能进历史（否则模型会学着照抄）。
 */
public final class ReplyMarkers {

    /** [[表情:想抱抱]] */
    private static final Pattern EMOJI = Pattern.compile("\\[\\[表情[:：]([^\\]]{0,30})\\]\\]");
    /** 模型可能照抄历史格式，把「（我发了一张表情包：…）」当台词写出来 */
    private static final Pattern FAKE_MEDIA =
            Pattern.compile("^（(?:我发了一张表情包|表情包)[:：]?([^）]{0,60})）$");
    private static final Pattern FAKE_MEDIA_INLINE =
            Pattern.compile("（(?:我发了一张表情包|表情包)[:：]?[^）]{0,60}）");

    /** [[语音]] 或 [[语音:想哄哄你]] */
    private static final Pattern VOICE = Pattern.compile("\\[\\[语音(?:[:：]([^\\]]{0,30}))?\\]\\]");

    private ReplyMarkers() {
    }

    /**
     * @param text       剥掉标记后的正文
     * @param emojiIntent 表情意图（可为空）
     * @param voiceHint  语音意图说明（可为空；wantsVoice 表示有没有标记）
     * @param wantsVoice 模型是否想发语音
     */
    public record Parsed(String text, String emojiIntent, String voiceHint, boolean wantsVoice) {
    }

    public static Parsed parse(String reply) {
        if (reply == null) {
            return new Parsed("", null, null, false);
        }
        String text = reply == null ? "" : reply.trim();
        String emojiIntent = null;
        String voiceHint = null;
        boolean wantsVoice = false;

        // 整句就是一个伪媒体标记 → 取括号里的内容当正文，别把括号发给用户
        Matcher fake = FAKE_MEDIA.matcher(text);
        if (fake.matches()) {
            String inner = fake.group(1).trim();
            text = inner.isEmpty() ? "嗯" : inner;
        } else {
            text = FAKE_MEDIA_INLINE.matcher(text).replaceAll("").trim();
        }

        Matcher em = EMOJI.matcher(text);
        if (em.find()) {
            String v = em.group(1) == null ? "" : em.group(1).trim();
            emojiIntent = v.isEmpty() ? null : v;
        }
        text = em.reset().replaceAll("");

        Matcher vm = VOICE.matcher(text);
        if (vm.find()) {
            wantsVoice = true;
            String v = vm.group(1) == null ? "" : vm.group(1).trim();
            voiceHint = v.isEmpty() ? null : v;
        }
        text = vm.reset().replaceAll("");

        return new Parsed(text.trim(), emojiIntent, voiceHint, wantsVoice);
    }

    /** 只剥标记、不看意图（存历史用） */
    public static String strip(String reply) {
        return parse(reply).text();
    }
}
