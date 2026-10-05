import { request } from './http'
import { validBindingTimestamp } from './binding-releases'
import type { BindingReleaseDomain } from './binding-releases'
/** 浏览器秘密传输保护，不替代服务端TLS/代理审批；环回例外仅用于隔离验证。 */
export function bindingRecoverySecureOrigin(): boolean {
  const origin = globalThis.location
  return (
    !!origin &&
    (origin.protocol === 'https:' ||
      (origin.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)))
  )
}
function requireSecureOrigin() {
  if (!bindingRecoverySecureOrigin())
    throw new Error('当前非HTTPS环境不允许发送运营密码或确认票据；本机环回仅用于隔离验收')
}
export interface BindingRecoveryCommand {
  /** 人工受理UUID，结果未知时不能重新生成。 */ commandId: string
  /** 已提交业务的原绑定UUID。 */ requestId: string
  /** 已明确确认的旧代次。 */ expectedGeneration: number
  /** 排障工单和恢复原因，规范化后不可改。 */ reason: string
}
export interface BindingRecoveryReceipt {
  /** 原人工命令UUID。 */ commandId: string
  /** 原绑定UUID。 */ requestId: string
  /** 实际受理代次，不说明释放已完成。 */ acceptedGeneration: number
  /** 业务数据库时间原值，无时区。 */ acceptedAt: string
}
export interface BindingRecoveryAudit {
  /** 原人工命令UUID。 */ commandId: string
  /** 原绑定UUID。 */ requestId: string
  /** 当前真实操作者ID，不能转number。 */ operatorId: string
  /** 确认旧代次。 */ expectedGeneration: number
  /** 受理新代次。 */ acceptedGeneration: number
  /** 受理前累计尝试。 */ previousAttempts: number
  /** 受理前本代尝试。 */ previousGenerationAttempts: number
  /** 固定失败类别，可空。 */ previousFailure: string | null
  /** 审计原因，Vue按纯文本输出。 */ reason: string
  /** 受理时间原值，无时区。 */ createdAt: string
}
export interface BindingRecoveryAuditPage {
  /** 本页审计，固定每页5条。 */ items: BindingRecoveryAudit[]
  /** exclusive代次，无更多为空。 */ nextGeneration: number | null
}
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const id = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, max: number): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= max
function prefix(domain: BindingReleaseDomain) {
  if (!['identity', 'community'].includes(domain)) throw new Error('恢复域无效')
  return '/operations/binding-releases/' + domain
}
function target(requestId: string, commandId?: string) {
  if (!id(requestId) || (commandId !== undefined && !id(commandId))) throw new Error('原目标或命令UUID无效')
}
/** 重录原命令也必须完整校验，不生成替代幂等键。 */
export function validateBindingRecoveryCommand(value: BindingRecoveryCommand) {
  target(value.requestId, value.commandId)
  if (
    !integer(value.expectedGeneration, 10) ||
    typeof value.reason !== 'string' ||
    value.reason !== value.reason.trim() ||
    Array.from(value.reason).length < 10 ||
    value.reason.length > 500
  )
    throw new Error('恢复命令无效')
}
function audit(value: unknown, requestId: string): BindingRecoveryAudit {
  if (
    !object(value) ||
    !id(value.commandId) ||
    value.requestId !== requestId ||
    typeof value.operatorId !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(value.operatorId) ||
    !integer(value.expectedGeneration, 9) ||
    value.acceptedGeneration !== value.expectedGeneration + 1 ||
    !integer(value.previousAttempts, 110) ||
    !integer(value.previousGenerationAttempts, 10) ||
    value.previousAttempts < value.previousGenerationAttempts ||
    value.previousAttempts > (value.expectedGeneration + 1) * 10 ||
    (value.previousFailure !== null &&
      !['release-unconfirmed', 'lease-exhausted', 'unknown'].includes(String(value.previousFailure))) ||
    typeof value.reason !== 'string' ||
    Array.from(value.reason).length < 10 ||
    value.reason.length > 500 ||
    !validBindingTimestamp(value.createdAt)
  )
    throw new Error('恢复审计响应无效')
  return value as unknown as BindingRecoveryAudit
}
export const bindingRecoveryApi = {
  async confirm(domain: BindingReleaseDomain, value: BindingRecoveryCommand, password: string, signal?: AbortSignal) {
    requireSecureOrigin()
    validateBindingRecoveryCommand(value)
    const result = await request<unknown>(prefix(domain) + '/confirmations', {
      method: 'POST',
      body: JSON.stringify({ command: value, password }),
      cache: 'no-store',
      signal,
    })
    if (
      !object(result) ||
      typeof result.confirmationToken !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(result.confirmationToken) ||
      !validBindingTimestamp(result.expiresAt)
    )
      throw new Error('资产恢复确认响应无效')
    return { confirmationToken: result.confirmationToken, expiresAt: result.expiresAt }
  },
  async replay(
    domain: BindingReleaseDomain,
    value: BindingRecoveryCommand,
    confirmationToken: string,
    signal?: AbortSignal,
  ) {
    requireSecureOrigin()
    validateBindingRecoveryCommand(value)
    if (!/^[A-Za-z0-9_-]{43}$/.test(confirmationToken)) throw new Error('恢复确认秘密无效')
    const result = await request<unknown>(prefix(domain) + '/replays', {
      method: 'POST',
      body: JSON.stringify({ command: value, confirmationToken }),
      cache: 'no-store',
      signal,
    })
    if (
      !object(result) ||
      result.commandId !== value.commandId ||
      result.requestId !== value.requestId ||
      result.acceptedGeneration !== value.expectedGeneration + 1 ||
      !validBindingTimestamp(result.acceptedAt)
    )
      throw new Error('受理响应不匹配，保留原命令查询')
    return result as unknown as BindingRecoveryReceipt
  },
  async receipt(domain: BindingReleaseDomain, requestId: string, commandId: string, signal?: AbortSignal) {
    target(requestId, commandId)
    const value = audit(
      await request<unknown>(prefix(domain) + '/tasks/' + requestId + '/commands/' + commandId, {
        cache: 'no-store',
        signal,
      }),
      requestId,
    )
    if (value.commandId !== commandId) throw new Error('受理审计不匹配原人工命令')
    return value
  },
  async audits(domain: BindingReleaseDomain, requestId: string, beforeGeneration: number | null, signal?: AbortSignal) {
    target(requestId)
    if (beforeGeneration !== null && (!integer(beforeGeneration, 11) || beforeGeneration < 1))
      throw new Error('审计游标无效')
    const query = new URLSearchParams({ limit: '5' })
    if (beforeGeneration !== null) query.set('beforeGeneration', String(beforeGeneration))
    const value = await request<unknown>(prefix(domain) + '/tasks/' + requestId + '/audits?' + query, {
      cache: 'no-store',
      signal,
    })
    if (!object(value) || !Array.isArray(value.items) || value.items.length > 5) throw new Error('审计页无效')
    const items = value.items.map((item) => audit(item, requestId))
    if (
      new Set(items.map((item) => item.acceptedGeneration)).size !== items.length ||
      items.some(
        (item, n) =>
          (beforeGeneration !== null && item.acceptedGeneration >= beforeGeneration) ||
          (n > 0 && item.acceptedGeneration >= items[n - 1]!.acceptedGeneration),
      ) ||
      (value.nextGeneration !== null &&
        (items.length !== 5 || value.nextGeneration !== items.at(-1)?.acceptedGeneration))
    )
      throw new Error('审计页顺序或游标无效')
    return { items, nextGeneration: value.nextGeneration } as BindingRecoveryAuditPage
  },
}
