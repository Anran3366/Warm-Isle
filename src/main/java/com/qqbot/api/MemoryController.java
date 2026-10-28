package com.qqbot.api;

import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryRecall;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.NightlyMemoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆 v2 后台接口：查看 / 编辑 / 删除 / 手动跳过主动回忆。
 * 另有只读的检索调试接口，用于确认「注入的是检索结果」。
 *
 * <p>账号隔离沿用全局约定：请求头 X-Bot-Id 决定库，qqId 决定用户。
 */
@RestController
@RequestMapping("/api")
public class MemoryController {

    private final MemoryStore store;
    private final MemoryRecall recall;
    private final NightlyMemoryService nightly;
    private final UserResolver users;

    public MemoryController(MemoryStore store, MemoryRecall recall, NightlyMemoryService nightly,
                            UserResolver users) {
        this.store = store;
        this.recall = recall;
        this.nightly = nightly;
        this.users = users;
    }

    @GetMapping("/memories")
    public Map<String, Object> list(@RequestParam(required = false) Long qqId,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        long uid = users.resolve(qqId);
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Memory m : store.page(uid, type, status, q, (p - 1) * s, s)) {
            items.add(toJson(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", store.count(uid, type, status, q));
        body.put("stats", new LinkedHashMap<String, Object>(store.stats(uid)));
        return body;
    }

    @GetMapping("/memories/stats")
    public Map<String, Object> stats(@RequestParam(required = false) Long qqId) {
        return new LinkedHashMap<>(store.stats(users.resolve(qqId)));
    }

    /** 检索调试：返回打分明细，验收「注入的是检索结果」时用 */
    @GetMapping("/memories/recall")
    public Map<String, Object> recallPreview(@RequestParam(required = false) Long qqId,
                                             @RequestParam(name = "q", required = false) String text,
                                             @RequestParam(defaultValue = "12") int limit) {
        long uid = users.resolve(qqId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", text == null ? "" : text);
        body.put("items", recall.explain(uid, text == null ? "" : text, limit));
        body.put("block", recall.promptBlock(uid, text == null ? "" : text));
        return body;
    }

    /** 手动跑一次夜间整理（验收/补跑用；会忽略内存保护） */
    @PostMapping("/memories/nightly")
    public Map<String, Object> runNightly() {
        return nightly.run(true);
    }

    /** 最近的 episode（每日整理产物） */
    @GetMapping("/memories/episodes")
    public List<Map<String, Object>> episodes(@RequestParam(required = false) Long qqId,
                                              @RequestParam(defaultValue = "7") int limit) {
        return store.recentEpisodes(users.resolve(qqId), limit);
    }

    @PutMapping("/memories/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Memory old = store.byId(id);
        if (old == null) {
            return Map.of("code", "MEMORY_NOT_FOUND", "message", "记忆不存在");
        }
        String type = body.containsKey("type") ? str(body.get("type"), old.type()) : old.type();
        String key = body.containsKey("key") ? str(body.get("key"), old.key()) : old.key();
        String value = body.containsKey("value") ? str(body.get("value"), old.value()) : old.value();
        double importance = body.get("importance") instanceof Number n ? n.doubleValue() : old.importance();
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : old.confidence();
        String status = body.containsKey("status") ? str(body.get("status"), old.status()) : old.status();
        String hint = body.containsKey("actionHint") ? str(body.get("actionHint"), old.actionHint()) : old.actionHint();
        store.update(id, type, key, value, importance, confidence, status, hint);
        return Map.of("ok", true);
    }

    @DeleteMapping("/memories/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        store.delete(id);
        return Map.of("ok", true);
    }

    /** 这个主题以后别再主动提 */
    @PostMapping("/memories/{id}/skip")
    public Map<String, Object> skip(@PathVariable long id) {
        Memory m = store.byId(id);
        if (m == null) {
            return Map.of("code", "MEMORY_NOT_FOUND", "message", "记忆不存在");
        }
        store.markFollowUp(id, "skipped");
        return Map.of("ok", true);
    }

    private Map<String, Object> toJson(Memory m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(m.id()));
        map.put("qqId", String.valueOf(m.qqId()));
        map.put("type", m.type());
        map.put("subject", m.subject());
        map.put("key", m.key());
        map.put("value", m.value());
        map.put("source", m.source());
        map.put("importance", m.importance());
        map.put("confidence", m.confidence());
        map.put("emotion", m.emotion());
        map.put("firstSeen", m.firstSeen());
        map.put("lastConfirmed", m.lastConfirmed());
        map.put("expiresAt", m.expiresAt());
        map.put("followUpAt", m.followUpAt());
        map.put("followUpState", m.followUpState());
        map.put("actionHint", m.actionHint());
        map.put("status", m.status());
        return map;
    }

    private String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
