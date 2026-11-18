package com.qqbot.plugin.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.memory.Db;
import com.qqbot.memory.MessageStore;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
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
 * 插件配置：作者在 manifest.config 声明字段，后台自动渲染，值存在 plugin_state.config。
 */
@Service
public class PluginConfigService {

    private static final Logger log = LoggerFactory.getLogger(PluginConfigService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TEXT = 500;
    private static final int MAX_TEXTAREA = 4000;
    private static final int MAX_USERS = 200;

    private final Db db;
    private final MessageStore messages;
    private final ObjectMapper mapper = new ObjectMapper();

    public PluginConfigService(Db db, MessageStore messages) {
        this.db = db;
        this.messages = messages;
    }

    public Map<String, Object> read(InstalledPlugin plugin) {
        PluginManifest manifest = plugin.manifest();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stored = stored(plugin.pluginId());
        for (PluginManifest.ConfigField f : manifest.configFieldsOrEmpty()) {
            if (f == null || f.key() == null) {
                continue;
            }
            Object value = stored.containsKey(f.key()) ? stored.get(f.key()) : defaultValue(f);
            out.put(f.key(), normalize(f, value));
        }
        return out;
    }

    public Map<String, Object> save(InstalledPlugin plugin, Map<String, Object> input) {
        PluginManifest manifest = plugin.manifest();
        Map<String, Object> values = new LinkedHashMap<>();
        for (PluginManifest.ConfigField f : manifest.configFieldsOrEmpty()) {
            if (f == null || f.key() == null) {
                continue;
            }
            Object raw = input == null ? null : input.get(f.key());
            values.put(f.key(), validate(f, raw));
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE plugin_state SET config=?, updated_at=? WHERE plugin_id=?")) {
            ps.setString(1, mapper.writeValueAsString(values));
            ps.setString(2, LocalDateTime.now().format(TS));
            ps.setString(3, plugin.pluginId());
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO plugin_state(plugin_id, enabled, config, updated_at) "
                                + "VALUES(?,0,?,?)")) {
                    ins.setString(1, plugin.pluginId());
                    ins.setString(2, mapper.writeValueAsString(values));
                    ins.setString(3, LocalDateTime.now().format(TS));
                    ins.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new PluginDataStore.PluginDataException("CONFIG_SAVE", "保存配置失败: " + e.getMessage());
        }
        return values;
    }

    /** 按 key 取单个值（scheduler 用）。 */
    public Object value(InstalledPlugin plugin, String key) {
        return read(plugin).get(key);
    }

    private Map<String, Object> stored(String pluginId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT config FROM plugin_state WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                    return mapper.readValue(rs.getString(1), Map.class);
                }
            }
        } catch (Exception e) {
            log.debug("读取插件配置失败: {}", e.getMessage());
        }
        return Map.of();
    }

    private Object validate(PluginManifest.ConfigField f, Object raw) {
        String type = f.typeOrDefault();
        switch (type) {
            case "number" -> {
                double v = raw instanceof Number n ? n.doubleValue()
                        : raw == null || String.valueOf(raw).isBlank() ? 0
                        : Double.parseDouble(String.valueOf(raw));
                if (f.min() != null && v < f.min().doubleValue()) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 不能小于 " + f.min());
                }
                if (f.max() != null && v > f.max().doubleValue()) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 不能大于 " + f.max());
                }
                return v;
            }
            case "switch" -> {
                return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            }
            case "select" -> {
                String v = raw == null ? "" : String.valueOf(raw);
                Set<String> allowed = new LinkedHashSet<>();
                if (f.options() != null) {
                    f.options().forEach(o -> allowed.add(o.value()));
                }
                if (!v.isBlank() && !allowed.contains(v)) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 不在可选范围");
                }
                return v;
            }
            case "user" -> {
                long id = toLong(raw);
                if (id > 0 && !userIds().contains(id)) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 不是这个机器人聊过天的人");
                }
                return id;
            }
            case "users" -> {
                List<Long> ids = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        long id = toLong(o);
                        if (id > 0 && !ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
                if (ids.size() > MAX_USERS) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 最多选 " + MAX_USERS + " 人");
                }
                Set<Long> known = userIds();
                for (Long id : ids) {
                    if (!known.contains(id)) {
                        throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                                f.label() + " 包含没聊过天的人: " + id);
                    }
                }
                return ids;
            }
            case "time" -> {
                String v = raw == null ? "" : String.valueOf(raw).trim();
                if (!v.isBlank() && !v.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 需为 HH:mm");
                }
                return v;
            }
            case "textarea" -> {
                String v = raw == null ? "" : String.valueOf(raw);
                if (v.length() > MAX_TEXTAREA) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 太长");
                }
                return v;
            }
            default -> {
                String v = raw == null ? "" : String.valueOf(raw);
                if (v.length() > MAX_TEXT) {
                    throw new PluginDataStore.PluginDataException("BAD_CONFIG",
                            f.label() + " 太长");
                }
                return v;
            }
        }
    }

    private Object normalize(PluginManifest.ConfigField f, Object value) {
        return switch (f.typeOrDefault()) {
            case "number" -> value instanceof Number n ? n.doubleValue() : toLong(value);
            case "switch" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            case "user" -> toLong(value);
            case "users" -> {
                List<Long> ids = new ArrayList<>();
                if (value instanceof List<?> list) {
                    list.forEach(o -> ids.add(toLong(o)));
                }
                yield ids;
            }
            default -> value == null ? "" : String.valueOf(value);
        };
    }

    private Object defaultValue(PluginManifest.ConfigField f) {
        if (f.defaultValue() != null) {
            return f.defaultValue();
        }
        return switch (f.typeOrDefault()) {
            case "switch" -> false;
            case "number" -> 0;
            case "user" -> 0L;
            case "users" -> List.of();
            default -> "";
        };
    }

    private Set<Long> userIds() {
        Set<Long> out = new LinkedHashSet<>();
        try {
            out.addAll(messages.users());
        } catch (Exception e) {
            log.debug("读取插件可选用户失败: {}", e.getMessage());
        }
        return out;
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
}
