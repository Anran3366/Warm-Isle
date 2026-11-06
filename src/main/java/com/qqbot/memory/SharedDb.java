package com.qqbot.memory;

import com.qqbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 公用库：知识库、表情包、话题屏蔽。所有机器人账号共享。
 * 首次初始化时从默认账号的库里复制一次，避免丢历史数据。
 */
@Component
public class SharedDb {

    private static final Logger log = LoggerFactory.getLogger(SharedDb.class);

    private final Path path;
    private final String url;
    private final Path migrateFrom;

    public SharedDb(BotProperties props) {
        Path base = Path.of(props.getDb().getPath()).toAbsolutePath().getParent();
        this.path = base.resolve("shared.db");
        this.url = "jdbc:sqlite:" + path;
        this.migrateFrom = base.resolve("bots").resolve("1.db");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(path.getParent());
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                for (String ddl : Db.SCHEMA) {
                    s.execute(ddl);
                }
                try {
                    s.execute("ALTER TABLE knowledge_docs ADD COLUMN parse_type TEXT NOT NULL DEFAULT 'style'");
                } catch (Exception ignored) {
                    // 列已存在
                }
                try {
                    s.execute("ALTER TABLE emoji ADD COLUMN description TEXT");
                } catch (Exception ignored) {
                    // 列已存在
                }
                for (String stmt : new String[]{
                        "ALTER TABLE emoji ADD COLUMN status TEXT NOT NULL DEFAULT 'approved'",
                        "ALTER TABLE emoji ADD COLUMN sent_count INTEGER NOT NULL DEFAULT 0",
                        "ALTER TABLE emoji ADD COLUMN last_sent_at TEXT",
                        "ALTER TABLE emoji ADD COLUMN image_hash TEXT",
                        "ALTER TABLE emoji ADD COLUMN image_ahash TEXT",
                        "CREATE TABLE IF NOT EXISTS emoji_send_log (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "emoji_id INTEGER NOT NULL, qq_id INTEGER NOT NULL, emotion TEXT, "
                                + "sent_at TEXT NOT NULL, replied_at TEXT)"
                }) {
                    try {
                        s.execute(stmt);
                    } catch (Exception ignored) {
                        // 列/表已存在
                    }
                }
                copyFromBot(c);
            }
            log.info("公用库就绪: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("初始化公用库失败: " + e.getMessage(), e);
        }
    }

    /** 首次：把默认账号库里的知识库/表情包/话题屏蔽复制过来 */
    private void copyFromBot(Connection c) throws SQLException {
        if (!Files.exists(migrateFrom)) {
            return;
        }
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM knowledge_docs")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        String src = migrateFrom.toAbsolutePath().toString().replace("'", "''");
        try (Statement s = c.createStatement()) {
            s.execute("ATTACH DATABASE '" + src + "' AS bot");
            for (String[] t : new String[][]{
                    {"knowledge_docs", "id, filename, stored_name, category, status, error, size, chunk_count, uploaded_at, processed_at, enabled"},
                    {"knowledge_chunks", "id, doc_id, category, kind, text, hash, created_at"},
                    {"knowledge_categories", "name, sort"},
                    {"emoji", "*"},
                    {"settings", "*"}
            }) {
                try {
                    s.execute("INSERT OR IGNORE INTO main." + t[0] + " SELECT " + t[1] + " FROM bot." + t[0]);
                } catch (Exception e) {
                    log.warn("复制 {} 失败: {}", t[0], e.getMessage());
                }
            }
            s.execute("DETACH DATABASE bot");
        }
        log.info("已从默认账号库复制公用数据");
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public Path getPath() {
        return path;
    }
}