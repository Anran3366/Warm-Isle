package com.qqbot.memory;

import com.qqbot.bot.BotAccountRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 记忆 v2 启动维护：把各账号库里的 facts 迁进 memories。
 *
 * <p>facts 保留只读（不再由 v2 写入），迁移是幂等的：同 type + key 已有
 * active 记忆就跳过，所以每次启动都跑一遍也没问题。
 */
@Component
public class MemoryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MemoryBootstrap.class);

    private final BotAccountRunner accountRunner;
    private final MessageStore messages;
    private final MemoryStore memoryStore;

    public MemoryBootstrap(BotAccountRunner accountRunner, MessageStore messages, MemoryStore memoryStore) {
        this.accountRunner = accountRunner;
        this.messages = messages;
        this.memoryStore = memoryStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAll() {
        try {
            accountRunner.forEach(botId -> {
                int written = 0;
                int users = 0;
                for (Long qqId : messages.users()) {
                    try {
                        written += memoryStore.migrateFromFacts(qqId);
                        users++;
                    } catch (Exception e) {
                        log.warn("迁移失败 botId={} qqId={}: {}", botId, qqId, e.getMessage());
                    }
                }
                log.info("记忆迁移完成 botId={} 用户数={} 新写入={}", botId, users, written);
            });
        } catch (Exception e) {
            log.warn("启动迁移跳过: {}", e.getMessage());
        }
    }
}
