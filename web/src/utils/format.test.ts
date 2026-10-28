import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { formatRelativeTime, useCountUp, copyToClipboard } from './format'

describe('formatRelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-22T14:30:00+08:00'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns "—" for empty input', () => {
    expect(formatRelativeTime('')).toBe('—')
  })

  it('formats today with time', () => {
    expect(formatRelativeTime('2026-10-22T09:15:00+08:00')).toBe('今天 09:15')
  })

  it('formats yesterday with time', () => {
    expect(formatRelativeTime('2026-10-21T18:45:00+08:00')).toBe('昨天 18:45')
  })

  it('formats older dates as MM-DD', () => {
    expect(formatRelativeTime('2026-10-15T12:00:00+08:00')).toBe('10-15')
  })

  it('handles single-digit hours and minutes', () => {
    expect(formatRelativeTime('2026-10-22T03:05:00+08:00')).toBe('今天 03:05')
  })
})

describe('useCountUp', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('initializes with formatted value', () => {
    const counter = useCountUp(100, 600, 0)
    expect(counter.value.value).toBe('100')
  })

  it('initializes with decimals', () => {
    const counter = useCountUp(1.5, 600, 2)
    expect(counter.value.value).toBe('1.50')
  })

  it('allows setting target', () => {
    const counter = useCountUp(0, 600, 0)
    counter.target = 50
    expect(counter.target).toBe(50)
  })

  it('animates to target value', async () => {
    const counter = useCountUp(100, 600, 0)
    counter.start()

    // Simulate animation frames
    vi.advanceTimersByTime(300)
    expect(Number(counter.value.value)).toBeGreaterThan(0)
    expect(Number(counter.value.value)).toBeLessThan(100)

    vi.advanceTimersByTime(400)
    expect(counter.value.value).toBe('100')
  })
})

describe('copyToClipboard', () => {
  it('returns boolean result', async () => {
    // This is a basic smoke test - actual clipboard behavior
    // depends on browser environment
    const result = await copyToClipboard('test')
    expect(typeof result).toBe('boolean')
  })
})
