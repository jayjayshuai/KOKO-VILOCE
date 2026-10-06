import { ref, watch, onBeforeUnmount } from 'vue'
import { voiceMediaPlanApi, type VoiceMediaPlanView } from '../services/voice-media-plan'

/** 与互动/审计分开读取，失败不将旧进度当成清退完成；无写入、JWT或续约。 */
export function useVoiceMediaPlan(
  context: {
    /** 房间正数ID字符串。 */ roomId: string
    /** 当前可信身份。 */ userId: string
    /** 登录会话轮次。 */ sessionRevision: number
    /** 父面板当前授权读取成功，失效后清除进度。 */ allowed: boolean
  },
  network = voiceMediaPlanApi,
) {
  const paused = ref('')
  const plan = ref<VoiceMediaPlanView | null>(null),
    loading = ref(false),
    error = ref('')
  let controller = new AbortController(),
    revision = 0,
    disposed = false
  let timer: ReturnType<typeof setInterval> | undefined,
    failures = 0,
    nextAt = 0
  function pauseReason() {
    if (!context.allowed || !context.userId) return '当前授权尚未核验，暂停媒体计划读取。'
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return '网络已离线，媒体计划进度未确认。'
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden')
      return '页面已在后台，回到前台后重新读取媒体计划。'
    return ''
  }
  const usable = () => !disposed && !pauseReason()
  function invalidate() {
    revision++
    controller.abort()
    plan.value = null
    loading.value = false
  }
  async function load(manual = true) {
    paused.value = pauseReason()
    if (!usable() || loading.value || (!manual && Date.now() < nextAt)) return
    controller = new AbortController()
    const stamp = ++revision,
      room = context.roomId,
      user = context.userId,
      session = context.sessionRevision
    const current = () =>
      !disposed &&
      stamp === revision &&
      room === context.roomId &&
      user === context.userId &&
      session === context.sessionRevision &&
      context.allowed
    loading.value = true
    error.value = ''
    try {
      const value = await network.get(room, controller.signal)
      if (current()) {
        plan.value = value
        failures = 0
        nextAt = 0
      }
    } catch (cause) {
      if (current()) {
        plan.value = null
        error.value = cause instanceof Error ? cause.message : '媒体计划读取失败'
        failures++
        nextAt = Date.now() + Math.min(60000, 5000 * 2 ** Math.min(failures - 1, 4))
      }
    } finally {
      if (current()) loading.value = false
    }
  }
  const stop = watch(
    () => [context.roomId, context.userId, context.sessionRevision, context.allowed],
    () => {
      invalidate()
      paused.value = pauseReason()
      error.value = ''
      failures = 0
      nextAt = 0
      if (timer) clearInterval(timer)
      if (context.allowed && context.userId) {
        void load()
        timer = setInterval(() => {
          void load(false)
        }, 5000)
      }
    },
    { immediate: true, flush: 'sync' },
  )
  function changed() {
    invalidate()
    paused.value = pauseReason()
    if (usable()) void load()
  }
  if (typeof window !== 'undefined')
    for (const event of ['offline', 'online', 'focus']) window.addEventListener(event, changed)
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', changed)
  onBeforeUnmount(() => {
    disposed = true
    stop()
    invalidate()
    if (timer) clearInterval(timer)
    if (typeof window !== 'undefined')
      for (const event of ['offline', 'online', 'focus']) window.removeEventListener(event, changed)
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', changed)
  })
  return { plan, loading, error, load, paused }
}
