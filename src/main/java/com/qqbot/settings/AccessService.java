package com.qqbot.settings;

import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.chat.OnboardingService;
import com.qqbot.memory.FriendRequestStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 试运行「一键放通」：打开后自动通过好友申请，并且任何好友都能直接聊天（不看白名单）。
 * 按机器人账号独立保存。
 */
@Service
public class AccessService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.qqbot.bot.BotAccountRunner accountRunner;

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);
    private static final String KEY = "access.open";

    private final SettingStore settings;
    private final FriendRequestStore requests;
    private final OneBotRegistry registry;
    private final OnboardingService onboarding;

    public AccessService(SettingStore settings, FriendRequestStore requests,
                         OneBotRegistry registry, OnboardingService onboarding) {
        this.settings = settings;
        this.requests = requests;
        this.registry = registry;
        this.onboarding = onboarding;
    }

    public boolean open() {
        return Boolean.parseBoolean(settings.get(KEY, "false"));
    }

    public void setOpen(boolean open) {
        settings.set(KEY, String.valueOf(open));
        if (open) {
            approveAll();
        }
    }

    /** 放通期间每 5 分钟补扫，处理漏掉的事件或开关打开前堆积的申请 */
    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        accountRunner.forEach(id -> {
            if (open()) {
                approveAll();
            }
    
        });
    }

    public int approveAll() {
        int done = 0;
        try {
            Long botId = com.qqbot.bot.BotContext.get();
            OneBotClient client = botId == null ? null : registry.get(botId);
            if (client == null || !client.isConnected()) {
                return 0;
            }
            for (Map<String, Object> row : requests.pending()) {
                Object idObj = row.get("id");
                Object userObj = row.get("userId");
                try {
                    String flag = String.valueOf(row.get("flag"));
                    client.setFriendAddRequest(flag, true, null);
                    if (idObj instanceof Number n) {
                        requests.mark(n.longValue(), "approved");
                    }
                    if (userObj instanceof Number u) {
                        onboarding.start(u.longValue());
                    }
                    done++;
                } catch (Exception e) {
                    // 申请过期或已被处理：标记掉，避免一直堆在待处理里
                    if (idObj instanceof Number n) {
                        requests.mark(n.longValue(), "expired");
                    }
                    log.info("好友申请自动通过失败，标记过期: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("自动通过好友申请失败: {}", e.getMessage());
        }
        return done;
    }
}
