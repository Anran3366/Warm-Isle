import { ref } from 'vue'

/**
 * 数字滚动动画（返回 Vue ref，支持小数）
 */
export function useCountUp(initialTarget = 0, duration = 600, decimals = 0) {
  let startTime = 0
  let rafId = 0
  let from = 0
  let target = initialTarget
  const value = ref(decimals > 0 ? initialTarget.toFixed(decimals) : String(Math.floor(initialTarget)))

  function step(timestamp: number) {
    if (!startTime) startTime = timestamp
    const progress = Math.min((timestamp - startTime) / duration, 1)
    const eased = 1 - Math.pow(1 - progress, 3)
    const current = from + (target - from) * eased
    value.value = decimals > 0 ? current.toFixed(decimals) : String(Math.floor(current))
    if (progress < 1) {
      rafId = requestAnimationFrame(step)
    } else {
      value.value = decimals > 0 ? target.toFixed(decimals) : String(target)
    }
  }

  function start() {
    cancelAnimationFrame(rafId)
    from = 0
    startTime = 0
    rafId = requestAnimationFrame(step)
  }

  return {
    value,
    get target() { return target },
    set target(v: number) { target = v },
    start
  }
}

/**
 * 相对时间格式化
 */
export function formatRelativeTime(iso: string): string {
  if (!iso) return '—'
  const date = new Date(iso)
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)

  if (date >= today) {
    return `今天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  if (date >= yesterday) {
    return `昨天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/**
 * 复制到剪贴板
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // Fallback
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  }
}
