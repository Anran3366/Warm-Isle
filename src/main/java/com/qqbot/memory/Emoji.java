package com.qqbot.memory;

/**
 * 表情包素材。
 * description 是给模型看的一句话描述；status=pending 表示自动入库、待人工确认。
 */
public record Emoji(long id, String path, String emoTags, String sceneTags,
                    String description, String status, int sentCount, String lastSentAt,
                    boolean enabled, String createdAt) {

    public boolean approved() {
        return status == null || status.isBlank() || "approved".equals(status);
    }

    /** 第一个情绪标签，用于「同情绪不连发」 */
    public String primaryEmotion() {
        if (emoTags == null || emoTags.isBlank()) {
            return "";
        }
        return emoTags.split("[,，\\s]+")[0];
    }
}
