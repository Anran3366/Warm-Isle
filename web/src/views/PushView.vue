<template>
  <div class="push-page">
    <div v-if="loading" class="skeleton-block">
      <el-skeleton class="card" :rows="8" animated />
    </div>

    <template v-else-if="!error">
      <div class="card board-card">
        <div class="config-head">
          <h3 class="section-title">关系状态看板</h3>
          <span class="board-tip">每 5 秒自动刷新</span>
        </div>
        <div class="board-stats">
          <div class="board-stat">
            <span class="bs-label">聊过的人</span>
            <span class="bs-value num">{{ board.summary.users }}</span>
          </div>
          <div class="board-stat">
            <span class="bs-label">今日已发</span>
            <span class="bs-value num">{{ board.summary.todaySent }}<em>/{{ board.summary.dailyLimit }}</em></span>
          </div>
          <div class="board-stat">
            <span class="bs-label">等回复</span>
            <span class="bs-value num">{{ board.summary.waiting }}</span>
          </div>
          <div class="board-stat">
            <span class="bs-label">退避 / 免打扰</span>
            <span class="bs-value num">{{ board.summary.backoff }}</span>
          </div>
          <div class="board-stat">
            <span class="bs-label">平均回复率</span>
            <span class="bs-value num">{{ Math.round((board.summary.replyRate ?? 0) * 100) }}%</span>
          </div>
        </div>

        <el-table :data="board.users" size="small" style="width: 100%">
          <el-table-column prop="qqId" label="用户" width="140" />
          <el-table-column label="关系状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="modeType(row.mode)">{{ modeText(row.mode) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回复率" min-width="180">
            <template #default="{ row }">
              <el-progress :percentage="Math.round((row.replyRate ?? 0) * 100)"
                           :stroke-width="10" :color="rateColor(row.replyRate)" />
            </template>
          </el-table-column>
          <el-table-column label="未回复" width="80">
            <template #default="{ row }">{{ row.unanswered }}</template>
          </el-table-column>
          <el-table-column label="今日" width="80">
            <template #default="{ row }">{{ row.todayCount }}</template>
          </el-table-column>
          <el-table-column label="下次可发" min-width="150">
            <template #default="{ row }">{{ row.nextEligibleAt || '—' }}</template>
          </el-table-column>
          <el-table-column label="最近主动" min-width="150">
            <template #default="{ row }">{{ row.lastProactiveAt || '—' }}</template>
          </el-table-column>
          <el-table-column label="最近一条" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.lastContent || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!board.users.length" description="还没有聊过天的用户" :image-size="70" />
      </div>

      <div class="card config-card">
        <div class="config-head">
          <h3 class="section-title">推送配置</h3>
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" />
        </div>

        <el-alert
          v-if="form.state?.suspended"
          type="warning"
          :closable="false"
          show-icon
          class="state-alert"
        >
          <template #title>因未回复已停止主动消息</template>
          <div class="state-detail">
            已有 {{ form.state?.unanswered ?? 0 }} 条主动消息未获回复，用户回复后会自动恢复。
            <span v-if="form.state?.lastProactiveAt">
              最近发送：{{ formatRelativeTime(form.state.lastProactiveAt) }}
            </span>
          </div>
        </el-alert>

        <el-form :model="form" label-width="110px" class="push-form">
          <el-form-item label="早安时段">
            <div class="window-row">
              <el-switch v-model="form.morning.enabled" />
              <el-time-picker
                v-model="morningRange"
                is-range
                format="HH:mm"
                value-format="HH:mm"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                :disabled="!form.morning.enabled"
                style="width: 240px"
              />
            </div>
          </el-form-item>
          <el-form-item label="晚安时段">
            <div class="window-row">
              <el-switch v-model="form.evening.enabled" />
              <el-time-picker
                v-model="eveningRange"
                is-range
                format="HH:mm"
                value-format="HH:mm"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                :disabled="!form.evening.enabled"
                style="width: 240px"
              />
            </div>
          </el-form-item>
          <el-form-item label="空闲触发">
            <el-input-number v-model="form.idleHours" :min="1" :max="24" />
            <span class="form-suffix">小时无消息后触发</span>
          </el-form-item>
          <el-form-item label="追问间隔">
            <el-input-number v-model="form.nudgeAfterHours" :min="1" :max="24" />
            <span class="form-suffix">小时无回复后追问一次</span>
          </el-form-item>
          <el-form-item label="停止打扰">
            <el-input-number v-model="form.suspendAfterHours" :min="1" :max="48" />
            <span class="form-suffix">小时仍无回复则停手</span>
          </el-form-item>
          <el-form-item label="每日上限">
            <el-input-number v-model="form.dailyLimit" :min="1" :max="10" />
            <span class="form-suffix">条/天</span>
          </el-form-item>
          <el-form-item label="追问概率">
            <el-slider v-model="form.nudgeProbability" :min="0" :max="1" :step="0.05" style="max-width: 320px" />
            <span class="form-suffix">{{ Math.round((form.nudgeProbability ?? 0.4) * 100) }}%</span>
          </el-form-item>
          <el-form-item label="退避天数">
            <el-input-number v-model="form.backoffMinDays" :min="1" :max="10" />
            <span class="form-suffix">至</span>
            <el-input-number v-model="form.backoffMaxDays" :min="1" :max="14" />
            <span class="form-suffix">天（两次没回后）</span>
          </el-form-item>
          <el-form-item label="早安 / 晚安概率">
            <el-slider v-model="form.morningProbability" :min="0" :max="1" :step="0.05" style="max-width: 150px" />
            <el-slider v-model="form.eveningProbability" :min="0" :max="1" :step="0.05" style="max-width: 150px" />
          </el-form-item>
          <el-form-item label="免打扰">
            <el-time-picker
              v-model="quietRange"
              is-range
              format="HH:mm"
              value-format="HH:mm"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 240px"
            />
          </el-form-item>
          <el-form-item label="测试发送">
            <div class="test-row">
              <el-select v-model="testType" style="width: 150px">
                <el-option v-for="t in pushTypes" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
              <el-button :loading="testing" @click="testPush">立即发送</el-button>
            </div>
            <span class="form-hint">会直接发到你的 QQ，忽略静默时段</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
            <span v-if="isDirty" class="unsaved-hint">有未保存的修改</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="card">
        <div class="table-toolbar">
          <h3 class="section-title">发送历史</h3>
          <div class="history-tools">
            <el-select
              v-model="typeFilter"
              clearable
              placeholder="全部类型"
              size="small"
              style="width: 140px"
              @change="loadHistory"
            >
              <el-option v-for="t in pushTypes" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-button-group size="small" class="density-toggle">
              <el-button :type="density === 'standard' ? 'primary' : 'default'" @click="density = 'standard'">标准</el-button>
              <el-button :type="density === 'compact' ? 'primary' : 'default'" @click="density = 'compact'">紧凑</el-button>
            </el-button-group>
          </div>
        </div>
        <EmptyState
          v-if="history.length === 0"
          title="还没有主动消息记录"
          desc="配置好推送规则后，机器人会主动找你聊天"
          :illustration="emptyPush"
        />
        <el-table v-else :data="history" stripe :class="'density-' + density" max-height="400">
          <el-table-column label="类型" width="90" sortable sort-by="type">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="pushTypeTag(row.type)">{{ pushTypeLabel(row.type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
          <el-table-column label="发送时间" width="180" sortable sort-by="sentAt">
            <template #default="{ row }">
              <span class="muted num">{{ formatRelativeTime(row.sentAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData">重试</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'PushView' })
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import type { PushConfig, PushRecord, PushType, Page } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import emptyPush from '@/assets/illustrations/empty-push.svg'
import { formatRelativeTime } from '@/utils/format'
import { useUnsavedGuard } from '@/composables/useUnsavedGuard'

const pushTypes: Array<{ value: PushType; label: string }> = [
  { value: 'morning', label: '早安' },
  { value: 'evening', label: '晚安' },
  { value: 'silence', label: '沉默' },
  { value: 'followup', label: '跟进' },
  { value: 'nudge', label: '追问' }
]

const { isDirty, markDirty, markClean } = useUnsavedGuard()

function defaultConfig(): PushConfig {
  return {
    enabled: true,
    morning: { enabled: true, from: '08:30', to: '09:30' },
    evening: { enabled: true, from: '22:30', to: '23:25' },
    idleHours: 6,
    nudgeAfterHours: 6,
    suspendAfterHours: 12,
    dailyLimit: 3,
    quiet: { from: '23:30', to: '08:00' },
    nudgeProbability: 0.4,
    nudgeMinHours: 2,
    nudgeMaxHours: 5,
    backoffMinDays: 2,
    backoffMaxDays: 5,
    morningProbability: 0.75,
    eveningProbability: 0.75,
    lowCostChance: 0.4,
    maxBubbles: 2
  }
}

const form = ref<PushConfig>(defaultConfig())
const history = ref<PushRecord[]>([])
const loading = ref(true)

const board = ref<{
  summary: { users: number; todaySent: number; waiting: number; backoff: number; replyRate: number; dailyLimit: number }
  users: Array<Record<string, any>>
}>({
  summary: { users: 0, todaySent: 0, waiting: 0, backoff: 0, replyRate: 0, dailyLimit: 3 },
  users: []
})
let boardTimer: number | undefined

function modeText(mode: string) {
  const map: Record<string, string> = {
    CALM: '平稳', MISS: '想找TA', PING: '等回复', SULK: '有点小情绪',
    BACKOFF: '退让中', REENTRY: '重新出现', DND: '免打扰'
  }
  return map[mode] ?? mode
}

function modeType(mode: string): "info" | "primary" | "success" | "warning" | "danger" {
  const map: Record<string, "info" | "primary" | "success" | "warning" | "danger"> = {
    CALM: 'info', MISS: 'primary', PING: 'warning', SULK: 'danger',
    BACKOFF: 'info', REENTRY: 'success', DND: 'danger'
  }
  return map[mode] ?? 'info'
}

function rateColor(rate: number) {
  if (rate >= 0.6) return '#67c23a'
  if (rate >= 0.25) return '#e6a23c'
  return '#f56c6c'
}

async function loadBoard() {
  try {
    board.value = await api('/push/dashboard')
  } catch { /* 看板失败不阻塞页面 */ }
}
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const density = ref<'standard' | 'compact'>('standard')
const typeFilter = ref<PushType | ''>('')
const testType = ref<PushType>('morning')
let suppressDirty = false

watch(form, () => {
  if (!suppressDirty) markDirty()
}, { deep: true })

const morningRange = computed({
  get: () => [form.value.morning.from, form.value.morning.to],
  set: (v: string[]) => { form.value.morning.from = v[0]; form.value.morning.to = v[1] }
})
const eveningRange = computed({
  get: () => [form.value.evening.from, form.value.evening.to],
  set: (v: string[]) => { form.value.evening.from = v[0]; form.value.evening.to = v[1] }
})
const quietRange = computed({
  get: () => [form.value.quiet.from, form.value.quiet.to],
  set: (v: string[]) => { form.value.quiet.from = v[0]; form.value.quiet.to = v[1] }
})

function pushTypeLabel(t: string): string {
  return pushTypes.find(x => x.value === t)?.label ?? t
}

function pushTypeTag(t: string): 'info' | 'success' | 'warning' | 'danger' {
  return ({ morning: 'warning', evening: 'info', silence: 'info', followup: 'success', nudge: 'danger' } as Record<string, 'info' | 'success' | 'warning' | 'danger'>)[t] ?? 'info'
}

function normalizeRecord(raw: unknown): PushRecord {
  const value = (raw ?? {}) as Record<string, any>
  const type = pushTypes.some(t => t.value === value.type) ? value.type as PushType : 'followup'
  const sentAt = String(value.sentAt ?? '')
  return {
    id: String(value.id ?? ''),
    type,
    content: String(value.content ?? ''),
    sentAt: sentAt.includes('T') ? sentAt : sentAt.replace(' ', 'T')
  }
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [cfg, page] = await Promise.all([
      api<PushConfig>('/push/config'),
      api<Page<unknown>>('/push/history?page=1&size=50')
    ])
    form.value = { ...cfg, state: cfg.state }
    history.value = page.items.map(normalizeRecord)
    suppressDirty = true
    await nextTick()
    suppressDirty = false
    markClean()
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  try {
    const type = typeFilter.value ? `&type=${typeFilter.value}` : ''
    const page = await api<Page<unknown>>(`/push/history?page=1&size=50${type}`)
    history.value = page.items.map(normalizeRecord)
  } catch (e: any) {
    ElMessage.error(e?.message ?? '历史加载失败')
  }
}

async function save() {
  saving.value = true
  try {
    const { state, ...payload } = form.value
    const saved = await api<PushConfig>('/push/config', { method: 'PUT', body: payload })
    form.value = { ...saved, state: saved.state ?? state }
    suppressDirty = true
    await nextTick()
    suppressDirty = false
    markClean()
    ElMessage.success('配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function testPush() {
  testing.value = true
  try {
    const res = await api<{ ok: boolean; content?: string | null; sentTo?: number }>('/push/test', {
      method: 'POST',
      body: { type: testType.value }
    })
    if (res.ok && res.content) {
      ElMessage.success(`已发送：${res.content}`)
    } else {
      ElMessage.warning('没有发出消息，检查主动消息开关或 NapCat 连接')
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? '发送失败')
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  loadData()
  loadBoard()
  boardTimer = window.setInterval(loadBoard, 5000)
})

onBeforeUnmount(() => {
  if (boardTimer) window.clearInterval(boardTimer)
  boardTimer = undefined
})
</script>

<style scoped>
.board-card { margin-bottom: 16px; }
.board-tip { font-size: 12px; color: var(--c-text-2); }
.board-stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; margin: 12px 0 16px; }
.board-stat { display: flex; flex-direction: column; gap: 4px; padding: 12px; border-radius: 10px; background: var(--c-bg-soft, #f5f7fb); }
.bs-label { font-size: 12px; color: var(--c-text-2); }
.bs-value { font-size: 22px; font-weight: 600; line-height: 1.1; }
.bs-value em { font-size: 12px; font-style: normal; color: var(--c-text-2); margin-left: 2px; }
@media (max-width: 900px) { .board-stats { grid-template-columns: repeat(2, 1fr); } }
.push-page { display: flex; flex-direction: column; gap: 16px; }
.config-card { max-width: 680px; }
.config-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.section-title { margin: 0; font-size: 15px; font-weight: 600; }
.state-alert { margin-bottom: 16px; }
.state-detail { font-size: 12.5px; line-height: 1.6; margin-top: 4px; }
.state-detail span { margin-left: 8px; color: var(--c-text-2); }
.table-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
.history-tools { display: flex; align-items: center; gap: 8px; }
.density-toggle { flex-shrink: 0; }
.density-standard :deep(.el-table__row) { height: 48px; }
.density-compact :deep(.el-table__row) { height: 34px; }
.density-compact :deep(.el-table__cell) { padding: 4px 0; }
.push-form { display: flex; flex-direction: column; gap: 4px; }
.window-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.test-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.form-suffix { margin-left: 8px; color: var(--c-text-2); font-size: 12px; }
.form-hint { margin-left: 10px; color: var(--c-text-2); font-size: 12px; }
.unsaved-hint { margin-left: 12px; color: var(--c-warn); font-size: 12px; }
.muted { color: var(--c-text-2); font-size: 12px; }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .config-card { max-width: 100%; }
  .push-form :deep(.el-form-item) { flex-direction: column; align-items: flex-start; }
  .push-form :deep(.el-form-item__label) { width: auto !important; margin-bottom: 4px; }
  .push-form :deep(.el-input-number), .push-form :deep(.el-time-picker), .push-form :deep(.el-select) { width: 100% !important; }
  .push-form :deep(.el-button) { width: 100%; }
  .window-row { width: 100%; }
  .window-row .el-time-picker { flex: 1; }
  .test-row { width: 100%; }
  .history-tools { width: 100%; }
}
</style>
