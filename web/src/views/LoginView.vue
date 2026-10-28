<template>
  <div class="login-page">
    <canvas ref="canvas" class="particles" />
    <div class="glow glow-1" />
    <div class="glow glow-2" />

    <div class="login-card">
      <div class="brand">
        <div class="brand-icon">伴</div>
        <div>
          <h1>暖屿 · 小满</h1>
          <p>私人陪聊控制台</p>
        </div>
      </div>

      <div class="tabs">
        <button :class="{ active: mode === 'qr' }" @click="switchMode('qr')">扫码登录</button>
        <button :class="{ active: mode === 'pwd' }" @click="switchMode('pwd')">密码登录</button>
      </div>

      <div v-if="mode === 'qr'" class="qr-area">
        <div class="qr-box" :class="{ dim: qrState !== 'ready' }">
          <canvas ref="qrCanvas" />
          <div v-if="qrState === 'loading'" class="qr-mask">生成中…</div>
          <div v-else-if="qrState === 'expired'" class="qr-mask" @click="refreshQr">已过期，点击刷新</div>
          <div v-else-if="qrState === 'approved'" class="qr-mask success">
            <span>✓</span>登录成功
          </div>
        </div>
        <p class="qr-tip">
          {{ qrState === 'approved' ? '正在进入…' : '用手机扫码，在打开的页面输入授权码' }}
        </p>
        <button class="link-btn" @click="refreshQr">刷新二维码</button>
      </div>

      <div v-else class="pwd-area">
        <el-input v-model="password" type="password" show-password placeholder="管理密码" size="large" @keyup.enter="submit" />
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">进入控制台</el-button>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
      <p class="footnote">仅限本人使用 · {{ year }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { api, isMock } from '@/api/client'

const router = useRouter()
const mode = ref<'qr' | 'pwd'>('qr')
const password = ref('')
const loading = ref(false)
const error = ref('')
const year = new Date().getFullYear()
const mockLogin = isMock('/auth/login')

const canvas = ref<HTMLCanvasElement | null>(null)
const qrCanvas = ref<HTMLCanvasElement | null>(null)

const qrState = ref<'loading' | 'ready' | 'expired' | 'approved'>('loading')
const qrToken = ref('')
let pollTimer: number | undefined
let expireTimer: number | undefined

// ---------- 粒子背景 ----------
let raf = 0
let points: { x: number, y: number, vx: number, vy: number, r: number }[] = []
let ctx: CanvasRenderingContext2D | null = null

function initParticles() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const resize = () => {
    el.width = el.clientWidth * dpr
    el.height = el.clientHeight * dpr
    ctx = el.getContext('2d')
    ctx?.scale(dpr, dpr)
  }
  resize()
  const count = Math.min(90, Math.floor((el.clientWidth * el.clientHeight) / 14000))
  points = Array.from({ length: count }, () => ({
    x: Math.random() * el.clientWidth,
    y: Math.random() * el.clientHeight,
    vx: (Math.random() - 0.5) * 0.28,
    vy: (Math.random() - 0.5) * 0.28,
    r: Math.random() * 1.6 + 0.6
  }))
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  const w = () => el.clientWidth
  const h = () => el.clientHeight
  const draw = () => {
    if (!ctx) return
    ctx.clearRect(0, 0, w(), h())
    for (const p of points) {
      if (!reduce) {
        p.x += p.vx
        p.y += p.vy
        if (p.x < 0 || p.x > w()) p.vx *= -1
        if (p.y < 0 || p.y > h()) p.vy *= -1
      }
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(214,120,96,0.38)'
      ctx.fill()
    }
    for (let i = 0; i < points.length; i++) {
      for (let j = i + 1; j < points.length; j++) {
        const dx = points[i].x - points[j].x
        const dy = points[i].y - points[j].y
        const d2 = dx * dx + dy * dy
        if (d2 < 13000) {
          ctx.beginPath()
          ctx.moveTo(points[i].x, points[i].y)
          ctx.lineTo(points[j].x, points[j].y)
          ctx.strokeStyle = `rgba(226,150,120,${0.18 * (1 - d2 / 13000)})`
          ctx.lineWidth = 0.7
          ctx.stroke()
        }
      }
    }
    raf = requestAnimationFrame(draw)
  }
  if (reduce) {
    draw()
  } else {
    raf = requestAnimationFrame(draw)
  }
  window.addEventListener('resize', resize)
}

// ---------- 扫码登录 ----------
async function refreshQr() {
  clearTimers()
  qrState.value = 'loading'
  error.value = ''
  try {
    const res = await api<{ token: string, url: string, ttlSeconds: number }>('/auth/qr/new', { method: 'POST' })
    qrToken.value = res.token
    await nextTick()
    const el = qrCanvas.value
    if (el) {
      await QRCode.toCanvas(el, res.url, {
        width: 200,
        margin: 1,
        color: { dark: '#1b2140', light: '#ffffff' }
      })
    }
    qrState.value = 'ready'
    expireTimer = window.setTimeout(() => { qrState.value = 'expired' }, res.ttlSeconds * 1000)
    pollTimer = window.setInterval(poll, 2000)
  } catch (e: any) {
    qrState.value = 'expired'
    error.value = e?.message ?? '二维码生成失败'
  }
}

async function poll() {
  if (!qrToken.value || qrState.value === 'approved') return
  try {
    const res = await api<{ status: string }>(`/auth/qr/${qrToken.value}`)
    if (res.status === 'approved') {
      qrState.value = 'approved'
      clearTimers()
      await api(`/auth/qr/${qrToken.value}/claim`, { method: 'POST' })
      sessionStorage.setItem('dsh-admin', '1')
      setTimeout(() => router.replace('/dashboard'), 600)
    } else if (res.status === 'expired') {
      qrState.value = 'expired'
      clearTimers()
    }
  } catch { /* 轮询失败忽略，下一轮重试 */ }
}

function clearTimers() {
  if (pollTimer) window.clearInterval(pollTimer)
  if (expireTimer) window.clearTimeout(expireTimer)
  pollTimer = undefined
  expireTimer = undefined
}

function switchMode(m: 'qr' | 'pwd') {
  mode.value = m
  if (m === 'qr') refreshQr()
}

// ---------- 密码登录 ----------
async function submit() {
  if (!password.value) { error.value = '请输入密码'; return }
  loading.value = true
  error.value = ''
  try {
    await api('/auth/login', { method: 'POST', body: { password: password.value } })
    sessionStorage.setItem('dsh-admin', '1')
    router.replace('/dashboard')
  } catch (e: any) {
    error.value = e?.message ?? '登录失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  initParticles()
  if (mockLogin) return
  try {
    await api('/status')
    sessionStorage.setItem('dsh-admin', '1')
    router.replace('/dashboard')
    return
  } catch { /* 未登录 */ }
  refreshQr()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  clearTimers()
})
</script>

<style scoped>
/* 与后台一致的暖色风格 */
.login-page {
  position: relative;
  min-height: 100vh;
  display: grid;
  place-items: center;
  overflow: hidden;
  padding: 24px 16px;
  background:
    radial-gradient(900px 420px at 15% 0%, #fbe7de 0%, rgba(251, 231, 222, 0) 60%),
    radial-gradient(700px 420px at 90% 100%, #f6e7f0 0%, rgba(246, 231, 240, 0) 60%),
    #f7f4f0;
}
.particles { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0.85; }
.glow { position: absolute; border-radius: 50%; filter: blur(90px); opacity: 0.55; pointer-events: none; }
.glow-1 { width: 420px; height: 420px; background: #f7cbb8; top: -140px; left: -100px; }
.glow-2 { width: 380px; height: 380px; background: #f3c3d8; bottom: -160px; right: -80px; }
.login-card {
  position: relative;
  width: 380px;
  max-width: calc(100vw - 32px);
  padding: 30px 28px 22px;
  border-radius: 20px;
  background: #fffdfb;
  border: 1px solid #ecdcd3;
  box-shadow: 0 18px 48px rgba(180, 130, 110, 0.16);
  color: #3a2f2a;
  z-index: 1;
}
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 22px; }
.brand-icon {
  width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center;
  font-size: 22px; font-weight: 700; color: #fff;
  background: linear-gradient(135deg, #e8927c, #d9738f);
  box-shadow: 0 8px 20px rgba(232, 146, 124, 0.35);
}
.brand h1 { margin: 0; font-size: 19px; letter-spacing: 0.4px; color: #3a2f2a; }
.brand p { margin: 2px 0 0; font-size: 12px; color: #9b857a; }
.tabs { display: flex; gap: 8px; margin-bottom: 18px; }
.tabs button {
  flex: 1; padding: 8px 0; border-radius: 10px; cursor: pointer;
  background: #faf3ee; border: 1px solid #f0e1d8;
  color: #8b7268; font-size: 13px; transition: all 0.2s;
}
.tabs button.active {
  color: #b8573f; border-color: #e8927c;
  background: #fdf0ea;
  font-weight: 600;
}
.qr-area { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.qr-box {
  position: relative; width: 200px; height: 200px; padding: 10px; border-radius: 14px;
  background: #fff; display: grid; place-items: center; border: 1px solid #f0e1d8;
}
.qr-box.dim canvas { opacity: 0.25; }
.qr-mask {
  position: absolute; inset: 0; display: grid; place-items: center; border-radius: 14px;
  background: rgba(255, 253, 251, 0.92); color: #8b7268; font-size: 13px; cursor: pointer;
}
.qr-mask.success { color: #3f9e6b; font-size: 15px; display: flex; gap: 8px; align-items: center; }
.qr-mask.success span { font-size: 22px; }
.qr-tip { margin: 0; font-size: 12px; color: #9b857a; }
.link-btn { background: none; border: none; color: #c96a55; font-size: 12px; cursor: pointer; }
.link-btn:hover { text-decoration: underline; }
.pwd-area { display: flex; flex-direction: column; gap: 12px; }
.pwd-area :deep(.el-input__wrapper) {
  background: #faf3ee;
  box-shadow: 0 0 0 1px #f0e1d8 inset;
}
.pwd-area :deep(.el-input__inner) { color: #3a2f2a; }
.submit { width: 100%; }
.error { margin: 12px 0 0; font-size: 12px; color: #d9534f; text-align: center; }
.footnote { margin: 16px 0 0; text-align: center; font-size: 11px; color: #b8a59a; }

@media (max-width: 480px) {
  .login-card { padding: 24px 20px 18px; }
  .qr-box { width: 180px; height: 180px; }
}
</style>