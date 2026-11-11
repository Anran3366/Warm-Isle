package com.qqbot.memory;

import java.util.List;

/** 带 id/kind 的消息行，供后台展示 */
public record MessageItem(long id, long qqId, String role, String content, String kind, Long emojiId, String voiceFile, String createdAt) {
    public static List<MessageItem> empty() {
        return List.of();
    }
}
