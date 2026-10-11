import { defineStore } from 'pinia'
import { markRaw } from 'vue'

/** 当前真实媒体投影，不保存JWT、Cookie、SDK实例或持久化设备状态。 */
export interface VoiceCallStatus {
  /** SDK阶段，不以SQL成员身份推断在线。 */ phase: 'idle' | 'joining' | 'connected' | 'reconnecting'
  /** SDK确认本机麦克风状态。 */ microphoneEnabled: boolean
  /** 设备操作互斥。 */ microphoneBusy: boolean
  /** 当前授权允许用户明确切换设备。 */ canToggleMicrophone: boolean
  /** SDK当前成员数。 */ participantCount: number
}
/** 组件事件作用域，旧缓存实例的迟到状态不能覆盖新房间。 */
export interface ScopedVoiceCallStatus extends VoiceCallStatus {
  /** 原房间，不是凭据。 */ roomId: string
  /** 原账号。 */ userId: string
  /** 原登录轮次。 */ sessionRevision: number
}
export interface VoiceCallControls {
  /** 明确一键发言，不在后台自动索取设备。 */ startSpeaking(): Promise<void>
  /** 明确用户连接，不自动开设备。 */ connect(): Promise<void>
  /** 明确设备切换，仍核验当前权限。 */ toggleMicrophone(): Promise<void>
  /** 本机释放，不假装服务端已离房。 */ disconnect(): Promise<void>
  /** 停止本机声音并尝试提交离房，结果未知仍需核对。 */ leave(): Promise<void>
}
interface VoiceCallRegistration {
  /** 实例所有权，旧缓存组件不能清除新通话。 */ registration: number
  /** 当前房间，不是凭据。 */ roomId: string
  /** 当前账号。 */
  userId: string
  /** 当前登录轮次。 */
  sessionRevision: number
  /** 展示名称。 */ title: string
  /** 实际组件确认的媒体状态。 */ status: VoiceCallStatus
  /** 仅当前组件控制入口，不持久化。 */ controls: VoiceCallControls
}
/** 常驻通话条只代理缓存房间的同一条连接，不创建第二条SDK。 */
export const useVoiceCallStore = defineStore('voice-call', {
  state: () => ({
    /** 每次组件/房间认领递增，不复用旧入口。 */ sequence: 0,
    /** 当前通话投影，整个文档生命周期内存有效。 */ call: null as VoiceCallRegistration | null,
  }),
  actions: {
    attach(
      scope: { roomId: string; userId: string; sessionRevision: number; title: string },
      controls: VoiceCallControls,
    ) {
      const registration = ++this.sequence
      this.call = {
        ...scope,
        registration,
        controls: markRaw(controls),
        status: {
          phase: 'idle',
          microphoneEnabled: false,
          microphoneBusy: false,
          canToggleMicrophone: false,
          participantCount: 0,
        },
      }
      return registration
    },
    update(registration: number, status: VoiceCallStatus) {
      if (this.call?.registration === registration)
        this.call.status = {
          phase: status.phase,
          microphoneEnabled: status.microphoneEnabled,
          microphoneBusy: status.microphoneBusy,
          canToggleMicrophone: status.canToggleMicrophone,
          participantCount: status.participantCount,
        }
    },
    clear(registration: number) {
      if (this.call?.registration === registration) this.call = null
    },
  },
})
