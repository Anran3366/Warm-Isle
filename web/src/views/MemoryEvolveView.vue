<template>
  <div class="evolve-page">
    <div class="card toolbar">
      <el-select
        v-model="qqId"
        filterable
        placeholder="选择要查看的人"
        style="width: 230px"
        @change="loadAll"
      >
        <el-option
          v-for="u in users"
          :key="u.qqId"
          :label="`${u.name}（${u.qqId}）`"
          :value="String(u.qqId)"
        />
      </el-select>
      <el-button :loading="loading" @click="loadAll">刷新</el-button>
      <el-switch v-model="enabled" active-text="记忆自进化" @change="toggle" />
      <span class="hint">每天 00:20 反思 · 周日 02:10 挖掘表达</span>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="成长档案" name="growth">
        <div class="card">
          <div class="pane-head">
            <span>她和你相处学到的「一句话经验」，采纳后才会进 prompt（最多 3 条 / 120 字）</span>
            <el-tag size="small" type="info">待审 {{ growth.filter(g => g.status === 'pending').length }}</el-tag>
          </div>
          <el-empty v-if="!growth.length" description="还没有成长候选，等今晚 00:20 反思" />
          <div v-for="g in growth" :key="g.id" class="row">
            <div class="row-main">
              <span class="content">{{ g.content }}</span>
              <span class="muted">证据 {{ g.evidence || '—' }} · {{ g.createdAt }}</span>
            </div>
            <el-tag :type="g.status === 'approved' ? 'success' : g.status === 'rejected' ? 'info' : 'warning'" size="small">
              {{ statusText(g.status) }}
            </el-tag>
            <el-button v-if="g.status === 'pending'" size="small" type="primary" plain @click="reviewGrowth(g.id, true)">采纳</el-button>
            <el-button v-if="g.status === 'pending'" size="small" @click="reviewGrowth(g.id, false)">拒绝</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="表达方式" name="expressions">
        <div class="card">
          <div class="pane-head">
            <span>她以前被接住的说法；审核通过后按场景注入，只学语气，不照抄</span>
            <el-button size="small" :loading="mining" @click="mine">从最近 7 天挖掘</el-button>
          </div>
          <el-empty v-if="!expressions.length" description="还没有候选" />
          <div v-for="e in expressions" :key="e.id" class="row">
            <div class="row-main">
              <span class="content">「{{ e.text }}」</span>
              <span class="muted">场景：{{ e.situation }} · 用过 {{ e.useCount }} 次 · {{ e.createdAt }}</span>
            </div>
            <el-tag :type="e.status === 'approved' ? 'success' : e.status === 'rejected' ? 'info' : 'warning'" size="small">
              {{ statusText(e.status) }}
            </el-tag>
            <el-button v-if="e.status === 'pending'" size="small" type="primary" plain @click="reviewExpression(e.id, true)">允许</el-button>
            <el-button v-if="e.status === 'pending'" size="small" @click="reviewExpression(e.id, false)">拒绝</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="记忆体检" name="audit">
        <div class="card">
          <div class="pane-head">
            <span>矛盾、快忘、过时、重复；只报告，不自动改</span>
            <el-button size="small" type="primary" plain :loading="auditing" @click="runAudit">运行体检</el-button>
          </div>
          <el-empty v-if="!audit" description="点「运行体检」" />
          <template v-else>
            <div class="metric-row">
              <el-tag type="danger">矛盾 {{ audit.conflicts?.length || 0 }}</el-tag>
              <el-tag type="warning">快忘 {{ audit.fading?.length || 0 }}</el-tag>
              <el-tag type="info">过时 {{ audit.stale?.length || 0 }}</el-tag>
              <el-tag type="info">重复 {{ audit.duplicates?.length || 0 }}</el-tag>
              <el-tag>待审成长 {{ audit.pendingGrowth || 0 }}</el-tag>
              <el-tag>待审表达 {{ audit.pendingExpressions || 0 }}</el-tag>
            </div>
            <div v-if="audit.conflicts?.length" class="block">
              <div class="block-title">矛盾</div>
              <div v-for="(c, i) in audit.conflicts" :key="i" class="line">
                {{ c.key }}：<span v-for="(v, j) in c.values" :key="j">{{ v.value }}(#{{ v.id }})<span v-if="j < c.values.length - 1"> / </span></span>
              </div>
            </div>
            <div v-if="audit.fading?.length" class="block">
              <div class="block-title">快忘（重要但强度低）</div>
              <div v-for="f in audit.fading" :key="f.id" class="line">{{ f.key }} = {{ f.value }}（强度 {{ f.strength }}）</div>
            </div>
            <div v-if="audit.stale?.length" class="block">
              <div class="block-title">过时事件</div>
              <div v-for="s in audit.stale" :key="s.id" class="line">{{ s.key }} = {{ s.value }}（{{ s.lastConfirmed }}）</div>
            </div>
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane label="常驻画像" name="profile">
        <div class="card">
          <div class="pane-head">
            <span>每次回复都会带的 160 字「她现在怎么看你」</span>
            <div>
              <el-button size="small" @click="rebuild">重建</el-button>
              <el-button size="small" :loading="reflecting" @click="reflect">立即反思</el-button>
              <el-button size="small" :loading="consolidating" @click="consolidate">巩固记忆</el-button>
            </div>
          </div>
          <el-input v-model="snapshot" type="textarea" :rows="3" readonly />
          <div class="pane-head" style="margin-top: 16px">
            <span>召回预览（用一句话试）</span>
            <div class="inline">
              <el-input v-model="probe" placeholder="在吗" style="width: 220px" />
              <el-button size="small" @click="preview">预览</el-button>
            </div>
          </div>
          <pre class="recall">{{ recallBlock || '（点预览）' }}</pre>
        </div>
      </el-tab-pane>

      <el-tab-pane label="人物档案" name="profile-card">
        <div class="card">
          <div class="pane-head">
            <span>称呼、阶段、特征、雷区、重要日期；反思会自动回填，也可以手改</span>
            <el-button size="small" type="primary" :loading="savingProfile" @click="saveProfile">保存</el-button>
          </div>
          <el-form label-width="88px" class="profile-form">
            <el-form-item label="怎么称呼">
              <el-input v-model="profile.address" placeholder="不知道就叫「你」" style="width: 240px" />
            </el-form-item>
            <el-form-item label="关系阶段">
              <el-select v-model="profile.stage" clearable style="width: 160px">
                <el-option v-for="x in ['陌生','熟悉','亲近','暧昧','恋人']" :key="x" :label="x" :value="x" />
              </el-select>
            </el-form-item>
            <el-form-item label="特征">
              <el-input v-model="profile.traitsText" type="textarea" :rows="2" placeholder="逗号或换行分隔，例如：凌晨三四点还不睡、喜欢养小猫" />
            </el-form-item>
            <el-form-item label="雷区">
              <el-input v-model="profile.taboosText" type="textarea" :rows="2" placeholder="例如：不要叫超威、别说教" />
            </el-form-item>
            <el-form-item label="重要日期">
              <el-input v-model="profile.datesText" type="textarea" :rows="2" placeholder="每行一个：08-12 生日" />
            </el-form-item>
          </el-form>
          <div class="muted">上次更新：{{ profile.updatedAt || '—' }}</div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="观测台" name="timeline">
        <div class="card">
          <div class="pane-head">
            <span>洞察、相处经验、成长候选、未完成跟进，按时间倒序</span>
            <el-button size="small" :loading="loadingTimeline" @click="loadTimeline">刷新</el-button>
          </div>
          <el-empty v-if="!timeline.length" description="还没有时间线，等今晚 00:20 反思" />
          <div v-for="(t, i) in timeline" :key="i" class="timeline-row">
            <div class="timeline-at">{{ t.at || '—' }}</div>
            <el-tag size="small" :type="kindType(t.kind)">{{ kindLabel(t.kind) }}</el-tag>
            <div class="timeline-main">
              <span class="content">{{ t.title }}</span>
              <span class="muted">{{ t.detail }}</span>
            </div>
            <el-tag v-if="t.status && t.status !== 'open'" size="small" effect="plain">{{ statusText(t.status) }}</el-tag>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'

interface UserOption { qqId: string; name: string; total: number; active: number }
interface GrowthNote { id: number; content: string; evidence?: string; status: string; createdAt?: string }
interface Expression { id: number; situation: string; text: string; status: string; useCount?: number; createdAt?: string }
interface AuditItem { id: number; key?: string; value?: string; strength?: number; lastConfirmed?: string }
interface TimelineItem { at: string; kind: string; title: string; detail: string; status: string; id: number }
interface AuditReport {
  conflicts?: { key: string; values: { id: number; value: string }[] }[]
  fading?: AuditItem[]
  stale?: AuditItem[]
  duplicates?: { value: string; ids: number[] }[]
  pendingGrowth?: number
  pendingExpressions?: number
}

const tab = ref('growth')
const qqId = ref(localStorage.getItem('activeQq') || '')
const users = ref<UserOption[]>([])
const enabled = ref(true)
const loading = ref(false)
const growth = ref<GrowthNote[]>([])
const expressions = ref<Expression[]>([])
const audit = ref<AuditReport | null>(null)
const timeline = ref<TimelineItem[]>([])
const loadingTimeline = ref(false)
const savingProfile = ref(false)
const profile = ref({
  address: '', stage: '', traitsText: '', taboosText: '', datesText: '', updatedAt: ''
})
const snapshot = ref('')
const recallBlock = ref('')
const probe = ref('在吗')
const mining = ref(false)
const auditing = ref(false)
const reflecting = ref(false)
const consolidating = ref(false)

function q(): string {
  return String(qqId.value || '').trim()
}

async function loadUsers() {
  const list = await api<UserOption[]>('/memories/graph/users')
  users.value = list || []
  if (!users.value.some(u => String(u.qqId) === qqId.value)) {
    qqId.value = users.value.length ? String(users.value[0].qqId) : ''
  }
}

async function loadAll() {
  if (!q()) {
    ElMessage.warning('还没有聊过天的人，先让机器人聊几句')
    return
  }
  loading.value = true
  try {
    const st = await api<{ enabled: boolean }>('/memory/evolve/status')
    enabled.value = st.enabled
    await Promise.all([loadGrowth(), loadExpressions(), loadSnapshot(), loadProfile(), loadTimeline()])
  } finally {
    loading.value = false
  }
}

async function loadGrowth() {
  const r = await api<{ items: GrowthNote[] }>(`/memory/evolve/growth?qqId=${q()}`)
  growth.value = r.items || []
}

async function loadExpressions() {
  const r = await api<{ items: Expression[] }>(`/memory/evolve/expressions?qqId=${q()}`)
  expressions.value = r.items || []
}

async function loadProfile() {
  const r = await api<{ address: string; stage: string; traits: string[]; taboos: string[]; importantDates: { md: string; label: string }[]; updatedAt: string }>(
    `/memory/evolve/profile?qqId=${q()}`)
  profile.value = {
    address: r.address || '',
    stage: r.stage || '',
    traitsText: (r.traits || []).join('、'),
    taboosText: (r.taboos || []).join('、'),
    datesText: (r.importantDates || []).map(d => `${d.md} ${d.label}`.trim()).join('\n'),
    updatedAt: r.updatedAt || ''
  }
}

async function saveProfile() {
  savingProfile.value = true
  try {
    const split = (s: string) => s.split(/[、,，\n]/).map(x => x.trim()).filter(Boolean)
    const dates = profile.value.datesText.split('\n').map(line => {
      const [md, ...rest] = line.trim().split(/\s+/)
      return { md: md || '', label: rest.join(' ') }
    }).filter(d => d.md)
    const r = await api<{ updatedAt: string }>(`/memory/evolve/profile?qqId=${q()}`, {
      method: 'POST',
      body: {
        address: profile.value.address,
        stage: profile.value.stage,
        traits: split(profile.value.traitsText),
        taboos: split(profile.value.taboosText),
        importantDates: dates
      }
    })
    profile.value.updatedAt = r.updatedAt || ''
    ElMessage.success('人物档案已保存')
    await loadSnapshot()
  } finally {
    savingProfile.value = false
  }
}

async function loadTimeline() {
  loadingTimeline.value = true
  try {
    const r = await api<{ items: TimelineItem[] }>(`/memory/evolve/timeline?qqId=${q()}&limit=100`)
    timeline.value = r.items || []
  } finally {
    loadingTimeline.value = false
  }
}

async function loadSnapshot() {
  const r = await api<{ text: string }>(`/memory/evolve/snapshot?qqId=${q()}`)
  snapshot.value = r.text || ''
}

async function toggle(v: boolean | string | number) {
  await api('/memory/evolve/toggle?enabled=' + !!v, { method: 'POST' })
  ElMessage.success(v ? '记忆自进化已开启' : '记忆自进化已关闭')
}

async function reviewGrowth(id: number, approve: boolean) {
  await api(`/memory/evolve/growth/${id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' })
  ElMessage.success(approve ? '已采纳' : '已拒绝')
  await loadGrowth()
}

async function reviewExpression(id: number, approve: boolean) {
  await api(`/memory/evolve/expressions/${id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' })
  ElMessage.success(approve ? '已允许' : '已拒绝')
  await loadExpressions()
}

async function mine() {
  mining.value = true
  try {
    const r = await api<{ added: number }>(`/memory/evolve/expressions/mine?qqId=${q()}`, { method: 'POST' })
    ElMessage.success(`新增 ${r.added} 条候选`)
    await loadExpressions()
  } finally {
    mining.value = false
  }
}

async function runAudit() {
  auditing.value = true
  try {
    audit.value = await api<AuditReport>(`/memory/evolve/audit?qqId=${q()}`)
  } finally {
    auditing.value = false
  }
}

async function rebuild() {
  const r = await api<{ text: string }>(`/memory/evolve/snapshot/rebuild?qqId=${q()}`, { method: 'POST' })
  snapshot.value = r.text || ''
  ElMessage.success('已重建')
}

async function reflect() {
  reflecting.value = true
  try {
    const r = await api<Record<string, unknown>>(`/memory/evolve/reflect?qqId=${q()}&force=true`, { method: 'POST' })
    ElMessage.success('反思完成：' + JSON.stringify(r))
    await Promise.all([loadGrowth(), loadSnapshot()])
  } finally {
    reflecting.value = false
  }
}

async function consolidate() {
  consolidating.value = true
  try {
    const r = await api<Record<string, unknown>>(`/memory/evolve/consolidate?qqId=${q()}`, { method: 'POST' })
    ElMessage.success('巩固完成：' + JSON.stringify(r))
    await loadSnapshot()
  } finally {
    consolidating.value = false
  }
}

async function preview() {
  const r = await api<{ block: string }>(
    `/memory/evolve/recall?qqId=${q()}&text=${encodeURIComponent(probe.value || '在吗')}`)
  recallBlock.value = r.block || ''
}

function statusText(s: string) {
  return s === 'approved' ? '已采纳' : s === 'rejected' ? '已拒绝' : '待审'
}

function kindLabel(kind: string) {
  return ({ insight: '洞察', lesson: '经验', growth: '成长', followup: '未完成' } as Record<string, string>)[kind] || kind
}

function kindType(kind: string) {
  return kind === 'growth' ? 'success' : kind === 'followup' ? 'warning' : kind === 'lesson' ? 'info' : 'primary'
}

function onTab(name: string | number) {
  if (name === 'audit' && !audit.value) runAudit()
  if (name === 'timeline' && !timeline.value.length) loadTimeline()
  if (name === 'profile-card') loadProfile()
}

onMounted(async () => {
  await loadUsers()
  await loadAll()
})
</script>

<style scoped>
.evolve-page { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.card { padding: 16px; }
.pane-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.row { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px dashed var(--el-border-color-light); }
.row-main { flex: 1; display: flex; flex-direction: column; gap: 4px; }
.content { color: var(--el-text-color-primary); }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.metric-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.block { margin-top: 12px; }
.block-title { font-weight: 600; margin-bottom: 6px; }
.line { font-size: 13px; color: var(--el-text-color-regular); padding: 3px 0; }
.inline { display: flex; gap: 8px; align-items: center; }
.profile-form { max-width: 720px; }
.timeline-row { display: flex; align-items: flex-start; gap: 10px; padding: 10px 0; border-bottom: 1px dashed var(--el-border-color-light); }
.timeline-at { width: 150px; color: var(--el-text-color-secondary); font-size: 12px; padding-top: 2px; }
.timeline-main { flex: 1; display: flex; flex-direction: column; gap: 4px; }
.recall { background: var(--el-fill-color-light); border-radius: 8px; padding: 12px; white-space: pre-wrap; font-size: 13px; line-height: 1.6; }
</style>
