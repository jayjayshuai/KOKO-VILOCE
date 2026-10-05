import { request } from './http'

/** 两域查询完成后的观察值，不是原子快照、公开授权或安全删除许可。 */
export type AssetReferenceSnapshot = {
  /** 包括草稿、已发布和停用主页的头像/封面引用。 */
  profileReferenced: boolean
  /** 包括草稿、已发布和归档文章封面引用。 */
  postReferenced: boolean
  /** 查询完成的带偏移 ISO 时间，服务端使用 UTC。 */
  checkedAt: string
}

export const assetReferencesApi = {
  /** 仅本人 READY 图片可查；不持久化查询结果，故障绝不显示为未使用。 */
  async inspect(id: string, signal?: AbortSignal): Promise<AssetReferenceSnapshot> {
    const result = await request<AssetReferenceSnapshot>(`/assets/images/${encodeURIComponent(id)}/references`, {
      signal,
      cache: 'no-store',
    })
    if (
      !result ||
      typeof result.profileReferenced !== 'boolean' ||
      typeof result.postReferenced !== 'boolean' ||
      typeof result.checkedAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.test(result.checkedAt) ||
      !Number.isFinite(Date.parse(result.checkedAt))
    ) {
      throw new Error('引用查询响应不完整，请重试。')
    }
    return result
  },
}
