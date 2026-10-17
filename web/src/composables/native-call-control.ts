import { reactive } from 'vue'
import { ApiRequestError } from '../services/http'
import {
  nativeCallApi,
  nativeCallRequestId,
  type NativeCallPort,
  type NativeCallView,
  type NativeCallInvite,
  type NativeCallCommand,
  type NativeCallCommandType,
} from '../services/native-calls'

/** kind固定原操作域；input保留原UUID/目标/版本；uncertain记录未知结果；stopVersion保留原本机停止围栏。 */
type Pending =
  | { kind: 'invite'; input: NativeCallInvite; uncertain: boolean; stopVersion: number }
  | { kind: 'command'; input: NativeCallCommand; uncertain: boolean; stopVersion: number }
interface Session {
  /** 当前认证用户，不读取或保存网站令牌。 */
  user: { id: string } | null
  /** 同账号重新登录也递增的本地认证轮次。 */
  sessionRevision: number
}
/** 常驻组件拥有的控制面；不存Cookie/媒体JWT，不根据轮询中的ACCEPTED自动获取设备。 */
export function createNativeCallControl(
  session: Session,
  disconnect: () => Promise<void>,
  occupied: () => boolean,
  api: NativeCallPort = nativeCallApi,
  uuid: () => string = nativeCallRequestId,
  now: () => number = Date.now,
) {
  const state = reactive({
    /** 能力未确认或关闭均不得发起调用。 */ enabled: false,
    /** 已核对当前登录轮次的能力。 */ checked: false,
    /** 临时故障的下一次核对时间；0表示无需后台重试（明确关闭或旧服务404）。 */ capabilityRetryAt: 0,
    /** 服务端当前事实，不等于SDK已连通。 */ view: null as NativeCallView | null,
    /** 当前写操作，和后台补拉分别互斥。 */ busy: false,
    /** 未确认原命令，重试不得改UUID、版本或目标。 */ pending: null as Pending | null,
    /** 明确操作的故障，不由轮询反复清空/重写。 */ error: '',
    /** 后台同步独立状态，不反复弹红色提示。 */ syncNote: '',
    /** 只有本网页明确发起/接听成功才为true，数据库成员身份不能自动赋值。 */ localConsent: false,
    /** 用户明确停止本机/挂断后，旧轮询不得自动连接。 */ stopped: false,
    /** 仅媒体连接请求轮次，不是权限版本。 */ mediaRevision: 0,
  })
  let generation = 0,
    mutation = 0,
    manualStop = 0,
    reading = false,
    heartbeating = false,
    failures = 0,
    lastVerified = 0,
    lastHeartbeat = 0
  let checkingCapability = false,
    capabilityFailures = 0
  let reads = new AbortController(),
    disposed = false
  const capture = () => ({ generation, userId: session.user?.id, revision: session.sessionRevision })
  const valid = (scope: ReturnType<typeof capture>) =>
    !disposed &&
    scope.generation === generation &&
    !!scope.userId &&
    session.user?.id === scope.userId &&
    session.sessionRevision === scope.revision
  const self = () => state.view?.participants.find((p) => p.userId === session.user?.id)
  function stopLocal(explicit = false) {
    if (explicit) manualStop++
    const needed = state.localConsent || !state.stopped
    state.localConsent = false
    state.stopped = true
    if (!needed) return
    state.mediaRevision++
    void disconnect().catch(() => {
      if (!disposed) state.error = '本机音频释放未确认，请关闭页面检查麦克风。'
    })
  }
  function reset() {
    generation++
    mutation++
    reads.abort()
    reads = new AbortController()
    stopLocal()
    Object.assign(state, {
      enabled: false,
      checked: false,
      capabilityRetryAt: 0,
      view: null,
      busy: false,
      pending: null,
      error: '',
      syncNote: '',
    })
    reading = false
    heartbeating = false
    failures = 0
    lastVerified = 0
    lastHeartbeat = 0
    checkingCapability = false
    capabilityFailures = 0
  }
  function apply(view: NativeCallView | undefined) {
    if (view && !view.participants.some((p) => p.userId === session.user?.id))
      throw new Error('当前通话身份不匹配，请重新登录核对。')
    if (view && state.view?.id === view.id && state.view.version > view.version) return
    if (state.view && state.view.id !== view?.id) stopLocal()
    state.view = view ?? null
    if (!view || view.state === 'ENDED' || self()?.state !== 'ACCEPTED') stopLocal()
    lastVerified = now()
  }
  function shouldCheckCapability() {
    return !state.checked || (state.capabilityRetryAt > 0 && now() >= state.capabilityRetryAt)
  }
  async function initialize() {
    const scope = capture()
    if (!scope.userId || disposed || checkingCapability || !shouldCheckCapability()) return
    checkingCapability = true
    try {
      const capability = await api.capability(reads.signal)
      if (!valid(scope)) return
      if (typeof capability?.enabled !== 'boolean') throw new Error('通话能力响应无效。')
      if (state.enabled && !capability.enabled) stopLocal()
      state.enabled = capability.enabled
      state.capabilityRetryAt = 0
      capabilityFailures = 0
      state.syncNote = ''
    } catch (failure) {
      if (!valid(scope)) return
      if (state.enabled) stopLocal()
      state.enabled = false
      // 已部署的旧服务没有此接口；不开启不存在的能力，也不影响已有消息/语音房。
      const unavailable = failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)
      state.capabilityRetryAt = unavailable ? 0 : now() + Math.min(30000, 3000 * 2 ** Math.min(capabilityFailures++, 4))
      state.syncNote = unavailable ? '' : '直接通话能力暂时无法核对，正在后台重试。'
    } finally {
      if (valid(scope)) {
        state.checked = true
        checkingCapability = false
      }
    }
  }
  async function refresh() {
    if (!state.enabled || state.busy || reading || disposed || !session.user) return
    reading = true
    const scope = capture(),
      version = mutation
    try {
      const view = await api.current(reads.signal)
      if (!valid(scope) || version !== mutation) return
      apply(view)
      failures = 0
      state.syncNote = ''
    } catch (failure) {
      if (!valid(scope) || version !== mutation) return
      if (failure instanceof ApiRequestError && [401, 403, 404].includes(failure.status)) {
        stopLocal()
        state.view = null
        state.syncNote = '当前通话不可访问，已停止本机音频。'
      } else if (++failures >= 3) state.syncNote = '通话状态暂时无法同步，正在后台重试。'
    } finally {
      if (valid(scope)) reading = false
    }
  }
  const rejected = (failure: unknown) =>
    failure instanceof ApiRequestError &&
    [400, 403, 404, 409].includes(failure.status) &&
    ['BAD_REQUEST', 'VALIDATION_ERROR', 'FORBIDDEN', 'NOT_FOUND', 'CONFLICT'].includes(failure.code ?? '')
  async function submit() {
    const pending = state.pending
    if (!pending || state.busy || disposed || !session.user) return
    const scope = capture(),
      stopVersion = pending.stopVersion
    mutation++
    state.busy = true
    state.error = ''
    try {
      const view =
        pending.kind === 'invite' ? await api.invite(pending.input) : (await api.command(pending.input)).current
      if (!valid(scope)) return
      if (pending.kind === 'invite' && view.conversationId !== pending.input.conversationId)
        throw new Error('原通话邀请结果未确认。')
      if (pending.kind === 'command' && view.id !== pending.input.callId) throw new Error('原通话命令结果未确认。')
      apply(view)
      state.pending = null
      if (pending.kind === 'invite' || pending.input.type === 'ACCEPT') {
        if (self()?.state === 'ACCEPTED' && view.state !== 'ENDED' && stopVersion === manualStop) {
          state.localConsent = true
          state.stopped = false
          state.mediaRevision++
        }
      }
    } catch (failure) {
      if (!valid(scope)) return
      if (rejected(failure) && !pending.uncertain) {
        state.pending = null
        state.error = failure instanceof Error ? failure.message : '通话操作被拒绝，请核对当前状态。'
      } else {
        pending.uncertain = true
        state.error = '原操作结果尚未确认。请重试原操作，不会重复创建呼叫。'
      }
    } finally {
      if (valid(scope)) state.busy = false
    }
  }
  async function invite(conversationId: string, targets?: string[]) {
    if (!state.enabled || state.busy || state.pending || (state.view && state.view.state !== 'ENDED')) return
    if (occupied()) {
      state.error = '已有语音连接，请先结束当前通话再发起呼叫。'
      return
    }
    state.pending = {
      kind: 'invite',
      uncertain: false,
      stopVersion: manualStop,
      input: { conversationId, requestId: uuid(), ...(targets ? { targets: [...targets] } : {}) },
    }
    await submit()
  }
  async function command(type: NativeCallCommandType) {
    const view = state.view
    if (!view || !state.enabled || state.busy || state.pending || !Number.isSafeInteger(view.version)) return
    if (type === 'ACCEPT' && occupied()) {
      state.error = '已有语音连接，请先结束当前通话再接听。'
      return
    }
    if (type === 'LEAVE' || type === 'END') stopLocal(true)
    state.pending = {
      kind: 'command',
      uncertain: false,
      stopVersion: manualStop,
      input: { callId: view.id, requestId: uuid(), expectedVersion: view.version, type },
    }
    await submit()
  }
  /** 明确恢复时可重新获取本人网站的凭据；是否允许仍由服务端判断，不根据ACCEPTED推断。 */
  function resume() {
    if (state.pending || state.busy || state.view?.state !== 'ACTIVE' || self()?.state !== 'ACCEPTED') return
    state.error = ''
    state.localConsent = true
    state.stopped = false
    state.mediaRevision++
    lastVerified = now()
  }
  async function heartbeat() {
    const view = state.view
    if (
      !view ||
      !state.localConsent ||
      state.stopped ||
      self()?.state !== 'ACCEPTED' ||
      heartbeating ||
      now() - lastHeartbeat < 9000
    )
      return
    const scope = capture(),
      id = view.id
    heartbeating = true
    lastHeartbeat = now()
    try {
      await api.heartbeat(id)
      if (valid(scope) && state.view?.id === id && state.localConsent) lastVerified = now()
    } catch (failure) {
      if (!valid(scope) || state.view?.id !== id) return
      if (failure instanceof ApiRequestError && [401, 403, 404, 409].includes(failure.status)) {
        stopLocal()
        state.syncNote = '当前接听会话已失效或在其他设备，已停止本机音频。'
      }
    } finally {
      if (valid(scope)) heartbeating = false
    }
  }
  /** 轻量本机看门狗不等待网络请求结束；浏览器完全挂起时仍以服务端租约为准。 */
  function expireUnverified() {
    if (state.localConsent && now() - lastVerified > 25000) {
      stopLocal()
      state.syncNote = '网络状态长时间未确认，已停止本机音频。恢复后请重新连接。'
    }
  }
  function dismiss() {
    if (state.pending || state.busy || (state.view && state.view.state !== 'ENDED')) return
    stopLocal()
    state.view = null
    state.error = ''
    state.syncNote = ''
  }
  return {
    state,
    initialize,
    shouldCheckCapability,
    refresh,
    invite,
    command,
    retry: submit,
    heartbeat,
    expireUnverified,
    resume,
    stopLocal: () => stopLocal(true),
    reset,
    dismiss,
    dispose() {
      reset()
      disposed = true
    },
  }
}
