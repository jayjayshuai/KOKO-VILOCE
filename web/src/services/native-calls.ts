import { request } from './http'
import { messageUuid } from './chat'

export type NativeCallCommandType = 'ACCEPT' | 'DECLINE' | 'LEAVE' | 'END'
export interface NativeCallView {
  /** 原通话UUID，不是公开语音房ID。 */ id: string
  /** 原聊天UUID。 */ conversationId: string
  /** 发起者ID字符串，不得转number。 */ initiatorId: string
  /** ACTIVE表示已接听，不等于音频已连接。 */ state: 'RINGING' | 'ACTIVE' | 'ENDED'
  /** 控制版本；超出JS安全整数拒绝操作，不做精度截断。 */ version: number
  /** 固定结束原因，可空。 */ endReason: string | null
  /** 数据库UTC时间，无时区后缀时按UTC解析。 */ ringExpiresAtUtc: string
  /** 明确邀请的参与者，不代表音轨在线。 */ participants: {
    /** 平台ID字符串，不得转number。 */
    userId: string
    /** 邀请时的显示名，不含邮箱。 */
    displayName: string
    /** 明确邀请/接听/退出事实，不代表已开麦。 */
    state: 'INVITED' | 'ACCEPTED' | 'DECLINED' | 'LEFT' | 'CANCELLED' | 'MISSED'
  }[]
}
export interface NativeCallInvite {
  /** 首次生成的原请求UUID，失败重试不改变。 */ requestId: string
  /** 固定原聊天。 */ conversationId: string
  /** 群内明确选择的1～7位用户ID；私信无需填写。 */ targets?: string[]
}
export interface NativeCallCommand {
  /** 固定原通话。 */ callId: string
  /** 固定原命令UUID。 */ requestId: string
  /** 首次提交时核对过的版本，重试不更新。 */ expectedVersion: number
  /** 原动作不可更换。 */ type: NativeCallCommandType
}
export interface NativeCallResult {
  /** 原命令UUID。 */ requestId: string
  /** 原命令应用时版本，不是当前版本。 */ appliedVersion: number
  /** 当前事实，不用旧ACCEPT收据复活已结束通话。 */ current: NativeCallView
}
export interface NativeCallCredential {
  /** 原通话UUID。 */ callId: string
  /** 同源受控WSS入口。 */ url: string
  /** 短期令牌，仅内存使用。 */ token: string
  /** 初次入会有效秒数。 */ expiresInSeconds: number
}
export interface NativeCallPort {
  capability(signal?: AbortSignal): Promise<{ enabled: boolean }>
  current(signal?: AbortSignal): Promise<NativeCallView | undefined>
  read(id: string, signal?: AbortSignal): Promise<NativeCallView>
  invite(input: NativeCallInvite): Promise<NativeCallView>
  command(input: NativeCallCommand): Promise<NativeCallResult>
  heartbeat(id: string): Promise<void>
}
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
const userId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
/** 响应不合法不冒充“无来电”，也不以损坏的身份/版本继续签发或写入。 */
export function nativeCallView(value: unknown): NativeCallView {
  const row = value as NativeCallView
  if (
    !row ||
    !uuid(row.id) ||
    !uuid(row.conversationId) ||
    !userId(row.initiatorId) ||
    !['RINGING', 'ACTIVE', 'ENDED'].includes(row.state) ||
    !Number.isSafeInteger(row.version) ||
    row.version < 1 ||
    !(row.endReason === null || typeof row.endReason === 'string') ||
    typeof row.ringExpiresAtUtc !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$/.test(row.ringExpiresAtUtc) ||
    !Number.isFinite(Date.parse(row.ringExpiresAtUtc + 'Z')) ||
    !Array.isArray(row.participants) ||
    row.participants.length < 2 ||
    row.participants.length > 8 ||
    row.participants.some(
      (p) =>
        !p ||
        !userId(p.userId) ||
        typeof p.displayName !== 'string' ||
        !p.displayName.trim() ||
        p.displayName.length > 80 ||
        !['INVITED', 'ACCEPTED', 'DECLINED', 'LEFT', 'CANCELLED', 'MISSED'].includes(p.state),
    ) ||
    new Set(row.participants.map((p) => p.userId)).size !== row.participants.length ||
    !row.participants.some((p) => p.userId === row.initiatorId)
  )
    throw new Error('通话状态响应无效，请重新核对。')
  return { ...row, participants: row.participants.map((p) => ({ ...p })) }
}
const prefix = '/chat/calls'
export const nativeCallApi: NativeCallPort & {
  credential(id: string, signal?: AbortSignal): Promise<NativeCallCredential>
} = {
  capability: (signal) => request(`${prefix}/capabilities`, { signal, cache: 'no-store' }),
  current: async (signal) => {
    const row = await request<unknown>(`${prefix}/current`, { signal, cache: 'no-store' })
    return row == null ? undefined : nativeCallView(row)
  },
  read: async (id, signal) =>
    nativeCallView(await request(`${prefix}/${encodeURIComponent(id)}`, { signal, cache: 'no-store' })),
  invite: async (input) => nativeCallView(await request(prefix, { method: 'POST', body: JSON.stringify(input) })),
  command: async (input) => {
    const { callId, ...body } = input
    const result = await request<NativeCallResult>(`${prefix}/${encodeURIComponent(callId)}/commands`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    const current = nativeCallView(result?.current)
    if (
      result.requestId !== input.requestId ||
      current.id !== callId ||
      !Number.isSafeInteger(result.appliedVersion) ||
      result.appliedVersion < 1 ||
      result.appliedVersion > current.version
    )
      throw new Error('原通话命令结果未确认，请使用原请求重试。')
    return { ...result, current }
  },
  heartbeat: (id) => request(`${prefix}/${encodeURIComponent(id)}/heartbeat`, { method: 'POST' }),
  credential: (id, signal) =>
    request(`${prefix}/${encodeURIComponent(id)}/media-credentials`, { method: 'POST', signal }),
}
export const nativeCallRequestId = messageUuid
