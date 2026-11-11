import { createRouter, createWebHashHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

// 用 hash 模式：产物挂在 jar 的 /static 下，避免服务端路由配置
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/setup', name: 'setup', component: () => import('@/views/SetupView.vue'), meta: { public: true } },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/m/:token', name: 'mobile-approve', component: () => import('@/views/MobileApproveView.vue'), meta: { public: true } },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '仪表盘' } },
        { path: 'persona', name: 'persona', component: () => import('@/views/PersonaView.vue'), meta: { title: '人设' } },
        { path: 'memory', name: 'memory', component: () => import('@/views/MemoryView.vue'), meta: { title: '记忆' } },
        { path: 'memory/star', name: 'memory-star', component: () => import('@/views/MemoryStarMap.vue'), meta: { title: '记忆星图' } },
        { path: 'account', name: 'account', component: () => import('@/views/AccountView.vue'), meta: { title: '机器人账号' } },
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { title: '知识库' } },
        { path: 'emoji', name: 'emoji', component: () => import('@/views/EmojiView.vue'), meta: { title: '表情包' } },
        { path: 'voice', name: 'voice', component: () => import('@/views/VoiceView.vue'), meta: { title: '语音' } },
        { path: 'push', name: 'push', component: () => import('@/views/PushView.vue'), meta: { title: '主动消息' } },
        { path: 'cost', name: 'cost', component: () => import('@/views/CostView.vue'), meta: { title: '成本' } },
        { path: 'review', name: 'review', component: () => import('@/views/ReviewView.vue'), meta: { title: '每日复盘' } },
        { path: 'relation', name: 'relation', component: () => import('@/views/RelationView.vue'), meta: { title: '关系与心情' } },
        { path: 'expression', name: 'expression', component: () => import('@/views/ExpressionView.vue'), meta: { title: '人格表达' } },
        { path: 'topic-block', name: 'topic-block', component: () => import('@/views/TopicBlockView.vue'), meta: { title: '话题屏蔽' } },
        { path: 'plugins', name: 'plugins', component: () => import('@/views/PluginsView.vue'), meta: { title: '插件商店' } },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置' } },
        { path: 'logs', name: 'logs', component: () => import('@/views/LogsView.vue'), meta: { title: '日志' } },
        { path: 'about', name: 'about', component: () => import('@/views/AboutView.vue'), meta: { title: '关于' } },
        { path: 'chat', name: 'chat', component: () => import('@/views/ChatView.vue'), meta: { title: '聊天记录' } }
      ]
    }
  ]
})

let setupPromise: Promise<boolean> | null = null

/** 初始化完成后清掉缓存，否则守卫会继续按“未配置”跳回 setup。 */
export function resetSetupCheck() {
  setupPromise = null
}

/** 后端未配置密码时，所有页面先跳到初始化页；失败时不阻塞（例如后端刚重启）。 */
function setupRequired(): Promise<boolean> {
  if (!setupPromise) {
    setupPromise = fetch('/api/setup/status', { credentials: 'include', cache: 'no-store' })
      .then(r => r.ok ? r.json() : { required: false })
      .then(d => !!d.required)
      .catch(() => false)
  }
  return setupPromise
}

router.beforeEach(async (to) => {
  const required = await setupRequired()
  if (required && to.name !== 'setup' && sessionStorage.getItem('dsh-admin') !== '1') {
    return { name: 'setup' }
  }
  if (!required && to.name === 'setup') return { name: 'login' }

  const logged = sessionStorage.getItem('dsh-admin') === '1'
  if (!to.meta.public && !logged) return { name: 'login' }
  if (to.name === 'login' && logged) return { name: 'dashboard' }
  return true
})

export default router
