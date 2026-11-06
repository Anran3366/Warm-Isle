package com.qqbot.proactive;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简意图识别：从用户的话里判断「有没有值得第二天追问的事」。
 * 关键词表优先，宁可漏判也不要乱发。
 */
@Component
public class IntentDetector {

    public record Intent(String topic, String detail) {
    }

    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("感冒", "感冒");
        KEYWORDS.put("发烧", "发烧");
        KEYWORDS.put("咳嗽", "咳嗽");
        KEYWORDS.put("头疼", "头疼");
        KEYWORDS.put("头痛", "头疼");
        KEYWORDS.put("肚子疼", "肚子不舒服");
        KEYWORDS.put("胃疼", "胃不舒服");
        KEYWORDS.put("牙疼", "牙疼");
        KEYWORDS.put("失眠", "没睡好");
        KEYWORDS.put("生理期", "身体不舒服");
        KEYWORDS.put("生病", "身体");
        KEYWORDS.put("去医院", "看病");
        KEYWORDS.put("体检", "体检");
        KEYWORDS.put("面试", "面试");
        KEYWORDS.put("考试", "考试");
        KEYWORDS.put("答辩", "答辩");
        KEYWORDS.put("加班", "加班");
        KEYWORDS.put("出差", "出差");
        KEYWORDS.put("搬家", "搬家");
    }

    /** 命中返回 intent，否则 null */
    public Intent detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            if (text.contains(e.getKey())) {
                return new Intent(e.getValue(), text.length() > 60 ? text.substring(0, 60) : text);
            }
        }
        return null;
    }
}
