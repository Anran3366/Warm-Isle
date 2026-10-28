package com.qqbot.emoji;

import com.qqbot.memory.Emoji;
import com.qqbot.memory.EmojiStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 聊天中挑表情：先按情绪关键词匹配标签，匹配不到就随机。
 * 频控放在调用方（每 N 条最多一张），这里只负责「有合适的就返回」。
 */
@Service
public class EmojiPicker {

    private static final Logger log = LoggerFactory.getLogger(EmojiPicker.class);

    /** 用户话里的情绪词 → 期望的表情标签 */
    private static final Map<String, List<String>> MOOD_TAGS = new LinkedHashMap<>();

    static {
        MOOD_TAGS.put("开心", List.of("开心", "高兴", "笑", "哈哈", "好耶", "赞"));
        MOOD_TAGS.put("难过", List.of("安慰", "抱抱", "难过", "委屈"));
        MOOD_TAGS.put("累", List.of("安慰", "抱抱", "累"));
        MOOD_TAGS.put("生气", List.of("无语", "生气", "翻白眼"));
        MOOD_TAGS.put("无语", List.of("无语", "翻白眼"));
        MOOD_TAGS.put("晚安", List.of("晚安", "睡"));
        MOOD_TAGS.put("早安", List.of("早安", "打招呼"));
    }

    private final EmojiStore store;
    private final SettingStore settings;

    public EmojiPicker(EmojiStore store, SettingStore settings) {
        this.store = store;
        this.settings = settings;
    }

    /** 用户是否明确点名要某个表情；返回关键词（如「雪山」），没有则 null */
    public static String requestKeyword(String userText) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        int idx = userText.indexOf("表情");
        if (idx <= 0) {
            return null;
        }
        String head = userText.substring(0, idx);
        // 取最后一个“发/来/给/要/想要…”之后的内容
        int cut = 0;
        for (String marker : new String[]{"想要", "想看", "发", "来", "给", "要", "整"}) {
            int i = head.lastIndexOf(marker);
            if (i >= 0) {
                cut = Math.max(cut, i + marker.length());
            }
        }
        String kw = head.substring(cut).trim();
        kw = kw.replaceAll("^(一张|一个|个|张|这种|那种|的|个)", "");
        kw = kw.replaceAll("[的了吧呀嘛]$", "");
        kw = kw.trim();
        if (kw.length() < 2 || kw.length() > 8) {
            return null;
        }
        if (kw.matches("(好看|可爱|那种|这种|一张|一个)")) {
            return null;
        }
        return kw;
    }

    /** @return 选中的表情，或 null（点名要的找不到时也返回 null） */
    public Emoji pick(String userText) {
        List<Emoji> all = store.usable(200);
        java.time.LocalTime now = java.time.LocalTime.now();
        all.removeIf(e -> !EmojiTimeFilter.allowed(e, now));
        if (all.isEmpty()) {
            return null;
        }
        String lastId = settings.get("emoji.lastId", "");

        // 点名要某个表情：只在库里按关键词找，找不到就返回 null（不要拿别的糊弄）
        String keyword = requestKeyword(userText);
        if (keyword != null) {
            List<Emoji> hits = new ArrayList<>();
            for (Emoji e : all) {
                if (containsAny(e, List.of(keyword)) || textOf(e).contains(keyword)) {
                    hits.add(e);
                }
            }
            if (hits.isEmpty()) {
                log.info("点名要的表情不在库里: {}", keyword);
                return null;
            }
            Emoji chosen0 = hits.get(ThreadLocalRandom.current().nextInt(hits.size()));
            settings.set("emoji.lastId", String.valueOf(chosen0.id()));
            log.info("按点名选中表情 id={} keyword={}", chosen0.id(), keyword);
            return chosen0;
        }

        List<Emoji> matched = new ArrayList<>();
        if (userText != null) {
            for (Map.Entry<String, List<String>> e : MOOD_TAGS.entrySet()) {
                if (userText.contains(e.getKey())) {
                    for (Emoji emoji : all) {
                        if (containsAny(emoji, e.getValue())) {
                            matched.add(emoji);
                        }
                    }
                }
            }
        }

        List<Emoji> pool = matched.isEmpty() ? all : matched;
        if (matched.isEmpty() && userText != null && !userText.isBlank()) {
            // 没有命中情绪词时，用描述做「二元组」相似度打分，挑最贴近的
            List<Emoji> scored = new ArrayList<>();
            int best = 0;
            for (Emoji e : all) {
                int score = similarity(userText, textOf(e));
                if (score > best) {
                    best = score;
                    scored.clear();
                    scored.add(e);
                } else if (score == best && score > 0) {
                    scored.add(e);
                }
            }
            if (best > 0) {
                pool = scored;
            }
        }
        List<Emoji> filtered = new ArrayList<>();
        for (Emoji e : pool) {
            if (!String.valueOf(e.id()).equals(lastId)) {
                filtered.add(e);
            }
        }
        if (filtered.isEmpty()) {
            filtered = pool;
        }
        Emoji chosen = filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
        settings.set("emoji.lastId", String.valueOf(chosen.id()));
        log.info("选中表情 id={} tags={}", chosen.id(), chosen.emoTags());
        return chosen;
    }

    private boolean containsAny(Emoji emoji, List<String> tags) {
        String text = textOf(emoji);
        for (String tag : tags) {
            if (text.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private String textOf(Emoji emoji) {
        return (emoji.emoTags() == null ? "" : emoji.emoTags()) + ","
                + (emoji.sceneTags() == null ? "" : emoji.sceneTags()) + ","
                + (emoji.description() == null ? "" : emoji.description());
    }

    /** 用户消息与表情文本的二元组重合度（中文按两字滑窗） */
    private int similarity(String userText, String emojiText) {
        if (userText == null || userText.isBlank() || emojiText == null || emojiText.isBlank()) {
            return 0;
        }
        String u = userText.replaceAll("\\s+", "");
        String e = emojiText.replaceAll("\\s+", "");
        int score = 0;
        for (int i = 0; i + 2 <= u.length(); i++) {
            String gram = u.substring(i, i + 2);
            if (e.contains(gram)) {
                score++;
            }
        }
        return score;
    }
}
