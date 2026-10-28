<template>
  <div class="memory-page">
    <div v-if="loading" class="skeleton-block">
      <el-skeleton class="card" :rows="10" animated />
    </div>

    <template v-else-if="!error">
      <div class="card toolbar">
        <el-input
          v-model="search"
          placeholder="搜索事实…"
          clearable
          class="search-input"
          :prefix-icon="Search"
        />
        <div class="toolbar-right">
          <el-dropdown trigger="click" @command="handleExport">
            <el-button size="small">
              <el-icon><Download /></el-icon>导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="facts-csv">事实卡 CSV</el-dropdown-item>
                <el-dropdown-item command="facts-json">事实卡 JSON</el-dropdown-item>
                <el-dropdown-item divided command="summaries-csv">摘要 CSV</el-dropdown-item>
                <el-dropdown-item command="summaries-json">摘要 JSON</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="timeline">时间线</el-radio-button>
          </el-radio-group>
          <el-button type="primary" :loading="extracting" @click="extract">
            <el-icon><MagicStick /></el-icon>抽取记忆
          </el-button>
        </div>
      </div>

      <div v-if="selectedIds.size > 0" class="card batch-bar">
        <span>已选 <b>{{ selectedIds.size }}</b> 项</span>
        <el-button type="danger" size="small" @click="batchDelete">批量删除</el-button>
        <el-button size="small" @click="selectedIds.clear()">取消选择</el-button>
      </div>

      <div class="card">
        <div class="table-toolbar">
          <el-tabs v-model="activeCategory" class="fact-tabs">
            <el-tab-pane label="全部" name="all" />
            <el-tab-pane v-for="c in categories" :key="c" :label="categoryLabel(c)" :name="c" />
          </el-tabs>
          <el-button-group size="small" class="density-toggle">
            <el-button :type="density === 'standard' ? 'primary' : 'default'" @click="density = 'standard'">标准</el-button>
            <el-button :type="density === 'compact' ? 'primary' : 'default'" @click="density = 'compact'">紧凑</el-button>
          </el-button-group>
        </div>

        <EmptyState
          v-if="filteredFacts.length === 0"
          title="还没有记忆"
          desc="聊几句她就会记住你了"
          :illustration="emptyMemory"
        />

        <!-- Table view -->
        <el-table v-else-if="viewMode === 'table'" :data="filteredFacts" stripe class="fact-table" :class="'density-' + density" max-height="520" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="42" />
          <el-table-column label="用户" width="140">
            <template #default="{ row }">
              <span class="qq-cell">{{ (row as Fact).qqId || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="昵称" width="140">
            <template #default="{ row }">
              <span>{{ (row as Fact).nickname || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类别" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="categoryTagType((row as Fact).category)">
                {{ categoryLabel((row as Fact).category) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="key" label="键" width="120" sortable />
          <el-table-column prop="value" label="值" min-width="200">
            <template #default="{ row }">
              <div class="value-cell">
                <span class="value-text">{{ (row as Fact).value }}</span>
                <el-button
                  text size="small" class="copy-btn"
                  :aria-label="'复制' + (row as Fact).key"
                  @click="doCopy((row as Fact).value)"
                ><el-icon><CopyDocument /></el-icon></el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="90" align="center" sortable sort-by="confidence">
            <template #default="{ row }">
              <span class="num">{{ ((row as Fact).confidence * 100).toFixed(0) }}%</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center" sortable sort-by="status">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType((row as Fact).status)" effect="plain" round>
                {{ statusLabel((row as Fact).status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后确认" width="120" sortable sort-by="lastConfirmed">
            <template #default="{ row }">
              <span class="muted">{{ (row as Fact).lastConfirmed ? formatRelativeTime((row as Fact).lastConfirmed!) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button text size="small" type="primary" @click="editFact(row as Fact)">编辑</el-button>
              <el-button text size="small" type="danger" @click="deleteFact(row as Fact)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- Timeline view -->
        <div v-else class="timeline-view">
          <div v-for="group in timelineGroups" :key="group.label" class="timeline-group">
            <div class="timeline-date">
              <span class="timeline-dot" />
              <span class="timeline-label">{{ group.label }}</span>
              <el-tag size="small" type="info" effect="plain" round>{{ group.facts.length }}</el-tag>
            </div>
            <div class="timeline-items">
              <div v-for="f in group.facts" :key="f.id" class="timeline-card" @click="editFact(f)">
                <el-tag size="small" effect="plain" :type="categoryTagType(f.category)">{{ categoryLabel(f.category) }}</el-tag>
                <span class="tl-key">{{ f.key }}</span>
                <span class="tl-value">{{ f.value }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="pending.length > 0" class="card">
        <div class="pending-head">
          <h3 class="section-title">待确认 <el-tag type="warning" size="small" round>{{ pending.length }}</el-tag></h3>
          <el-button size="small" :loading="reviewing" @click="reviewPending">让机器人审核</el-button>
        </div>
        <div class="pending-list">
          <div v-for="f in pending" :key="f.id" class="pending-item">
            <div class="pending-content">
              <span class="pending-user">QQ {{ f.qqId || '—' }}<span v-if="f.nickname">（{{ f.nickname }}）</span></span>
              <el-tag size="small" effect="plain">{{ categoryLabel(f.category) }}</el-tag>
              <span class="pending-key">{{ f.key }}</span>
              <span class="pending-value">{{ f.value }}</span>
            </div>
            <div class="pending-actions">
              <el-button size="small" type="success" @click="confirmFact(f)">确认</el-button>
              <el-button size="small" type="danger" @click="rejectFact(f)">拒绝</el-button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="summaries.length > 0" class="card">
        <h3 class="section-title">对话摘要</h3>
        <div class="summary-list">
          <div v-for="s in summaries" :key="s.date" class="summary-item">
            <span class="summary-date num">{{ s.date.slice(5) }}</span>
            <span class="summary-text">{{ s.text }}</span>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="card error-card">
      <el-icon :size="40" color="var(--c-danger)"><WarningFilled /></el-icon>
      <p>加载失败：{{ error }}</p>
      <el-button type="primary" @click="loadData">重试</el-button>
    </div>

    <el-dialog v-model="showEdit" title="编辑事实" width="440px">
      <el-form v-if="editForm" label-width="80px">
        <el-form-item label="类别">
          <el-select v-model="editForm.category" style="width: 100%">
            <el-option v-for="c in categories" :key="c" :label="categoryLabel(c)" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="键">
          <el-input v-model="editForm.key" />
        </el-form-item>
        <el-form-item label="值">
          <el-input v-model="editForm.value" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="置信度">
          <el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-stops />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveFact">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'MemoryView' })
import { ref, computed, onMounted } from 'vue'
import { Search, MagicStick, Collection, WarningFilled, CopyDocument, Download, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { Fact, FactCategory, FactStatus, Page, Summary } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import emptyMemory from '@/assets/illustrations/empty-memory.svg'
import { formatRelativeTime, copyToClipboard } from '@/utils/format'
import { exportCsv, exportJson } from '@/utils/export'

const categories: FactCategory[] = ['profile', 'preference', 'relationship', 'event', 'commitment']
const categoryLabel = (c: string) => ({
  profile: '档案', preference: '偏好', relationship: '关系', event: '事件', commitment: '约定'
}[c] ?? c)
type TagType = 'info' | 'success' | 'warning' | 'danger' | undefined
const categoryTagType = (c: string): TagType => ({
  profile: 'info', preference: 'success', relationship: 'warning', event: 'info', commitment: 'danger'
} as Record<string, TagType>)[c] ?? 'info'
const statusLabel = (s: string) => ({
  active: '活跃', outdated: '过时', disputed: '存疑', pending: '待确认'
}[s] ?? s)
const statusTagType = (s: string): TagType => ({
  active: 'success', outdated: 'info', disputed: 'warning', pending: 'warning'
} as Record<string, TagType>)[s] ?? 'info'

const facts = ref<Fact[]>([])
const pending = ref<Fact[]>([])
const summaries = ref<Summary[]>([])
const search = ref('')
const activeCategory = ref<string>('all')
const viewMode = ref<'table' | 'timeline'>('table')
const density = ref<'standard' | 'compact'>('standard')
const selectedIds = ref<Set<string>>(new Set())
const loading = ref(true)
const error = ref('')
const extracting = ref(false)
const showEdit = ref(false)
const saving = ref(false)
const editForm = ref<{ id: string; category: FactCategory; key: string; value: string; confidence: number } | null>(null)

const filteredFacts = computed(() => {
  let list = facts.value
  if (activeCategory.value !== 'all') {
    list = list.filter(f => f.category === activeCategory.value)
  }
  if (search.value.trim()) {
    const q = search.value.trim().toLowerCase()
    list = list.filter(f => f.key.toLowerCase().includes(q) || f.value.toLowerCase().includes(q))
  }
  return list
})

interface TimelineGroup { label: string; facts: Fact[] }

const timelineGroups = computed<TimelineGroup[]>(() => {
  const groups: Record<string, Fact[]> = {}
  for (const f of filteredFacts.value) {
    const date = f.firstSeen || '未知'
    const label = formatRelativeTime(date)
    if (!groups[label]) groups[label] = []
    groups[label].push(f)
  }
  return Object.entries(groups)
    .map(([label, facts]) => ({ label, facts }))
    .sort((a, b) => {
      const da = a.facts[0]?.firstSeen ?? ''
      const db = b.facts[0]?.firstSeen ?? ''
      return db.localeCompare(da)
    })
})

function onSelectionChange(rows: Fact[]) {
  selectedIds.value = new Set(rows.map(r => r.id))
}

async function batchDelete() {
  if (selectedIds.value.size === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.size} 条记忆？`, '批量删除', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    const ids = [...selectedIds.value]
    await Promise.all(ids.map(id => api(`/facts/${id}`, { method: 'DELETE' })))
    facts.value = facts.value.filter(f => !ids.includes(f.id))
    selectedIds.value.clear()
    ElMessage.success(`已删除 ${ids.length} 条`)
  } catch { /* cancelled */ }
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [factsPage, pendingPage, sums] = await Promise.all([
      api<Page<Fact>>('/facts?page=1&size=200&status=active&all=true'),
      api<{ items: Fact[] }>('/facts/pending'),
      api<Summary[]>('/summaries?from=&to=')
    ])
    facts.value = factsPage.items
    pending.value = pendingPage.items
    summaries.value = sums
  } catch (e: any) {
    error.value = e?.message ?? '未知错误'
  } finally {
    loading.value = false
  }
}

function editFact(f: Fact) {
  editForm.value = { id: f.id, category: f.category, key: f.key, value: f.value, confidence: f.confidence }
  showEdit.value = true
}

async function saveFact() {
  if (!editForm.value) return
  saving.value = true
  try {
    await api(`/facts/${editForm.value.id}`, {
      method: 'PUT',
      body: editForm.value
    })
    const idx = facts.value.findIndex(f => f.id === editForm.value!.id)
    if (idx >= 0) {
      facts.value[idx] = { ...facts.value[idx], ...editForm.value }
    }
    showEdit.value = false
    ElMessage.success('已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function deleteFact(f: Fact) {
  try {
    await ElMessageBox.confirm(`确定删除「${f.key}」？`, '删除确认', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await api(`/facts/${f.id}`, { method: 'DELETE' })
    facts.value = facts.value.filter(x => x.id !== f.id)
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

async function confirmFact(f: Fact) {
  try {
    await api(`/facts/${f.id}/confirm`, { method: 'POST' })
    pending.value = pending.value.filter(x => x.id !== f.id)
    const idx = facts.value.findIndex(x => x.id === f.id)
    if (idx >= 0) facts.value[idx].status = 'active'
    ElMessage.success('已确认')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '操作失败')
  }
}

async function rejectFact(f: Fact) {
  try {
    await api(`/facts/${f.id}/reject`, { method: 'POST' })
    pending.value = pending.value.filter(x => x.id !== f.id)
    ElMessage.success('已拒绝')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '操作失败')
  }
}

const reviewing = ref(false)

async function reviewPending() {
  reviewing.value = true
  try {
    const r = await api<{ ok: boolean; approved?: number; rejected?: number; kept?: number; message?: string }>(
      '/facts/review-pending',
      { method: 'POST' }
    )
    if (!r.ok) {
      ElMessage.error(r.message ?? '审核失败')
      return
    }
    ElMessage.success(`审核完成：通过 ${r.approved ?? 0} 条，拒绝 ${r.rejected ?? 0} 条，保留 ${r.kept ?? 0} 条`)
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '审核失败')
  } finally {
    reviewing.value = false
  }
}

async function extract() {
  extracting.value = true
  try {
    const res = await api<{ written?: number; users?: number }>('/memory/extract', { method: 'POST' })
    const written = typeof res.written === 'number' ? res.written : 0
    const users = typeof res.users === 'number' ? res.users : 0
    ElMessage.success(`已抽取 ${users} 个用户，共更新 ${written} 条记忆`)
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '抽取失败')
  } finally {
    extracting.value = false
  }
}

async function doCopy(text: string) {
  const ok = await copyToClipboard(text)
  ElMessage[ok ? 'success' : 'error'](ok ? '已复制' : '复制失败')
}

function handleExport(cmd: string) {
  const date = new Date().toISOString().slice(0, 10)
  if (cmd === 'facts-csv') {
    exportCsv(`facts-${date}.csv`,
      ['类别', '键', '值', '置信度', '状态', '首次发现', '最后确认'],
      facts.value.map(f => [f.category, f.key, f.value, f.confidence, f.status, f.firstSeen, f.lastConfirmed])
    )
    ElMessage.success('已导出事实卡 CSV')
  } else if (cmd === 'facts-json') {
    exportJson(`facts-${date}.json`, facts.value)
    ElMessage.success('已导出事实卡 JSON')
  } else if (cmd === 'summaries-csv') {
    exportCsv(`summaries-${date}.csv`,
      ['日期', '摘要'],
      summaries.value.map(s => [s.date, s.text])
    )
    ElMessage.success('已导出摘要 CSV')
  } else if (cmd === 'summaries-json') {
    exportJson(`summaries-${date}.json`, summaries.value)
    ElMessage.success('已导出摘要 JSON')
  }
}

onMounted(() => loadData())
</script>

<style scoped>
.memory-page { display: flex; flex-direction: column; gap: 16px; }
.skeleton-block { }
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.search-input { max-width: 320px; }
.toolbar-right { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.batch-bar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; background: var(--c-primary-weak); }
.section-title { margin: 0 0 12px; font-size: 15px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.muted { color: var(--c-text-2); font-size: 12px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 16px; color: var(--c-text-2); font-size: 13px; }
.fact-tabs :deep(.el-tabs__header) { margin-bottom: 0; flex: 1; }
.table-toolbar { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; gap: 12px; }
.density-toggle { flex-shrink: 0; margin-top: 4px; }
.fact-table { width: 100%; }
.fact-table.density-standard :deep(.el-table__row) { height: 48px; }
.fact-table.density-compact :deep(.el-table__row) { height: 34px; }
.fact-table.density-compact :deep(.el-table__cell) { padding: 4px 0; }
.pending-list { display: flex; flex-direction: column; gap: 10px; }
.pending-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px; background: var(--c-bg); border-radius: 10px;
}
.pending-content { display: flex; align-items: center; gap: 10px; }
.pending-key { font-weight: 600; font-size: 14px; }
.pending-value { color: var(--c-text-2); font-size: 13px; }
.pending-actions { display: flex; gap: 6px; }
.summary-list { display: flex; flex-direction: column; gap: 10px; }
.summary-item { display: flex; gap: 12px; align-items: flex-start; padding: 8px 0; border-bottom: 1px solid var(--c-border); }
.summary-item:last-child { border-bottom: none; }
.summary-date { color: var(--c-primary); font-weight: 600; font-size: 13px; flex-shrink: 0; width: 48px; }
.summary-text { font-size: 13px; line-height: 1.6; color: var(--c-text); }
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }
.value-cell { display: flex; align-items: center; gap: 4px; }
.value-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.copy-btn { opacity: 0; transition: opacity 0.15s; flex-shrink: 0; }
.fact-table tr:hover .copy-btn { opacity: 1; }

/* Timeline view */
.timeline-view { display: flex; flex-direction: column; gap: 20px; }
.timeline-group { position: relative; padding-left: 24px; }
.timeline-group::before {
  content: ''; position: absolute; left: 5px; top: 18px; bottom: 0;
  width: 2px; background: var(--c-border);
}
.timeline-date {
  display: flex; align-items: center; gap: 8px; margin-bottom: 10px; position: relative;
}
.timeline-dot {
  position: absolute; left: -24px; top: 50%; transform: translate(-50%, -50%);
  width: 12px; height: 12px; border-radius: 50%; background: var(--c-primary);
  border: 2px solid var(--c-surface); z-index: 1;
}
.timeline-label { font-size: 14px; font-weight: 600; color: var(--c-text); }
.timeline-items { display: flex; flex-direction: column; gap: 8px; }
.timeline-card {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  background: var(--c-bg); border-radius: 10px; cursor: pointer;
  transition: background 0.15s;
}
.timeline-card:hover { background: var(--c-primary-weak); }
.tl-key { font-weight: 600; font-size: 13px; flex-shrink: 0; }
.tl-value { font-size: 13px; color: var(--c-text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: stretch; }
  .search-input { max-width: 100%; width: 100%; }
  .toolbar-right { margin-left: 0; justify-content: flex-end; }
  .fact-table { display: block; overflow-x: auto; }
  .pending-item { flex-direction: column; align-items: flex-start; gap: 8px; }
  .pending-actions { width: 100%; justify-content: flex-end; }
}
</style>
