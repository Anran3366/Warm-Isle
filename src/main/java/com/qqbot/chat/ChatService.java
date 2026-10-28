package com.qqbot.chat;

import com.qqbot.config.BotProperties;
import com.qqbot.llm.DeepSeekClient;
import com.qqbot.llm.LlmResult;
import com.qqbot.llm.PromptBuilder;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.UsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话主流程：从 SQLite 取最近上下文 → 组 prompt → 调模型 → 落库。
 * 重启后历史仍在，不会失忆。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final MessageStore messages;
    private final UsageStore usage;
    private final PromptBuilder promptBuilder;
    private final DeepSeekClient llm;
    private final BotProperties props;
    private final com.qqbot.llm.TimeGuard timeGuard;

    public ChatService(MessageStore messages,
                       UsageStore usage,
                       PromptBuilder promptBuilder,
                       DeepSeekClient llm,
                       BotProperties props,
                       com.qqbot.llm.TimeGuard timeGuard) {
        this.messages = messages;
        this.usage = usage;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.props = props;
        this.timeGuard = timeGuard;
    }

    /** 和最近 8 条里说过的话太像时，让它换个说法再生成一次 */
    private String avoidRepeat(long userId, String text, List<Map<String, String>> prompt) {
        try {
            List<com.qqbot.chat.ChatMessage> recent = messages.recent(userId, 8);
            for (com.qqbot.chat.ChatMessage m : recent) {
                if (!"assistant".equals(m.role()) || m.content() == null) {
                    continue;
                }
                if (!isDuplicateReply(text, m.content()) && similarity(m.content(), text) < 0.72) {
                    continue;
                }
                List<Map<String, String>> again = new java.util.ArrayList<>(prompt);
                again.add(Map.of("role", "user", "content",
                        "你刚才那句话和之前说过的重复了。换一种说法，意思可以接近，但不要用同样的句子。"));
                var r = llm.chat(again, 1.15, 200);
                String out = r.content() == null ? "" : r.content().trim();
                double before = similarity(m.content(), text);
                if (!out.isBlank() && !isDuplicateReply(out, m.content())
                        && similarity(out, text) < Math.max(0.6, before)) {
                    log.info("防重复改写: {} -> {}", abbreviate(text), abbreviate(out));
                    return out;
                }
            }
        } catch (Exception e) {
            log.warn("防重复检查失败: {}", e.getMessage());
        }
        return text;
    }

    /** 先归一化再算二元组相似度 */
    static double bigramSimilarity(String a, String b) {
        java.util.Set<String> sa = bigramsOf(a);
        java.util.Set<String> sb = bigramsOf(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static java.util.Set<String> bigramsOf(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (s == null) {
            return set;
        }
        String clean = s.replaceAll("[\\p{Punct}\\s]", "");
        for (int i = 0; i + 1 < clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        return set;
    }

    /** 中文按两个字一组算相似度，0-1 */
    private double similarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        java.util.Set<String> sa = bigrams(a);
        java.util.Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private java.util.Set<String> bigrams(String s) {
        String clean = s.replaceAll("[\\p{Punct}\\s]", "");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        if (clean.length() == 1) {
            set.add(clean);
        }
        return set;
    }

    private String abbreviate(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    /**
     * 生成一条回复；失败返回 null（调用方决定是否发兜底话术）。
     */
    public String reply(long userId, String userText) {
        return reply(userId, userText, null, "text");
    }

    /**
     * @param suffix 追加在回复末尾的一句话（例如被冷落后的抱怨），没有则传 null
     */
    public String reply(long userId, String userText, String suffix) {
        return reply(userId, userText, suffix, "text");
    }

    /**
     * @param userKind 用户消息类型：text / image
     */
    public String reply(long userId, String userText, String suffix, String userKind) {
        long gapMinutes = -1;
        try {
            String lastAt = messages.lastCreatedAt(userId);
            if (lastAt != null && !lastAt.isBlank()) {
                java.time.LocalDateTime prev = java.time.LocalDateTime.parse(lastAt,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                gapMinutes = java.time.Duration.between(prev, java.time.LocalDateTime.now()).toMinutes();
            }
        } catch (Exception ignored) {
            // 解析失败按未知处理
        }
        int limit = Math.max(1, props.getLlm().getHistoryRounds() * 2);
        // 隔了很久没聊：只带少量上下文，避免把旧话题当成现在
        if (gapMinutes > 360) {
            limit = Math.min(limit, 6);
        }
        List<ChatMessage> history;
        try {
            history = messages.recent(userId, limit);
        } catch (Exception e) {
            log.warn("读取历史失败，按空上下文处理: {}", e.getMessage());
            history = List.of();
        }

        List<Map<String, String>> prompt = promptBuilder.build(userId, history, userText, gapMinutes);
        LlmResult result;
        try {
            result = llm.chat(prompt);
        } catch (com.qqbot.llm.BudgetExceededException e) {
            log.warn("今日预算用尽，跳过模型调用");
            String fallback = "我这会儿有点卡，等下再回你哈";
            try {
                messages.add(userId, "user", userText, userKind == null ? "text" : userKind);
                messages.add(userId, "assistant", fallback, "budget");
            } catch (Exception ignored) {
                // 记录失败不影响兜底回复
            }
            return fallback;
        } catch (IllegalStateException e) {
            log.warn("跳过回复：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("生成回复失败", e);
            return "我这会儿有点卡，等下再回你哈";
        }

        if (result.content() == null || result.content().isBlank()) {
            return null;
        }
        String replyText = result.content();
        // 与上一条回复过于相似时重写一次，避免复读
        if (looksRepeated(userId, replyText)) {
            try {
                List<Map<String, String>> retry = new ArrayList<>(prompt);
                retry.add(Map.of("role", "system", "content",
                        "你刚才已经说过几乎一样的话，这次换个说法，不要重复上一句的措辞。"));
                LlmResult again = llm.chat(retry);
                if (again.content() != null && !again.content().isBlank()
                        && !looksRepeated(userId, again.content())) {
                    replyText = again.content();
                    result = again;
                }
            } catch (Exception ignored) {
                // 重写失败就用原回复
            }
        }
        if (suffix != null && !suffix.isBlank()) {
            replyText = replyText + "\n" + suffix;
        }

        // 时间感兜底：白天出现夜间用语时改写（表情标记不算正文）
        try {
            java.util.regex.Matcher markerMatcher = java.util.regex.Pattern
                    .compile("\\[\\[表情[:：][^\\]]{0,30}\\]\\]").matcher(replyText);
            String marker = markerMatcher.find() ? markerMatcher.group() : "";
            String body = markerMatcher.replaceAll("").trim();
            String fixed = timeGuard.fix(body, java.time.LocalDateTime.now());
            replyText = (fixed == null || fixed.isBlank() ? body : fixed) + marker;
        } catch (Exception e) {
            log.warn("时间感处理失败: {}", e.getMessage());
        }

        // 防重复：和最近说过的话太像就重写一次
        replyText = avoidRepeat(userId, replyText, prompt);

        try {
            // 历史里不保留 [[表情:…]] / [[语音]] 标记，避免模型模仿这种格式
            String stored = ReplyMarkers.strip(replyText).trim();
            messages.add(userId, "user", userText, userKind == null ? "text" : userKind);
            messages.add(userId, "assistant", stored, "text");
            usage.add(props.getLlm().getModel(), result.promptTokens(), result.completionTokens(),
                    llm.cost(result.promptTokens(), result.completionTokens()), userId);
        } catch (Exception e) {
            log.warn("落库失败（不影响回复）: {}", e.getMessage());
        }
        return replyText;
    }

    /** 与最近 2 条回复太像就算复读（会先剥掉「（语音）」「（表情包…）」这类系统标记） */
    private boolean looksRepeated(long userId, String text) {
        try {
            java.util.List<String> last = messages.lastAssistantTexts(userId, 2);
            for (String prev : last) {
                if (isDuplicateReply(text, prev)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 复读判定：先去掉「（语音）」「（我发了一张表情包：…）」这类前缀，
     * 再做二元组相似度与包含判断。
     *
     * <p>只看相似度会漏掉「同一句换皮」：上一条是语音，历史里带「（语音）」前缀，
     * 二元组被稀释到 0.7 以下就放过了（线上 id=1759/1761 就是这样重复的）。
     */
    static boolean isDuplicateReply(String candidate, String previous) {
        String a = normalizeReply(candidate);
        String b = normalizeReply(previous);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int shorter = Math.min(a.length(), b.length());
        int longer = Math.max(a.length(), b.length());
        if (shorter >= 4 && (a.contains(b) || b.contains(a))
                && (double) shorter / longer >= 0.7) {
            return true;
        }
        return bigramSimilarity(a, b) >= 0.8;
    }

    /** 去掉系统媒体标记与标点，只留可比较的正文 */
    static String normalizeReply(String s) {
        if (s == null) {
            return "";
        }
        String out = s.replaceAll("\\[\\[[^\\]]{0,40}\\]\\]", "")
                .replaceAll("^（(语音|表情包)[^）]*）", "")
                .replaceAll("^（我发了一张表情包：[^）]*）", "")
                .replaceAll("[\\p{Punct}\\s，。！？、；：「」『』（）【】…—～]", "");
        return out.trim();
    }

    /** 记录一轮对话（话题拦截等不走模型时用） */
    public void recordExchange(long userId, String userText, String reply, String kind) {
        recordExchange(userId, userText, reply, kind, "text");
    }

    public void recordExchange(long userId, String userText, String reply, String kind, String userKind) {
        try {
            messages.add(userId, "user", userText, userKind == null ? "text" : userKind);
            messages.add(userId, "assistant", reply, kind);
        } catch (Exception e) {
            log.warn("记录对话失败: {}", e.getMessage());
        }
    }

    /** 只记录用户消息（静默拦截时用） */
    public void recordUserOnly(long userId, String userText) {
        recordUserOnly(userId, userText, "text");
    }

    public void recordUserOnly(long userId, String userText, String userKind) {
        try {
            messages.add(userId, "user", userText, userKind == null ? "text" : userKind);
        } catch (Exception e) {
            log.warn("记录消息失败: {}", e.getMessage());
        }
    }

    public long activeUsers() {
        return messages.countUsers();
    }
}
