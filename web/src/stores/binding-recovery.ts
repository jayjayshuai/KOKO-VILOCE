import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from './auth'
import type { BindingReleaseDomain } from '../services/binding-releases'
import type { BindingRecoveryCommand, BindingRecoveryReceipt } from '../services/binding-recovery'
import { validateBindingRecoveryCommand } from '../services/binding-recovery'
export interface PendingBindingRecovery {
  /** 当前认证操作者。 */ operatorId: string
  /** 当前UI会话轮次。 */ revision: number
  /** 原命令固定域。 */ domain: BindingReleaseDomain
  /** 原命令，不保存密码或确认令牌。 */ command: Readonly<BindingRecoveryCommand>
  /** 未提交/结果未知/实际受理。 */ phase: 'prepared' | 'uncertain' | 'accepted'
  /** 只存匹配的受理事实。 */ receipt: BindingRecoveryReceipt | null
}
/** 只在内存跨路由保留；刷新浏览器前记录命令ID查审计，不保存任何秘密。 */
export const useBindingRecoveryStore = defineStore('binding-recovery', () => {
  const auth = useAuthStore(),
    pending = ref<PendingBindingRecovery | null>(null)
  watch(
    [() => auth.user?.id, () => auth.sessionRevision],
    () => {
      pending.value = null
    },
    { flush: 'sync' },
  )
  function prepare(domain: BindingReleaseDomain, command: BindingRecoveryCommand) {
    if (!auth.user || pending.value) throw new Error('先处理原命令，不能创建第二个请求')
    if (!['identity', 'community'].includes(domain)) throw new Error('原命令业务域无效')
    validateBindingRecoveryCommand(command)
    pending.value = {
      operatorId: auth.user.id,
      revision: auth.sessionRevision,
      domain,
      command: Object.freeze({ ...command }),
      phase: 'prepared',
      receipt: null,
    }
  }
  /** 刷新后人工重录完整原命令；没有查到事实也不允许丢弃或换ID。 */
  function restore(domain: BindingReleaseDomain, command: BindingRecoveryCommand) {
    prepare(domain, command)
    pending.value!.phase = 'uncertain'
  }
  function finish() {
    if (pending.value?.phase === 'uncertain') throw new Error('原命令结果未知，不能丢弃幂等键')
    pending.value = null
  }
  return { pending, prepare, restore, finish }
})
