import { request } from './http'

/** 只有两个提交域包含绑定释放表，不支持任意服务名或直播域。 */
export type BindingReleaseDomain = 'identity' | 'community'
export interface BindingReleaseCursor {
  /** 无时区业务库时间，微秒原值不能截断。 */
  createdAt: string
  /** 同时间内的原绑定请求 UUID。 */
  requestId: string
}
export interface BindingReleaseTask {
  /** 原绑定请求 UUID，不是通知 ID。 */
  requestId: string
  /** 受管资产 UUID，不含对象存储路径。 */
  assetId: string
  /** 原绑定用途。 */
  purpose: 'AVATAR' | 'BANNER' | 'POST_COVER'
  /** SENT 仅表示释放保护确认，不表示删除资产。 */
  status: 'PENDING' | 'LEASED' | 'SENT' | 'DEAD'
  /** 永久累计领取次数，最多110，人工恢复也不清零。 */
  attempts: number
  /** 已受理人工代次，0～10。 */
  replayGeneration: number
  /** 本代已用领取次数，0～10。 */
  generationAttempts: number
  /** 固定类别，可空，不包含原始异常。 */
  lastFailure: 'release-unconfirmed' | 'lease-exhausted' | 'unknown' | null
  /** 创建时间，业务库原值，无时区，不换算成本地时间。 */
  createdAt: string
  /** 最近状态变化时间，无时区。 */
  updatedAt: string
  /** 最早自动重试时间，无时区，不说明 DEAD 会自动重新排队。 */
  nextAttemptAt: string
}
export interface BindingReleasePage {
  /** 本页真实任务，固定每页二十条。 */
  items: BindingReleaseTask[]
  /** exclusive 下一页断点，无更多时为空。 */
  nextCursor: BindingReleaseCursor | null
}
export interface BindingReleaseSnapshot {
  /** 等待执行的有界数量，达到上限即为下界。 */
  pending: number
  /** 当前租约中的有界数量。 */
  leased: number
  /** 自动重试耗尽的有界数量。 */
  dead: number
  /** 每状态采样上限，固定 1001。 */
  sampleLimit: number
  /** 最旧 PENDING 的秒数，无任务为零。 */
  oldestPendingAgeSeconds: number
  /** 最旧 DEAD 的秒数，无任务为零。 */
  oldestDeadAgeSeconds: number
  /** 单一 SQL 的数据库时点，无时区。 */
  observedAt: string
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const time = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?$/
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const id = (value: unknown): value is string => typeof value === 'string' && uuid.test(value)
/** 不用 Date 转换无时区业务时间；验证真实日历，仍保留原六位小数字符串。 */
function stamp(value: unknown): value is string {
  if (typeof value !== 'string' || !time.test(value)) return false
  const [year, month, day, hour, minute, second] = value.slice(0, 19).split(/[-T:]/).map(Number)
  const leap = year! % 400 === 0 || (year! % 4 === 0 && year! % 100 !== 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return (
    year! >= 1000 &&
    month! >= 1 &&
    month! <= 12 &&
    day! >= 1 &&
    day! <= days[month! - 1]! &&
    hour! <= 23 &&
    minute! <= 59 &&
    second! <= 59
  )
}
/** 共享数据库 DATETIME 原值校验，不做时区转换或微秒截断。 */
export { stamp as validBindingTimestamp }
const integer = (value: unknown, max = Number.MAX_SAFE_INTEGER): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max

function task(value: unknown): BindingReleaseTask {
  if (
    !object(value) ||
    !id(value.requestId) ||
    !id(value.assetId) ||
    !['AVATAR', 'BANNER', 'POST_COVER'].includes(String(value.purpose)) ||
    !['PENDING', 'LEASED', 'SENT', 'DEAD'].includes(String(value.status)) ||
    !integer(value.attempts, 110) ||
    !integer(value.replayGeneration, 10) ||
    !integer(value.generationAttempts, 10) ||
    value.attempts < value.generationAttempts ||
    value.attempts > (value.replayGeneration + 1) * 10 ||
    (value.lastFailure !== null &&
      !['release-unconfirmed', 'lease-exhausted', 'unknown'].includes(String(value.lastFailure))) ||
    !stamp(value.createdAt) ||
    !stamp(value.updatedAt) ||
    !stamp(value.nextAttemptAt)
  ) {
    throw new Error('绑定释放响应不完整，不能显示成成功事实')
  }
  return value as unknown as BindingReleaseTask
}
function prefix(domain: BindingReleaseDomain) {
  if (!['identity', 'community'].includes(domain)) throw new Error('绑定释放业务域无效')
  return `/operations/binding-releases/${domain}`
}
export const bindingReleaseApi = {
  async dead(domain: BindingReleaseDomain, cursor: BindingReleaseCursor | null, signal?: AbortSignal) {
    const query = new URLSearchParams({ limit: '20' })
    if (cursor) {
      query.set('beforeCreatedAt', cursor.createdAt)
      query.set('beforeRequestId', cursor.requestId)
    }
    const value = await request<unknown>(`${prefix(domain)}/dead?${query}`, { signal, cache: 'no-store' })
    if (!object(value) || !Array.isArray(value.items) || value.items.length > 20)
      throw new Error('绑定释放分页响应无效')
    const items = value.items.map(task)
    const next = value.nextCursor
    if (
      items.some((item) => item.status !== 'DEAD') ||
      new Set(items.map((item) => item.requestId)).size !== items.length ||
      (next !== null &&
        (!object(next) ||
          !stamp(next.createdAt) ||
          !id(next.requestId) ||
          items.length !== 20 ||
          next.requestId !== items.at(-1)?.requestId ||
          next.createdAt !== items.at(-1)?.createdAt))
    ) {
      throw new Error('绑定释放游标响应无效')
    }
    return { items, nextCursor: next } as BindingReleasePage
  },
  async detail(domain: BindingReleaseDomain, requestId: string, signal?: AbortSignal) {
    if (!id(requestId)) throw new Error('原绑定请求必须为标准 UUID')
    const value = task(
      await request<unknown>(`${prefix(domain)}/tasks/${encodeURIComponent(requestId)}`, {
        signal,
        cache: 'no-store',
      }),
    )
    if (value.requestId !== requestId) throw new Error('绑定释放详情与原请求不匹配')
    return value
  },
  async snapshot(domain: BindingReleaseDomain, signal?: AbortSignal) {
    const value = await request<unknown>(`${prefix(domain)}/snapshot`, { signal, cache: 'no-store' })
    if (
      !object(value) ||
      value.sampleLimit !== 1001 ||
      !integer(value.pending, 1001) ||
      !integer(value.leased, 1001) ||
      !integer(value.dead, 1001) ||
      !integer(value.oldestPendingAgeSeconds) ||
      !integer(value.oldestDeadAgeSeconds) ||
      !stamp(value.observedAt)
    )
      throw new Error('绑定释放采样响应不完整，不能显示成零积压')
    return value as unknown as BindingReleaseSnapshot
  },
}
