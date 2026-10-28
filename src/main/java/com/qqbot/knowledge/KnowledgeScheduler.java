package com.qqbot.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 00:00 处理知识库队列（00:00–06:00 窗口）。
 * 处理内容：解析 → 清洗 → 切片 → 自动分类 → 入库。
 */
@Component
public class KnowledgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeScheduler.class);

    private final KnowledgeService service;

    public KnowledgeScheduler(KnowledgeService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void nightly() {
        log.info("知识库定时处理开始");
        var result = service.processPending(true);
        log.info("知识库定时处理结束: {}", result);
    }
}
