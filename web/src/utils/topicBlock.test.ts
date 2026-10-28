import { describe, it, expect } from 'vitest'
import {
  activeTopicLabels, buildTopicBlockPrompt, detectCrisis, highlightMatches,
  matchTopicBlock, parseTopicBlockConfig, serializeTopicBlockConfig
} from './topicBlock'
import type { TopicBlockConfig } from '@/api/types'

function config(overrides: Partial<TopicBlockConfig> = {}): TopicBlockConfig {
  return {
    enabled: true,
    action: 'deflect',
    replyTemplate: '这个话题不想聊，换一个吧',
    customTopics: ['前任'],
    presets: [
      {
        id: 'porn',
        label: '色情低俗',
        desc: '',
        enabled: true,
        keywords: ['裸照', '色情', 'Nude']
      },
      {
        id: 'politics',
        label: '国家政治',
        desc: '',
        enabled: false,
        keywords: ['选举']
      }
    ],
    ...overrides
  }
}

describe('matchTopicBlock', () => {
  it('matches enabled preset keywords', () => {
    const hits = matchTopicBlock('给我看看裸照', config())
    expect(hits).toHaveLength(1)
    expect(hits[0]).toMatchObject({ topic: '色情低俗', keyword: '裸照', source: 'preset' })
  })

  it('matches keywords case-insensitively', () => {
    const hits = matchTopicBlock('send me NUDE pics', config())
    expect(hits).toHaveLength(1)
    expect(hits[0].keyword).toBe('Nude')
  })

  it('ignores spaces and full-width characters', () => {
    const spaced = matchTopicBlock('发张裸 照看看', config())
    expect(spaced).toHaveLength(1)

    const fullWidth = matchTopicBlock('send me ＮＵＤＥ pics', config())
    expect(fullWidth).toHaveLength(1)
  })

  it('ignores disabled presets', () => {
    const hits = matchTopicBlock('聊聊选举吧', config())
    expect(hits).toHaveLength(0)
  })

  it('matches custom topics', () => {
    const hits = matchTopicBlock('又想起前任了', config())
    expect(hits).toHaveLength(1)
    expect(hits[0]).toMatchObject({ topic: '前任', keyword: '前任', source: 'custom' })
  })

  it('returns empty for normal text or blank input', () => {
    expect(matchTopicBlock('今天吃了火锅', config())).toEqual([])
    expect(matchTopicBlock('   ', config())).toEqual([])
  })

  it('does not return duplicate hits for repeated keywords', () => {
    const hits = matchTopicBlock('裸照 裸照 色情', config())
    expect(hits.map(h => h.keyword)).toEqual(['裸照', '色情'])
  })

  it('reports positions in the original text', () => {
    const hits = matchTopicBlock('发张裸 照看看', config())
    expect('发张裸 照看看'.slice(hits[0].start, hits[0].end)).toBe('裸 照')
  })
})

describe('highlightMatches', () => {
  it('returns one plain segment when nothing matched', () => {
    expect(highlightMatches('今天吃了火锅', [])).toEqual([{ text: '今天吃了火锅', hit: false }])
  })

  it('splits and marks matched ranges', () => {
    const text = '发张裸照看看'
    const segments = highlightMatches(text, matchTopicBlock(text, config()))
    expect(segments.map(s => s.text).join('')).toBe(text)
    expect(segments.filter(s => s.hit).map(s => s.text)).toEqual(['裸照'])
  })

  it('merges overlapping ranges', () => {
    const segments = highlightMatches('裸照', [
      { topic: 'a', keyword: '裸照', source: 'preset', start: 0, end: 2 },
      { topic: 'b', keyword: '照', source: 'preset', start: 1, end: 2 }
    ])
    expect(segments).toEqual([{ text: '裸照', hit: true }])
  })
})

describe('detectCrisis', () => {
  it('detects self-harm expressions', () => {
    expect(detectCrisis('我真的不想活了')).toBe('不想活了')
    expect(detectCrisis('最近有点想自杀')).toBe('自杀')
  })

  it('ignores normal text', () => {
    expect(detectCrisis('今天加班好累')).toBeNull()
  })
})

describe('parseTopicBlockConfig', () => {
  it('round-trips a valid config and dedupes keywords', () => {
    const base = config()
    const raw = serializeTopicBlockConfig(base)
    const parsed = parseTopicBlockConfig(raw)
    expect(parsed).toEqual(base)
  })

  it('dedupes keywords and custom topics', () => {
    const raw = JSON.stringify({
      ...config(),
      customTopics: ['前任', '前任', '  '],
      presets: [{ id: 'p', label: 'P', desc: '', enabled: true, keywords: ['裸照', '裸照', '裸 照'] }]
    })
    const parsed = parseTopicBlockConfig(raw)
    expect(parsed.customTopics).toEqual(['前任'])
    expect(parsed.presets[0].keywords).toEqual(['裸照'])
  })

  it('rejects invalid JSON and unknown action', () => {
    expect(() => parseTopicBlockConfig('{')).toThrow('JSON')
    expect(() => parseTopicBlockConfig(JSON.stringify({ ...config(), action: 'ignore' }))).toThrow('action')
  })
})

describe('buildTopicBlockPrompt', () => {
  it('lists only enabled topics and keeps custom topics last', () => {
    expect(activeTopicLabels(config())).toEqual(['色情低俗', '前任'])
  })

  it('returns empty string when disabled', () => {
    expect(buildTopicBlockPrompt(config({ enabled: false }))).toBe('')
  })

  it('returns empty string when nothing is configured', () => {
    const empty = config({ presets: [], customTopics: [] })
    expect(buildTopicBlockPrompt(empty)).toBe('')
  })

  it('includes topics, action instruction and reply template', () => {
    const prompt = buildTopicBlockPrompt(config())
    expect(prompt).toContain('色情低俗、前任')
    expect(prompt).toContain('带到别处')
    expect(prompt).toContain('「这个话题不想聊，换一个吧」')
    expect(prompt).toContain('{topic}')
  })

  it('omits the reply template for silent mode', () => {
    const prompt = buildTopicBlockPrompt(config({ action: 'silent' }))
    expect(prompt).toContain('不要回复')
    expect(prompt).not.toContain('这个话题不想聊')
  })
})
