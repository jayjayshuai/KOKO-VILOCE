import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { ApiRequestError } from '../services/http'
import { assetReferencesApi, type AssetReferenceSnapshot } from '../services/asset-references'
import type { ManagedImage } from '../api'

/** 只需要身份与轮次，禁止会话令牌进入素材工作台状态。 */
type InspectionSession = {
  /** 当前身份；匿名时为空。 */
  user: { id: string } | null
  /** 登录、退出、恢复或过期的单调会话轮次。 */
  sessionRevision: number
  /** 仅当前身份/轮次的确定401可以清除会话。 */
  expireSession: (userId: string | undefined, revision: number) => void
}

/** 切图、换账号和卸载都取消旧查询；服务端仍独立校验所有权。 */
export function useAssetReferenceInspection(
  selected: Ref<ManagedImage | null>,
  session: InspectionSession,
  network = assetReferencesApi,
) {
  const snapshot = ref<AssetReferenceSnapshot | null>(null)
  const loading = ref(false)
  const error = ref('')
  const referenced = computed(() =>
    snapshot.value ? snapshot.value.profileReferenced || snapshot.value.postReferenced : null,
  )
  let revision = 0
  let active = true
  let controller: AbortController | null = null

  function invalidate() {
    revision++
    controller?.abort()
    controller = null
    snapshot.value = null
    error.value = ''
    loading.value = false
  }

  const stop = watch([() => selected.value?.id, () => session.user?.id, () => session.sessionRevision], invalidate, {
    flush: 'sync',
  })
  onBeforeUnmount(() => {
    active = false
    invalidate()
    stop()
  })

  async function inspect() {
    const id = selected.value?.id,
      userId = session.user?.id
    if (!active || loading.value || !id || !userId) return
    const current = ++revision,
      sessionRevision = session.sessionRevision
    controller = new AbortController()
    const signal = controller.signal
    snapshot.value = null
    error.value = ''
    loading.value = true
    const isCurrent = () =>
      active &&
      current === revision &&
      selected.value?.id === id &&
      session.user?.id === userId &&
      session.sessionRevision === sessionRevision
    try {
      const result = await network.inspect(id, signal)
      if (isCurrent()) snapshot.value = result
    } catch (cause) {
      if (!isCurrent()) return
      if (cause instanceof ApiRequestError && cause.status === 401) {
        session.expireSession(userId, sessionRevision)
        return
      }
      error.value = cause instanceof Error ? cause.message : '引用核验失败，请重试。'
    } finally {
      if (isCurrent()) {
        loading.value = false
        controller = null
      }
    }
  }

  return { snapshot, loading, error, referenced, inspect }
}
