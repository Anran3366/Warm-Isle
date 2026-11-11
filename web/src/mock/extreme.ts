/**
 * 极端场景 Mock 数据（?mock=extreme 或 localStorage mock-scenario=extreme 时生效）
 */
import type {
  ChatMessage, Emoji, Fact, LogLine, Page, Persona, PromptPreview,
  PushConfig, PushRecord, Settings, StatusResp, Summary, TopicBlockConfig, UsagePoint, VoiceConfig
} from '@/api/types'
import { cloneTopicBlock, defaultTopicBlock } from './topicBlock'

const today = new Date()
const day = (offset: number) => {
  const d = new Date(today)
  d.setDate(d.getDate() + offset)
  return d.toISOString().slice(0, 10)
}

// 超长文本
const longText = '这是一段非常长的测试文本。'.repeat(80)

const status: StatusResp = {
  bot: { online: false, napcat: false, uptimeSec: 0 },
  today: { messages: 0, cost: 0, tokens: 0 },
  persona: null,
  memory: { facts: 0, pending: 0, lastExtractedAt: null }
}

const usage: UsagePoint[] = Array.from({ length: 30 }, (_, i) => ({
  date: day(i - 29),
  cost: Number((Math.random() * 2).toFixed(2)),
  tokens: Math.floor(Math.random() * 500000)
}))

const personas: Persona[] = [
  { id: 'p1', name: '小满', content: '# 人设\n\n' + longText, active: true }
]

const facts: Fact[] = Array.from({ length: 200 }, (_, i) => ({
  id: `f${i + 1}`,
  category: (['profile', 'preference', 'relationship', 'event', 'commitment'] as const)[i % 5],
  key: `事实键${i + 1}`,
  value: i % 10 === 0 ? longText : `事实值${i + 1}`,
  confidence: Number((0.5 + Math.random() * 0.5).toFixed(2)),
  status: (['active', 'outdated', 'disputed', 'pending'] as const)[i % 4],
  firstSeen: day(-Math.floor(Math.random() * 90)),
  lastConfirmed: day(-Math.floor(Math.random() * 30))
}))

const summaries: Summary[] = Array.from({ length: 30 }, (_, i) => ({
  date: day(-i),
  text: longText.slice(0, 200)
}))

const messages: ChatMessage[] = Array.from({ length: 200 }, (_, i) => ({
  id: `m${i + 1}`,
  role: i % 2 === 0 ? 'user' : 'assistant',
  content: i % 15 === 0 ? longText : `消息内容${i + 1}`,
  kind: 'text',
  createdAt: `${day(-Math.floor(i / 20))}T${String(8 + (i % 14)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}:00+08:00`
}))

const emoji: Emoji[] = Array.from({ length: 50 }, (_, i) => ({
  id: `e${i + 1}`,
  url: i % 8 === 0 ? '/files/emoji/broken.png' : `https://placehold.co/160x160/fbe6df/2f2a25?text=%E8%A1%A8%E6%83%85${i + 1}`,
  emoTags: Array.from({ length: i % 12 + 1 }, (_, j) => `标签${j + 1}`),
  sceneTags: Array.from({ length: i % 8 + 1 }, (_, j) => `场景${j + 1}`),
  enabled: i % 3 !== 0,
  source: i % 2 === 0 ? 'upload' : 'auto'
}))

const voice: VoiceConfig = { provider: 'mimo', voice: '茉莉', speed: 1.0, enabled: false, chatProbability: 0, dailyLimit: 0 }

const push: PushConfig = {
  enabled: false,
  morning: { enabled: false, from: '08:30', to: '09:30' },
  evening: { enabled: false, from: '22:30', to: '23:25' },
  idleHours: 6,
  nudgeAfterHours: 6,
  suspendAfterHours: 12,
  dailyLimit: 3,
  quiet: { from: '23:30', to: '08:00' },
  state: {
    suspended: true,
    unanswered: 2,
    grievanceLevel: 1,
    teaseNext: true,
    lastUserAt: null,
    lastProactiveAt: null
  }
}

const pushHistory: PushRecord[] = []

let settings: Settings = {
  model: 'deepseek-flash',
  temperature: 0.8,
  dailyBudget: 1.0,
  contextRounds: 20,
  maxTokens: 400,
  vision: { enabled: false, maxPerMessage: 3, maxSize: 1024, dailyLimit: 0, emojiAutoImport: false },
  reply: { maxChars: 60, splitDelayMs: 1200, typingMs: 600, adaptive: true }
}

const logs: LogLine[] = []

// 极端场景：屏蔽关闭、自定义话题很多，用来压一压标签换行
const extremeTopicBlock: TopicBlockConfig = {
  ...defaultTopicBlock(),
  enabled: false,
  customTopics: Array.from({ length: 12 }, (_, i) => `不想聊的话题${i + 1}`)
}
let topicBlock: TopicBlockConfig = extremeTopicBlock

const promptPreview: PromptPreview = {
  persona: longText.slice(0, 500),
  facts: longText.slice(0, 300),
  memories: longText.slice(0, 200),
  history: longText.slice(0, 800),
  user: '今天好累',
  totalTokens: 15000
}

function pageOf<T>(items: T[], path: string): Page<T> {
  const q = new URLSearchParams(path.split('?')[1] ?? '')
  const p = Number(q.get('page') ?? 1)
  const s = Number(q.get('size') ?? 20)
  const start = (p - 1) * s
  return { items: items.slice(start, start + s), total: items.length }
}

export function mockForExtreme<T>(path: string, method: string, body?: unknown): T {
  const p = path.split('?')[0]
  const w = (v: unknown) => v as T

  if (p === '/auth/login') return w({ ok: true })
  if (p === '/auth/logout') return w({ ok: true })
  if (p === '/status') return w(status)
  if (p === '/usage') return w(usage)
  if (p === '/personas' && method === 'GET') return w(personas)
  if (p.startsWith('/personas')) return w({ ok: true })
  if (p === '/prompt/preview') return w(promptPreview)
  if (p === '/facts/pending') return w({ items: facts.filter(f => f.status === 'pending'), total: 50 })
  if (p === '/facts') return w(pageOf(facts, path))
  if (p.startsWith('/facts')) return w({ ok: true })
  if (p === '/summaries') return w(summaries)
  if (p === '/messages') return w(pageOf(messages, path))
  if (p === '/memory/extract') return w({ ok: true, jobId: 'job-1' })
  if (p === '/emoji') return w(pageOf(emoji, path))
  if (p.startsWith('/emoji')) return w({ ok: true })
  if (p === '/voice/config') return w(voice)
  if (p === '/voice/test') return w({ fileUrl: '/files/tts/test.silk' })
  if (p === '/push/config') return w(push)
  if (p === '/push/history') return w(pageOf(pushHistory, path))
  if (p === '/push/test') return w({ ok: true })
  if (p === '/settings') {
    if (method === 'PUT' && body) {
      const next = body as Partial<Settings>
      settings = {
        ...settings,
        ...next,
        vision: { ...settings.vision, ...(next.vision ?? {}) }
      }
    }
    return w(settings)
  }
  if (p === '/topic-block') {
    if (method === 'PUT' && body) topicBlock = cloneTopicBlock(body as TopicBlockConfig)
    return w(cloneTopicBlock(topicBlock))
  }
  if (p === '/logs') return w(logs)
  return w({})
}
