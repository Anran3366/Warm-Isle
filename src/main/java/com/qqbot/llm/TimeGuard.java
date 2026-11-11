package com.qqbot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 时间感兜底：白天回复里出现「晚安 / 今晚 / 早点睡」这类夜间用语时，
 * 让模型按当前时段改写一遍，避免上午 9 点说“今晚早点睡”。
 */
@Service
public class TimeGuard {

    private static final Logger log = LoggerFactory.getLogger(TimeGuard.class);
    private static final Pattern NIGHT_WORDS =
            Pattern.compile("(晚安|今晚|大晚上|这么晚|早点睡|该睡了|睡吧|做个好梦|好梦)");

    private final DeepSeekClient llm;

    public TimeGuard(DeepSeekClient llm) {
        this.llm = llm;
    }

    public String fix(String text, LocalDateTime now) {
        if (text == null || text.isBlank()) {
            return text;
        }
        int hour = now.getHour();
        if (hour < 6 || hour >= 18) {
            return text;
        }
        if (!NIGHT_WORDS.matcher(text).find()) {
            return text;
        }
        String slot = hour < 11 ? "上午" : hour < 14 ? "中午" : hour < 18 ? "下午" : "傍晚";
        try {
            String prompt = """
                    现在是 %02d:%02d，属于%s。
                    下面这句话里出现了夜间用语，和现在的时间不符。
                    请改写这句话，要求：
                    1. 不能出现“晚安、今晚、早点睡、该睡了、睡吧、好梦”这些词。
                    2. 保持原来的意思和口语风格，长度差不多。
                    3. 只输出改写后的这一句，不要解释。

                    原句：%s
                    """.formatted(hour, now.getMinute(), slot, text);
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content", "你只输出改写后的句子。"),
                    Map.of("role", "user", "content", prompt)
            ), 0.5, 200);
            String fixed = result.content() == null ? "" : result.content().trim();
            if (!fixed.isBlank() && !NIGHT_WORDS.matcher(fixed).find()) {
                log.info("时间感修正: {} -> {}", abbreviate(text), abbreviate(fixed));
                return fixed;
            }
        } catch (Exception e) {
            log.warn("时间感改写失败: {}", e.getMessage());
        }
        return text.replace("今晚", "今天").replace("晚安", "回头聊")
                .replace("早点睡", "早点休息").replace("该睡了", "别太累");
    }

    private String abbreviate(String s) {
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }
}
