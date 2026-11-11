<template>
  <div class="expression-page">
    <div v-if="loading" class="card">
      <el-skeleton :rows="8" animated />
    </div>

    <template v-else>
      <el-alert v-if="error" type="error" :closable="false" show-icon class="error-alert">
        {{ error }}
      </el-alert>

      <div class="card">
        <div class="section-head">
          <div>
            <h3 class="section-title">人格表达</h3>
            <p class="section-desc">说说、头像、签名属于公开表达，默认关闭；先预览再发布。</p>
          </div>
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" @change="saveConfig" />
        </div>

        <el-form label-width="120px" class="config-form">
          <el-divider content-position="left">QQ 空间说说</el-divider>
          <el-form-item label="说说功能">
            <el-switch v-model="form.qzone.enabled" @change="saveConfig" />
            <span class="hint">关闭后不能发布，但仍可生成草稿预览；切换后自动保存</span>
          </el-form-item>
          <el-form-item label="自动发布">
            <el-switch v-model="form.qzone.autoPost" disabled />
            <span class="hint">当前版本只支持生成草稿后人工确认，自动发布预留</span>
          </el-form-item>
          <el-form-item label="受众策略">
            <el-select v-model="form.qzone.audienceMode" style="width: 220px">
              <el-option label="模型自动判断" value="auto" />
              <el-option label="全部好友可见" value="public" />
              <el-option label="只给目标用户" value="targeted" />
              <el-option label="仅自己可见" value="private" />
            </el-select>
          </el-form-item>
          <el-form-item label="公开范围">
            <el-select v-model="form.qzone.publicUgcRight" style="width: 180px">
              <el-option label="好友可见" :value="4" />
              <el-option label="所有人可见" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item label="每日自动生成">
            <el-input-number v-model="form.qzone.autoDraftPerDay" :min="0" :max="5" @change="saveConfig" />
            <span class="hint">条草稿/天（0 = 不自动生成，生成后等你审核）</span>
          </el-form-item>
          <el-form-item label="每日上限">
            <el-input-number v-model="form.qzone.maxPerDay" :min="1" :max="5" />
            <span class="hint">条/天</span>
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

          <el-divider content-position="left">好友自动分类</el-divider>
          <el-form-item label="自动分类">
            <el-switch v-model="form.friends.autoCategory" />
            <span class="hint">根据聊天条数、最近活跃时间生成后台标签，不会修改 QQ 原生分组</span>
          </el-form-item>
          <el-form-item label="刷新间隔">
            <el-input-number v-model="form.friends.refreshHours" :min="1" :max="72" />
            <span class="hint">小时</span>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="card">
        <div class="section-head">
          <div>
            <h3 class="section-title">发一条说说</h3>
            <p class="section-desc">模型先判断这条内容适合公开，还是只给影响心情的那个人看。</p>
          </div>
        </div>

        <div class="draft-tools">
          <el-input v-model="targetQq" placeholder="目标 QQ（可选，影响心情的用户）" style="width: 240px" />
          <el-input v-model="hint" placeholder="给模型一个由头，例如：今天心情不错（可选）" style="flex: 1; min-width: 220px" />
          <el-button type="primary" :loading="drafting" @click="generateDraft">
            <el-icon><MagicStick /></el-icon>生成草稿
          </el-button>
        </div>

        <div v-if="draft" class="draft-box">
          <el-input
            v-model="draftContent"
            type="textarea"
            :rows="4"
            maxlength="200"
            show-word-limit
            placeholder="说说正文"
          />
          <div class="audience-row">
            <span class="label">受众</span>
            <el-radio-group v-model="publishAudience">
              <el-radio-button value="auto">按草稿判断</el-radio-button>
              <el-radio-button value="public">所有人</el-radio-button>
              <el-radio-button value="targeted">只给 TA</el-radio-button>
              <el-radio-button value="private">仅自己</el-radio-button>
            </el-radio-group>
            <el-tag size="small" effect="plain">模型判断：{{ audienceLabel(draft.audience) }}</el-tag>
            <span v-if="draft.inspirationQq" class="draft-target">素材来自 QQ {{ draft.inspirationQq }}</span>
          </div>
          <div class="draft-reason">{{ draft.reason }}</div>
          <div class="draft-actions">
            <el-button type="primary" :loading="publishing" @click="publishDraft">发布到 QQ 空间</el-button>
            <el-button @click="draft = null">取消</el-button>
          </div>
        </div>

        <el-divider content-position="left">待审核说说（{{ drafts.length }}）</el-divider>
        <el-table :data="drafts" size="small" max-height="320">
          <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
          <el-table-column label="受众" width="150">
            <template #default="{ row }">
              <el-select
                :model-value="row.audience"
                size="small"
                @change="(v: string) => changeAudience(row, v)"
              >
                <el-option label="自动判断" value="public" />
                <el-option label="好友可见" value="public" />
                <el-option label="只给TA看" value="targeted" />
                <el-option label="仅自己" value="private" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="生成时间" width="150">
            <template #default="{ row }">{{ formatRelativeTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button text size="small" type="primary" @click="publishRow(row)">发布</el-button>
              <el-button text size="small" type="danger" @click="removeRow(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">发布历史</el-divider>
        <el-table :data="history" size="small" max-height="320">
          <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
          <el-table-column label="受众" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ audienceLabel(row.audience) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标" width="150">
            <template #default="{ row }">
              <span v-if="row.targetUins">QQ {{ row.targetUins }}<span v-if="row.targetNickname">（{{ row.targetNickname }}）</span></span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatRelativeTime(row.publishedAt || row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="card">
        <div class="section-head">
          <div>
            <h3 class="section-title">好友分类</h3>
            <p class="section-desc">
              原生分组读取自 NapCat；自动标签由聊天频率和最近活跃时间生成。
            </p>
          </div>
          <div class="friend-tools">
            <el-select v-model="friendCategory" clearable placeholder="全部分类" size="small" style="width: 130px" @change="loadFriends">
              <el-option v-for="(count, name) in friendCounts" :key="name" :label="`${name} (${count})`" :value="name" />
            </el-select>
            <el-button size="small" :loading="refreshing" @click="refreshFriends">
              <el-icon><Refresh /></el-icon>刷新好友
            </el-button>
          </div>
        </div>

        <EmptyState
          v-if="friends.length === 0"
          title="还没有好友数据"
          desc="点击「刷新好友」从 NapCat 读取好友列表并生成分类"
          :illustration="emptyEmoji"
        />
        <el-table v-else :data="friends" size="small" max-height="420">
          <el-table-column label="好友" min-width="170">
            <template #default="{ row }">
              <div class="friend-name">{{ row.remark || row.nickname }}</div>
              <div class="friend-qq num">{{ row.qqId }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="nativeCategoryName" label="QQ 原生分组" width="130" />
          <el-table-column label="自动分类" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="categoryTag(row.autoCategory)">{{ row.autoCategory }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="消息" width="90">
            <template #default="{ row }">{{ row.messageCount }}</template>
          </el-table-column>
          <el-table-column label="最近聊天" width="170">
            <template #default="{ row }">
              <span class="muted">{{ row.lastMessageAt ? formatRelativeTime(row.lastMessageAt) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="心情" width="90">
            <template #default="{ row }">
              <span :class="row.mood < -0.2 ? 'mood-low' : row.mood > 0.2 ? 'mood-high' : 'muted'">
                {{ row.mood.toFixed(2) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="依据" min-width="180" show-overflow-tooltip />
        </el-table>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'ExpressionView' })
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Refresh } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import EmptyState from '@/components/EmptyState.vue'
import emptyEmoji from '@/assets/illustrations/empty-emoji.svg'
import { formatRelativeTime } from '@/utils/format'

interface QzoneConfig {
  enabled: boolean
  autoPost: boolean
  audienceMode: string
  publicUgcRight: number
  maxPerDay: number
  autoDraftPerDay: number
  includeImages: boolean
  quiet: { from: string; to: string }
}
interface FriendsConfig {
  autoCategory: boolean
  refreshHours: number
  nativeSync: boolean
  closeThreshold?: number
  frequentThreshold?: number
  quietDays?: number
}
interface ExpressionConfig {
  enabled: boolean
  qzone: QzoneConfig
  friends: FriendsConfig
}
interface Draft {
  content: string
  audience: string
  ugcRight: number
  targetUins?: number[] | string
  targetNickname?: string
  reason: string
  inspirationQq?: number
  moodText?: string
}
interface FriendProfile {
  qqId: number
  nickname: string
  remark: string
  nativeCategoryName: string
  autoCategory: string
  messageCount: number
  lastMessageAt: string | null
  mood: number
  reason: string
  inspirationQq?: number
}
interface QzoneHistory {
  id: number
  content: string
  audience: string
  status: string
  createdAt: string
  publishedAt: string | null
}

const loading = ref(true)
const error = ref('')
const saving = ref(false)
const drafting = ref(false)
const publishing = ref(false)
const refreshing = ref(false)
const form = ref<ExpressionConfig>({
  enabled: false,
  qzone: {
    enabled: false,
    autoPost: false,
    audienceMode: 'auto',
    publicUgcRight: 4,
    maxPerDay: 1,
    autoDraftPerDay: 0,
    includeImages: false,
    quiet: { from: '23:30', to: '08:00' }
  },
  friends: { autoCategory: true, refreshHours: 6, nativeSync: false }
})
const drafts = ref<Array<{ id: number; content: string; audience: string; createdAt: string; targetUins?: string; targetNickname?: string }>>([])
const draft = ref<Draft | null>(null)
const draftContent = ref('')
const publishAudience = ref('auto')
const targetQq = ref('')
const hint = ref('')
const history = ref<QzoneHistory[]>([])
const friends = ref<FriendProfile[]>([])
const friendCounts = ref<Record<string, number>>({})
const friendCategory = ref('')

const quietRange = computed({
  get: () => [form.value.qzone.quiet.from, form.value.qzone.quiet.to],
  set: (v: string[]) => {
    form.value.qzone.quiet.from = v[0]
    form.value.qzone.quiet.to = v[1]
  }
})

function audienceLabel(value: string): string {
  return ({ public: '所有人', targeted: '只给 TA', private: '仅自己', skip: '不发' } as Record<string, string>)[value] ?? value
}

function categoryTag(value: string): 'danger' | 'warning' | 'success' | 'info' {
  if (value === '特别关心') return 'danger'
  if (value === '常聊') return 'warning'
  if (value === '新朋友') return 'success'
  return 'info'
}

async function loadConfig() {
  form.value = await api<ExpressionConfig>('/expression/config')
}

async function loadDrafts() {
  try {
    const r = await api<{ items: typeof drafts.value }>('/expression/qzone/drafts')
    drafts.value = r.items ?? []
  } catch {
    drafts.value = []
  }
}

async function changeAudience(row: any, audience: string) {
  try {
    await api(`/expression/qzone/drafts/${row.id}/audience`, {
      method: 'PUT',
      body: { audience }
    })
    row.audience = audience
    ElMessage.success('受众已更新')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '更新失败')
  }
}

async function publishRow(row: any) {
  try {
    const r = await api<{ ok: boolean; message?: string }>(
      `/expression/qzone/drafts/${row.id}/publish`,
      { method: 'POST' }
    )
    if (!r.ok) {
      ElMessage.error(r.message ?? '发布失败')
      return
    }
    ElMessage.success('已发布到 QQ 空间')
    await loadDrafts()
    await loadHistory()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '发布失败')
  }
}

async function removeRow(row: any) {
  try {
    await api(`/expression/qzone/drafts/${row.id}`, { method: 'DELETE' })
    drafts.value = drafts.value.filter(x => x.id !== row.id)
    ElMessage.success('已删除')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '删除失败')
  }
}

async function loadHistory() {
  const page = await api<{ items: QzoneHistory[] }>('/expression/qzone/history?page=1&size=20')
  history.value = page.items ?? []
}

async function loadFriends() {
  const query = friendCategory.value ? `?category=${encodeURIComponent(friendCategory.value)}` : ''
  const data = await api<{ items: FriendProfile[]; counts: Record<string, number> }>(`/friends${query}`)
  friends.value = data.items ?? []
  friendCounts.value = data.counts ?? {}
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadConfig(), loadHistory(), loadDrafts(), loadFriends()])
  } catch (e: any) {
    error.value = e?.message ?? '加载失败'
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  saving.value = true
  try {
    form.value = await api<ExpressionConfig>('/expression/config', { method: 'PUT', body: form.value })
    ElMessage.success('配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function generateDraft() {
  drafting.value = true
  try {
    const qqId = targetQq.value.trim() ? Number(targetQq.value.trim()) : undefined
    const data = await api<Draft>('/expression/qzone/draft', {
      method: 'POST',
      body: { qqId, hint: hint.value || undefined }
    })
    draft.value = data
    draftContent.value = data.content
    publishAudience.value = data.audience
    if (data.inspirationQq) targetQq.value = String(data.inspirationQq)
  } catch (e: any) {
    ElMessage.error(e?.message ?? '生成失败')
  } finally {
    drafting.value = false
  }
}

async function publishDraft() {
  if (!draftContent.value.trim()) {
    ElMessage.warning('说说内容不能为空')
    return
  }
  publishing.value = true
  try {
    const qqId = targetQq.value.trim()
      ? Number(targetQq.value.trim())
      : (draft.value?.inspirationQq || undefined)
    const result = await api<{ ok: boolean; tid?: string; audience?: string; reason?: string }>(
      '/expression/qzone/publish',
      {
        method: 'POST',
        body: { content: draftContent.value.trim(), qqId, audience: publishAudience.value }
      }
    )
    if (result.ok) {
      ElMessage.success(`已发布（${audienceLabel(result.audience ?? '')}）`)
      draft.value = null
      await loadHistory()
    } else {
      ElMessage.warning(result.reason ?? '已跳过')
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? '发布失败')
  } finally {
    publishing.value = false
  }
}

async function refreshFriends() {
  refreshing.value = true
  try {
    const result = await api<{ friends: number; note?: string }>('/friends/refresh', { method: 'POST' })
    ElMessage.success(`已刷新 ${result.friends} 位好友`)
    await loadFriends()
  } catch (e: any) {
    ElMessage.error(e?.message ?? '刷新失败')
  } finally {
    refreshing.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.target-hint { font-size: 11.5px; color: var(--c-text-2); margin-top: 4px; }
.expression-page { display: flex; flex-direction: column; gap: 16px; }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.section-title { margin: 0; font-size: 15px; font-weight: 600; }
.section-desc { margin: 4px 0 0; color: var(--c-text-2); font-size: 12.5px; }
.config-form { max-width: 760px; }
.hint { margin-left: 10px; color: var(--c-text-2); font-size: 12px; }
.draft-tools { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.draft-box { border: 1px solid var(--c-border); border-radius: 10px; padding: 14px; background: var(--c-bg-soft); }
.audience-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 12px; }
.audience-row .label { color: var(--c-text-2); font-size: 13px; }
.draft-reason { margin-top: 8px; color: var(--c-text-2); font-size: 12px; }
.draft-actions { display: flex; gap: 8px; margin-top: 12px; }
.friend-tools { display: flex; gap: 8px; align-items: center; }
.friend-name { font-weight: 500; }
.friend-qq { color: var(--c-text-2); font-size: 11px; }
.muted { color: var(--c-text-2); font-size: 12px; }
.mood-low { color: var(--c-danger); }
.mood-high { color: var(--c-success); }
.error-alert { margin-bottom: 0; }
@media (max-width: 768px) {
  .section-head { flex-direction: column; }
  .config-form :deep(.el-form-item) { flex-direction: column; align-items: flex-start; }
  .config-form :deep(.el-form-item__label) { width: auto !important; margin-bottom: 4px; }
  .draft-tools > * { width: 100% !important; }
  .friend-tools { width: 100%; }
}
</style>
