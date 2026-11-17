package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.Db;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MemoryStrength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 记忆体检：矛盾、快忘、过时、重复，以及待审的成长/表达。只读，不自动修。 */
@Service
public class MemoryAuditService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAuditService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemoryStore memories;
    private final GrowthService growth;
    private final ExpressionService expressions;
    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public MemoryAuditService(MemoryStore memories, GrowthService growth,
                              ExpressionService expressions, Db db) {
        this.memories = memories;
        this.growth = growth;
        this.expressions = expressions;
        this.db = db;
    }

    public Map<String, Object> audit(long qqId) {
        List<Memory> active = memories.active(qqId);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Memory>> byKey = new LinkedHashMap<>();
        for (Memory m : active) {
            byKey.computeIfAbsent(m.subject() + "|" + m.key(), k -> new ArrayList<>()).add(m);
        }
        for (List<Memory> group : byKey.values()) {
            if (group.size() > 1) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", group.get(0).key());
                List<Map<String, Object>> values = new ArrayList<>();
                for (Memory m : group) {
                    values.add(Map.of("id", m.id(), "value", m.value() == null ? "" : m.value()));
                }
                item.put("values", values);
                conflicts.add(item);
            }
        }

        List<Map<String, Object>> fading = new ArrayList<>();
        List<Map<String, Object>> stale = new ArrayList<>();
        Map<String, List<Memory>> byValue = new LinkedHashMap<>();
        for (Memory m : active) {
            double strength = MemoryStrength.effective(m, now);
            if (!m.pinned() && m.importance() >= 0.7 && strength < MemoryStrength.FADING
                    && strength >= MemoryStrength.FORGET) {
                fading.add(Map.of("id", m.id(), "key", blank(m.key()), "value", blank(m.value()),
                        "strength", Math.round(strength * 100) / 100.0));
            }
            if ("event".equals(m.type()) && MemoryStore.daysSince(m.lastConfirmed()) > 30) {
                stale.add(Map.of("id", m.id(), "key", blank(m.key()), "value", blank(m.value()),
                        "lastConfirmed", blank(m.lastConfirmed())));
            }
            if (m.value() != null) {
                byValue.computeIfAbsent(m.type() + "|" + normalize(m.value()), k -> new ArrayList<>()).add(m);
            }
        }

        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (List<Memory> group : byValue.values()) {
            if (group.size() > 1) {
                List<Long> ids = new ArrayList<>();
                group.forEach(m -> ids.add(m.id()));
                duplicates.add(Map.of("value", blank(group.get(0).value()), "ids", ids));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conflicts", conflicts);
        out.put("fading", fading);
        out.put("stale", stale);
        out.put("duplicates", duplicates);
        out.put("pendingGrowth", growth.list(qqId, "pending", 100).size());
        out.put("pendingExpressions", expressions.list(qqId, "pending", 100).size());
        out.put("active", active.size());
        save(qqId, out);
        return out;
    }

    private void save(long qqId, Map<String, Object> report) {
        try (Connection c = db.open()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM memory_checks WHERE qq_id=? AND id NOT IN "
                            + "(SELECT id FROM memory_checks WHERE qq_id=? ORDER BY id DESC LIMIT 30)")) {
                del.setLong(1, qqId);
                del.setLong(2, qqId);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO memory_checks(qq_id, kind, detail, created_at) VALUES(?,?,?,?)")) {
                ps.setLong(1, qqId);
                ps.setString(2, "audit");
                ps.setString(3, mapper.writeValueAsString(report));
                ps.setString(4, LocalDateTime.now().format(TS));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.debug("保存体检报告失败: {}", e.getMessage());
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s，。,.!?！？、]", "").toLowerCase();
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }
}
