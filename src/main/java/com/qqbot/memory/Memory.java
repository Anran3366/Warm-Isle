package com.qqbot.memory;

/**
 * 一条 v2 记忆。
 *
 * <p>type：profile（长期属性）/ event（有时间的经历）/ relation（你们之间）/ world（文档设定）。
 * subject：user / bot / third:名字。key 归一化主题，value 具体内容。
 * importance 取「可行动性」评分：能不能支撑一次关心、追问或延续话题。
 *
 * <p>strength 是「现在的记忆强度」（0~1）：写入/复习时回到 1.0，之后按
 * {@link MemoryStrength} 的遗忘曲线连续衰减；pinned=1 的记忆永不衰减。
 * recurMd 是每年的周期日（MM-DD，生日等），到那天由主动消息祝贺。
 */
public record Memory(long id, long qqId, String type, String subject, String key, String value,
                     String source, double importance, double confidence, double emotion,
                     String firstSeen, String lastConfirmed, String expiresAt,
                     String followUpAt, String followUpState, String actionHint,
                     String status, Long supersededBy,
                     double strength, String strengthAt, boolean pinned,
                     String recurMd, String recurLast, int recallCount) {

    /** 归一化后的 key（去掉事件后缀 @yyyy-MM-dd，用于展示/去重） */
    public String baseKey() {
        if (key == null) {
            return "";
        }
        int at = key.indexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }
}
