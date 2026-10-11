import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './styles.css'
import './workspace.css'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { observeSession } from './services/http'
import NavigationRecoveryBanner from './components/NavigationRecoveryBanner.vue'
import { installNavigationRecovery } from './services/navigation-recovery'

const application = createApp(App)
const pinia = createPinia()
application.use(pinia).use(router)
const stopNavigationRecovery = installNavigationRecovery(router)
// 独立于业务应用壳挂载：懒加载路由失败时恢复提示仍可见，不触碰通话/编辑器。
const recoveryRoot = document.createElement('div')
document.body.appendChild(recoveryRoot)
const recoveryApplication = createApp(NavigationRecoveryBanner)
recoveryApplication.mount(recoveryRoot)
application.onUnmount(() => {
  stopNavigationRecovery()
  recoveryApplication.unmount()
  recoveryRoot.remove()
})
const auth = useAuthStore(pinia)
const stopObserving = observeSession({
  capture: () => ({ userId: auth.user?.id, revision: auth.sessionRevision }),
  unauthorized: (snapshot) => {
    const session = snapshot as { userId: string | undefined; revision: number }
    auth.expireSession(session.userId, session.revision)
  },
})
application.onUnmount(stopObserving)
application.mount('#app')
