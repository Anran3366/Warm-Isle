package com.qqbot.proactive;

/** 一条跟进事项（例如「感冒了，明天问问」） */
public record Followup(long id, long qqId, String topic, String detail,
                       String dueAt, String status, String sentAt) {
}
