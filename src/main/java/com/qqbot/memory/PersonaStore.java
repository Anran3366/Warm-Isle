package com.qqbot.memory;

import com.qqbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 人设存储：所有机器人账号共用一份人设库（shared.db），
 * 「哪个账号用哪个人设」记在各账号自己的 settings（persona.activeId）。
 */
@Component
public class PersonaStore {

    private static final String ACTIVE_KEY = "persona.activeId";

    private final SharedDb db;
    private final Db botDb;
    private final SettingStore settings;
    private final BotProperties props;

    public PersonaStore(SharedDb db, Db botDb, SettingStore settings, BotProperties props) {
        this.db = db;
        this.botDb = botDb;
        this.settings = settings;
        this.props = props;
    }

    /** 首次启动时把配置里的人设落库并给当前账号启用 */
    @PostConstruct
    public void seed() {
        try {
            if (!isEmpty()) {
                return;
            }
            long id = create(props.getPersona().getName(), props.getPersona().getPrompt());
            activate(id);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PersonaStore.class)
                    .warn("初始化默认人设失败: {}", e.getMessage());
        }
    }

    public List<Persona> list() {
        long activeId = activeId();
        List<Persona> list = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, content FROM personas ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                list.add(new Persona(id, rs.getString("name"), rs.getString("content"), id == activeId));
            }
        } catch (Exception ignored) {
            // 返回空列表
        }
        return list;
    }

    /** 按 QQ 解析：优先该用户绑定的人设，其次当前账号全局人设。 */
    public Persona activeFor(long qqId) {
        Long botId = com.qqbot.bot.BotContext.get();
        if (botId != null) {
            Persona bound = binding(botId, qqId);
            if (bound != null) {
                return bound;
            }
        }
        return active();
    }

    public Persona binding(long botId, long qqId) {
        String sql = "SELECT persona_id, source_plugin FROM persona_bindings "
                + "WHERE bot_id=? AND qq_id=? AND enabled=1";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, botId);
            ps.setLong(2, qqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String source = rs.getString(2);
                if (source != null && !source.isBlank() && !pluginEnabled(source)) {
                    return null;
                }
                Persona p = byId(rs.getLong(1));
                return p == null ? null : new Persona(p.id(), p.name(), p.content(), true);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 插件停用后，它绑定的用户人设自动失效；重新启用后自动恢复。 */
    private boolean pluginEnabled(String pluginId) {
        Long botId = com.qqbot.bot.BotContext.get();
        if (botId == null) {
            return true;
        }
        try (Connection c = botDb.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enabled FROM plugin_state WHERE plugin_id=?")) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 给指定账号下的指定 QQ 绑定人设；一个 QQ 只保留一条绑定。 */
    public void bind(long botId, long qqId, long personaId, String sourcePlugin) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sql = "INSERT INTO persona_bindings(bot_id, qq_id, persona_id, enabled, "
                + "source_plugin, created_at, updated_at) VALUES(?,?,?,1,?,?,?) "
                + "ON CONFLICT(bot_id, qq_id) DO UPDATE SET persona_id=excluded.persona_id, "
                + "enabled=1, source_plugin=excluded.source_plugin, updated_at=excluded.updated_at";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, botId);
            ps.setLong(2, qqId);
            ps.setLong(3, personaId);
            ps.setString(4, sourcePlugin);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("绑定人设失败: " + e.getMessage(), e);
        }
    }

    public void setBindingEnabled(long botId, long qqId, boolean enabled) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE persona_bindings SET enabled=?, updated_at=? WHERE bot_id=? AND qq_id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.setLong(3, botId);
            ps.setLong(4, qqId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("切换人设绑定失败: " + e.getMessage(), e);
        }
    }

    public void unbind(long botId, long qqId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM persona_bindings WHERE bot_id=? AND qq_id=?")) {
            ps.setLong(1, botId);
            ps.setLong(2, qqId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("解绑人设失败: " + e.getMessage(), e);
        }
    }

    public Persona active() {
        long activeId = activeId();
        if (activeId > 0) {
            Persona p = byId(activeId);
            if (p != null) {
                return new Persona(p.id(), p.name(), p.content(), true);
            }
        }
        // 没设置过就退回最早的那个
        List<Persona> all = list();
        if (all.isEmpty()) {
            return null;
        }
        Persona first = all.get(0);
        return new Persona(first.id(), first.name(), first.content(), true);
    }

    public Persona byId(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, content FROM personas WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Persona(rs.getLong("id"), rs.getString("name"),
                            rs.getString("content"), rs.getLong("id") == activeId());
                }
            }
        } catch (Exception ignored) {
            // 不存在
        }
        return null;
    }

    public long create(String name, String content) {
        String sql = "INSERT INTO personas(name, content, active) VALUES(?,?,0)";
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, content);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT last_insert_rowid()");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("创建人设失败: " + e.getMessage(), e);
        }
    }

    public void update(long id, String name, String content) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE personas SET name=?, content=? WHERE id=?")) {
            ps.setString(1, name);
            ps.setString(2, content);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("更新人设失败: " + e.getMessage(), e);
        }
    }

    public void delete(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM personas WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("删除人设失败: " + e.getMessage(), e);
        }
        if (activeId() == id) {
            settings.set(ACTIVE_KEY, "");
        }
    }

    /** 给「当前机器人账号」启用人设；不同账号可以各自不同 */
    public void activate(long id) {
        settings.set(ACTIVE_KEY, String.valueOf(id));
    }

    public boolean isEmpty() {
        return list().isEmpty();
    }

    private long activeId() {
        String raw = settings.get(ACTIVE_KEY, null);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
