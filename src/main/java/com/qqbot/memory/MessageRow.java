package com.qqbot.memory;

/** 带时间戳的消息行，供摘要/事实抽取使用 */
public record MessageRow(String createdAt, String role, String content) {
}
