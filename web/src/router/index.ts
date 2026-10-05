import { createRouter, createWebHashHistory } from 'vue-router'
import { workspacePages } from './pages'
import DiscoveryView from '../views/DiscoveryView.vue'

/** 页面生命周期由 RouterView 管理；消息/通知离开路由后真正卸载。 */
const views = {
  studio: () => import('../views/StudioView.vue'),
  assets: () => import('../views/AssetWorkspaceView.vue'),
  messages: () => import('../components/ChatPanel.vue'),
  notifications: () => import('../views/NotificationCenterView.vue'),
  account: () => import('../views/AccountView.vue'),
  operations: () => import('../views/OperationsView.vue'),
  'binding-operations': () => import('../views/BindingReleaseOperationsView.vue'),
}
export const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/explore' },
    ...workspacePages.map((page) => ({
      path: page.path,
      name: page.name,
      component: page.section ? DiscoveryView : views[page.name as keyof typeof views],
      meta: { page },
    })),
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.afterEach((route) => {
  const page = workspacePages.find((item) => item.name === route.name)
  document.title = `${page?.title ?? '页面不存在'} · KOKO Nexus`
})
