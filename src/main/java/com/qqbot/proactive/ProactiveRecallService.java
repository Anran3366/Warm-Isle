package com.qqbot.proactive;

import com.qqbot.chat.ChatMessage;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 主动回忆（记忆 v2 P3）：到点的 event / relation 记忆 → 一条关心。
 *
 * <p>约束（设计稿第 4 节）：
 * <ul>
 *   <li>同一事件只提一次（follow_up_state 变 done）</li>
 *   <li>每个用户每天最多 2 条主动回忆，关系类最多 1 条</li>
 *   <li>免打扰时段、用户 2 小时内说过话都不发</li>
 *   <li>用户当天已经提过这个话题 → skipped</li>
 *   <li>文案里不出现「记忆」「记录」</li>
 * </ul>
 */
@Service
public class ProactiveRecallService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveRecallService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String TYPE_EVENT = "recall";
    static final String TYPE_RELATION = "recall_relation";

    private static final int DAILY_EVENT_MAX = 2;
    private static final int DAILY_RELATION_MAX = 1;
    private static final int IDLE_MINUTES = 120;
    private static final int STALE_DAYS = 3;
    private static final String[] FORBIDDEN = {"记忆", "记录", "我记得", "记着"};

    /** 文案生成（生产用 ProactiveComposer，测试可注入假实现） */
    @FunctionalInterface
    interface TextComposer {
        String compose(long qqId, String situation, String topic);
    }

    private final MemoryStore memories;
    private final MessageStore messages;
    private final ProactiveStore store;
    private final TextComposer composer;

    @org.springframework.beans.factory.annotation.Autowired
    public ProactiveRecallService(MemoryStore memories, MessageStore messages,
                                  ProactiveStore store, ProactiveComposer composer) {
        this(memories, messages, store, composer::compose);
    }

    ProactiveRecallService(MemoryStore memories, MessageStore messages,
                           ProactiveStore store, TextComposer composer) {
        this.memories = memories;
        this.messages = messages;
        this.store = store;
        this.composer = composer;
    }

    /** 一条待发送的主动回忆 */
    public record Recall(long memoryId, String channelType, String topic, String text, String type) {
    }

    /**
     * 选出这一轮要发的一条；没有符合条件的返回 empty。
     * 该方法会顺手把该跳过的记忆置 skipped。
     */
    public Optional<Recall> next(long qqId, ProactiveState st, LocalDateTime now) {
        List<Memory> due;
        try {
            due = memories.dueFollowUps(qqId, now);
        } catch (Exception e) {
            log.warn("读取待主动回忆失败 qqId={}: {}", qqId, e.getMessage());
            return Optional.empty();
        }
        if (due.isEmpty()) {
            return Optional.empty();
        }
        // 2 小时内说过话就先不打扰
        LocalDateTime lastUser = parse(st.lastUserAt());
        if (lastUser != null && Duration.between(lastUser, now).toMinutes() < IDLE_MINUTES) {
            return Optional.empty();
        }

        int eventSent = store.sentToday(qqId, TYPE_EVENT);
        int relationSent = store.sentToday(qqId, TYPE_RELATION);

        for (Memory m : due) {
            boolean relation = "relation".equals(m.type());
            if (relation && relationSent >= DAILY_RELATION_MAX) {
                continue;
            }
            if (!relation && eventSent >= DAILY_EVENT_MAX) {
                continue;
            }
            LocalDateTime dueAt = parse(m.followUpAt());
            if (dueAt != null && dueAt.isBefore(now.minusDays(STALE_DAYS))) {
                // 陈年旧事不补发
                memories.markFollowUp(m.id(), "skipped");
                continue;
            }
            if (userAlreadyTalkedAbout(qqId, m)) {
                memories.markFollowUp(m.id(), "skipped");
                continue;
            }
            String text = compose(qqId, m, relation);
            if (text == null || text.isBlank() || containsForbidden(text)) {
                memories.markFollowUp(m.id(), "skipped");
                continue;
            }
            return Optional.of(new Recall(m.id(), relation ? TYPE_RELATION : TYPE_EVENT,
                    m.baseKey(), text, relation ? "relation" : "event"));
        }
        return Optional.empty();
    }

    public void markSent(Recall r) {
        memories.markFollowUp(r.memoryId(), "done");
    }

    public void markSkipped(Recall r) {
        memories.markFollowUp(r.memoryId(), "skipped");
    }

    /** 用户说「别问了」：该用户所有待主动回忆全部跳过 */
    public int skipAllPending(long qqId) {
        return memories.skipAllPending(qqId);
    }

    private String compose(long qqId, Memory m, boolean relation) {
        String situation = relation
                ? "你们之间有一件和你" + m.value() + "有关的事，现在自然地提一句，语气软一点，不要追问、不要翻旧账"
                : "对方之前说过「" + m.value() + "」，现在自然地关心一句后续，像朋友随口问的，别像客服回访";
        try {
            String text = composer.compose(qqId, situation, m.baseKey());
            if (text != null && !text.isBlank()) {
                if (containsForbidden(text)) {
                    // 宁可换成干净的兜底句，也不留「记忆/记录」字样
                    return fallback(m);
                }
                return sanitize(text);
            }
        } catch (Exception e) {
            log.warn("主动回忆文案生成失败 qqId={}: {}", qqId, e.getMessage());
        }
        return fallback(m);
    }

    /** 模型偶尔会写「我记得你说过」，这里兜底改写掉 */
    static String sanitize(String text) {
        String out = text;
        out = out.replace("我的记忆里", "").replace("根据记忆", "").replace("记忆里", "")
                .replace("我记得你说过", "之前你说").replace("我记录到", "你说过")
                .replace("记录", "").replace("记忆", "");
        out = out.replaceAll("\\s{2,}", " ").trim();
        return out;
    }

    static boolean containsForbidden(String text) {
        if (text == null) {
            return true;
        }
        for (String w : FORBIDDEN) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 用户最近 2 小时已经主动提过这个话题 → 不再提 */
    private boolean userAlreadyTalkedAbout(long qqId, Memory m) {
        String topic = m.baseKey();
        String value = m.value() == null ? "" : m.value();
        if (topic.isEmpty() && value.isEmpty()) {
            return false;
        }
        try {
            List<ChatMessage> recent = messages.recent(qqId, 6);
            for (ChatMessage c : recent) {
                if (!"user".equals(c.role()) || c.content() == null) {
                    continue;
                }
                if (overlap(c.content(), topic) || overlap(c.content(), value)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 查不到就按没提过处理
        }
        return false;
    }

    /** 中文二元组重合度 ≥0.5 视为同一话题 */
    private boolean overlap(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        java.util.Set<String> sa = bigrams(a);
        java.util.Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return false;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        return (double) inter.size() / sb.size() >= 0.5;
    }

    private java.util.Set<String> bigrams(String s) {
        String clean = s.replaceAll("[\\p{Punct}\\s，。！？、；：]", "");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i + 1 < clean.length(); i++) {
            set.add(clean.substring(i, i + 2));
        }
        return set;
    }

    /** 模型失败时的确定性兜底（同样不提"记忆"） */
    static String fallback(Memory m) {
        String k = (m.baseKey() + " " + (m.value() == null ? "" : m.value()));
        if (k.contains("加班") || k.contains("熬夜") || k.contains("忙到")) {
            return "昨晚又忙到那么晚，今天早点睡吧";
        }
        if (k.contains("面试") || k.contains("考试") || k.contains("笔试")) {
            return "昨天那场怎么样，还顺利吗";
        }
        if (k.contains("生病") || k.contains("不舒服") || k.contains("感冒")
                || k.contains("发烧") || k.contains("胃疼")) {
            return "今天好点没，别硬撑";
        }
        if (k.contains("吵架") || k.contains("闹别扭")) {
            return "还在生我气吗，我昨天说话不好听";
        }
        if (k.contains("冷战") || k.contains("没回")) {
            return "这两天是不是有点别扭，我先说句不好意思";
        }
        return "之前说的那件事，后来怎么样了";
    }

    static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), TS);
        } catch (Exception e) {
            return null;
        }
    }
}
