package com.qqbot.proactive;

import com.qqbot.memory.SettingStore;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主动消息的可调配置，持久化在 settings 表（push.*）。
 * 默认值与之前写死的常量一致，后台改完立即生效。
 */
@Component
public class PushConfig {

    private static final String K = "push.";

    private final SettingStore store;

    public PushConfig(SettingStore store) {
        this.store = store;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> morning = new LinkedHashMap<>();
        morning.put("enabled", bool("morning.enabled", true));
        morning.put("from", str("morning.from", "08:30"));
        morning.put("to", str("morning.to", "09:30"));

        Map<String, Object> evening = new LinkedHashMap<>();
        evening.put("enabled", bool("evening.enabled", true));
        evening.put("from", str("evening.from", "22:30"));
        evening.put("to", str("evening.to", "23:25"));

        Map<String, Object> quiet = new LinkedHashMap<>();
        quiet.put("from", str("quiet.from", "23:30"));
        quiet.put("to", str("quiet.to", "08:00"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", bool("enabled", true));
        body.put("morning", morning);
        body.put("evening", evening);
        body.put("idleHours", num("idleHours", 6));
        body.put("nudgeAfterHours", num("nudgeAfterHours", 6));
        body.put("suspendAfterHours", num("suspendAfterHours", 12));
        body.put("dailyLimit", num("dailyLimit", 3));
        body.put("nudgeProbability", dbl("nudgeProbability", 0.4));
        body.put("nudgeMinHours", num("nudgeMinHours", 2));
        body.put("nudgeMaxHours", num("nudgeMaxHours", 5));
        body.put("backoffMinDays", num("backoffMinDays", 2));
        body.put("backoffMaxDays", num("backoffMaxDays", 5));
        body.put("reentryMinDays", num("reentryMinDays", 2));
        body.put("reentryMaxDays", num("reentryMaxDays", 5));
        body.put("morningProbability", dbl("morningProbability", 0.75));
        body.put("eveningProbability", dbl("eveningProbability", 0.75));
        body.put("lowCostChance", dbl("lowCostChance", 0.4));
        body.put("maxBubbles", num("maxBubbles", 2));
        body.put("quiet", quiet);
        return body;
    }

    /** 部分更新，返回更新后的完整配置 */
    public Map<String, Object> update(Map<String, Object> body) {
        if (body == null) {
            return snapshot();
        }
        if (body.get("enabled") instanceof Boolean b) {
            store.set(K + "enabled", String.valueOf(b));
        }
        if (body.get("idleHours") instanceof Number n) {
            store.set(K + "idleHours", String.valueOf(n.intValue()));
        }
        if (body.get("nudgeAfterHours") instanceof Number n) {
            store.set(K + "nudgeAfterHours", String.valueOf(n.intValue()));
        }
        if (body.get("suspendAfterHours") instanceof Number n) {
            store.set(K + "suspendAfterHours", String.valueOf(n.intValue()));
        }
        if (body.get("dailyLimit") instanceof Number n) {
            store.set(K + "dailyLimit", String.valueOf(n.intValue()));
        }
        for (String key : new String[]{"nudgeProbability", "morningProbability", "eveningProbability", "lowCostChance"}) {
            if (body.get(key) instanceof Number n) {
                store.set(K + key, String.valueOf(n.doubleValue()));
            }
        }
        for (String key : new String[]{"nudgeMinHours", "nudgeMaxHours", "backoffMinDays", "backoffMaxDays",
                "reentryMinDays", "reentryMaxDays", "maxBubbles"}) {
            if (body.get(key) instanceof Number n) {
                store.set(K + key, String.valueOf(n.intValue()));
            }
        }
        nested(body, "morning", "morning.");
        nested(body, "evening", "evening.");
        nested(body, "quiet", "quiet.");
        return snapshot();
    }

    private void nested(Map<String, Object> body, String key, String prefix) {
        if (!(body.get(key) instanceof Map<?, ?> map)) {
            return;
        }
        if (map.get("enabled") instanceof Boolean b) {
            store.set(K + prefix + "enabled", String.valueOf(b));
        }
        if (map.get("from") instanceof String s) {
            store.set(K + prefix + "from", s);
        }
        if (map.get("to") instanceof String s) {
            store.set(K + prefix + "to", s);
        }
    }

    // ---------- 供调度使用 ----------

    public boolean enabled() {
        return bool("enabled", true);
    }

    public boolean morningEnabled() {
        return bool("morning.enabled", true);
    }

    public boolean eveningEnabled() {
        return bool("evening.enabled", true);
    }

    public LocalTime morningFrom() {
        return time("morning.from", "08:30");
    }

    public LocalTime morningTo() {
        return time("morning.to", "09:30");
    }

    public LocalTime eveningFrom() {
        return time("evening.from", "22:30");
    }

    public LocalTime eveningTo() {
        return time("evening.to", "23:25");
    }

    public LocalTime quietFrom() {
        return time("quiet.from", "23:30");
    }

    public LocalTime quietTo() {
        return time("quiet.to", "08:00");
    }

    public int idleHours() {
        return num("idleHours", 6);
    }

    public int nudgeAfterHours() {
        return num("nudgeAfterHours", 6);
    }

    public int suspendAfterHours() {
        return num("suspendAfterHours", 12);
    }

    public int dailyLimit() {
        return num("dailyLimit", 3);
    }

    public double nudgeProbability() {
        return dbl("nudgeProbability", 0.4);
    }

    public int nudgeMinHours() {
        return num("nudgeMinHours", 2);
    }

    public int nudgeMaxHours() {
        return num("nudgeMaxHours", 5);
    }

    public int backoffMinDays() {
        return num("backoffMinDays", 2);
    }

    public int backoffMaxDays() {
        return num("backoffMaxDays", 5);
    }

    public int reentryMinDays() {
        return num("reentryMinDays", 2);
    }

    public int reentryMaxDays() {
        return num("reentryMaxDays", 5);
    }

    public double morningProbability() {
        return dbl("morningProbability", 0.75);
    }

    public double eveningProbability() {
        return dbl("eveningProbability", 0.75);
    }

    public double lowCostChance() {
        return dbl("lowCostChance", 0.4);
    }

    public int maxBubbles() {
        return Math.max(1, Math.min(3, num("maxBubbles", 2)));
    }

    private double dbl(String key, double fallback) {
        try {
            return Double.parseDouble(store.get(K + key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
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
