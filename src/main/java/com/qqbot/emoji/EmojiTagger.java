package com.qqbot.emoji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 给表情包打标签：用图片描述让模型输出情绪/场景标签，失败则关键词兜底 */
@Service
public class EmojiTagger {

    private static final Logger log = LoggerFactory.getLogger(EmojiTagger.class);

    public record Tags(String emo, String scene) {
    }

    private final DeepSeekClient llm;
    private final ObjectMapper mapper;

    public EmojiTagger(DeepSeekClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public Tags tag(String description) {
        if (description == null || description.isBlank()) {
            return new Tags("自动收集", "聊天来源");
        }
        try {
            List<Map<String, String>> prompt = List.of(
                    Map.of("role", "system", "content", SYSTEM),
                    Map.of("role", "user", "content", "图片描述：" + description));
            LlmResult r = llm.chat(prompt, 0.2, 120);
            JsonNode node = parse(r.content());
            if (node != null) {
                List<String> emo = readTags(node.path("emo"));
                List<String> scene = readTags(node.path("scene"));
                if (!emo.isEmpty() || !scene.isEmpty()) {
                    return new Tags(String.join(",", emo), String.join(",", scene));
                }
            }
        } catch (Exception e) {
            log.warn("表情打标签失败，用关键词兜底: {}", e.getMessage());
        }
        return fallback(description);
    }

    private List<String> readTags(JsonNode arr) {
        List<String> list = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String t = n.asText("").trim();
                if (!t.isEmpty() && list.size() < 3) {
                    list.add(t);
                }
            }
        }
        return list;
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

    private Tags fallback(String desc) {
        List<String> emo = new ArrayList<>();
        List<String> scene = new ArrayList<>();
        if (desc.contains("开心") || desc.contains("笑")) emo.add("开心");
        if (desc.contains("撒娇") || desc.contains("亲昵")) { emo.add("撒娇"); scene.add("撒娇"); }
        if (desc.contains("难过") || desc.contains("委屈")) emo.add("难过");
        if (desc.contains("无语") || desc.contains("翻白眼")) emo.add("无语");
        if (desc.contains("晚安") || desc.contains("睡")) scene.add("晚安");
        if (emo.isEmpty()) emo.add("自动收集");
        if (scene.isEmpty()) scene.add("聊天来源");
        return new Tags(String.join(",", emo), String.join(",", scene));
    }

    private static final String SYSTEM = """
            你是表情包标签器。根据给出的图片描述，输出 JSON（不要解释、不要代码块）：
            {"emo":["情绪标签"],"scene":["使用场景"]}
            要求：
            - emo 是这张表情传达的情绪，例如：开心、撒娇、难过、无语、生气、害羞、惊讶、疲惫
            - scene 是适合发送的场合，例如：打招呼、安慰、晚安、回应夸赞、吐槽、撒娇
            - 各最多 3 个，用简短中文词，不要重复
            """;
}
