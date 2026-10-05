import { request } from './http'
import type { VoiceRoom } from '../api'

/** 本人全部状态房间；游标仍为字符串，禁止丢失雪花ID精度。 */
export interface OwnedVoiceRooms {
  /** 本页本人真实房间，不含供应商房间名或JWT。 */
  items: VoiceRoom[]
  /** 下一页独占ID；null表示本轮末页。 */
  nextBefore: string | null
}

/** 私有用例依赖；测试可替换网络，不替换页面状态逻辑。 */
export interface VoiceOwnerApi {
  /** 不接受房主参数，由Cookie/网关确认身份。 */
  list(before: string | null, signal?: AbortSignal): Promise<OwnedVoiceRooms>
  /** 仅204或兼容成功响应确认关闭；取消请求不撤销服务端写入。 */
  close(roomId: string, signal?: AbortSignal): Promise<void>
}

export const voiceOwnerApi: VoiceOwnerApi = {
  list: (before, signal) => {
    const query = new URLSearchParams({ size: '20' })
    if (before !== null) query.set('before', before)
    return request<OwnedVoiceRooms>(`/voice/rooms/mine?${query}`, { signal })
  },
  close: (roomId, signal) => request<void>(`/voice/rooms/${encodeURIComponent(roomId)}`, { method: 'DELETE', signal }),
}
