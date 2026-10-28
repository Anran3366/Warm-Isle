package com.qqbot.api;

import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.MemoryExtractor;
import com.qqbot.memory.MessageItem;
import com.qqbot.memory.MessageStore;
import com.qqbot.memory.SummaryStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M3a 第 3 批：记忆相关接口（事实卡 / 摘要 / 聊天记录 / 手动抽取） */
@RestController
@RequestMapping("/api")
public class MemoryAdminController {

    private final FactStore facts;
    private final SummaryStore summaries;
    private final MessageStore messages;
    private final MemoryExtractor extractor;
    private final UserResolver users;
    private final com.qqbot.memory.Db db;
    private final com.qqbot.memory.MessageEventBus messageEvents;
    private final com.qqbot.review.FactReviewService factReviews;

    public MemoryAdminController(FactStore facts, SummaryStore summaries, MessageStore messages,
                                 MemoryExtractor extractor, UserResolver users,
                                 com.qqbot.memory.Db db,
                                 com.qqbot.memory.MessageEventBus messageEvents,
                                 com.qqbot.review.FactReviewService factReviews) {
        this.facts = facts;
        this.summaries = summaries;
        this.messages = messages;
        this.extractor = extractor;
        this.users = users;
        this.db = db;
        this.messageEvents = messageEvents;
        this.factReviews = factReviews;
    }

    @GetMapping("/facts")
    public Map<String, Object> listFacts(@RequestParam(required = false) Long qqId,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "false") boolean all,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        Map<Long, String> nicknames = nicknames();
        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        if (all) {
            for (Long uid : messages.users()) {
                for (Fact f : facts.page(uid, category, q, status, 0, 1000)) {
                    items.add(toJson(f, nicknames.get(f.qqId())));
                }
                total += facts.count(uid, category, q, status);
            }
            items.sort((a, b) -> String.valueOf(b.get("lastConfirmed"))
                    .compareTo(String.valueOf(a.get("lastConfirmed"))));
            int from = Math.min(items.size(), (p - 1) * s);
            int to = Math.min(items.size(), from + s);
            items = new ArrayList<>(items.subList(from, to));
        } else {
            long uid = users.resolve(qqId);
            for (Fact f : facts.page(uid, category, q, status, (p - 1) * s, s)) {
                items.add(toJson(f, nicknames.get(f.qqId())));
            }
            total = facts.count(uid, category, q, status);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        return body;
    }

    /** qq_id -> 昵称（好友同步时写入 friend_profile） */
    private Map<Long, String> nicknames() {
        Map<Long, String> map = new LinkedHashMap<>();
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT qq_id, nickname FROM friend_profile WHERE nickname IS NOT NULL AND nickname<>''");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getLong(1), rs.getString(2));
            }
        } catch (Exception ignored) {
            // 没有好友资料表时忽略
        }
        return map;
    }

    @GetMapping("/facts/pending")
    public Map<String, Object> pendingFacts(@RequestParam(required = false) Long qqId) {
        Map<Long, String> nicknames = nicknames();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Fact f : facts.pendingAll()) {
            items.add(toJson(f, nicknames.get(f.qqId())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return body;
    }

    /** 让机器人结合前一天的聊天记录审核待确认记忆 */
    @PostMapping("/facts/review-pending")
    public Map<String, Object> reviewPending(@RequestParam(required = false) String day) {
        try {
            return factReviews.run(day == null || day.isBlank() ? null : day);
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", e.getMessage());
            return body;
        }
    }

    @PostMapping("/facts")
    public Map<String, Object> createFact(@RequestBody Map<String, Object> body) {
        long uid = users.resolve(body.get("qqId") instanceof Number n ? n.longValue() : null);
        String category = str(body.get("category"), "profile");
        String key = str(body.get("key"), "");
        String value = str(body.get("value"), "");
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : 0.9;
        if (key.isBlank() || value.isBlank()) {
            return Map.of("code", "INVALID_ARGUMENT", "message", "key 与 value 不能为空");
        }
        facts.upsert(uid, category, key, value, confidence);
        return Map.of("ok", true);
    }

    @PutMapping("/facts/{id}")
    public Map<String, Object> updateFact(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Fact existing = facts.byId(id);
        if (existing == null) {
            return notFound();
        }
        String category = body.containsKey("category") ? str(body.get("category"), existing.category()) : existing.category();
        String key = body.containsKey("key") ? str(body.get("key"), existing.key()) : existing.key();
        String value = body.containsKey("value") ? str(body.get("value"), existing.value()) : existing.value();
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : existing.confidence();
        facts.update(id, category, key, value, confidence);
        return Map.of("ok", true);
    }

    @DeleteMapping("/facts/{id}")
    public Map<String, Object> deleteFact(@PathVariable long id) {
        facts.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/facts/{id}/confirm")
    public Map<String, Object> confirmFact(@PathVariable long id) {
        facts.setStatus(id, "active");
        return Map.of("ok", true);
    }

    @PostMapping("/facts/{id}/reject")
    public Map<String, Object> rejectFact(@PathVariable long id) {
        facts.setStatus(id, "outdated");
        return Map.of("ok", true);
    }

    @GetMapping("/summaries")
    public List<Map<String, String>> listSummaries(@RequestParam(required = false) Long qqId,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to) {
        long uid = users.resolve(qqId);
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate start = from == null || from.isBlank() ? end.minusDays(7) : LocalDate.parse(from);
        List<Map<String, String>> out = new ArrayList<>();
        for (String[] row : summaries.range(uid, start.toString(), end.toString())) {
            out.add(Map.of("date", row[0], "text", row[1]));
        }
        return out;
    }

    /** 新消息实时推送（SSE） */
    @GetMapping("/messages/stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter messageStream(
            @RequestParam long botId) {
        return messageEvents.subscribe(botId);
    }

    @GetMapping("/messages")
    public Map<String, Object> listMessages(@RequestParam(required = false) Long qqId,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        // qqId=0 表示查看「全部用户」混合记录
        long uid = (qqId != null && qqId == 0L) ? 0L : users.resolve(qqId);
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        List<MessageItem> list = messages.searchPage(uid, q, from, to, (p - 1) * s, s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", list);
        body.put("total", messages.countSearch(uid, q, from, to));
        return body;
    }

    /** 聊天记录里出现过的用户列表（QQ 号 + 消息数） */
    @GetMapping("/messages/users")
    public List<Map<String, Object>> messageUsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (long id : messages.users()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("qqId", id);
            out.add(row);
        }
        return out;
    }

    @PostMapping("/memory/extract")
    public Map<String, Object> extract(@RequestParam(required = false) Long qqId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        if (qqId != null) {
            long uid = users.resolve(qqId);
            int written = extractor.extract(uid, now, true);
            body.put("ok", true);
            body.put("written", written);
            body.put("users", 1);
            body.put("active", facts.countActive(uid));
            return body;
        }
        // 不指定用户 = 抽取所有聊过天的人
        int total = 0;
        int done = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Long uid : messages.users()) {
            try {
                int written = extractor.extract(uid, now, true);
                total += written;
                done++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("qqId", uid);
                item.put("written", written);
                detail.add(item);
            } catch (Exception e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("qqId", uid);
                item.put("error", e.getMessage());
                detail.add(item);
            }
        }
        body.put("ok", true);
        body.put("written", total);
        body.put("users", done);
        body.put("detail", detail);
        return body;
    }

    private Map<String, Object> toJson(Fact f) {
        return toJson(f, null);
    }

    private Map<String, Object> toJson(Fact f, String nickname) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(f.id()));
        m.put("qqId", String.valueOf(f.qqId()));
        m.put("nickname", nickname);
        m.put("category", f.category());
        m.put("key", f.key());
        m.put("value", f.value());
        m.put("confidence", f.confidence());
        m.put("status", f.status());
        m.put("firstSeen", f.lastConfirmed() == null ? null : f.lastConfirmed().substring(0, 10));
        m.put("lastConfirmed", f.lastConfirmed() == null ? null : f.lastConfirmed().substring(0, 10));
        return m;
    }

    private Map<String, Object> notFound() {
        return Map.of("code", "FACT_NOT_FOUND", "message", "事实不存在");
    }

    private String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
