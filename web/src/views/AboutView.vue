<template>
  <div class="about-page">
    <div class="card about-card">
      <div class="about-header">
        <div class="about-logo">
          <span class="brand-dot" />
          <span class="brand-name">暖屿 · 小满</span>
        </div>
        <el-tag type="info" effect="plain" round>v{{ version }}</el-tag>
      </div>
      <p class="about-desc">一个温暖的 AI 伴侣，记住你说的每一件事。</p>

      <el-divider />

      <div class="info-grid">
        <div class="info-item">
          <span class="info-label">版本号</span>
          <span class="info-value num">{{ version }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">构建时间</span>
          <span class="info-value num">{{ buildTime }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">仓库地址</span>
          <span class="info-value">
            <a :href="repoUrl" target="_blank" rel="noopener">{{ repoUrl }}</a>
            <span style="margin-left:8px">
              <a href="https://github.com/Anran3366/Warm-Isle" target="_blank" rel="noopener">GitHub</a>
            </span>
          </span>
        </div>
        <div class="info-item">
          <span class="info-label">运行环境</span>
          <span class="info-value">{{ env }}</span>
        </div>
      </div>

      <el-divider />

      <div class="tech-stack">
        <h4>技术栈</h4>
        <div class="tech-tags">
          <el-tag v-for="t in techStack" :key="t" effect="plain" round>{{ t }}</el-tag>
        </div>
      </div>
    </div>

    <div class="card about-card sys-card" v-if="system">
      <h3 class="sys-title">系统状态</h3>
      <div class="sys-row">
        <span class="sys-label">内存</span>
        <div class="sys-bar"><div class="sys-bar-inner" :style="{ width: (system.memory.percent || 0) + '%' }" /></div>
        <span class="sys-value num">{{ system.memory.usedMb }} / {{ system.memory.totalMb }} MB（{{ system.memory.percent }}%）</span>
      </div>
      <div class="sys-row">
        <span class="sys-label">JVM</span>
        <span class="sys-value num">{{ system.jvm.usedMb }} / {{ system.jvm.maxMb }} MB</span>
        <span class="sys-label">运行时长</span>
        <span class="sys-value num">{{ formatUptime(system.uptimeSec) }}</span>
      </div>
      <div class="sys-row">
        <span class="sys-label">系统</span>
        <span class="sys-value">{{ system.os }} · Java {{ system.java }}</span>
        <span class="sys-label">记忆</span>
        <span class="sys-value num">{{ status?.memory.facts ?? 0 }} 条</span>
      </div>

      <h4 class="sys-sub">容器内存（{{ containers.length }}）</h4>
      <el-table :data="containers" size="small" max-height="240">
        <el-table-column prop="name" label="容器" width="150" />
        <el-table-column prop="state" label="状态" width="90" />
        <el-table-column prop="memUsedMb" label="内存 MB" width="100" />
        <el-table-column prop="memPercent" label="占用 %" width="90" />
      </el-table>
      <p class="sys-note">后端进程：{{ containerJvm.usedMb }} / {{ containerJvm.maxMb }} MB</p>

      <h4 class="sys-sub">已开启实例（{{ system.onlineCount }} / {{ system.instances.length }} 在线）</h4>
      <div class="inst-list">
        <div v-for="i in system.instances" :key="i.botId" class="inst-item">
          <span class="dot" :class="{ on: i.online }" />
          <span>账号 {{ i.botId }}</span>
          <span class="num">QQ {{ i.qq }}</span>
          <el-tag size="small" :type="i.online ? 'success' : 'info'">{{ i.online ? '在线' : '离线' }}</el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'AboutView' })
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api/client'
import { normalizeStatus } from '@/api/adapters'
import type { StatusResp } from '@/api/types'

const version = __APP_VERSION__
const buildTime = __BUILD_TIME__
const repoUrl = 'https://gitee.com/menran/warm_isle'
const env = import.meta.env.PROD ? 'production' : 'development'

const techStack = [
  'Vue 3', 'TypeScript', 'Element Plus', 'Vite',
  'ECharts', 'Vue Router', 'PWA'
]

const status = ref<StatusResp | null>(null)
const system = ref<any>(null)
const statusError = ref(false)
const containers = ref<Array<{ name: string; state: string; memUsedMb?: number; memPercent?: number }>>([])
const containerJvm = ref({ usedMb: 0, maxMb: 0 })

function formatUptime(sec: number): string {
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  if (d > 0) return `${d} 天 ${h} 小时`
  const m = Math.floor((sec % 3600) / 60)
  return `${h} 小时 ${m} 分`
}

async function loadStatus() {
  statusError.value = false
  try {
    status.value = normalizeStatus(await api<unknown>('/status'))
  } catch {
    statusError.value = true
  }
}

async function loadContainers() {
  try {
    const r = await api<{ items: typeof containers.value; jvm: { usedMb: number; maxMb: number } }>('/system/containers')
    containers.value = r.items ?? []
    containerJvm.value = r.jvm ?? { usedMb: 0, maxMb: 0 }
  } catch {
    // 忽略：没有 docker socket 时不影响页面
  }
}

let timer: number | undefined

onMounted(() => {
  loadStatus()
  loadContainers()
  loadSystem()
  // 每 5 秒刷新一次系统状态
  timer = window.setInterval(() => {
    loadStatus()
    loadContainers()
    loadSystem()
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  timer = undefined
})

async function loadSystem() {
  try { system.value = await api('/system') } catch { /* 忽略 */ }
}
</script>

<style scoped>
.sys-note { margin: 8px 0 0; font-size: 12px; color: var(--c-text-2); }
.about-page { display: flex; flex-direction: column; gap: 16px; max-width: 600px; }
.about-card { text-align: center; }
.about-header {
  display: flex; align-items: center; justify-content: center; gap: 12px;
}
.about-logo { display: flex; align-items: center; gap: 8px; }
.brand-dot {
  width: 14px; height: 14px; border-radius: 50%;
  background: var(--c-primary);
}
.brand-name { font-size: 20px; font-weight: 600; }
.about-desc {
  color: var(--c-text-2); font-size: 14px; margin: 12px 0 0;
}
.section-title { margin: 0 0 16px; font-size: 15px; font-weight: 600; }
.info-grid {
  display: grid; grid-template-columns: auto 1fr;
  gap: 12px 24px; text-align: left;
}
.info-item {
  display: contents;
}
.info-label {
  color: var(--c-text-2); font-size: 13px;
}
.info-value {
  font-size: 13px; color: var(--c-text);
}
.info-value a {
  color: var(--c-primary); text-decoration: none;
}
.info-value a:hover {
  text-decoration: underline;
}
.tech-stack { text-align: left; }
.tech-stack h4 {
  margin: 0 0 12px; font-size: 14px; font-weight: 600;
}
.tech-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.status-card { text-align: left; }
.loading-status { padding: 12px 0; }
.status-error {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 0; color: var(--c-text-2); font-size: 13px;
}

:deep(.el-divider) {
  margin: 20px 0;
}

@media (max-width: 768px) {
  .info-grid { grid-template-columns: 1fr; gap: 8px; }
  .info-item {
    display: flex; justify-content: space-between; align-items: center;
    padding: 8px 0; border-bottom: 1px solid var(--c-border);
  }
  .info-item:last-child { border-bottom: none; }
}
.sys-card { margin-top: 16px; }
.sys-title { margin: 0 0 14px; font-size: 15px; }
.sys-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; font-size: 13px; }
.sys-label { color: var(--c-text-2); min-width: 56px; }
.sys-value { color: var(--c-text); }
.sys-bar { flex: 1; height: 8px; border-radius: 4px; background: var(--c-bg-soft, #f0f2f7); overflow: hidden; }
.sys-bar-inner { height: 100%; background: linear-gradient(90deg, #6d8bff, #b45bff); }
.sys-sub { margin: 16px 0 10px; font-size: 13px; color: var(--c-text-2); }
.inst-list { display: flex; flex-direction: column; gap: 8px; }
.inst-item { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #c9ced8; }
.dot.on { background: #34d399; }
</style>
