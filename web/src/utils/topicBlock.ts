import type { TopicAction, TopicBlockConfig } from '@/api/types'

/** 处理方式的展示文案，页面与测试共用 */
export const TOPIC_ACTIONS: Array<{
  value: TopicAction
  label: string
  desc: string
  icon: string
}> = [
  {
    value: 'deflect',
    label: '委婉转移',
    desc: '先应一声，再自然地把话题带到别处',
    icon: 'Promotion'
  },
  {
    value: 'refuse',
    label: '温和拒绝',
    desc: '直接说不想聊这个，语气保持友好',
    icon: 'ChatLineSquare'
  },
  {
    value: 'silent',
    label: '不作回复',
    desc: '这条消息不接，等对方换个话题',
    icon: 'MuteNotification'
  }
]

export const DEFAULT_REPLY_TEMPLATES: Record<Exclude<TopicAction, 'silent'>, string> = {
  deflect: '这个话题我不太想聊欸，我们换个别的吧～',
  refuse: '抱歉呀，这个我不方便聊，聊点别的可以吗？'
}

export function actionLabel(action: TopicAction): string {
  return TOPIC_ACTIONS.find(a => a.value === action)?.label ?? action
}

export function defaultReplyTemplate(action: TopicAction): string {
  return action === 'silent' ? '' : DEFAULT_REPLY_TEMPLATES[action]
}

export interface TopicMatch {
  /** 命中的话题名（预设标签或自定义话题） */
  topic: string
  /** 实际命中的词 */
  keyword: string
  source: 'preset' | 'custom'
  /** 命中位置（原文下标，用于高亮） */
  start: number
  end: number
}

interface NormalizedText {
  /** 去掉空白、全角转半角、转小写后的文本 */
  text: string
  /** 归一化后的每个字符对应的原文起始下标 */
  map: number[]
}

/**
 * 匹配前的归一化：NFKC（全角→半角）、小写、去掉所有空白。
 * 这样「裸 照」「ＮＵＤＥ」也能命中「裸照」「Nude」。
 */
export function normalizeForMatch(input: string): NormalizedText {
  let text = ''
  const map: number[] = []
  let index = 0

  for (const char of input) {
    const start = index
    const normalized = char.normalize('NFKC').toLowerCase()
    for (const piece of normalized) {
      if (/\s/u.test(piece)) continue
      text += piece
      map.push(start)
    }
    index += char.length
  }

  return { text, map }
}

function findKeyword(haystack: NormalizedText, keyword: string): { start: number; end: number } | null {
  const needle = normalizeForMatch(keyword).text
  if (!needle) return null
  const at = haystack.text.indexOf(needle)
  if (at < 0) return null

  const first = haystack.map[at]
  const last = haystack.map[at + needle.length - 1]
  if (first === undefined || last === undefined) return null
  return { start: first, end: last + 1 }
}

/** 本地关键词匹配：消息包含关键词即命中；忽略空白、全半角与大小写 */
export function matchTopicBlock(text: string, config: TopicBlockConfig): TopicMatch[] {
  const haystack = normalizeForMatch(text)
  if (!haystack.text) return []

  const matches: TopicMatch[] = []
  const seen = new Set<string>()

  for (const preset of config.presets) {
    if (!preset.enabled) continue
    for (const keyword of preset.keywords) {
      const word = keyword.trim()
      if (!word) continue
      const key = `preset:${preset.id}:${normalizeForMatch(word).text}`
      if (seen.has(key)) continue
      const found = findKeyword(haystack, word)
      if (!found) continue
      seen.add(key)
      matches.push({ topic: preset.label, keyword: word, source: 'preset', ...found })
    }
  }

  for (const topic of config.customTopics) {
    const word = topic.trim()
    if (!word) continue
    const key = `custom:${normalizeForMatch(word).text}`
    if (seen.has(key)) continue
    const found = findKeyword(haystack, word)
    if (!found) continue
    seen.add(key)
    matches.push({ topic: word, keyword: word, source: 'custom', ...found })
  }

  return matches
}

/** 自伤/自杀相关表达：这类消息不适合简单屏蔽，页面会单独提示 */
export const CRISIS_KEYWORDS = ['自杀', '自残', '轻生', '割腕', '跳楼', '不想活了', '结束生命', '活着没意思']

export function detectCrisis(text: string): string | null {
  const haystack = normalizeForMatch(text).text
  for (const keyword of CRISIS_KEYWORDS) {
    if (haystack.includes(normalizeForMatch(keyword).text)) return keyword
  }
  return null
}

export const CRISIS_ADVICE =
  '检测到自伤/自杀相关表达。这类消息不建议用「不作回复」或简单转移，先关心对方，再引导其联系信任的人或心理援助热线（如 12356）。'

export interface TextSegment {
  text: string
  hit: boolean
}

/** 把命中的区间切成高亮片段，重叠区间会合并 */
export function highlightMatches(text: string, matches: TopicMatch[]): TextSegment[] {
  if (!text) return []
  const ranges = matches
    .map(m => ({ start: Math.max(0, m.start), end: Math.min(text.length, m.end) }))
    .filter(r => r.end > r.start)
    .sort((a, b) => a.start - b.start)

  if (ranges.length === 0) return [{ text, hit: false }]

  const merged: Array<{ start: number; end: number }> = []
  for (const range of ranges) {
    const last = merged[merged.length - 1]
    if (last && range.start <= last.end) {
      last.end = Math.max(last.end, range.end)
    } else {
      merged.push({ ...range })
    }
  }

  const segments: TextSegment[] = []
  let cursor = 0
  for (const range of merged) {
    if (range.start > cursor) segments.push({ text: text.slice(cursor, range.start), hit: false })
    segments.push({ text: text.slice(range.start, range.end), hit: true })
    cursor = range.end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), hit: false })
  return segments
}

/** 当前生效的话题名列表（预设按配置顺序，自定义排在后面） */
export function activeTopicLabels(config: TopicBlockConfig): string[] {
  const labels = config.presets.filter(p => p.enabled).map(p => p.label)
  for (const topic of config.customTopics) {
    const t = topic.trim()
    if (t) labels.push(t)
  }
  return labels
}

/**
 * 生成注入 system prompt 的「话题边界」段落。
 * 关闭或没有配置任何话题时返回空字符串。
 */
export function buildTopicBlockPrompt(config: TopicBlockConfig): string {
  if (!config.enabled) return ''
  const labels = activeTopicLabels(config)
  if (labels.length === 0) return ''

  const lines: string[] = [
    `【话题边界】以下话题不展开聊：${labels.join('、')}。`
  ]

  switch (config.action) {
    case 'deflect':
      lines.push('对方主动提起时，先用一句轻松的话应一下，再把话题自然带到别处；不要展开、不要评价、不要解释规则。')
      break
    case 'refuse':
      lines.push('对方主动提起时，礼貌但明确地表示不想聊这个，然后邀请对方换个话题；不要展开、不要解释规则。')
      break
    case 'silent':
      lines.push('对方主动提起时，这条消息不要回复，也不要生成任何相关内容，等对方换话题。')
      break
  }

  const template = config.replyTemplate.trim()
  if (config.action !== 'silent' && template) {
    lines.push(`需要开口时可以参考这句话：「${template}」（{topic} 会替换成命中的话题名，不要逐字照搬）。`)
  }

  return lines.join('\n')
}

/** 为预置话题挑选展示图标（Element Plus 图标名） */
export const PRESET_ICONS: Record<string, string> = {
  porn: 'Hide',
  politics: 'Flag',
  violence: 'Warning',
  illegal: 'Lock',
  spam: 'Promotion'
}

export function presetIcon(id: string): string {
  return PRESET_ICONS[id] ?? 'InfoFilled'
}

export function cloneTopicBlockConfig(config: TopicBlockConfig): TopicBlockConfig {
  return JSON.parse(JSON.stringify(config)) as TopicBlockConfig
}

export function serializeTopicBlockConfig(config: TopicBlockConfig): string {
  return JSON.stringify(config, null, 2)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function uniqueStrings(values: string[], limit: number): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const value of values) {
    const item = value.trim()
    if (!item) continue
    const key = normalizeForMatch(item).text
    if (seen.has(key)) continue
    seen.add(key)
    result.push(item)
    if (result.length >= limit) break
  }
  return result
}

/**
 * 解析导入的配置文件，做结构校验和去重。
 * 校验失败会抛出带原因的错误，页面直接提示。
 */
export function parseTopicBlockConfig(raw: string): TopicBlockConfig {
  let data: unknown
  try {
    data = JSON.parse(raw)
  } catch {
    throw new Error('不是合法的 JSON 文件')
  }
  if (!isRecord(data)) throw new Error('配置格式不对，应该是一个对象')
  if (typeof data.enabled !== 'boolean') throw new Error('缺少 enabled 开关')
  if (data.action !== 'deflect' && data.action !== 'refuse' && data.action !== 'silent') {
    throw new Error('action 只能是 deflect / refuse / silent')
  }
  if (typeof data.replyTemplate !== 'string') throw new Error('缺少 replyTemplate')
  if (!Array.isArray(data.customTopics) || !data.customTopics.every(t => typeof t === 'string')) {
    throw new Error('customTopics 应该是字符串数组')
  }
  if (!Array.isArray(data.presets)) throw new Error('presets 应该是数组')

  const presets = data.presets.map((item, index) => {
    if (!isRecord(item)) throw new Error(`第 ${index + 1} 个预设话题格式不对`)
    if (typeof item.id !== 'string' || !item.id.trim()) throw new Error(`第 ${index + 1} 个预设话题缺少 id`)
    if (typeof item.label !== 'string' || !item.label.trim()) throw new Error(`第 ${index + 1} 个预设话题缺少名称`)
    if (typeof item.desc !== 'string') throw new Error(`第 ${index + 1} 个预设话题缺少描述`)
    if (typeof item.enabled !== 'boolean') throw new Error(`第 ${index + 1} 个预设话题缺少 enabled`)
    if (!Array.isArray(item.keywords) || !item.keywords.every(k => typeof k === 'string')) {
      throw new Error(`第 ${index + 1} 个预设话题的 keywords 应该是字符串数组`)
    }
    return {
      id: item.id.trim(),
      label: item.label.trim().slice(0, 20),
      desc: item.desc.trim().slice(0, 80),
      enabled: item.enabled,
      keywords: uniqueStrings(item.keywords, 50)
    }
  })

  if (new Set(presets.map(p => p.id)).size !== presets.length) {
    throw new Error('预设话题的 id 有重复')
  }

  return {
    enabled: data.enabled,
    action: data.action,
    replyTemplate: data.replyTemplate.slice(0, 160),
    customTopics: uniqueStrings(data.customTopics, 30).map(t => t.slice(0, 20)),
    presets
  }
}
