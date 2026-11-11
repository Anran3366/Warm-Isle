/** 与 docs/api-contract.md 一一对应；字段名不可随意改动 */

export type FactCategory = 'profile' | 'preference' | 'relationship' | 'event' | 'commitment'
export type FactStatus = 'active' | 'outdated' | 'disputed' | 'pending'
export type PushType = 'morning' | 'evening' | 'silence' | 'followup' | 'nudge'

export interface StatusResp {
  bot: { online: boolean; napcat: boolean; uptimeSec: number }
  today: { messages: number; cost: number; tokens: number }
  persona: { id: string; name: string } | null
  memory: { facts: number; pending: number; lastExtractedAt: string | null }
}

export interface UsagePoint { date: string; cost: number; tokens: number }

export interface Persona { id: string; name: string; content: string; active: boolean }

export interface Fact {
  id: string
  qqId?: string
  nickname?: string | null
  category: FactCategory
  key: string
  value: string
  confidence: number
  status: FactStatus
  firstSeen: string
  lastConfirmed: string
}

export interface Summary { date: string; text: string }

export interface ChatMessage {
  emojiId?: number | null
  voiceFile?: string | null
  id: string
  qqId?: string
  role: 'user' | 'assistant'
  content: string
  kind: string
  createdAt: string
}

export interface Emoji {
  id: string
  url: string
  emoTags: string[]
  sceneTags: string[]
  /** 给模型看的一句话描述（自动入库时由视觉模型生成） */
  description?: string
  enabled: boolean
  /** upload: 后台上传；auto: 聊天中收到并自动入库 */
  source: 'upload' | 'auto'
  status?: 'approved' | 'pending' | 'rejected'
  sentCount?: number
  lastSentAt?: string
}

export interface VoiceProfileView {
  id: string
  name: string
  seconds: number
  sampleRate: number
  /** ok: 质检通过；warn: 能用但有提醒；reject: 已拒绝（不会入库） */
  verdict: 'ok' | 'warn' | 'reject'
  issues: string[]
  transcript?: string | null
  createdAt: string
}

export interface VoiceCloneInfo {
  enabled: boolean
  hasRef: boolean
  name: string
  activeId?: string | null
  profiles?: VoiceProfileView[]
}

export interface AudioAnalysis {
  seconds: number
  sampleRate: number
  channels: number
  codec: string
  meanDb: number
  maxDb: number
  silenceRatio: number
  transcript?: string | null
  verdict: 'ok' | 'warn' | 'reject'
  issues: string[]
}

export interface VoiceConfig {
  provider: string
  voice: string
  speed: number
  chatProbability: number
  dailyLimit: number
  styleLlm?: boolean
  enabled: boolean
  configured?: boolean
  model?: string
  format?: string
  voices?: string[]
  nightFromHour?: number
  clone?: VoiceCloneInfo
}

export interface PushWindow {
  enabled: boolean
  from: string
  to: string
}

export interface PushState {
  suspended: boolean
  unanswered: number
  grievanceLevel: number
  teaseNext: boolean
  lastUserAt: string | null
  lastProactiveAt: string | null
  mode?: string
  mood?: string | null
  nextEligibleAt?: string | null
  dndUntil?: string | null
  reentryStage?: number
}

export interface PushConfig {
  enabled: boolean
  morning: PushWindow
  evening: PushWindow
  idleHours: number
  nudgeAfterHours: number
  suspendAfterHours: number
  dailyLimit: number
  quiet: { from: string; to: string }
  nudgeProbability?: number
  nudgeMinHours?: number
  nudgeMaxHours?: number
  backoffMinDays?: number
  backoffMaxDays?: number
  reentryMinDays?: number
  reentryMaxDays?: number
  morningProbability?: number
  eveningProbability?: number
  lowCostChance?: number
  maxBubbles?: number
  state?: PushState
}

export interface PushRecord {
  id: string
  type: PushType
  content: string
  sentAt: string
  replied?: boolean
}

export interface VisionConfig {
  enabled: boolean
  maxPerMessage: number
  maxSize: number
  dailyLimit: number
  emojiAutoImport: boolean
}

export interface ReplyConfig {
  /** 单条最大字数，超过就拆条 */
  maxChars: number
  /** 两条之间的间隔（毫秒） */
  splitDelayMs: number
  /** 发送前的基础延迟（模拟打字，毫秒） */
  typingMs: number
  /** 按对方消息长短自适应 */
  adaptive: boolean
}

export interface Settings {
  model: string
  temperature: number
  dailyBudget: number
  contextRounds: number
  maxTokens: number
  vision: VisionConfig
  reply: ReplyConfig
}

/** 话题屏蔽：命中后机器人的处理方式 */
export type TopicAction = 'deflect' | 'refuse' | 'silent'

/** 预设话题（色情、政治等），关键词可由用户增删 */
export interface TopicPreset {
  id: string
  label: string
  desc: string
  keywords: string[]
  enabled: boolean
}

export interface TopicBlockConfig {
  enabled: boolean
  presets: TopicPreset[]
  /** 用户自定义的不聊话题（按「消息包含该词」匹配） */
  customTopics: string[]
  action: TopicAction
  /** 命中后的参考话术，支持 {topic} 占位符 */
  replyTemplate: string
}

export interface LogLine {
  ts: string
  level: 'info' | 'warn' | 'error' | 'debug'
  logger?: string
  msg: string
}

export interface PromptPreview {
  persona: string
  facts: string
  memories: string
  history: string
  user: string
  totalTokens: number
}

export interface Page<T> { items: T[]; total: number }
