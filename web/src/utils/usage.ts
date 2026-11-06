import type { UsagePoint } from '@/api/types'

function formatDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 后端只返回有调用的日期，这里按 from/to 闭区间补齐缺失日期为 0，
 * 保证图表 X 轴连续。
 */
export function fillUsageSeries(from: string, to: string, points: UsagePoint[]): UsagePoint[] {
  if (!from || !to) return points
  const start = new Date(`${from}T00:00:00`)
  const end = new Date(`${to}T00:00:00`)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || start > end) return points

  const byDate = new Map(points.map(p => [p.date, p]))
  const result: UsagePoint[] = []
  const cursor = new Date(start)
  while (cursor <= end) {
    const date = formatDate(cursor)
    const point = byDate.get(date)
    result.push({ date, cost: point?.cost ?? 0, tokens: point?.tokens ?? 0 })
    cursor.setDate(cursor.getDate() + 1)
  }
  return result
}
