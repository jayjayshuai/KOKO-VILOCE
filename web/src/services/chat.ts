import { apiBaseUrl as base, request as httpRequest } from './http'

/** 用户 ID 使用字符串，避免 JavaScript 雪花 ID 精度损失。 */
export type ChatMember = {
  /** 成员雪花 ID，禁止转成 JavaScript number。 */
  userId: string
  /** 加入时的公开用户名快照。 */
  handle: string
  /** 加入时的公开显示名称快照。 */
  displayName: string
  /** 加入历史边界，只允许读取更大的序号。 */
  joinedSeq: number
  /** 服务端已确认的已读序号，只前进。 */
  readSeq: number
}
export type Conversation = {
  /** 会话 UUID。 */
  id: string
  /** 私信或群聊；决定客户端管理控件。 */
  kind: 'DIRECT' | 'GROUP'
  /** 群名称；私信为对方显示名称。 */
  title: string
  /** 群主/会话创建人 ID；权限仍由服务端判断。 */
  ownerId: string
  /** 最后已提交消息序号，不表示本人已读。 */
  lastSeq: number
  /** 当前成员列表，服务端限制最多 50 人。 */
  members: ChatMember[]
}
export type ChatMessage = {
  /** 服务端消息 UUID，合并历史和实时确认的去重依据。 */
  id: string
  /** 所属会话 UUID。 */
  conversationId: string
  /** 会话内提交顺序，用于增量恢复。 */
  seq: number
  /** 服务端认证的发送者 ID，不采信客户端声明。 */
  senderId: string
  /** 发送时显示名称快照。 */
  senderName: string
  /** 客户端幂等 UUID；超时后复用原值和正文。 */
  clientMessageId: string
  /** 纯文本正文，最多 2000 Java 字符，不解释 HTML。 */
  body: string
  /** 服务端 Asia/Shanghai 本地日期时间。 */
  createdAt: string
}

/** 聊天域仍保持原路径与协议，共享 Cookie / 超时 / 取消 / 空响应处理。 */
function request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  return httpRequest<T>(`/chat${path}`, { method, signal, body: body === undefined ? undefined : JSON.stringify(body) })
}
export const chatApi = {
  list: (after?: string, signal?: AbortSignal) =>
    request<Conversation[]>(
      `/conversations?size=100${after ? `&after=${encodeURIComponent(after)}` : ''}`,
      'GET',
      undefined,
      signal,
    ),
  direct: (handle: string) => request<Conversation>('/direct', 'POST', { handle }),
  group: (title: string, handles: string[]) => request<Conversation>('/groups', 'POST', { title, handles }),
  history: (id: string, query = '', signal?: AbortSignal) =>
    request<ChatMessage[]>(`/conversations/${id}/messages?size=100${query}`, 'GET', undefined, signal),
  read: (id: string, seq: number, signal?: AbortSignal) =>
    request<void>(`/conversations/${id}/read`, 'POST', { seq }, signal),
  add: (id: string, handle: string) => request<void>(`/conversations/${id}/members`, 'POST', { handle }),
  remove: (id: string, userId: string) => request<void>(`/conversations/${id}/members/${userId}`, 'DELETE'),
  rename: (id: string, title: string) => request<void>(`/conversations/${id}`, 'PATCH', { title }),
  close: (id: string) => request<void>(`/conversations/${id}`, 'DELETE'),
  search: (id: string, query: string, before?: number, signal?: AbortSignal) =>
    request<MessageSlice>(
      `/conversations/${id}/search?query=${encodeURIComponent(query)}&size=20${before === undefined ? '' : `&before=${before}`}`,
      'GET',
      undefined,
      signal,
    ),
  bookmarks: (id: string, before?: number, signal?: AbortSignal) =>
    request<MessageSlice>(
      `/conversations/${id}/bookmarks?size=20${before === undefined ? '' : `&before=${before}`}`,
      'GET',
      undefined,
      signal,
    ),
  save: (id: string, messageId: string, signal?: AbortSignal) =>
    request<void>(`/conversations/${id}/bookmarks/${messageId}`, 'PUT', undefined, signal),
  unsave: (id: string, messageId: string, signal?: AbortSignal) =>
    request<void>(`/conversations/${id}/bookmarks/${messageId}`, 'DELETE', undefined, signal),
  clearBookmarks: (id: string, signal?: AbortSignal) =>
    request<void>(`/conversations/${id}/bookmarks`, 'DELETE', undefined, signal),
}
/** 搜索或收藏切片，不以空批次推断整个历史没有匹配。 */
export type MessageSlice = {
  /** 本批可读真实消息，按序号倒序。 */
  items: ChatMessage[]
  /** 下一批独占序号上界；为空时结束，即使 items 为空仍可能有值。 */
  nextBefore: number | null
}
export function socketUrl(): string {
  const url = new URL(`${base}/chat/ws`, window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}
/** HTTP 本地调试可生成 UUID，公网生产仍必须 HTTPS/WSS。 */
export function messageUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 15) | 64
  bytes[8] = (bytes[8]! & 63) | 128
  const h = Array.from(bytes, (n) => n.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
