// 生成新的商店签名密钥，并追加一条由「旧私钥」签名的轮换记录。
// 用法：node scripts/rotate-key.mjs [--data-dir /opt/nuanyu-store/data]
// 之后重启 nuanyu-store 生效。
//
// 安全前提：旧私钥仍在服务器上（signing.key）。如果旧私钥已经泄漏，
// 应该改用离线根密钥方案，而不是继续用旧私钥签轮换。
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex')
const args = process.argv.slice(2)
const dataDirArg = args.indexOf('--data-dir')
const dataDir = path.resolve(dataDirArg >= 0 ? args[dataDirArg + 1] : process.env.STORE_DATA_DIR || './data')

function fromSeed(seed) {
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([PKCS8_PREFIX, seed]), format: 'der', type: 'pkcs8',
  })
  const publicKey = crypto.createPublicKey(privateKey)
  const pub = publicKey.export({ format: 'der', type: 'spki' }).toString('base64')
  const id = crypto.createHash('sha256').update(Buffer.from(pub, 'base64')).digest('hex').slice(0, 16)
  return { privateKey, publicKey, pub, id }
}

const envSeed = (process.env.STORE_SIGNING_KEY || '').trim()
if (envSeed) {
  console.error('检测到 STORE_SIGNING_KEY：密钥来自环境变量，脚本不会改它。请更新环境变量后重启。')
  process.exit(1)
}

const keyFile = path.join(dataDir, 'signing.key')
if (!fs.existsSync(keyFile)) {
  console.error('找不到 signing.key：' + keyFile)
  process.exit(1)
}
const oldSeed = Buffer.from(fs.readFileSync(keyFile, 'utf8').trim(), 'base64')
if (oldSeed.length !== 32) {
  console.error('signing.key 损坏：不是 32 字节种子')
  process.exit(1)
}
const oldKey = fromSeed(oldSeed)

const newSeed = crypto.randomBytes(32)
const newKey = fromSeed(newSeed)
const effectiveAt = new Date().toISOString()
const statement = {
  from: oldKey.id,
  to: newKey.id,
  newPublicKey: newKey.pub,
  effectiveAt,
}
const canonical = Buffer.from(JSON.stringify(statement), 'utf8')
const signature = crypto.sign(null, canonical, oldKey.privateKey).toString('base64')

const chainFile = path.join(dataDir, 'rotation-chain.json')
let chain = []
try { chain = JSON.parse(fs.readFileSync(chainFile, 'utf8')) } catch { /* 首轮 */ }
chain.push({ ...statement, signature })
fs.writeFileSync(chainFile, JSON.stringify(chain, null, 2), { mode: 0o600 })

// 备份旧私钥（回滚用），再切到新私钥
fs.writeFileSync(keyFile + '.' + oldKey.id + '.bak', oldSeed.toString('base64'), { mode: 0o600 })
fs.writeFileSync(keyFile, newSeed.toString('base64'), { mode: 0o600 })

console.log('已轮换签名密钥')
console.log('  old keyId =', oldKey.id)
console.log('  new keyId =', newKey.id)
console.log('  new publicKey =', newKey.pub)
console.log('  chain =', chainFile)
console.log('重启服务后，已部署实例会在下次验签失败时自动拉链并采纳新公钥。')
