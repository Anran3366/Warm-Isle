// 生成审核后台 TOTP 密钥：node scripts/totp-setup.mjs
// 把输出的 STORE_TOTP_SECRET 写进 /etc/nuanyu-store.env，otpauth 链接粘到任意二维码生成器，
// 用认证器 App（如 Google Authenticator / 1Password / 微信「腾讯身份验证器」）扫码后即完成绑定。
import { generateSecret, otpauthUrl } from '../src/totp.js'

const secret = generateSecret()
console.log('STORE_TOTP_SECRET=' + secret)
console.log('')
console.log('otpauth 链接（生成二维码后扫码绑定）：')
console.log(otpauthUrl(secret))
