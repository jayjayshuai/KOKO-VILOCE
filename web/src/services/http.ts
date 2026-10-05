/** 阶段/生产同源入口；开发代理不改变用户侧会话存储方式。 */
export const apiBaseUrl = (import.meta.env.VITE_API_BASE || '/api').replace(/\/+$/, '')

export class ApiRequestError extends Error {
  constructor(
    message: string,
    /** HTTP 状态；网络超时为 0，不应被当成 401 清除会话。 */
    public readonly status: number,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}
export type ApiRequestOptions = RequestInit & {
  /** 客户端等待上限；超时不代表服务器没有提交写入，不能自动重试写请求。 */
  timeoutMs?: number
}

/** 请求开始时捕获会话轮次；旧账号的迟到 401 不得注销新账号。 */
export interface SessionObserver {
  capture(): unknown
  unauthorized(snapshot: unknown): void
}
let sessionObserver: SessionObserver | undefined
/** 应用启动时绑定 Pinia 会话；请求层不依赖组件或保存任何令牌。 */
export function observeSession(observer: SessionObserver): () => void {
  sessionObserver = observer
  return () => {
    if (sessionObserver === observer) sessionObserver = undefined
  }
}

/** 请求基础能力：Cookie、取消、等待上限、空响应与 JSON 契约分别处理。 */
export async function request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { timeoutMs = 20000, signal, ...init } = options
  // 登录、注册、恢复和注销由身份 Store 管理，错误凭据不能注销当前用户。
  const observer = path.startsWith('/auth/') ? undefined : sessionObserver
  const sessionSnapshot = observer?.capture()
  const controller = new AbortController()
  const forwardAbort = () => controller.abort(signal?.reason)
  if (signal?.aborted) forwardAbort()
  else signal?.addEventListener('abort', forwardAbort, { once: true })
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const headers = new Headers(init.headers)
  if (!(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  try {
    const response = await fetch(`${apiBaseUrl}${path}`, {
      ...init,
      credentials: 'include',
      headers,
      signal: controller.signal,
    })
    if (!response.ok) {
      if (response.status === 401) observer?.unauthorized(sessionSnapshot)
      const body = await response.json().catch(() => undefined)
      throw new ApiRequestError(body?.message || `请求失败（${response.status}）`, response.status)
    }
    if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
    const content = await response.text()
    if (!content.trim()) return undefined as T
    try {
      return JSON.parse(content) as T
    } catch {
      throw new ApiRequestError('服务响应格式错误，请稍后重试。', response.status)
    }
  } catch (cause) {
    if (timedOut && !signal?.aborted)
      throw new ApiRequestError('请求超时；写入结果可能已提交，请先重新读取再决定是否重试。', 0)
    throw cause
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', forwardAbort)
  }
}
