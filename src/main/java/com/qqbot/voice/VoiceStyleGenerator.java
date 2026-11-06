package com.qqbot.voice;

import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让模型根据对话上下文生成 TTS 语气指令。
 * 例：「她在撒娇，语气软一点、带笑意，语速稍快，句尾上扬」
 *
 * 失败或超时自动回退到规则版（ChatVoiceReplier 里的 styleFor）。
 */
@Service
public class VoiceStyleGenerator {

    private static final Logger log = LoggerFactory.getLogger(VoiceStyleGenerator.class);
    private static final int MAX_CACHE = 200;

    private final DeepSeekClient llm;
    private final MessageStore messages;

    /** 简单的 LRU：同一句话不重复调用模型 */
    private final Map<String, String> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public VoiceStyleGenerator(DeepSeekClient llm, MessageStore messages) {
        this.llm = llm;
        this.messages = messages;
    }

    /**
     * @return 一段中文语气指令；失败返回 null（调用方走规则版）
     */
    public String generate(long qqId, String userText, String replyText) {
        String key = String.valueOf(userText) + "|" + replyText;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<Map<String, String>> prompt = new ArrayList<>();
            prompt.add(Map.of("role", "system", "content",
                    "你是语音导演。根据下面的聊天上下文，写一句不超过 40 字的中文语气指令，"
                            + "说明这句话应该怎么读：情绪、语速、音量、停顿、句尾是否上扬。"
                            + "只输出指令本身，不要引号，不要解释，不要提到“文本”“语音”。"));
            StringBuilder ctx = new StringBuilder("最近的对话：\n");
            try {
                var history = messages.recent(qqId, 6);
                for (var m : history) {
                    ctx.append("user".equals(m.role()) ? "对方：" : "我：").append(m.content()).append('\n');
                }
            } catch (Exception ignored) {
                // 没有历史也能生成
            }
            ctx.append("\n对方刚说：").append(userText == null ? "" : userText);
            ctx.append("\n我要说的这句话：").append(replyText == null ? "" : replyText);
            prompt.add(Map.of("role", "user", "content", ctx.toString()));

            LlmResult r = llm.chat(prompt, 0.7, 60);
            String style = r.content() == null ? "" : r.content().trim()
                    .replace("\"", "").replace("“", "").replace("”", "")
                    .replace("\n", " ");
            if (style.isBlank()) {
                return null;
            }
            if (style.length() > 80) {
                style = style.substring(0, 80);
            }
            cache.put(key, style);
            log.info("语气指令: {}", style);
            return style;
        } catch (Exception e) {
            log.warn("生成语气指令失败，回退规则版: {}", e.getMessage());
            return null;
        }
    }
}
