<template>
  <div class="logs-page">
    <div v-if="loading" class="card">
      <el-skeleton :rows="10" animated />
    </div>

    <template v-else-if="!error">
      <div class="card toolbar">
        <el-select v-model="source" style="width: 150px">
          <el-option label="机器人" value="bot" />
          <el-option label="图片预筛" value="guard" />
        </el-select>
        <el-select v-model="levelFilter" placeholder="日志级别" clearable style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="Info" value="info" />
          <el-option label="Warn" value="warn" />
          <el-option label="Error" value="error" />
          <el-option label="Debug" value="debug" />
        </el-select>
        <el-input-number v-model="tail" :min="50" :max="1000" :step="50" style="width: 160px" />
        <span class="toolbar-label">条</span>
        <el-button @click="loadData()">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <span v-if="pollError" class="poll-error">{{ pollError }}</span>
        <div class="toolbar-right">
          <el-switch v-model="autoRefresh" active-text="自动刷新" />
        </div>
      </div>

      <div class="card log-card">
        <EmptyState
          v-if="filteredLogs.length === 0"
          title="暂无日志"
          :desc="source === 'guard' ? '图片预筛还没有日志记录' : '机器人运行一段时间后这里会有记录'"
          :illustration="emptyLogs"
        />
        <div v-else class="log-list">
          <div v-for="(log, i) in filteredLogs" :key="i" class="log-row" :class="'level-' + log.level">
            <span class="log-time num">{{ formatTime(log.ts) }}</span>
            <el-tag :type="levelTag(log.level)" size="small" effect="plain" class="log-level">{{ log.level.toUpperCase() }}</el-tag>
            <span v-if="log.logger" class="log-logger" :title="log.logger">{{ log.logger }}</span>
            <span class="log-msg">{{ log.msg }}</span>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData()">重试</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'LogsView' })
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Refresh, Document, WarningFilled } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { normalizeLogs } from '@/api/adapters'
import type { LogLine } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import emptyLogs from '@/assets/illustrations/empty-logs.svg'

const logs = ref<LogLine[]>([])
const source = ref<'bot' | 'guard'>('bot')
const levelFilter = ref('')
const tail = ref(200)
const loading = ref(true)
const error = ref('')
const pollError = ref('')
const autoRefresh = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const filteredLogs = computed(() => {
  if (!levelFilter.value) return logs.value
  return logs.value.filter(l => l.level === levelFilter.value)
})

const levelTag = (level: string): 'info' | 'success' | 'warning' | 'danger' => {
  if (level === 'error') return 'danger'
  if (level === 'warn') return 'warning'
  return 'info'
}

function formatTime(ts: string): string {
  if (!ts) return '—'
  return ts.replace('T', ' ').slice(0, 19)
}

async function loadData(silent = false) {
  if (!silent) loading.value = true
  if (!silent) error.value = ''
  try {
    const data = await api<unknown[]>(`/logs?source=${source.value}&level=&tail=${tail.value}`)
    logs.value = normalizeLogs(data)
    pollError.value = ''
  } catch (e: any) {
    if (silent) {
      pollError.value = e?.message ?? '自动刷新失败'
    } else {
      error.value = e?.message ?? '未知错误'
    }
  } finally {
    if (!silent) loading.value = false
  }
}

watch(source, () => { void loadData() })

watch(autoRefresh, (v) => {
  if (v) {
    timer = setInterval(() => { void loadData(true) }, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
})

onMounted(() => loadData())
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.logs-page { display: flex; flex-direction: column; gap: 16px; }
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.toolbar-label { color: var(--c-text-2); font-size: 13px; }
.toolbar-right { margin-left: auto; }
.log-card { padding: 0; overflow: hidden; }
.log-list { max-height: 600px; overflow: auto; }
.log-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 10px 16px; border-bottom: 1px solid var(--c-border);
  font-size: 13px; font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}
.log-row:last-child { border-bottom: none; }
.log-row.level-error { background: rgba(201, 106, 99, 0.08); }
.log-row.level-warn { background: rgba(217, 164, 65, 0.08); }
.log-row.level-debug { opacity: 0.75; }
[data-theme="dark"] .log-row.level-error { background: rgba(201, 106, 99, 0.15); }
[data-theme="dark"] .log-row.level-warn { background: rgba(217, 164, 65, 0.12); }
.log-time { color: var(--c-text-2); flex-shrink: 0; width: 160px; font-size: 12px; }
.log-level { flex-shrink: 0; width: 52px; text-align: center; }
.log-logger {
  color: var(--c-text-2); flex-shrink: 0; width: 160px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 11px;
}
.log-msg { color: var(--c-text); line-height: 1.5; word-break: break-all; }
.poll-error { color: var(--c-warn); font-size: 12px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px; color: var(--c-text-2); }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: stretch; }
  .toolbar :deep(.el-select), .toolbar :deep(.el-input-number) { width: 100% !important; }
  .toolbar-right { margin-left: 0; margin-top: 8px; }
  .log-row { flex-wrap: wrap; gap: 6px; padding: 8px 12px; }
  .log-time { width: 100%; font-size: 11px; }
  .log-level { width: auto; }
  .log-logger { width: 100%; }
  .log-msg { width: 100%; font-size: 12px; }
}
</style>
