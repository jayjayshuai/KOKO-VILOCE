import { request } from './http'
import { messageUuid } from './chat'

export type VoiceCommandType =
  | 'APPLY'
  | 'INVITE'
  | 'PULL'
  | 'ACCEPT'
  | 'REJECT'
  | 'CANCEL'
  | 'DOWN'
  | 'KICK'
  | 'MUTE'
  | 'LOCK'
  | 'LEAVE'
  | 'ADMIN'
  | 'TRANSFER'
export interface InteractionCapabilities {
  /** 核心开关，不等于正式运营或媒体就绪。 */ enabled: boolean
  /** 当前固定false，UI不请求麦克风/旧JWT。 */ mediaReady: boolean
  /** 单调版本字符串，禁用为空。 */ version: string | null
  /** 仅显隐快照请求，后端仍授权。 */ canInspect: boolean
}
export interface VoiceMember {
  /** 真实身份ID字符串。 */ userId: string
  /** 目录名称快照。 */ displayName: string
  /** 房间权限与HOST展示派生态。 */ role: string
  /** 有占用时席位号。 */ seatNo: number | null
}
export interface VoiceSeat {
  /** 1～8。 */ seatNo: number
  /** SQL状态不是实际音轨权限。 */ state: 'EMPTY' | 'LOCKED' | 'RESERVED' | 'ON_MIC'
  /** 占用者ID字符串，空位为null。 */ userId: string | null
  /** 希望闭麦值，非实际SDK状态。 */ muted: boolean
}
export interface SeatRequest {
  /** 服务端预约UUID。 */ id: string
  /** 目标席位。 */ seatNo: number
  /** 申请/受邀者ID字符串。 */ userId: string
  /** APPLY/INVITE。 */ type: 'APPLY' | 'INVITE'
  /** 数据库时区的预约期限。 */ expiresAt: string
}
export interface InteractionSnapshot {
  /** 房间ID字符串。 */ roomId: string
  /** 单调版本，不得转number。 */ version: string
  /** 数据库时钟。 */ serverTime: string
  /** 会话租约秒数。 */ leaseSeconds: number
  /** 仅本人当前会话UUID，不是登录令牌。 */ mySessionId: string | null
  /** 当前权限角色，未加入为null。 */ myRole: string | null
  /** 媒体未接入时false。 */ mediaReady: boolean
  /** 有有效租约的成员，不是RTC在线计数。 */ members: VoiceMember[]
  /** 管理方可见的长期房管，包括离线者。 */ administrators: VoiceMember[]
  /** 固定八麦位SQL事实。 */ seats: VoiceSeat[]
  /** 本人/管理方可见的有效预约。 */ requests: SeatRequest[]
}
export interface VoiceCommand {
  /** 新UUID，未知回复重试使用原参数。 */ requestId: string
  /** 本人当前成员会话。 */ sessionId: string
  /** 原快照版本字符串。 */ expectedVersion: string
  /** 明确操作类型。 */ type: VoiceCommandType
  /** 对应操作的麦位，非席位命令不传。 */ seatNo?: number
  /** 目标用户ID，适用操作才传。 */ targetUserId?: string
  /** 原预约UUID，适用操作才传。 */ seatRequestId?: string
  /** 明确希望值，不能只发“翻转”。 */ value?: boolean
}
export interface VoiceAck {
  /** 原已提交命令类型。 */ type: string
  /** 原提交版本，不是最新快照。 */ version: string
  /** 只有JOIN返回本人会话UUID。 */ sessionId: string | null
}
export interface VoiceSync {
  /** 回收后的版本字符串，禁转JS number。 */ version: string
  /** 本次数据库核验时间，不使用客户端时间判断数据库租约。 */ checkedAt: string
  /** 同版本为null，不代表成员列表为空。 */ snapshot: InteractionSnapshot | null
}
export interface VoiceReceipt {
  /** false不证明在途操作失败，不能自动换UUID。 */ committed: boolean
  /** 原提交结果，不是当前权限或会话。 */ ack: VoiceAck | null
}
/** 审计投影不含成员会话或媒体凭据。 */
export interface VoiceAction {
  /** 原房间版本字符串。 */ version: string
  /** 系统回收为null。 */ actorId: string | null
  /** 原命令名。 */ type: string
  /** 原目标，不是当前占用者。 */ targetUserId: string | null
  /** 原麦位。 */ seatNo: number | null
  /** 原预约UUID。 */ seatRequestId: string | null
  /** 原目标值。 */ value: boolean | null
  /** 数据库时间。 */ createdAt: string
}
export interface VoiceActionPage {
  /** 本页管理方可见事实。 */ items: VoiceAction[]
  /** 下一页独占版本字符串。 */ nextBefore: string | null
}
export const voiceInteractionApi = {
  actions: (room: string, before: string | null, signal?: AbortSignal) => {
    const query = new URLSearchParams({ size: '20' })
    if (before !== null) query.set('before', before)
    return request<VoiceActionPage>(`/voice/rooms/${encodeURIComponent(room)}/interaction/actions?${query}`, { signal })
  },
  features: (signal?: AbortSignal) =>
    request<InteractionCapabilities>('/voice/rooms/interaction-capabilities', { signal }),
  capabilities: (room: string, signal?: AbortSignal) =>
    request<InteractionCapabilities>(`/voice/rooms/${encodeURIComponent(room)}/interaction/capabilities`, { signal }),
  snapshot: (room: string, signal?: AbortSignal) =>
    request<InteractionSnapshot>(`/voice/rooms/${encodeURIComponent(room)}/interaction`, { signal }),
  sync: (room: string, knownVersion: string | null, signal?: AbortSignal) => {
    const query = new URLSearchParams()
    if (knownVersion !== null) query.set('knownVersion', knownVersion)
    return request<VoiceSync>(`/voice/rooms/${encodeURIComponent(room)}/interaction/sync?${query}`, { signal })
  },
  receipt: (room: string, requestId: string, signal?: AbortSignal) =>
    request<VoiceReceipt>(
      `/voice/rooms/${encodeURIComponent(room)}/interaction/receipts/${encodeURIComponent(requestId)}`,
      { signal },
    ),
  join: (room: string, input: { requestId: string; expectedVersion: string }, signal?: AbortSignal) =>
    request<VoiceAck>(`/voice/rooms/${encodeURIComponent(room)}/interaction/join`, {
      method: 'POST',
      body: JSON.stringify(input),
      signal,
    }),
  command: (room: string, input: VoiceCommand, signal?: AbortSignal) =>
    request<VoiceAck>(`/voice/rooms/${encodeURIComponent(room)}/interaction/commands`, {
      method: 'POST',
      body: JSON.stringify(input),
      signal,
    }),
  heartbeat: (room: string, sessionId: string, signal?: AbortSignal) =>
    request<void>(`/voice/rooms/${encodeURIComponent(room)}/interaction/heartbeat`, {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
      signal,
    }),
}
export { messageUuid as interactionUuid }
