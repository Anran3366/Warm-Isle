package com.qqbot.llm;

/** 当日预算用尽时抛出，调用方据此回退到不花钱的路径。 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
