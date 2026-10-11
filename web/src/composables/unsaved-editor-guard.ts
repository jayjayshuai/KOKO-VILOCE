import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import type { Router } from 'vue-router'
import type { CreatorEditorContext } from './creator-editor-writes'

/** 仅比较当前编辑器可写字段；正文只留在内存，不写浏览器存储或网络。 */
export function useUnsavedEditorGuard(
  context: () => CreatorEditorContext,
  fields: () => Record<string, string>,
  ready: () => boolean,
  busy: () => boolean,
  unconfirmed: () => boolean,
  navigation: Pick<Router, 'beforeEach' | 'afterEach'>,
  onNavigated: () => void,
) {
  /** 真实打开或读取/保存确认后的基线，版本/计数等只读字段不参与脏检查。 */
  const baseline = shallowRef<{ key: string; fields: string } | null>(null)
  let disposed = false,
    unloadAttached = false
  const key = () => {
    const value = context()
    return JSON.stringify([value.userId, value.sessionRevision, value.kind, value.formRevision])
  }
  const active = () => {
    const value = context()
    return !disposed && !!value.userId && ['post', 'creator'].includes(value.kind ?? '')
  }
  /** 不把异步主页读取时的默认资料当已确认基线。 */
  const dirty = computed(
    () => active() && ready() && baseline.value?.key === key() && baseline.value.fields !== JSON.stringify(fields()),
  )
  /** 在途/未知写入也提醒；只读初始加载没有用户修改则不阻止离开。 */
  const shouldWarn = computed(() => active() && (busy() || unconfirmed() || dirty.value))
  function markConfirmed() {
    if (active() && ready()) baseline.value = { key: key(), fields: JSON.stringify(fields()) }
  }
  /** 确认只是允许调用者尝试关闭/替换；此处不清理基线，以免随后导航被其他守卫拒绝。 */
  function requestLeave() {
    if (!active()) return true
    if (busy()) return false
    if (unconfirmed())
      return window.confirm('提交结果仍未核实，离开会丢失当前页面的核实信息，不会撤回服务端操作。是否继续离开？')
    if (dirty.value) return window.confirm('有尚未保存的修改。离开会丢弃当前表单中的修改，不会自动保存。是否继续离开？')
    return true
  }
  /** 浏览器决定是否显示原生提醒，移动端进程终止不保证触发；绝不在卸载时偷偷发送保存。 */
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!shouldWarn.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  const stopContext = watch(
    key,
    () => {
      baseline.value = null
    },
    { flush: 'sync' },
  )
  const stopWarning = watch(
    shouldWarn,
    (value) => {
      if (value && !unloadAttached) {
        window.addEventListener('beforeunload', beforeUnload)
        unloadAttached = true
      } else if (!value && unloadAttached) {
        window.removeEventListener('beforeunload', beforeUnload)
        unloadAttached = false
      }
    },
    { immediate: true, flush: 'sync' },
  )
  const removeBefore = navigation.beforeEach((to, from) => to.fullPath === from.fullPath || requestLeave())
  const removeAfter = navigation.afterEach((to, from, failure) => {
    if (!failure && to.fullPath !== from.fullPath && active()) onNavigated()
  })
  onBeforeUnmount(() => {
    disposed = true
    stopContext()
    stopWarning()
    removeBefore()
    removeAfter()
    if (unloadAttached) window.removeEventListener('beforeunload', beforeUnload)
    unloadAttached = false
    baseline.value = null
  })
  return { dirty, shouldWarn, markConfirmed, requestLeave }
}
