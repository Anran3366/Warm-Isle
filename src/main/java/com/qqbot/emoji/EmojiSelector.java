package com.qqbot.emoji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.chat.ChatMessage;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.Emoji;
import com.qqbot.memory.EmojiStore;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 让模型从候选表情里挑一张（带描述和情绪），比字符串匹配更懂语境。
 * 解析失败或没合适表情时返回 null，调用方回退到标签匹配。
 */
@Service
public class EmojiSelector {

    private static final Logger log = LoggerFactory.getLogger(EmojiSelector.class);

    public record Choice(Emoji emoji, String reason) {
    }

    private final EmojiStore store;
    private final DeepSeekClient llm;
    private final ObjectMapper mapper;
    private final MessageStore messages;

    public EmojiSelector(EmojiStore store, DeepSeekClient llm, ObjectMapper mapper, MessageStore messages) {
        this.store = store;
        this.llm = llm;
        this.mapper = mapper;
        this.messages = messages;
    }

    public Choice choose(long qqId, String intent) {
        List<Emoji> candidates = store.usable(15);
        java.time.LocalTime now = java.time.LocalTime.now();
        candidates.removeIf(e -> !EmojiTimeFilter.allowed(e, now));
        if (candidates.isEmpty()) {
            return null;
        }
        try {
            StringBuilder list = new StringBuilder();
            for (Emoji e : candidates) {
                list.append(e.id()).append(" | ")
                        .append(e.description() == null ? "(无描述)" : e.description())
                        .append(" | 情绪:").append(e.emoTags())
                        .append(" | 场景:").append(e.sceneTags())
                        .append('\n');
            }
            String recent = recentContext(qqId);
            String prompt = "候选表情：\n" + list
                    + "\n" + EmojiTimeFilter.hint(now)
                    + "\n最近的对话：\n" + recent
                    + "\n现在想配一张表情，语境是：" + (intent == null ? "(未指定)" : intent)
                    + "\n只输出 JSON：{\"id\": 数字, \"reason\": \"为什么选它\"}；如果没有合适的，输出 {\"id\": 0}。"
                            + "\n要求：必须和“语气/语境”贴切才选；只是情绪标签像、含义对不上就不要选。"
                            + "严禁在语境不是在打招呼时选“在呢/收到/你好/摆手”这类回应型表情。";
            LlmResult r = llm.chat(List.of(
                    Map.of("role", "system", "content", "你是表情包挑选器。只输出 JSON，不要解释，不要代码块。"),
                    Map.of("role", "user", "content", prompt)), 0.2, 120);
            JsonNode node = parse(r.content());
            if (node == null) {
                return null;
            }
            long id = node.path("id").asLong(0);
            if (id <= 0) {
                return null;
            }
            for (Emoji e : candidates) {
                if (e.id() == id) {
                    return new Choice(e, node.path("reason").asText(""));
                }
            }
        } catch (Exception e) {
            log.warn("模型挑表情失败，回退标签匹配: {}", e.getMessage());
        }
        return null;
    }

    private String recentContext(long qqId) {
        StringBuilder sb = new StringBuilder();
        try {
            for (ChatMessage m : messages.recent(qqId, 6)) {
                sb.append("user".equals(m.role()) ? "对方：" : "我：").append(m.content()).append('\n');
            }
        } catch (Exception ignored) {
            // 上下文拿不到就只用 intent
        }
        String text = sb.toString().trim();
        return text.length() > 600 ? text.substring(text.length() - 600) : text;
    }

    private JsonNode parse(String content) {
        if (content == null) {
            return null;
        }
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return null;
        }
        try {
            return mapper.readTree(content.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
