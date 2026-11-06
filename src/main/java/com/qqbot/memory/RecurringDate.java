package com.qqbot.memory;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从记忆文本里认出「每年都会再来一次」的日期（目前主要给生日用）。
 *
 * <p>认这几种写法：{@code 10月26日}、{@code 10月26号}、{@code 10-26}、{@code 10/26}、
 * {@code 生日是10月26}。只返回 MM-DD，不带年份——生日是周期事件，年份没意义。
 */
public final class RecurringDate {

    private static final Pattern CN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final String[] BIRTH_HINTS = {"生日", "出生", "诞辰"};

    private RecurringDate() {
    }

    /** 文本里有没有「生日」这类关键词。 */
    public static boolean looksBirthday(String key, String value) {
        String all = (key == null ? "" : key) + " " + (value == null ? "" : value);
        for (String hint : BIRTH_HINTS) {
            if (all.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 抽出 MM-DD；认不出来返回 null。 */
    public static String monthDay(String key, String value) {
        String all = (key == null ? "" : key) + " " + (value == null ? "" : value);
        String md = firstMonthDay(all);
        return md;
    }

    static String firstMonthDay(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher cn = CN.matcher(text);
        if (cn.find()) {
            String md = normalize(cn.group(1), cn.group(2));
            if (md != null) {
                return md;
            }
        }
        Matcher iso = ISO.matcher(text);
        if (iso.find()) {
            String md = normalize(iso.group(1), iso.group(2));
            if (md != null) {
                return md;
            }
        }
        return null;
    }

    private static String normalize(String month, String day) {
        try {
            int m = Integer.parseInt(month);
            int d = Integer.parseInt(day);
            if (m < 1 || m > 12 || d < 1 || d > 31) {
                return null;
            }
            // 2 月 30 日这种不存在的日期直接放过，避免 2 月天天祝贺
            if (d > LocalDate.of(2024, m, 1).lengthOfMonth()) {
                return null;
            }
            return String.format("%02d-%02d", m, d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 今天是不是这个周期日。 */
    public static boolean isToday(String md, LocalDate today) {
        return md != null && md.equals(String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth()));
    }
}
