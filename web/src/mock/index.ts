/**
 * Mock 数据（VITE_USE_MOCK=true 时生效）
 * Qoder 请勿把 Mock 逻辑写进页面 —— 一律通过 client.ts 走。
 */
import type {
  ChatMessage, Emoji, Fact, LogLine, Page, Persona, PromptPreview,
  PushConfig, PushRecord, Settings, StatusResp, Summary, TopicBlockConfig, UsagePoint,
  VoiceConfig, VoiceProfileView
} from '@/api/types'
import { cloneTopicBlock, defaultTopicBlock } from './topicBlock'

const today = new Date()
const day = (offset: number) => {
  const d = new Date(today)
  d.setDate(d.getDate() + offset)
  return d.toISOString().slice(0, 10)
}

const status: StatusResp = {
  bot: { online: true, napcat: true, uptimeSec: 129600 },
  today: { messages: 87, cost: 0.18, tokens: 120345 },
  persona: { id: 'p1', name: '小满' },
  memory: { facts: 42, pending: 3, lastExtractedAt: `${day(0)}T12:00:05+08:00` }
}

const allUsage: UsagePoint[] = Array.from({ length: 30 }, (_, i) => ({
  date: day(i - 29),
  cost: Number((0.08 + Math.random() * 0.22).toFixed(2)),
  tokens: 60000 + Math.floor(Math.random() * 90000)
}))

const personas: Persona[] = [
  { id: 'p1', name: '小满', content: '# 小满\n\n- 24 岁，杭州\n- 说话简短，偶尔撒娇\n- 会记住你说过的事', active: true },
  { id: 'p2', name: '深夜版', content: '# 深夜小满\n\n- 语速慢，多用“嗯…”\n- 不主动给建议', active: false }
]

const facts: Fact[] = [
  { id: 'f1', category: 'profile', key: '称呼', value: '阿满', confidence: 0.98, status: 'active', firstSeen: '2026-03-02', lastConfirmed: day(0) },
  { id: 'f2', category: 'profile', key: '城市', value: '杭州', confidence: 0.95, status: 'active', firstSeen: '2026-03-02', lastConfirmed: day(-2) },
  { id: 'f3', category: 'preference', key: '咖啡', value: '喜欢冰美式，不加糖', confidence: 0.82, status: 'active', firstSeen: '2026-05-11', lastConfirmed: day(-5) },
  { id: 'f4', category: 'event', key: '面试', value: '10月22日 字节二面', confidence: 0.9, status: 'active', firstSeen: day(0), lastConfirmed: day(0) },
  { id: 'f5', category: 'commitment', key: '提醒', value: '11月1日提醒交材料', confidence: 0.88, status: 'active', firstSeen: day(-1), lastConfirmed: day(-1) },
  { id: 'f6', category: 'relationship', key: '家人', value: '有个妹妹读高三', confidence: 0.7, status: 'pending', firstSeen: day(-3), lastConfirmed: day(-3) }
]

const summaries: Summary[] = [
  { date: day(0), text: '今天聊了面试准备和晚饭，情绪偏低落。' },
  { date: day(-1), text: '提到最近失眠，聊了很久工作压力。' }
]

const messages: ChatMessage[] = [
  { id: 'm1', role: 'user', content: '今天好累', kind: 'text', createdAt: `${day(0)}T21:12:00+08:00` },
  { id: 'm2', role: 'assistant', content: '怎么了，工作上的事吗？', kind: 'text', createdAt: `${day(0)}T21:12:20+08:00` },
  { id: 'm3', role: 'user', content: '嗯，又加班了，项目赶进度', kind: 'text', createdAt: `${day(0)}T21:13:00+08:00` },
  { id: 'm4', role: 'assistant', content: '辛苦了，记得吃点东西再休息', kind: 'text', createdAt: `${day(0)}T21:13:30+08:00` },
  { id: 'm5', role: 'user', content: '好的，你也早点休息', kind: 'text', createdAt: `${day(0)}T21:14:00+08:00` },
  { id: 'm6', role: 'assistant', content: '嗯嗯，晚安呀', kind: 'text', createdAt: `${day(0)}T21:14:10+08:00` },
  { id: 'm7', role: 'user', content: '面试准备得怎么样了？', kind: 'text', createdAt: `${day(-1)}T19:00:00+08:00` },
  { id: 'm8', role: 'assistant', content: '算法题刷了几道，八股文还在背', kind: 'text', createdAt: `${day(-1)}T19:00:30+08:00` },
  { id: 'm9', role: 'user', content: '加油，你可以的', kind: 'text', createdAt: `${day(-1)}T19:01:00+08:00` },
  { id: 'm10', role: 'assistant', content: '谢谢你的鼓励！', kind: 'text', createdAt: `${day(-1)}T19:01:10+08:00` },
  { id: 'm11', role: 'user', content: '最近失眠好严重', kind: 'text', createdAt: `${day(-2)}T23:30:00+08:00` },
  { id: 'm12', role: 'assistant', content: '是不是压力太大了？试试睡前不看手机', kind: 'text', createdAt: `${day(-2)}T23:30:30+08:00` },
  { id: 'm13', role: 'user', content: '嗯，我试试', kind: 'text', createdAt: `${day(-2)}T23:31:00+08:00` },
]

const emoji: Emoji[] = Array.from({ length: 12 }, (_, i) => ({
  id: `e${i + 1}`,
  url: `https://placehold.co/160x160/fbe6df/2f2a25?text=%E8%A1%A8%E6%83%85${i + 1}`,
  emoTags: [['开心', '难过', '无语', '撒娇'][i % 4]],
  sceneTags: [['打招呼', '晚安', '安慰'][i % 3]],
  enabled: i % 5 !== 0,
  source: i % 3 === 0 ? 'auto' : 'upload'
}))

const voice: VoiceConfig = { provider: 'mimo', voice: '茉莉', speed: 1.0, enabled: true, chatProbability: 0.2, dailyLimit: 3 }

/** 音色库（Mock）：上传不真的读文件，返回固定样例 */
let voiceProfiles: VoiceProfileView[] = [
  {
    id: 'v1',
    name: '我的音色',
    seconds: 11.2,
    sampleRate: 24000,
    verdict: 'ok',
    issues: [],
    transcript: '今天天气不错，我们出去走走吧。',
    createdAt: '2026-10-12 21:40'
  }
]
let voiceActiveId: string | null = 'v1'

function voiceConfigWithClone(): VoiceConfig {
  const active = voiceProfiles.find((p) => p.id === voiceActiveId) ?? null
  return {
    ...voice,
    clone: {
      enabled: Boolean(active),
      hasRef: Boolean(active),
      name: active?.name ?? '',
      activeId: voiceActiveId,
      profiles: voiceProfiles
    }
  }
}

const push: PushConfig = {
  enabled: true,
  morning: { enabled: true, from: '08:30', to: '09:30' },
  evening: { enabled: true, from: '22:30', to: '23:25' },
  idleHours: 6,
  nudgeAfterHours: 6,
  suspendAfterHours: 12,
  dailyLimit: 3,
  quiet: { from: '23:30', to: '08:00' },
  state: {
    suspended: false,
    unanswered: 0,
    grievanceLevel: 0,
    teaseNext: false,
    lastUserAt: `${day(0)}T21:14:10+08:00`,
    lastProactiveAt: `${day(-1)}T09:12:00+08:00`
  }
}

const pushHistory: PushRecord[] = [
  { id: 'x1', type: 'nudge', content: '昨天烧到 38 度，今天好点了吗', sentAt: `${day(-1)}T09:12:00+08:00` },
  { id: 'x2', type: 'silence', content: '半天没动静，忙什么呢', sentAt: `${day(-2)}T16:40:00+08:00` }
]

let expressionConfig = {
  enabled: false,
  qzone: {
    enabled: false,
    autoPost: false,
    audienceMode: 'auto',
    publicUgcRight: 4,
    maxPerDay: 1,
    includeImages: false,
    quiet: { from: '23:30', to: '08:00' }
  },
  friends: { autoCategory: true, refreshHours: 6, nativeSync: false }
}

const expressionHistory = [
  {
    id: 1,
    content: '今天路过一家咖啡店，坐了会儿。',
    audience: 'public',
    status: 'published',
    createdAt: `${day(-1)} 15:20:00`,
    publishedAt: `${day(-1)} 15:20:00`
  }
]

const friendProfiles = [
  {
    qqId: 10001,
    nickname: '阿满',
    remark: '小满',
    nativeCategoryName: '我的好友',
    autoCategory: '特别关心',
    messageCount: 286,
    lastMessageAt: `${day(0)} 21:14:10`,
    mood: 0.42,
    reason: '消息 286 条，最近今天'
  },
  {
    qqId: 10002,
    nickname: '小林',
    remark: '小林',
    nativeCategoryName: '同事',
    autoCategory: '常聊',
    messageCount: 38,
    lastMessageAt: `${day(-3)} 18:02:00`,
    mood: -0.1,
    reason: '消息 38 条，最近三天前'
  }
]

let settings: Settings = {
  model: 'deepseek-flash',
  temperature: 0.8,
  dailyBudget: 1.0,
  contextRounds: 20,
  maxTokens: 400,
  vision: { enabled: true, maxPerMessage: 3, maxSize: 1024, dailyLimit: 30, emojiAutoImport: true },
  reply: { maxChars: 60, splitDelayMs: 1200, typingMs: 600, adaptive: true }
}

let topicBlock: TopicBlockConfig = defaultTopicBlock()

const logs: LogLine[] = [
  { ts: `${day(0)}T12:00:05+08:00`, level: 'info', msg: '记忆抽取完成：新增 3 条事实' },
  { ts: `${day(0)}T09:12:00+08:00`, level: 'info', msg: '主动消息已发送（followup）' },
  { ts: `${day(-1)}T23:02:00+08:00`, level: 'warn', msg: '模型响应超时，已重试成功' }
]

const promptPreview: PromptPreview = {
  persona: '# 小满\n\n24 岁，杭州……',
  facts: '称呼：阿满；城市：杭州；咖啡：冰美式',
  memories: '（昨日摘要）提到面试和失眠',
  history: 'user: 今天好累\nassistant: 怎么了…',
  user: '今天好累',
  totalTokens: 3120
}

function pageOf<T>(items: T[], path: string): Page<T> {
  const q = new URLSearchParams(path.split('?')[1] ?? '')
  const p = Number(q.get('page') ?? 1)
  const s = Number(q.get('size') ?? 20)
  const start = (p - 1) * s
  return { items: items.slice(start, start + s), total: items.length }
}

export function mockFor<T>(path: string, method: string, body?: unknown): T {
  const p = path.split('?')[0]
  const w = (v: unknown) => v as T

  if (p === '/auth/login') return w({ ok: true })
  if (p === '/auth/logout') return w({ ok: true })
  if (p === '/status') return w(status)
  if (p === '/usage') {
    const q = new URLSearchParams(path.split('?')[1] ?? '')
    const from = q.get('from') ?? ''
    const to = q.get('to') ?? ''
    let data = allUsage
    if (from) data = data.filter(u => u.date >= from)
    if (to) data = data.filter(u => u.date <= to)
    return w(data)
  }
  if (p === '/personas' && method === 'GET') return w(personas)
  if (p.startsWith('/personas')) return w({ ok: true })
  if (p === '/prompt/preview') return w(promptPreview)
  if (p === '/facts/pending') return w({ items: facts.filter(f => f.status === 'pending'), total: 1 })
  if (p === '/facts') return w(pageOf(facts, path))
  if (p.startsWith('/facts')) return w({ ok: true })
  if (p === '/summaries') return w(summaries)
  if (p === '/messages') return w(pageOf(messages, path))
  if (p === '/memory/extract') return w({ ok: true, jobId: 'job-1' })
  if (p === '/emoji') return w(pageOf(emoji, path))
  if (p.startsWith('/emoji')) return w({ ok: true })
  if (p === '/voice/config') return w(voiceConfigWithClone())
  if (p === '/voice/clone' && method === 'POST') {
    const profile: VoiceProfileView = {
      id: 'v' + (voiceProfiles.length + 1),
      name: '我的新音色',
      seconds: 10.4,
      sampleRate: 24000,
      verdict: 'ok',
      issues: [],
      transcript: '这是一段用来验证音色的示例录音。',
      createdAt: new Date().toISOString().slice(0, 16).replace('T', ' ')
    }
    voiceProfiles = [profile, ...voiceProfiles]
    voiceActiveId = profile.id
    return w({
      ok: true,
      profile,
      analysis: {
        seconds: 10.4, sampleRate: 24000, channels: 1, codec: 'pcm_s16le',
        meanDb: -21, maxDb: -3, silenceRatio: 0.08,
        transcript: profile.transcript, verdict: 'ok', issues: []
      },
      previewUrl: '/files/tts/clone-preview.wav',
      previewError: null
    })
  }
  if (p === '/voice/clones') return w({ ok: true, activeId: voiceActiveId, profiles: voiceProfiles })
  const cloneMatch = p.match(/^\/voice\/clones\/([^/?]+)(?:\/(activate|preview))?$/)
  if (cloneMatch) {
    const id = decodeURIComponent(cloneMatch[1])
    const action = cloneMatch[2]
    if (method === 'PUT' && action === 'activate') {
      voiceActiveId = id
      return w({ ok: true, activeId: id })
    }
    if (method === 'POST' && action === 'preview') {
      return w({ ok: true, fileUrl: '/files/tts/clone-preview.wav' })
    }
    if (method === 'DELETE') {
      voiceProfiles = voiceProfiles.filter((x) => x.id !== id)
      if (voiceActiveId === id) voiceActiveId = voiceProfiles[0]?.id ?? null
      return w({ ok: true, activeId: voiceActiveId, profiles: voiceProfiles })
    }
  }
  if (p === '/voice/clone' && method === 'DELETE') {
    if (voiceActiveId) voiceProfiles = voiceProfiles.filter((x) => x.id !== voiceActiveId)
    voiceActiveId = voiceProfiles[0]?.id ?? null
    return w({ ok: true })
  }
  if (p === '/voice/test') return w({ fileUrl: '/files/tts/test.silk' })
  if (p === '/push/config') return w(push)
  if (p === '/push/history') return w(pageOf(pushHistory, path))
  if (p === '/push/test') return w({ ok: true })
  if (p === '/expression/config') {
    if (method === 'PUT' && body) {
      const next = body as any
      expressionConfig = {
        ...expressionConfig,
        ...next,
        qzone: { ...expressionConfig.qzone, ...(next.qzone ?? {}), quiet: { ...expressionConfig.qzone.quiet, ...(next.qzone?.quiet ?? {}) } },
        friends: { ...expressionConfig.friends, ...(next.friends ?? {}) }
      }
    }
    return w(expressionConfig)
  }
  if (p === '/expression/qzone/draft') {
    const b = (body ?? {}) as any
    const targeted = Boolean(b.qqId)
    return w({
      content: b.hint ? `今天有点想说说：${b.hint}` : '今天路过一家咖啡店，坐了会儿。',
      mood: 0.3,
      moodText: '平静',
      audience: targeted ? 'targeted' : 'public',
      ugcRight: targeted ? 16 : 4,
      targetUins: targeted ? [Number(b.qqId)] : [],
      reason: targeted ? '内容有明确指向，只给相关用户看' : '普通生活分享'
    })
  }
  if (p === '/expression/qzone/publish') {
    const b = (body ?? {}) as any
    const row = {
      id: expressionHistory.length + 1,
      content: String(b.content ?? ''),
      audience: String(b.audience ?? 'public'),
      status: 'published',
      createdAt: `${day(0)} 20:10:00`,
      publishedAt: `${day(0)} 20:10:00`
    }
    expressionHistory.unshift(row)
    return w({ ok: true, status: 'published', tid: 'mock_' + row.id, audience: row.audience })
  }
  if (p === '/expression/qzone/history') return w(pageOf(expressionHistory, path))
  if (p === '/friends') return w({ items: friendProfiles, counts: { '特别关心': 1, '常聊': 1 } })
  if (p === '/friends/refresh') return w({ ok: true, friends: friendProfiles.length, updated: friendProfiles.length, nativeSync: false })
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
