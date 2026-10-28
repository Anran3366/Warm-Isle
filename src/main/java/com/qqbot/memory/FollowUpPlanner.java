package com.qqbot.memory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主动回忆时间规划（设计稿第 4 节）。
 *
 * <p>夜间整理在 00:05 跑，处理的是「昨天」的消息，所以事件日 T 的「次日」
 * 就是整理当天 D（T+1）。这里统一以 nightlyAt（整理时刻）为基准计算：
 * 加班 → D 21:00–22:30；面试/考试 → D 19:00–21:00；生病 → D 10:00；
 * 吵架 → D+1 20:00；冷战 → D 20:00；约定 → 到点 20:00。
 */
public final class FollowUpPlanner {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FollowUpPlanner() {
    }

    /** @param at 计划发送时间；@param hint 给文案模型的行动提示 */
    public record Plan(LocalDateTime at, String hint) {
        public String atText() {
            return at.format(TS);
        }
    }

    /**
     * @param nightlyAt 整理时刻（通常是当天 00:05）
     * @param type      记忆类型
     * @param kind      模型给的事件类别（overtime/interview/sick/fight/coldwar/promise/other）
     * @param text      记忆内容，用于兜底识别类别与解析「明天/周末」
     */
    public static Plan plan(LocalDateTime nightlyAt, String type, String kind, String text) {
        String k = normalize(kind, type, text);
        LocalDate d = nightlyAt.toLocalDate();
        return switch (k) {
            case "overtime" -> new Plan(at(d, 21, 0, 90), "对方昨天加班/熬夜，问一句今天有没有早点歇");
            case "interview" -> new Plan(at(d, 19, 0, 120), "对方昨天面试/考试，问问结果和感受");
            case "sick" -> new Plan(at(d, 10, 0, 0), "对方昨天不舒服，上午问一句好点没");
            case "fight" -> new Plan(at(d.plusDays(1), 20, 0, 0), "前两天有过不愉快，主动软化，不翻旧账");
            case "coldwar" -> new Plan(at(d, 20, 0, 0), "你们有点冷战，主动服个软");
            case "promise" -> new Plan(promiseDate(d, text).atTime(20, 0), "之前约好的事，到点了提一句");
            default -> null;
        };
    }

    /** 类别归一化：模型给的 kind 优先，缺失时按关键词猜 */
    static String normalize(String kind, String type, String text) {
        String raw = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            raw = inferKind(type, text);
        }
        return switch (raw) {
            case "overtime", "加班", "熬夜" -> "overtime";
            case "interview", "exam", "面试", "考试" -> "interview";
            case "sick", "ill", "生病", "不舒服", "感冒", "发烧", "胃疼", "生理期" -> "sick";
            case "fight", "quarrel", "吵架", "情绪低落", "难过", "委屈" -> "fight";
            case "coldwar", "冷战", "没回" -> "coldwar";
            case "promise", "约定", "答应" -> "promise";
            default -> "other";
        };
    }

    static String inferKind(String type, String text) {
        String t = text == null ? "" : text;
        if (containsAny(t, "加班", "熬夜", "通宵", "忙到")) {
            return "overtime";
        }
        if (containsAny(t, "面试", "考试", "笔试", "复试", "考研")) {
            return "interview";
        }
        if (containsAny(t, "生病", "不舒服", "感冒", "发烧", "胃疼", "生理期", "头疼")) {
            return "sick";
        }
        if (containsAny(t, "吵架", "吵了", "闹别扭", "难过", "委屈", "低落")) {
            return "fight";
        }
        if (containsAny(t, "冷战", "没理", "没回")) {
            return "coldwar";
        }
        if ("relation".equals(type) && containsAny(t, "约定", "答应", "说好", "周末")) {
            return "promise";
        }
        return "other";
    }

    /** 约定类：解析「明天/后天/周末/周X」，默认明天 */
    static LocalDate promiseDate(LocalDate d, String text) {
        String t = text == null ? "" : text;
        if (t.contains("后天")) {
            return d.plusDays(2);
        }
        if (t.contains("明天")) {
            return d.plusDays(1);
        }
        if (t.contains("周末") || t.contains("周六") || t.contains("星期六")) {
            return nextWeekday(d, DayOfWeek.SATURDAY);
        }
        if (t.contains("周日") || t.contains("星期天") || t.contains("星期日")) {
            return nextWeekday(d, DayOfWeek.SUNDAY);
        }
        String[] names = {"一", "二", "三", "四", "五"};
        for (int i = 0; i < names.length; i++) {
            if (t.contains("周" + names[i]) || t.contains("星期" + names[i])) {
                return nextWeekday(d, DayOfWeek.of(i + 1));
            }
        }
        return d.plusDays(1);
    }

    private static LocalDate nextWeekday(LocalDate d, DayOfWeek target) {
        LocalDate x = d.plusDays(1);
        while (x.getDayOfWeek() != target) {
            x = x.plusDays(1);
        }
        return x;
    }

    private static LocalDateTime at(LocalDate d, int hour, int min, int spreadMinutes) {
        LocalTime t = LocalTime.of(hour, min);
        if (spreadMinutes > 0) {
            t = t.plusMinutes(ThreadLocalRandom.current().nextInt(spreadMinutes + 1));
        }
        return LocalDateTime.of(d, t.withSecond(0).withNano(0));
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
