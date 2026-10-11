import { ref } from 'vue'
import type { Router } from 'vue-router'

/** 仅页面加载反馈，不保存资源URL、身份或原始异常信息。 */
export const navigationLoadFailed = ref(false)

/** 捕获发布后的懒加载失败，保留当前通话/表单，绝不自动刷新或重放业务请求。 */
export function installNavigationRecovery(router: Pick<Router, 'onError' | 'afterEach'>) {
  const onPreloadError = () => {
    navigationLoadFailed.value = true
  }
  window.addEventListener('vite:preloadError', onPreloadError)
  const removeError = router.onError(() => {
    navigationLoadFailed.value = true
  })
  const removeAfter = router.afterEach((_to, _from, failure) => {
    if (!failure) navigationLoadFailed.value = false
  })
  return () => {
    window.removeEventListener('vite:preloadError', onPreloadError)
    removeError()
    removeAfter()
    navigationLoadFailed.value = false
  }
}
