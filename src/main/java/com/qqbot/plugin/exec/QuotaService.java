package com.qqbot.plugin.exec;

import com.qqbot.memory.Db;
import com.qqbot.memory.SettingStore;
import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.model.PluginManifest;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

/**
 * 插件配额：每人每天（manifest 声明，默认 5，上限 50）+ 全实例每小时上限。
 *
 * <p>按人计数放 {@code settings}（键 {@code plugin.q:<pluginId>:<qqId>}），
 * 刻意不往 {@code plugin_runs} 里写 QQ 号 —— 运行表保持匿名。
 * 全实例每小时计数走 {@code plugin_runs}（无用户标识也能统计）。
 */
@Component
public class QuotaService {

    private final Db db;
    private final SettingStore settings;
    private final PluginProperties props;

    public QuotaService(Db db, SettingStore settings, PluginProperties props) {
        this.db = db;
        this.settings = settings;
        this.props = props;
    }

    /** 配额够不够（执行前调用） */
    public boolean allow(long qqId, PluginManifest manifest) {
        String pluginId = manifest == null ? "" : manifest.id();
        int perUser = manifest == null ? 5 : manifest.perUserPerDay();
        return usedToday(qqId, pluginId) < perUser && usedLastHour(pluginId) < globalPerHour(manifest);
    }

    /** 执行前占位：先记账，避免同一秒并发请求绕过配额 */
    public void consume(long qqId, PluginManifest manifest) {
        String pluginId = manifest == null ? "" : manifest.id();
        String key = key(pluginId, qqId);
        settings.set(key, LocalDate.now() + ":" + (usedToday(qqId, pluginId) + 1));
    }

    /** 执行失败（未真正消耗）时释放 */
    public void release(long qqId, PluginManifest manifest) {
        String pluginId = manifest == null ? "" : manifest.id();
        int used = usedToday(qqId, pluginId);
        if (used > 0) {
            settings.set(key(pluginId, qqId), LocalDate.now() + ":" + (used - 1));
        }
    }

    private int usedToday(long qqId, String pluginId) {
        String raw = settings.get(key(pluginId, qqId), "");
        int i = raw.indexOf(':');
        if (i <= 0) {
            return 0;
        }
        if (!LocalDate.now().toString().equals(raw.substring(0, i))) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.substring(i + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    private int usedLastHour(String pluginId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM plugin_runs WHERE plugin_id=? "
                             + "AND created_at >= datetime('now','localtime','-1 hour')")) {
            ps.setString(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private int globalPerHour(PluginManifest manifest) {
        if (manifest != null && manifest.quota() != null && manifest.quota().globalPerHour() != null) {
            return Math.min(600, Math.max(1, manifest.quota().globalPerHour()));
        }
        return props.getLimits().getGlobalPerHour();
    }

    private String key(String pluginId, long qqId) {
        return "plugin.q:" + pluginId + ":" + qqId;
    }
}
