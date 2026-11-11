package com.qqbot.plugin.memory;

import com.qqbot.memory.Db;
import com.qqbot.memory.MemoryStore;
import com.qqbot.plugin.model.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件记忆写入（设计文档 §8.7）。
 *
 * <p>硬约束由宿主强制，不信作者：
 * subject 只能是 bot（不污染用户画像）、type 只能 relation/event、importance 封顶 0.85、
 * key 加插件前缀（避免两个插件写同一个 key 互相 archive）、每天每插件 ≤5 条。
 */
@Component
public class PluginMemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(PluginMemoryWriter.class);
    private static final Pattern VAR = Pattern.compile("\\{(\\w+)}");
    private static final int DAILY_CAP = 5;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MemoryStore memories;
    private final Db db;

    public PluginMemoryWriter(MemoryStore memories, Db db) {
        this.memories = memories;
        this.db = db;
    }

    /**
     * @param vars 模板变量（来自执行结果，如 song/duration）
     * @return 是否写入
     */
    public boolean write(long qqId, String pluginId, PluginManifest manifest, Map<String, Object> vars) {
        if (manifest == null || "none".equals(manifest.memoryPolicy())) {
            return false;
        }
        PluginManifest.MemorySpec spec = manifest.memory();
        if (spec == null || spec.keyTemplate() == null || spec.valueTemplate() == null) {
            return false;
        }
        if (writtenToday(qqId, pluginId) >= DAILY_CAP) {
            log.debug("插件 {} 今日记忆已达上限，跳过", pluginId);
            return false;
        }
        Map<String, Object> safe = vars == null ? Map.of() : vars;
        String key;
        String value;
        try {
            key = render(spec.keyTemplate(), safe);
            value = render(spec.valueTemplate(), safe);
        } catch (IllegalArgumentException e) {
            log.warn("插件 {} 记忆模板渲染失败: {}", pluginId, e.getMessage());
            return false;
        }
        if (value.isBlank() || value.length() < 6 || value.length() > 80) {
            return false;
        }
        double importance = spec.importance() == null ? 0.5 : Math.min(0.85, spec.importance());
        String type = "event".equals(spec.type()) ? "event" : "relation";
        memories.upsert(qqId, new MemoryStore.Draft(type, "bot",
                pluginId + ":" + key, value, "plugin:" + pluginId,
                importance, 0.9, 0, null, null, null));
        return true;
    }

    /** 今天这个插件给这个用户写了几条（含已归档，防绕过） */
    private int writtenToday(long qqId, String pluginId) {
        String today = LocalDate.now().format(DAY);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM memories WHERE qq_id=? AND source=? "
                             + "AND substr(COALESCE(last_confirmed, first_seen),1,10)=?")) {
            ps.setLong(1, qqId);
            ps.setString(2, "plugin:" + pluginId);
            ps.setString(3, today);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return DAILY_CAP;
        }
    }

    /**
     * 最近做过的事（指代回填的辅路）：按时间取，不按相似度。
     * 「再唱一遍上次那首」这种句子关键词命中是 0，只能靠这条。
     */
    public String lastEntity(long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key FROM memories WHERE qq_id=? AND subject='bot' "
                             + "AND source LIKE 'plugin%' AND status='active' "
                             + "AND last_confirmed >= datetime('now','localtime','-7 days') "
                             + "ORDER BY last_confirmed DESC LIMIT 1")) {
            ps.setLong(1, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String key = rs.getString(1);
                int i = key == null ? -1 : key.lastIndexOf(':');
                return i >= 0 && i < key.length() - 1 ? key.substring(i + 1) : null;
            }
        } catch (Exception e) {
            log.debug("读取最近行为失败: {}", e.getMessage());
            return null;
        }
    }

    /** 渲染「{song}」这类占位符；缺变量直接抛错，不做空字符串替换（§10.8） */
    static String render(String template, Map<String, Object> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException("缺少模板变量 {" + m.group(1) + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 供执行器收集模板变量 */
    public static Map<String, Object> vars(String... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
