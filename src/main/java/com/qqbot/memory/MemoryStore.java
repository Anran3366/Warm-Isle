package com.qqbot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆库 v2：memories 表读写。
 *
 * <p>与 facts 的区别：memories 有类型、重要度、情绪、时效、主动回忆时间；
 * facts 只读保留，作为迁移来源与回滚依据。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ACTIVE_PER_USER = 500;

    private static final String COLS = "id, qq_id, type, subject, key, value, source, importance, confidence, "
            + "emotion, first_seen, last_confirmed, expires_at, follow_up_at, follow_up_state, action_hint, "
            + "status, superseded_by";

    private final Db db;

    public MemoryStore(Db db) {
        this.db = db;
    }

    /** 待写入的一条记忆（NightlyMemoryService / 迁移 / 手动后台共用） */
    public record Draft(String type, String subject, String key, String value, String source,
                        double importance, double confidence, double emotion,
                        String expiresAt, String followUpAt, String actionHint) {

        public static Draft of(String type, String key, String value, double importance) {
            return new Draft(type, "user", key, value, "chat", importance, 0.8, 0,
                    null, null, null);
        }

        public Draft withFollowUp(String followUpAt, String actionHint) {
            return new Draft(type, subject, key, value, source, importance, confidence, emotion,
                    expiresAt, followUpAt, actionHint);
        }

        public Draft withSource(String newSource) {
            return new Draft(type, subject, key, value, newSource, importance, confidence, emotion,
                    expiresAt, followUpAt, actionHint);
        }
    }

    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    public static String ts(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    public static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, TS);
        } catch (Exception e) {
            try {
                return LocalDate.parse(s).atStartOfDay();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // ---------------- 写入 ----------------

    /**
     * 写入一条记忆，处理冲突：
     * 同 subject + key 的新值 → 旧记录 archived + superseded_by 指向新记录；
     * 值没变 → 只刷新 last_confirmed 与重要度。
     *
     * @return 记忆 id
     */
    public synchronized long upsert(long qqId, Draft draft) {
        String type = normalizeType(draft.type());
        String subject = blankTo(draft.subject(), "user");
        String key = blankTo(draft.key(), "").trim();
        String value = blankTo(draft.value(), "").trim();
        if (key.isEmpty() || value.isEmpty()) {
            return -1;
        }
        double importance = clamp(draft.importance());
        double confidence = clamp(draft.confidence());
        double emotion = Math.max(-1, Math.min(1, draft.emotion()));
        String source = blankTo(draft.source(), "chat");

        Memory existing = findActive(qqId, type, subject, key);
        if (existing != null && sameValue(existing.value(), value)) {
            String confirmed = now();
            String followUpAt = existing.followUpAt();
            String followUpState = existing.followUpState();
            // 只有从未安排过的记忆才允许补 follow_up；已 skipped/done 的同一事件不再骚扰
            if ((followUpState == null || "none".equals(followUpState)) && draft.followUpAt() != null) {
                followUpAt = draft.followUpAt();
                followUpState = "pending";
            }
            String sql = "UPDATE memories SET last_confirmed=?, importance=?, confidence=?, emotion=?, "
                    + "expires_at=COALESCE(?, expires_at), follow_up_at=?, follow_up_state=?, "
                    + "action_hint=COALESCE(?, action_hint) WHERE id=?";
            update(sql, confirmed, Math.max(existing.importance(), importance),
                    Math.max(existing.confidence(), confidence), emotion, draft.expiresAt(),
                    followUpAt, followUpState == null ? "none" : followUpState,
                    blankToNull(draft.actionHint()), existing.id());
            return existing.id();
        }

        String followUpState = draft.followUpAt() == null || draft.followUpAt().isBlank() ? "none" : "pending";
        long newId = insert(qqId, type, subject, key, value, source, importance, confidence, emotion,
                draft.expiresAt(), draft.followUpAt(), followUpState, draft.actionHint());
        if (existing != null) {
            archive(existing.id(), newId);
        }
        trim(qqId);
        return newId;
    }

    /** 后台手动新增 */
    public long add(long qqId, Draft draft) {
        return upsert(qqId, draft.withSource("manual"));
    }

    private long insert(long qqId, String type, String subject, String key, String value, String source,
                        double importance, double confidence, double emotion, String expiresAt,
                        String followUpAt, String followUpState, String actionHint) {
        String now = now();
        String sql = "INSERT INTO memories(qq_id, type, subject, key, value, source, importance, confidence, "
                + "emotion, first_seen, last_confirmed, expires_at, follow_up_at, follow_up_state, action_hint, status) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'active')";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, qqId);
            ps.setString(2, type);
            ps.setString(3, subject);
            ps.setString(4, key);
            ps.setString(5, value);
            ps.setString(6, source);
            ps.setDouble(7, importance);
            ps.setDouble(8, confidence);
            ps.setDouble(9, emotion);
            ps.setString(10, now);
            ps.setString(11, now);
            ps.setString(12, blankToNull(expiresAt));
            ps.setString(13, blankToNull(followUpAt));
            ps.setString(14, followUpState == null ? "none" : followUpState);
            ps.setString(15, blankToNull(actionHint));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入记忆失败: " + e.getMessage(), e);
        }
    }

    private void archive(long id, long supersededBy) {
        update("UPDATE memories SET status='archived', superseded_by=? WHERE id=?", supersededBy, id);
    }

    /** 后台编辑 */
    public void update(long id, String type, String key, String value, double importance,
                       double confidence, String status, String actionHint) {
        Memory old = byId(id);
        if (old == null) {
            return;
        }
        String sql = "UPDATE memories SET type=?, key=?, value=?, importance=?, confidence=?, "
                + "status=?, action_hint=?, last_confirmed=? WHERE id=?";
        update(sql, normalizeType(type), key, value, clamp(importance), clamp(confidence),
                status == null || status.isBlank() ? old.status() : status,
                blankToNull(actionHint), now(), id);
    }

    public void delete(long id) {
        update("DELETE FROM memories WHERE id=?", id);
    }

    /** 用户说「别问了」→ 该用户所有待主动回忆跳过 */
    public int skipAllPending(long qqId) {
        return update("UPDATE memories SET follow_up_state='skipped' "
                + "WHERE qq_id=? AND follow_up_state='pending'", qqId);
    }

    /** 某个主题不再主动提（key 或 key@日期 都算） */
    public int skipPendingByKey(long qqId, String key) {
        return update("UPDATE memories SET follow_up_state='skipped' WHERE qq_id=? AND follow_up_state='pending' "
                + "AND (key=? OR key LIKE ?)", qqId, key, key + "@%");
    }

    public void markFollowUp(long id, String state) {
        update("UPDATE memories SET follow_up_state=?, last_confirmed=? WHERE id=?", state, now(), id);
    }

    /**
     * 夜间维护：到期 → expired；90 天未确认 → 重要度衰减，<0.3 归档。
     *
     * @return [过期数, 归档数]
     */
    public int[] maintain(long qqId, LocalDateTime nowT) {
        String now = ts(nowT);
        int expired = update("UPDATE memories SET status='expired' WHERE qq_id=? AND status='active' "
                + "AND expires_at IS NOT NULL AND expires_at<>'' AND expires_at < ?", qqId, now);
        int archived = 0;
        String oldBefore = ts(nowT.minusDays(90));
        List<Memory> stale = query("qq_id=? AND status='active' AND last_confirmed < ?", qqId, oldBefore);
        for (Memory m : stale) {
            double decayed = Math.max(0.05, m.importance() * 0.85);
            if (decayed < 0.3) {
                update("UPDATE memories SET status='archived', importance=? WHERE id=?", decayed, m.id());
                archived++;
            } else {
                update("UPDATE memories SET importance=? WHERE id=?", decayed, m.id());
            }
        }
        return new int[]{expired, archived};
    }

    /**
     * facts → memories 一次性迁移。facts 保持只读，不删除。
     * 幂等：同 type + key 已有 active 记忆就跳过。
     *
     * @return 新写入条数
     */
    public int migrateFromFacts(long qqId) {
        int written = 0;
        String sql = "SELECT category, key, value, confidence, first_seen, last_confirmed "
                + "FROM facts WHERE qq_id=? AND status='active'";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String category = rs.getString("category");
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    if (key == null || key.isBlank() || value == null || value.isBlank()) {
                        continue;
                    }
                    String type = mapFactType(category);
                    if (findActive(qqId, type, "user", key) != null) {
                        continue;
                    }
                    double importance = mapFactImportance(category);
                    double confidence = rs.getDouble("confidence");
                    if (confidence <= 0) {
                        confidence = 0.8;
                    }
                    String firstSeen = blankTo(rs.getString("first_seen"), now());
                    String lastConfirmed = blankTo(rs.getString("last_confirmed"), firstSeen);
                    insertMigrated(qqId, type, key, value, importance, confidence, firstSeen, lastConfirmed);
                    written++;
                }
            }
        } catch (Exception e) {
            log.warn("facts 迁移失败 qqId={}: {}", qqId, e.getMessage());
        }
        if (written > 0) {
            log.info("facts → memories 迁移完成 qqId={} 写入 {} 条", qqId, written);
        }
        return written;
    }

    private void insertMigrated(long qqId, String type, String key, String value, double importance,
                                double confidence, String firstSeen, String lastConfirmed) {
        String sql = "INSERT INTO memories(qq_id, type, subject, key, value, source, importance, confidence, "
                + "emotion, first_seen, last_confirmed, follow_up_state, status) "
                + "VALUES(?,?,?,?,?,'chat',?,?,0,?,?,'none','active')";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setString(2, type);
            ps.setString(3, "user");
            ps.setString(4, key);
            ps.setString(5, value);
            ps.setDouble(6, importance);
            ps.setDouble(7, confidence);
            ps.setString(8, firstSeen);
            ps.setString(9, lastConfirmed);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("迁移单条失败 key={}: {}", key, e.getMessage());
        }
    }

    static String mapFactType(String category) {
        if (category == null) {
            return "profile";
        }
        return switch (category) {
            case "profile", "preference" -> "profile";
            case "relationship", "commitment" -> "relation";
            case "event" -> "event";
            default -> "profile";
        };
    }

    static double mapFactImportance(String category) {
        if (category == null) {
            return 0.5;
        }
        return switch (category) {
            case "profile" -> 0.65;
            case "preference" -> 0.35;
            case "relationship", "commitment" -> 0.7;
            case "event" -> 0.55;
            default -> 0.5;
        };
    }

    // ---------------- episode（每日整理产物） ----------------

    /** 写入/覆盖某天的 episode（一天一条） */
    public void saveEpisode(long qqId, String day, String summary, String eventsJson,
                            double emotion, int msgCount) {
        String sql = "INSERT INTO episodes(qq_id, day, summary, events, emotion, msg_count, created_at) "
                + "VALUES(?,?,?,?,?,?,?) "
                + "ON CONFLICT(qq_id, day) DO UPDATE SET summary=excluded.summary, events=excluded.events, "
                + "emotion=excluded.emotion, msg_count=excluded.msg_count, created_at=excluded.created_at";
        update(sql, qqId, day, summary, eventsJson, Math.max(-1, Math.min(1, emotion)), msgCount, now());
    }

    public List<Map<String, Object>> recentEpisodes(long qqId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT day, summary, events, emotion, msg_count, created_at FROM episodes "
                + "WHERE qq_id=? ORDER BY day DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, qqId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", rs.getString("day"));
                    m.put("summary", rs.getString("summary"));
                    m.put("events", rs.getString("events"));
                    m.put("emotion", rs.getDouble("emotion"));
                    m.put("msgCount", rs.getInt("msg_count"));
                    m.put("createdAt", rs.getString("created_at"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("读取 episode 失败: {}", e.getMessage());
        }
        return out;
    }

    // ---------------- 读取 ----------------

    public List<Memory> active(long qqId) {
        return query("qq_id=? AND status='active'", qqId);
    }

    public List<Memory> topByType(long qqId, String type, int limit) {
        List<Memory> list = query("qq_id=? AND status='active' AND type=?", qqId, type);
        return list.size() <= limit ? list : new ArrayList<>(list.subList(0, limit));
    }

    /** 最近事件（没有命中关键词时的兜底） */
    public List<Memory> recentEvents(long qqId, int days, int limit) {
        String from = ts(LocalDateTime.now().minusDays(Math.max(1, days)));
        List<Memory> list = query("qq_id=? AND status='active' AND type='event' AND last_confirmed >= ?", qqId, from);
        return list.size() <= limit ? list : new ArrayList<>(list.subList(0, limit));
    }

    public List<Memory> dueFollowUps(long qqId, LocalDateTime nowT) {
        return query("qq_id=? AND status='active' AND follow_up_state='pending' "
                + "AND follow_up_at IS NOT NULL AND follow_up_at<>'' AND follow_up_at <= ?", qqId, ts(nowT));
    }

    public Memory byId(long id) {
        List<Memory> list = query("id=?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    private Memory findActive(long qqId, String type, String subject, String key) {
        List<Memory> list = query("qq_id=? AND status='active' AND type=? AND subject=? AND key=?",
                qqId, type, subject, key);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 后台分页（qqId 为 null 时跨用户；调用方按账号上下文逐个查） */
    public List<Memory> page(long qqId, String type, String status, String q, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(qqId, type, status, q, args);
        args.add(Math.max(1, limit));
        args.add(Math.max(0, offset));
        return queryRaw(where + " ORDER BY importance DESC, last_confirmed DESC LIMIT ? OFFSET ?", args);
    }

    public long count(long qqId, String type, String status, String q) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(qqId, type, status, q, args);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM memories WHERE " + where)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public long countActive(long qqId) {
        return count(qqId, null, "active", null);
    }

    public Map<String, Long> stats(long qqId) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String type : List.of("profile", "event", "relation", "world")) {
            map.put(type, count(qqId, type, "active", null));
        }
        map.put("active", count(qqId, null, "active", null));
        return map;
    }

    // ---------------- 内部 ----------------

    private String buildWhere(long qqId, String type, String status, String q, List<Object> args) {
        StringBuilder sb = new StringBuilder("qq_id=?");
        args.add(qqId);
        if (type != null && !type.isBlank()) {
            sb.append(" AND type=?");
            args.add(type);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            sb.append(" AND (key LIKE ? OR value LIKE ?)");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        return sb.toString();
    }

    private List<Memory> query(String where, Object... args) {
        List<Object> list = new ArrayList<>();
        for (Object a : args) {
            list.add(a);
        }
        return queryRaw(where + " ORDER BY importance DESC, last_confirmed DESC, id DESC", list);
    }

    private List<Memory> queryRaw(String whereOrSql, List<Object> args) {
        String sql = whereOrSql.startsWith("SELECT") ? whereOrSql
                : "SELECT " + COLS + " FROM memories WHERE " + whereOrSql;
        List<Memory> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询记忆失败: " + e.getMessage(), e);
        }
        return list;
    }

    private Memory map(ResultSet rs) throws Exception {
        long superseded = rs.getLong("superseded_by");
        Long supersededBy = rs.wasNull() ? null : superseded;
        return new Memory(rs.getLong("id"), rs.getLong("qq_id"), rs.getString("type"),
                rs.getString("subject"), rs.getString("key"), rs.getString("value"),
                rs.getString("source"), rs.getDouble("importance"), rs.getDouble("confidence"),
                rs.getDouble("emotion"), rs.getString("first_seen"), rs.getString("last_confirmed"),
                rs.getString("expires_at"), rs.getString("follow_up_at"), rs.getString("follow_up_state"),
                rs.getString("action_hint"), rs.getString("status"), supersededBy);
    }

    private void trim(long qqId) {
        if (countActive(qqId) <= MAX_ACTIVE_PER_USER) {
            return;
        }
        update("UPDATE memories SET status='archived' WHERE id IN ("
                + "SELECT id FROM memories WHERE qq_id=? AND status='active' "
                + "ORDER BY importance ASC, last_confirmed ASC LIMIT 20)", qqId);
    }

    private int update(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            log.warn("记忆写操作失败: {} ({})", e.getMessage(), sql);
            return 0;
        }
    }

    private static boolean sameValue(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.replaceAll("[\\s，。,.]", "");
        String y = b.replaceAll("[\\s，。,.]", "");
        return x.equals(y);
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "profile";
        }
        return switch (type.trim().toLowerCase()) {
            case "event", "relation", "world" -> type.trim().toLowerCase();
            case "relationship", "commitment" -> "relation";
            default -> "profile";
        };
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** 距今多少天（展示用） */
    public static long daysSince(String ts) {
        LocalDateTime t = parse(ts);
        return t == null ? 0 : Math.max(0, Duration.between(t, LocalDateTime.now()).toDays());
    }
}
