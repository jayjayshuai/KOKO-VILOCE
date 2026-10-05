import { onBeforeUnmount, ref, watch } from 'vue'
import type { VoiceRoom } from '../api'
import { voiceOwnerApi, type VoiceOwnerApi } from '../services/voice-owner'

/** 页面只保留身份及轮次，不保存登录或媒体令牌。 */
interface VoiceOwnerSession {
  /** 当前可信账号；匿名为空。 */
  userId?: string
  /** 同账号重新登录也改变轮次。 */
  sessionRevision: number
}

/** 本人房间管理；读取取消、写结果未知和身份轮次各自处理。 */
export function useVoiceOwnerWorkspace(
  session: VoiceOwnerSession,
  onClosed: (roomId: string) => void,
  network: VoiceOwnerApi = voiceOwnerApi,
) {
  /** 服务器返回的本人房间；失败不冒充空态成功。 */
  const rooms = ref<VoiceRoom[]>([])
  /** 字符串独占ID游标。 */
  const nextBefore = ref<string | null>(null)
  /** 当前读取阶段，防止重复分页。 */
  const loading = ref(false)
  /** 列表错误，刷新不会吞掉写入未知提示。 */
  const readError = ref('')
  /** 已关闭/未知写入反馈，保留直到明确新操作。 */
  const writeError = ref('')
  /** 只有服务端成功确认后展示。 */
  const success = ref('')
  /** 正在确认的房间快照，不把弹窗当成授权。 */
  const confirmation = ref<{ id: string; title: string } | null>(null)
  /** 单一正在写入的房间，禁止重复发送关闭。 */
  const closing = ref<string | null>(null)
  /** 身份/卸载与读取有独立轮次，旧写入不能修改新页面。 */
  let epoch = 0,
    readRevision = 0,
    disposed = false
  /** 读取取消不涉及写入。 */
  let read = new AbortController()
  /** 身份变化/卸载取消等待，不宣称SQL或媒体操作撤销。 */
  let write = new AbortController()
  const current = (revision: number, userId: string | undefined, sessionRevision: number) =>
    !disposed && epoch === revision && session.userId === userId && session.sessionRevision === sessionRevision
  const message = (cause: unknown) => (cause instanceof Error ? cause.message : '语音房操作暂不可用')

  /** 刷新替换旧读取；翻页保留原事实，失败仍可从原游标重试。 */
  async function load(reset = true) {
    if (disposed || !session.userId || closing.value || (!reset && (loading.value || !nextBefore.value))) return
    read.abort()
    read = new AbortController()
    const request = ++readRevision,
      revision = epoch,
      userId = session.userId,
      sessionRevision = session.sessionRevision
    const cursor = reset ? null : nextBefore.value
    confirmation.value = null
    if (reset) {
      rooms.value = []
      nextBefore.value = null
    }
    loading.value = true
    readError.value = ''
    try {
      const page = await network.list(cursor, read.signal)
      if (!current(revision, userId, sessionRevision) || request !== readRevision) return
      const existing = new Set(rooms.value.map((room) => room.id))
      rooms.value = reset ? page.items : [...rooms.value, ...page.items.filter((room) => !existing.has(room.id))]
      nextBefore.value = page.nextBefore
    } catch (cause) {
      if (current(revision, userId, sessionRevision) && request === readRevision) readError.value = message(cause)
    } finally {
      if (current(revision, userId, sessionRevision) && request === readRevision) loading.value = false
    }
  }

  /** 必须是本页真实OPEN房间；服务端仍重新核对所有者及当前状态。 */
  function prepareClose(room: VoiceRoom) {
    if (disposed || !session.userId || loading.value || closing.value) return
    const found = rooms.value.find((item) => item.id === room.id && item.status === 'OPEN')
    if (!found) return
    confirmation.value = { id: found.id, title: found.title }
    writeError.value = ''
    success.value = ''
  }
  function cancelClose() {
    if (!closing.value) confirmation.value = null
  }

  /** 未确认不更新状态；后台可能已关闭，重试沿用同一个房间ID。 */
  async function confirmClose() {
    const target = confirmation.value
    if (disposed || !session.userId || !target || loading.value || closing.value) return
    const revision = epoch,
      userId = session.userId,
      sessionRevision = session.sessionRevision
    closing.value = target.id
    writeError.value = ''
    success.value = ''
    try {
      await network.close(target.id, write.signal)
      if (!current(revision, userId, sessionRevision)) return
      rooms.value = rooms.value.map((room) => (room.id === target.id ? { ...room, status: 'CLOSED' } : room))
      confirmation.value = null
      success.value = `已确认关闭「${target.title}」。`
      try {
        onClosed(target.id)
      } catch {
        // 本机快照/媒体清理是提交后的副作用，不能把已确认关闭改成未知失败。
        writeError.value = '房间关闭已确认，但本机界面或媒体清理未完成，请刷新并手动离开旧连接。'
      }
    } catch (cause) {
      if (current(revision, userId, sessionRevision))
        writeError.value = `${message(cause)}。关闭结果未确认，服务端可能已执行；请刷新后按原房间重试。`
    } finally {
      if (current(revision, userId, sessionRevision)) closing.value = null
    }
  }

  /** 同步撤销旧身份结果与等待；不把本地取消当媒体撤销。 */
  function resetSession() {
    epoch++
    readRevision++
    read.abort()
    write.abort()
    read = new AbortController()
    write = new AbortController()
    rooms.value = []
    nextBefore.value = null
    confirmation.value = null
    loading.value = false
    closing.value = null
    readError.value = ''
    writeError.value = ''
    success.value = ''
  }
  const stop = watch(
    () => [session.userId, session.sessionRevision],
    () => {
      resetSession()
      if (session.userId) void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    stop()
    resetSession()
  })
  return {
    rooms,
    nextBefore,
    loading,
    readError,
    writeError,
    success,
    confirmation,
    closing,
    load,
    prepareClose,
    cancelClose,
    confirmClose,
  }
}
