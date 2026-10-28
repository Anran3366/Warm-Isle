<template>
  <a href="#main-content" class="skip-link">跳到主要内容</a>
  <el-container class="layout">
    <!-- Desktop sidebar -->
    <el-aside v-if="!isMobile" width="220px" class="aside">
      <div class="brand">
        <span class="brand-dot" />
        <span class="brand-name">暖屿 · 小满</span>
      </div>
      <el-menu :default-active="route.path" router class="menu">
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
          @mouseenter="preloadRoute(item.path)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-foot">
        <el-button text size="small" @click="logout">退出登录</el-button>
      </div>
    </el-aside>

    <!-- Mobile drawer -->
    <el-drawer
      v-if="isMobile"
      v-model="drawerVisible"
      direction="ltr"
      :size="260"
      :show-close="false"
      class="mobile-drawer"
    >
      <template #header>
        <div class="brand">
          <span class="brand-dot" />
          <span class="brand-name">暖屿 · 小满</span>
        </div>
      </template>
      <el-menu :default-active="route.path" router class="menu" @select="drawerVisible = false">
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
      <template #footer>
        <el-button text @click="logout">退出登录</el-button>
      </template>
    </el-drawer>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button v-if="isMobile" text circle @click="drawerVisible = true" class="hamburger" aria-label="打开导航菜单">
            <el-icon :size="20"><Expand /></el-icon>
          </el-button>
          <h1 class="page-title">{{ title }}</h1>
        </div>
        <div class="header-right">
          <el-select v-if="bots.length > 1" v-model="activeBot" size="small" class="bot-select"
                     @change="switchBot">
            <el-option v-for="b in bots" :key="b.id" :value="b.id"
                       :label="(b.name || ('账号' + b.id)) + (b.qqId ? ' · ' + b.qqId : '')" />
          </el-select>
          <el-tag v-if="!isMobile" :type="status?.bot.online ? 'success' : 'info'" size="small" effect="plain">
            {{ status?.bot.online ? '在线' : '离线' }}
          </el-tag>
          <span v-if="!isMobile" class="muted">¥{{ status?.today.cost.toFixed(2) ?? '0.00' }}</span>
          <el-button text circle @click="toggleDark" :aria-label="isDark ? '切换到亮色主题' : '切换到暗色主题'">
            <el-icon :size="18">
              <Moon v-if="isDark" />
              <Sunny v-else />
            </el-icon>
          </el-button>
        </div>
      </el-header>
      <el-main id="main-content" class="main" tabindex="-1">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <keep-alive :include="cachedViews">
              <component :is="Component" />
            </keep-alive>
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="showHelp" title="键盘快捷键" width="400px" class="shortcut-dialog">
    <div class="shortcut-list">
      <div v-for="s in shortcuts" :key="s.key" class="shortcut-item">
        <kbd>{{ s.key === 'g d' ? 'g → d' : s.key === 'g m' ? 'g → m' : s.key === 'g p' ? 'g → p' : s.key === 'g e' ? 'g → e' : s.key === 'g c' ? 'g → c' : s.key }}</kbd>
        <span>{{ s.description }}</span>
      </div>
    </div>
    <template #footer>
      <el-button type="primary" @click="showHelp = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Odometer, User, Collection, PictureRounded, Microphone,
  ChatDotRound, ChatLineSquare, Setting, Document, Moon, Sunny, Expand, InfoFilled,
  CircleClose, Files, Cellphone, MagicStick, TrendCharts, Connection, Money
} from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { normalizeStatus } from '@/api/adapters'
import type { StatusResp } from '@/api/types'
import { useKeyboardShortcuts } from '@/composables/useKeyboardShortcuts'
import { useTheme } from '@/composables/useTheme'

const { showHelp, shortcuts } = useKeyboardShortcuts()
const { isDark, toggleDark } = useTheme()

const route = useRoute()
const router = useRouter()
const status = ref<StatusResp | null>(null)
const bots = ref<{ id: number, name?: string, qqId?: number }[]>([])
const activeBot = ref<number>(Number(localStorage.getItem('activeBotId')) || 0)
const title = computed(() => (route.meta.title as string) ?? '')
const cachedViews = ref<string[]>([])
const visitedRoutes = ref<Set<string>>(new Set())
const drawerVisible = ref(false)
const isMobile = ref(false)

const menuItems = [
  { path: '/dashboard', icon: 'Odometer', label: '仪表盘' },
  { path: '/chat', icon: 'ChatLineSquare', label: '聊天记录' },
  { path: '/account', icon: 'Cellphone', label: '机器人账号' },
  { path: '/persona', icon: 'User', label: '人设' },
  { path: '/memory', icon: 'Collection', label: '记忆' },
  { path: '/relation', icon: 'Connection', label: '关系与心情' },
  { path: '/knowledge', icon: 'Files', label: '知识库' },
  { path: '/emoji', icon: 'PictureRounded', label: '表情包' },
  { path: '/voice', icon: 'Microphone', label: '语音' },
  { path: '/push', icon: 'ChatDotRound', label: '主动消息' },
  { path: '/cost', icon: 'Money', label: '成本' },
  { path: '/review', icon: 'TrendCharts', label: '每日复盘' },
  { path: '/expression', icon: 'MagicStick', label: '人格表达' },
  { path: '/topic-block', icon: 'CircleClose', label: '话题屏蔽' },
  { path: '/settings', icon: 'Setting', label: '设置' },
  { path: '/logs', icon: 'Document', label: '日志' },
  { path: '/about', icon: 'InfoFilled', label: '关于' }
]

const routeLoaders: Record<string, () => Promise<any>> = {
  '/dashboard': () => import('@/views/DashboardView.vue'),
  '/chat': () => import('@/views/ChatView.vue'),
  '/persona': () => import('@/views/PersonaView.vue'),
  '/memory': () => import('@/views/MemoryView.vue'),
  '/relation': () => import('@/views/RelationView.vue'),
  '/emoji': () => import('@/views/EmojiView.vue'),
  '/voice': () => import('@/views/VoiceView.vue'),
  '/push': () => import('@/views/PushView.vue'),
  '/review': () => import('@/views/ReviewView.vue'),
  '/cost': () => import('@/views/CostView.vue'),
  '/expression': () => import('@/views/ExpressionView.vue'),
  '/topic-block': () => import('@/views/TopicBlockView.vue'),
  '/settings': () => import('@/views/SettingsView.vue'),
  '/logs': () => import('@/views/LogsView.vue'),
  '/about': () => import('@/views/AboutView.vue')
}

function preloadRoute(path: string) {
  const loader = routeLoaders[path]
  if (loader && !visitedRoutes.value.has(path)) {
    loader()
  }
}

function checkMobile() {
  isMobile.value = window.innerWidth <= 768
}

const routeToComponent: Record<string, string> = {
  dashboard: 'DashboardView',
  chat: 'ChatView',
  persona: 'PersonaView',
  memory: 'MemoryView',
  emoji: 'EmojiView',
  voice: 'VoiceView',
  push: 'PushView',
  review: 'ReviewView',
  expression: 'ExpressionView',
  'topic-block': 'TopicBlockView',
  settings: 'SettingsView',
  logs: 'LogsView',
  about: 'AboutView'
}

watch(() => route.path, (path) => {
  visitedRoutes.value.add(path)
  const routeName = route.name as string
  const componentName = routeToComponent[routeName]
  if (componentName && !cachedViews.value.includes(componentName)) {
    cachedViews.value.push(componentName)
  }
}, { immediate: true })

onMounted(async () => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
  try { status.value = normalizeStatus(await api<unknown>('/status')) } catch { /* 顶栏失败不阻塞页面 */ }
  try {
    const r = await api<{ items: { id: number, name?: string, qqId?: number }[] }>('/account/list')
    bots.value = r.items ?? []
    if (!bots.value.some(b => b.id === activeBot.value) && bots.value.length) {
      activeBot.value = bots.value[0].id
      localStorage.setItem('activeBotId', String(activeBot.value))
    }
  } catch { /* 忽略 */ }
})


onBeforeUnmount(() => {
  window.removeEventListener('resize', checkMobile)
})

function switchBot(id: number) {
  localStorage.setItem('activeBotId', String(id))
  window.location.reload()
}

async function logout() {
  try { await api('/auth/logout', { method: 'POST' }) } catch { /* 会话已失效也继续退出 */ }
  sessionStorage.removeItem('dsh-admin')
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside {
  background: var(--c-surface);
  border-right: 1px solid var(--c-border);
  display: flex; flex-direction: column;
}
.brand { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.brand-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--c-primary); }
.menu { border-right: none; flex: 1; }
.aside-foot { padding: 12px 20px; border-top: 1px solid var(--c-border); }
.header {
  display: flex; align-items: center; justify-content: space-between;
  background: transparent; border-bottom: 1px solid var(--c-border);
  padding-bottom: env(safe-area-inset-bottom);
}
.header-left { display: flex; align-items: center; gap: 8px; }
.user-select { width: 150px; }
.bot-select { width: 180px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.muted { color: var(--c-text-2); font-size: 13px; }
.main { padding: 24px; overflow: auto; padding-bottom: calc(24px + env(safe-area-inset-bottom)); }
.hamburger { margin-right: 4px; }

.mobile-drawer :deep(.el-drawer__header) {
  margin-bottom: 16px; padding: 20px;
  border-bottom: 1px solid var(--c-border);
}
.mobile-drawer :deep(.el-drawer__footer) {
  padding: 16px 20px; border-top: 1px solid var(--c-border);
}
.mobile-drawer :deep(.el-menu-item) {
  height: 48px; line-height: 48px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 120ms ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .main { padding: 16px; padding-bottom: calc(16px + env(safe-area-inset-bottom)); }
}

.shortcut-list { display: flex; flex-direction: column; gap: 10px; }
.shortcut-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 0; font-size: 13px;
}
.shortcut-item kbd {
  display: inline-block; padding: 2px 8px;
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-radius: 4px; font-family: monospace; font-size: 12px;
  color: var(--c-text);
}
</style>
