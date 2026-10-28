import { mockFor } from '@/mock'
import { mockForExtreme } from '@/mock/extreme'

/**
 * Mock 开关：
 * - VITE_USE_MOCK=true（默认）：所有接口走 Mock
 * - VITE_USE_MOCK=false：所有接口请求真实后端
 * - VITE_REAL_API=/auth,/status：只把这些前缀切到真实后端，其余继续 Mock，
 *   用于后端分批交付时「接一点是一点」。设置后优先级高于 VITE_USE_MOCK。
 */
const USE_MOCK = (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'
const REAL_API = String(import.meta.env.VITE_REAL_API ?? '')
  .split(',')
  .map((s: string) => s.trim())
  .filter(Boolean)

function matchesPrefix(path: string, prefix: string): boolean {
  const clean = path.split('?')[0]
  const base = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix
  return clean === base || clean.startsWith(base + '/')
}

/** 这个接口是否走真实后端 */
export function isRealRequest(path: string): boolean {
  if (REAL_API.length > 0) {
    return REAL_API.some((prefix: string) => matchesPrefix(path, prefix))
  }
  return !USE_MOCK
}

/** 这个接口是否走 Mock */
export function isMock(path: string): boolean {
  return !isRealRequest(path)
}

function getMockScenario(): string {
  const url = new URL(window.location.href)
  if (url.searchParams.get('mock') === 'extreme') return 'extreme'
  return localStorage.getItem('mock-scenario') ?? 'normal'
}

export class ApiError extends Error {
  code: string
  traceId?: string
  constructor(code: string, message: string, traceId?: string) {
    super(message)
    this.code = code
    this.traceId = traceId
  }
}

export interface ApiOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  formData?: FormData
}

export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const { method = 'GET', body, formData } = options

  if (isMock(path)) {
    // 模拟网络延迟，让加载态能看出来
    await new Promise((r) => setTimeout(r, 60 + Math.random() * 60))
    const scenario = getMockScenario()
    if (scenario === 'extreme') {
      return mockForExtreme<T>(path, method, body)
    }
    return mockFor<T>(path, method, body)
  }

  const init: RequestInit = { method, credentials: 'include', headers: {}, cache: 'no-store' }
  if (formData) {
    init.body = formData
  } else if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const activeQq = localStorage.getItem('activeQq')
  if (activeQq) {
    ;(init.headers as Record<string, string>)['X-QQ-Id'] = activeQq
  }
  // 当前查看的机器人账号（没有则后端用默认账号）
  const activeBot = localStorage.getItem('activeBotId')
  if (activeBot) {
    ;(init.headers as Record<string, string>)['X-Bot-Id'] = activeBot
  }

  let res: Response
  try {
    // GET 加时间戳，避免手机浏览器/运营商缓存旧数据
    const url = method === 'GET'
      ? `/api${path}${path.includes('?') ? '&' : '?'}_t=${Date.now()}`
      : `/api${path}`
    res = await fetch(url, init)
  } catch {
    throw new ApiError('NETWORK_ERROR', '无法连接后端，请确认 8080 服务已启动')
  }
  if (!res.ok) {
    const raw = await res.text()
    let payload: any = {}
    try { payload = raw ? JSON.parse(raw) : {} } catch { /* 非 JSON 错误页 */ }

    // 会话过期：清掉页面登录标记，回到登录页。登录接口本身的 401 不跳转。
    if (res.status === 401 && !path.startsWith('/auth/')) {
      sessionStorage.removeItem('dsh-admin')
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    }

    const fallbackMessage = res.status >= 500
      ? `后端服务异常（${res.status}），可能正在重启`
      : (res.statusText || `HTTP_${res.status}`)

    throw new ApiError(
      payload.code ?? `HTTP_${res.status}`,
      payload.message ?? fallbackMessage,
      payload.traceId
    )
  }
  if (res.status === 204) return undefined as T
  const data = await res.json()

  // 后端部分校验错误用 HTTP 200 + {code,message} 返回，这里统一当错误抛出
  if (data && typeof data === 'object' && 'code' in data && !('ok' in data)) {
    const error = data as { code: string; message?: string; traceId?: string }
    throw new ApiError(String(error.code), error.message ?? '请求失败', error.traceId)
  }

  return data as T
}
