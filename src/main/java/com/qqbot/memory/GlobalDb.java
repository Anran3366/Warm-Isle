package com.qqbot.memory;

import com.qqbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 全局库（与机器人账号无关）：登录会话、扫码 token、机器人账号列表。
 * 多账号改造的第一步：把「认证/账号」从业务库里拆出来，
 * 这样切换账号不影响登录，登录也不依赖某个账号的库。
 */
@Component
public class GlobalDb {

    private static final Logger log = LoggerFactory.getLogger(GlobalDb.class);

    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS auth_sessions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              token_hash TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_auth_expires ON auth_sessions(expires_at)",
            """
            CREATE TABLE IF NOT EXISTS login_qr (
              token TEXT PRIMARY KEY,
              status TEXT NOT NULL DEFAULT 'pending',
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS kept_data (
              qq_id INTEGER PRIMARY KEY,
              file TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bot_accounts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER,
              name TEXT,
              backend TEXT NOT NULL DEFAULT 'napcat',
              container TEXT NOT NULL,
              ws_url TEXT NOT NULL,
              ws_port INTEGER NOT NULL,
              ui_port INTEGER NOT NULL,
              cache_dir TEXT,
              created_at TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1
            )
            """
    };

    private final Path path;
    private final String url;
    private final long defaultBotId;

    public GlobalDb(BotProperties props) {
        Path base = Path.of(props.getDb().getPath()).toAbsolutePath().getParent();
        this.path = base.resolve("global.db");
        this.url = "jdbc:sqlite:" + path;
        this.defaultBotId = props.getBot().getDefaultId();
    }

    @PostConstruct
    public void init() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                for (String ddl : SCHEMA) {
                    s.execute(ddl);
                }
                migrateAccounts(c);
                ensureBackendColumn(c);
                seedDefaultAccount(c);
            }
            log.info("全局库就绪: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("初始化全局库失败: " + e.getMessage(), e);
        }
    }

    /** 旧版以 qq_id 为主键，升级为自增 id（扫码前还不知道 QQ 号） */
    private void migrateAccounts(Connection c) throws SQLException {
        boolean hasId = false;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(bot_accounts)")) {
            while (rs.next()) {
                if ("id".equals(rs.getString("name"))) {
                    hasId = true;
                }
            }
        }
        if (hasId) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE bot_accounts RENAME TO bot_accounts_old");
            s.execute("""
                    CREATE TABLE bot_accounts (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      qq_id INTEGER,
                      name TEXT,
                      container TEXT NOT NULL,
                      ws_url TEXT NOT NULL,
                      ws_port INTEGER NOT NULL,
                      ui_port INTEGER NOT NULL,
                      cache_dir TEXT,
                      created_at TEXT NOT NULL,
                      enabled INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            s.execute("""
                    INSERT INTO bot_accounts(id, qq_id, name, container, ws_url, ws_port, ui_port, created_at, enabled)
                    SELECT 1, qq_id, name, container, 'ws://napcat:3001', ws_port, ui_port, created_at, enabled
                    FROM bot_accounts_old ORDER BY qq_id LIMIT 1
                    """);
            s.execute("DROP TABLE bot_accounts_old");
        }
        log.info("bot_accounts 已升级为自增 id");
    }

    /** 老库补 backend 列：已有账号一律视为 NapCat，行为不变 */
    private void ensureBackendColumn(Connection c) {
        boolean has = false;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(bot_accounts)")) {
            while (rs.next()) {
                if ("backend".equals(rs.getString("name"))) {
                    has = true;
                    break;
                }
            }
        } catch (SQLException e) {
            return;
        }
        if (has) {
            return;
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE bot_accounts ADD COLUMN backend TEXT NOT NULL DEFAULT 'napcat'");
            log.info("bot_accounts 已补 backend 列（默认 napcat）");
        } catch (SQLException e) {
            log.warn("补 backend 列失败: {}", e.getMessage());
        }
    }

    /** 表为空时登记第一个账号（单账号首次启动） */
    private void seedDefaultAccount(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM bot_accounts")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bot_accounts(id, qq_id, name, backend, container, ws_url, ws_port, ui_port, created_at, enabled)"
                        + " VALUES(1, ?, ?, 'napcat', ?, ?, 3001, 6099, ?, 1)")) {
            ps.setObject(1, null);
            ps.setString(2, "小满");
            ps.setString(3, "napcat");
            ps.setString(4, "ws://napcat:3001");
            ps.setString(5, java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
        }
        log.info("已登记首个机器人账号");
    }

    /** 系统里实际存在的第一个账号（仅在缺少上下文时用于后台任务兜底） */
    public Long firstAccountId() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM bot_accounts ORDER BY id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public Path getPath() {
        return path;
    }
}
