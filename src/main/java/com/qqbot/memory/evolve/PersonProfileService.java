package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.Db;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人物档案：称呼、关系阶段、特征、雷区、重要日期。
 *
 * <p>由每日反思自动回填（必须有证据），也可以后台手改。只做「合并 + 去重」，
 * 不覆盖用户手写的值；否认过的称呼一律不写。</p>
 */
@Service
public class PersonProfileService {

    private static final Logger log = LoggerFactory.getLogger(PersonProfileService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> STAGES = Set.of("陌生", "熟悉", "亲近", "暧昧", "恋人");
    private static final int MAX_LIST = 5;

    private final Db db;
    private final MemoryStore memories;
    private final MessageStore messages;
    private final ObjectMapper mapper = new ObjectMapper();

    public PersonProfileService(Db db, MemoryStore memories, MessageStore messages) {
        this.db = db;
        this.memories = memories;
        this.messages = messages;
    }

    public Map<String, Object> get(long qqId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("qqId", qqId);
        out.put("address", "");
        out.put("stage", "");
        out.put("traits", List.of());
        out.put("taboos", List.of());
        out.put("importantDates", List.of());
        out.put("updatedAt", "");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT address, stage, traits, taboos, important_dates, profile_updated_at "
                             + "FROM friend_profile WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return out;
                }
                out.put("address", blank(rs.getString(1)));
                out.put("stage", blank(rs.getString(2)));
                out.put("traits", parseArray(rs.getString(3)));
                out.put("taboos", parseArray(rs.getString(4)));
                out.put("importantDates", parseDates(rs.getString(5)));
                out.put("updatedAt", blank(rs.getString(6)));
            }
        } catch (Exception e) {
            log.debug("读取人物档案失败: {}", e.getMessage());
        }
        return out;
    }

    /** 后台手改：整体覆盖（空串表示清空）。 */
    public Map<String, Object> save(long qqId, String address, String stage,
                                    List<String> traits, List<String> taboos, List<Map<String, String>> dates) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE friend_profile SET address=?, stage=?, traits=?, taboos=?, "
                             + "important_dates=?, profile_updated_at=? WHERE qq_id=?")) {
            ps.setString(1, cut(address, 14));
            ps.setString(2, stage == null ? "" : stage);
            ps.setString(3, mapper.writeValueAsString(cleanList(traits, 12)));
            ps.setString(4, mapper.writeValueAsString(cleanList(taboos, 14)));
            ps.setString(5, mapper.writeValueAsString(dates == null ? List.of() : dates));
            ps.setString(6, LocalDateTime.now().format(TS));
            ps.setLong(7, qqId);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO friend_profile(qq_id, address, stage, traits, taboos, important_dates, "
                                + "profile_updated_at) VALUES(?,?,?,?,?,?,?)")) {
                    ins.setLong(1, qqId);
                    ins.setString(2, cut(address, 14));
                    ins.setString(3, stage == null ? "" : stage);
                    ins.setString(4, mapper.writeValueAsString(cleanList(traits, 12)));
                    ins.setString(5, mapper.writeValueAsString(cleanList(taboos, 14)));
                    ins.setString(6, mapper.writeValueAsString(dates == null ? List.of() : dates));
                    ins.setString(7, LocalDateTime.now().format(TS));
                    ins.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存人物档案失败: " + e.getMessage(), e);
        }
        return get(qqId);
    }

    /**
     * 确定性回填：不依赖模型，从已有记忆/聊天量里补称呼、阶段、特征、雷区、重要日期。
     * 反思之后跑一遍，保证档案不会一直是空的。
     */
    public int backfill(long qqId) {
        Map<String, Object> current = get(qqId);
        String address = String.valueOf(current.getOrDefault("address", ""));
        String stage = String.valueOf(current.getOrDefault("stage", ""));
        List<String> traits = stringList(current.get("traits"));
        List<String> taboos = stringList(current.get("taboos"));
        List<Map<String, String>> dates = dateList(current.get("importantDates"));
        int changed = 0;

        Set<String> deniedTerms = new LinkedHashSet<>();
        Set<String> songValues = new LinkedHashSet<>();
        for (Memory m : memories.active(qqId)) {
            if (m.value() != null && m.value().startsWith("不要叫")) {
                deniedTerms.add(denialTerm(m.value()));
            }
            if (m.key() != null && (m.key().contains("歌") || m.key().contains("音乐"))
                    && m.value() != null) {
                songValues.add(m.value());
            }
        }
        int beforeTraits = traits.size();
        traits.removeIf(songValues::contains);
        if (traits.size() != beforeTraits) {
            changed++;
        }

        for (Memory m : memories.active(qqId)) {
            if (!"user".equals(m.subject())) {
                continue;
            }
            String key = m.key() == null ? "" : m.key();
            String value = m.value() == null ? "" : m.value();
            if (key.contains("称呼") || key.contains("昵称")) {
                if (value.startsWith("不要叫")) {
                    String term = denialTerm(value);
                    String taboo = "不要叫" + term;
                    if (!term.isBlank() && !taboos.contains(taboo) && taboos.size() < MAX_LIST) {
                        taboos.add(taboo);
                        changed++;
                    }
                } else if (address.isBlank() && !value.isBlank() && value.length() <= 14
                        && !deniedTerms.contains(value)) {
                    address = value;
                    changed++;
                }
            } else if (traits.size() < 3 && "profile".equals(m.type())
                    && !key.contains("表情") && !key.contains("歌") && !key.contains("音乐")
                    && !value.contains("唱") && !value.contains("歌")
                    && !value.contains("表情") && value.length() <= 24
                    && key.matches(".*(性格|作息|宠物|恋爱|聊天风格|相处|喜好|喜欢|偏好|习惯|口味|工作).*")
                    && !traits.contains(value)) {
                traits.add(value);
                changed++;
            }
            if (dates.size() < 3 && m.pinned() && m.recurMd() != null && !m.recurMd().isBlank()) {
                boolean exists = dates.stream().anyMatch(d -> m.recurMd().equals(d.get("md")));
                if (!exists) {
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("md", m.recurMd());
                    one.put("label", key.length() <= 10 ? key : "重要日子");
                    dates.add(one);
                    changed++;
                }
            }
        }

        if (stage.isBlank()) {
            int n = messages.countUser(qqId);
            stage = n < 30 ? "陌生" : n < 300 ? "熟悉" : "亲近";
            changed++;
        }

        if (changed > 0) {
            save(qqId, address, stage, traits, taboos, dates);
        }
        return changed;
    }

    private static String denialTerm(String value) {
        String t = value.replaceFirst("^不要叫", "").trim();
        t = t.replaceFirst("^[「\"']", "");
        int cut = t.indexOf('」');
        if (cut < 0) cut = t.indexOf('"');
        if (cut < 0) cut = t.indexOf('\'');
        if (cut < 0) cut = t.indexOf('；');
        if (cut > 0) t = t.substring(0, cut);
        return t.trim();
    }

    /** 反思回填：只合并、去重、过滤否认项；至少要有证据。 */
    public int applyReflection(long qqId, JsonNode node, Set<Long> evidence, Set<String> denied) {
        if (node == null || !node.isObject() || evidence == null || evidence.isEmpty()) {
            return 0;
        }
        Map<String, Object> current = get(qqId);
        String address = String.valueOf(current.getOrDefault("address", ""));
        String stage = String.valueOf(current.getOrDefault("stage", ""));
        List<String> traits = stringList(current.get("traits"));
        List<String> taboos = stringList(current.get("taboos"));
        List<Map<String, String>> dates = dateList(current.get("importantDates"));
        int changed = 0;

        String newAddress = node.path("address").asText("").trim();
        if (!newAddress.isEmpty() && newAddress.length() <= 14
                && !deniedHit(denied, newAddress) && !newAddress.equals(address)) {
            address = newAddress;
            changed++;
        }
        String newStage = node.path("stage").asText("").trim();
        if (STAGES.contains(newStage) && !newStage.equals(stage)) {
            stage = newStage;
            changed++;
        }
        changed += merge(traits, node.path("traits"), 12, denied);
        changed += merge(taboos, node.path("taboos"), 14, denied);
        changed += mergeDates(dates, node.path("important_dates"));
        changed += mergeDates(dates, node.path("importantDates"));

        if (changed == 0) {
            return 0;
        }
        save(qqId, address, stage, traits, taboos, dates);
        return changed;
    }

    private int mergeDates(List<Map<String, String>> target, JsonNode source) {
        if (source == null || !source.isArray()) {
            return 0;
        }
        int changed = 0;
        for (JsonNode item : source) {
            String md = item.path("md").asText("").trim();
            String label = item.path("label").asText("").trim();
            if (!md.matches("\\d{2}-\\d{2}") || label.length() > 10) {
                continue;
            }
            boolean exists = target.stream().anyMatch(d -> md.equals(d.get("md")));
            if (exists || target.size() >= MAX_LIST) {
                continue;
            }
            Map<String, String> one = new LinkedHashMap<>();
            one.put("md", md);
            one.put("label", label);
            target.add(one);
            changed++;
        }
        return changed;
    }

    private int merge(List<String> target, JsonNode source, int maxLen, Set<String> denied) {
        if (source == null || !source.isArray()) {
            return 0;
        }
        int changed = 0;
        for (JsonNode item : source) {
            String value = item.asText("").trim();
            if (value.isEmpty() || value.length() > maxLen || deniedHit(denied, value)) {
                continue;
            }
            if (target.contains(value) || target.size() >= MAX_LIST) {
                continue;
            }
            target.add(value);
            changed++;
        }
        return changed;
    }

    private List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private List<Map<String, String>> dateList(Object value) {
        List<Map<String, String>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    Map<String, String> item = new LinkedHashMap<>();
                    Object md = map.get("md");
                    Object label = map.get("label");
                    item.put("md", md == null ? "" : String.valueOf(md));
                    item.put("label", label == null ? "" : String.valueOf(label));
                    if (!item.get("md").isBlank()) {
                        out.add(item);
                    }
                }
            }
        }
        return out;
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = mapper.readTree(json);
            List<String> out = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asText("")));
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, String>> parseDates(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = mapper.readTree(json);
            List<Map<String, String>> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode n : node) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("md", n.path("md").asText(""));
                    item.put("label", n.path("label").asText(""));
                    out.add(item);
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<String> cleanList(List<String> list, int maxLen) {
        if (list == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : list) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isEmpty() && t.length() <= maxLen) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean deniedHit(Set<String> denied, String text) {
        if (denied == null) {
            return false;
        }
        for (String term : denied) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
