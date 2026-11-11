package com.qqbot.plugin.routing;

import com.qqbot.plugin.registry.InstalledPlugin;

import java.util.Map;

/** 路由结论：不触发 / 直接执行 / 参数不全需要反问 */
public record RouteDecision(Kind kind, InstalledPlugin plugin, String action,
                            Map<String, String> params, double score, String hint) {

    public enum Kind {NONE, EXECUTE, FILL_PARAMS}

    public static RouteDecision none() {
        return new RouteDecision(Kind.NONE, null, null, Map.of(), 0, null);
    }

    public boolean handled() {
        return kind != Kind.NONE;
    }
}
