package com.qqbot.api;

import com.qqbot.proactive.ProactiveService;
import com.qqbot.proactive.ProactiveState;
import com.qqbot.proactive.ProactiveStore;
import com.qqbot.proactive.PushConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主动消息：配置、发送历史、立即测试 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushConfig config;
    private final ProactiveStore store;
    private final ProactiveService service;
    private final UserResolver users;
    private final com.qqbot.memory.MessageStore messages;

    public PushController(PushConfig config, ProactiveStore store, ProactiveService service,
                          UserResolver users, com.qqbot.memory.MessageStore messages) {
        this.config = config;
        this.store = store;
        this.service = service;
        this.users = users;
        this.messages = messages;
    }

    /** 配置 + 当前运行状态 */
    @GetMapping("/config")
    public Map<String, Object> getConfig(@RequestParam(required = false) Long qqId) {
        long uid = users.resolve(qqId);
        ProactiveState st = service.state(uid);
        Map<String, Object> body = new LinkedHashMap<>(config.snapshot());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("suspended", st.suspended());
        state.put("unanswered", st.unansweredCount());
        state.put("grievanceLevel", st.grievanceLevel());
        state.put("teaseNext", st.teaseNext());
        state.put("lastUserAt", st.lastUserAt());
        state.put("lastProactiveAt", st.lastProactiveAt());
        state.put("mode", st.mode() == null ? "CALM" : st.mode().name());
        state.put("mood", st.mood());
        state.put("nextEligibleAt", st.nextEligibleAt());
        state.put("dndUntil", st.dndUntil());
        state.put("reentryStage", st.reentryStage());
        body.put("state", state);
        return body;
    }

    /** 关系状态看板：每个聊过天的用户一行 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        int todaySent = 0;
        int waiting = 0;
        int backoff = 0;
        double rateSum = 0;
        int rateCount = 0;
        for (Long qqId : messages.users()) {
            ProactiveState st = store.get(qqId);
            Map<String, Object> stats = store.stats(qqId);
            int today = store.todayCount(qqId);
            todaySent += today;
            if (st.unansweredCount() >= 1) {
                waiting++;
            }
            if (st.mode() == com.qqbot.proactive.ProactiveMode.BACKOFF
                    || st.mode() == com.qqbot.proactive.ProactiveMode.DND) {
                backoff++;
            }
            double rate = ((Number) stats.getOrDefault("replyRate", 0.0)).doubleValue();
            rateSum += rate;
            rateCount++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("qqId", qqId);
            row.put("mode", st.mode() == null ? "CALM" : st.mode().name());
            row.put("unanswered", st.unansweredCount());
            row.put("lastUserAt", st.lastUserAt());
            row.put("lastProactiveAt", st.lastProactiveAt());
            row.put("nextEligibleAt", st.nextEligibleAt());
            row.put("dndUntil", st.dndUntil());
            row.put("reentryStage", st.reentryStage());
            row.put("mood", st.mood());
            row.put("todayCount", today);
            row.put("total", stats.get("total"));
            row.put("replied", stats.get("replied"));
            row.put("replyRate", rate);
            row.put("avgLatencySec", stats.get("avgLatencySec"));
            row.put("lastContent", store.lastContent(qqId));
            rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare(
                ((Number) b.get("total")).intValue(), ((Number) a.get("total")).intValue()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("users", rows.size());
        summary.put("todaySent", todaySent);
        summary.put("waiting", waiting);
        summary.put("backoff", backoff);
        summary.put("replyRate", rateCount == 0 ? 0.0 : rateSum / rateCount);
        summary.put("dailyLimit", config.dailyLimit());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("users", rows);
        return body;
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        return config.update(body);
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(required = false) Long qqId,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        long uid = users.resolve(qqId);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> items = store.historyPage(uid, type, (p - 1) * s, s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", store.historyCount(uid, type));
        return body;
    }

    /** 立即发一条（绕过时间窗），type: morning / evening / silence / followup / nudge */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body,
                                    @RequestParam(required = false) Long qqId) {
        long uid = users.resolve(qqId);
        String type = body != null && body.get("type") != null ? String.valueOf(body.get("type")) : "morning";
        String content = service.forceSend(uid, type);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", content != null);
        result.put("type", type);
        result.put("content", content);
        result.put("sentTo", content == null ? null : uid);
        return result;
    }
}
