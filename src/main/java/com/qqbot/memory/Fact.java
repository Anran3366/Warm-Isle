package com.qqbot.memory;

/** 一条事实卡（关于某个用户的一条记忆） */
public record Fact(long id, long qqId, String category, String key, String value,
                   double confidence, String status, String lastConfirmed) {
}
