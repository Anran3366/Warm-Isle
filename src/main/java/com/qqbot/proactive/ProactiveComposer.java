package com.qqbot.proactive;

import com.qqbot.chat.ChatMessage;
import com.qqbot.config.BotProperties;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主动消息文案生成：让模型结合人设、记忆、最近聊天、历史主动消息来写，
 * 避免每次都发同一句话。失败时退回随机模板。
 */
@Component
public class ProactiveComposer {

    private static final Logger log = LoggerFactory.getLogger(ProactiveComposer.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DeepSeekClient llm;
    private final FactStore facts;
    private final MessageStore messages;
    private final ProactiveStore proactiveStore;
    private final BotProperties props;

    public ProactiveComposer(DeepSeekClient llm, FactStore facts, MessageStore messages,
                             ProactiveStore proactiveStore, BotProperties props) {
        this.llm = llm;
        this.facts = facts;
        this.messages = messages;
        this.proactiveStore = proactiveStore;
        this.props = props;
    }

    /**
     * @param situation 现在为什么要主动说话，例如「她昨天说感冒了，现在是第二天早上」
     * @param topic     话题关键词，用于兜底模板
     */
    public String compose(long qqId, String situation, String topic) {
        String generated = generate(qqId, situation, topic);
        String last = proactiveStore.lastContent(qqId);

        if (generated != null && !generated.isBlank()) {
            String text = generated.trim();
            // 和上一条主动消息一模一样就重写一次
            if (!text.equals(last)) {
                return text;
            }
            String retry = generate(qqId, situation + "。注意不要和上一次说的话重复，换个说法。", topic);
            if (retry != null && !retry.isBlank() && !retry.trim().equals(last)) {
                return retry.trim();
            }
        }
        return fallback(topic, situation);
    }

    /**
     * 日程型（早安/晚安/沉默）专用：不注入聊天记录与事实，避免模型编造具体细节。
     */
    public String composeScheduled(long qqId, String kind, String situation) {
        String last = proactiveStore.lastContent(qqId);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String extra = attempt == 0 ? "" : "。换一个说法，不要和上一条重复";
                List<Map<String, String>> prompt = List.of(
                        Map.of("role", "system", "content", scheduledPrompt(kind, situation + extra)),
                        Map.of("role", "user", "content", "请直接输出这条消息。"));
                LlmResult r = llm.chat(prompt, 1.0, 80);
                String text = r.content() == null ? "" : r.content().trim();
                if (!text.isBlank() && !text.equals(last) && !containsSymptom(text)) {
                    return sanitize(text);
                }
            } catch (Exception e) {
                log.warn("生成日程消息失败: {}", e.getMessage());
            }
        }
        return scheduledFallback(kind, last);
    }

    private String scheduledPrompt(String kind, String situation) {
        String style = switch (kind) {
            case "morning" -> "早安问候";
            case "evening" -> "睡前晚安";
            default -> "随口找个话头聊两句";
        };
        return props.getPersona().getPrompt()
                + "\n现在你要主动发一条" + style + "。情况：" + situation + "。"
                + """
                
                硬性要求：
                1. 只输出这一条消息，不要解释、不要引号。
                2. 30 字以内，一到两句，像微信随手发的。
                3. 不要提及任何具体病症（嗓子、发烧、咳嗽等），不要提及任何具体项目、地点、事件或人名。
                4. 不要重复历史里说过的话，不要用回应式开头。
                5. 不要提 AI、不要报时间。
                """;
    }

    private boolean containsSymptom(String text) {
        String[] bad = {"嗓子", "发烧", "咳嗽", "鼻涕", "头疼", "项目"};
        for (String b : bad) {
            if (text.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private String scheduledFallback(String kind, String last) {
        String[] pool = switch (kind) {
            case "morning" -> new String[]{"早呀，起床了没", "早安，今天也要好好的", "醒了没，打个招呼"};
            case "evening" -> new String[]{"早点睡呀，晚安", "我先睡啦，你也别熬太晚", "晚安，明天见"};
            default -> new String[]{"在忙吗", "闲着没，聊两句", "刚想起你，干嘛呢"};
        };
        for (String s : pool) {
            if (!s.equals(last)) {
                return s;
            }
        }
        return pool[0];
    }

    private String generate(long qqId, String situation, String topic) {
        try {
            List<Map<String, String>> prompt = new ArrayList<>();
            prompt.add(Map.of("role", "system", "content", systemPrompt(qqId, situation)));

            List<ChatMessage> recent = messages.recent(qqId, 6);
            StringBuilder ctx = new StringBuilder();
            if (!recent.isEmpty()) {
                ctx.append("最近聊天：\n");
                for (ChatMessage m : recent) {
                    ctx.append("user".equals(m.role()) ? "对方：" : "我：").append(m.content()).append('\n');
                }
            }
            List<String> history = proactiveStore.recentContents(qqId, 5);
            if (!history.isEmpty()) {
                ctx.append("\n我最近主动发过的话（不要重复）：\n");
                for (String h : history) {
                    ctx.append("- ").append(h).append('\n');
                }
            }
            if (topic != null && !topic.isBlank()) {
                ctx.append("\n本次只围绕「").append(topic).append("」这件事，原样用这个词。禁止出现任何具体症状词（如嗓子、声音、发烧、咳嗽、鼻涕、头疼），也不要编造对方没提过的细节。");
            }
            ctx.append("\n现在主动发一条消息。");
            prompt.add(Map.of("role", "user", "content", ctx.toString()));

            LlmResult r = llm.chat(prompt, 1.1, 120);
            return sanitize(r.content());
        } catch (Exception e) {
            log.warn("生成主动消息失败，用模板兜底: {}", e.getMessage());
            return null;
        }
    }

    private String systemPrompt(long qqId, String situation) {
        StringBuilder sb = new StringBuilder(props.getPersona().getPrompt());
        sb.append("\n现在换你主动找对方说话。情况：").append(situation).append('。');
        sb.append("\n当前时间：").append(LocalDateTime.now().format(TIME)).append('。');

        List<Fact> list = facts.active(qqId);
        if (!list.isEmpty()) {
            sb.append("\n你记得关于对方的事：");
            int n = 0;
            for (Fact f : list) {
                if (n++ >= 20) {
                    break;
                }
                sb.append(f.key()).append('=').append(f.value()).append("；");
            }
        }
        sb.append("""
                
                要求：
                1. 只输出你要发的那条消息，不要解释、不要引号、不要加「我：」。
                2. 一句话，最多两句，30 字以内，像微信随手发的。
                3. 自然、口语化，可以带一点情绪，但不要浮夸、不要道歉式开场。
                4. 不要重复历史里说过的句子，不要提「又」「每次」这类指责词（除非情况要求）。
                5. 不要提 AI、不要报时间、不要列清单。
                6. 这是你主动开启对话，不要用回应式开头（例如「你这话说的」「你说呢」）。
                7. 不要编造对方没说过的细节，只依据上面给出的信息；不要自行把话题替换成更具体的说法（例如把「感冒」说成「嗓子疼」「发烧」）。
                8. 除非用户原话里出现过某个具体细节，否则不要提及任何症状（嗓子、发烧、咳嗽、鼻涕等）或具体事件；宁可说得笼统一点。
                9. 绝对不要在消息里写具体时间点（例如“都9.55了”“12点了”“现在三点”），也不要复述上次聊天的时间。
                10. 只能按“当前时间”判断对方状态：06:00–11:00 才可以说早安/起了吗；11:00–14:00 说中午/吃饭；14:00–18:00 说下午/忙不忙；18:00–20:00 说傍晚/下班；20:00 以后才说晚上；23:00 以后才说睡。
                11. 如果今天对方已经回过消息，不要说“还没睡醒”“没回我”“怎么不理我”这类话；可以正常开启新话题。
                """);
        return sb.toString();
    }

    /** 去掉报时句式、修正白天里的“没睡醒” */
    private String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text.replaceAll("都\\s*\\d{1,2}\\s*[.:：]\\s*\\d{2}\\s*了[，,、]?\\s*", "")
                .replaceAll("\\d{1,2}\\s*[.:：]\\s*\\d{2}\\s*了[，,、]?\\s*", "")
                .trim();
        int hour = LocalDateTime.now().getHour();
        if (hour >= 11) {
            out = out.replace("还没睡醒", "在忙")
                    .replace("没睡醒", "在忙")
                    .replace("睡醒了没", "忙完了吗")
                    .replace("睡醒了吗", "忙完了吗");
        }
        if (hour < 20) {
            out = out.replace("大晚上", "这会儿").replace("这么晚了", "这会儿").replace("该睡了", "早点休息");
        }
        if (hour >= 18) {
            out = out.replace("早安", "晚上好");
        }
        return out;
    }

    private String fallback(String topic, String situation) {
        String t = topic == null ? "" : topic;
        boolean morning = LocalDateTime.now().getHour() < 11;
        if (situation.contains("第二次")) {
            String[] pool = {
                    "怎么不回我呀，我还惦记着你" + t + "好没好呢。",
                    "人呢？" + t + "好点没有呀。",
                    "你是不是又忙忘了，我都等你半天了。"
            };
            return pool[ThreadLocalRandom.current().nextInt(pool.length)];
        }
        if (situation.contains("冷落")) {
            String[] pool = {
                    "你又想不理我啦？",
                    "喂，又来这套，上次也是我先找你的。",
                    "你可算想起我了。"
            };
            return pool[ThreadLocalRandom.current().nextInt(pool.length)];
        }
        String[] pool = morning
                ? new String[]{"早呀，" + t + "好点了吗？", "醒了没，" + t + "怎么样了？", "早上好，今天" + t + "还难受吗？"}
                : new String[]{t + "好些了吗？", "今天" + t + "怎么样啦？", "忙完了吗，" + t + "好点没？"};
        return pool[ThreadLocalRandom.current().nextInt(pool.length)];
    }
}
