import fs from 'node:fs'
import crypto from 'node:crypto'
import semver from 'semver'
import { db, INDEX_FILE, now } from './db.js'

let cache = null // { body, etag, generatedAt }

function pickLatest(rows) {
  let best = null
  for (const r of rows) {
    if (!semver.valid(r.version)) continue
    if (!best || semver.gt(r.version, best.version)) best = r
  }
  return best
}

function isRevoked(pluginId, version) {
  const rows = db
    .prepare('SELECT version FROM revocations WHERE plugin_id=?')
    .all(pluginId)
  return rows.some((r) => r.version === null || r.version === version)
}

/**
 * 索引由 DB 重建，tmp + rename 原子替换（文档 §6.7）。
 * 触发点：审核通过 / 下架 / 新版本 / 吊销，最多 30 秒生效。
 */
export function rebuildIndex() {
  const plugins = []
  const approved = db
    .prepare("SELECT * FROM plugins WHERE status='approved' ORDER BY id")
    .all()

  for (const p of approved) {
    const rows = db
      .prepare("SELECT * FROM versions WHERE plugin_id=? AND status='approved'")
      .all(p.id)
      .filter((r) => !isRevoked(p.id, r.version))
    // 修：吊销最新版本时应退到仍 approved 的旧版本，而不是把整个插件从索引里拿掉
    const latest = pickLatest(rows)
    if (!latest) continue

    let manifest = {}
    try {
      manifest = JSON.parse(latest.manifest)
    } catch {
      manifest = {}
    }

    plugins.push({
      id: p.id,
      name: p.name,
      summary: p.summary,
      latest: latest.version,
      runtime: latest.runtime,
      apiVersion: latest.api_version,
      minHostVersion: latest.min_host,
      intent: manifest.intent?.id || null,
      tags: manifest.tags || [],
      icon: manifest.icon ? `/icon/${p.id}.png` : null,
      downloads: p.downloads,
      updatedAt: (p.updated_at || '').slice(0, 10),
      sha256: latest.sha256,
      size: latest.size,
      changelog: latest.changelog ? String(latest.changelog).slice(0, 4000) : null,
    })
  }

  const intentsVersion = db.prepare('SELECT COUNT(*) AS n FROM intents').get().n
  const body = JSON.stringify(
    {
      schemaVersion: 1,
      generatedAt: now(),
      intentsVersion,
      plugins,
    },
    null,
    2,
  )

  const tmp = `${INDEX_FILE}.tmp`
  fs.writeFileSync(tmp, body, 'utf8')
  fs.renameSync(tmp, INDEX_FILE)

  const etag = `"${crypto.createHash('sha256').update(body).digest('hex').slice(0, 32)}"`
  cache = { body, etag, generatedAt: now() }
  return cache
}

export function getIndex() {
  if (cache) return cache
  try {
    const body = fs.readFileSync(INDEX_FILE, 'utf8')
    const etag = `"${crypto.createHash('sha256').update(body).digest('hex').slice(0, 32)}"`
    cache = { body, etag, generatedAt: JSON.parse(body).generatedAt }
    return cache
  } catch {
    return rebuildIndex()
  }
}

/** 客户端带 hostVersion 时过滤掉不兼容项（文档 §6.3） */
export function filterForClient(indexBody, { hostVersion, apiVersion }) {
  const data = JSON.parse(indexBody)
  if (!hostVersion && !apiVersion) return { ...data, plugins: data.plugins }

  data.plugins = data.plugins.filter((p) => {
    if (apiVersion && Number(apiVersion) !== p.apiVersion) return false
    if (hostVersion && semver.valid(hostVersion) && semver.valid(p.minHostVersion)) {
      return semver.gte(hostVersion, p.minHostVersion)
    }
    return true
  })
  return data
}
