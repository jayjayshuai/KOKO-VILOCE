import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError } from '../services/http'
import type { OperationsAccess } from '../services/operations'
import type {
  BindingReleaseCursor,
  BindingReleaseDomain,
  BindingReleaseSnapshot,
  BindingReleaseTask,
  bindingReleaseApi,
} from '../services/binding-releases'

/** 身份变化同步撤销旧查询；运行时只调用真实接口，测试可注入网络边界。 */
export function useBindingReleaseWorkspace(
  api: typeof bindingReleaseApi,
  accessApi: (signal?: AbortSignal) => Promise<OperationsAccess>,
  session: { user: { id: string } | null; sessionRevision: number },
) {
  /** 专用读权限，不能借通知运营角色提权。 */
  const access = ref<OperationsAccess | null>(null)
  /** 当前固定业务域。 */
  const domain = ref<BindingReleaseDomain>('identity')
  /** 最多缓存两百条真实 DEAD 任务，刷新重新从头读。 */
  const rows = ref<BindingReleaseTask[]>([])
  /** 原库微秒时间游标，不转 Date。 */
  const cursor = ref<BindingReleaseCursor | null>(null)
  /** 当前原请求释放事实，可已离开 DEAD。 */
  const selected = ref<BindingReleaseTask | null>(null)
  /** 本次有界 SQL 时点，不保存旧故障前的采样作为当前值。 */
  const snapshot = ref<BindingReleaseSnapshot | null>(null)
  /** 三条独立网络路径的加载状态。 */
  const accessLoading = ref(false),
    loading = ref(false),
    detailLoading = ref(false),
    snapshotLoading = ref(false)
  /** 各路径错误，失败不能冒充成功空态。 */
  const accessError = ref(''),
    error = ref(''),
    detailError = ref(''),
    snapshotError = ref('')
  /** 首次成功读取页之前不显示成功空队列。 */
  const loaded = ref(false)
  /** 本页能力只控制展示，所有请求仍由服务器再次校验。 */
  const canRead = computed(
    () => !!session.user && access.value?.enabled === true && access.value.permissions.includes('asset:binding:read'),
  )
  /** 缓存达到两百条后要求刷新，避免前端无界累积。 */
  const capped = computed(() => rows.value.length >= 200 && cursor.value !== null)
  let disposed = false,
    epoch = 0,
    detailEpoch = 0
  let accessRequest = new AbortController(),
    listRequest = new AbortController(),
    detailRequest = new AbortController(),
    snapshotRequest = new AbortController()
  const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '绑定释放读取失败')

  function invalidate() {
    epoch++
    detailEpoch++
    for (const request of [accessRequest, listRequest, detailRequest, snapshotRequest]) request.abort()
    access.value = null
    rows.value = []
    cursor.value = null
    selected.value = null
    snapshot.value = null
    accessLoading.value = loading.value = detailLoading.value = snapshotLoading.value = loaded.value = false
    error.value = detailError.value = snapshotError.value = ''
  }
  function denied(cause: unknown) {
    if (cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403)) {
      invalidate()
      accessError.value = '当前会话或运营权限已改变，请重新读取本人权限。'
      return true
    }
    return false
  }
  const current = (revision: number, target: BindingReleaseDomain) =>
    !disposed && revision === epoch && target === domain.value && canRead.value

  async function loadAccess() {
    if (disposed || !session.user || accessLoading.value) return
    invalidate()
    const revision = epoch
    accessRequest = new AbortController()
    accessLoading.value = true
    accessError.value = ''
    try {
      const value = await accessApi(accessRequest.signal)
      if (disposed || revision !== epoch) return
      if (
        !value ||
        typeof value.enabled !== 'boolean' ||
        !Array.isArray(value.permissions) ||
        !Array.isArray(value.roles) ||
        value.permissions.some((item) => typeof item !== 'string') ||
        value.roles.some((item) => typeof item !== 'string')
      ) {
        throw new Error('运营权限响应不完整，未加载绑定释放任务')
      }
      access.value = value
      if (canRead.value) await Promise.all([load(true), sample()])
    } catch (cause) {
      if (!disposed && revision === epoch) {
        if (!denied(cause)) accessError.value = failure(cause)
      }
    } finally {
      if (!disposed && revision === epoch) accessLoading.value = false
    }
  }
  async function load(reset = true) {
    if (disposed || !canRead.value || loading.value || (!reset && (!cursor.value || capped.value))) return
    const revision = epoch,
      target = domain.value,
      before = reset ? null : cursor.value
    listRequest.abort()
    listRequest = new AbortController()
    loading.value = true
    error.value = ''
    if (reset) {
      rows.value = []
      cursor.value = null
      loaded.value = false
    }
    try {
      const page = await api.dead(target, before, listRequest.signal)
      if (!current(revision, target)) return
      if (before && page.nextCursor?.requestId === before.requestId && page.nextCursor.createdAt === before.createdAt) {
        throw new Error('游标没有推进，请刷新队列')
      }
      const unique = new Map((reset ? [] : rows.value).map((row) => [row.requestId, row]))
      page.items.forEach((row) => unique.set(row.requestId, row))
      rows.value = [...unique.values()].slice(0, 200)
      cursor.value = page.nextCursor
      loaded.value = true
    } catch (cause) {
      if (current(revision, target) && !denied(cause)) error.value = failure(cause)
    } finally {
      if (!disposed && revision === epoch) loading.value = false
    }
  }
  async function sample() {
    if (disposed || !canRead.value || snapshotLoading.value) return
    const revision = epoch,
      target = domain.value
    snapshotRequest.abort()
    snapshotRequest = new AbortController()
    snapshotLoading.value = true
    snapshot.value = null
    snapshotError.value = ''
    try {
      const value = await api.snapshot(target, snapshotRequest.signal)
      if (current(revision, target)) snapshot.value = value
    } catch (cause) {
      if (current(revision, target) && !denied(cause)) snapshotError.value = failure(cause)
    } finally {
      if (!disposed && revision === epoch) snapshotLoading.value = false
    }
  }
  async function select(requestId: string) {
    if (disposed || !canRead.value) return
    const revision = epoch,
      target = domain.value,
      detailRevision = ++detailEpoch
    detailRequest.abort()
    detailRequest = new AbortController()
    selected.value = null
    detailLoading.value = true
    detailError.value = ''
    try {
      const value = await api.detail(target, requestId, detailRequest.signal)
      if (current(revision, target) && detailRevision === detailEpoch) selected.value = value
    } catch (cause) {
      if (current(revision, target) && detailRevision === detailEpoch && !denied(cause))
        detailError.value = failure(cause)
    } finally {
      if (!disposed && revision === epoch && detailRevision === detailEpoch) detailLoading.value = false
    }
  }
  async function chooseDomain(next: BindingReleaseDomain) {
    if (disposed || !canRead.value || !['identity', 'community'].includes(next) || next === domain.value) return
    const permission = access.value
    invalidate()
    access.value = permission
    domain.value = next
    await Promise.all([load(true), sample()])
  }
  const stopWatching = watch(
    [() => session.user?.id, () => session.sessionRevision],
    () => {
      invalidate()
      accessError.value = '身份已改变，请重新读取本人权限。'
    },
    { flush: 'sync' },
  )
  function dispose() {
    disposed = true
    stopWatching()
    invalidate()
  }
  onBeforeUnmount(dispose)
  return {
    access,
    domain,
    rows,
    cursor,
    selected,
    snapshot,
    accessLoading,
    loading,
    detailLoading,
    snapshotLoading,
    accessError,
    error,
    detailError,
    snapshotError,
    loaded,
    canRead,
    capped,
    loadAccess,
    load,
    sample,
    select,
    chooseDomain,
    dispose,
  }
}
