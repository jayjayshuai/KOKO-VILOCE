import { onBeforeUnmount, ref, watch } from 'vue'
/** 只响应用户点击复制公开地址；不上传数据，不存储凭据。 */
export function useClipboardFeedback(
  address: () => string,
  write: (value: string) => Promise<void> = (value) => navigator.clipboard.writeText(value),
) {
  const busy = ref(false),
    copied = ref(false),
    message = ref('')
  let revision = 0,
    disposed = false
  const stop = watch(
    address,
    () => {
      revision++
      busy.value = false
      copied.value = false
      message.value = ''
    },
    { flush: 'sync' },
  )
  async function copy() {
    if (disposed || busy.value || !address()) return
    const value = address(),
      current = ++revision
    busy.value = true
    copied.value = false
    message.value = ''
    try {
      await write(value)
      if (disposed || current !== revision || address() !== value) return
      copied.value = true
      message.value = '主页地址已复制。'
    } catch {
      if (!disposed && current === revision && address() === value)
        message.value = '浏览器未允许复制，请选中下方地址手动复制。'
    } finally {
      if (!disposed && current === revision) busy.value = false
    }
  }
  onBeforeUnmount(() => {
    disposed = true
    revision++
    stop()
  })
  return { busy, copied, message, copy }
}
