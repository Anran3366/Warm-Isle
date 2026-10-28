import { describe, it, expect } from 'vitest'
import { DEFAULT_SETTINGS, normalizeEmoji, normalizeLog, normalizeMessage, normalizeSettings, normalizeStatus, repairMojibake } from './adapters'

describe('repairMojibake', () => {
  const mojibake = String.fromCharCode(0xe5, 0xb0, 0x8f, 0xe6, 0xbb, 0xa1) // 小满 被按 Latin-1 读

  it('repairs UTF-8 text read as Latin-1', () => {
    expect(repairMojibake(mojibake)).toBe('小满')
  })

  it('keeps normal Chinese and Latin names', () => {
    expect(repairMojibake('小满')).toBe('小满')
    expect(repairMojibake('José')).toBe('José')
  })

  it('repairs the persona name in status', () => {
    const status = normalizeStatus({
      bot: { online: true, napcat: true, uptimeSec: 1 },
      today: { messages: 1, cost: 0, tokens: 1 },
      persona: { id: 'p0', name: mojibake },
      memory: { facts: 0, pending: 0, lastExtractedAt: null }
    })
    expect(status.persona?.name).toBe('小满')
  })
})

describe('normalizeStatus', () => {
  it('keeps the contract shape unchanged', () => {
    const contract = {
      bot: { online: true, napcat: true, uptimeSec: 120 },
      today: { messages: 7, cost: 0.2, tokens: 900 },
      persona: { id: 'p1', name: '小满' },
      memory: { facts: 3, pending: 1, lastExtractedAt: '2026-10-22T12:00:00+08:00' }
    }
    expect(normalizeStatus(contract)).toEqual(contract)
  })

  it('maps the backend internal shape', () => {
    const result = normalizeStatus({
      connected: true,
      todayMessages: 2,
      persona: '小满',
      memory: { messagesToday: 20, factsActive: 3 },
      usage: { todayTokens: 4923, todayCostCny: 0.31 },
      uptimeSeconds: 86400
    })
    expect(result).toEqual({
      bot: { online: true, napcat: true, uptimeSec: 86400 },
      today: { messages: 20, cost: 0.31, tokens: 4923 },
      persona: { id: 'current', name: '小满' },
      memory: { facts: 3, pending: 0, lastExtractedAt: null }
    })
  })

  it('handles missing fields with safe defaults', () => {
    expect(normalizeStatus({})).toEqual({
      bot: { online: false, napcat: false, uptimeSec: 0 },
      today: { messages: 0, cost: 0, tokens: 0 },
      persona: null,
      memory: { facts: 0, pending: 0, lastExtractedAt: null }
    })
  })
})

describe('normalizeSettings', () => {
  it('keeps the backend settings shape', () => {
    const raw = {
      model: 'deepseek-chat',
      temperature: 1.1,
      dailyBudget: 2.5,
      contextRounds: 12,
      maxTokens: 600,
      vision: { enabled: false, maxPerMessage: 2, maxSize: 768, dailyLimit: 10, emojiAutoImport: false }
    }
    expect(normalizeSettings(raw)).toEqual({
      model: 'deepseek-chat',
      temperature: 1.1,
      dailyBudget: 2.5,
      contextRounds: 12,
      maxTokens: 600,
      vision: { enabled: false, maxPerMessage: 2, maxSize: 768, dailyLimit: 10, emojiAutoImport: false },
      reply: { maxChars: 60, splitDelayMs: 1200, typingMs: 600, adaptive: true }
    })
  })

  it('fills missing fields from defaults', () => {
    expect(normalizeSettings({ model: 'qwen-max' })).toEqual({
      ...DEFAULT_SETTINGS,
      model: 'qwen-max'
    })
  })
})

describe('normalizeEmoji', () => {
  it('splits backend comma strings and fills the file url', () => {
    expect(normalizeEmoji({
      id: '3',
      emoTags: '开心，无语',
      sceneTags: '晚安 打招呼',
      enabled: true,
      url: '/api/emoji/3/file'
    })).toEqual({
      id: '3',
      url: '/api/emoji/3/file',
      emoTags: ['开心', '无语'],
      sceneTags: ['晚安', '打招呼'],
      enabled: true,
      source: 'upload'
    })
  })

  it('keeps array tags and auto source', () => {
    const emoji = normalizeEmoji({ id: 4, emoTags: ['a'], sceneTags: ['b'], source: 'auto' })
    expect(emoji.emoTags).toEqual(['a'])
    expect(emoji.source).toBe('auto')
    expect(emoji.url).toBe('/api/emoji/4/file')
  })
})

describe('normalizeLog', () => {
  it('lowercases levels and keeps logger', () => {
    expect(normalizeLog({
      ts: '22:06:36.123',
      level: 'WARN',
      logger: 'c.q.bot.OneBotClient',
      msg: '重连中'
    })).toEqual({
      ts: '22:06:36.123',
      level: 'warn',
      logger: 'c.q.bot.OneBotClient',
      msg: '重连中'
    })
  })

  it('maps DEBUG and unknown levels to supported values', () => {
    expect(normalizeLog({ level: 'DEBUG' }).level).toBe('debug')
    expect(normalizeLog({ level: 'TRACE' }).level).toBe('info')
  })
})

describe('normalizeMessage', () => {
  it('converts backend datetime to ISO and ids to strings', () => {
    expect(normalizeMessage({
      id: 7,
      role: 'assistant',
      content: '在呢',
      kind: 'text',
      createdAt: '2026-10-22 21:12:00'
    })).toEqual({
      id: '7',
      role: 'assistant',
      content: '在呢',
      kind: 'text',
      createdAt: '2026-10-22T21:12:00'
    })
  })

  it('keeps an existing ISO string', () => {
    expect(normalizeMessage({ createdAt: '2026-10-22T21:12:00+08:00' }).createdAt)
      .toBe('2026-10-22T21:12:00+08:00')
  })
})
