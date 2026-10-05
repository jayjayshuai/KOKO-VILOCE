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
  const canAudit = () => !!snapshot.value?.mySessionId && ['OWNER', 'ADMIN'].includes(snapshot.value.myRole || '')
  const stopAudit = watch(
    () => [snapshot.value?.mySessionId, snapshot.value?.myRole],
    () => {
      if (!canAudit()) clearActions()
    },
    { flush: 'sync' },
  )
  async function loadActions(more = false) {
    if (disposed || actionLoading.value || !canAudit() || (more && !actions.value?.nextBefore)) return
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
      if (current(saved) && revision === actionRevision) actionError.value = failure(cause)
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

  async function load() {
    if (disposed || busy.value || !context.userId) return
    read.abort()
    read = new AbortController()
    const saved = capture(),
      revision = ++readRevision
    loading.value = true
    readError.value = ''
    try {
      const cap = await network.capabilities(saved.roomId, read.signal)
      if (!current(saved) || revision !== readRevision) return
      capabilities.value = cap
      if (!cap.enabled || !cap.canInspect) {
        snapshot.value = null
        return
      }
      const data = await network.snapshot(saved.roomId, read.signal)
      if (current(saved) && revision === readRevision) snapshot.value = data
    } catch (cause) {
      if (current(saved) && revision === readRevision) readError.value = failure(cause)
    } finally {
      if (current(saved) && revision === readRevision) loading.value = false
    }
  }
  async function transmit() {
    if (disposed || busy.value || !pending.value) return
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
      busy.value ||
      loading.value ||
      pending.value ||
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
    if (disposed || busy.value || loading.value || pending.value || !data?.mySessionId || !capabilities.value?.enabled)
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
  /** 明确放弃只是停止本地重试，不表示取消SQL；重新读取已提交事实。 */
  async function discard() {
    if (!busy.value) {
      pending.value = null
      await load()
    }
  }
  async function pulse() {
    const sessionId = snapshot.value?.mySessionId
    if (disposed || busy.value || pending.value || !sessionId || !capabilities.value?.enabled) return
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
    readError.value = ''
    writeError.value = ''
    success.value = ''
  }
  const stop = watch(
    () => [context.roomId, context.userId, context.sessionRevision],
    () => {
      reset()
      if (!context.userId) return
      void load()
      poll = setInterval(() => {
        if (!loading.value && !busy.value) void load()
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
    join,
    command,
    transmit,
    discard,
    pulse,
  }
}
