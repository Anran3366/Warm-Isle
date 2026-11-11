// Ed25519 签名密钥管理（文档 §6.8 M2）
// 私钥优先级：env STORE_SIGNING_KEY（base64 的 32 字节种子） > DATA_DIR/signing.key（首次启动自动生成，0600）
// 公钥以 SPKI DER 的 base64 对外提供，宿主内置同款公钥验签。
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { DATA_DIR } from './db.js'

// PKCS#8 前缀（Ed25519 私钥，RFC 8410）：302e020100300506032b657004220420 + 32 字节种子
const PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex')

function keyFromSeed(seed) {
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([PKCS8_PREFIX, seed]),
    format: 'der',
    type: 'pkcs8',
  })
  const publicKey = crypto.createPublicKey(privateKey)
  return { privateKey, publicKey }
}

function loadOrCreate() {
  const envKey = (process.env.STORE_SIGNING_KEY || '').trim()
  if (envKey) {
    const seed = Buffer.from(envKey, 'base64')
    if (seed.length !== 32) throw new Error('STORE_SIGNING_KEY 必须是 base64 编码的 32 字节种子')
    return keyFromSeed(seed)
  }
  const keyFile = path.join(DATA_DIR, 'signing.key')
  if (fs.existsSync(keyFile)) {
    const seed = Buffer.from(fs.readFileSync(keyFile, 'utf8').trim(), 'base64')
    if (seed.length !== 32) throw new Error('signing.key 损坏：不是 32 字节种子')
    return keyFromSeed(seed)
  }
  const seed = crypto.randomBytes(32)
  fs.mkdirSync(DATA_DIR, { recursive: true })
  fs.writeFileSync(keyFile, seed.toString('base64'), { mode: 0o600 })
  const kp = keyFromSeed(seed)
  console.log('[store] 已生成 Ed25519 签名密钥 signing.key，公钥：', kp.publicKey.export({ format: 'der', type: 'spki' }).toString('base64'))
  return kp
}

const { privateKey, publicKey } = loadOrCreate()

/** 公钥（SPKI DER，base64）——宿主验签内置这个值 */
export const PUBLIC_KEY_B64 = publicKey.export({ format: 'der', type: 'spki' }).toString('base64')
/** 密钥 ID：公钥 sha256 前 16 位，便于轮换时识别 */
export const KEY_ID = crypto.createHash('sha256').update(Buffer.from(PUBLIC_KEY_B64, 'base64')).digest('hex').slice(0, 16)

/**
 * 密钥轮换链：每换一次密钥，追加一条「旧私钥签名新公钥」的记录。
 * 宿主已信任旧公钥时，可以顺着链验到新公钥并自动采纳。
 * 链本身不是秘密，也不含私钥。
 */
const CHAIN_FILE = path.join(DATA_DIR, 'rotation-chain.json')

export function rotationChain() {
  try {
    const raw = fs.readFileSync(CHAIN_FILE, 'utf8')
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 对响应体原文签名，返回 base64 */
export function sign(bodyBuffer) {
  return crypto.sign(null, bodyBuffer, privateKey).toString('base64')
}

/** 以签名方式发送 JSON：先序列化，再对原文字节签名放进响应头 */
export function sendSigned(res, payload, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('X-Store-Signature', sign(body))
  res.setHeader('X-Store-Key-Id', KEY_ID)
  for (const [k, v] of Object.entries(extraHeaders)) res.setHeader(k, v)
  res.send(body)
}
