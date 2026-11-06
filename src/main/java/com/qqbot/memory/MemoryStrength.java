package com.qqbot.memory;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 记忆强度的连续遗忘曲线。
 *
 * <p>人忘东西不是「到第 90 天突然清零」，而是一条指数曲线：刚说完最清楚（1.0），
 * 之后按类型不同的半衰期往下走。这里用
 *
 * <pre>strength(t) = strength₀ · exp(−Δt / τ)</pre>
 *
 * <p>τ 按记忆类型给底数，再按重要性放大：随口一句的陌生闲聊（importance≈0.4、event/profile）
 * 大约 4~5 天就「忘了」；而高重要性的长期属性、关系记忆忘得慢得多。
 *
 * <p>三档状态：{@code strength ≥ FADING} 正常；{@code FADING > strength ≥ FORGET} 变暗（快忘了）；
 * {@code < FORGET} 归档（忘掉）。{@code pinned} 的记忆（生日这类「不能忘」）不参与衰减。
 */
public final class MemoryStrength {

    /** 低于这个强度就算忘了：归档，不再参与检索。 */
    public static final double FORGET = 0.15;

    /** 低于这个强度开始「变暗」：还能想起来，但排位靠后、星图上发灰。 */
    public static final double FADING = 0.35;

    /** 被检索命中（被想起）时的强化幅度：只回一点点，不是复习。 */
    public static final double RECALL_BUMP = 0.06;

    private MemoryStrength() {
    }

    /**
     * 遗忘时间常数（天）。数值是手算校准的：importance=0.4 的 event 约 2.2 天减到 1/e，
     * 也就是 4 天出头跌破 {@link #FORGET}，对应「陌生人的一两句话，三五天就忘了」。
     */
    public static double tauDays(String type, double importance) {
        double base = switch (type == null ? "" : type) {
            case "event" -> 2.2;
            case "relation" -> 5.0;
            case "world" -> 12.0;
            default -> 2.4; // profile：长期属性，比事件稳一点
        };
        double weight = 0.4 + 1.5 * clamp(importance);
        return Math.max(0.5, base * weight);
    }

    /** 当前强度：钉住的是 1.0，否则按 strength_at 起算做指数衰减。 */
    public static double effective(Memory m, LocalDateTime now) {
        if (m.pinned()) {
            return 1.0;
        }
        return effective(m.strength(), m.strengthAt(), m.type(), m.importance(), now);
    }

    public static double effective(double strength, String strengthAt, String type,
                                   double importance, LocalDateTime now) {
        double s = clamp(strength <= 0 ? 1.0 : strength);
        LocalDateTime at = MemoryStore.parse(strengthAt);
        if (at == null || now == null) {
            return s;
        }
        long minutes = Duration.between(at, now).toMinutes();
        if (minutes <= 0) {
            return s;
        }
        double days = minutes / 1440.0;
        return clamp(s * Math.exp(-days / tauDays(type, importance)));
    }

    /**
     * 被动想起：只回一点点（还有 60% 的空间可涨），避免「只要被检索到就永远满格」。
     */
    public static double onRecall(double current) {
        double s = clamp(current);
        return clamp(s + RECALL_BUMP * (1 - s));
    }

    /** 复习（对方又提起同一件事）：直接回到 1.0，对应「周六又说了一次，记忆回满」。 */
    public static double afterRehearsal() {
        return 1.0;
    }

    public static boolean forgotten(double effective) {
        return effective < FORGET;
    }

    public static boolean fading(double effective) {
        return effective < FADING;
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
