package com.qqbot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.SharedSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 话题屏蔽配置的持久化（前端页面已存在，后端先保证存取不丢）。
 * 实际拦截逻辑尚未接入聊天链路。
 */
@RestController
@RequestMapping("/api")
public class TopicBlockController {

    private static final String KEY = "topicBlock.config";

    private static final String DEFAULT_JSON = """
            {"enabled":false,"presets":[],"customTopics":[],
             "action":"refuse","replyTemplate":"这个我不太想聊，换个话题吧"}
            """;

    private final SharedSettings settings;
    private final ObjectMapper mapper;
    private final com.qqbot.topic.TopicBlockService service;

    public TopicBlockController(SharedSettings settings, ObjectMapper mapper,
                                com.qqbot.topic.TopicBlockService service) {
        this.settings = settings;
        this.mapper = mapper;
        this.service = service;
    }

    @GetMapping("/topic-block")
    public JsonNode get() {
        String raw = settings.get(KEY, DEFAULT_JSON);
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** 调试：拿一段话试匹配（不真的拦截） */
    @org.springframework.web.bind.annotation.PostMapping("/topic-block/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        String text = body != null && body.get("text") != null ? String.valueOf(body.get("text")) : "";
        com.qqbot.topic.TopicBlockService.Match match = service.check(text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("matched", match != null);
        if (match != null) {
            out.put("topic", match.topic());
            out.put("keyword", match.keyword());
            out.put("action", match.action());
            out.put("reply", service.reply(match));
        }
        return out;
    }

    @PutMapping("/topic-block")
    public JsonNode put(@RequestBody JsonNode body) {
        try {
            settings.set(KEY, mapper.writeValueAsString(body));
            return body;
        } catch (Exception e) {
            return mapper.createObjectNode().put("code", "SAVE_FAILED").put("message", e.getMessage());
        }
    }
}
