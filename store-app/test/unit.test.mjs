// 商店纯逻辑单测：manifest 体检、索引重建、按客户端版本过滤。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const appDir = path.resolve(here, '..')
process.env.STORE_DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'store-unit-'))

const { validateManifest } = await import('../src/verify.js')
const { db, INDEX_FILE } = await import('../src/db.js')
const { rebuildIndex, filterForClient } = await import('../src/indexer.js')

const validManifest = JSON.parse(
  fs.readFileSync(path.resolve(appDir, '..', 'packs', 'menran.sing', 'manifest.json'), 'utf8'),
)

test('官方唱歌包能通过 manifest 体检', () => {
  const issues = validateManifest(structuredClone(validManifest))
  assert.deepEqual(issues, [], JSON.stringify(issues))
})

test('示例句过短、缺 license、缺 voice.strategy 都会被拒', () => {
  const m = structuredClone(validManifest)
  m.intent.examples = ['歌', '唱首歌', '来一首', '想听你唱', '给我唱一段吧']
  m.license = ''
  m.voice = null
  const codes = validateManifest(m).map((i) => i.code)
  assert.ok(codes.includes('EXAMPLE_TOO_SHORT'), codes.join(','))
  assert.ok(codes.includes('MISSING_LICENSE'), codes.join(','))
  assert.ok(codes.includes('MISSING_VOICE'), codes.join(','))
})

test('索引重建选择最高 approved 版本，过滤 revoked 与低版本客户端', () => {
  const now = new Date().toISOString()
  db.prepare(
    'INSERT INTO plugins(id, owner_id, name, summary, status, latest, downloads, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)',
  ).run('menran.sing', 'author-1', '唱歌', '点歌', 'approved', null, 0, now, now)

  const insert = db.prepare(
    `INSERT INTO versions(plugin_id, version, runtime, api_version, min_host, sha256, size, manifest, readme, status, submitted_at)
     VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
  )
  insert.run('menran.sing', '1.0.0', 'data', 1, '1.0.0', 'aaa', 10, JSON.stringify(validManifest), 'r', 'approved', now)
  const newer = structuredClone(validManifest)
  newer.version = '1.2.0'
  newer.compat.minHostVersion = '1.1.0'
  insert.run('menran.sing', '1.2.0', 'data', 1, '1.1.0', 'bbb', 20, JSON.stringify(newer), 'r', 'approved', now)

  const idx = rebuildIndex()
  const body = JSON.parse(fs.readFileSync(INDEX_FILE, 'utf8'))
  assert.equal(body.plugins.length, 1)
  assert.equal(body.plugins[0].latest, '1.2.0')
  assert.equal(body.plugins[0].sha256, 'bbb')

  // 1.0.0 客户端看不到 1.2.0
  const oldClient = filterForClient(idx.body, { hostVersion: '1.0.0', apiVersion: 1 })
  assert.equal(oldClient.plugins.length, 0)
  const newClient = filterForClient(idx.body, { hostVersion: '1.1.0', apiVersion: 1 })
  assert.equal(newClient.plugins.length, 1)

  // 吊销最新版本后，索引退到仍 approved 的 1.0.0
  db.prepare('INSERT INTO revocations(plugin_id, version, reason, created_at) VALUES (?,?,?,?)')
    .run('menran.sing', '1.2.0', 'test', now)
  const afterRevoke = JSON.parse(rebuildIndex().body)
  assert.equal(afterRevoke.plugins[0].latest, '1.0.0')
})

test('rotate-key 生成由旧私钥签名的轮换链', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'store-rotate-'))
  const seed = crypto.randomBytes(32)
  fs.writeFileSync(path.join(dir, 'signing.key'), seed.toString('base64'), { mode: 0o600 })

  execFileSync(process.execPath, [path.join(appDir, 'scripts', 'rotate-key.mjs'), '--data-dir', dir], {
    env: { ...process.env, STORE_SIGNING_KEY: '' },
    stdio: 'ignore',
  })

  const chain = JSON.parse(fs.readFileSync(path.join(dir, 'rotation-chain.json'), 'utf8'))
  assert.equal(chain.length, 1)
  const st = chain[0]

  const pkcs8 = Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), seed])
  const oldPub = crypto.createPublicKey(crypto.createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' }))
  const canonical = Buffer.from(JSON.stringify({
    from: st.from, to: st.to, newPublicKey: st.newPublicKey, effectiveAt: st.effectiveAt,
  }))
  assert.ok(crypto.verify(null, canonical, oldPub, Buffer.from(st.signature, 'base64')), '轮换记录必须由旧私钥签名')

  const newSeed = Buffer.from(fs.readFileSync(path.join(dir, 'signing.key'), 'utf8').trim(), 'base64')
  assert.notDeepEqual(newSeed, seed, '私钥应已更换')
})
