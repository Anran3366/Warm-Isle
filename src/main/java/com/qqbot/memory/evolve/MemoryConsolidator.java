package com.qqbot.memory.evolve;

import com.qqbot.memory.Fact;
import com.qqbot.memory.FactStore;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆巩固：合并重复、处理矛盾、校准 facts。
 *
 * <p>不调用模型；每天反思之后跑一遍。原则：同一件事只留一条 active，
 * 称呼类冲突以「最近的」为准，facts 只保留不与 memories 冲突的部分。</p>
 */
@Service
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private final MemoryStore memories;
    private final FactStore facts;
    private final ProfileSnapshotService profile;

    public MemoryConsolidator(MemoryStore memories, FactStore facts, ProfileSnapshotService profile) {
        this.memories = memories;
        this.facts = facts;
        this.profile = profile;
    }

    public Map<String, Object> run(long qqId) {
        int archived = 0;
        int factFixed = 0;

        List<Memory> active = memories.active(qqId);
        java.util.Set<Long> archivedIds = new java.util.HashSet<>();

        // 1) 称呼/昵称跨 type 冲突：只留最新的一条，避免「profile/称呼」和「relation/称呼」两个值
        archived += keepNameGroup(active, archivedIds);

        // 2) 同 type + subject + key 多条：留最新
        Map<String, List<Memory>> byKey = new LinkedHashMap<>();
        for (Memory m : active) {
            if (archivedIds.contains(m.id())) {
                continue;
            }
            byKey.computeIfAbsent(m.type() + "|" + m.subject() + "|" + m.key(), k -> new ArrayList<>()).add(m);
        }
        for (List<Memory> group : byKey.values()) {
            if (group.size() > 1) {
                archived += keepNewestInGroup(group, archivedIds);
            }
        }

        // 3) 同 type 下 value 完全重复（归一化后）：留重要度/强度最高的
        Map<String, List<Memory>> byValue = new LinkedHashMap<>();
        for (Memory m : active) {
            if (archivedIds.contains(m.id()) || m.value() == null) {
                continue;
            }
            byValue.computeIfAbsent(m.type() + "|" + normalize(m.value()), k -> new ArrayList<>()).add(m);
        }
        for (List<Memory> group : byValue.values()) {
            if (group.size() > 1) {
                archived += keepStrongest(group, archivedIds);
            }
        }

        // 4) facts 与 memories 冲突：以 memories 为准
        try {
            for (Fact f : facts.active(qqId)) {
                if (!isNameKey(f.key())) {
                    continue;
                }
                Memory winner = active.stream()
                        .filter(m -> isNameKey(m.key()) && !"archived".equals(m.status()))
                        .filter(m -> m.value() != null && !m.value().equals(f.value()))
                        .findFirst().orElse(null);
                if (winner != null) {
                    factFixed += facts.deactivateValue(qqId, f.value());
                }
            }
        } catch (Exception e) {
            log.debug("facts 一致性检查失败: {}", e.getMessage());
        }

        if (archived > 0 || factFixed > 0) {
            profile.rebuild(qqId);
            log.info("记忆巩固 qqId={} 归档={} 条，facts 停用={} 条", qqId, archived, factFixed);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archived", archived);
        out.put("factsOutdated", factFixed);
        return out;
    }

    private int keepNameGroup(List<Memory> active, java.util.Set<Long> archivedIds) {
        List<Memory> group = new ArrayList<>();
        for (Memory m : active) {
            if (isNameKey(m.key())) {
                group.add(m);
            }
        }
        return group.size() > 1 ? keepNewestInGroup(group, archivedIds, true) : 0;
    }

    private int keepNewestInGroup(List<Memory> group, java.util.Set<Long> archivedIds) {
        return keepNewestInGroup(group, archivedIds, false);
    }

    private int keepNewestInGroup(List<Memory> group, java.util.Set<Long> archivedIds, boolean preferDenial) {
        List<Memory> sorted = new ArrayList<>(group);
        sorted.sort(Comparator
                .comparing((Memory m) -> m.lastConfirmed() == null ? "" : m.lastConfirmed())
                .thenComparingDouble(Memory::importance)
                .reversed());
        Memory keep = sorted.get(0);
        if (preferDenial) {
            for (Memory m : sorted) {
                if (m.value() != null && m.value().startsWith("不要叫")) {
                    keep = m;
                    break;
                }
            }
        }
        int archived = 0;
        for (Memory m : sorted) {
            if (m.id() == keep.id()) {
                continue;
            }
            archived += memories.setStatus(m.id(), "archived");
            archivedIds.add(m.id());
        }
        return archived;
    }

    private int keepStrongest(List<Memory> group, java.util.Set<Long> archivedIds) {
        List<Memory> sorted = new ArrayList<>(group);
        sorted.sort(Comparator
                .comparingDouble(Memory::importance)
                .thenComparingDouble(Memory::strength)
                .reversed());
        int archived = 0;
        for (int i = 1; i < sorted.size(); i++) {
            archived += memories.setStatus(sorted.get(i).id(), "archived");
            archivedIds.add(sorted.get(i).id());
        }
        return archived;
    }

    private static boolean isNameKey(String key) {
        return key != null && (key.contains("称呼") || key.contains("昵称") || key.equals("名字"));
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s，。,.!?！？、]", "").toLowerCase();
    }
}
