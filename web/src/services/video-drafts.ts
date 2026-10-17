import { apiBaseUrl, request } from './http'
import { messageUuid } from './chat'

export interface VideoDraft {
  /** 本人私有视频UUID。 */ id: string
  /** 原上传请求UUID，不是网站令牌。 */ requestId: string
  /** 私有标题。 */ title: string
  /** 私有说明。 */ description: string
  /** READY才允许本人预览；DELETED才表示清理核验及额度回收完成。 */ state:
    'UPLOADING' | 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'DELETING' | 'DELETED'
  /** 原文件字节数。 */ sourceBytes: number
  /** 成功产物字节数，可空。 */ outputBytes: number | null
  /** 成功产物毫秒时长，可空。 */ durationMillis: number | null
  /** 成功产物像素宽，可空。 */ width: number | null
  /** 成功产物像素高，可空。 */ height: number | null
  /** 固定失败代码，可空。 */ failureCode: string | null
  /** 数据库UTC时间。 */ createdAtUtc: string
  /** 数据库UTC最近变化时间。 */ updatedAtUtc: string
  /** 原删除请求UUID，旧服务或未删除可空。 */ deleteRequestId: string | null
  /** 固定删除等待原因，不包含私有对象键。 */ deletionWaitReason: string | null
}
export interface VideoDraftCapability {
  /** 配置及程序已就绪，不是公开发布能力。 */ enabled: boolean
  /** 最大原文件字节数。 */ maxBytes: number
  /** 最大时长秒。 */ maxSeconds: number
  /** 本人含失败草稿最多数量。 */ maxDrafts: number
  /** 每条含源文件和产物的固定预留字节数。 */ reservedBytesPerDraft: number
  /** 单独删除门禁，旧服务未返回时视为关闭。 */ deletionEnabled?: boolean
}
export interface VideoDraftUpload {
  /** 首次提交UUID，所有重试保持不变。 */ requestId: string
  /** 首次确认标题。 */ title: string
  /** 首次确认说明。 */ description: string
  /** 原文件引用只留内存，不写浏览器持久存储。 */ file: File
}
export interface VideoDraftPort {
  capability(signal?: AbortSignal): Promise<VideoDraftCapability>
  list(signal?: AbortSignal): Promise<VideoDraft[]>
  upload(input: VideoDraftUpload): Promise<VideoDraft>
  read(id: string, signal?: AbortSignal): Promise<VideoDraft>
  delete(id: string, requestId: string): Promise<VideoDraft>
}
const canonical = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
/** 损坏响应不能冒充可播放产物或“没有草稿”。 */
export function videoDraft(value: unknown): VideoDraft {
  const row = value as VideoDraft
  if (
    !row ||
    !canonical(row.id) ||
    !canonical(row.requestId) ||
    typeof row.title !== 'string' ||
    !row.title.trim() ||
    row.title.length > 100 ||
    typeof row.description !== 'string' ||
    row.description.length > 1000 ||
    !['UPLOADING', 'QUEUED', 'PROCESSING', 'READY', 'FAILED', 'DELETING', 'DELETED'].includes(row.state) ||
    !Number.isSafeInteger(row.sourceBytes) ||
    row.sourceBytes < 16 ||
    row.sourceBytes > 33554432 ||
    typeof row.createdAtUtc !== 'string' ||
    typeof row.updatedAtUtc !== 'string'
  )
    throw new Error('视频状态响应无效，请重新核对。')
  if (
    (row.deleteRequestId != null && !canonical(row.deleteRequestId)) ||
    (['DELETING', 'DELETED'].includes(row.state) && !canonical(row.deleteRequestId)) ||
    (row.deletionWaitReason != null &&
      ![
        'LEGACY_WRITES_UNVERIFIED',
        'WRITES_UNVERIFIED',
        'WAIT_PROCESSING',
        'CLEANUP_FAILED',
        'STORAGE_UNVERIFIED',
      ].includes(row.deletionWaitReason))
  )
    throw new Error('视频删除状态响应无效。')
  if (
    row.state === 'READY' &&
    (!Number.isSafeInteger(row.outputBytes) ||
      row.outputBytes! < 16 ||
      row.outputBytes! > 67108864 ||
      !Number.isSafeInteger(row.durationMillis) ||
      row.durationMillis! < 100 ||
      row.durationMillis! > 180000 ||
      !Number.isSafeInteger(row.width) ||
      row.width! < 2 ||
      row.width! > 1280 ||
      !Number.isSafeInteger(row.height) ||
      row.height! < 2 ||
      row.height! > 720)
  )
    throw new Error('视频产物尚未得到有效确认。')
  return { ...row, deleteRequestId: row.deleteRequestId ?? null, deletionWaitReason: row.deletionWaitReason ?? null }
}
const prefix = '/assets/videos'
export const videoDraftApi: VideoDraftPort = {
  capability: (signal) => request(`${prefix}/capabilities`, { signal, cache: 'no-store' }),
  list: async (signal) => {
    const rows = await request<unknown>(prefix, { signal, cache: 'no-store' })
    if (!Array.isArray(rows) || rows.length > 5) throw new Error('视频草稿列表响应无效。')
    const result = rows.map(videoDraft)
    if (new Set(result.map((row) => row.id)).size !== result.length || result.some((row) => row.state === 'DELETED'))
      throw new Error('视频草稿列表存在重复或已清理记录。')
    return result
  },
  upload: async (input) => {
    const form = new FormData()
    form.set('requestId', input.requestId)
    form.set('title', input.title)
    form.set('description', input.description)
    form.set('file', input.file)
    const row = videoDraft(await request(prefix, { method: 'POST', body: form, timeoutMs: 90000 }))
    if (row.requestId !== input.requestId) throw new Error('原上传结果尚未确认，请使用原请求重试。')
    return row
  },
  read: async (id, signal) => {
    if (!canonical(id)) throw new Error('视频ID无效')
    const row = videoDraft(await request(`${prefix}/${id}`, { signal, cache: 'no-store' }))
    if (row.id !== id) throw new Error('视频查询结果不匹配。')
    return row
  },
  delete: async (id, requestId) => {
    if (!canonical(id) || !canonical(requestId)) throw new Error('视频删除请求无效')
    const row = videoDraft(
      await request(`${prefix}/${id}/deletion`, { method: 'POST', body: JSON.stringify({ requestId }) }),
    )
    if (row.id !== id || row.deleteRequestId !== requestId || !['DELETING', 'DELETED'].includes(row.state))
      throw new Error('原删除请求结果尚未确认。')
    return row
  },
}
/** 同源Cookie授权，不返回MinIO地址、对象键或带令牌的URL。 */
export const videoPreviewUrl = (id: string) => {
  if (!canonical(id)) throw new Error('视频ID无效')
  return `${apiBaseUrl}${prefix}/${id}/content`
}
export const videoUploadRequestId = messageUuid
