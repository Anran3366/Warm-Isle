import express from 'express'
import multer from 'multer'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import semver from 'semver'
import {
  db,
  now,
  audit,
  packagePath,
  readmePath,
  PKG_DIR,
  DATA_DIR,
} from './db.js'
import { healthCheck } from './verify.js'
import { rebuildIndex, getIndex, filterForClient } from './indexer.js'
import { PUBLIC_KEY_B64, KEY_ID, sendSigned, rotationChain } from './keys.js'
import { verifyTotp } from './totp.js'

const PORT = Number(process.env.PORT || 3500)
const HOST = process.env.STORE_HOST || '127.0.0.1'
const PUBLIC_BASE = (process.env.STORE_PUBLIC_BASE || `http://${HOST}:${PORT}`).replace(/\/$/, '')
const ADMIN_TOKEN = process.env.STORE_ADMIN_TOKEN || ''
const IP_SECRET = process.env.STORE_IP_SECRET || 'change-me'
const TOTP_SECRET = (process.env.STORE_TOTP_SECRET || '').trim()

if (!ADMIN_TOKEN || ADMIN_TOKEN.length < 16) {
  console.warn('[store] STORE_ADMIN_TOKEN 未设置或过短，管理接口将全部拒绝（请设置 16 位以上随机串）')
}
if (!TOTP_SECRET) {
  console.warn('[store] STORE_TOTP_SECRET 未设置，审核后台仅密码登录（建议运行 scripts/totp-setup.mjs 开启 2FA）')
}

const app = express()
app.disable('x-powered-by')
app.use(express.json({ limit: '1mb' }))
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
  next()
})

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 50 * 1024 * 1024, files: 1 },
})

const fail = (res, status, errors) => res.status(status).json({ ok: false, errors })
const err = (field, code, message) => ({ field, code, message })

/* ---------------------------------------------------------------- 鉴权 */

const SESSION_TTL = 12 * 3600_000
const LOCK_AFTER = 5
const LOCK_MS = 15 * 60_000
const adminSessions = new Map() // token -> expiresAt(ms)
const loginFails = new Map() // ip -> { fails, lockedUntil }

function clientIp(req) {
  const raw = req.get('X-Real-IP') || req.ip || ''
  return String(raw).replace(/^::ffff:/, '')
}

function newSession() {
  const token = crypto.randomBytes(32).toString('hex')
  adminSessions.set(token, Date.now() + SESSION_TTL)
  return token
}

function requireAdmin(req, res, next) {
  // 优先：登录会话（密码 + TOTP 换来）
  const bearer = (req.get('Authorization') || '').replace(/^Bearer\s+/i, '')
  if (bearer) {
    const exp = adminSessions.get(bearer)
    if (exp && exp > Date.now()) return next()
    if (exp) adminSessions.delete(bearer)
  }
  // 兜底：运维 token（走 SSH 隧道的脚本场景），每次使用都记审计
  const token = req.get('X-Admin-Token') || ''
  if (ADMIN_TOKEN && token.length === ADMIN_TOKEN.length &&
      crypto.timingSafeEqual(Buffer.from(token), Buffer.from(ADMIN_TOKEN))) {
    audit('admin', 'token-auth', req.path, { ip: clientIp(req) })
    return next()
  }
  return fail(res, 401, [err('auth', 'UNAUTHORIZED', '请先登录审核后台')])
}

/* ------------------------------------------------------------ 公开 API */

app.get('/healthz', (_req, res) => res.json({ ok: true, at: now() }))

app.get('/api/v1/index.json', (req, res) => {
  const idx = getIndex()
  const data = filterForClient(idx.body, {
    hostVersion: req.query.hostVersion,
    apiVersion: req.query.apiVersion,
  })
  // ETag 必须基于“过滤后的 body”，不能复用未过滤索引的 ETag：
  // 客户端升级 hostVersion 后带旧 ETag 会拿 304，继续看不到新兼容插件。
  const filteredBody = JSON.stringify(data)
  const etag = `"${crypto.createHash('sha256').update(filteredBody).digest('hex').slice(0, 32)}"`
  if (req.get('If-None-Match') === etag) return res.status(304).end()
  sendSigned(res, data, { ETag: etag, 'Cache-Control': 'public, max-age=300' })
})

// 宿主验签用公钥（公钥本身公开，防投毒靠的是私钥不出服务器）
app.get('/api/v1/pubkey', (_req, res) => {
  res.json({ algo: 'ed25519', keyId: KEY_ID, publicKey: PUBLIC_KEY_B64 })
})

// 轮换链：宿主用已信任的旧公钥逐条验签，自动采纳新公钥；不需要发版
app.get('/api/v1/keys/rotation', (_req, res) => {
  res.json({ schemaVersion: 1, currentKeyId: KEY_ID, chain: rotationChain() })
})

app.get('/api/v1/plugins/:id', (req, res) => {
  const p = db.prepare('SELECT * FROM plugins WHERE id=?').get(req.params.id)
  if (!p || p.status === 'blocked') return fail(res, 404, [err('id', 'NOT_FOUND', '插件不存在')])
  const versions = db
    .prepare(
      "SELECT version, runtime, api_version, min_host, sha256, size, manifest, readme, changelog, status, submitted_at, reviewed_at FROM versions WHERE plugin_id=? AND status IN ('approved','yanked') ORDER BY submitted_at DESC",
    )
    .all(p.id)
    .map((v) => ({
      version: v.version,
      runtime: v.runtime,
      apiVersion: v.api_version,
      minHostVersion: v.min_host,
      sha256: v.sha256,
      size: v.size,
      status: v.status,
      submittedAt: v.submitted_at,
      reviewedAt: v.reviewed_at,
      manifest: safeJson(v.manifest),
      readme: v.readme,
      changelog: v.changelog,
    }))
  res.json({
    id: p.id,
    name: p.name,
    summary: p.summary,
    status: p.status,
    downloads: p.downloads,
    versions: versions.filter((v) => v.status === 'approved'),
  })
})

app.get('/api/v1/plugins/:id/versions/:version', (req, res) => {
  const v = db
    .prepare("SELECT * FROM versions WHERE plugin_id=? AND version=? AND status='approved'")
    .get(req.params.id, req.params.version)
  if (!v) return fail(res, 404, [err('version', 'NOT_FOUND', '版本不存在或未上架')])
  res.json({
    pluginId: v.plugin_id,
    version: v.version,
    sha256: v.sha256,
    size: v.size,
    manifest: safeJson(v.manifest),
  })
})

const downloadBuckets = new Map()

function rateLimited(ip) {
  const minute = Math.floor(Date.now() / 60000)
  const key = `${ip}:${minute}`
  const n = (downloadBuckets.get(key) || 0) + 1
  downloadBuckets.set(key, n)
  if (downloadBuckets.size > 5000) {
    for (const k of downloadBuckets.keys()) {
      if (!k.endsWith(`:${minute}`)) downloadBuckets.delete(k)
    }
  }
  return n > 30
}

app.get('/api/v1/download/:id/:version', (req, res) => {
  const { id, version } = req.params
  const ip = req.ip || req.socket.remoteAddress || '0.0.0.0'
  if (rateLimited(ip)) return fail(res, 429, [err('ip', 'RATE_LIMITED', '下载过于频繁')])

  const v = db
    .prepare("SELECT * FROM versions WHERE plugin_id=? AND version=? AND status='approved'")
    .get(id, version)
  if (!v) return fail(res, 404, [err('version', 'NOT_FOUND', '版本不存在或未上架')])
  const revoked = db
    .prepare('SELECT 1 FROM revocations WHERE plugin_id=? AND (version IS NULL OR version=?)')
    .get(id, version)
  if (revoked) return fail(res, 410, [err('version', 'REVOKED', '该版本已吊销')])

  const file = packagePath(id, version)
  if (!fs.existsSync(file)) return fail(res, 500, [err('file', 'MISSING_PACKAGE', '包体丢失')])

  // 下载量按 (plugin, version, ip_hash, day) 去重，不存原始 IP（文档 §6.8）
  const day = new Date().toISOString().slice(0, 10)
  const ipHash = crypto.createHmac('sha256', IP_SECRET).update(ip).digest('hex').slice(0, 32)
  const info = db
    .prepare(
      'INSERT OR IGNORE INTO downloads(plugin_id, version, ip_hash, day) VALUES (?,?,?,?)',
    )
    .run(id, version, ipHash, day)
  if (info.changes > 0) {
    db.prepare('UPDATE plugins SET downloads = downloads + 1 WHERE id=?').run(id)
  }

  res.setHeader('Content-Type', 'application/zip')
  res.setHeader('Content-Disposition', `attachment; filename="${id}-${version}.zip"`)
  res.setHeader('X-Checksum-Sha256', v.sha256)
  fs.createReadStream(file).pipe(res)
})

app.get('/api/v1/intents.json', (_req, res) => {
  const rows = db.prepare('SELECT * FROM intents ORDER BY id').all()
  res.json({
    schemaVersion: 1,
    generatedAt: now(),
    intents: rows.map((r) => ({
      id: r.id,
      description: r.description,
      exclusive: !!r.exclusive,
      reserved: !!r.reserved,
    })),
  })
})

app.get('/api/v1/revocations.json', (_req, res) => {
  const rows = db.prepare('SELECT * FROM revocations ORDER BY created_at DESC').all()
  // §6.8：吊销名单带签名；签名上线后宿主命中即停止触发（不卸载）
  sendSigned(res, {
    schemaVersion: 1,
    generatedAt: now(),
    revocations: rows.map((r) => ({
      pluginId: r.plugin_id,
      version: r.version,
      reason: r.reason,
      createdAt: r.created_at,
    })),
  })
})

/* ------------------------------------------------ 上架（任何部署实例都可投递，所有者审核） */

const OWNER_ID = 'owner'

/**
 * 上传包 → 体检 → 落盘 → 建/更新插件 → 写一条 pending 版本。
 * 只有管理员能调用；审核通过后才会进索引。
 */
async function ingestPackage(buffer, actor, publisher) {
  let health
  try {
    health = await healthCheck(buffer)
  } catch (e) {
    return { status: 400, body: { ok: false, errors: [err('file', 'INSPECT_FAILED', '体检失败: ' + e.message)] } }
  }
  if (!health.ok) return { status: 422, body: { ok: false, errors: health.issues } }

  const m = health.pkg.manifest
  const existing = db
    .prepare('SELECT * FROM versions WHERE plugin_id=? AND version=?')
    .get(m.id, m.version)
  if (existing && (existing.status === 'approved' || existing.status === 'yanked')) {
    return {
      status: 409,
      body: { ok: false, errors: [err('manifest.version', 'VERSION_FROZEN', '该版本已上架，必须升版本号再提交')] },
    }
  }
  if (existing) db.prepare('DELETE FROM versions WHERE id=?').run(existing.id)

  const dir = path.join(PKG_DIR, m.id)
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(packagePath(m.id, m.version), buffer)
  if (health.pkg.readme) {
    fs.writeFileSync(readmePath(m.id, m.version), health.pkg.readme, 'utf8')
  }

  const ts = now()
  const owner = db.prepare('SELECT owner_id FROM plugins WHERE id=?').get(m.id)
  if (!owner) {
    db.prepare(
      'INSERT INTO plugins(id, owner_id, name, summary, status, latest, downloads, created_at, updated_at) VALUES (?,?,?,?,?,?,0,?,?)',
    ).run(m.id, OWNER_ID, m.name, m.description, 'pending', null, ts, ts)
  } else {
    db.prepare('UPDATE plugins SET name=?, summary=?, updated_at=? WHERE id=?').run(
      m.name, m.description, ts, m.id,
    )
  }

  const info = db
    .prepare(
      'INSERT INTO versions(plugin_id, version, runtime, api_version, min_host, sha256, size, manifest, readme, status, submitted_at, publisher, changelog) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
    )
    .run(
      m.id, m.version, m.runtime, m.apiVersion, m.compat.minHostVersion,
      health.pkg.sha256, health.pkg.size, JSON.stringify(m), health.pkg.readme, 'pending', ts,
      publisher || null, health.pkg.changelog || null,
    )

  audit(actor, 'upload', m.id, { version: m.version, sha256: health.pkg.sha256, publisher: publisher || null })
  return {
    status: 200,
    body: {
      ok: true,
      submissionId: Number(info.lastInsertRowid),
      pluginId: m.id,
      version: m.version,
      sha256: health.pkg.sha256,
      status: 'pending',
    },
  }
}

// 任何部署了本项目的实例都可以投递；不需要注册。发布只进待审队列，
// 上架与否由所有者在自己的后台决定（§6.4）。
const publishHits = new Map() // ip -> [timestamps]
const PUBLISH_PER_HOUR = 5
const PUBLISH_PER_DAY = 20
const PENDING_CAP = 100

app.post('/api/v1/publish/upload', upload.single('file'), async (req, res) => {
  if (!req.file) return fail(res, 400, [err('file', 'MISSING_FILE', '缺少 zip 文件')])
  const ip = clientIp(req)
  const nowMs = Date.now()
  const hits = (publishHits.get(ip) || []).filter((t) => nowMs - t < 24 * 3600_000)
  if (hits.filter((t) => nowMs - t < 3600_000).length >= PUBLISH_PER_HOUR ||
      hits.length >= PUBLISH_PER_DAY) {
    return fail(res, 429, [err('publish', 'PUBLISH_LIMITED', '发布太频繁，稍后再试')])
  }
  const pending = db.prepare("SELECT COUNT(*) AS n FROM versions WHERE status='pending'").get().n
  if (pending >= PENDING_CAP) {
    return fail(res, 429, [err('publish', 'QUEUE_FULL', '待审队列已满，请联系商店所有者')])
  }
  hits.push(nowMs)
  publishHits.set(ip, hits)
  const publisher = String(req.get('X-Publisher') || '').slice(0, 60)
  const r = await ingestPackage(req.file.buffer, 'publish', publisher)
  res.status(r.status).json(r.body)
})

/* ------------------------------------------------------------ 管理 API */

// 登录：密码（STORE_ADMIN_TOKEN）+ TOTP（STORE_TOTP_SECRET，未配置则只验密码）
// 同一 IP 连续失败 5 次锁 15 分钟（§6.9）
app.post('/api/v1/admin/login', (req, res) => {
  const ip = clientIp(req)
  const st = loginFails.get(ip)
  if (st?.lockedUntil && st.lockedUntil > Date.now()) {
    return fail(res, 429, [err('auth', 'LOCKED', `失败次数过多，${Math.ceil((st.lockedUntil - Date.now()) / 60000)} 分钟后再试`)])
  }
  const password = String(req.body?.password || '')
  const code = String(req.body?.code || '')
  const passOk = ADMIN_TOKEN && password.length === ADMIN_TOKEN.length &&
    crypto.timingSafeEqual(Buffer.from(password), Buffer.from(ADMIN_TOKEN))
  const totpOk = !TOTP_SECRET || verifyTotp(TOTP_SECRET, code)
  if (!passOk || !totpOk) {
    const cur = loginFails.get(ip) || { fails: 0, lockedUntil: 0 }
    cur.fails += 1
    if (cur.fails >= LOCK_AFTER) {
      cur.lockedUntil = Date.now() + LOCK_MS
      cur.fails = 0
      audit('admin', 'login-locked', ip, null)
    }
    loginFails.set(ip, cur)
    audit('admin', 'login-failed', ip, { passOk, totpOk })
    return fail(res, 401, [err('auth', 'BAD_CREDENTIALS', '密码或验证码不正确')])
  }
  loginFails.delete(ip)
  const token = newSession()
  audit('admin', 'login', ip, { totp: !!TOTP_SECRET })
  res.json({ ok: true, sessionToken: token, expiresIn: SESSION_TTL / 1000, totpRequired: !!TOTP_SECRET })
})

app.post('/api/v1/admin/logout', (req, res) => {
  const bearer = (req.get('Authorization') || '').replace(/^Bearer\s+/i, '')
  if (bearer) adminSessions.delete(bearer)
  res.json({ ok: true })
})

app.get('/api/v1/admin/pending', requireAdmin, (_req, res) => {
  const rows = db
    .prepare(
      "SELECT v.*, p.name, p.summary, a.namespace, a.name AS author_name, a.email FROM versions v JOIN plugins p ON p.id=v.plugin_id JOIN authors a ON a.id=p.owner_id WHERE v.status='pending' ORDER BY v.submitted_at ASC",
    )
    .all()
  res.json({
    ok: true,
    items: rows.map((r) => ({
      submissionId: r.id,
      pluginId: r.plugin_id,
      name: r.name,
      summary: r.summary,
      version: r.version,
      runtime: r.runtime,
      apiVersion: r.api_version,
      minHostVersion: r.min_host,
      sha256: r.sha256,
      size: r.size,
      submittedAt: r.submitted_at,
      author: { namespace: r.namespace, name: r.author_name, email: r.email },
      publisher: r.publisher || null,
      manifest: safeJson(r.manifest),
      readme: r.readme,
      downloadUrl: `${PUBLIC_BASE}/api/v1/download/${r.plugin_id}/${r.version}`,
    })),
  })
})

app.post('/api/v1/admin/review/:submissionId', requireAdmin, (req, res) => {
  const id = Number(req.params.submissionId)
  const action = req.body?.action
  const note = String(req.body?.note || '')
  if (!['approve', 'reject'].includes(action)) {
    return fail(res, 400, [err('action', 'BAD_ACTION', 'action 只能是 approve / reject')])
  }
  const v = db.prepare('SELECT * FROM versions WHERE id=?').get(id)
  if (!v) return fail(res, 404, [err('submissionId', 'NOT_FOUND', '提交不存在')])
  if (v.status !== 'pending') {
    return fail(res, 409, [err('submissionId', 'NOT_PENDING', '该提交已处理')])
  }

  const ts = now()
  if (action === 'approve') {
    db.prepare("UPDATE versions SET status='approved', review_note=?, reviewed_at=? WHERE id=?").run(
      note || null,
      ts,
      id,
    )
    const approved = db
      .prepare("SELECT version FROM versions WHERE plugin_id=? AND status='approved'")
      .all(v.plugin_id)
      .map((r) => r.version)
      .filter((x) => semver.valid(x))
      .sort(semver.rcompare)
    db.prepare("UPDATE plugins SET status='approved', latest=?, updated_at=? WHERE id=?").run(
      approved[0] || null,
      ts,
      v.plugin_id,
    )
    rebuildIndex()
  } else {
    db.prepare("UPDATE versions SET status='rejected', review_note=?, reviewed_at=? WHERE id=?").run(
      note || null,
      ts,
      id,
    )
    const left = db
      .prepare("SELECT COUNT(*) AS n FROM versions WHERE plugin_id=? AND status IN ('approved','pending')")
      .get(v.plugin_id).n
    if (left === 0) {
      db.prepare("UPDATE plugins SET status='rejected', updated_at=? WHERE id=?").run(ts, v.plugin_id)
    }
  }

  audit('admin', 'review:' + action, v.plugin_id, { version: v.version, note })
  res.json({ ok: true })
})

app.post('/api/v1/admin/yank/:pluginId', requireAdmin, (req, res) => {
  const p = db.prepare('SELECT * FROM plugins WHERE id=?').get(req.params.pluginId)
  if (!p) return fail(res, 404, [err('pluginId', 'NOT_FOUND', '插件不存在')])
  const version = req.body?.version || null
  if (version) {
    db.prepare("UPDATE versions SET status='yanked' WHERE plugin_id=? AND version=?").run(p.id, version)
  } else {
    db.prepare("UPDATE versions SET status='yanked' WHERE plugin_id=?").run(p.id)
    db.prepare("UPDATE plugins SET status='yanked' WHERE id=?").run(p.id)
  }
  rebuildIndex()
  audit('admin', 'yank', p.id, { version })
  res.json({ ok: true })
})

app.post('/api/v1/admin/revoke', requireAdmin, (req, res) => {
  const pluginId = String(req.body?.pluginId || '')
  const version = req.body?.version || null
  const reason = String(req.body?.reason || '').trim()
  if (!pluginId || !reason) {
    return fail(res, 400, [err('pluginId', 'MISSING', 'pluginId 与 reason 必填')])
  }
  db.prepare('INSERT INTO revocations(plugin_id, version, reason, created_at) VALUES (?,?,?,?)').run(
    pluginId,
    version,
    reason,
    now(),
  )
  rebuildIndex()
  audit('admin', 'revoke', pluginId, { version, reason })
  res.json({ ok: true })
})

app.post('/api/v1/admin/unrevoke', requireAdmin, (req, res) => {
  const pluginId = String(req.body?.pluginId || '')
  const version = req.body?.version || null
  if (!pluginId) {
    return fail(res, 400, [err('pluginId', 'MISSING', 'pluginId 必填')])
  }
  const info = version
    ? db.prepare('DELETE FROM revocations WHERE plugin_id=? AND version=?').run(pluginId, version)
    : db.prepare('DELETE FROM revocations WHERE plugin_id=?').run(pluginId)
  rebuildIndex()
  audit('admin', 'unrevoke', pluginId, { version, removed: info.changes })
  res.json({ ok: true, removed: info.changes })
})

app.get('/api/v1/admin/revocations', requireAdmin, (_req, res) => {
  res.json({
    ok: true,
    items: db.prepare('SELECT * FROM revocations ORDER BY created_at DESC').all(),
  })
})

app.get('/api/v1/admin/stats', requireAdmin, (_req, res) => {
  const one = (sql) => db.prepare(sql).get().n
  res.json({
    ok: true,
    plugins: one("SELECT COUNT(*) AS n FROM plugins WHERE status='approved'"),
    pending: one("SELECT COUNT(*) AS n FROM versions WHERE status='pending'"),
    rejected: one("SELECT COUNT(*) AS n FROM versions WHERE status='rejected'"),
    authors: one('SELECT COUNT(*) AS n FROM authors'),
    downloads: one('SELECT COUNT(*) AS n FROM downloads'),
    revocations: one('SELECT COUNT(*) AS n FROM revocations'),
  })
})

app.get('/api/v1/admin/audit', requireAdmin, (req, res) => {
  const limit = Math.min(Number(req.query.limit || 100), 500)
  res.json({
    ok: true,
    items: db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT ?').all(limit),
  })
})

/* --------------------------------------------------------------- 静态页 */

app.use('/admin', express.static(path.join(import.meta.dirname, '..', 'admin')))
app.use('/', express.static(path.join(import.meta.dirname, '..', 'public')))

app.use((e, _req, res, _next) => {
  if (e?.code === 'LIMIT_FILE_SIZE') {
    return fail(res, 413, [err('file', 'TOO_LARGE', '包体超过 50MB')])
  }
  console.error('[store]', e)
  fail(res, 500, [err('server', 'INTERNAL', e.message || '服务端错误')])
})

function safeJson(s) {
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

rebuildIndex()
app.listen(PORT, HOST, () => {
  console.log(`[store] listening on http://${HOST}:${PORT}  data=${DATA_DIR}  public=${PUBLIC_BASE}`)
})
