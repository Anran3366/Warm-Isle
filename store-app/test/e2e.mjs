// 商店端到端冒烟：作者注册 → 上传 → 审批 → 索引验签 → 下载校验。
// 不依赖测试框架，直接用现有 packs/menran.sing-1.0.0.zip 跑真实链路。
import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const appDir = path.resolve(here, '..')
const zipPath = process.env.E2E_PACK || path.resolve(appDir, '..', 'packs', 'menran.sing-1.0.0.zip')
const port = 3600 + Math.floor(Math.random() * 300)
const base = `http://127.0.0.1:${port}`
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'store-e2e-'))
const adminToken = 'e2e-admin-token-123456'
const invite = 'e2e-invite'

assert.ok(fs.existsSync(zipPath), `缺少测试包：${zipPath}`)
const zip = fs.readFileSync(zipPath)

const child = spawn(process.execPath, ['src/server.js'], {
  cwd: appDir,
  env: {
    ...process.env,
    PORT: String(port),
    STORE_DATA_DIR: dataDir,
    STORE_ADMIN_TOKEN: adminToken,
    STORE_TOTP_SECRET: '',
    STORE_PUBLIC_BASE: base,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let serverLog = ''
child.stdout.on('data', (b) => (serverLog += b))
child.stderr.on('data', (b) => (serverLog += b))

async function waitUp() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`${base}/healthz`)
      if (r.ok) return
    } catch {
      // 还没起来
    }
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error('商店没有在 12 秒内起来\n' + serverLog)
}

async function json(url, opts = {}) {
  const r = await fetch(url, opts)
  const body = await r.json().catch(() => ({}))
  assert.ok(r.ok, `${opts.method || 'GET'} ${url} -> ${r.status} ${JSON.stringify(body)}`)
  return body
}

try {
  await waitUp()

  // 1. 管理员直接上传（不再有作者注册）
  const form = new FormData()
  form.append('file', new Blob([zip], { type: 'application/zip' }), 'menran.sing-1.0.0.zip')
  const up = await json(`${base}/api/v1/publish/upload`, {
    method: 'POST',
    headers: { 'X-Publisher': 'e2e-instance' },
    body: form,
  })
  assert.ok(up.ok, '上传应通过体检')

  // 2. 待审队列 + 审批
  const auth = { 'X-Admin-Token': adminToken }
  const pending = await json(`${base}/api/v1/admin/pending`, { headers: auth })
  assert.equal(pending.items.length, 1, '应有一条待审')
  assert.equal(pending.items[0].author.namespace, '__owner__')
  assert.equal(pending.items[0].publisher, 'e2e-instance')
  const submissionId = pending.items[0].submissionId
  await json(`${base}/api/v1/admin/review/${submissionId}`, {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'approve', note: 'e2e' }),
  })

  // 3. 索引包含该插件，且签名可验
  const pub = await json(`${base}/api/v1/pubkey`)
  const res = await fetch(`${base}/api/v1/index.json`)
  const indexBody = Buffer.from(await res.arrayBuffer())
  const signature = res.headers.get('x-store-signature')
  assert.ok(signature, '索引必须带签名头')
  const key = crypto.createPublicKey({
    key: Buffer.from(pub.publicKey, 'base64'),
    format: 'der',
    type: 'spki',
  })
  assert.ok(crypto.verify(null, indexBody, key, Buffer.from(signature, 'base64')), '索引签名应通过校验')

  const index = JSON.parse(indexBody.toString('utf8'))
  const plugin = index.plugins.find((p) => p.id === 'menran.sing')
  assert.ok(plugin, '索引里应有 menran.sing')
  assert.equal(plugin.latest, '1.0.0')

  // 4. 下载并核对 sha256
  const dl = await fetch(`${base}/api/v1/download/menran.sing/1.0.0`)
  assert.ok(dl.ok, '下载应成功')
  const bytes = Buffer.from(await dl.arrayBuffer())
  const sha = crypto.createHash('sha256').update(bytes).digest('hex')
  assert.equal(sha, plugin.sha256, '下载内容 sha256 应与索引一致')
  assert.equal(dl.headers.get('x-checksum-sha256'), plugin.sha256)

  // 5. 未上架/不存在的版本应 404
  const miss = await fetch(`${base}/api/v1/download/menran.sing/9.9.9`)
  assert.equal(miss.status, 404)

  console.log('✓ store e2e passed:', plugin.id, plugin.latest, sha.slice(0, 12))
} finally {
  child.kill('SIGTERM')
  await new Promise((r) => setTimeout(r, 300))
  fs.rmSync(dataDir, { recursive: true, force: true })
}
