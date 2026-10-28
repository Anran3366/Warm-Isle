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
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite 连接与建表。WAL 模式，单机读写足够。
 * 表结构对应 PLAN.md 第 6 节。
 */
@Component
public class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    public static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              role TEXT NOT NULL,
              content TEXT NOT NULL,
              kind TEXT NOT NULL DEFAULT 'text',
              emoji_id INTEGER,
              voice_file TEXT,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_messages_qq ON messages(qq_id, id)",
            """
            CREATE TABLE IF NOT EXISTS facts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              category TEXT NOT NULL,
              key TEXT NOT NULL,
              value TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 1.0,
              status TEXT NOT NULL DEFAULT 'active',
              first_seen TEXT,
              last_confirmed TEXT,
              source_msg_id INTEGER
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_facts_qq ON facts(qq_id, status)",
            """
            CREATE TABLE IF NOT EXISTS memories (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              type TEXT NOT NULL,
              subject TEXT NOT NULL DEFAULT 'user',
              key TEXT NOT NULL,
              value TEXT NOT NULL,
              source TEXT NOT NULL DEFAULT 'chat',
              importance REAL NOT NULL DEFAULT 0.5,
              confidence REAL NOT NULL DEFAULT 0.8,
              emotion REAL NOT NULL DEFAULT 0,
              first_seen TEXT,
              last_confirmed TEXT,
              expires_at TEXT,
              follow_up_at TEXT,
              follow_up_state TEXT NOT NULL DEFAULT 'none',
              action_hint TEXT,
              status TEXT NOT NULL DEFAULT 'active',
              superseded_by INTEGER
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_memories_qq ON memories(qq_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(qq_id, type, status)",
            "CREATE INDEX IF NOT EXISTS idx_memories_follow ON memories(qq_id, follow_up_state, follow_up_at)",
            """
            CREATE TABLE IF NOT EXISTS episodes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              day TEXT NOT NULL,
              summary TEXT NOT NULL,
              events TEXT,
              emotion REAL NOT NULL DEFAULT 0,
              msg_count INTEGER NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_episodes_qq_day ON episodes(qq_id, day)",
            """
            CREATE TABLE IF NOT EXISTS summaries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              date TEXT NOT NULL,
              text TEXT NOT NULL
            )
            """,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_summaries_qq_date ON summaries(qq_id, date)",
            """
            CREATE TABLE IF NOT EXISTS followups (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              topic TEXT NOT NULL,
              detail TEXT,
              due_at TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL DEFAULT 'open',
              created_at TEXT,
              sent_at TEXT,
              resolved_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS usage (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              model TEXT NOT NULL,
              input_tokens INTEGER NOT NULL DEFAULT 0,
              output_tokens INTEGER NOT NULL DEFAULT 0,
              cached_tokens INTEGER NOT NULL DEFAULT 0,
              cost REAL NOT NULL DEFAULT 0,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_usage_time ON usage(created_at)",
            """
            CREATE TABLE IF NOT EXISTS settings (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS emoji (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              path TEXT NOT NULL,
              emo_tags TEXT,
              scene_tags TEXT,
              description TEXT,
              status TEXT NOT NULL DEFAULT 'approved',
              sent_count INTEGER NOT NULL DEFAULT 0,
              last_sent_at TEXT,
              enabled INTEGER NOT NULL DEFAULT 1,
              created_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS personas (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              content TEXT NOT NULL,
              active INTEGER NOT NULL DEFAULT 0,
              created_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS login_qr (
              token TEXT PRIMARY KEY,
              status TEXT NOT NULL DEFAULT 'pending',
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
            )
            """,
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
            CREATE TABLE IF NOT EXISTS proactive_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              type TEXT NOT NULL,
              content TEXT NOT NULL,
              sent_at TEXT NOT NULL,
              replied_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_proactive_qq_time ON proactive_log(qq_id, sent_at)",
            """
            CREATE TABLE IF NOT EXISTS emotional_memory (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER NOT NULL,
              topic TEXT NOT NULL,
              detail TEXT NOT NULL,
              sentiment REAL NOT NULL DEFAULT -1,
              created_at TEXT NOT NULL,
              followed_up INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_emot_qq ON emotional_memory(qq_id, followed_up)",
            """
            CREATE TABLE IF NOT EXISTS user_mood (
              qq_id INTEGER PRIMARY KEY,
              mood REAL NOT NULL DEFAULT 0,
              updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS knowledge_docs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              filename TEXT NOT NULL,
              stored_name TEXT NOT NULL,
              category TEXT NOT NULL DEFAULT '未分类',
              status TEXT NOT NULL DEFAULT 'pending',
              error TEXT,
              size INTEGER NOT NULL DEFAULT 0,
              chunk_count INTEGER NOT NULL DEFAULT 0,
              uploaded_at TEXT NOT NULL,
              processed_at TEXT,
              enabled INTEGER NOT NULL DEFAULT 1
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_kdocs_status ON knowledge_docs(status)",
            """
            CREATE TABLE IF NOT EXISTS knowledge_chunks (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              doc_id INTEGER NOT NULL,
              category TEXT NOT NULL,
              kind TEXT NOT NULL,
              text TEXT NOT NULL,
              hash TEXT,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_kchunks_doc ON knowledge_chunks(doc_id)",
            "CREATE INDEX IF NOT EXISTS idx_kchunks_cat ON knowledge_chunks(category, kind)",
            """
            CREATE TABLE IF NOT EXISTS knowledge_categories (
              name TEXT PRIMARY KEY,
              sort INTEGER NOT NULL DEFAULT 0
            )
            """,
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('可爱', 1)",
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('温柔', 2)",
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('傲娇', 3)",
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('日常', 4)",
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('聊天记录', 5)",
            "INSERT OR IGNORE INTO knowledge_categories(name, sort) VALUES ('知识', 6)",
            """
            CREATE TABLE IF NOT EXISTS proactive_state (
              qq_id INTEGER PRIMARY KEY,
              last_user_at TEXT,
              last_proactive_at TEXT,
              unanswered_count INTEGER NOT NULL DEFAULT 0,
              suspended INTEGER NOT NULL DEFAULT 0,
              grievance_level INTEGER NOT NULL DEFAULT 0,
              tease_next INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS friend_profile (
              qq_id INTEGER PRIMARY KEY,
              nickname TEXT,
              remark TEXT,
              native_category_id INTEGER,
              native_category_name TEXT,
              auto_category TEXT,
              message_count INTEGER NOT NULL DEFAULT 0,
              user_message_count INTEGER NOT NULL DEFAULT 0,
              last_message_at TEXT,
              mood REAL NOT NULL DEFAULT 0,
              reason TEXT,
              updated_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_friend_category ON friend_profile(auto_category)",
            """
            CREATE TABLE IF NOT EXISTS qzone_post (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              qq_id INTEGER,
              content TEXT NOT NULL,
              audience TEXT NOT NULL,
              ugc_right INTEGER NOT NULL,
              target_uins TEXT,
              tid TEXT,
              status TEXT NOT NULL,
              reason TEXT,
              created_at TEXT NOT NULL,
              published_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_qzone_created ON qzone_post(created_at)",
            """
            CREATE TABLE IF NOT EXISTS friend_request (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              flag TEXT NOT NULL,
              user_id INTEGER NOT NULL,
              nickname TEXT,
              comment TEXT,
              status TEXT NOT NULL DEFAULT 'pending',
              created_at TEXT NOT NULL,
              handled_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_friend_request_status ON friend_request(status)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_friend_request_flag ON friend_request(flag)",
            """
            CREATE TABLE IF NOT EXISTS meme_card (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              aliases TEXT,
              summary TEXT,
              usage TEXT,
              heat INTEGER DEFAULT 0,
              source TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS onboarding (
              qq_id INTEGER PRIMARY KEY,
              stage TEXT NOT NULL,
              intro_at TEXT,
              topic_at TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS life_event (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              day TEXT NOT NULL,
              content TEXT NOT NULL,
              used_at TEXT,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_life_event_day ON life_event(day)",
            """
            CREATE TABLE IF NOT EXISTS chat_review (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              day TEXT NOT NULL,
              qq_id INTEGER NOT NULL,
              score INTEGER,
              summary TEXT,
              issues TEXT,
              suggestions TEXT,
              msg_count INTEGER,
              created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_chat_review_day ON chat_review(day)",
            """
            CREATE TABLE IF NOT EXISTS bot_profile_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              field TEXT NOT NULL,
              old_value TEXT,
              new_value TEXT,
              by_qq INTEGER,
              created_at TEXT NOT NULL
            )
            """
    };

    /** 一次性数据迁移：把早期中文类目对齐到契约里的英文枚举 */
    private static final String[] MIGRATIONS = {
            "ALTER TABLE facts ADD COLUMN review_note TEXT",
            "ALTER TABLE chat_review ADD COLUMN human_score INTEGER",
            "ALTER TABLE messages ADD COLUMN emoji_id INTEGER",
            "ALTER TABLE messages ADD COLUMN voice_file TEXT",
            "ALTER TABLE usage ADD COLUMN qq_id INTEGER",
            "UPDATE facts SET category='profile' WHERE category IN ('基本信息','工作学习','健康')",
            "UPDATE facts SET category='preference' WHERE category='偏好'",
            "UPDATE facts SET category='relationship' WHERE category='关系'",
            "UPDATE facts SET category='event' WHERE category IN ('日程','其他')",
            "ALTER TABLE proactive_state ADD COLUMN mode TEXT",
            "ALTER TABLE proactive_state ADD COLUMN next_eligible_at TEXT",
            "ALTER TABLE proactive_state ADD COLUMN dnd_until TEXT",
            "ALTER TABLE proactive_state ADD COLUMN mood TEXT",
            "ALTER TABLE proactive_state ADD COLUMN mood_until TEXT",
            "ALTER TABLE proactive_state ADD COLUMN reentry_stage INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE proactive_state ADD COLUMN activity_hint TEXT",
            "ALTER TABLE proactive_state ADD COLUMN activity_until TEXT",
            "ALTER TABLE proactive_log ADD COLUMN channel TEXT",
            "ALTER TABLE proactive_log ADD COLUMN hook TEXT",
            "ALTER TABLE proactive_log ADD COLUMN bubble_count INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE proactive_log ADD COLUMN reply_latency_sec INTEGER",
            "ALTER TABLE emoji ADD COLUMN status TEXT NOT NULL DEFAULT 'approved'",
            "ALTER TABLE emoji ADD COLUMN sent_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE emoji ADD COLUMN last_sent_at TEXT",
            "CREATE TABLE IF NOT EXISTS emoji_send_log (id INTEGER PRIMARY KEY AUTOINCREMENT, emoji_id INTEGER NOT NULL, qq_id INTEGER NOT NULL, emotion TEXT, sent_at TEXT NOT NULL, replied_at TEXT)"
    };

    private final Path path;
    private final Path botsDir;
    private final GlobalDb globalDb;
    private final java.util.Set<Long> ready = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Db(BotProperties props, GlobalDb globalDb) {
        this.path = Path.of(props.getDb().getPath()).toAbsolutePath();
        this.botsDir = path.getParent().resolve("bots");
        this.globalDb = globalDb;
    }

    @PostConstruct
    public void init() {
        migrateLegacy();
        try {
            open();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 失败: " + e.getMessage(), e);
        }
    }

    /** 旧库迁到 bots/<默认账号>.db（只做一次，失败保留原库） */
    private void migrateLegacy() {
        try {
            // 账号主键改为 id 后，旧库名从 QQ 号改为 1
            Path legacyById = botsDir.resolve("100000000.db");
            Path firstAccount = botsDir.resolve("1.db");
            if (Files.exists(legacyById) && !Files.exists(firstAccount)) {
                Files.move(legacyById, firstAccount);
                Path wal = Path.of(legacyById + "-wal");
                if (Files.exists(wal)) {
                    Files.move(wal, Path.of(firstAccount + "-wal"));
                }
                log.info("账号库已重命名: {} -> {}", legacyById, firstAccount);
            }
            Path target = botsDir.resolve("1.db");
            if (Files.exists(target) || !Files.exists(path)) {
                return;
            }
            Files.createDirectories(botsDir);
            Files.copy(path, target);
            Path wal = Path.of(path + "-wal");
            Path shm = Path.of(path + "-shm");
            if (Files.exists(wal)) {
                Files.copy(wal, Path.of(target + "-wal"));
            }
            if (Files.exists(shm)) {
                Files.copy(shm, Path.of(target + "-shm"));
            }
            log.info("旧库已迁移: {} -> {}", path, target);
        } catch (Exception e) {
            log.error("旧库迁移失败，继续使用原库: {}", e.getMessage());
        }
    }

    private boolean migrationApplied(Statement s, int version) {
        try (ResultSet rs = s.executeQuery("SELECT 1 FROM schema_migrations WHERE version=" + version)) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    private void markMigration(Statement s, int version) throws Exception {
        s.execute("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES("
                + version + ", datetime('now','localtime'))");
    }

    private void initSchema(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                for (String ddl : SCHEMA) {
                    s.execute(ddl);
                }
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations("
                        + "version INTEGER PRIMARY KEY, applied_at TEXT)");
                for (int i = 0; i < MIGRATIONS.length; i++) {
                    int version = i + 1;
                    if (migrationApplied(s, version)) {
                        continue;
                    }
                    try {
                        s.execute(MIGRATIONS[i]);
                        markMigration(s, version);
                    } catch (Exception e) {
                        String msg = String.valueOf(e.getMessage()).toLowerCase();
                        if (msg.contains("duplicate column") || msg.contains("already exists")) {
                            // 老库已经有这个字段，记为已执行
                            markMigration(s, version);
                        } else {
                            log.warn("迁移 {} 失败（下次启动重试）: {}", version, e.getMessage());
                        }
                    }
                }
            }
            log.info("SQLite 就绪: {}", dbPath);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SQLite 失败: " + e.getMessage(), e);
        }
    }

    public Connection open() throws SQLException {
        Long ctx = com.qqbot.bot.BotContext.get();
        if (ctx == null) {
            Long first = globalDb.firstAccountId();
            if (first == null) {
                throw new IllegalStateException("还没有任何机器人账号");
            }
            return open(first);
        }
        return open(ctx);
    }

    public Connection open(long botId) throws SQLException {
        Path p = botsDir.resolve(botId + ".db");
        if (ready.add(botId)) {
            initSchema(p);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + p);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public Path getPath() {
        return path;
    }
}
