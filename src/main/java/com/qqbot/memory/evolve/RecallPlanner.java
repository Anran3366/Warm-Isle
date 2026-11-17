package com.qqbot.memory.evolve;

import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryRecall;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.SettingStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话前的记忆编排：在原来的「相关记忆」之外，补四类东西——
 * 常驻印象、今天是个日子、可以自然问一句的跟进、偶然想起的旧事、以及她以前的说法。
 *
 * <p>所有附加段总长 ≤300 字，避免 prompt 变长反而稀释重点。</p>
 */
@Service
public class RecallPlanner {

    private static final String ENABLED_KEY = "memory.evolve.enabled";
    private static final int EXTRA_BUDGET = 300;

    private final MemoryRecall recall;
    private final MemoryStore memories;
    private final ProfileSnapshotService profile;
    private final ExpressionService expressions;
    private final SettingStore settings;

    public RecallPlanner(MemoryRecall recall, MemoryStore memories, ProfileSnapshotService profile,
                         ExpressionService expressions, SettingStore settings) {
        this.recall = recall;
        this.memories = memories;
        this.profile = profile;
        this.expressions = expressions;
        this.settings = settings;
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.get(ENABLED_KEY, "true"));
    }

    /** 给 PromptBuilder 用的一整块；关闭时退回原来的检索块。 */
    public String planBlock(long qqId, String userText) {
        if (!enabled()) {
            return recall.promptBlock(qqId, userText);
        }
        List<String> extras = new ArrayList<>();

        String l0 = profile.snapshot(qqId);
        if (l0 != null && !l0.isBlank()) {
            extras.add("【常驻印象】" + l0);
        }

        try {
            List<Memory> anniversaries = memories.anniversaries(qqId, LocalDate.now());
            if (!anniversaries.isEmpty()) {
                extras.add("【可以自然带一句】今天是个日子："
                        + cut(anniversaries.get(0).value(), 40));
            }
        } catch (Exception ignored) {
            // 纪念日失败不影响回复
        }

        try {
            List<Memory> followUps = memories.dueFollowUps(qqId, LocalDateTime.now());
            if (!followUps.isEmpty()) {
                extras.add("【可以自然问一句】- " + cut(followUps.get(0).value(), 50));
            }
        } catch (Exception ignored) {
            // 跟进失败不影响回复
        }

        // 没有相关记忆、又是短句/问候时，补一条「忽然想起」的旧事
        if (shortGreeting(userText)) {
            try {
                boolean hasRelevant = !recall.recall(qqId, userText, 1).isEmpty();
                if (!hasRelevant) {
                    Memory pick = mostEmotional(memories.recentEvents(qqId, 14, 6));
                    if (pick != null && (l0 == null || pick.value() == null
                            || !l0.contains(cut(pick.value(), 20)))) {
                        extras.add("【可以自然带一句】你忽然想起："
                                + cut(pick.key() + "：" + pick.value(), 60));
                    }
                }
            } catch (Exception ignored) {
                // 联想失败不影响回复
            }
        }

        try {
            String expressionsBlock = expressions.block(qqId, userText);
            if (expressionsBlock != null && !expressionsBlock.isBlank()) {
                extras.add(expressionsBlock.trim());
            }
        } catch (Exception ignored) {
            // 表达方式失败不影响回复
        }

        return recall.promptBlock(qqId, userText, cap(extras, EXTRA_BUDGET));
    }

    private static Memory mostEmotional(List<Memory> list) {
        Memory best = null;
        double bestScore = 0;
        for (Memory m : list) {
            if (m.value() == null || m.value().isBlank() || m.value().startsWith("不要叫")) {
                continue;
            }
            double score = Math.abs(m.emotion()) + m.importance() * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    private static boolean shortGreeting(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() <= 6) {
            return true;
        }
        return t.contains("在吗") || t.contains("早安") || t.contains("晚安") || t.contains("干嘛");
    }

    private static List<String> cap(List<String> items, int max) {
        List<String> out = new ArrayList<>();
        int chars = 0;
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            if (chars + item.length() > max) {
                break;
            }
            out.add(item);
            chars += item.length();
        }
        return out;
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
