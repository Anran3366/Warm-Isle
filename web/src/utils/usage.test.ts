import { describe, it, expect } from 'vitest'
import { fillUsageSeries } from './usage'

describe('fillUsageSeries', () => {
  it('fills missing dates with zero', () => {
    const result = fillUsageSeries('2026-10-20', '2026-10-22', [
      { date: '2026-10-22', cost: 0.5, tokens: 100 }
    ])
    expect(result).toEqual([
      { date: '2026-10-20', cost: 0, tokens: 0 },
      { date: '2026-10-21', cost: 0, tokens: 0 },
      { date: '2026-10-22', cost: 0.5, tokens: 100 }
    ])
  })

  it('keeps all points when range covers them', () => {
    const points = [
      { date: '2026-10-20', cost: 1, tokens: 10 },
      { date: '2026-10-21', cost: 2, tokens: 20 }
    ]
    expect(fillUsageSeries('2026-10-20', '2026-10-21', points)).toEqual(points)
  })

  it('returns input for invalid ranges', () => {
    const points = [{ date: '2026-10-22', cost: 1, tokens: 2 }]
    expect(fillUsageSeries('2026-10-22', '2026-10-20', points)).toBe(points)
    expect(fillUsageSeries('bad', '2026-10-20', points)).toBe(points)
  })
})
