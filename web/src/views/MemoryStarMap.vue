<template>
  <div class="star-page">
    <div class="toolbar card">
      <div class="left">
        <el-select v-model="scope" size="default" class="user-select" @change="load">
          <el-option label="所有人" value="all" />
          <el-option
            v-for="u in userOptions"
            :key="u.qqId"
            :label="`${u.name}（${u.active}）`"
            :value="u.qqId"
          />
        </el-select>
        <el-input v-model="search" placeholder="搜记忆…" clearable size="default" class="search" />
        <el-checkbox v-model="includeArchived" @change="load">显示已忘记</el-checkbox>
      </div>
      <div class="right">
        <span class="stat">记忆 <b>{{ meta.total ?? 0 }}</b></span>
        <span class="stat">用户 <b>{{ meta.users ?? 0 }}</b></span>
        <span class="stat fading">快忘了 <b>{{ meta.fading ?? 0 }}</b></span>
        <span class="stat pinned">钉住 <b>{{ meta.pinned ?? 0 }}</b></span>
        <el-button size="small" @click="resetView">重置视角</el-button>
        <el-button size="small" :type="autoRotate ? 'primary' : 'default'" @click="autoRotate = !autoRotate">
          {{ autoRotate ? '暂停自转' : '继续自转' }}
        </el-button>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="stage card">
      <canvas
        ref="canvas"
        @wheel.prevent="onWheel"
        @mousedown="onDown"
        @mousemove="onMove"
        @mouseup="onUp"
        @mouseleave="onLeave"
        @click="onClick"
      />
      <div class="legend">
        <div
          v-for="l in layers"
          :key="l.type"
          class="legend-item"
          :class="{ off: hidden.has(l.type) }"
          @click="toggleLayer(l.type)"
        >
          <i :style="{ background: l.color }" />
          <span>{{ l.label }}</span>
          <em>{{ countOf(l.type) }}</em>
        </div>
      </div>
      <div v-if="empty" class="empty-tip">
        <p>还没有记忆</p>
        <span>聊几句，这片星海就会亮起来</span>
      </div>
      <div class="hint">拖动旋转 · 滚轮缩放 · 悬停高亮整条链路 · 越靠近中心 = 记得越牢</div>

      <transition name="slide">
        <aside v-if="selected" class="detail" :style="{ borderColor: colorOf(selected.type) }">
          <header>
            <i :style="{ background: colorOf(selected.type) }" />
            <span>{{ typeLabel(selected.type) }}</span>
            <el-button text size="small" class="close" @click="selected = null">✕</el-button>
          </header>
          <h3>{{ selected.key || '（无主题）' }}</h3>
          <p class="value">{{ selected.value }}</p>
          <div class="strength">
            <div class="bar"><i :style="{ width: (selected.strength * 100).toFixed(0) + '%', background: colorOf(selected.type) }" /></div>
            <span>记忆强度 {{ (selected.strength * 100).toFixed(0) }}%</span>
          </div>
          <dl>
            <dt>归属</dt><dd>{{ selected.name }}（{{ selected.qqId }}）</dd>
            <dt>重要度</dt><dd>{{ selected.importance.toFixed(2) }}</dd>
            <dt>被想起</dt><dd>{{ selected.recallCount }} 次</dd>
            <dt>最近确认</dt><dd>{{ selected.lastConfirmed || '—' }}</dd>
            <dt v-if="selected.recurMd">每年</dt><dd v-if="selected.recurMd">{{ selected.recurMd }}</dd>
          </dl>
          <el-button
            size="small"
            :type="selected.pinned ? 'warning' : 'default'"
            @click="togglePin(selected)"
          >{{ selected.pinned ? '取消钉住' : '钉住，永不忘记' }}</el-button>
        </aside>
      </transition>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'MemoryStarMap' })
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'

interface GraphNode {
  id: string
  qqId: string
  name: string
  type: string
  subject: string
  key: string
  value: string
  importance: number
  strength: number
  pinned: boolean
  status: string
  fading: boolean
  recallCount: number
  lastConfirmed?: string | null
  recurMd?: string | null
}
interface GraphEdge { a: string; b: string; w: number; kind: string }
interface GraphLayer { type: string; label: string; color: string; ring?: number }
interface GraphResp {
  nodes: GraphNode[]
  edges: GraphEdge[]
  layers: GraphLayer[]
  meta: { total: number; fading: number; pinned: number; users?: number }
}
interface UserOption { qqId: string; name: string; total: number; active: number }

/** 银河：最内圈 / 最外沿半径（强度决定落在哪一圈） */
const INNER_R = 22
const GALAXY_R = 330
const CAMERA = 900
const GOLDEN = Math.PI * (3 - Math.sqrt(5))
/** 旋臂数量随记忆量增长（3→7），不设条数上限 */
let arms = 3

interface Dust { x: number; y: number; z: number; ph: number; sz: number }
interface Star extends GraphNode {
  x: number
  y: number
  z: number
  dust: Dust[]
}

const canvas = ref<HTMLCanvasElement | null>(null)
const search = ref('')
const scope = ref('all')
const userOptions = ref<UserOption[]>([])
const includeArchived = ref(true)
const loading = ref(false)
const selected = ref<GraphNode | null>(null)
const hidden = ref<Set<string>>(new Set())
const autoRotate = ref(true)
const layers = ref<GraphLayer[]>([
  { type: 'relation', label: '你们之间', color: '#ff8fb8' },
  { type: 'profile', label: '关于对方', color: '#7ee8fa' },
  { type: 'event', label: '最近发生', color: '#9dffc9' },
  { type: 'world', label: '设定', color: '#c9a7ff' }
])
const meta = ref<{ total: number; fading: number; pinned: number; users?: number }>({ total: 0, fading: 0, pinned: 0 })

const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
/** 邻接表：悬停时用它点亮整条链路 */
const adjacency = new Map<string, { id: string; w: number }[]>()

const stars = new Map<string, Star>()
const screenPos = new Map<string, { x: number; y: number; r: number; z: number }>()

let rotY = 0.6
let rotX = -0.46
let zoom = 1
let dragging = false
let moved = 0
let lastX = 0
let lastY = 0
let hovered: string | null = null
let hoveredSet = new Set<string>()
let raf = 0
let dpr = 1
let W = 0
let H = 0
let dustBg: { x: number; y: number; r: number; a: number; p: number }[] = []
/** 星系底噪：不属于任何记忆的星尘，用来撑出旋涡的形状 */
let ambient: { r: number; a: number; y: number; ph: number; sz: number }[] = []

function mulberry32(seed: number) {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const empty = computed(() => nodes.value.length === 0)
const colorOf = (type: string) => layers.value.find(l => l.type === type)?.color ?? '#9aa4b5'
const typeLabel = (type: string) => layers.value.find(l => l.type === type)?.label ?? type
function countOf(type: string) {
  return nodes.value.filter(n => n.type === type).length
}

function hash(s: string) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return ((h >>> 0) % 10000) / 10000
}

/**
 * 摆位：三条旋臂的压扁星系。
 * 半径由强度决定（强 → 靠内，弱 → 靠外），用 0.55 次幂把中段拉开，避免全挤在中心；
 * 角度 = 臂偏移 + 对数螺旋 + 抖动，半径和角度强耦合，转起来才看得出螺旋臂。
 */
function layout() {
  stars.clear()
  // 按强度从强到弱排：最强的落在圆心，忘掉的排到最外圈；
  // 半径只看排名（面积均匀），任意条数都能铺满整个盘面，不设上限。
  const list = nodes.value.slice().sort((a, b) => b.strength - a.strength)
  arms = Math.min(7, Math.max(3, Math.ceil(list.length / 90)))
  const dustFactor = list.length > 1200 ? 0.3 : list.length > 600 ? 0.6 : 1
  list.forEach((node, i) => {
    const s = Math.max(0, Math.min(1, node.strength))
    const t = 1 - s
    const rank = (i + 0.5) / list.length
    const rr = INNER_R + Math.sqrt(rank)
      * (GALAXY_R - INNER_R) * (0.92 + hash(node.id) * 0.16)
    const arm = i % arms
    const theta = arm * ((Math.PI * 2) / arms)
      + Math.log(1 + rr / 60) * 2.6
      + (hash(node.id + 't') - 0.5) * 0.95
    const spread = 7 + 46 * t
    const y = (hash(node.id + 'y') - 0.5) * spread
    const count = Math.max(2, Math.round((5 + node.importance * 9) * dustFactor))
    const dust: Dust[] = []
    for (let d = 0; d < count; d++) {
      const a = hash(node.id + 'a' + d) * Math.PI * 2
      const rad = (0.3 + hash(node.id + 'r' + d)) * (12 + 26 * t)
      dust.push({
        x: Math.cos(a) * rad,
        y: (hash(node.id + 'y' + d) - 0.5) * spread * 0.9,
        z: Math.sin(a) * rad,
        ph: hash(node.id + 'p' + d) * 6.28,
        sz: 0.35 + hash(node.id + 's' + d) * 0.85
      })
    }
    stars.set(node.id, {
      ...node,
      x: Math.cos(theta) * rr,
      y,
      z: Math.sin(theta) * rr,
      dust
    })
  })
  rebuildAdjacency()
}

function rebuildAdjacency() {
  adjacency.clear()
  for (const e of edges.value) {
    if (!adjacency.has(e.a)) adjacency.set(e.a, [])
    if (!adjacency.has(e.b)) adjacency.set(e.b, [])
    adjacency.get(e.a)!.push({ id: e.b, w: e.w })
    adjacency.get(e.b)!.push({ id: e.a, w: e.w })
  }
}

function buildAmbient() {
  const rnd = mulberry32(20261104)
  ambient = []
  // 三分之二沿旋臂铺，三分之一均匀铺底，避免只剩三条细线
  for (let i = 0; i < 2200; i++) {
    const t = Math.pow(rnd(), 0.62)
    const rr = INNER_R + t * (GALAXY_R - INNER_R) * 1.02
    const onArm = rnd() < 0.66
    const arm = i % arms
    const a = onArm
      ? arm * ((Math.PI * 2) / arms) + Math.log(1 + rr / 60) * 2.6 + (rnd() - 0.5) * 0.85
      : rnd() * Math.PI * 2
    ambient.push({
      r: rr,
      a,
      y: (rnd() - 0.5) * (10 + 52 * t),
      ph: rnd() * 6.28,
      sz: 0.45 + rnd() * 1.1
    })
  }
  // 核心：再加一小团密集的星尘，撑住中心的亮度
  for (let i = 0; i < 420; i++) {
    const rr = 8 + Math.pow(rnd(), 0.6) * 95
    ambient.push({
      r: rr,
      a: rnd() * Math.PI * 2,
      y: (rnd() - 0.5) * 26,
      ph: rnd() * 6.28,
      sz: 0.4 + rnd() * 0.9
    })
  }
}

function visible(n: GraphNode) {
  if (hidden.value.has(n.type)) return false
  const q = search.value.trim().toLowerCase()
  if (!q) return true
  return `${n.key} ${n.value} ${n.name ?? ''}`.toLowerCase().includes(q)
}

function rot(x: number, y: number, z: number) {
  const cy = Math.cos(rotY), sy = Math.sin(rotY)
  const x1 = x * cy + z * sy
  const z1 = -x * sy + z * cy
  const cx = Math.cos(rotX), sx = Math.sin(rotX)
  const y1 = y * cx - z1 * sx
  const z2 = y * sx + z1 * cx
  return { x: x1, y: y1, z: z2 }
}

function fitScale() {
  return Math.min(1, (Math.min(W, H) / 2 - 40) / (GALAXY_R * 1.06))
}

function project(p: { x: number; y: number; z: number }, cx: number, cy: number) {
  const s = (CAMERA / (CAMERA + p.z)) * zoom * fitScale()
  return { x: cx + p.x * s, y: cy + p.y * s, z: p.z, s }
}

function resize() {
  const cv = canvas.value
  if (!cv) return
  dpr = window.devicePixelRatio || 1
  const rect = cv.parentElement!.getBoundingClientRect()
  W = rect.width
  H = rect.height
  cv.width = W * dpr
  cv.height = H * dpr
  cv.style.width = `${W}px`
  cv.style.height = `${H}px`
  dustBg = Array.from({ length: 320 }, () => ({
    x: Math.random(), y: Math.random(), r: Math.random() * 1 + 0.2,
    a: Math.random() * 0.4 + 0.04, p: Math.random() * 6.28
  }))
}

function hexA(hex: string, a: number) {
  const v = parseInt(hex.slice(1), 16)
  return `rgba(${(v >> 16) & 255},${(v >> 8) & 255},${v & 255},${a})`
}

/** 把类型色往白里混，星海整体偏冷白，只有光晕保留一点类型色 */
function soften(hex: string, t = 0.6) {
  if (hex.startsWith('rgb')) return hex
  const v = parseInt(hex.slice(1), 16)
  const mix = (c: number, w: number) => Math.round(c + (w - c) * t)
  const r = mix((v >> 16) & 255, 226)
  const g = mix((v >> 8) & 255, 238)
  const b = mix(v & 255, 255)
  return '#' + [r, g, b].map(x => x.toString(16).padStart(2, '0')).join('')
}

function draw() {
  const cv = canvas.value
  if (!cv) return
  const ctx = cv.getContext('2d')!
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  const cx = W / 2
  const cy = H / 2
  const t = Date.now() / 1000

  if (autoRotate.value && !dragging) rotY += 0.0016

  const bg = ctx.createRadialGradient(cx, cy, 40, cx, cy, Math.max(W, H) * 0.72)
  bg.addColorStop(0, '#0b1430')
  bg.addColorStop(0.55, '#060a18')
  bg.addColorStop(1, '#020308')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, W, H)

  for (const d of dustBg) {
    ctx.globalAlpha = d.a * (0.5 + 0.5 * Math.sin(t * 1.1 + d.p))
    ctx.fillStyle = '#cdd8ff'
    ctx.beginPath()
    ctx.arc(d.x * W, d.y * H, d.r, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.globalAlpha = 1

  // 核心光晕
  const coreR = 150 * zoom * fitScale()
  const core = ctx.createRadialGradient(cx, cy, 0, cx, cy, coreR)
  core.addColorStop(0, 'rgba(255, 248, 228, 0.22)')
  core.addColorStop(0.35, 'rgba(190, 210, 255, 0.09)')
  core.addColorStop(1, 'rgba(120, 160, 255, 0)')
  ctx.fillStyle = core
  ctx.beginPath()
  ctx.arc(cx, cy, coreR, 0, Math.PI * 2)
  ctx.fill()

  drawHud(ctx)

  // 悬停的链路集合：自己 + 一跳邻居
  hoveredSet = new Set()
  if (hovered) {
    hoveredSet.add(hovered)
    for (const nb of adjacency.get(hovered) ?? []) {
      hoveredSet.add(nb.id)
    }
  }
  const dimming = hoveredSet.size > 0

  const amb: { x: number; y: number; z: number; r: number; a: number }[] = []
  for (const p of ambient) {
    const q = rot(Math.cos(p.a) * p.r, p.y, Math.sin(p.a) * p.r)
    const P = project(q, cx, cy)
    const depth = 1 - Math.min(1, Math.abs(q.z) / (GALAXY_R * 1.2))
    amb.push({
      x: P.x, y: P.y, z: q.z,
      r: p.sz * P.s,
      a: depth * (dimming ? 0.03 : 0.14) * (0.6 + 0.4 * Math.sin(t * 1.3 + p.ph))
    })
  }
  amb.sort((a, b) => b.z - a.z)
  for (const p of amb) {
    ctx.fillStyle = `rgba(205, 224, 255, ${p.a})`
    ctx.beginPath()
    ctx.arc(p.x, p.y, Math.max(0.3, p.r), 0, Math.PI * 2)
    ctx.fill()
  }

  // 悬停链路：先画线，避免被点盖住
  if (hovered) {
    const center = stars.get(hovered)
    if (center) {
      const c = rot(center.x, center.y, center.z)
      const C = project(c, cx, cy)
      for (const nb of adjacency.get(hovered) ?? []) {
        const target = stars.get(nb.id)
        if (!target) continue
        const q = rot(target.x, target.y, target.z)
        const P = project(q, cx, cy)
        const grad = ctx.createLinearGradient(C.x, C.y, P.x, P.y)
        const color = soften(colorOf(target.type), 0.3)
        grad.addColorStop(0, hexA(color, 0.85))
        grad.addColorStop(1, hexA(color, 0.25))
        ctx.strokeStyle = grad
        ctx.lineWidth = 0.9
        ctx.beginPath()
        ctx.moveTo(C.x, C.y)
        ctx.lineTo(P.x, P.y)
        ctx.stroke()
      }
    }
  }

  const points: { x: number; y: number; z: number; r: number; a: number; color: string }[] = []
  const cores: { star: Star; x: number; y: number; z: number; r: number }[] = []

  for (const star of stars.values()) {
    if (!visible(star)) continue
    const s = Math.max(0.05, star.strength)
    const color = star.status !== 'active' ? '#5b6472' : colorOf(star.type)
    const inChain = hoveredSet.has(star.id)
    const dim = dimming && !inChain ? 0.16 : 1

    for (const d of star.dust) {
      const q = rot(star.x + d.x, star.y + d.y, star.z + d.z)
      const P = project(q, cx, cy)
      const depth = 1 - Math.min(1, Math.abs(q.z) / (GALAXY_R * 1.2))
      points.push({
        x: P.x,
        y: P.y,
        z: q.z,
        r: d.sz * P.s * 0.9,
        a: (0.14 + 0.34 * s) * depth * dim * (0.65 + 0.35 * Math.sin(t * 1.7 + d.ph)),
        color
      })
    }

    const q = rot(star.x, star.y, star.z)
    const P = project(q, cx, cy)
    cores.push({
      star, x: P.x, y: P.y, z: q.z,
      r: (1.6 + star.importance * 2.2) * P.s * (0.55 + 0.7 * s) * (inChain ? 1.25 : 1)
    })
  }

  // 底噪点
  for (const p of points) {
    ctx.fillStyle = hexA(p.color, p.a * 0.7)
    ctx.beginPath()
    ctx.arc(p.x, p.y, Math.max(0.3, p.r), 0, Math.PI * 2)
    ctx.fill()
  }

  cores.sort((a, b) => b.z - a.z)
  screenPos.clear()
  for (const c of cores) {
    const { star } = c
    const s = Math.max(0.05, star.strength)
    const back = c.z > 0
    const color = star.status !== 'active' ? '#5b6472' : colorOf(star.type)
    const inChain = hoveredSet.has(star.id)
    const dim = dimming && !inChain ? 0.18 : 1
    const r = c.r

    const halo = ctx.createRadialGradient(c.x, c.y, 0, c.x, c.y, r * 6)
    halo.addColorStop(0, hexA(color, (back ? 0.16 : 0.48) * s * dim))
    halo.addColorStop(0.3, hexA(color, (back ? 0.05 : 0.13) * s * dim))
    halo.addColorStop(1, hexA(color, 0))
    ctx.fillStyle = halo
    ctx.beginPath()
    ctx.arc(c.x, c.y, r * 6, 0, Math.PI * 2)
    ctx.fill()

    ctx.fillStyle = hexA(soften(color), ((back ? 0.4 : 0.7) + 0.3 * s) * dim)
    ctx.beginPath()
    ctx.arc(c.x, c.y, r, 0, Math.PI * 2)
    ctx.fill()

    if (star.pinned) {
      ctx.strokeStyle = `rgba(255, 214, 102, ${0.8 * dim})`
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.arc(c.x, c.y, r + 3 + Math.sin(t * 3) * 1.2, 0, Math.PI * 2)
      ctx.stroke()
    }
    if (selected.value?.id === star.id) {
      ctx.strokeStyle = 'rgba(255,255,255,0.9)'
      ctx.lineWidth = 1.2
      ctx.beginPath()
      ctx.arc(c.x, c.y, r + 6, 0, Math.PI * 2)
      ctx.stroke()
    }
    if (hovered === star.id) {
      ctx.strokeStyle = 'rgba(255,255,255,0.95)'
      ctx.lineWidth = 1.5
      ctx.beginPath()
      ctx.arc(c.x, c.y, r + 4, 0, Math.PI * 2)
      ctx.stroke()
    }

    screenPos.set(star.id, { x: c.x, y: c.y, r: Math.max(7, r + 5), z: c.z })
  }

  drawLabels(ctx, cores, dimming)
  raf = requestAnimationFrame(draw)
}

/** 淡淡的记忆文字：只给足够强、朝前的记忆加一行小字 */
function drawLabels(ctx: CanvasRenderingContext2D, cores: { star: Star; x: number; y: number; z: number; r: number }[], dimming: boolean) {
  ctx.font = '10px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.textBaseline = 'middle'
  let shown = 0
  for (const c of cores) {
    if (shown >= 70) break
    const s = c.star.strength
    const inChain = hoveredSet.has(c.star.id)
    if (c.z > 0 || (s < 0.42 && !inChain)) continue
    if (dimming && !inChain) continue
    const text = inChain && hovered === c.star.id
      ? `${c.star.name} · ${(c.star.key || c.star.value).slice(0, 12)}`
      : (c.star.key || c.star.value || '').replace(/\s+/g, ' ').slice(0, 14)
    if (!text) continue
    const alpha = inChain && dimming ? 0.75 : 0.06 + 0.15 * s
    ctx.fillStyle = `rgba(210, 230, 255, ${alpha})`
    ctx.fillText(text, c.x + c.r + 5, c.y)
    shown++
  }
}

/** 机械 HUD：四角括号 + 左上角状态字 */
function drawHud(ctx: CanvasRenderingContext2D) {
  const pad = 18
  const len = 26
  ctx.strokeStyle = 'rgba(126, 232, 250, 0.26)'
  ctx.lineWidth = 1.2
  const corners = [
    [pad, pad, 1, 1], [W - pad, pad, -1, 1],
    [pad, H - pad, 1, -1], [W - pad, H - pad, -1, -1]
  ]
  for (const [x, y, sx, sy] of corners) {
    ctx.beginPath()
    ctx.moveTo(x + sx * len, y)
    ctx.lineTo(x, y)
    ctx.lineTo(x, y + sy * len)
    ctx.stroke()
  }

  ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.textBaseline = 'top'
  ctx.fillStyle = 'rgba(126, 232, 250, 0.5)'
  ctx.fillText('MEMORY SEA', pad + 10, pad + 12)
  ctx.fillStyle = 'rgba(160, 190, 220, 0.42)'
  ctx.fillText(`NODES ${String(meta.value.total).padStart(3, '0')}`, pad + 10, pad + 30)
  const avg = nodes.value.length
    ? nodes.value.reduce((s, n) => s + n.strength, 0) / nodes.value.length
    : 0
  ctx.fillText(`SYNC ${(avg * 100).toFixed(1)}%`, pad + 10, pad + 46)
  ctx.fillText(`SPIN ${(((rotY * 180) / Math.PI) % 360).toFixed(1)}°`, pad + 10, pad + 62)
}

function hitTest(clientX: number, clientY: number): GraphNode | null {
  const cv = canvas.value
  if (!cv) return null
  const rect = cv.getBoundingClientRect()
  const mx = clientX - rect.left
  const my = clientY - rect.top
  let best: GraphNode | null = null
  let bestD = 18
  for (const [id, p] of screenPos) {
    const d = Math.hypot(p.x - mx, p.y - my)
    if (d < Math.max(bestD, p.r)) {
      bestD = d
      best = stars.get(id) ?? null
    }
  }
  return best
}

function onWheel(e: WheelEvent) {
  zoom = Math.min(3, Math.max(0.45, zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)))
}
function onDown(e: MouseEvent) {
  dragging = true
  moved = 0
  lastX = e.clientX
  lastY = e.clientY
}
function onMove(e: MouseEvent) {
  if (dragging) {
    const dx = e.clientX - lastX
    const dy = e.clientY - lastY
    lastX = e.clientX
    lastY = e.clientY
    moved += Math.abs(dx) + Math.abs(dy)
    rotY += dx * 0.006
    rotX = Math.max(-1.3, Math.min(1.3, rotX + dy * 0.005))
    return
  }
  const hit = hitTest(e.clientX, e.clientY)
  hovered = hit?.id ?? null
  const cv = canvas.value
  if (cv) cv.style.cursor = hit ? 'pointer' : 'grab'
}
function onUp() { dragging = false }
function onLeave() {
  dragging = false
  hovered = null
}
function onClick(e: MouseEvent) {
  if (moved > 4) return
  const hit = hitTest(e.clientX, e.clientY)
  if (hit) selected.value = hit
}
function resetView() {
  zoom = 1
  rotY = 0.6
  rotX = -0.46
}
function toggleLayer(type: string) {
  const next = new Set(hidden.value)
  if (next.has(type)) next.delete(type)
  else next.add(type)
  hidden.value = next
}

async function togglePin(n: GraphNode) {
  try {
    await api(`/memories/${n.id}/pin`, { method: 'POST', body: { pinned: !n.pinned } })
    n.pinned = !n.pinned
    if (n.pinned) n.strength = 1
    const s = stars.get(n.id)
    if (s) s.pinned = n.pinned
    ElMessage.success(n.pinned ? '已钉住，这条不会再忘' : '已取消钉住')
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

async function loadUsers() {
  try {
    userOptions.value = await api<UserOption[]>('/memories/graph/users')
  } catch (e) {
    userOptions.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const scopeQuery = scope.value === 'all' ? 'all=true' : `all=false&qqId=${scope.value}`
    const data = await api<GraphResp>(`/memories/graph?${scopeQuery}&includeArchived=${includeArchived.value}`)
    nodes.value = data.nodes ?? []
    edges.value = data.edges ?? []
    if (data.layers?.length) layers.value = data.layers
    meta.value = {
      total: data.meta?.total ?? 0,
      fading: data.meta?.fading ?? 0,
      pinned: data.meta?.pinned ?? 0,
      users: data.meta?.users ?? 0
    }
    if (selected.value) {
      selected.value = nodes.value.find(n => n.id === selected.value!.id) ?? null
    }
    layout()
  } catch (e) {
    nodes.value = []
    edges.value = []
    ElMessage.error('记忆星图加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  buildAmbient()
  loadUsers()
  resize()
  load()
  draw()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  window.removeEventListener('resize', resize)
})
</script>

<style scoped>
.star-page { display: flex; flex-direction: column; gap: 12px; height: calc(100vh - 120px); }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; padding: 10px 14px; }
.toolbar .left { display: flex; align-items: center; gap: 14px; }
.search { width: 200px; }
.user-select { width: 170px; }
.stat { color: #8b98ab; font-size: 13px; margin-right: 6px; }
.stat b { color: #e6edf7; }
.stat.fading b { color: #f0b45a; }
.stat.pinned b { color: #ffd166; }
.stage { position: relative; flex: 1; overflow: hidden; padding: 0; background: #020308; min-height: 460px; }
.stage canvas { display: block; width: 100%; height: 100%; cursor: grab; }
.stage canvas:active { cursor: grabbing; }
.legend { position: absolute; left: 16px; bottom: 16px; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.legend-item { display: flex; align-items: center; gap: 8px; color: #9fb0c6; cursor: pointer; user-select: none; }
.legend-item.off { opacity: 0.32; text-decoration: line-through; }
.legend-item i { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.legend-item em { font-style: normal; color: #5d7189; }
.hint { position: absolute; right: 16px; bottom: 14px; color: #46586d; font-size: 12px; }
.empty-tip { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #5d7189; pointer-events: none; }
.empty-tip p { font-size: 16px; color: #93a3b8; margin: 0 0 6px; }
.detail { position: absolute; top: 14px; right: 14px; width: 280px; max-height: calc(100% - 28px); overflow: auto; background: rgba(10, 18, 28, 0.94); border: 1px solid #1b2c40; border-radius: 12px; padding: 14px; color: #cfe0f5; backdrop-filter: blur(6px); }
.detail header { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8b98ab; }
.detail header i { width: 8px; height: 8px; border-radius: 50%; }
.detail h3 { font-size: 15px; margin: 10px 0 6px; color: #eaf1fb; }
.detail .value { font-size: 13px; line-height: 1.7; color: #b9c8db; margin: 0 0 12px; white-space: pre-wrap; }
.detail .close { margin-left: auto; }
.strength { margin-bottom: 12px; }
.strength .bar { height: 4px; background: #16202e; border-radius: 4px; overflow: hidden; margin-bottom: 6px; }
.strength .bar i { display: block; height: 100%; border-radius: 4px; }
.strength span { font-size: 12px; color: #8b98ab; }
.detail dl { display: grid; grid-template-columns: 70px 1fr; gap: 6px 10px; font-size: 12px; margin: 0 0 12px; }
.detail dt { color: #5d7189; }
.detail dd { margin: 0; color: #b9c8db; }
.slide-enter-active, .slide-leave-active { transition: all 0.18s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateX(12px); }
</style>
