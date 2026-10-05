import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  // 本地工作台可以代理阶段 API，不需要把 HttpOnly 会话搬进 localStorage。
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080'
  return {
    plugins: [vue()],
    base: env.VITE_PUBLIC_BASE || '/',
    server: {
      port: 5173,
      proxy: { '/api': { target: apiTarget, ws: true }, '/koko-api': { target: apiTarget, ws: true } },
    },
  }
})
