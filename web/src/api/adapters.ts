import type { ChatMessage, Emoji, LogLine, Settings, StatusResp } from './types'

/**
 * 后端 /api/status 目前有两种形态：
 * - 契约形态：{ bot, today, persona, memory }
 * - 内部形态：{ connected, todayMessages, persona(string), memory{...}, usage{...}, uptimeSeconds }
 * 这里统一转成前端契约形态，兼容后端分批改造。
 */
export interface BackendStatus {
  ok?: boolean
  connected?: boolean
  selfId?: number
  todayMessages?: number
  activeUsers?: number
  model?: string
  persona?: string | { id?: string; name?: string } | null
  memory?: {
    messagesTotal?: number
    messagesToday?: number
    users?: number
    factsActive?: number
    facts?: number
    pending?: number
    lastExtractedAt?: string | null
  }
  usage?: { todayTokens?: number; todayCostCny?: number }
  uptimeSeconds?: number
}

function decodeUtf8Bytes(bytes: number[]): string | null {
  let out = ''
  for (let i = 0; i < bytes.length;) {
    const first = bytes[i]
    if (first < 0x80) {
      out += String.fromCharCode(first)
      i += 1
      continue
    }
    let extra = 0
    let codePoint = 0
    if ((first & 0xe0) === 0xc0) {
      extra = 1
      codePoint = first & 0x1f
    } else if ((first & 0xf0) === 0xe0) {
      extra = 2
      codePoint = first & 0x0f
    } else if ((first & 0xf8) === 0xf0) {
      extra = 3
      codePoint = first & 0x07
    } else {
      return null
    }
    if (i + extra >= bytes.length) return null
    for (let j = 1; j <= extra; j++) {
      const next = bytes[i + j]
      if ((next & 0xc0) !== 0x80) return null
      codePoint = (codePoint << 6) | (next & 0x3f)
    }
    out += String.fromCodePoint(codePoint)
    i += extra + 1
  }
  return out
}

/**
 * 修复「UTF-8 被当成 Latin-1 读」造成的乱码，例如 å°æ»¡ → 小满。
 * 只在重新解码后能得到中文时才替换，避免误伤正常的西文名字。
 */
export function repairMojibake(value: string): string {
  if (!value || !/[\u0080-\u00ff]/.test(value)) return value
  const decoded = decodeUtf8Bytes([...value].map(ch => ch.charCodeAt(0) & 0xff))
  return decoded && /[\u4e00-\u9fff]/.test(decoded) ? decoded : value
}

export const DEFAULT_SETTINGS: Settings = {
  model: 'deepseek-flash',
  temperature: 0.8,
  dailyBudget: 1.0,
  contextRounds: 10,
  maxTokens: 400,
  vision: {
    enabled: true,
    maxPerMessage: 3,
    maxSize: 1024,
    dailyLimit: 30,
    emojiAutoImport: true
  },
  reply: {
    maxChars: 60,
    splitDelayMs: 1200,
    typingMs: 600,
    adaptive: true
  }
}

function num(value: unknown, fallback: number): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

/** 后端可能少给字段或带额外字段（如 maxTokens），这里补全成前端 Settings */
export function normalizeSettings(raw: unknown): Settings {
  const value = (raw ?? {}) as Partial<Settings> & {
    vision?: Partial<Settings['vision']>
    reply?: Partial<Settings['reply']>
  }
  const vision: Partial<Settings['vision']> = value.vision ?? {}
  const reply: Partial<Settings['reply']> = value.reply ?? {}
  return {
    model: typeof value.model === 'string' && value.model.trim() ? value.model : DEFAULT_SETTINGS.model,
    temperature: num(value.temperature, DEFAULT_SETTINGS.temperature),
    dailyBudget: num(value.dailyBudget, DEFAULT_SETTINGS.dailyBudget),
    contextRounds: num(value.contextRounds, DEFAULT_SETTINGS.contextRounds),
    maxTokens: num(value.maxTokens, DEFAULT_SETTINGS.maxTokens),
    vision: {
      enabled: bool(vision.enabled, DEFAULT_SETTINGS.vision.enabled),
      maxPerMessage: num(vision.maxPerMessage, DEFAULT_SETTINGS.vision.maxPerMessage),
      maxSize: num(vision.maxSize, DEFAULT_SETTINGS.vision.maxSize),
      dailyLimit: num(vision.dailyLimit, DEFAULT_SETTINGS.vision.dailyLimit),
      emojiAutoImport: bool(vision.emojiAutoImport, DEFAULT_SETTINGS.vision.emojiAutoImport)
    },
    reply: {
      maxChars: num(reply.maxChars, DEFAULT_SETTINGS.reply.maxChars),
      splitDelayMs: num(reply.splitDelayMs, DEFAULT_SETTINGS.reply.splitDelayMs),
      typingMs: num(reply.typingMs, DEFAULT_SETTINGS.reply.typingMs),
      adaptive: bool(reply.adaptive, DEFAULT_SETTINGS.reply.adaptive)
    }
  }
}
function splitTags(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(v => String(v).trim()).filter(Boolean)
  if (typeof value === 'string') {
    return value.split(/[,，\s]+/).map(v => v.trim()).filter(Boolean)
  }
  return []
}

/** 后端 emoTags/sceneTags 是逗号字符串，前端统一成数组 */
export function normalizeEmoji(raw: unknown): Emoji {
  const value = (raw ?? {}) as Record<string, any>
  const id = String(value.id ?? '')
  return {
    id,
    url: String(value.url ?? `/api/emoji/${id}/file`),
    emoTags: splitTags(value.emoTags),
    sceneTags: splitTags(value.sceneTags),
    description: String(value.description ?? ''),
    enabled: Boolean(value.enabled),
    source: value.source === 'auto' ? 'auto' : 'upload',
    status: value.status ?? 'approved',
    sentCount: Number(value.sentCount ?? 0)
  }
}

export function normalizeEmojis(raw: unknown[]): Emoji[] {
  return raw.map(normalizeEmoji)
}

/** 后端日志级别是大写，前端统一成小写并保留 logger */
export function normalizeLog(raw: unknown): LogLine {
  const value = (raw ?? {}) as Record<string, any>
  const level = String(value.level ?? '').toLowerCase()
  return {
    ts: String(value.ts ?? ''),
    level: level === 'warn' || level === 'error' || level === 'debug' ? level : 'info',
    logger: value.logger ? String(value.logger) : undefined,
    msg: String(value.msg ?? '')
  }
}

export function normalizeLogs(raw: unknown[]): LogLine[] {
  return raw.map(normalizeLog)
}

/** 后端 messages.createdAt 是 "yyyy-MM-dd HH:mm:ss"，浏览器解析成 ISO 更稳 */
export function normalizeMessage(raw: unknown): ChatMessage {
  const value = (raw ?? {}) as Record<string, any>
  const createdAt = String(value.createdAt ?? '')
  return {
    id: String(value.id ?? ''),
    qqId: value.qqId != null ? String(value.qqId) : undefined,
    role: value.role === 'assistant' ? 'assistant' : 'user',
    content: String(value.content ?? ''),
    kind: String(value.kind ?? 'text'),
    emojiId: value.emojiId != null ? Number(value.emojiId) : null,
    voiceFile: value.voiceFile ? String(value.voiceFile) : null,
    createdAt: createdAt.includes('T') ? createdAt : createdAt.replace(' ', 'T')
  }
}

export function normalizeMessages(raw: unknown[]): ChatMessage[] {
  return raw.map(normalizeMessage)
}

export function normalizeStatus(raw: unknown): StatusResp {
  const value = raw as Record<string, any> | null | undefined

  // 已经是契约形态就直接用（只修一下可能乱码的人设名）
  if (value && typeof value === 'object' && value.bot && typeof value.bot === 'object') {
    const status = value as StatusResp
    return {
      ...status,
      persona: status.persona
        ? { ...status.persona, name: repairMojibake(status.persona.name) }
        : null
    }
  }

  const data = (value ?? {}) as BackendStatus
  const memory = data.memory ?? {}

  let persona: { id: string; name: string } | null = null
  if (typeof data.persona === 'string' && data.persona.trim()) {
    persona = { id: 'current', name: repairMojibake(data.persona) }
  } else if (data.persona && typeof data.persona === 'object' && data.persona.name) {
    persona = { id: String(data.persona.id ?? 'current'), name: repairMojibake(data.persona.name) }
  }

  return {
    bot: {
      online: Boolean(data.connected),
      napcat: Boolean(data.connected),
      uptimeSec: Number(data.uptimeSeconds ?? 0)
    },
    today: {
      messages: Number(memory.messagesToday ?? data.todayMessages ?? 0),
      cost: Number(data.usage?.todayCostCny ?? 0),
      tokens: Number(data.usage?.todayTokens ?? 0)
    },
    persona,
    memory: {
      facts: Number(memory.factsActive ?? memory.facts ?? 0),
      pending: Number(memory.pending ?? 0),
      lastExtractedAt: memory.lastExtractedAt ?? null
    }
  }
}
