package com.qqbot.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.bot.BotAccountRunner;
import com.qqbot.bot.BotContext;
import com.qqbot.bot.OneBotClient;
import com.qqbot.bot.OneBotRegistry;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import com.qqbot.plugin.data.PluginConfigService;
import com.qqbot.plugin.data.PluginDataStore;
import com.qqbot.plugin.exec.PluginOutput;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import com.qqbot.plugin.registry.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件定时任务：宿主每 30 秒扫一次 plugin_schedules，到点调用声明的 action。
 *
 * <p>插件自己不持有线程；发送、存档、限额都由宿主做。</p>
 */
@Service
public class PluginScheduler {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TARGETS = 200;

    private final PluginProperties props;
    private final BotAccountRunner accounts;
    private final PluginRegistry registry;
    private final PluginGateway gateway;
    private final OneBotRegistry clients;
    private final MessageStore messages;
    private final Db db;
    private final PluginConfigService configs;
    private final PluginDataStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public PluginScheduler(PluginProperties props, BotAccountRunner accounts,
                           PluginRegistry registry, PluginGateway gateway,
                           OneBotRegistry clients, MessageStore messages, Db db,
                           PluginConfigService configs, PluginDataStore store) {
        this.props = props;
        this.accounts = accounts;
        this.registry = registry;
        this.gateway = gateway;
        this.clients = clients;
        this.messages = messages;
        this.db = db;
        this.configs = configs;
        this.store = store;
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        if (!props.isEnabled()) {
            return;
        }
        for (Long botId : accounts.accountIds()) {
            try {
                BotContext.run(botId, () -> tickBot(botId));
            } catch (Exception e) {
                log.warn("插件定时任务失败 botId={}: {}", botId, e.getMessage());
            }
        }
    }

    private void tickBot(long botId) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (InstalledPlugin plugin : registry.activeFor(botId)) {
            sync(botId, plugin, now);
        }
        for (ScheduleRow row : due(botId, now)) {
            try {
                runDue(botId, row, now);
            } catch (Exception e) {
                log.warn("执行定时任务失败 plugin={} schedule={}: {}",
                        row.pluginId, row.scheduleId, e.getMessage());
            }
        }
    }

    /** 把 manifest 的 schedules 同步到表；manifest 里删掉的置为停用。 */
    private void sync(long botId, InstalledPlugin plugin, LocalDateTime now) {
        List<PluginManifest.ScheduleSpec> specs = plugin.manifest().schedulesOrEmpty();
        String ts = now.format(TS);
        try (Connection c = db.open()) {
            try (PreparedStatement off = c.prepareStatement(
                    "UPDATE plugin_schedules SET enabled=0, updated_at=? WHERE plugin_id=?")) {
                off.setString(1, ts);
                off.setString(2, plugin.pluginId());
                off.executeUpdate();
            }
            for (PluginManifest.ScheduleSpec s : specs) {
                if (s == null || s.id() == null) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO plugin_schedules(plugin_id, schedule_id, cron, timezone, target, "
                                + "scope, recipients_key, action, content_source, template, catch_up, "
                                + "grace_minutes, enabled, updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?) "
                                + "ON CONFLICT(plugin_id, schedule_id) DO UPDATE SET "
                                + "cron=excluded.cron, timezone=excluded.timezone, target=excluded.target, "
                                + "scope=excluded.scope, recipients_key=excluded.recipients_key, "
                                + "action=excluded.action, content_source=excluded.content_source, "
                                + "template=excluded.template, catch_up=excluded.catch_up, "
                                + "grace_minutes=excluded.grace_minutes, enabled=1, updated_at=excluded.updated_at")) {
                    ps.setString(1, plugin.pluginId());
                    ps.setString(2, s.id());
                    ps.setString(3, s.cron());
                    ps.setString(4, s.timezone() == null ? "Asia/Shanghai" : s.timezone());
                    ps.setString(5, s.target() == null ? "dm" : s.target());
                    ps.setString(6, s.scope() == null ? "per-user" : s.scope());
                    ps.setString(7, s.recipientsKey());
                    ps.setString(8, s.action());
                    ps.setString(9, s.contentSource() == null ? "model" : s.contentSource());
                    ps.setString(10, s.template());
                    ps.setString(11, s.catchUp() == null ? "skip" : s.catchUp());
                    ps.setInt(12, s.graceMinutes() == null ? 10 : s.graceMinutes());
                    ps.setString(13, ts);
                    ps.executeUpdate();
                }
            }
            // 清掉 manifest 里已经不存在的 schedule
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM plugin_schedules WHERE plugin_id=? AND enabled=0")) {
                del.setString(1, plugin.pluginId());
                del.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("同步插件定时失败 plugin={}: {}", plugin.pluginId(), e.getMessage());
        }
    }

    private List<ScheduleRow> due(long botId, LocalDateTime now) {
        List<ScheduleRow> out = new ArrayList<>();
        String sql = "SELECT plugin_id, schedule_id, cron, timezone, target, scope, recipients_key, "
                + "action, content_source, template, catch_up, grace_minutes, last_occurrence "
                + "FROM plugin_schedules WHERE enabled=1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ScheduleRow row = row(rs);
                try {
                    CronExpression cron = CronExpression.parse(row.cron);
                    LocalDateTime last = parse(row.lastOccurrence);
                    LocalDateTime base = last == null ? now.minusSeconds(1) : last;
                    LocalDateTime next = cron.next(base);
                    if (next == null || next.isAfter(now)) {
                        continue;
                    }
                    int grace = row.graceMinutes <= 0 ? 10 : row.graceMinutes;
                    if (Duration.between(next, now).toMinutes() > grace) {
                        markOccurrence(row, next, now);
                        continue;
                    }
                    out.add(row.withOccurrence(next));
                } catch (Exception e) {
                    log.debug("跳过无法解析的定时 plugin={} schedule={}: {}",
                            row.pluginId, row.scheduleId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("读取插件定时失败: {}", e.getMessage());
        }
        return out;
    }

    private void runDue(long botId, ScheduleRow row, LocalDateTime now) {
        InstalledPlugin plugin = registry.find(row.pluginId);
        if (plugin == null || plugin.manifest() == null
                || !registry.enabled(botId, row.pluginId)) {
            markOccurrence(row, row.occurrence, now);
            return;
        }
        Object enabled = configs.read(plugin).get("enabled");
        if (Boolean.FALSE.equals(enabled)) {
            markOccurrence(row, row.occurrence, now);
            return;
        }
        List<Long> targets = targets(plugin, row);
        for (Long uid : targets) {
            if (!claim(row, uid, row.occurrence)) {
                continue;
            }
            try {
                String text = generate(plugin, row, uid);
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("生成结果为空");
                }
                OneBotClient client = clients.get(botId);
                if (client == null) {
                    throw new IllegalStateException("机器人不在线");
                }
                client.sendPrivate(uid, text);
                saveRecord(plugin, row, uid, text);
                ok(row, uid, row.occurrence, text);
            } catch (Exception e) {
                error(row, uid, row.occurrence, e.getMessage());
            }
        }
        markOccurrence(row, row.occurrence, now);
    }

    /** 手动触发（后台/验收）：dryRun 只生成不发送；save 决定是否写表。 */
    public Map<String, Object> runNow(InstalledPlugin plugin, String scheduleId, long botId,
                                      long qqId, boolean dryRun, boolean save) {
        PluginManifest.ScheduleSpec spec = null;
        for (PluginManifest.ScheduleSpec s : plugin.manifest().schedulesOrEmpty()) {
            if (s != null && scheduleId.equals(s.id())) {
                spec = s;
                break;
            }
        }
        if (spec == null) {
            throw new IllegalStateException("插件没有声明这个定时任务: " + scheduleId);
        }
        long uid = qqId;
        if (uid <= 0) {
            List<Long> targets = targets(plugin, new ScheduleRow(
                    plugin.pluginId(), spec.id(), spec.cron(), spec.timezone(), spec.target(),
                    spec.scope(), spec.recipientsKey(), spec.action(), spec.contentSource(),
                    spec.template(), spec.catchUp(), spec.graceMinutes(), null, null));
            uid = targets.isEmpty() ? 0 : targets.get(0);
        }
        String text = generate(plugin, new ScheduleRow(plugin.pluginId(), spec.id(), spec.cron(),
                spec.timezone(), spec.target(), spec.scope(), spec.recipientsKey(), spec.action(),
                spec.contentSource(), spec.template(), spec.catchUp(), spec.graceMinutes(), null, null), uid);
        if (!dryRun && uid > 0) {
            OneBotClient client = clients.get(botId);
            if (client == null) {
                throw new IllegalStateException("机器人不在线");
            }
            client.sendPrivate(uid, text);
        }
        if (save && uid > 0) {
            saveRecord(plugin, new ScheduleRow(plugin.pluginId(), spec.id(), spec.cron(),
                    spec.timezone(), spec.target(), spec.scope(), spec.recipientsKey(), spec.action(),
                    spec.contentSource(), spec.template(), spec.catchUp(), spec.graceMinutes(), null, null),
                    uid, text);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("userId", uid);
        out.put("text", text);
        out.put("sent", !dryRun && uid > 0);
        out.put("saved", save && uid > 0);
        return out;
    }

    private String generate(InstalledPlugin plugin, ScheduleRow row, long uid) {
        if ("template".equals(row.contentSource)) {
            return row.template == null ? "" : row.template;
        }
        PluginOutput output = gateway.invokeScheduled(plugin, row.action, Map.of(), uid);
        if (output == null) {
            return "";
        }
        if (output.text() != null && !output.text().isBlank()) {
            return output.text();
        }
        return output.caption() == null ? "" : output.caption();
    }

    private List<Long> targets(InstalledPlugin plugin, ScheduleRow row) {
        if ("config".equals(row.scope)) {
            Object value = configs.read(plugin).get(row.recipientsKey);
            List<Long> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object o : list) {
                    long id = toLong(o);
                    if (id > 0) {
                        out.add(id);
                    }
                }
            } else if (value != null) {
                for (String part : String.valueOf(value).split("[,，、\\s]+")) {
                    long id = toLong(part);
                    if (id > 0) {
                        out.add(id);
                    }
                }
            }
            return out.stream().distinct().limit(MAX_TARGETS).toList();
        }
        if ("global".equals(row.scope)) {
            return List.of(0L);
        }
        try {
            return messages.recentUsers(LocalDateTime.now().minusDays(7).format(TS), MAX_TARGETS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void saveRecord(InstalledPlugin plugin, ScheduleRow row, long uid, String text) {
        PluginManifest.ScheduleSpec spec = find(plugin, row.scheduleId);
        if (spec == null || spec.save() == null || spec.save().table() == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> fields = spec.save().fields();
        if (fields != null) {
            Map<String, Object> config = configs.read(plugin);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String raw = e.getValue() == null ? "" : e.getValue();
                values.put(e.getKey(), resolve(raw, uid, text, row, config));
            }
        }
        Long botId = BotContext.get();
        if (botId == null) {
            return;
        }
        store.insert(plugin, spec.save().table(), botId, values);
    }

    private Object resolve(String raw, long uid, String text, ScheduleRow row, Map<String, Object> config) {
        if ("$recipient".equals(raw)) {
            return uid;
        }
        if ("$output".equals(raw)) {
            return text;
        }
        if ("$now".equals(raw)) {
            return LocalDateTime.now().format(TS);
        }
        if ("$date".equals(raw)) {
            return LocalDate.now().toString();
        }
        if ("$schedule".equals(raw)) {
            return row.scheduleId;
        }
        if ("$period".equals(raw)) {
            LocalDate today = LocalDate.now();
            java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.ISO;
            return today.get(wf.weekBasedYear()) + "-W"
                    + String.format("%02d", today.get(wf.weekOfWeekBasedYear()));
        }
        if (raw.startsWith("$config.")) {
            return config.get(raw.substring("$config.".length()));
        }
        return raw;
    }

    private boolean claim(ScheduleRow row, long uid, LocalDateTime occurrence) {
        String sql = "INSERT INTO plugin_schedule_runs(plugin_id, schedule_id, user_id, occurrence, "
                + "status, started_at) VALUES(?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.pluginId);
            ps.setString(2, row.scheduleId);
            ps.setLong(3, uid);
            ps.setString(4, occurrence.format(TS));
            ps.setString(5, "running");
            ps.setString(6, LocalDateTime.now().format(TS));
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ok(ScheduleRow row, long uid, LocalDateTime occurrence, String output) {
        updateRun(row, uid, occurrence, "ok", null, output);
    }

    private void error(ScheduleRow row, long uid, LocalDateTime occurrence, String error) {
        updateRun(row, uid, occurrence, "error", error, null);
    }

    private void updateRun(ScheduleRow row, long uid, LocalDateTime occurrence,
                           String status, String error, String output) {
        String sql = "UPDATE plugin_schedule_runs SET status=?, error=?, output=?, finished_at=? "
                + "WHERE plugin_id=? AND schedule_id=? AND user_id=? AND occurrence=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, error == null ? null : error.length() > 300 ? error.substring(0, 300) : error);
            ps.setString(3, output == null ? null : output.length() > 600 ? output.substring(0, 600) : output);
            ps.setString(4, LocalDateTime.now().format(TS));
            ps.setString(5, row.pluginId);
            ps.setString(6, row.scheduleId);
            ps.setLong(7, uid);
            ps.setString(8, occurrence.format(TS));
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("更新定时运行记录失败: {}", e.getMessage());
        }
    }

    private void markOccurrence(ScheduleRow row, LocalDateTime occurrence, LocalDateTime now) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_schedules SET last_occurrence=?, updated_at=? "
                             + "WHERE plugin_id=? AND schedule_id=?")) {
            ps.setString(1, occurrence.format(TS));
            ps.setString(2, now.format(TS));
            ps.setString(3, row.pluginId);
            ps.setString(4, row.scheduleId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("更新定时水位失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> schedules() {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT plugin_id, schedule_id, cron, timezone, target, scope, recipients_key, "
                + "action, content_source, template, enabled, last_occurrence, updated_at "
                + "FROM plugin_schedules ORDER BY plugin_id, schedule_id";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pluginId", rs.getString(1));
                row.put("scheduleId", rs.getString(2));
                row.put("cron", rs.getString(3));
                row.put("timezone", rs.getString(4));
                row.put("target", rs.getString(5));
                row.put("scope", rs.getString(6));
                row.put("recipientsKey", rs.getString(7));
                row.put("action", rs.getString(8));
                row.put("contentSource", rs.getString(9));
                row.put("enabled", rs.getInt(11) == 1);
                row.put("lastOccurrence", rs.getString(12));
                row.put("updatedAt", rs.getString(13));
                out.add(row);
            }
        } catch (Exception e) {
            log.debug("读取定时列表失败: {}", e.getMessage());
        }
        return out;
    }

    public List<Map<String, Object>> runs(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, plugin_id, schedule_id, user_id, occurrence, status, error, "
                + "substr(output,1,200), started_at, finished_at FROM plugin_schedule_runs "
                + "ORDER BY id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, Math.min(200, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong(1));
                    row.put("pluginId", rs.getString(2));
                    row.put("scheduleId", rs.getString(3));
                    row.put("userId", rs.getLong(4));
                    row.put("occurrence", rs.getString(5));
                    row.put("status", rs.getString(6));
                    row.put("error", rs.getString(7));
                    row.put("output", rs.getString(8));
                    row.put("startedAt", rs.getString(9));
                    row.put("finishedAt", rs.getString(10));
                    out.add(row);
                }
            }
        } catch (Exception e) {
            log.debug("读取定时运行记录失败: {}", e.getMessage());
        }
        return out;
    }

    private static PluginManifest.ScheduleSpec find(InstalledPlugin plugin, String id) {
        for (PluginManifest.ScheduleSpec s : plugin.manifest().schedulesOrEmpty()) {
            if (s != null && id.equals(s.id())) {
                return s;
            }
        }
        return null;
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TS);
        } catch (Exception e) {
            return null;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static ScheduleRow row(ResultSet rs) throws Exception {
        return new ScheduleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getInt(12), rs.getString(13), null);
    }

    private record ScheduleRow(String pluginId, String scheduleId, String cron, String timezone,
                               String target, String scope, String recipientsKey, String action,
                               String contentSource, String template, String catchUp,
                               Integer graceMinutes, String lastOccurrence, LocalDateTime occurrence) {
        ScheduleRow withOccurrence(LocalDateTime time) {
            return new ScheduleRow(pluginId, scheduleId, cron, timezone, target, scope, recipientsKey,
                    action, contentSource, template, catchUp, graceMinutes, lastOccurrence, time);
        }
    }
}
