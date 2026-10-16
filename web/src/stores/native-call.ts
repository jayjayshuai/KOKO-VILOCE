import { defineStore } from 'pinia'
import type { Conversation } from '../services/chat'

/** 页面只申请打开原生通话入口；API/设备及计时器归常驻组件，不随ChatPanel卸载。 */
export const useNativeCallStore = defineStore('native-call', {
  state: () => ({
    /** 服务端能力核对结果，默认不能呼叫。 */ enabled: false,
    /** 防旧账号迟到点击复用对话框。 */ userId: '',
    /** 当前认证轮次。 */ sessionRevision: -1,
    /** 群选择所需的当前聊天快照，仅内存。 */ draft: null as Conversation | null,
    /** 是否已有本人全局来电/通话或不确定写入。 */ occupied: false,
  }),
  actions: {
    open(conversation: Conversation, userId: string, sessionRevision: number) {
      if (!this.enabled || this.occupied || this.userId !== userId || this.sessionRevision !== sessionRevision) return
      this.draft = { ...conversation, members: conversation.members.map((member) => ({ ...member })) }
    },
    reset(userId = '', sessionRevision = -1) {
      this.enabled = false
      this.occupied = false
      this.draft = null
      this.userId = userId
      this.sessionRevision = sessionRevision
    },
  },
})
