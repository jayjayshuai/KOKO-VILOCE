import { request } from './http'
import type { VoiceJoinCredential } from '../api'

/** 服务端当前绑定的签发投影；不把canPublish当成设备或音轨实际状态。 */
export interface ControlledMediaCredential extends VoiceJoinCredential {
  /** 必须是请求时本人互动会话。 */ sessionId: string
  /** 单调媒体轮次字符串，不能转number。 */ generation: string
  /** 听众为空，其他为当前本人1～8麦位。 */ seatNo: number | null
  /** 只允许麦克风发布，不代表已经打开麦克风。 */ canPublish: boolean
  /** 初次入会有效期秒，不作为客户端绕过服务端授权的时钟。 */ expiresInSeconds: number
}
export const voiceMediaCredentialsApi = {
  capability: (signal?: AbortSignal) =>
    request<{ enabled: boolean }>('/voice/rooms/interaction-media-capabilities', { signal, cache: 'no-store' }),
  issue: (room: string, sessionId: string, expectedVersion: string, signal?: AbortSignal) =>
    request<ControlledMediaCredential>(`/voice/rooms/${encodeURIComponent(room)}/interaction/media-credentials`, {
      method: 'POST',
      body: JSON.stringify({ sessionId, expectedVersion }),
      signal,
      cache: 'no-store',
    }),
}
