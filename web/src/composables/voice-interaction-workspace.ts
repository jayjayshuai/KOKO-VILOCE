import { onBeforeUnmount, ref, watch } from 'vue'
import type { VoiceActionPage } from '../services/voice-interaction'
import {
  interactionUuid,
  voiceInteractionApi,
  type InteractionCapabilities,
  type InteractionSnapshot,
  type VoiceCommand,
  type VoiceCommandType,
} from '../services/voice-interaction'

/** 会话/房间轮次，不保存JWT。 */
interface InteractionContext {
  /** 房间ID字符串。 */ roomId: string
  /** 当前账号ID。 */ userId: string
  /** 同账号新登录也改变轮次。 */ sessionRevision: number
}
/** 一次未知写入的不可变重试事实，不重用新版本/新会话。 */
type Attempt =
  { kind: 'JOIN'; input: { requestId: string; expectedVersion: string } } | { kind: 'COMMAND'; input: VoiceCommand }

export function useVoiceInteractionWorkspace(context: InteractionContext, network = voiceInteractionApi) {
  /** 真实能力，不以失败伪装关闭开关。 */ const capabilities = ref<InteractionCapabilities | null>(null)
  /** 本人授权快照，读取失败保留旧快照并标记故障。 */ const snapshot = ref<InteractionSnapshot | null>(null)
  /** 读取进行中。 */ const loading = ref(false)
  /** 单写/心跳互斥，防乱序刷新。 */ const busy = ref(false)
  /** 能力或快照错误。 */ const readError = ref('')
  /** 写入/心跳未确认，不自动重试管理命令。 */ const writeError = ref('')
  /** 原写入待重试，必须维持UUID和参数。 */ const pending = ref<Attempt | null>(null)
  /** SQL提交反馈，不当实际发声状态。 */ const success = ref('')
  /** 当前授权核验成功；读取失败、离线或隐藏后不能发起新命令。 */ const fresh = ref(false)
  /** 本机确认时刻仅供显示，不作为数据库租约时钟。 */ const lastVerifiedAt = ref<number | null>(null)
  /** 浏览器离线不发请求，恢复后先重取事实。 */ const online = ref(
    typeof navigator === 'undefined' || navigator.onLine !== false,
  )
  /** 审计独立读取，失败不假装无记录。 */ const actions = ref<VoiceActionPage | null>(null),
    actionLoading = ref(false),
    actionError = ref('')
  let actionRead = new AbortController(),
    actionRevision = 0
  function clearActions() {
    actionRevision++
    actionRead.abort()
    actions.value = null
    actionLoading.value = false
    actionError.value = ''
  }
  const canAudit = () =>
    fresh.value && !!snapshot.value?.mySessionId && ['OWNER', 'ADMIN'].includes(snapshot.value.myRole || '')
  const stopAudit = watch(
    () => [fresh.value, snapshot.value?.mySessionId, snapshot.value?.myRole],
    () => {
      if (!canAudit()) clearActions()
    },
    { flush: 'sync' },
  )
  async function loadActions(more = false) {
    if (
      disposed ||
      busy.value ||
      loading.value ||
      actionLoading.value ||
      !canAudit() ||
      (more && !actions.value?.nextBefore)
    )
      return
    actionRead.abort()
    actionRead = new AbortController()
    const saved = capture(),
      revision = ++actionRevision,
      member = snapshot.value?.mySessionId
    actionLoading.value = true
    actionError.value = ''
    try {
      const page = await network.actions(saved.roomId, more ? actions.value!.nextBefore : null, actionRead.signal)
      if (current(saved) && revision === actionRevision && canAudit() && member === snapshot.value?.mySessionId)
        actions.value = {
          items: more ? [...(actions.value?.items || []), ...page.items] : page.items,
          nextBefore: page.nextBefore,
        }
    } catch (cause) {
      if (current(saved) && revision === actionRevision) {
        const status = (cause as { status?: number })?.status
        if (status === 401 || status === 403 || status === 404) {
          invalidate()
          snapshot.value = null
          capabilities.value = null
        }
        actionError.value = failure(cause)
      }
    } finally {
      if (current(saved) && revision === actionRevision) actionLoading.value = false
    }
  }
  /** 页面取消不撤销后台已提交事实。 */ let read = new AbortController(),
    write = new AbortController()
  /** 生命周期与读取分离轮次。 */ let epoch = 0,
    readRevision = 0,
    disposed = false
  /** 定时器均在卸载或换身份时停止。 */ let poll: ReturnType<typeof setInterval> | undefined,
    heartbeat: ReturnType<typeof setInterval> | undefined
  /** 失败读取5/10/20/40/60秒退避，手工刷新和恢复不受此限制。 */ let readFailures = 0,
    nextReadAt = 0
  const visible = () => typeof document === 'undefined' || document.visibilityState !== 'hidden'
  /** 明确新操作的前置读取独占窗口，续约/轮询不能取消它；不代表请求已发送。 */
  const actionPreparing = ref(false)
  const capture = () => ({
    epoch,
    roomId: context.roomId,
    userId: context.userId,
    sessionRevision: context.sessionRevision,
  })
  const current = (saved: ReturnType<typeof capture>) =>
    !disposed &&
    saved.epoch === epoch &&
    saved.roomId === context.roomId &&
    saved.userId === context.userId &&
    saved.sessionRevision === context.sessionRevision
  const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '房间互动暂不可用')

  function invalidate() {
    fresh.value = false
    clearActions()
  }
  async function load(force = true, preparingRead = false) {
    if (
      disposed ||
      busy.value ||
      (actionPreparing.value && !preparingRead) ||
      !context.userId ||
      !online.value ||
      !visible() ||
      (!force && Date.now() < nextReadAt)
    )
      return
    read.abort()
    read = new AbortController()
    const saved = capture(),
      revision = ++readRevision
    loading.value = true
    readError.value = ''
    try {
      if (!force && capabilities.value?.enabled && snapshot.value) {
        const known = snapshot.value.version
        const update = await network.sync(saved.roomId, known, read.signal)
        if (!current(saved) || revision !== readRevision) return
        if (update.snapshot) {
          if (update.snapshot.roomId !== saved.roomId || update.snapshot.version !== update.version)
            throw new Error('房间同步响应不一致，请重新读取')
          snapshot.value = update.snapshot
        } else if (update.version !== known) throw new Error('房间版本变化但缺少快照，请重新读取')
        capabilities.value = { ...capabilities.value, version: update.version, canInspect: true }
      } else {
        const cap = await network.capabilities(saved.roomId, read.signal)
        if (!current(saved) || revision !== readRevision) return
        capabilities.value = cap
        if (!cap.enabled || !cap.canInspect) {
          snapshot.value = null
        } else {
          const data = await network.snapshot(saved.roomId, read.signal)
          if (!current(saved) || revision !== readRevision) return
          if (data.roomId !== saved.roomId) throw new Error('房间快照标识不一致')
          snapshot.value = data
        }
      }
      fresh.value = true
      lastVerifiedAt.value = Date.now()
      readFailures = 0
      nextReadAt = 0
    } catch (cause) {
      if (current(saved) && revision === readRevision) {
        invalidate()
        readError.value = failure(cause)
        readFailures++
        nextReadAt = Date.now() + Math.min(60000, 5000 * 2 ** Math.min(readFailures - 1, 4))
        const status = (cause as { status?: number })?.status
        if (status === 401 || status === 403 || status === 404) {
          snapshot.value = null
          capabilities.value = null
        }
      }
    } finally {
      if (current(saved) && revision === readRevision) loading.value = false
    }
  }
  /** 仅准备一次用户新操作；核验失败、取消、换身份后false，不写入也不重试旧请求。 */
  async function prepareAction() {
    if (
      disposed ||
      actionPreparing.value ||
      busy.value ||
      loading.value ||
      pending.value ||
      !fresh.value ||
      !online.value ||
      !visible()
    )
      return false
    const saved = capture()
    actionPreparing.value = true
    try {
      await load(true, true)
      return (
        current(saved) && fresh.value && !loading.value && !busy.value && !pending.value && online.value && visible()
      )
    } finally {
      if (current(saved)) actionPreparing.value = false
    }
  }
  async function transmit() {
    if (disposed || busy.value || !pending.value || !online.value || !visible()) return
    const saved = capture(),
      attempt = pending.value
    busy.value = true
    success.value = ''
    writeError.value = ''
    read.abort()
    ++readRevision
    loading.value = false
    try {
      const ack =
        attempt.kind === 'JOIN'
          ? await network.join(saved.roomId, attempt.input, write.signal)
          : await network.command(saved.roomId, attempt.input, write.signal)
      if (!current(saved)) return
      pending.value = null
      success.value = `已确认提交 ${ack.type}；席位不是媒体发声权限。`
    } catch (cause) {
      if (current(saved)) writeError.value = `${failure(cause)}；结果未确认，可用原请求重试，或刷新后明确放弃旧请求。`
    } finally {
      if (current(saved)) {
        busy.value = false
        await load()
      }
    }
  }
  function join() {
    if (
      disposed ||
      actionPreparing.value ||
      busy.value ||
      loading.value ||
      pending.value ||
      !fresh.value ||
      !online.value ||
      !visible() ||
      !capabilities.value?.enabled ||
      !capabilities.value.version
    )
      return
    pending.value = {
      kind: 'JOIN',
      input: { requestId: interactionUuid(), expectedVersion: capabilities.value.version },
    }
    return transmit()
  }
  function command(
    type: VoiceCommandType,
    args: Partial<Pick<VoiceCommand, 'seatNo' | 'targetUserId' | 'seatRequestId' | 'value'>> = {},
  ) {
    const data = snapshot.value
    if (
      disposed ||
      actionPreparing.value ||
      busy.value ||
      loading.value ||
      pending.value ||
      !fresh.value ||
      !online.value ||
      !visible() ||
      !data?.mySessionId ||
      !capabilities.value?.enabled
    )
      return
    pending.value = {
      kind: 'COMMAND',
      input: {
        ...args,
        type,
        requestId: interactionUuid(),
        sessionId: data.mySessionId,
        expectedVersion: data.version,
      },
    }
    return transmit()
  }
  /** 仅核对原UUID；未找到仍保留未知请求，不自动换参数重试。 */
  async function checkReceipt() {
    if (disposed || busy.value || !pending.value || !online.value || !visible()) return
    const saved = capture(),
      attempt = pending.value
    busy.value = true
    read.abort()
    ++readRevision
    loading.value = false
    try {
      const result = await network.receipt(saved.roomId, attempt.input.requestId, write.signal)
      if (!current(saved)) return
      if (!result.committed) {
        writeError.value = '尚未查到原请求提交收据；操作可能仍在途中，不能据此认定失败或自动创建新请求。'
        return
      }
      const expectedType = attempt.kind === 'JOIN' ? 'JOIN' : attempt.input.type
      if (
        !result.ack ||
        result.ack.type !== expectedType ||
        BigInt(result.ack.version) !== BigInt(attempt.input.expectedVersion) + 1n
      )
        throw new Error('原请求收据与待核对操作不一致，请保留请求并人工核对')
      pending.value = null
      writeError.value = ''
      success.value = `原 UUID 已有 ${result.ack.type} 提交收据；请核对当前状态，原会话不保证仍有效。`
    } catch (cause) {
      if (current(saved)) writeError.value = `收据核对未确认：${failure(cause)}`
    } finally {
      if (current(saved)) {
        busy.value = false
        await load()
      }
    }
  }
  /** 明确放弃只是停止本地重试，不表示取消SQL；重新读取已提交事实。 */
  async function discard() {
    if (!busy.value) {
      pending.value = null
      await load()
    }
  }
  async function pulse() {
    const sessionId = snapshot.value?.mySessionId
    if (
      disposed ||
      actionPreparing.value ||
      busy.value ||
      pending.value ||
      !fresh.value ||
      !online.value ||
      !visible() ||
      !sessionId ||
      !capabilities.value?.enabled
    )
      return
    const saved = capture()
    busy.value = true
    read.abort()
    ++readRevision
    loading.value = false
    try {
      await network.heartbeat(saved.roomId, sessionId, write.signal)
    } catch (cause) {
      if (current(saved)) writeError.value = `成员续约未确认：${failure(cause)}；刷新状态后可重新加入。`
    } finally {
      if (current(saved)) {
        busy.value = false
        await load()
      }
    }
  }
  function reset() {
    clearActions()
    epoch++
    readRevision++
    read.abort()
    write.abort()
    read = new AbortController()
    write = new AbortController()
    if (poll) clearInterval(poll)
    if (heartbeat) clearInterval(heartbeat)
    capabilities.value = null
    snapshot.value = null
    pending.value = null
    loading.value = false
    busy.value = false
    actionPreparing.value = false
    readError.value = ''
    writeError.value = ''
    success.value = ''
    fresh.value = false
    lastVerifiedAt.value = null
    readFailures = 0
    nextReadAt = 0
  }
  /** 恢复不自动加入或重试写入；取消旧读取，重新核验当前权限。 */
  function suspend() {
    invalidate()
    read.abort()
    ++readRevision
    loading.value = false
  }
  function onOffline() {
    online.value = false
    suspend()
  }
  function onOnline() {
    online.value = true
    suspend()
    void load()
  }
  function onFocus() {
    // 普通焦点切换不等于失权；后台/离线已由对应事件失效。刷新失败或超过15秒仍按原策略断开。
    if (fresh.value && lastVerifiedAt.value !== null && Date.now() - lastVerifiedAt.value < 15000) return
    if (fresh.value) invalidate()
    void load()
  }
  function onVisibility() {
    if (visible()) onFocus()
    else suspend()
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
    window.addEventListener('focus', onFocus)
  }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility)
  const stop = watch(
    () => [context.roomId, context.userId, context.sessionRevision],
    () => {
      reset()
      if (!context.userId) return
      void load()
      poll = setInterval(() => {
        if (lastVerifiedAt.value !== null && Date.now() - lastVerifiedAt.value >= 15000) invalidate()
        if (!loading.value && !busy.value) void load(false)
      }, 5000)
      heartbeat = setInterval(() => {
        void pulse()
      }, 25000)
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    stop()
    stopAudit()
    if (typeof window !== 'undefined') {
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
      window.removeEventListener('focus', onFocus)
    }
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility)
    reset()
  })
  return {
    actions,
    actionLoading,
    actionError,
    loadActions,
    capabilities,
    snapshot,
    loading,
    busy,
    readError,
    writeError,
    pending,
    success,
    load,
    prepareAction,
    actionPreparing,
    join,
    command,
    transmit,
    discard,
    pulse,
    fresh,
    lastVerifiedAt,
    online,
    checkReceipt,
  }
}
