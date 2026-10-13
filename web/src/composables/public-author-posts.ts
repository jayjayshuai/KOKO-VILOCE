import { onBeforeUnmount, ref, watch } from 'vue'
import { api, type CreatorPost } from '../api'
import { notificationDate } from '../services/notification-time'
/** 公开列表上下文不含凭据；登录轮次变化也清理在途读取。 */
export interface AuthorPostsContext {
  /** 当前已确认公开主页的作者ID。 */ ownerId: string
  /** 当前访问者，匿名缺省；不是作品归属。 */ userId?: string
  /** 同账号登录轮次。 */ sessionRevision: number
}
const validId = (id: unknown) =>
  typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
const count = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0
/** 转成固定微秒排序键，不用截断微秒的Date比较同一毫秒内的先后。 */
function timeKey(value: unknown) {
  if (typeof value !== 'string' || !notificationDate(value)) return null
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?$/.exec(value)
  return match ? `${match[1]}.${(match[2] || '').padEnd(6, '0')}` : null
}
function before(left: CreatorPost, right: CreatorPost) {
  const a = timeKey(left.publishedAt)!,
    b = timeKey(right.publishedAt)!
  return a < b || (a === b && BigInt(left.id) < BigInt(right.id))
}
/** 每次读取只接受当前作者公开摘要，失败重试原游标；游标不是固定数据库快照。 */
export function usePublicAuthorPosts(
  context: () => AuthorPostsContext,
  network: Pick<typeof api, 'authorPosts'> = api,
) {
  const items = ref<CreatorPost[]>([]),
    nextCursor = ref<string | null>(null),
    loaded = ref(false)
  const loading = ref(false),
    error = ref('')
  /** 后页不允许混入不同存储时区的投影。 */ const timeZone = ref<'UTC' | 'Asia/Shanghai' | null>(null)
  /** 上次失败读取的原边界，undefined为首读；不将后页失败重试变成第一页。 */ let failedAfter: string | undefined
  let failedReset = true,
    epoch = 0,
    disposed = false,
    controller = new AbortController()
  const active = (version: number, snapshot: AuthorPostsContext) => {
    const now = context()
    return (
      !disposed &&
      epoch === version &&
      snapshot.ownerId === now.ownerId &&
      snapshot.userId === now.userId &&
      snapshot.sessionRevision === now.sessionRevision
    )
  }
  async function load(reset = true, retryAfter?: string) {
    if (disposed || loading.value) return
    const snapshot = { ...context() },
      version = epoch
    if (!validId(snapshot.ownerId)) {
      error.value = '作者标识未确认，不能读取作品。'
      return
    }
    const after = reset ? undefined : (retryAfter ?? nextCursor.value ?? undefined)
    if (!reset && !after) return
    loading.value = true
    error.value = ''
    try {
      const result = await network.authorPosts(snapshot.ownerId, after, controller.signal)
      if (!active(version, snapshot)) return
      const rows = result?.items
      if (
        !result ||
        result.ownerId !== snapshot.ownerId ||
        result.size !== 12 ||
        !['UTC', 'Asia/Shanghai'].includes(result.timeZone) ||
        (!reset && timeZone.value !== result.timeZone) ||
        !Array.isArray(rows) ||
        rows.length > 12 ||
        (result.nextCursor !== null &&
          (typeof result.nextCursor !== 'string' ||
            !/^[A-Za-z0-9_-]{1,160}$/.test(result.nextCursor) ||
            result.nextCursor === after ||
            !rows.length)) ||
        rows.some(
          (row) =>
            !row ||
            row.ownerId !== snapshot.ownerId ||
            !validId(row.id) ||
            typeof row.slug !== 'string' ||
            !/^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$/.test(row.slug) ||
            typeof row.title !== 'string' ||
            !row.title.trim() ||
            row.title.length > 160 ||
            (row.excerpt != null && (typeof row.excerpt !== 'string' || row.excerpt.length > 300)) ||
            Object.hasOwn(row, 'body') ||
            (row.status !== undefined && row.status !== 'PUBLISHED') ||
            row.kind !== 'ARTICLE' ||
            !timeKey(row.publishedAt) ||
            !count(row.likeCount) ||
            !count(row.commentCount) ||
            !count(row.favoriteCount),
        ) ||
        new Set(rows.map((row) => row.id)).size !== rows.length ||
        rows.some((row, index) => index > 0 && !before(row, rows[index - 1]!)) ||
        (!reset && items.value.length && rows.length && !before(rows[0]!, items.value.at(-1)!))
      )
        throw new Error('作品响应的作者、公开摘要或游标顺序未确认，请重新读取。')
      items.value = reset ? [...rows] : [...items.value, ...rows]
      nextCursor.value = result.nextCursor
      loaded.value = true
      timeZone.value = result.timeZone
    } catch (cause) {
      if (active(version, snapshot)) {
        failedAfter = after
        failedReset = reset
        error.value = cause instanceof Error ? cause.message : '作品读取失败，请重试。'
      }
    } finally {
      if (active(version, snapshot)) loading.value = false
    }
  }
  const retry = () => load(failedReset, failedAfter)
  const stop = watch(
    () => {
      const value = context()
      return [value.ownerId, value.userId, value.sessionRevision]
    },
    () => {
      epoch++
      controller.abort()
      controller = new AbortController()
      items.value = []
      nextCursor.value = null
      loaded.value = false
      timeZone.value = null
      loading.value = false
      error.value = ''
      failedAfter = undefined
      failedReset = true
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    epoch++
    controller.abort()
    stop()
  })
  return { items, nextCursor, loaded, loading, error, timeZone, load, retry }
}
