import { defineStore } from 'pinia'
import { ApiRequestError, api, type UserIdentity } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    /** 已认证的用户投影；匿名或注销后为空，不保存会话令牌。 */
    user: null as UserIdentity | null,
    /** 是否完成首次会话恢复，用于避免页面提前误判为匿名。 */
    initialized: false,
    /** 非 401 的会话恢复故障，不冒充确认的匿名会话。 */
    sessionError: '',
    /** 当前轮次是否已收到确定的业务 401，区别于网络故障。 */
    sessionExpired: false,
    /** 会话操作轮次；旧恢复请求不能覆盖随后登录/注销的结果。 */
    sessionRevision: 0,
  }),
  actions: {
    /** 只接受当前身份/轮次的服务端 401；网络错误与旧请求不得清除会话。 */
    expireSession(userId: string | undefined, revision: number) {
      if (!userId || this.user?.id !== userId || this.sessionRevision !== revision) return
      this.sessionRevision++
      this.user = null
      this.initialized = true
      this.sessionExpired = true
      this.sessionError = '会话已失效，请重新登录。'
    },
    async restore() {
      const revision = ++this.sessionRevision
      this.initialized = false
      this.sessionError = ''
      this.sessionExpired = false
      try {
        const user = await api.me()
        if (revision === this.sessionRevision) this.user = user
      } catch (cause) {
        if (revision !== this.sessionRevision) return
        this.user = null
        if (!(cause instanceof ApiRequestError && cause.status === 401)) {
          this.sessionError = cause instanceof Error ? cause.message : '会话恢复失败，请重新读取。'
        }
      } finally {
        if (revision === this.sessionRevision) this.initialized = true
      }
    },
    async login(email: string, password: string) {
      const revision = ++this.sessionRevision
      try {
        const result = await api.login(email, password)
        if (revision !== this.sessionRevision) return
        this.user = result.user
        this.sessionError = ''
        this.sessionExpired = false
      } finally {
        if (revision === this.sessionRevision) this.initialized = true
      }
    },
    async register(email: string, password: string, handle: string, displayName: string) {
      const revision = ++this.sessionRevision
      try {
        const result = await api.register(email, password, handle, displayName)
        if (revision !== this.sessionRevision) return
        this.user = result.user
        this.sessionError = ''
        this.sessionExpired = false
      } finally {
        if (revision === this.sessionRevision) this.initialized = true
      }
    },
    async logout() {
      const revision = ++this.sessionRevision
      try {
        await api.logout()
        if (revision !== this.sessionRevision) return
        this.user = null
        this.sessionError = ''
        this.sessionExpired = false
      } finally {
        if (revision === this.sessionRevision) this.initialized = true
      }
    },
  },
})
