package com.qqbot.api;

import com.qqbot.account.AccountService;
import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.memory.FriendRequestStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 好友申请审核：跨机器人账号汇总，同意/拒绝 */
@RestController
@RequestMapping("/api/friend-requests")
public class FriendRequestController {

    private final AccountService accounts;
    private final OneBotRegistry registry;
    private final FriendRequestStore store;
    private final com.qqbot.chat.OnboardingService onboarding;

    public FriendRequestController(AccountService accounts, OneBotRegistry registry,
                                   FriendRequestStore store,
                                   com.qqbot.chat.OnboardingService onboarding) {
        this.accounts = accounts;
        this.registry = registry;
        this.store = store;
        this.onboarding = onboarding;
    }

    @GetMapping
    public Map<String, Object> list() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> acc : accounts.list()) {
            long botId = ((Number) acc.get("id")).longValue();
            try {
                BotContext.call(botId, () -> {
                    for (Map<String, Object> row : store.pending()) {
                        row.put("botId", botId);
                        row.put("botName", acc.get("name"));
                        row.put("botQq", acc.get("qqId"));
                        String nickname = row.get("nickname") == null ? "" : String.valueOf(row.get("nickname"));
                        if (nickname.isBlank()) {
                            OneBotClient client = registry.get(botId);
                            long userId = ((Number) row.get("userId")).longValue();
                            String name = client == null ? "" : client.strangerName(userId);
                            if (!name.isBlank()) {
                                row.put("nickname", name);
                                store.updateNickname(((Number) row.get("id")).longValue(), name);
                            }
                        }
                        items.add(row);
                    }
                    return null;
                });
            } catch (Exception e) {
                // 单个账号失败不影响其他账号
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return body;
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id,
                                       @RequestParam long botId,
                                       @RequestParam(required = false) String remark) {
        return handle(id, botId, true, remark);
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestParam long botId) {
        return handle(id, botId, false, null);
    }

    private Map<String, Object> handle(long id, long botId, boolean approve, String remark) {
        return BotContext.call(botId, () -> {
            Map<String, Object> row = store.byId(id);
            if (row == null) {
                return Map.of("ok", false, "message", "申请不存在或已处理");
            }
            OneBotClient client = registry.get(botId);
            if (client == null) {
                return Map.of("ok", false, "message", "该机器人账号未连接");
            }
            String flag = String.valueOf(row.get("flag"));
            try {
                client.setFriendAddRequest(flag, approve, remark);
            } catch (Exception e) {
                return Map.of("ok", false, "message", "操作失败: " + e.getMessage());
            }
            store.mark(id, approve ? "approved" : "rejected");
            if (approve && row.get("userId") instanceof Number n) {
                onboarding.start(n.longValue());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("status", approve ? "approved" : "rejected");
            return out;
        });
    }
}
