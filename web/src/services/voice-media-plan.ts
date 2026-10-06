import { request } from './http'

/** 媒体计划仅为持久希望值与退场队列诊断，不能当成发声权限。 */
export interface VoiceMediaPlanView {
  /** SQL计划候选开关，禁用不是零任务完成。 */ tracked: boolean
  /** 本人媒体轮次字符串，无分配或禁用为null。 */ generation: string | null
  /** 本人希望接入状态，不是RTC在线。 */ active: boolean
  /** 席位希望允许发布，不是已生效的SFU权限。 */ publishDesired: boolean
  /** 全房间待退场任务数，未启用为null。 */ pendingRetirements: number | null
  /** 全房间预算耗尽任务数，未启用为null。 */ deadRetirements: number | null
  /** 当前必须false，客户端不能将候选进度当作媒体开放。 */ mediaReady: false
}
export function validateVoiceMediaPlan(value: unknown): VoiceMediaPlanView {
  const data = value as VoiceMediaPlanView
  if (
    !data ||
    typeof data.tracked !== 'boolean' ||
    typeof data.active !== 'boolean' ||
    typeof data.publishDesired !== 'boolean' ||
    data.mediaReady !== false
  )
    throw new Error('媒体计划响应格式不一致')
  if (!data.tracked) {
    if (
      data.generation !== null ||
      data.active ||
      data.publishDesired ||
      data.pendingRetirements !== null ||
      data.deadRetirements !== null
    )
      throw new Error('禁用媒体计划不能宣称任务已完成')
  } else {
    if (
      data.generation !== null &&
      (typeof data.generation !== 'string' ||
        !/^[1-9][0-9]{0,18}$/.test(data.generation) ||
        BigInt(data.generation) > 9223372036854775807n)
    )
      throw new Error('媒体授权轮次无效')
    if ((data.generation === null && (data.active || data.publishDesired)) || (data.publishDesired && !data.active))
      throw new Error('媒体计划状态矛盾')
    for (const count of [data.pendingRetirements, data.deadRetirements])
      if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0 || count > 1000000)
        throw new Error('媒体退场任务计数无效或超出展示上限')
  }
  return data
}
export const voiceMediaPlanApi = {
  async get(roomId: string, signal?: AbortSignal) {
    return validateVoiceMediaPlan(
      await request<unknown>(`/voice/rooms/${encodeURIComponent(roomId)}/interaction/media-plan`, {
        signal,
        cache: 'no-store',
      }),
    )
  },
}
