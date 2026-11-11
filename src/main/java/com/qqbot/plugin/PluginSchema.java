package com.qqbot.plugin;

/**
 * 插件相关建表语句。
 *
 * <p><b>为什么不进 {@code Db.SCHEMA}：</b>{@code SharedDb.init()}（SharedDb.java:43）
 * 直接遍历 {@code Db.SCHEMA} 建表 —— 塞进去的话，{@code installed_plugins} 会同时
 * 出现在 shared.db 和每个 bot 库、{@code plugin_state} 也会被复制进 shared.db。
 * 所以拆成两套，各建各的：
 *
 * <ul>
 *   <li>{@link #INSTANCE_DDL}：只在 shared.db 建（实例级，一次安装所有 bot 共用），
 *       由 {@code SharedDb.init()} 执行；</li>
 *   <li>{@link #BOT_DDL}：只在每个 bot 库建（账号级启用状态与运行记录），
 *       由 {@code Db.initSchema()} 执行。</li>
 * </ul>
 *
 * <p>全部 {@code CREATE TABLE IF NOT EXISTS}，旧 jar 不读这些表，回滚安全。
 */
public final class PluginSchema {

    private PluginSchema() {
    }

    /** 实例级表：只在 shared.db 建。 */
    public static final String[] INSTANCE_DDL = {
            """
            CREATE TABLE IF NOT EXISTS installed_plugins (
              install_id    TEXT PRIMARY KEY,
              plugin_id     TEXT NOT NULL UNIQUE,
              version       TEXT NOT NULL,
              runtime       TEXT NOT NULL,
              source        TEXT NOT NULL,          -- store / upload / builtin
              sha256        TEXT,
              manifest      TEXT NOT NULL,          -- 原始 manifest.json
              endpoint      TEXT,                   -- 仅 local：部署者填的地址（实例级，所有 bot 共用）
              status        TEXT NOT NULL,          -- installed / broken / revoked
              installed_at  TEXT NOT NULL,
              updated_at    TEXT NOT NULL,
              prev_version  TEXT,                   -- 上一版版本号（仅升级时写入，回滚用）
              prev_sha256   TEXT                    -- 上一版包体摘要
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_installed_status ON installed_plugins(status)"
    };

    /** 账号级表：只在每个 bot 库（bots/<id>.db）建。 */
    public static final String[] BOT_DDL = {
            """
            CREATE TABLE IF NOT EXISTS plugin_state (
              plugin_id     TEXT PRIMARY KEY,
              enabled       INTEGER NOT NULL DEFAULT 0,
              permissions   TEXT,                   -- 用户确认过的权限快照（JSON）
              manifest_hash TEXT,                   -- 升级后比对，变化则要求重新确认
              config        TEXT,                   -- 后台改过的配置项（JSON，仅存非默认值）
              updated_at    TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS plugin_runs (
              id             INTEGER PRIMARY KEY AUTOINCREMENT,
              plugin_id      TEXT NOT NULL,
              plugin_version TEXT NOT NULL,
              invoke_id      TEXT NOT NULL UNIQUE,
              intent_id      TEXT,
              action         TEXT,
              status         TEXT NOT NULL,         -- ok / error / timeout / rejected / pending
              error_code     TEXT,
              latency_ms     INTEGER,
              params         TEXT,
              created_at     TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_plugin_runs_plugin ON plugin_runs(plugin_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_plugin_runs_qq ON plugin_runs(invoke_id)",
            """
            CREATE TABLE IF NOT EXISTS plugin_proposals (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              plugin_id   TEXT NOT NULL,
              created_at  TEXT NOT NULL,
              status      TEXT NOT NULL DEFAULT 'pending'  -- pending/accepted/rejected/expired
            )
            """
    };
}
