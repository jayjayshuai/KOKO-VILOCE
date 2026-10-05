import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ApiRequestError } from '../services/http'
import type { BindingReleaseDomain, BindingReleaseTask } from '../services/binding-releases'
import type { OperationsAccess } from '../services/operations'
import type { bindingRecoveryApi, BindingRecoveryAudit } from '../services/binding-recovery'
import type { PendingBindingRecovery } from '../stores/binding-recovery'
interface RetryStore {
  /** 仅内存持有的原命令。 */ pending: PendingBindingRecovery | null
  /** 创建未发送命令。 */ prepare(domain: BindingReleaseDomain, command: PendingBindingRecovery['command']): void
  /** 重录原命令，强制保留未知状态。 */ restore(
    domain: BindingReleaseDomain,
    command: PendingBindingRecovery['command'],
  ): void
  /** 不允许丢弃结果未知命令。 */ finish(): void
}
interface Session {
  /** 当前账号。 */ user: { id: string } | null
  /** 认证轮次。 */ sessionRevision: number
}
/** 独立资产恢复流程；写入超时/取消不等于回滚，原命令跨路由保留。 */
export function useBindingRecoveryWorkspace(
  api: typeof bindingRecoveryApi,
  domain: Ref<BindingReleaseDomain>,
  task: Ref<BindingReleaseTask | null>,
  access: Ref<OperationsAccess | null>,
  session: Session,
  retry: RetryStore,
  newId: () => string = () => crypto.randomUUID(),
  invalidateAccess: () => void = () => {
    access.value = null
  },
  secretTransportAllowed: () => boolean = () => false,
) {
  const reason = ref(''),
    password = ref(''),
    error = ref(''),
    message = ref(''),
    busy = ref(false)
  const restoreRequestId = ref(''),
    restoreCommandId = ref(''),
    restoreGeneration = ref(''),
    restoreReason = ref('')
  const audits = ref<BindingRecoveryAudit[]>([]),
    auditCursor = ref<number | null>(null),
    auditLoading = ref(false),
    auditError = ref(''),
    auditsLoaded = ref(false)
  const secret = ref<string | null>(null)
  let epoch = 0,
    disposed = false,
    request = new AbortController(),
    auditRequest = new AbortController(),
    auditEpoch = 0
  const canRead = computed(
    () => !!session.user && access.value?.enabled === true && access.value.permissions.includes('asset:binding:read'),
  )
  const secureTransport = computed(secretTransportAllowed)
  const canReplay = computed(
    () => secureTransport.value && canRead.value && access.value!.permissions.includes('asset:binding:replay'),
  )
  const pending = computed(() => retry.pending)
  const confirmationReady = computed(() => secret.value !== null)
  function reset() {
    epoch++
    auditEpoch++
    request.abort()
    auditRequest.abort()
    secret.value = null
    password.value = ''
    reason.value = ''
    restoreRequestId.value = ''
    restoreCommandId.value = ''
    restoreGeneration.value = ''
    restoreReason.value = ''
    busy.value = false
    auditLoading.value = false
    audits.value = []
    auditCursor.value = null
    auditsLoaded.value = false
    auditError.value = ''
    error.value = ''
    message.value = ''
  }
  const current = (version: number) => !disposed && version === epoch
  function denied(cause: unknown) {
    if (cause instanceof ApiRequestError && [401, 403].includes(cause.status)) {
      reset()
      invalidateAccess()
      error.value = '权限或会话已失效，原命令未被当作失败丢弃。请重新读取权限。'
      return true
    }
    return false
  }
  function prepare() {
    if (
      disposed ||
      !canReplay.value ||
      busy.value ||
      retry.pending ||
      task.value?.status !== 'DEAD' ||
      task.value.generationAttempts !== 10 ||
      task.value.replayGeneration >= 10
    )
      return
    const why = reason.value.trim()
    if (Array.from(why).length < 10 || why.length > 500) {
      error.value = '请填写10～500字符的工单与根因处理说明'
      return
    }
    retry.prepare(domain.value, {
      commandId: newId(),
      requestId: task.value.requestId,
      expectedGeneration: task.value.replayGeneration,
      reason: why,
    })
    reason.value = ''
    error.value = ''
    message.value = '原命令已准备，未受理。请核对目标并确认本人密码。'
  }
  /** 只导入操作者保存的原字段；先查询，不擅自创建新命令。 */
  function restore() {
    if (disposed || !canRead.value || busy.value || retry.pending) return
    error.value = ''
    if (!/^(?:[0-9]|10)$/.test(restoreGeneration.value)) {
      error.value = '请填写保存的原确认代次（0～10），不能猜测当前代次'
      return
    }
    try {
      retry.restore(domain.value, {
        requestId: restoreRequestId.value.trim().toLowerCase(),
        commandId: restoreCommandId.value.trim().toLowerCase(),
        expectedGeneration: Number(restoreGeneration.value),
        reason: restoreReason.value.trim(),
      })
      restoreRequestId.value = ''
      restoreCommandId.value = ''
      restoreGeneration.value = ''
      restoreReason.value = ''
      message.value = '原命令已重录为结果未知。先查询受理事实；查不到也不得生成第二个ID。'
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '原命令重录失败'
    }
  }
  async function confirm() {
    const item = retry.pending
    if (disposed || !canReplay.value || busy.value || !item || item.phase === 'accepted') return
    if (!password.value || password.value.length > 72) {
      error.value = '请输入本人密码，最长72字符'
      return
    }
    const version = epoch
    request.abort()
    request = new AbortController()
    secret.value = null
    busy.value = true
    error.value = ''
    message.value = ''
    try {
      const proof = await api.confirm(item.domain, item.command, password.value, request.signal)
      if (current(version) && retry.pending === item && canReplay.value) {
        secret.value = proof.confirmationToken
        message.value = '本人密码已确认；受理前仍需当前权限。确认有效期五分钟。'
      }
    } catch (cause) {
      if (current(version) && !denied(cause)) error.value = cause instanceof Error ? cause.message : '二次确认不可用'
    } finally {
      if (current(version)) {
        password.value = ''
        busy.value = false
      }
    }
  }
  async function submit() {
    const item = retry.pending,
      token = secret.value
    if (disposed || !canReplay.value || busy.value || !item || item.phase === 'accepted' || !token) return
    const version = epoch
    request.abort()
    request = new AbortController()
    busy.value = true
    error.value = ''
    message.value = ''
    // 先保存结果未知，Abort/超时也不能悄悄创建新ID或推断事务未提交。
    const previouslyUncertain = item.phase === 'uncertain'
    item.phase = 'uncertain'
    secret.value = null
    password.value = ''
    try {
      const receipt = await api.replay(item.domain, item.command, token, request.signal)
      if (current(version) && retry.pending === item) {
        item.receipt = receipt
        item.phase = 'accepted'
        message.value = '已受理并记录审计，不代表保护释放完成。请刷新原请求事实。'
      }
    } catch (cause) {
      if (current(version)) {
        const rejected =
          !previouslyUncertain && cause instanceof ApiRequestError && [400, 403, 404, 409].includes(cause.status)
        if (rejected) item.phase = 'prepared'
        if (!denied(cause))
          error.value =
            (rejected
              ? '本次请求明确未受理，可取消或核对后重试原命令。'
              : '受理结果未确认，请查询原命令或重新确认同一命令后重试。') +
            (cause instanceof Error ? cause.message : '')
      }
    } finally {
      if (current(version)) busy.value = false
    }
  }
  async function query() {
    const item = retry.pending
    if (disposed || !canRead.value || busy.value || !item) return
    const version = epoch
    request.abort()
    request = new AbortController()
    busy.value = true
    error.value = ''
    message.value = ''
    try {
      const value = await api.receipt(item.domain, item.command.requestId, item.command.commandId, request.signal)
      if (!current(version) || retry.pending !== item) return
      if (
        value.operatorId !== item.operatorId ||
        value.expectedGeneration !== item.command.expectedGeneration ||
        value.acceptedGeneration !== item.command.expectedGeneration + 1 ||
        value.reason !== item.command.reason
      )
        throw new Error('原命令审计内容不匹配')
      item.receipt = {
        commandId: value.commandId,
        requestId: value.requestId,
        acceptedGeneration: value.acceptedGeneration,
        acceptedAt: value.createdAt,
      }
      item.phase = 'accepted'
      secret.value = null
      message.value = '已查询到原命令真实受理事实，不会再次创建代次。'
    } catch (cause) {
      if (current(version) && !denied(cause))
        error.value =
          cause instanceof ApiRequestError && cause.status === 404
            ? '本次未观察到受理事实，不证明在途提交已终止。保留原命令，重新确认同一命令后幂等重试。'
            : cause instanceof Error
              ? cause.message
              : '原命令查询不可用'
    } finally {
      if (current(version)) busy.value = false
    }
  }
  function finish() {
    if (disposed || busy.value || retry.pending?.phase === 'uncertain') return
    retry.finish()
    secret.value = null
    password.value = ''
    error.value = ''
    message.value = ''
  }
  async function loadAudits(refresh = true) {
    if (disposed || !canRead.value || !task.value || auditLoading.value || (!refresh && auditCursor.value === null))
      return
    const version = epoch,
      revision = ++auditEpoch,
      target = task.value.requestId,
      selectedDomain = domain.value,
      cursor = refresh ? null : auditCursor.value
    auditRequest.abort()
    auditRequest = new AbortController()
    auditLoading.value = true
    auditError.value = ''
    if (refresh) {
      audits.value = []
      auditCursor.value = null
      auditsLoaded.value = false
    }
    try {
      const page = await api.audits(selectedDomain, target, cursor, auditRequest.signal)
      if (!current(version) || revision !== auditEpoch || task.value?.requestId !== target) return
      const next = [...audits.value, ...page.items]
      if (
        next.length > 10 ||
        new Set(next.map((item) => item.commandId)).size !== next.length ||
        (page.nextGeneration !== null && page.nextGeneration === cursor)
      )
        throw new Error('审计分页重复或超过十代上限')
      audits.value = next
      auditCursor.value = page.nextGeneration
      auditsLoaded.value = true
    } catch (cause) {
      if (current(version) && revision === auditEpoch && !denied(cause))
        auditError.value = cause instanceof Error ? cause.message : '审计读取失败'
    } finally {
      if (current(version) && revision === auditEpoch) auditLoading.value = false
    }
  }
  const stop = watch(
    [
      () => session.user?.id,
      () => session.sessionRevision,
      () => domain.value,
      () => task.value?.requestId,
      () => access.value?.enabled,
      () => access.value?.permissions.join('|'),
    ],
    () => reset(),
    { flush: 'sync' },
  )
  function dispose() {
    disposed = true
    stop()
    reset()
  }
  onBeforeUnmount(dispose)
  return {
    reason,
    restoreRequestId,
    restoreCommandId,
    restoreGeneration,
    restoreReason,
    password,
    error,
    message,
    busy,
    audits,
    auditCursor,
    auditLoading,
    auditError,
    auditsLoaded,
    canRead,
    canReplay,
    pending,
    confirmationReady,
    secureTransport,
    prepare,
    restore,
    confirm,
    submit,
    query,
    finish,
    loadAudits,
    dispose,
  }
}
