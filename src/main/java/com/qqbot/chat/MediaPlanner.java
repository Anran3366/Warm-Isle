package com.qqbot.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发言前的「媒体计划」：在生成正文之前，先判断这一条要不要配表情包、或直接发成语音。
 *
 * <p>这样正文和动作是一起决定的，不会出现「喏，挑了个最像我的」结果什么都没发出去。
 * 判断失败或超时一律按 {@link Media#NONE} 处理，不影响正常回复。
 */
@Component
public class MediaPlanner {

    private static final Logger log = LoggerFactory.getLogger(MediaPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RECENT_MESSAGES = 6;
    private static final int MAX_INTENT_CHARS = 12;

    public enum Media {
        NONE, EMOJI, VOICE
    }

    /** @param intent 表情意图（media=emoji 时才有），例如「想抱抱」 */
    public record Plan(Media media, String intent, String reason) {

        public static Plan none() {
            return new Plan(Media.NONE, null, "default");
        }

        public static Plan emoji(String intent, String reason) {
            return new Plan(Media.EMOJI, intent, reason);
        }

        public static Plan voice(String reason) {
            return new Plan(Media.VOICE, null, reason);
        }

        public boolean wantsEmoji() {
            return media == Media.EMOJI;
        }

        public boolean wantsVoice() {
            return media == Media.VOICE;
        }
    }

    private final DeepSeekClient llm;
    private final MessageStore messages;

    public MediaPlanner(DeepSeekClient llm, MessageStore messages) {
        this.llm = llm;
        this.messages = messages;
    }

    /** 判断这条回复要不要带媒体；任何异常都退回 NONE */
    public Plan plan(long qqId, String userText) {
        try {
            List<Map<String, String>> prompt = List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", buildInput(qqId, userText)));
            LlmResult r = llm.chat(prompt, 0.2, 160);
            Plan plan = parse(r.content());
            log.info("媒体计划 qqId={} media={} intent={} reason={}",
                    qqId, plan.media(), plan.intent(), plan.reason());
            return plan;
        } catch (Exception e) {
            log.warn("媒体计划失败，按无媒体处理: {}", e.getMessage());
            return Plan.none();
        }
    }

    private String buildInput(long qqId, String userText) {
        StringBuilder sb = new StringBuilder("最近几句：\n");
        try {
            List<ChatMessage> recent = messages.recent(qqId, RECENT_MESSAGES);
            for (ChatMessage m : recent) {
                String text = m.content();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if ("assistant".equals(m.role()) && text.startsWith("（")) {
                    // 媒体记录只告诉它「刚发过」，不把整段描述塞进来
                    sb.append("我：（发过一条媒体）\n");
                    continue;
                }
                sb.append("assistant".equals(m.role()) ? "我：" : "对方：").append(shorten(text, 60)).append('\n');
            }
        } catch (Exception ignored) {
            // 读历史失败就用当前这句
        }
        sb.append("\n对方刚说：").append(shorten(userText, 120)).append("\n请输出 JSON。");
        return sb.toString();
    }

    /** 解析模型输出；解析不出来按 NONE */
    static Plan parse(String content) {
        if (content == null) {
            return Plan.none();
        }
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Plan.none();
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            String media = node.path("media").asText("none").trim().toLowerCase();
            String reason = shorten(node.path("reason").asText(""), 40);
            switch (media) {
                case "emoji", "sticker", "表情" -> {
                    String intent = node.path("intent").asText("").trim();
                    if (intent.isEmpty()) {
                        return Plan.none();
                    }
                    return Plan.emoji(shorten(intent, MAX_INTENT_CHARS), reason);
                }
                case "voice", "语音" -> {
                    return Plan.voice(reason);
                }
                default -> {
                    return new Plan(Media.NONE, null, reason);
                }
            }
        } catch (Exception e) {
            return Plan.none();
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static final String SYSTEM_PROMPT = """
            你是陪聊机器人的「媒体判断器」。看最近几句和对方刚说的话，判断这一条回复要不要配一张表情包，或者干脆发成语音。

            规则：
            1. 默认 none。别为了发而发，大多数时候就是 none。
            2. 对方在开玩笑、斗嘴、撒娇、庆祝、被逗笑、气氛轻松 → emoji，并给一个简短的意图（≤10 字，例如 想抱抱 / 无语 / 得意 / 假装生气）。
            3. 对方情绪低落、说正事、求助、抱怨工作 → 不要 emoji；如果你在安慰、语气亲近，可以 voice。
            4. 对方明确要语音、想听你说话 → voice。
            5. 最近一条你刚发过表情或语音 → 这次 none，别连着来。
            6. 对方明确要表情包（「来张奶龙表情包」这类）由别的流程处理，这里选 none。

            只输出一个 JSON，不要解释：
            {"media":"none|emoji|voice","intent":"media=emoji 时填表情意图，其余留空","reason":"一句话说明为什么"}
            """;
}
