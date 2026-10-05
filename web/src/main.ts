import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './styles.css'
import './workspace.css'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { observeSession } from './services/http'

const application = createApp(App)
const pinia = createPinia()
application.use(pinia).use(router)
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
