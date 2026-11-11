<template>
  <div class="relation-page">
    <div class="card head">
      <div>
        <h3 class="title">关系与心情</h3>
        <p class="hint">熟悉度按聊天总量和认识天数计算，会影响机器人的说话方式；心情会随时间衰减。</p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="!loading && !items.length" class="card empty">还没有聊天记录</div>

    <div class="grid">
      <div v-for="it in items" :key="it.qqId" class="card person">
        <div class="person-head">
          <div class="avatar">{{ (it.nickname || String(it.qqId)).slice(0, 1) }}</div>
          <div class="who">
            <div class="name">{{ it.nickname || ('QQ ' + it.qqId) }}</div>
            <div class="qq">QQ {{ it.qqId }}</div>
          </div>
          <el-tag :type="moodType(it.mood.label)" size="small" effect="dark" round>
            {{ it.mood.label }}
          </el-tag>
        </div>

        <div class="level-row">
          <el-tag :type="levelType(it.familiarity.level)" size="small" effect="plain">
            {{ it.familiarity.label }}
          </el-tag>
          <span class="muted">{{ it.familiarity.messageCount }} 条 · 认识 {{ it.familiarity.days }} 天</span>
        </div>

        <el-progress
          :percentage="it.familiarity.progress"
          :stroke-width="8"
          :show-text="false"
          :color="levelColor(it.familiarity.level)"
        />
        <div class="mood-bar">
          <span class="muted">心情</span>
          <el-progress
            :percentage="moodPercent(it.mood.value)"
            :stroke-width="8"
            :show-text="false"
            :color="moodColor(it.mood.value)"
          />
          <span class="muted num">{{ it.mood.value }}</span>
        </div>

        <p class="tone">{{ toneText(it) }}</p>
        <div class="foot muted">最后聊天：{{ it.lastMessageAt || '—' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'

interface Familiarity {
  level: number
  label: string
  messageCount: number
  days: number
  progress: number
  hint: string
}

interface Mood {
  value: number
  label: string
}

interface Row {
  qqId: number
  nickname?: string | null
  lastMessageAt?: string | null
  familiarity: Familiarity
  mood: Mood
}

const items = ref<Row[]>([])
const loading = ref(false)

function levelType(level: number) {
  return level >= 4 ? 'danger' : level === 3 ? 'warning' : level === 2 ? 'success' : 'info'
}

function levelColor(level: number) {
  return level >= 4 ? '#f56c6c' : level === 3 ? '#e6a23c' : level === 2 ? '#67c23a' : '#909399'
}

function moodType(label: string) {
  if (label === '很好') return 'success'
  if (label === '不错') return 'success'
  if (label === '一般') return 'info'
  if (label === '有点低落') return 'warning'
  return 'danger'
}

function moodColor(v: number) {
  return v >= 0.15 ? '#67c23a' : v <= -0.15 ? '#f56c6c' : '#909399'
}

function moodPercent(v: number) {
  return Math.round(((v + 1) / 2) * 100)
}

function toneText(it: Row) {
  const l = it.familiarity.level
  if (l === 1) return '语气偏客气，不会太自来熟'
  if (l === 2) return '可以开点小玩笑，但还有分寸'
  if (l === 3) return '会互怼、起外号，说话更短'
  return '很随意，会拆台也会直接关心'
}

async function load() {
  loading.value = true
  try {
    const r = await api<{ items: Row[] }>('/relation/overview')
    items.value = r.items ?? []
  } catch (e: any) {
    ElMessage.error(e?.message ?? '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.relation-page { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.title { margin: 0 0 6px; font-size: 16px; }
.hint { margin: 0; font-size: 12.5px; color: var(--c-text-2); line-height: 1.6; }
.empty { padding: 40px; text-align: center; color: var(--c-text-2); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.person { padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.person-head { display: flex; align-items: center; gap: 10px; }
.avatar {
  width: 38px; height: 38px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--c-bg); border: 1px solid var(--c-border);
  font-weight: 600;
}
.who { flex: 1; min-width: 0; }
.name { font-weight: 600; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qq { font-size: 12px; color: var(--c-text-2); }
.level-row { display: flex; align-items: center; gap: 8px; }
.muted { font-size: 12px; color: var(--c-text-2); }
.mood-bar { display: flex; align-items: center; gap: 8px; }
.mood-bar :deep(.el-progress) { flex: 1; }
.tone { margin: 2px 0 0; font-size: 13px; line-height: 1.6; }
.foot { font-size: 11.5px; }
</style>
