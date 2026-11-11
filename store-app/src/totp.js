// TOTP（RFC 6238）：审核后台二次验证（文档 §6.9「审批后台被爆破 → 独立密码 + 2FA + 登录失败锁定」）
// 零依赖实现：HMAC-SHA1、30 秒步长、6 位码、容忍前后各 1 个窗口。
import crypto from 'node:crypto'

const B32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function generateSecret() {
  const bytes = crypto.randomBytes(20)
  let bits = ''
  for (const b of bytes) bits += b.toString(2).padStart(8, '0')
  let out = ''
  for (let i = 0; i < bits.length; i += 5) out += B32_ALPHABET[parseInt(bits.slice(i, i + 5).padEnd(5, '0'), 2)]
  return out
}

export function base32Decode(s) {
  const clean = String(s).toUpperCase().replace(/[^A-Z2-7]/g, '')
  let bits = ''
  for (const c of clean) bits += B32_ALPHABET.indexOf(c).toString(2).padStart(5, '0')
  const bytes = []
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2))
  return Buffer.from(bytes)
}

function hotp(secret, counter) {
  const buf = Buffer.alloc(8)
  buf.writeBigUInt64BE(BigInt(counter))
  const h = crypto.createHmac('sha1', secret).update(buf).digest()
  const off = h[h.length - 1] & 0x0f
  const code = ((h[off] & 0x7f) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3]
  return String(code % 1_000_000).padStart(6, '0')
}

/** 校验 6 位 TOTP 码；window=±1 个 30s 窗口 */
export function verifyTotp(secretB32, code, atMs = Date.now()) {
  if (!/^\d{6}$/.test(String(code || ''))) return false
  const secret = base32Decode(secretB32)
  if (secret.length === 0) return false
  const step = Math.floor(atMs / 1000 / 30)
  for (const c of [step - 1, step, step + 1]) {
    if (crypto.timingSafeEqual(Buffer.from(String(code)), Buffer.from(hotp(secret, c)))) return true
  }
  return false
}

/** otpauth:// 链接，粘到二维码生成器或直接给认证器 App */
export function otpauthUrl(secretB32, account = 'admin', issuer = 'nuanyu-store') {
  return `otpauth://totp/${encodeURIComponent(issuer)}:${encodeURIComponent(account)}?secret=${secretB32}&issuer=${encodeURIComponent(issuer)}&digits=6&period=30`
}
