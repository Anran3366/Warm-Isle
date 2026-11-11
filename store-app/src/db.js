import { DatabaseSync } from 'node:sqlite'
import fs from 'node:fs'
import path from 'node:path'

/** 数据目录：DB、包体、索引、临时文件都在这里（文档 §6.1） */
export const DATA_DIR = path.resolve(process.env.STORE_DATA_DIR || './data')
export const PKG_DIR = path.join(DATA_DIR, 'packages')
export const TMP_DIR = path.join(DATA_DIR, 'tmp')
export const INDEX_FILE = path.join(DATA_DIR, 'index.json')

for (const dir of [DATA_DIR, PKG_DIR, TMP_DIR]) {
  fs.mkdirSync(dir, { recursive: true })
}

export const db = new DatabaseSync(path.join(DATA_DIR, 'store.db'))

db.exec(`
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS authors (
  id           TEXT PRIMARY KEY,
  namespace    TEXT NOT NULL UNIQUE,
  name         TEXT NOT NULL,
  email        TEXT,
  token_hash   TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'active',
  created_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS plugins (
  id           TEXT PRIMARY KEY,
  owner_id     TEXT NOT NULL,
  name         TEXT NOT NULL,
  summary      TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'pending',
  latest       TEXT,
  downloads    INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS versions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  plugin_id    TEXT NOT NULL,
  version      TEXT NOT NULL,
  runtime      TEXT NOT NULL,
  api_version  INTEGER NOT NULL,
  min_host     TEXT NOT NULL,
  sha256       TEXT NOT NULL,
  size         INTEGER NOT NULL,
  manifest     TEXT NOT NULL,
  readme       TEXT,
  status       TEXT NOT NULL DEFAULT 'pending',
  review_note  TEXT,
  submitted_at TEXT NOT NULL,
  reviewed_at  TEXT,
  publisher    TEXT,
  changelog    TEXT,
  UNIQUE(plugin_id, version)
);

CREATE TABLE IF NOT EXISTS downloads (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  plugin_id TEXT NOT NULL,
  version   TEXT NOT NULL,
  ip_hash   TEXT NOT NULL,
  day       TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_dl ON downloads(plugin_id, version, ip_hash, day);

CREATE TABLE IF NOT EXISTS revocations (
  plugin_id  TEXT NOT NULL,
  version    TEXT,
  reason     TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS intents (
  id          TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  exclusive   INTEGER NOT NULL DEFAULT 0,
  reserved    INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS audit_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  actor      TEXT NOT NULL,
  action     TEXT NOT NULL,
  target     TEXT,
  detail     TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_versions_status ON versions(status);
CREATE INDEX IF NOT EXISTS ix_versions_plugin ON versions(plugin_id, status);
`)
// 旧库补列（发布者信息：实例上报，可能为空）
try { db.exec('ALTER TABLE versions ADD COLUMN publisher TEXT') } catch { /* 列已存在 */ }
try { db.exec('ALTER TABLE versions ADD COLUMN changelog TEXT') } catch { /* 列已存在 */ }

// 唯一的发布者：管理员。保留 authors 表以兼容旧数据，但不再开放注册。
db.prepare(
  "INSERT OR IGNORE INTO authors(id, namespace, name, email, token_hash, status, created_at) VALUES('owner','__owner__','暖屿官方',NULL,'','active',?)",
).run(new Date().toISOString())


export const now = () => new Date().toISOString()

export function audit(actor, action, target, detail) {
  db.prepare(
    'INSERT INTO audit_log(actor, action, target, detail, created_at) VALUES (?,?,?,?,?)',
  ).run(actor, action, target ?? null, detail ? JSON.stringify(detail) : null, now())
}

/** 索引里「最新版本」按 approved 的最高 semver 重算，不信任 plugins.latest（文档 §6.2 约定 2） */
export function approvedVersions(pluginId) {
  return db
    .prepare(
      "SELECT * FROM versions WHERE plugin_id=? AND status='approved' ORDER BY submitted_at",
    )
    .all(pluginId)
}

export function packagePath(pluginId, version) {
  return path.join(PKG_DIR, pluginId, `${version}.zip`)
}

export function readmePath(pluginId, version) {
  return path.join(PKG_DIR, pluginId, `${version}.README.md`)
}
