import { request } from './http'

/** 三个后端固定 group，不接受任意表名、服务名或 SQL。 */
export type OutboxDomain = 'identity' | 'community' | 'live'
export interface OperationsAccess {
  /** 服务端运营开关。 */
  enabled: boolean
  /** 当前有效角色，仅用于页面展示。 */
  roles: string[]
  /** 当前事实权限，写操作仍由后端再次验证。 */
  permissions: string[]
}
export interface OutboxCursor {
  /** 原业务库 DATETIME 字符串，不擅自加时区或截断微秒。 */
  createdAt: string
  /** 同一创建时间内的 UUID 断点。 */
  eventId: string
}
export interface OutboxEvent {
  /** 原事件 UUID。 */
  id: string
  /** 原通知类型。 */
  eventType: string
  /** 目标账号 ID，不能转换成 JS number。 */
  recipientId: string
  /** 原业务操作者 ID。 */
  actorId: string
  /** 原业务资源 ID。 */
  resourceId: string
  /** 仅当前运营读权限可见的摘要。 */
  summary: string
  /** 当前投递状态，不是消费送达状态。 */
  status: 'DEAD' | 'PENDING' | 'RETRY' | 'IN_FLIGHT' | 'SENT'
  /** 当前轮次自动投递尝试数。 */
  attempts: number
  /** 累计次数十进制字符串，保留精度。 */
  totalAttempts: string
  /** 当前人工重放代次。 */
  replayGeneration: number
  /** 原最后失败，可空。 */
  lastError: string | null
  /** 业务库时间原值，不含时区。 */
  createdAt: string
  /** 业务库下一尝试时间。 */
  nextAttemptAt: string
  /** 业务库发送确认时间，可空，不代表消费完成。 */
  sentAt: string | null
}
export interface OutboxAudit {
  /** 原幂等受理 UUID。 */
  requestId: string
  /** 原事件 UUID。 */
  eventId: string
  /** 受理的真实操作者 ID。 */
  operatorId: string
  /** 原确认代次。 */
  expectedGeneration: number
  /** 已受理的新代次。 */
  acceptedGeneration: number
  /** 保存的原因，不包含密码。 */
  reason: string
  /** 受理前本轮尝试数。 */
  previousAttempts: number
  /** 累计次数快照，字符串。 */
  totalAttemptsSnapshot: string
  /** 原失败快照，可空。 */
  previousError: string | null
  /** 业务库受理时间，不含时区。 */
  createdAt: string
}
export interface ReplayCommand {
  /** 新确认时生成，超时或重试绝不能重新生成。 */
  requestId: string
  /** 原事件 UUID。 */
  eventId: string
  /** 读取并明确确认的人工代次。 */
  expectedGeneration: number
  /** 10～500 字符原因，规范化 strip 后不再修改。 */
  reason: string
}
export interface ReplayReceipt {
  /** 实际保存的原受理 UUID。 */
  requestId: string
  /** 原事件 UUID。 */
  eventId: string
  /** 已受理代次，不因投递后状态改变。 */
  generation: number
  /** 业务库原受理时间，不是消费完成时间。 */
  acceptedAt: string
}

function prefix(domain: OutboxDomain) {
  if (!['identity', 'community', 'live'].includes(domain)) throw new Error('运营业务域无效')
  return `/operations/outbox/${domain}`
}
function eventPath(domain: OutboxDomain, event: string) {
  return `${prefix(domain)}/events/${encodeURIComponent(event)}`
}
export const operationsApi = {
  access: (signal?: AbortSignal) => request<OperationsAccess>('/operations/access', { signal, cache: 'no-store' }),
  dead(domain: OutboxDomain, cursor: OutboxCursor | null, signal?: AbortSignal) {
    const query = new URLSearchParams({ limit: '20' })
    if (cursor) {
      query.set('beforeCreatedAt', cursor.createdAt)
      query.set('beforeEventId', cursor.eventId)
    }
    return request<{ items: OutboxEvent[]; nextCursor: OutboxCursor | null }>(`${prefix(domain)}/dead?${query}`, {
      signal,
      cache: 'no-store',
    })
  },
  detail: (domain: OutboxDomain, event: string, signal?: AbortSignal) =>
    request<OutboxEvent>(eventPath(domain, event), { signal, cache: 'no-store' }),
  audits(domain: OutboxDomain, event: string, before: number | null, signal?: AbortSignal) {
    // 单事件至多十代；每页五条，避免完整失败快照一次铺满工作台。
    const query = new URLSearchParams({ limit: '5' })
    if (before !== null) query.set('beforeGeneration', String(before))
    return request<{ items: OutboxAudit[]; nextGeneration: number | null }>(
      `${eventPath(domain, event)}/audits?${query}`,
      { signal, cache: 'no-store' },
    )
  },
  receipt: (domain: OutboxDomain, event: string, requestId: string, signal?: AbortSignal) =>
    request<OutboxAudit>(`${eventPath(domain, event)}/requests/${encodeURIComponent(requestId)}`, {
      signal,
      cache: 'no-store',
    }),
  confirm: (domain: OutboxDomain, command: ReplayCommand, password: string, signal?: AbortSignal) =>
    request<{ confirmationToken: string; expiresAt: string }>('/operations/replay-confirmations', {
      method: 'POST',
      body: JSON.stringify({ domain, command, password }),
      signal,
      cache: 'no-store',
    }),
  replay: (domain: OutboxDomain, command: ReplayCommand, confirmationToken: string, signal?: AbortSignal) =>
    request<ReplayReceipt>(`${prefix(domain)}/replays`, {
      method: 'POST',
      body: JSON.stringify({ command, confirmationToken }),
      signal,
      cache: 'no-store',
    }),
}
