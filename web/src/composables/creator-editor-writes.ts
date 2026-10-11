import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, ApiRequestError, type CreatorPost, type CreatorProfile } from '../api'

/** 编辑器上下文不包含令牌，身份/同账号登录/同弹窗换内容均独立隔离。 */
export interface CreatorEditorContext {
  /** 当前可信账号，匿名时缺省。 */ userId?: string
  /** 网站登录轮次。 */ sessionRevision: number
  /** 当前文章或主页表单类型。 */ kind: string | null
  /** 每次打开/切换表单改变，即使弹窗类型不变。 */ formRevision: number
}
/** 已提交但结果未确认的本机恢复信息，不能作为后台提交收据。 */
interface EditorRecovery {
  /** 原编辑器类型。 */ kind: 'post' | 'creator'
  /** 有确认ID时按ID查询，否则仅按原请求slug查找。 */ id?: string
  /** 原请求地址标识，不使用随后编辑的新slug。 */ slug?: string
}
/** 保存输入仅包含明确表单字段，不在请求中透传所有者或领域状态。 */
type PostInput = Pick<CreatorPost, 'id' | 'slug' | 'title' | 'excerpt' | 'body' | 'coverUrl' | 'coverAssetId'> & {
  /** 本人已确认的当前版本。 */ version: number
}
/** 原有真实接口可替换网络用于状态测试，不替代服务端鉴权或事务。 */
type EditorNetwork = Pick<
  typeof api,
  | 'createPost'
  | 'updatePost'
  | 'publishPost'
  | 'myPosts'
  | 'saveCreatorProfile'
  | 'publishCreatorProfile'
  | 'myCreatorProfile'
>

/** 草稿保存与发布是两次HTTP操作；逐步应用已确认事实，未知结果只读核实，不自动重发写入。 */
export function useCreatorEditorWrites(
  context: () => CreatorEditorContext,
  callbacks: {
    /** 应用本人已确认的完整内容投影。 */ postConfirmed(value: CreatorPost): void
    /** 应用本人已确认的主页投影。 */ profileConfirmed(value: CreatorProfile): void
    /** 仅本流程实际忙态变化通知应用壳，不让旧finally解除新操作。 */ busyChanged(value: boolean): void
  },
  network: EditorNetwork = api,
) {
  /** 单流程保存/发布/只读核实在途。 */ const busy = ref(false)
  /** 只展示已确认步骤。 */ const success = ref('')
  /** 独立于素材上传/工作台读取的写入错误。 */ const error = ref('')
  /** 在内存保留原查询目标；不持久化私有正文或会话。 */ const recovery = shallowRef<EditorRecovery | null>(null)
  /** 读取可以取消；取消不表示后台写入回滚。 */ let read = new AbortController()
  let epoch = 0,
    disposed = false
  const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '编辑器请求失败')
  function setBusy(value: boolean) {
    if (busy.value === value) return
    busy.value = value
    callbacks.busyChanged(value)
  }
  function capture(kind: 'post' | 'creator', checking = false) {
    const snapshot = { ...context() }
    if (disposed || busy.value || !snapshot.userId || snapshot.kind !== kind || (!checking && recovery.value))
      return null
    const revision = ++epoch
    const current = () => {
      const now = context()
      return (
        !disposed &&
        revision === epoch &&
        snapshot.userId === now.userId &&
        snapshot.sessionRevision === now.sessionRevision &&
        snapshot.kind === now.kind &&
        snapshot.formRevision === now.formRevision
      )
    }
    setBusy(true)
    error.value = ''
    success.value = ''
    return { snapshot, current }
  }
  const versionValid = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
  function checkPost(value: CreatorPost, userId: string, id?: string) {
    if (
      !value ||
      value.ownerId !== userId ||
      typeof value.id !== 'string' ||
      !/^[1-9][0-9]{0,18}$/.test(value.id) ||
      BigInt(value.id) > 9223372036854775807n ||
      (id && value.id !== id) ||
      !versionValid(value.version) ||
      typeof value.body !== 'string' ||
      !['DRAFT', 'PUBLISHED'].includes(value.status ?? '')
    )
      throw new Error('内容响应的归属、完整正文或版本未确认。')
    return value
  }
  function checkProfile(value: CreatorProfile, userId: string) {
    if (
      !value ||
      value.userId !== userId ||
      !versionValid(value.version) ||
      !['DRAFT', 'ACTIVE', 'SUSPENDED'].includes(value.status)
    )
      throw new Error('主页响应的归属、状态或版本未确认。')
    return value
  }
  async function savePost(input: PostInput, publish: boolean) {
    const lease = capture('post')
    if (!lease) return false
    const payload = {
      slug: input.slug,
      title: input.title,
      excerpt: input.excerpt,
      body: input.body ?? '',
      coverUrl: input.coverUrl ?? '',
      coverAssetId: input.coverAssetId,
    }
    const id = input.id,
      version = input.version
    let confirmed: CreatorPost | null = null
    try {
      let saved = id ? await network.updatePost(id, { ...payload, version }) : await network.createPost(payload)
      if (!lease.current()) return false
      saved = checkPost(saved, lease.snapshot.userId!, id || undefined)
      if (saved.slug !== payload.slug || saved.version! < version) throw new Error('保存响应的地址或版本不匹配。')
      confirmed = saved
      callbacks.postConfirmed(saved)
      success.value = '内容保存已确认。'
      if (publish && saved.status === 'DRAFT') {
        saved = await network.publishPost(saved.id, saved.version!)
        if (!lease.current()) return false
        saved = checkPost(saved, lease.snapshot.userId!, confirmed.id)
        if (saved.version! < confirmed.version!) throw new Error('发布响应的版本倒退。')
        if (saved.status !== 'PUBLISHED') throw new Error('发布状态未确认。')
        callbacks.postConfirmed(saved)
        success.value = '内容发布已确认。'
      }
      return true
    } catch (cause) {
      if (!lease.current()) return false
      if (!confirmed && cause instanceof ApiRequestError && [400, 422].includes(cause.status)) {
        error.value = `保存被拒绝，请检查表单。${failure(cause)}`
      } else {
        recovery.value = { kind: 'post', id: confirmed?.id || id || undefined, slug: payload.slug }
        error.value = `${confirmed ? '内容保存已确认，但发布或本机更新结果未确认' : '保存结果未确认，服务端可能已执行'}；请先重新读取。${failure(cause)}`
      }
      return false
    } finally {
      if (lease.current()) setBusy(false)
    }
  }
  async function saveProfile(input: CreatorProfile, publish: boolean) {
    const lease = capture('creator')
    if (!lease) return false
    const payload = {
      slug: input.slug,
      displayName: input.displayName,
      headline: input.headline,
      bio: input.bio,
      avatarUrl: input.avatarUrl,
      bannerUrl: input.bannerUrl,
      avatarAssetId: input.avatarAssetId,
      bannerAssetId: input.bannerAssetId,
      version: input.version,
    }
    let confirmed = false
    try {
      let saved = await network.saveCreatorProfile(payload)
      if (!lease.current()) return false
      saved = checkProfile(saved, lease.snapshot.userId!)
      if (saved.slug !== payload.slug || saved.version < payload.version)
        throw new Error('主页保存响应的地址或版本不匹配。')
      confirmed = true
      callbacks.profileConfirmed(saved)
      success.value = '主页保存已确认。'
      if (publish && saved.status === 'DRAFT') {
        const version = saved.version
        saved = await network.publishCreatorProfile(saved.version)
        if (!lease.current()) return false
        saved = checkProfile(saved, lease.snapshot.userId!)
        if (saved.version < version) throw new Error('主页发布响应的版本倒退。')
        if (saved.status !== 'ACTIVE') throw new Error('主页发布状态未确认。')
        callbacks.profileConfirmed(saved)
        success.value = '主页发布已确认。'
      }
      return true
    } catch (cause) {
      if (!lease.current()) return false
      if (!confirmed && cause instanceof ApiRequestError && [400, 422].includes(cause.status))
        error.value = `保存被拒绝，请检查表单。${failure(cause)}`
      else {
        recovery.value = { kind: 'creator' }
        error.value = `${confirmed ? '主页保存已确认，但发布或本机更新结果未确认' : '保存结果未确认，服务端可能已执行'}；请先重新读取。${failure(cause)}`
      }
      return false
    } finally {
      if (lease.current()) setBusy(false)
    }
  }
  /** 明确点击只读核实；没有记录不证明旧写入回滚，不能解锁未知创建。 */
  async function recheck() {
    const target = recovery.value
    if (!target) return
    const lease = capture(target.kind, true)
    if (!lease) return
    read.abort()
    read = new AbortController()
    try {
      if (target.kind === 'post') {
        const rows = await network.myPosts(read.signal)
        if (!lease.current()) return
        const matches = rows.filter((row) => (target.id ? row.id === target.id : row.slug === target.slug))
        if (matches.length !== 1) throw new Error('暂未找到唯一的已确认内容；这不证明原写入已回滚，请稍后重新读取。')
        callbacks.postConfirmed(checkPost(matches[0]!, lease.snapshot.userId!, target.id))
      } else {
        const value = await network.myCreatorProfile(read.signal)
        if (!lease.current()) return
        callbacks.profileConfirmed(checkProfile(value, lease.snapshot.userId!))
      }
      recovery.value = null
      success.value = '已读取服务端最新状态，未重发保存或发布。'
    } catch (cause) {
      if (lease.current()) error.value = `核实未完成，仍不重发写入。${failure(cause)}`
    } finally {
      if (lease.current()) setBusy(false)
    }
  }
  const stop = watch(
    () => {
      const value = context()
      return [value.userId, value.sessionRevision, value.kind, value.formRevision]
    },
    () => {
      epoch++
      read.abort()
      setBusy(false)
      recovery.value = null
      error.value = ''
      success.value = ''
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    epoch++
    read.abort()
    stop()
    setBusy(false)
  })
  return { busy, error, success, recovery, savePost, saveProfile, recheck }
}
