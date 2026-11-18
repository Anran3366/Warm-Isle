package com.qqbot.plugin.data;

import com.qqbot.plugin.PluginProperties;
import com.qqbot.plugin.model.PluginManifest;
import com.qqbot.plugin.registry.InstalledPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件独立数据表：一个插件一个 SQLite 文件，宿主按 manifest.storage 自动建表。
 *
 * <p>插件拿不到连接，只能通过宿主 API 做参数化的 insert/list/get/delete；
 * 所有表自动带 bot_id，跨账号不可见。</p>
 */
@Service
public class PluginDataStore {

    private static final Logger log = LoggerFactory.getLogger(PluginDataStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PAGE = 200;

    private final PluginProperties props;

    public PluginDataStore(PluginProperties props) {
        this.props = props;
    }

    public Path root() {
        Path dir = Path.of(props.getDir()).toAbsolutePath().normalize();
        return dir.resolveSibling("plugin-data");
    }

    public Path dbFile(String pluginId) {
        if (pluginId == null || !pluginId.matches("^[a-z0-9][a-z0-9._-]{2,48}$")) {
            throw new PluginDataException("BAD_PLUGIN_ID", "插件 id 不合法");
        }
        return root().resolve(pluginId + ".db");
    }

    /** 安装/升级时调用：按 manifest 建表，记录 schema 版本。 */
    public synchronized void ensureSchema(InstalledPlugin plugin) {
        PluginManifest manifest = plugin.manifest();
        if (manifest == null || manifest.storage() == null
                || manifest.storage().tables() == null || manifest.storage().tables().isEmpty()) {
            return;
        }
        Path file = dbFile(plugin.pluginId());
        try {
            Files.createDirectories(file.getParent());
        } catch (Exception e) {
            throw new PluginDataException("IO", "创建插件数据目录失败: " + e.getMessage());
        }
        int version = manifest.storage().version() == null ? 1 : manifest.storage().version();
        try (Connection c = connect(file); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=3000");
            s.execute("CREATE TABLE IF NOT EXISTS __schema("
                    + "version INTEGER NOT NULL, updated_at TEXT NOT NULL)");
            int existing = schemaVersion(c);
            if (existing > version) {
                throw new PluginDataException("SCHEMA_TOO_NEW",
                        "插件数据版本 " + existing + " 高于 manifest 的 " + version + "，拒绝降级");
            }
            for (PluginManifest.TableSpec table : manifest.storage().tables()) {
                createTable(c, table);
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM __schema")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO __schema(version, updated_at) VALUES(?,?)")) {
                ps.setInt(1, Math.max(existing, version));
                ps.setString(2, LocalDateTime.now().format(TS));
                ps.executeUpdate();
            }
            log.info("插件数据表就绪 plugin={} version={} tables={}",
                    plugin.pluginId(), Math.max(existing, version), manifest.storage().tables().size());
        } catch (PluginDataException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginDataException("SCHEMA", "建表失败: " + e.getMessage());
        }
    }

    public long insert(InstalledPlugin plugin, String table, long botId, Map<String, Object> values) {
        PluginManifest.TableSpec spec = tableSpec(plugin, table);
        ensureSchema(plugin);
        Map<String, Object> cols = new LinkedHashMap<>();
        for (PluginManifest.ColumnSpec col : spec.columns()) {
            if (col == null || "id".equals(col.name()) || "bot_id".equals(col.name())) {
                continue;
            }
            if (values.containsKey(col.name())) {
                cols.put(col.name(), values.get(col.name()));
            }
        }
        StringBuilder names = new StringBuilder("bot_id");
        StringBuilder marks = new StringBuilder("?");
        for (String name : cols.keySet()) {
            names.append(',').append(name);
            marks.append(",?");
        }
        String sql = "INSERT INTO " + safe(table) + "(" + names + ") VALUES(" + marks + ")";
        try (Connection c = connect(dbFile(plugin.pluginId()));
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, botId);
            for (Object value : cols.values()) {
                ps.setObject(i++, value);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            throw new PluginDataException("INSERT", "写入 " + table + " 失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> list(InstalledPlugin plugin, String table, long botId,
                                          Long userId, int page, int size) {
        PluginManifest.TableSpec spec = tableSpec(plugin, table);
        ensureSchema(plugin);
        int p = Math.max(1, page);
        int s = Math.min(MAX_PAGE, Math.max(1, size));
        boolean hasUser = hasColumn(spec, "user_id");
        StringBuilder where = new StringBuilder("bot_id=?");
        if (hasUser && userId != null) {
            where.append(" AND user_id=?");
        }
        String sql = "SELECT * FROM " + safe(table) + " WHERE " + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection c = connect(dbFile(plugin.pluginId()));
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, botId);
            if (hasUser && userId != null) {
                ps.setLong(i++, userId);
            }
            ps.setInt(i++, s);
            ps.setInt(i, (p - 1) * s);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        } catch (Exception e) {
            throw new PluginDataException("LIST", "读取 " + table + " 失败: " + e.getMessage());
        }
    }

    public Map<String, Object> get(InstalledPlugin plugin, String table, long botId, long id) {
        tableSpec(plugin, table);
        ensureSchema(plugin);
        String sql = "SELECT * FROM " + safe(table) + " WHERE id=? AND bot_id=?";
        try (Connection c = connect(dbFile(plugin.pluginId()));
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, botId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = rows(rs);
                return rows.isEmpty() ? null : rows.get(0);
            }
        } catch (Exception e) {
            throw new PluginDataException("GET", "读取 " + table + " 失败: " + e.getMessage());
        }
    }

    public int delete(InstalledPlugin plugin, String table, long botId, long id) {
        tableSpec(plugin, table);
        ensureSchema(plugin);
        String sql = "DELETE FROM " + safe(table) + " WHERE id=? AND bot_id=?";
        try (Connection c = connect(dbFile(plugin.pluginId()));
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, botId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new PluginDataException("DELETE", "删除 " + table + " 失败: " + e.getMessage());
        }
    }

    public long count(InstalledPlugin plugin, String table, long botId, Long userId) {
        PluginManifest.TableSpec spec = tableSpec(plugin, table);
        ensureSchema(plugin);
        boolean hasUser = hasColumn(spec, "user_id");
        String sql = "SELECT count(*) FROM " + safe(table) + " WHERE bot_id=?"
                + (hasUser && userId != null ? " AND user_id=?" : "");
        try (Connection c = connect(dbFile(plugin.pluginId()));
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, botId);
            if (hasUser && userId != null) {
                ps.setLong(2, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> stats(InstalledPlugin plugin, long botId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", dbFile(plugin.pluginId()).toString());
        out.put("version", manifestVersion(plugin));
        List<Map<String, Object>> tables = new ArrayList<>();
        for (PluginManifest.TableSpec t : plugin.manifest().tables()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", t.name());
            one.put("label", t.label());
            one.put("rows", count(plugin, t.name(), botId, null));
            tables.add(one);
        }
        out.put("tables", tables);
        try {
            out.put("bytes", Files.size(dbFile(plugin.pluginId())));
        } catch (Exception e) {
            out.put("bytes", 0);
        }
        return out;
    }

    private void createTable(Connection c, PluginManifest.TableSpec table) throws Exception {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(safe(table.name()))
                .append("(id INTEGER PRIMARY KEY AUTOINCREMENT, bot_id INTEGER NOT NULL");
        for (PluginManifest.ColumnSpec col : table.columns()) {
            if (col == null || "id".equals(col.name()) || "bot_id".equals(col.name())) {
                continue;
            }
            ddl.append(", ").append(safe(col.name())).append(' ').append(typeOf(col.type()));
            if (Boolean.TRUE.equals(col.notNull())) {
                ddl.append(" NOT NULL");
            }
            if (col.defaultValue() != null) {
                ddl.append(" DEFAULT ").append(literal(col.defaultValue()));
            }
        }
        ddl.append(')');
        try (Statement s = c.createStatement()) {
            s.execute(ddl.toString());
            if (table.indexes() != null) {
                int i = 0;
                for (PluginManifest.IndexSpec idx : table.indexes()) {
                    if (idx == null || idx.columns() == null || idx.columns().isEmpty()) {
                        continue;
                    }
                    String name = "idx_" + table.name() + "_" + (i++);
                    StringBuilder cols = new StringBuilder();
                    for (String col : idx.columns()) {
                        if (cols.length() > 0) {
                            cols.append(',');
                        }
                        cols.append(safe(col));
                    }
                    s.execute("CREATE INDEX IF NOT EXISTS " + name + " ON "
                            + safe(table.name()) + "(" + cols + ")");
                }
            }
        }
    }

    private PluginManifest.TableSpec tableSpec(InstalledPlugin plugin, String table) {
        PluginManifest.TableSpec spec = plugin.manifest() == null ? null : plugin.manifest().table(table);
        if (spec == null) {
            throw new PluginDataException("TABLE_MISSING", "插件没有声明表: " + table);
        }
        return spec;
    }

    private static boolean hasColumn(PluginManifest.TableSpec spec, String name) {
        for (PluginManifest.ColumnSpec c : spec.columns()) {
            if (c != null && name.equals(c.name())) {
                return true;
            }
        }
        return false;
    }

    private static int schemaVersion(Connection c) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM __schema LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int manifestVersion(InstalledPlugin plugin) {
        return plugin.manifest() == null || plugin.manifest().storage() == null
                || plugin.manifest().storage().version() == null
                ? 1 : plugin.manifest().storage().version();
    }

    private static Connection connect(Path file) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(md.getColumnLabel(i), rs.getObject(i));
            }
            out.add(row);
        }
        return out;
    }

    private static String safe(String name) {
        if (name == null || !name.matches("^[a-z][a-z0-9_]{0,30}$")) {
            throw new PluginDataException("BAD_IDENTIFIER", "非法表/列名: " + name);
        }
        return "\"" + name + "\"";
    }

    private static String typeOf(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "integer" -> "INTEGER";
            case "real" -> "REAL";
            default -> "TEXT";
        };
    }

    private static String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value instanceof Boolean ? ((Boolean) value ? 1 : 0) : value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    public static class PluginDataException extends RuntimeException {
        private final String code;

        public PluginDataException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
