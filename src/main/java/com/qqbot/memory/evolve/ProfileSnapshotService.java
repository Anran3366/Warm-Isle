package com.qqbot.memory.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.chat.MoodService;
import com.qqbot.memory.Db;
import com.qqbot.memory.Memory;
import com.qqbot.memory.MemoryStore;
import com.qqbot.memory.SettingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L0 常驻画像：每次回复都带的一小段「她现在怎么看你」。
 *
 * <p>纯规则拼装，不额外调模型；缓存 12 小时，反思写完会自动重建。
 * 控制在 160 字以内，避免把 prompt 撑长反而让模型分心。</p>
 */
@Service
public class ProfileSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSnapshotService.class);
    private static final String KEY = "memory.l0:";
    private static final long TTL_MS = 12 * 3600_000L;
    private static final int MAX_CHARS = 160;

    private final Db db;
    private final MemoryStore memories;
    private final SettingStore settings;
    private final MoodService moods;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfileSnapshotService(Db db, MemoryStore memories, SettingStore settings, MoodService moods) {
        this.db = db;
        this.memories = memories;
        this.settings = settings;
        this.moods = moods;
    }

    /** 取缓存；过期或没有就重建。 */
    public String snapshot(long qqId) {
        String raw = settings.get(KEY + qqId, null);
        if (raw != null && !raw.isBlank()) {
            try {
                Map<?, ?> map = mapper.readValue(raw, Map.class);
                Object atObj = map.get("at");
                long at = atObj instanceof Number number ? number.longValue() : 0;
                Object text = map.get("text");
                if (text != null && System.currentTimeMillis() - at < TTL_MS) {
                    return String.valueOf(text);
                }
            } catch (Exception ignored) {
                // 缓存坏了就重建
            }
        }
        return rebuild(qqId);
    }

    /** 反思/巩固后强制刷新。 */
    public String rebuild(long qqId) {
        String text = build(qqId);
        try {
            settings.set(KEY + qqId, mapper.writeValueAsString(
                    Map.of("at", System.currentTimeMillis(), "text", text)));
        } catch (Exception e) {
            log.debug("写常驻画像失败: {}", e.getMessage());
        }
        return text;
    }

    private String build(long qqId) {
        String address = profileField(qqId, "address");
        String stage = profileField(qqId, "stage");
        List<String> traits = new ArrayList<>();
        for (String t : parseList(profileField(qqId, "traits"))) {
            if (traits.size() >= 2) {
                break;
            }
            if (!t.isBlank()) {
                traits.add(t);
            }
        }
        List<Memory> candidates = new ArrayList<>();

        for (Memory m : memories.active(qqId)) {
            if (m.key() == null || m.value() == null) {
                continue;
            }
            if (m.key().contains("称呼") || m.key().contains("昵称")) {
                if (address == null && !m.value().startsWith("不要叫")) {
                    address = m.value();
                }
                continue;
            }
            if (!goodTrait(m)) {
                continue;
            }
            candidates.add(m);
        }
        candidates.sort((a, b) -> {
            int byScore = Integer.compare(traitScore(b), traitScore(a));
            return byScore != 0 ? byScore : Double.compare(b.importance(), a.importance());
        });
        List<String> usedKeys = new ArrayList<>();
        List<String> usedTopics = new ArrayList<>();
        for (Memory m : candidates) {
            if (traits.size() >= 2) {
                break;
            }
            if (usedKeys.contains(m.key())) {
                continue;
            }
            String topic = topicOf(m.key());
            if (topic != null && usedTopics.contains(topic)) {
                continue;
            }
            boolean duplicate = traits.stream().anyMatch(t -> similar(t, m.value()));
            if (duplicate) {
                continue;
            }
            traits.add(m.value());
            usedKeys.add(m.key());
            if (topic != null) {
                usedTopics.add(topic);
            }
        }

        String recent = "";
        for (Memory m : memories.recentEvents(qqId, 7, 2)) {
            if (m.value() != null && !m.value().isBlank()) {
                recent = m.value();
                break;
            }
        }

        String mood = "";
        try {
            mood = moods.describe(qqId);
        } catch (Exception ignored) {
            // 心情读不到就不写
        }

        StringBuilder sb = new StringBuilder();
        sb.append("称呼：").append(address == null || address.isBlank() ? "你" : cut(address, 14));
        if (stage != null && !stage.isBlank()) {
            sb.append("；关系：").append(cut(stage, 8));
        }
        if (!traits.isEmpty()) {
            sb.append("；特征：");
            for (int i = 0; i < traits.size(); i++) {
                if (i > 0) {
                    sb.append('、');
                }
                sb.append(cut(traits.get(i), 24));
            }
        }
        String taboo = parseList(profileField(qqId, "taboos")).stream().findFirst().orElse("");
        if (!taboo.isBlank() && sb.length() < 130) {
            sb.append("；忌：").append(cut(taboo, 12));
        }
        if (!recent.isBlank()) {
            sb.append("；近况：").append(cut(recent, 30));
        }
        if (mood != null && !mood.isBlank()) {
            sb.append("；心情：").append(cut(mood, 12));
        }
        return cut(sb.toString(), MAX_CHARS);
    }

    private List<String> parseList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asText("")));
            }
        } catch (Exception ignored) {
            // 坏 JSON 当空
        }
        return out;
    }

    private String profileField(long qqId, String column) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM friend_profile WHERE qq_id=?")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 只把「相对稳定」的属性当特征；点歌、发表情、发语音这类一次性行为不算。 */
    private static boolean goodTrait(Memory m) {
        String key = m.key() == null ? "" : m.key();
        String value = m.value() == null ? "" : m.value();
        if (value.isBlank() || value.startsWith("不要叫")) {
            return false;
        }
        if (key.contains("表情") || key.contains("点歌")
                || value.contains("唱") || value.contains("歌") || value.contains("表情")
                || value.contains("语音") || value.contains("图片") || value.contains("点了")) {
            return false;
        }
        boolean profile = "profile".equals(m.type());
        boolean relation = "relation".equals(m.type()) && key.matches(".*(相处|聊天风格|温柔|关系).*");
        return profile || relation;
    }

    private static String topicOf(String key) {
        if (key == null) {
            return null;
        }
        if (key.matches(".*(聊天风格|温柔|说话|相处).*")) {
            return "style";
        }
        if (key.matches(".*(作息|睡觉|熬夜|起床).*")) {
            return "schedule";
        }
        if (key.matches(".*(宠物|猫|狗).*")) {
            return "pet";
        }
        if (key.matches(".*(恋爱|感情|表白).*")) {
            return "love";
        }
        if (key.matches(".*(工作|职业|上班).*")) {
            return "work";
        }
        return null;
    }

    private static boolean similar(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 1 < a.length(); i++) {
            total++;
            if (b.contains(a.substring(i, i + 2))) {
                hit++;
            }
        }
        return total > 0 && (double) hit / total >= 0.5;
    }

    private static int traitScore(Memory m) {
        String key = m.key() == null ? "" : m.key();
        int score = 0;
        if (key.matches(".*(性格|作息|宠物|恋爱|聊天风格|相处|工作|雷区).*")) {
            score += 3;
        } else if (key.matches(".*(喜好|喜欢|偏好|口味|习惯|特征|状态).*")) {
            score += 2;
        } else {
            score += 1;
        }
        if (m.value() != null && m.value().length() <= 24) {
            score += 1;
        }
        return score;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
