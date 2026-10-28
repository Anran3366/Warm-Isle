package com.qqbot.llm;

/** 一次补全的结果：正文 + 用量 */
public record LlmResult(String content, int promptTokens, int completionTokens) {
}
