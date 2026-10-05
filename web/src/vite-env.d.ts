/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 同源公开 API 前缀，生产使用 /koko-api；不包含凭据。 */
  readonly VITE_API_BASE?: string
}
