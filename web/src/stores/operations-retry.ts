import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from './auth'
import type { OutboxDomain, ReplayCommand, ReplayReceipt } from '../services/operations'

export interface PendingReplay {
  /** 已认证操作者，不从表单读取。 */
  operatorId: string
  /** 绑定当前 UI 会话轮次，换账号/重新认证时清除。 */
  revision: number
  /** 原确认的固定域。 */
  domain: OutboxDomain
  /** 原命令，重试/查询必须保留 UUID 和其余字段。 */
  command: Readonly<ReplayCommand>
  /** 未发送、结果未确认或有受理事实；不能把未知当成失败。 */
  phase: 'prepared' | 'uncertain' | 'accepted'
  /** 仅真实响应或匹配审计得到的受理回执。 */
  receipt: ReplayReceipt | null
}

/** 仅内存跨路由保留原命令；不保存密码、确认令牌、登录令牌或浏览器持久状态。 */
export const useOperationsRetryStore = defineStore('operations-retry', () => {
  const auth = useAuthStore()
  const pending = ref<PendingReplay | null>(null)
  // 同步清除，避免账号已切换但下一次 Vue 调度前仍可取到旧账号的幂等命令。
  watch(
    [() => auth.user?.id, () => auth.sessionRevision],
    () => {
      pending.value = null
    },
    { flush: 'sync' },
  )
  function prepare(domain: OutboxDomain, command: ReplayCommand) {
    if (!auth.user) throw new Error('当前账号未认证')
    if (pending.value) throw new Error('先处理原命令，不得创建第二个受理请求')
    pending.value = {
      operatorId: auth.user.id,
      revision: auth.sessionRevision,
      domain,
      command: Object.freeze({ ...command }),
      phase: 'prepared',
      receipt: null,
    }
  }
  function finish() {
    if (pending.value?.phase === 'uncertain') throw new Error('原请求结果尚未确认，不能丢弃幂等命令')
    pending.value = null
  }
  return { pending, prepare, finish }
})
