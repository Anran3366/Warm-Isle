package com.qqbot.plugin.registry;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 能力清单（设计文档 §8.1）：只在「对方问你会什么」或刚启用时注入，普通闲聊 0 token。
 */
public final class CapabilityCatalog {

    public static final int MAX_ITEMS = 8;
    public static final int MAX_CHARS = 300;

    /** 「你会什么 / 会不会唱歌 / 有什么本领」——用正则判定，避免白付 token */
    private static final Pattern ASK = Pattern.compile(
            "会.{0,6}吗|能.{0,4}什么|有什么(本领|本事|技能)|会什么|还会(什么|啥)|你会(什么|啥)");

    private CapabilityCatalog() {
    }

    public static boolean asksCapability(String text) {
        return text != null && !text.isBlank() && ASK.matcher(text).find();
    }

    /** 生成注入 prompt 的极短清单；没有可用插件时返回空串（prompt 与改造前一致） */
    public static String buildText(List<InstalledPlugin> active) {
        if (active == null || active.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【你会的能力】（只在对方明确需要时使用，不要主动炫耀，也不要说\"我是插件\"）\n");
        int chars = 0;
        int items = 0;
        for (InstalledPlugin p : active) {
            if (items >= MAX_ITEMS || chars >= MAX_CHARS) {
                break;
            }
            String name = p.manifest().name() == null ? p.pluginId() : p.manifest().name();
            String intent = p.manifest().intent() == null ? "" : p.manifest().intent().id();
            String hint = usageHint(intent, name);
            String line = "- " + name + "（" + intent + "）：" + hint + "\n";
            if (chars + line.length() > MAX_CHARS) {
                break;
            }
            sb.append(line);
            chars += line.length();
            items++;
        }
        return sb.toString().trim();
    }

    private static String usageHint(String intent, String name) {
        if (intent == null) {
            return "对方需要时直接用";
        }
        return switch (intent) {
            case "music.sing" -> "对方想听歌、点歌、\"来一首\"时，直接唱，不要说\"我不能发语音\"";
            case "music.play" -> "对方让你放首歌时，直接放，不要解释";
            case "divination.tarot", "divination.fortune" -> "对方想算牌、问运势时";
            case "info.weather" -> "对方问天气时";
            case "story.tell" -> "对方想听故事、要哄睡时";
            default -> "对方明确需要「" + name + "」时";
        };
    }
}
