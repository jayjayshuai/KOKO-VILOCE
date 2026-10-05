import { computed, onBeforeUnmount, ref } from 'vue'
import { ApiRequestError } from '../services/http'
import type {
  operationsApi,
  OperationsAccess,
  OutboxAudit,
  OutboxCursor,
  OutboxDomain,
  OutboxEvent,
} from '../services/operations'
import type { useOperationsRetryStore } from '../stores/operations-retry'

/** 页面状态执行实际接口；网络桩只用于测试，不在运行中构造示范事件或默认权限。 */
export function useOutboxWorkspace(
  api: typeof operationsApi,
  retry: ReturnType<typeof useOperationsRetryStore>,
  secure: boolean,
) {
  const access = ref<OperationsAccess | null>(null),
    accessError = ref(''),
    accessLoading = ref(false)
  const domain = ref<OutboxDomain>(retry.pending?.domain ?? 'identity')
  const rows = ref<OutboxEvent[]>([]),
    cursor = ref<OutboxCursor | null>(null),
    loaded = ref(false),
    loading = ref(false)
  const selected = ref<OutboxEvent | null>(null),
    audits = ref<OutboxAudit[]>([]),
    auditCursor = ref<number | null>(null)
  const detailLoading = ref(false),
    auditLoading = ref(false),
    error = ref(''),
    detailError = ref(''),
    actionError = ref('')
  const reason = ref(''),
    password = ref(''),
    busy = ref(false),
    notice = ref('')
  const canRead = computed(
    () => access.value?.enabled === true && access.value.permissions.includes('notification:outbox:read'),
  )
  const canReplay = computed(() => canRead.value && access.value!.permissions.includes('notification:outbox:replay'))
  const pending = computed(() => retry.pending)
  let disposed = false,
    listRevision = 0,
    detailRevision = 0
  let listRequest = new AbortController(),
    detailRequest = new AbortController()
  const lifetime = new AbortController(),
    writes = new AbortController()
  const failure = (cause: unknown) => (cause instanceof Error ? cause.message : '运营请求失败')
  function revoked(cause: unknown) {
    if (cause instanceof ApiRequestError && cause.status === 403) {
      access.value = null
      rows.value = []
      selected.value = null
      audits.value = []
      loading.value = false
      detailLoading.value = false
      auditLoading.value = false
      password.value = ''
      accessError.value = '当前运营权限已改变，请重新读取本人权限。'
      listRevision++
      detailRevision++
      listRequest.abort()
      detailRequest.abort()
    }
  }
  async function loadAccess() {
    if (disposed || accessLoading.value || busy.value) return
    accessLoading.value = true
    accessError.value = ''
    access.value = null
    listRevision++
    detailRevision++
    listRequest.abort()
    detailRequest.abort()
    rows.value = []
    cursor.value = null
    selected.value = null
    audits.value = []
    auditCursor.value = null
    loaded.value = false
    loading.value = false
    detailLoading.value = false
    auditLoading.value = false
    error.value = ''
    detailError.value = ''
    password.value = ''
    try {
      const current = await api.access(lifetime.signal)
      if (disposed) return
      access.value = current
      if (canRead.value) await load(true)
    } catch (cause) {
      if (!disposed)
        accessError.value =
          cause instanceof ApiRequestError && cause.status === 404
            ? '此环境尚未启用运营接口，不能读取或执行重放。'
            : failure(cause)
    } finally {
      if (!disposed) accessLoading.value = false
    }
  }
  async function load(reset = true) {
    if (disposed || !canRead.value || loading.value || busy.value || (!reset && !cursor.value)) return
    const revision = ++listRevision,
      target = domain.value
    listRequest.abort()
    listRequest = new AbortController()
    loading.value = true
    error.value = ''
    try {
      const page = await api.dead(target, reset ? null : cursor.value, listRequest.signal)
      if (disposed || revision !== listRevision || target !== domain.value || !canRead.value) return
      const unique = new Map((reset ? [] : rows.value).map((row) => [row.id, row]))
      page.items.forEach((row) => unique.set(row.id, row))
      rows.value = [...unique.values()]
      cursor.value = page.nextCursor
      loaded.value = true
    } catch (cause) {
      if (!disposed && revision === listRevision) {
        error.value = failure(cause)
        revoked(cause)
      }
    } finally {
      if (!disposed && revision === listRevision) loading.value = false
    }
  }
  async function chooseDomain(next: OutboxDomain) {
    if (disposed || busy.value || (retry.pending && next !== retry.pending.domain)) return
    if (!['identity', 'community', 'live'].includes(next)) return
    domain.value = next
    listRevision++
    detailRevision++
    listRequest.abort()
    detailRequest.abort()
    loading.value = false
    detailLoading.value = false
    auditLoading.value = false
    rows.value = []
    cursor.value = null
    selected.value = null
    audits.value = []
    auditCursor.value = null
    loaded.value = false
    detailError.value = ''
    actionError.value = ''
    reason.value = ''
    password.value = ''
    await load(true)
  }
  async function select(event: string) {
    if (disposed || !canRead.value || busy.value || (retry.pending && event !== retry.pending.command.eventId)) return
    const revision = ++detailRevision,
      target = domain.value
    detailRequest.abort()
    detailRequest = new AbortController()
    selected.value = null
    audits.value = []
    auditCursor.value = null
    reason.value = ''
    password.value = ''
    detailLoading.value = true
    auditLoading.value = false
    detailError.value = ''
    actionError.value = ''
    try {
      const detail = await api.detail(target, event, detailRequest.signal)
      if (disposed || revision !== detailRevision || target !== domain.value || !canRead.value) return
      selected.value = detail
      await loadAudits(true)
    } catch (cause) {
      if (!disposed && revision === detailRevision) {
        detailError.value = failure(cause)
        revoked(cause)
      }
    } finally {
      if (!disposed && revision === detailRevision) detailLoading.value = false
    }
  }
  async function loadAudits(reset = false) {
    if (disposed || !canRead.value || !selected.value || auditLoading.value || (!reset && auditCursor.value === null))
      return
    const revision = detailRevision,
      event = selected.value.id,
      target = domain.value
    auditLoading.value = true
    try {
      const page = await api.audits(target, event, reset ? null : auditCursor.value, detailRequest.signal)
      if (disposed || revision !== detailRevision || selected.value?.id !== event || !canRead.value) return
      audits.value = reset ? page.items : [...audits.value, ...page.items]
      auditCursor.value = page.nextGeneration
      detailError.value = ''
    } catch (cause) {
      if (!disposed && revision === detailRevision) {
        detailError.value = `事件已读取，审计读取失败：${failure(cause)}`
        revoked(cause)
      }
    } finally {
      if (!disposed && revision === detailRevision) auditLoading.value = false
    }
  }
  function prepare() {
    actionError.value = ''
    if (!canReplay.value || busy.value || retry.pending || selected.value?.status !== 'DEAD') return
    if (!secure) {
      actionError.value = '敏感确认仅允许 HTTPS 或本机隔离环境，请先配置 TLS。'
      return
    }
    const normalized = reason.value.trim()
    if (normalized.length < 10 || normalized.length > 500) {
      actionError.value = '原因须为 10～500 字符。'
      return
    }
    if (selected.value.replayGeneration >= 10) {
      actionError.value = '人工重放已达十轮上限，请先调查根因。'
      return
    }
    retry.prepare(domain.value, {
      requestId: crypto.randomUUID(),
      eventId: selected.value.id,
      expectedGeneration: selected.value.replayGeneration,
      reason: normalized,
    })
    reason.value = normalized
    notice.value = ''
  }
  async function confirmAndReplay() {
    const command = retry.pending
    if (disposed || !canReplay.value || !command || busy.value || command.phase === 'accepted') return
    if (!secure) {
      actionError.value = '敏感确认仅允许 HTTPS 或本机隔离环境，请先配置 TLS。'
      password.value = ''
      return
    }
    if (!password.value || password.value.length > 72) {
      actionError.value = '请输入本人密码，最多 72 字符。'
      return
    }
    const credential = password.value
    password.value = ''
    busy.value = true
    actionError.value = ''
    notice.value = ''
    let writing = false,
      recheckAccess = false
    try {
      const proof = await api.confirm(command.domain, command.command, credential, writes.signal)
      if (disposed || retry.pending !== command || !canReplay.value) return
      if (!/^[A-Za-z0-9_-]{43}$/.test(proof.confirmationToken)) throw new Error('确认凭据响应不符合协议，未发送重放')
      command.phase = 'uncertain'
      writing = true
      const receipt = await api.replay(command.domain, command.command, proof.confirmationToken, writes.signal)
      if (disposed || retry.pending !== command) return
      if (
        receipt.requestId !== command.command.requestId ||
        receipt.eventId !== command.command.eventId ||
        receipt.generation !== command.command.expectedGeneration + 1
      )
        throw new Error('受理响应不符合原命令，请查询原请求')
      command.phase = 'accepted'
      command.receipt = receipt
      notice.value = '原请求已受理，不代表通知已消费或送达。请刷新事件事实。'
    } catch (cause) {
      if (!disposed && retry.pending === command) {
        actionError.value = writing
          ? `原请求结果待确认，请查询原请求或重新确认同一命令：${failure(cause)}`
          : failure(cause)
        if (writing) revoked(cause)
        else recheckAccess = cause instanceof ApiRequestError && cause.status === 403
      }
    } finally {
      if (!disposed) {
        busy.value = false
        password.value = ''
        if (recheckAccess) await loadAccess()
      }
    }
  }
  async function reconcile() {
    const command = retry.pending
    if (disposed || !canRead.value || !command || busy.value) return
    busy.value = true
    actionError.value = ''
    try {
      const audit = await api.receipt(
        command.domain,
        command.command.eventId,
        command.command.requestId,
        lifetime.signal,
      )
      if (disposed || retry.pending !== command) return
      if (
        audit.operatorId !== command.operatorId ||
        audit.eventId !== command.command.eventId ||
        audit.requestId !== command.command.requestId ||
        audit.expectedGeneration !== command.command.expectedGeneration ||
        audit.reason !== command.command.reason ||
        audit.acceptedGeneration !== command.command.expectedGeneration + 1
      )
        throw new Error('受理审计与原命令不匹配，禁止冒充成功')
      command.phase = 'accepted'
      command.receipt = {
        requestId: audit.requestId,
        eventId: audit.eventId,
        generation: audit.acceptedGeneration,
        acceptedAt: audit.createdAt,
      }
      notice.value = '已读取原受理审计，不代表通知已送达。'
    } catch (cause) {
      if (!disposed && retry.pending === command) {
        actionError.value =
          cause instanceof ApiRequestError && cause.status === 404
            ? '本次未观察到原请求；在途请求仍可能提交，只能重试相同命令，不要新建请求 ID。'
            : failure(cause)
        revoked(cause)
      }
    } finally {
      if (!disposed) busy.value = false
    }
  }
  function finish() {
    if (busy.value || retry.pending?.phase === 'uncertain') return
    retry.finish()
    password.value = ''
    reason.value = ''
    actionError.value = ''
  }
  function dispose() {
    disposed = true
    listRevision++
    detailRevision++
    lifetime.abort()
    writes.abort()
    listRequest.abort()
    detailRequest.abort()
    password.value = ''
    rows.value = []
    selected.value = null
    audits.value = []
  }
  onBeforeUnmount(dispose)
  return {
    access,
    accessError,
    accessLoading,
    domain,
    rows,
    cursor,
    loaded,
    loading,
    selected,
    audits,
    auditCursor,
    detailLoading,
    auditLoading,
    error,
    detailError,
    actionError,
    reason,
    password,
    busy,
    notice,
    pending,
    canRead,
    canReplay,
    loadAccess,
    load,
    chooseDomain,
    select,
    loadAudits,
    prepare,
    confirmAndReplay,
    reconcile,
    finish,
    dispose,
  }
}
