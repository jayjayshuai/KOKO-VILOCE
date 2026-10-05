import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

/** 为现有业务对话框补焦点循环/还原；不替代各服务的权限校验。 */
export function useDialogAccessibility(open: Ref<boolean>, close: () => void, busy: () => boolean) {
  let previous: HTMLElement | null = null,
    revision = 0
  const visibleDialog = () => [...document.querySelectorAll<HTMLElement>('.modal-backdrop')].at(-1)
  const focusable = (root: HTMLElement) =>
    [
      ...root.querySelectorAll<HTMLElement>(
        'a[href], button, input, textarea, select, [tabindex]:not([tabindex="-1"])',
      ),
    ].filter((element) => !element.hasAttribute('disabled') && element.getClientRects().length > 0)
  function keydown(event: KeyboardEvent) {
    if (!open.value) return
    const root = visibleDialog()
    if (!root) return
    if (event.key === 'Escape') {
      event.preventDefault()
      if (!busy()) close()
      return
    }
    if (event.key !== 'Tab') return
    const targets = focusable(root),
      first = targets[0],
      last = targets.at(-1)
    if (!first || !last) {
      event.preventDefault()
      root.focus()
      return
    }
    if (!root.contains(document.activeElement) || (event.shiftKey && document.activeElement === first)) {
      event.preventDefault()
      ;(event.shiftKey ? last : first).focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }
  const stop = watch(
    open,
    async (value) => {
      const current = ++revision
      if (value) {
        previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
        document.addEventListener('keydown', keydown)
        await nextTick()
        if (current !== revision || !open.value) return
        const root = visibleDialog()
        if (root) (focusable(root)[0] ?? root).focus()
      } else {
        document.removeEventListener('keydown', keydown)
        if (previous?.isConnected) previous.focus()
        previous = null
      }
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    ++revision
    stop()
    document.removeEventListener('keydown', keydown)
  })
}
