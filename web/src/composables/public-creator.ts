import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, type CreatorProfile, type CreatorFollowState } from '../api'

/** 身份轮次来自会话仓库，slug来自公开路由；不包含凭据。 */
export interface PublicCreatorContext {
  /** 原始路由标识；格式非法时不发送网络请求。 */ slug: string
  /** 当前账号，匿名缺省。 */ userId?: string
  /** 同账号重新登录也需隔离。 */ sessionRevision: number
}
type PublicCreatorNetwork = Pick<typeof api, 'publicCreator' | 'followState' | 'followCreator' | 'unfollowCreator'>
const validId = (id: unknown) =>
  typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
const validCount = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0

/** 公开事实与本人关注分别读取；回应未知仅核实，不以默认false反向提交。 */
export function usePublicCreator(context: () => PublicCreatorContext, network: PublicCreatorNetwork = api) {
  /** 仅保存当前路由有效ACTIVE投影。 */ const profile = shallowRef<CreatorProfile | null>(null)
  /** 当前本人已确认的关注状态；匿名或尚未核验为null。 */ const relation = shallowRef<CreatorFollowState | null>(null)
  const loading = ref(false),
    error = ref(''),
    relationLoading = ref(false),
    relationError = ref('')
  const writing = ref(false),
    uncertain = ref(false),
    success = ref('')
  let epoch = 0,
    disposed = false,
    profileRead = new AbortController(),
    relationRead = new AbortController()
  const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '请求失败，请重试。')
  const current = (revision: number, snapshot: PublicCreatorContext) => {
    const now = context()
    return (
      !disposed &&
      revision === epoch &&
      now.slug === snapshot.slug &&
      now.userId === snapshot.userId &&
      now.sessionRevision === snapshot.sessionRevision
    )
  }
  function reset() {
    epoch++
    profileRead.abort()
    relationRead.abort()
    profileRead = new AbortController()
    relationRead = new AbortController()
    profile.value = null
    relation.value = null
    loading.value = false
    relationLoading.value = false
    writing.value = false
    uncertain.value = false
    error.value = ''
    relationError.value = ''
    success.value = ''
  }
  function checkRelation(value: CreatorFollowState, id: string) {
    if (!value || value.creatorId !== id || typeof value.followedByMe !== 'boolean' || !validCount(value.followerCount))
      throw new Error('关注状态响应未核实，不能切换。')
    return value
  }
  /** 独立GET可恢复未知写入；未验证/错目标/失败不能解除门禁。 */
  async function readRelation() {
    const snapshot = { ...context() },
      revision = epoch,
      target = profile.value
    if (
      disposed ||
      !target ||
      !snapshot.userId ||
      snapshot.userId === target.userId ||
      relationLoading.value ||
      writing.value
    )
      return
    relationLoading.value = true
    relationError.value = ''
    try {
      const result = await network.followState(target.userId, relationRead.signal)
      if (!current(revision, snapshot)) return
      relation.value = checkRelation(result, target.userId)
      uncertain.value = false
    } catch (cause) {
      if (current(revision, snapshot)) relationError.value = failure(cause)
    } finally {
      if (current(revision, snapshot)) relationLoading.value = false
    }
  }
  /** 刷新公开主页不释放未确认关注写入，也不丢弃已核验关系。 */
  async function load() {
    if (disposed || loading.value || writing.value || relationLoading.value) return
    const snapshot = { ...context() },
      revision = epoch
    if (!/^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/.test(snapshot.slug)) {
      error.value = '主页地址无效，请从创作者列表重新打开。'
      return
    }
    loading.value = true
    error.value = ''
    try {
      const value = await network.publicCreator(snapshot.slug, profileRead.signal)
      if (!current(revision, snapshot)) return
      if (
        !value ||
        value.slug !== snapshot.slug ||
        value.status !== 'ACTIVE' ||
        !validId(value.userId) ||
        typeof value.displayName !== 'string' ||
        !value.displayName.trim() ||
        value.displayName.length > 80 ||
        typeof value.headline !== 'string' ||
        value.headline.length > 120 ||
        typeof value.bio !== 'string' ||
        value.bio.length > 1000 ||
        !validCount(value.followerCount) ||
        !validCount(value.version) ||
        (profile.value && profile.value.userId !== value.userId)
      )
        throw new Error('公开主页响应未核实，请重新读取。')
      profile.value = value
    } catch (cause) {
      if (current(revision, snapshot)) error.value = failure(cause)
    } finally {
      if (current(revision, snapshot)) loading.value = false
    }
    if (current(revision, snapshot) && !error.value) await readRelation()
  }
  async function toggleFollow() {
    const snapshot = { ...context() },
      revision = epoch,
      target = profile.value,
      known = relation.value
    if (
      disposed ||
      !target ||
      !snapshot.userId ||
      snapshot.userId === target.userId ||
      !known ||
      known.creatorId !== target.userId ||
      writing.value ||
      loading.value ||
      relationLoading.value ||
      uncertain.value
    )
      return
    writing.value = true
    relationError.value = ''
    success.value = ''
    try {
      const value = known.followedByMe
        ? await network.unfollowCreator(target.userId)
        : await network.followCreator(target.userId)
      if (!current(revision, snapshot)) return
      const checked = checkRelation(value, target.userId)
      if (checked.followedByMe === known.followedByMe) throw new Error('关注回应没有确认请求状态。')
      relation.value = checked
      success.value = checked.followedByMe ? '关注已确认。' : '取消关注已确认。'
    } catch (cause) {
      if (current(revision, snapshot)) {
        uncertain.value = true
        relationError.value = `操作结果未确认，服务端可能已执行；请先查询关注状态。${failure(cause)}`
      }
    } finally {
      if (current(revision, snapshot)) writing.value = false
    }
  }
  const stop = watch(
    () => {
      const value = context()
      return [value.slug, value.userId, value.sessionRevision]
    },
    () => {
      reset()
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    epoch++
    profileRead.abort()
    relationRead.abort()
    stop()
  })
  return {
    profile,
    relation,
    loading,
    error,
    relationLoading,
    relationError,
    writing,
    uncertain,
    success,
    load,
    readRelation,
    toggleFollow,
  }
}
