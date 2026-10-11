import { voiceMediaPlanApi } from './voice-media-plan'

/** 等待已登记的旧连接清退；只做有界读取，不重试写命令、不签JWT，也不授予发布权限。 */
export async function waitForVoiceMediaRetirement(
  roomId: string,
  signal?: AbortSignal,
  /** 固定上限的可测时序；生产调用使用默认10秒窗口。 */
  timing = { rounds: 20, intervalMs: 500 },
): Promise<void> {
  const cancelled = () => {
    if (signal?.aborted) throw new DOMException('语音准备已取消', 'AbortError')
  }
  for (let attempt = 0; attempt < timing.rounds; attempt++) {
    cancelled()
    const plan = await voiceMediaPlanApi.get(roomId, signal)
    cancelled()
    if (!plan.tracked || !plan.active || plan.deadRetirements !== 0)
      throw new Error('语音清退尚未完成，请稍后重新连接；持续失败时请联系房主检查媒体服务。')
    if (plan.pendingRetirements === 0) return
    if (attempt + 1 === timing.rounds) break
    await new Promise<void>((resolve, reject) => {
      const abort = () => {
        clearTimeout(timer)
        signal?.removeEventListener('abort', abort)
        reject(new DOMException('语音准备已取消', 'AbortError'))
      }
      const timer = setTimeout(() => {
        signal?.removeEventListener('abort', abort)
        resolve()
      }, timing.intervalMs)
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) abort()
    })
  }
  throw new Error('语音仍在准备中，请稍候再点击“连接语音”；不会自动开启麦克风。')
}
