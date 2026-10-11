import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, ApiRequestError, type CreatorPost } from '../api'

/** 原归档目标只在内存中保留，不包含正文或会话凭据。 */
interface ArchiveAttempt {
  /** 原内容Long字符串。 */ id: string
  /** 原所有者，用于恢复再次检查归属。 */ ownerId: string
  /** 已发送版本；未知请求不得改为新版本重试。 */ version: number
}

/** 归档仅按确认版本发送一次；未知结果先读事实，跨身份不得回填。 */
export function useCreatorContentActions(
  context: () => { userId?: string; sessionRevision: number },
  archived: (id: string) => void,
  network = {
    archivePost: (id: string, version: number) => api.archivePost(id, version),
    myPosts: (signal?: AbortSignal) => api.myPosts(signal),
  },
) {
  const busy = ref(false),
    error = ref(''),
    success = ref('')
  const pending = shallowRef<ArchiveAttempt | null>(null)
  let epoch = 0,
    disposed = false,
    read = new AbortController()
  function capture() {
    const scope = { ...context() }
    if (disposed || busy.value || !scope.userId) return null
    const revision = ++epoch
    busy.value = true
    error.value = success.value = ''
    return {
      scope,
      current: () =>
        !disposed &&
        revision === epoch &&
        context().userId === scope.userId &&
        context().sessionRevision === scope.sessionRevision,
    }
  }
  function valid(post: CreatorPost, ownerId: string) {
    return (
      post.ownerId === ownerId &&
      typeof post.id === 'string' &&
      /^[1-9][0-9]{0,18}$/.test(post.id) &&
      BigInt(post.id) <= 9223372036854775807n &&
      Number.isSafeInteger(post.version) &&
      post.version! >= 0 &&
      ['DRAFT', 'PUBLISHED'].includes(post.status || '')
    )
  }
  async function archive(post: CreatorPost) {
    if (busy.value || pending.value || !context().userId || !valid(post, context().userId!)) return
    const lease = capture()
    if (!lease) return
    const attempt = { id: post.id, ownerId: post.ownerId, version: post.version! }
    try {
      await network.archivePost(attempt.id, attempt.version)
      if (!lease.current()) return
      archived(attempt.id)
      success.value = '归档已确认，内容不再公开展示。'
    } catch (cause) {
      if (!lease.current()) return
      const rejected =
        cause instanceof ApiRequestError &&
        ((cause.status === 400 && ['BAD_REQUEST', 'VALIDATION_ERROR'].includes(cause.code || '')) ||
          (cause.status === 403 && cause.code === 'FORBIDDEN') ||
          (cause.status === 404 && cause.code === 'NOT_FOUND') ||
          (cause.status === 409 && cause.code === 'CONFLICT'))
      if (!rejected) pending.value = attempt
      error.value = rejected ? '归档被拒绝，请刷新内容核对权限和版本。' : '归档结果未确认，请先查询，不要重复归档。'
    } finally {
      if (lease.current()) busy.value = false
    }
  }
  async function recheck() {
    if (!pending.value || busy.value) return
    const attempt = pending.value,
      lease = capture()
    if (!lease) return
    read.abort()
    read = new AbortController()
    try {
      const rows = await network.myPosts(read.signal)
      if (!lease.current()) return
      if (!Array.isArray(rows)) throw new Error('Invalid content list')
      const matches = rows.filter((row) => row.id === attempt.id)
      if (matches.length > 1 || (matches[0] && !valid(matches[0], attempt.ownerId)))
        throw new Error('Invalid owner/version')
      if (!matches.length) {
        archived(attempt.id)
        pending.value = null
        success.value = '当前本人列表已不含该内容；本次只查询，没有重复归档。'
      } else if (matches[0]!.version! > attempt.version) {
        pending.value = null
        error.value = '内容已有新版本，旧版本归档不能再成功；请刷新后核对最新内容。'
      } else error.value = '内容仍存在，原归档结果仍未确认；稍后再次查询，不会重发请求。'
    } catch {
      if (lease.current()) error.value = '归档核实未完成，保留原目标；不会自动重发归档。'
    } finally {
      if (lease.current()) busy.value = false
    }
  }
  const stop = watch(
    () => [context().userId, context().sessionRevision],
    () => {
      epoch++
      read.abort()
      busy.value = false
      pending.value = null
      error.value = success.value = ''
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    epoch++
    read.abort()
    stop()
    pending.value = null
  })
  return { busy, error, success, pending, archive, recheck }
}
