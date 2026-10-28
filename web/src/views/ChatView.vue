<template>
  <div class="chat-page">
    <div v-if="loading" class="card">
      <el-skeleton :rows="10" animated />
    </div>

    <template v-else-if="!error">
      <div class="card toolbar">
        <el-select v-model="selectedQq" class="user-select" size="default">
          <el-option label="全部用户" :value="0" />
          <el-option
            v-for="u in userList"
            :key="u.qqId"
            :label="'QQ ' + u.qqId"
            :value="u.qqId"
          />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          class="date-range"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          :shortcuts="dateShortcuts"
          unlink-panels
        />
        <el-input
          v-model="searchText"
          placeholder="搜索聊天记录…"
          clearable
          class="search-input"
          :prefix-icon="Search"
        />
        <el-button v-if="hasFilter" text size="small" @click="resetFilters">清空筛选</el-button>
        <span class="search-count num">{{ matchCount }} 条</span>
      </div>

      <div class="card chat-card">
        <EmptyState
          v-if="groupedMessages.length === 0"
          title="还没有聊天记录"
          desc="机器人开始聊天后，记录会出现在这里"
          :illustration="emptyLogs"
        />
        <div v-else ref="chatListRef" class="chat-list">
          <div v-for="group in groupedMessages" :key="group.date" class="date-group">
            <div class="date-header">
              <span class="date-label">{{ group.date }}</span>
            </div>
            <div
              v-for="msg in group.messages"
              :key="msg.id"
              :id="'msg-' + msg.id"
              class="message-row"
              :class="'role-' + msg.role"
            >
              <div class="msg-avatar">
                <span v-if="msg.role === 'user'" class="avatar avatar-user">{{ avatarOf(msg) }}</span>
                <span v-else class="avatar avatar-bot">{{ personaName }}</span>
              </div>
              <div class="msg-body">
                <div v-if="msg.role === 'user'" class="msg-sender">
                  QQ {{ msg.qqId ?? '未知' }}
                </div>
                <audio
                  v-if="msg.voiceFile"
                  class="bubble-audio"
                  :src="'/api/voices/' + msg.voiceFile"
                  controls
                  preload="none"
                />
                <div v-if="msg.voiceFile" class="bubble-transcript">{{ msg.content }}</div>
                <img
                  v-if="msg.emojiId"
                  class="bubble-sticker"
                  :src="'/api/emoji/' + msg.emojiId + '/file'"
                  :alt="msg.content"
                  :title="msg.content"
                />
                <div v-else-if="!msg.voiceFile" class="msg-bubble" v-html="highlightText(msg.content)" />
                <span class="msg-time num">{{ formatMsgTime(msg.createdAt) }}</span>
              </div>
            </div>
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
defineOptions({ name: 'ChatView' })
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Search, WarningFilled } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { normalizeMessage, normalizeMessages } from '@/api/adapters'
import type { ChatMessage, Page } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import emptyLogs from '@/assets/illustrations/empty-logs.svg'

const messages = ref<ChatMessage[]>([])
const loading = ref(true)
const error = ref('')
const searchText = ref('')
const chatListRef = ref<HTMLElement | null>(null)
const personaName = '小满'
const selectedQq = ref<number>(0)
const userList = ref<Array<{ qqId: number }>>([])
const dateRange = ref<[string, string] | null>(null)

const dateShortcuts = [
  {
    text: '今天',
    value: () => {
      const start = new Date()
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  },
  {
    text: '近 3 天',
    value: () => {
      const start = new Date()
      start.setDate(start.getDate() - 2)
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  },
  {
    text: '近 7 天',
    value: () => {
      const start = new Date()
      start.setDate(start.getDate() - 6)
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  },
  {
    text: '近 30 天',
    value: () => {
      const start = new Date()
      start.setDate(start.getDate() - 29)
      start.setHours(0, 0, 0, 0)
      return [start, new Date()]
    }
  }
]

function avatarOf(msg: ChatMessage) {
  const id = msg.qqId ?? ''
  return id ? id.slice(-4) : '用户'
}

interface DateGroup {
  date: string
  messages: ChatMessage[]
}

const groupedMessages = computed<DateGroup[]>(() => {
  const filtered = searchText.value.trim()
    ? messages.value.filter(m => m.content.includes(searchText.value.trim()))
    : messages.value

  const groups: Record<string, ChatMessage[]> = {}
  for (const msg of filtered) {
    const date = msg.createdAt.slice(0, 10)
    const label = formatDateLabel(date)
    if (!groups[label]) groups[label] = []
    groups[label].push(msg)
  }

  return Object.entries(groups).map(([date, msgs]) => ({ date, messages: msgs }))
})

const matchCount = computed(() => {
  if (!searchText.value.trim()) return messages.value.length
  return messages.value.filter(m => m.content.includes(searchText.value.trim())).length
})

function formatDateLabel(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)

  if (d >= today) return '今天'
  if (d >= yesterday) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

function formatMsgTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function highlightText(text: string): string {
  const q = searchText.value.trim()
  if (!q) return escapeHtml(text)
  const escaped = escapeHtml(text)
  const qEscaped = escapeHtml(q)
  const regex = new RegExp(`(${qEscaped.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'gi')
  return escaped.replace(regex, '<mark class="search-highlight">$1</mark>')
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

watch(searchText, () => {
  nextTick(() => {
    const firstMark = chatListRef.value?.querySelector('.search-highlight')
    if (firstMark) {
      firstMark.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  })
})

async function loadUsers() {
  try {
    const list = await api<Array<{ qqId: number }>>('/messages/users')
    userList.value = list
  } catch {
    userList.value = []
  }
}

async function loadData(silent = false) {
  if (!silent) {
    loading.value = true
  }
  error.value = ''
  try {
    const params = new URLSearchParams({
      page: '1',
      size: '300',
      qqId: String(selectedQq.value)
    })
    if (dateRange.value && dateRange.value.length === 2) {
      params.set('from', dateRange.value[0])
      params.set('to', dateRange.value[1])
    }
    const page = await api<Page<unknown>>('/messages?' + params.toString())
    messages.value = normalizeMessages(page.items)
      .sort((a, b) => Number(a.id) - Number(b.id))
  } catch (e: any) {
    if (!silent) {
      error.value = e?.message ?? '未知错误'
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

let refreshTimer: number | undefined
let stream: EventSource | undefined

function appendLive(m: ChatMessage) {
  if (!m.id) return
  if (selectedQq.value !== 0 && String(selectedQq.value) !== String(m.qqId)) return
  if (messages.value.some(x => x.id === m.id)) return
  messages.value = [...messages.value, m].sort((a, b) => Number(a.id) - Number(b.id))
}

async function startStream() {
  stopStream()
  let botId = localStorage.getItem('activeBotId')
  if (!botId) {
    try {
      const r = await api<{ items: Array<{ id: number }> }>('/account/list')
      if (r.items && r.items.length) {
        botId = String(r.items[0].id)
        localStorage.setItem('activeBotId', botId)
      }
    } catch { /* 忽略 */ }
  }
  if (!botId) return
  try {
    stream = new EventSource(`/api/messages/stream?botId=${botId}`)
    stream.addEventListener('message', (ev: MessageEvent) => {
      try {
        appendLive(normalizeMessage(JSON.parse(ev.data)))
      } catch { /* 忽略坏消息 */ }
    })
  } catch {
    // 浏览器不支持或不支持 SSE 时退回轮询
  }
}

function stopStream() {
  if (stream) stream.close()
  stream = undefined
}

function startRealtime() {
  if (refreshTimer) window.clearInterval(refreshTimer)
  // SSE 为主，轮询兜底（断线、切后台回来时补齐）
  refreshTimer = window.setInterval(() => {
    if (document.visibilityState !== 'visible') return
    loadData(true)
  }, 2000)
}

function stopRealtime() {
  if (refreshTimer) window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

watch(selectedQq, () => loadData())
watch(dateRange, () => loadData())

const hasFilter = computed(
  () => selectedQq.value !== 0 || Boolean(dateRange.value?.length) || Boolean(searchText.value.trim())
)

function resetFilters() {
  selectedQq.value = 0
  dateRange.value = null
  searchText.value = ''
}

onMounted(() => {
  loadUsers()
  loadData()
  startRealtime()
  startStream()
})

onBeforeUnmount(() => {
  stopRealtime()
  stopStream()
})
</script>

<style scoped>
.bubble-audio { width: 220px; max-width: 100%; }
.bubble-transcript { margin-top: 4px; font-size: 12px; color: var(--c-text-2); max-width: 260px; }
.bubble-sticker {
  max-width: 160px; max-height: 160px; border-radius: 10px;
  border: 1px solid var(--c-border); background: var(--c-bg);
  object-fit: contain;
}
.chat-page { display: flex; flex-direction: column; gap: 16px; }
.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.user-select { width: 160px; flex: none; }
.date-range { width: 360px; flex: none; }
.search-input { max-width: 400px; }
.search-count { font-size: 13px; color: var(--c-text-2); }
.chat-card { padding: 0; overflow: hidden; }
.chat-list { max-height: 680px; overflow-y: auto; padding: 16px; }
.date-group { margin-bottom: 20px; }
.date-header {
  display: flex; justify-content: center; margin-bottom: 12px;
}
.date-label {
  font-size: 12px; color: var(--c-text-2); background: var(--c-bg);
  padding: 2px 12px; border-radius: 10px;
}
.message-row {
  display: flex; gap: 10px; margin-bottom: 12px;
  align-items: flex-start;
}
.message-row.role-assistant { flex-direction: row; }
.message-row.role-user { flex-direction: row-reverse; }
.msg-avatar { flex-shrink: 0; }
.avatar {
  display: flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%;
  font-size: 12px; font-weight: 600;
}
.avatar-user { background: var(--c-primary-weak); color: var(--c-primary); }
.avatar-bot { background: var(--c-bg); color: var(--c-text-2); border: 1px solid var(--c-border); }
.msg-body { max-width: 70%; display: flex; flex-direction: column; gap: 4px; }
.message-row.role-user .msg-body { align-items: flex-end; }
.msg-bubble {
  padding: 10px 14px; border-radius: 14px;
  font-size: 14px; line-height: 1.6; word-break: break-word;
}
.message-row.role-assistant .msg-bubble {
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-top-left-radius: 4px;
}
.message-row.role-user .msg-bubble {
  background: var(--c-primary-weak); color: var(--c-text);
  border-top-right-radius: 4px;
}
.msg-time { font-size: 11px; color: var(--c-text-2); }
.msg-sender { font-size: 11.5px; color: var(--c-text-2); margin-bottom: 3px; font-variant-numeric: tabular-nums; }
:deep(.search-highlight) {
  background: #fde68a; color: #92400e; border-radius: 2px; padding: 0 1px;
}
[data-theme="dark"] :deep(.search-highlight) {
  background: #92400e; color: #fde68a;
}
.error-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; color: var(--c-text-2); }

@media (max-width: 768px) {
  .chat-page { gap: 12px; }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 12px;
  }
  .user-select,
  .date-range,
  .search-input {
    width: 100% !important;
    max-width: 100%;
    flex: none;
  }
  .date-range :deep(.el-range-input) { font-size: 12px; }
  .search-count { align-self: flex-end; }
  .chat-card { border-radius: 12px; }
  .chat-list { max-height: none; padding: 12px 10px; }
  .message-row { gap: 6px; margin-bottom: 10px; }
  .msg-body { max-width: 86%; }
  .bubble { font-size: 14px; padding: 9px 12px; }
  .avatar { width: 28px; height: 28px; font-size: 11px; }
  .msg-time { font-size: 10.5px; }
  .date-label { font-size: 11.5px; }
}
</style>
