package com.qqbot.expression;

import com.qqbot.memory.SettingStore;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人格表达配置：说说、受众判断、好友自动分类。
 * 与主动消息配置一样持久化在 settings 表，改动立即生效。
 */
@Component
public class ExpressionConfig {

    private static final String K = "expression.";

    private final SettingStore store;

    public ExpressionConfig(SettingStore store) {
        this.store = store;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> qzone = new LinkedHashMap<>();
        qzone.put("enabled", bool("qzone.enabled", false));
        qzone.put("autoPost", bool("qzone.autoPost", false));
        qzone.put("audienceMode", str("qzone.audienceMode", "auto"));
        qzone.put("publicUgcRight", num("qzone.publicUgcRight", 4));
        qzone.put("maxPerDay", num("qzone.maxPerDay", 1));
        qzone.put("includeImages", bool("qzone.includeImages", false));
        qzone.put("autoDraftPerDay", num("qzone.autoDraftPerDay", 0));

        Map<String, Object> quiet = new LinkedHashMap<>();
        quiet.put("from", str("qzone.quiet.from", "23:30"));
        quiet.put("to", str("qzone.quiet.to", "08:00"));
        qzone.put("quiet", quiet);

        Map<String, Object> friends = new LinkedHashMap<>();
        friends.put("autoCategory", bool("friends.autoCategory", true));
        friends.put("refreshHours", num("friends.refreshHours", 6));
        friends.put("nativeSync", bool("friends.nativeSync", false));
        friends.put("closeThreshold", num("friends.closeThreshold", 100));
        friends.put("frequentThreshold", num("friends.frequentThreshold", 20));
        friends.put("quietDays", num("friends.quietDays", 30));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", bool("enabled", false));
        body.put("qzone", qzone);
        body.put("friends", friends);
        return body;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        if (body == null) {
            return snapshot();
        }
        if (body.get("enabled") instanceof Boolean b) {
            store.set(K + "enabled", String.valueOf(b));
        }
        nested(body, "qzone", "qzone.");
        nested(body, "friends", "friends.");
        if (body.get("qzone") instanceof Map<?, ?> qzone) {
            nested(qzone, "quiet", "qzone.quiet.");
        }
        return snapshot();
    }

    private void nested(Map<?, ?> body, String key, String prefix) {
        if (!(body.get(key) instanceof Map<?, ?> map)) {
            return;
        }
        for (String name : new String[]{"enabled", "autoPost", "includeImages", "autoCategory", "nativeSync"}) {
            if (map.get(name) instanceof Boolean b) {
                store.set(K + prefix + name, String.valueOf(b));
            }
        }
        for (String name : new String[]{"publicUgcRight", "maxPerDay", "refreshHours",
                "closeThreshold", "frequentThreshold", "quietDays", "autoDraftPerDay"}) {
            if (map.get(name) instanceof Number n) {
                store.set(K + prefix + name, String.valueOf(n.intValue()));
            }
        }
        if (map.get("audienceMode") instanceof String s && validAudience(s)) {
            store.set(K + prefix + "audienceMode", s);
        }
        if (map.get("from") instanceof String s) {
            store.set(K + prefix + "from", s);
        }
        if (map.get("to") instanceof String s) {
            store.set(K + prefix + "to", s);
        }
    }

    public boolean enabled() {
        return bool("enabled", false);
    }

    public boolean qzoneEnabled() {
        return enabled() && bool("qzone.enabled", false);
    }

    public boolean autoPost() {
        return qzoneEnabled() && bool("qzone.autoPost", false);
    }

    public String audienceMode() {
        return str("qzone.audienceMode", "auto");
    }

    public int publicUgcRight() {
        int value = num("qzone.publicUgcRight", 4);
        return value == 1 ? 1 : 4;
    }

    public int maxPerDay() {
        return Math.max(1, num("qzone.maxPerDay", 1));
    }

    /** 每天自动生成多少条说说草稿（0 = 不自动生成） */
    public int autoDraftPerDay() {
        return Math.max(0, Math.min(5, num("qzone.autoDraftPerDay", 0)));
    }

    public boolean includeImages() {
        return bool("qzone.includeImages", false);
    }

    public boolean friendAutoCategory() {
        return bool("friends.autoCategory", true);
    }

    public int friendRefreshHours() {
        return Math.max(1, num("friends.refreshHours", 6));
    }

    public boolean friendNativeSync() {
        return bool("friends.nativeSync", false);
    }

    public int closeThreshold() {
        return Math.max(1, num("friends.closeThreshold", 100));
    }

    public int frequentThreshold() {
        return Math.max(1, num("friends.frequentThreshold", 20));
    }

    public int quietDays() {
        return Math.max(1, num("friends.quietDays", 30));
    }

    public LocalTime quietFrom() {
        return quietValid() ? time("qzone.quiet.from", "23:30") : LocalTime.of(23, 30);
    }

    public LocalTime quietTo() {
        return quietValid() ? time("qzone.quiet.to", "08:00") : LocalTime.of(8, 0);
    }

    /** 免打扰窗口不能覆盖几乎一整天，否则等于永远不能发 */
    private boolean quietValid() {
        try {
            LocalTime from = time("qzone.quiet.from", "23:30");
            LocalTime to = time("qzone.quiet.to", "08:00");
            int minutes = ((to.toSecondOfDay() - from.toSecondOfDay() + 24 * 3600) % (24 * 3600)) / 60;
            return minutes > 0 && minutes <= 20 * 60;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validAudience(String value) {
        return "auto".equals(value) || "public".equals(value) || "targeted".equals(value)
                || "private".equals(value);
    }

    private boolean bool(String key, boolean fallback) {
        return Boolean.parseBoolean(store.get(K + key, String.valueOf(fallback)));
    }

    private String str(String key, String fallback) {
        return store.get(K + key, fallback);
    }

    private int num(String key, int fallback) {
        try {
            return Integer.parseInt(store.get(K + key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private LocalTime time(String key, String fallback) {
        try {
            return LocalTime.parse(store.get(K + key, fallback));
        } catch (Exception e) {
            return LocalTime.parse(fallback);
        }
    }
}
