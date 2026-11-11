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
    private final SettingStore settings;
    private final BotProperties props;

    public PersonaStore(SharedDb db, SettingStore settings, BotProperties props) {
        this.db = db;
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
