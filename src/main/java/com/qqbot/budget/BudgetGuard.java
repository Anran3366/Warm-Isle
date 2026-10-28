package com.qqbot.budget;

import com.qqbot.config.BotProperties;
import com.qqbot.memory.UsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 每日预算闸门：花费达到 dailyBudget 后，所有模型调用直接拒绝。
 * dailyBudget <= 0 表示不限制。
 */
@Service
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final BotProperties props;
    private final UsageStore usage;

    public BudgetGuard(BotProperties props, UsageStore usage) {
        this.props = props;
        this.usage = usage;
    }

    public double budget() {
        return props.getAdmin().getDailyBudget();
    }

    public double spentToday() {
        return usage.todayCost();
    }

    public boolean exceeded() {
        double budget = budget();
        if (budget <= 0) {
            return false;
        }
        boolean over = spentToday() >= budget;
        if (over) {
            log.warn("今日预算已用尽: {} / {} 元，暂停模型调用", spentToday(), budget);
        }
        return over;
    }

    /** 给用户的兜底话术 */
    public String fallbackMessage() {
        return "我这会儿有点卡，等下再回你哈";
    }
}
