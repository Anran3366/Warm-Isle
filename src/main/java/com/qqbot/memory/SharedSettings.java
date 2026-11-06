package com.qqbot.memory;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 公用配置（知识库/表情包/话题屏蔽共用 shared.db 的 settings 表） */
@Component
public class SharedSettings {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SharedDb db;

    public SharedSettings(SharedDb db) {
        this.db = db;
    }

    public String get(String key, String defaultValue) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : defaultValue;
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO settings(key, value) VALUES(?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 写入失败不影响主流程
        }
    }
}
