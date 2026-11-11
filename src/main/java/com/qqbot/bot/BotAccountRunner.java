package com.qqbot.bot;

import com.qqbot.memory.GlobalDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 定时任务按账号执行：遍历 bot_accounts，每个账号在独立 BotContext 中跑一遍。
 * 单个账号失败不影响其他账号。
 */
@Service
public class BotAccountRunner {

    private static final Logger log = LoggerFactory.getLogger(BotAccountRunner.class);

    private final GlobalDb globalDb;

    public BotAccountRunner(GlobalDb globalDb) {
        this.globalDb = globalDb;
    }

    public List<Long> accountIds() {
        List<Long> ids = new ArrayList<>();
        try (Connection c = globalDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM bot_accounts WHERE enabled=1 ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (Exception e) {
            log.warn("读取账号列表失败: {}", e.getMessage());
        }
        return ids;
    }

    public void forEach(Consumer<Long> action) {
        for (Long id : accountIds()) {
            try {
                BotContext.run(id, () -> action.accept(id));
            } catch (Exception e) {
                log.warn("账号任务执行失败 botId={}: {}", id, e.getMessage());
            }
        }
    }
}
