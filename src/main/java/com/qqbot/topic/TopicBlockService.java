package com.qqbot.topic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.SharedSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 话题屏蔽：命中配置里的关键词后，按 action 处理。
 * 配置存在 settings 表的 topicBlock.config（JSON，前端页面写入）。
 */
@Service
public class TopicBlockService {

    private static final Logger log = LoggerFactory.getLogger(TopicBlockService.class);
    private static final String KEY = "topicBlock.config";

    public record Match(String topic, String keyword, String action, String reply) {
    }

    private final SharedSettings settings;
    private final ObjectMapper mapper;
    private final com.qqbot.llm.DeepSeekClient llm;

    public TopicBlockService(SharedSettings settings, ObjectMapper mapper,
                             com.qqbot.llm.DeepSeekClient llm) {
        this.mapper = mapper;
        this.settings = settings;
        this.llm = llm;
        seedDefaults();
    }

    private void seedDefaults() {
        if (settings.get(KEY, null) != null) {
            return;
        }
        String json = """
                {"enabled":true,"action":"refuse","replyTemplate":"这个话题我不聊的，换个别的吧",
                 "presets":[
                   {"id":"porn","label":"色情内容","enabled":true,"keywords":["色情","黄色网站","裸聊","约炮","嫖娼","性交易","成人视频","黄片","A片","做爱"]},
                   {"id":"gamble","label":"赌博","enabled":true,"keywords":["赌博","赌球","博彩","下注","百家乐","时时彩","六合彩","开赌"]},
                   {"id":"drug","label":"毒品","enabled":true,"keywords":["毒品","冰毒","海洛因","摇头丸","大麻","吸毒","贩毒","K粉"]},
                   {"id":"politics","label":"政治敏感","enabled":true,"keywords":["政治","领导人","国家主席","总统","共产党","政府腐败","游行示威","颠覆政权","台独","港独","疆独","藏独"]}
                 ],
                 "customTopics":[]}
                """;
        settings.set(KEY, json);
    }

    /** 命中返回 Match，否则 null */
    public Match check(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode cfg = config();
        if (cfg == null || !cfg.path("enabled").asBoolean(false)) {
            return null;
        }
        String normalized = normalize(text);

        for (JsonNode preset : cfg.path("presets")) {
            if (!preset.path("enabled").asBoolean(true)) {
                continue;
            }
            String label = preset.path("label").asText(preset.path("id").asText("话题"));
            for (JsonNode kw : preset.path("keywords")) {
                String keyword = kw.asText("").trim();
                if (!keyword.isEmpty() && normalized.contains(normalize(keyword))) {
                    return build(label, keyword, cfg);
                }
            }
        }

        for (JsonNode topic : cfg.path("customTopics")) {
            String word = topic.asText("").trim();
            if (!word.isEmpty() && normalized.contains(normalize(word))) {
                return build(word, word, cfg);
            }
        }
        return null;
    }

    /**
     * 带语义判断的检查：关键词命中后，再让模型判断这句话是不是真的在聊这类内容。
     * 例如「我是政治老师」只是职业介绍，应放行；「聊聊台独」才拦。
     */
    public Match checkSemantic(String text) {
        Match match = check(text);
        if (match == null) {
            return null;
        }
        if (looksLikeBlockedIntent(text, match)) {
            return match;
        }
        log.info("话题屏蔽放行（语义判断）topic={} keyword={} text={}",
                match.topic(), match.keyword(), abbreviate(text));
        return null;
    }

    private boolean looksLikeBlockedIntent(String text, Match match) {
        try {
            String prompt = """
                    判断下面这句话是否在“讨论、索要、宣扬”【%s】类内容。
                    只输出一个词：
                    BLOCK：对方确实在聊这类内容、要这类内容、或表达相关立场。
                    ALLOW：只是出现了相关词，但实际在说职业、身份、课程、学习、新闻转述、开玩笑、日常聊天。

                    句子：%s
                    """.formatted(match.topic(), text);
            var result = llm.chat(java.util.List.of(
                    java.util.Map.of("role", "system", "content", "你只输出 BLOCK 或 ALLOW。"),
                    java.util.Map.of("role", "user", "content", prompt)
            ), 0.0, 8);
            String answer = result.content() == null ? "" : result.content().trim().toUpperCase(java.util.Locale.ROOT);
            return answer.contains("BLOCK");
        } catch (Exception e) {
            log.warn("话题语义判断失败，按命中处理: {}", e.getMessage());
            return true;
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    /** 按 action 生成回复；silent 返回 null（不回复） */
    public String reply(Match match) {
        if (match == null) {
            return null;
        }
        if ("silent".equals(match.action())) {
            return null;
        }
        String template = match.reply();
        if (template == null || template.isBlank()) {
            template = "这个我聊不太来，换点轻松的？";
        }
        if (template.contains("这个话题我不聊的")) {
            template = "这个我聊不太来，换点轻松的？";
        }
        return template.replace("{topic}", match.topic());
    }

    private Match build(String topic, String keyword, JsonNode cfg) {
        String action = cfg.path("action").asText("refuse");
        String reply = cfg.path("replyTemplate").asText("这个我不太想聊，换个话题吧");
        log.info("命中话题屏蔽 topic={} keyword={} action={}", topic, keyword, action);
        return new Match(topic, keyword, action, reply);
    }

    private JsonNode config() {
        String raw = settings.get(KEY, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            log.warn("话题屏蔽配置解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 去空白 + NFKC + 小写，避免「裸 照」「ＮＵＤＥ」绕过 */
    private String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("\\s+", "");
    }
}
