package com.qqbot.proactive;

/**
 * 某个用户的主动消息状态。
 * 旧字段（suspended/grievanceLevel/teaseNext）保留，用于兼容旧接口和前端。
 */
public record ProactiveState(long qqId,
                             String lastUserAt,
                             String lastProactiveAt,
                             int unansweredCount,
                             boolean suspended,
                             int grievanceLevel,
                             boolean teaseNext,
                             ProactiveMode mode,
                             String nextEligibleAt,
                             String dndUntil,
                             String mood,
                             String moodUntil,
                             int reentryStage,
                             String activityHint,
                             String activityUntil) {

    public ProactiveMode modeOr(ProactiveMode fallback) {
        return mode == null ? fallback : mode;
    }
}
