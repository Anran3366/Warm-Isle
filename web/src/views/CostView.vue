<template>
  <div class="cost-page">
    <div class="card head-card">
      <div>
        <h3 class="title">成本</h3>
        <p class="hint">按用户统计模型调用花费，只统计接入用户维度之后的数据。</p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="stat-row">
      <div class="card stat">
        <span class="stat-label">今日花费</span>
        <span class="stat-value">¥{{ todayCost.toFixed(4) }}</span>
      </div>
      <div class="card stat">
        <span class="stat-label">今日 Tokens</span>
        <span class="stat-value">{{ todayTokens }}</span>
      </div>
      <div class="card stat">
        <span class="stat-label">统计用户</span>
        <span class="stat-value">{{ rows.length }}</span>
      </div>
    </div>

    <div class="card">
      <h3 class="section-title">用户花费排行</h3>
      <el-table :data="rows" size="small" max-height="420">
        <el-table-column prop="qqId" label="QQ" width="150" />
        <el-table-column prop="nickname" label="昵称" width="160" />
        <el-table-column prop="cost" label="花费（元）" width="120" />
        <el-table-column prop="tokens" label="Tokens" width="130" />
        <el-table-column prop="calls" label="调用次数" width="110" />
      </el-table>
      <div v-if="!loading && !rows.length" class="empty">还没有带用户维度的用量数据</div>
    </div>

    <div class="card">
      <h3 class="section-title">近 7 天</h3>
      <el-table :data="daily" size="small">
        <el-table-column prop="date" label="日期" width="140" />
        <el-table-column prop="cost" label="花费（元）" width="140" />
        <el-table-column prop="tokens" label="Tokens" />
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'

interface Row {
  qqId: number
  nickname?: string
  cost: number
  tokens: number
  calls: number
}

const todayCost = ref(0)
const todayTokens = ref(0)
const rows = ref<Row[]>([])
const daily = ref<Array<{ date: string; cost: number; tokens: number }>>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await api<{
      todayCost: number
      todayTokens: number
      daily: Array<{ date: string; cost: number; tokens: number }>
      perUser: Row[]
    }>('/cost?days=7')
    todayCost.value = Number(r.todayCost ?? 0)
    todayTokens.value = Number(r.todayTokens ?? 0)
    daily.value = r.daily ?? []
    const nicknames: Record<string, string> = {}
    try {
      const facts = await api<{ items: Array<{ qqId: string; nickname?: string }> }>('/facts?all=true')
      for (const f of facts.items ?? []) {
        if (f.nickname) nicknames[String(f.qqId)] = f.nickname
      }
    } catch {
      // 昵称拿不到就只显示 QQ
    }
    rows.value = (r.perUser ?? []).map((x) => ({
      ...x,
      nickname: nicknames[String(x.qqId)] ?? ''
    }))
  } catch (e: any) {
    ElMessage.error(e?.message ?? '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.cost-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px;
}
.title {
  margin: 0 0 6px;
  font-size: 16px;
}
.hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--c-text-2);
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
}
.stat-label {
  font-size: 12.5px;
  color: var(--c-text-2);
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
}
.empty {
  padding: 24px;
  text-align: center;
  color: var(--c-text-2);
  font-size: 13px;
}
@media (max-width: 640px) {
  .stat-row {
    grid-template-columns: 1fr;
  }
}
</style>
