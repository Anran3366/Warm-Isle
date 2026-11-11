package com.qqbot.plugin.exec;

import java.util.Map;

/** 插件执行结果（宿主统一格式，作者看不到这个类） */
public record PluginOutput(String kind, String mime, byte[] bytes, String text,
                           long durationMs, String caption, Map<String, Object> memoryVars) {

    public static PluginOutput audio(byte[] bytes, String mime, long durationMs, String caption,
                                     Map<String, Object> memoryVars) {
        return new PluginOutput("audio", mime, bytes, null, durationMs, caption, memoryVars);
    }

    public static PluginOutput image(byte[] bytes, String mime, String caption) {
        return new PluginOutput("image", mime, bytes, null, 0, caption, Map.of());
    }

    public static PluginOutput text(String text, String caption, Map<String, Object> memoryVars) {
        return new PluginOutput("text", "text/plain", null, text, 0, caption, memoryVars);
    }
}
