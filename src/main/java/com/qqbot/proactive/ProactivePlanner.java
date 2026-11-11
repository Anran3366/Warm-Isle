package com.qqbot.proactive;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 主动消息的纯计算部分：延迟、活跃窗口、概率、退避天数、气泡切分、索取感检测。
 * 全部是静态方法，方便单元测试（可传固定 Random 与时间）。
 */
public final class ProactivePlanner {

    /** 静默多久主动找你：对数正态，均值≈median，夹在 1.5–9 小时 */
    public static long silenceDelayMinutes(double medianMinutes, Random r) {
        double sigma = 0.45;
        double gauss = r.nextGaussian();
        double minutes = medianMinutes * Math.exp(sigma * gauss);
        return Math.round(Math.max(90, Math.min(540, minutes)));
    }

    public static boolean inWindow(LocalDateTime now, LocalTime from, LocalTime to) {
        LocalTime t = now.toLocalTime();
        if (from.equals(to)) {
            return false;
        }
        if (from.isBefore(to)) {
            return !t.isBefore(from) && t.isBefore(to);
        }
        // 跨天窗口
        return !t.isBefore(from) || t.isBefore(to);
    }

    /** 把时间对齐到下一个活跃窗口；已经在窗口内则原样返回 */
    public static LocalDateTime alignToWindow(LocalDateTime t, LocalTime from, LocalTime to) {
        if (inWindow(t, from, to)) {
            return t;
        }
        LocalDateTime sameDay = t.with(from);
        if (sameDay.isAfter(t)) {
            return sameDay;
        }
        return sameDay.plusDays(1);
    }

    public static LocalDateTime nextDayWindow(LocalDateTime now, LocalTime from) {
        return now.plusDays(1).with(from);
    }

    public static boolean roll(double probability, Random r) {
        return r.nextDouble() < probability;
    }

    /**
     * 退避天数：第 0 次 2–5 天，第 1 次 7–14 天，第 2 次 21–30 天，之后不再加长。
     */
    public static int backoffDays(int stage, int minDays, int maxDays, Random r) {
        int base;
        int span;
        switch (stage) {
            case 0 -> {
                base = Math.max(1, minDays);
                span = Math.max(1, maxDays - minDays);
            }
            case 1 -> {
                base = 7;
                span = 7;
            }
            default -> {
                base = 21;
                span = 9;
            }
        }
        return base + r.nextInt(span + 1);
    }

    /** 把一段话拆成 1–3 条短气泡，尽量在标点处断开 */
    public static List<String> splitBubbles(String text, int maxBubbles) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String cleaned = text.trim().replaceAll("\\s+", " ");
        String[] parts = cleaned.split("(?<=[。！？!?…~])");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + p.length() > 30) {
                out.add(current.toString());
                current.setLength(0);
                if (out.size() >= maxBubbles) {
                    return out;
                }
            }
            current.append(p);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        if (out.size() > maxBubbles) {
            out = new ArrayList<>(out.subList(0, maxBubbles));
        }
        List<String> result = new ArrayList<>();
        for (String s : out) {
            result.add(s.length() > 40 ? s.substring(0, 40) : s);
        }
        return result;
    }

    /** 索取感 / 客服腔 / 指责词的粗略检测 */
    public static boolean hasNeediness(String text) {
        if (text == null) {
            return false;
        }
        String[] bad = {"怎么不回", "又不理", "每次", "等你半天", "你是不是", "在干嘛",
                "忙吗", "记得吃饭", "希望你", "作为AI", "首先", "其次"};
        for (String b : bad) {
            if (text.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** 用户说「去洗澡/吃饭/忙」这类短时离开 */
    public static String activityHint(String text) {
        if (text == null) {
            return null;
        }
        if (text.contains("洗澡") || text.contains("洗个澡")) {
            return "洗澡";
        }
        if (text.contains("吃饭") || text.contains("吃点东西")) {
            return "吃饭";
        }
        if (text.contains("开会") || text.contains("忙") || text.contains("加班")) {
            return "忙";
        }
        if (text.contains("睡觉") || text.contains("睡了") || text.contains("晚安")) {
            return "睡觉";
        }
        return null;
    }

    /** 用户明确说别发了 */
    public static boolean asksStop(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("别发") || text.contains("不要发") || text.contains("别主动")
                || text.contains("别再找我") || text.contains("烦不烦");
    }

    /** 「忙/没空」但没到别发：1–3 小时后再考虑 */
    public static boolean asksBusy(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("在忙") || text.contains("没空") || text.contains("等会聊")
                || text.contains("回头聊") || text.contains("先不聊");
    }
}
