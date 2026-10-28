package com.qqbot.review;

import com.qqbot.llm.DeepSeekClient;
import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.MessageRow;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待确认记忆的自动审核：第二天早上结合前一天的聊天记录，
 * 让模型判断这条记忆有没有依据，支持就转正，明确相反就拒绝，拿不准就继续挂着。
 */
@Service
public class FactReviewService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(FactReviewService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_CHARS = 4000;

    private final FactStore facts;
    private final MessageStore messages;
    private final DeepSeekClient llm;

    public FactReviewService(FactStore facts, MessageStore messages, DeepSeekClient llm) {
        this.facts = facts;
        this.messages = messages;
        this.llm = llm;
    }

    @Scheduled(cron = "0 30 6 * * *")
    public void daily() {
        accountRunner.forEach(id -> {
            try {
                run(LocalDate.now().minusDays(1).format(DAY));
            } catch (Exception e) {
                log.warn("记忆自动审核失败: {}", e.getMessage());
            }
    
        });
    }

    /** @return 统计：approved / rejected / kept */
    public Map<String, Object> run(String day) {
        boolean anyDay = day == null || day.isBlank();
        LocalDate date = null;
        if (!anyDay) {
            try {
                date = LocalDate.parse(day);
            } catch (Exception e) {
                throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
            }
        }
        int approved = 0;
        int rejected = 0;
        int kept = 0;

        for (Fact fact : facts.pendingAll()) {
            try {
                // 不指定日期时，按这条记忆自己记录的日期取当天聊天
                LocalDate factDay = date;
                if (factDay == null) {
                    String base = fact.lastConfirmed();
                    try {
                        factDay = base == null ? LocalDate.now() : LocalDate.parse(base.substring(0, 10));
                    } catch (Exception e) {
                        factDay = LocalDate.now();
                    }
                }
                String from = factDay.format(DAY) + " 00:00:00";
                String to = factDay.plusDays(1).format(DAY) + " 00:00:00";
                List<MessageRow> rows = messages.between(fact.qqId(), from, to);
                if (rows.size() < 2) {
                    kept++;
                    continue;
                }
                String verdict = judge(fact, rows);
                if (verdict == null) {
                    kept++;
                    continue;
                }
                if (verdict.startsWith("APPROVE")) {
                    facts.setStatusWithNote(fact.id(), "active", "自动审核通过：" + reason(verdict));
                    approved++;
                } else if (verdict.startsWith("REJECT")) {
                    facts.setStatusWithNote(fact.id(), "rejected", "自动审核拒绝：" + reason(verdict));
                    rejected++;
                } else {
                    kept++;
                }
            } catch (Exception e) {
                kept++;
                log.warn("审核记忆失败 id={}: {}", fact.id(), e.getMessage());
            }
        }
        log.info("记忆自动审核 day={} approved={} rejected={} kept={}", anyDay ? "按记录日期" : day, approved, rejected, kept);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("day", anyDay ? "auto" : day);
        result.put("approved", approved);
        result.put("rejected", rejected);
        result.put("kept", kept);
        return result;
    }

    private String judge(Fact fact, List<MessageRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (MessageRow r : rows) {
            sb.append("user".equals(r.role()) ? "用户：" : "我：").append(r.content()).append('\n');
        }
        String transcript = sb.length() > MAX_CHARS
                ? sb.substring(sb.length() - MAX_CHARS) : sb.toString();
        String prompt = """
                这是一条从聊天里抽出来、还没确认的记忆：
                类别：%s
                内容：%s = %s

                下面是当天的聊天记录。请判断这条记忆是否有明确依据：
                - 用户明确说过、或多次印证 → APPROVE
                - 用户明确否认、或记录里根本对不上 → REJECT
                - 记录太少、模糊、无法判断 → UNSURE

                只输出一行：APPROVE/REJECT/UNSURE + 空格 + 一句不超过20字的理由。

                聊天记录：
                %s
                """.formatted(fact.category(), fact.key(), fact.value(), transcript);
        try {
            var result = llm.chat(List.of(
                    Map.of("role", "system", "content", "你只输出一行判定，不要解释多余内容。"),
                    Map.of("role", "user", "content", prompt)
            ), 0.1, 80);
            String out = result.content() == null ? "" : result.content().trim();
            String upper = out.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("APPROVE") || upper.contains("REJECT") || upper.contains("UNSURE")) {
                // 模型拿不准，但原文里明确出现过这个说法 → 通过
                if (upper.contains("UNSURE") && mentions(transcript, fact)) {
                    return "APPROVE 聊天里明确提到过";
                }
                return out;
            }
            log.info("审核返回无法识别: {}", out);
        } catch (Exception e) {
            log.warn("审核模型失败: {}", e.getMessage());
        }
        return null;
    }

    /** 聊天原文里是否直接出现过这条记忆的关键词 */
    private boolean mentions(String transcript, Fact fact) {
        String value = fact.value() == null ? "" : fact.value().trim();
        String key = fact.key() == null ? "" : fact.key().trim();
        if (value.length() >= 2 && transcript.contains(value)) {
            return true;
        }
        return key.length() >= 2 && transcript.contains(key);
    }

    private String reason(String verdict) {
        int idx = verdict.indexOf(' ');
        return idx > 0 ? verdict.substring(idx + 1).trim() : "";
    }
}
