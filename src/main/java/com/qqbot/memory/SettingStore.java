package com.qqbot.memory;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 键值配置表：存放抽取水位线等运行时状态。 */
@Component
public class SettingStore {

    private final Db db;

    public SettingStore(Db db) {
        this.db = db;
    }

    public String get(String key, String defaultValue) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : defaultValue;
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        String sql = "INSERT INTO settings(key, value) VALUES(?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入设置失败: " + e.getMessage(), e);
        }
    }
}
