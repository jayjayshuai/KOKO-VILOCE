import type { ChatMessage } from './chat'
import { request as httpRequest } from './http'

/** 本人的主动拉黑关系，目标 ID 保持字符串。 */
export type ChatBlock = {
  /** 拉黑记录 UUID，分页游标。 */
  id: string
  /** 目标用户 ID，不转换为 number。 */
  targetId: string
  /** 目标公开用户名快照。 */
  handle: string
  /** 目标公开显示名称快照。 */
  displayName: string
  /** 服务端时间，Asia/Shanghai。 */
  createdAt: string
}
export type ReportReason = 'HARASSMENT' | 'SPAM' | 'THREAT' | 'OTHER'
export type ReportStatus = 'PENDING' | 'RESOLVED' | 'REJECTED'
/** 本人可查询的举报进度，不包含消息证据或审核员身份。 */
export type ChatReport = {
  /** 举报 UUID。 */
  id: string
  /** 被举报消息 UUID。 */
  messageId: string
  /** 消息所属会话 UUID。 */
  conversationId: string
  /** 被举报消息发送者 ID 字符串。 */
  reportedUserId: string
  /** 举报原因枚举。 */
  reason: ReportReason
  /** 本人填写的说明，最多 500 字符。 */
  detail: string
  /** 当前处理状态，前端不能自行结案。 */
  status: ReportStatus
  /** 乐观锁版本；审核时提交本次读取的值。 */
  version: number
  /** 审核说明；待处理时为空。 */
  reviewNote: string | null
  /** 审核时间；待处理时为空。 */
  reviewedAt: string | null
  /** 服务端提交时间，Asia/Shanghai。 */
  createdAt: string
}
/** 只能从受 Sa-Token 审核权限保护的接口获取。 */
export type ModerationItem = {
  /** 举报事实与进度。 */
  report: ChatReport
  /** 举报人 ID，仅审核员可见。 */
  reporterId: string
  /** 服务端消息正文快照，禁止写日志或本地存储。 */
  evidenceBody: string
}
type Capabilities = { /** 是否显示人工审核入口；不替代服务端权限检查。 */ moderation: boolean }
/** 游标保持字符串并编码，不能拼接未编码的查询参数。 */
const cursor = (after?: string) => (after ? `&after=${encodeURIComponent(after)}` : '')

/** 聊天域仍保持原路径与协议，共享 Cookie / 超时 / 取消 / 空响应处理。 */
function request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  return httpRequest<T>(`/chat${path}`, { method, signal, body: body === undefined ? undefined : JSON.stringify(body) })
}
export const chatSafetyApi = {
  capabilities: (signal?: AbortSignal) => request<Capabilities>('/safety/capabilities', 'GET', undefined, signal),
  blocks: (after?: string, signal?: AbortSignal) =>
    request<ChatBlock[]>(`/blocks?size=100${cursor(after)}`, 'GET', undefined, signal),
  block: (handle: string, signal?: AbortSignal) => request<ChatBlock>('/blocks', 'POST', { handle }, signal),
  unblock: (targetId: string, signal?: AbortSignal) =>
    request<void>(`/blocks/${encodeURIComponent(targetId)}`, 'DELETE', undefined, signal),
  report: (message: ChatMessage, reason: ReportReason, detail: string, signal?: AbortSignal) =>
    request<ChatReport>(
      '/reports',
      'POST',
      { conversationId: message.conversationId, messageId: message.id, reason, detail },
      signal,
    ),
  reports: (after?: string, signal?: AbortSignal) =>
    request<ChatReport[]>(`/reports?size=50${cursor(after)}`, 'GET', undefined, signal),
  queue: (status: ReportStatus, after?: string, signal?: AbortSignal) =>
    request<ModerationItem[]>(`/moderation/reports?status=${status}&size=50${cursor(after)}`, 'GET', undefined, signal),
  review: (report: ChatReport, decision: 'RESOLVED' | 'REJECTED', note: string, signal?: AbortSignal) =>
    request<ChatReport>(
      `/moderation/reports/${encodeURIComponent(report.id)}`,
      'PATCH',
      { version: report.version, decision, note },
      signal,
    ),
}
