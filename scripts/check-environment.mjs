import { pathToFileURL } from 'node:url'

/** 只读取公开接口，不携带登录凭据、不注册账号、不修改现有数据。 */
export const probes = [
  { name: '社区发现', path: '/discovery/communities', shape: 'list' },
  { name: '创作者发现', path: '/discovery/creators/page?page=1&size=12', shape: 'page' },
  { name: '动态发现', path: '/discovery/posts/page?page=1&size=12', shape: 'page' },
  { name: '直播发现', path: '/live/discovery', shape: 'list' },
  { name: '语音房发现', path: '/voice/rooms/discovery', shape: 'list' },
  { name: '匿名身份边界', path: '/auth/me', shape: 'unauthorized' },
]

/** 禁止URL中的秘密和隐式重定向；运行报告不输出响应正文、目标主机或用户数据。 */
function endpoint(value) {
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash)
    throw new Error('API地址必须是无凭据、查询参数和片段的HTTP(S)地址')
  return url.href.replace(/\/$/, '')
}

/** 检查协议外形，避免反向代理返回200 HTML时误判成业务可用。 */
function validBody(body, shape) {
  if (shape === 'list') return Array.isArray(body)
  return (
    body !== null &&
    typeof body === 'object' &&
    Array.isArray(body.items) &&
    Number.isSafeInteger(body.total) &&
    body.total >= 0 &&
    Number.isSafeInteger(body.page) &&
    body.page >= 1 &&
    Number.isSafeInteger(body.size) &&
    body.size >= 1
  )
}

/** 每个领域独立出结果；失败不妨碍检查其余领域，响应大小与等待都有上限。 */
export async function checkEnvironment(base, { timeoutMs = 15000, fetchImpl = fetch } = {}) {
  const api = endpoint(base)
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000)
    throw new Error('超时范围必须为1至30000毫秒')
  const results = await Promise.all(
    probes.map(async (probe) => {
      let status = null
      try {
        const response = await fetchImpl(api + probe.path, {
          method: 'GET',
          redirect: 'manual',
          credentials: 'omit',
          headers: { accept: 'application/json' },
          signal: AbortSignal.timeout(timeoutMs),
        })
        status = response.status
        if (probe.shape === 'unauthorized') {
          await response.body?.cancel()
          return { name: probe.name, status, passed: status === 401, reason: status === 401 ? 'OK' : 'AUTH_BOUNDARY' }
        }
        if (status !== 200) {
          await response.body?.cancel()
          return { name: probe.name, status, passed: false, reason: 'HTTP_STATUS' }
        }
        if (!response.headers.get('content-type')?.toLowerCase().includes('application/json')) {
          await response.body?.cancel()
          return { name: probe.name, status, passed: false, reason: 'CONTENT_TYPE' }
        }
        const reader = response.body?.getReader()
        if (!reader) return { name: probe.name, status, passed: false, reason: 'EMPTY_BODY' }
        const chunks = []
        let length = 0
        try {
          for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            length += value.length
            if (length > 1024 * 1024) {
              await reader.cancel()
              return { name: probe.name, status, passed: false, reason: 'BODY_LIMIT' }
            }
            chunks.push(value)
          }
        } finally {
          reader.releaseLock()
        }
        const passed = validBody(JSON.parse(Buffer.concat(chunks).toString('utf8')), probe.shape)
        return { name: probe.name, status, passed, reason: passed ? 'OK' : 'RESPONSE_SHAPE' }
      } catch {
        // 依赖错误可能含地址或响应片段，报告仅保留固定原因，不打印异常message。
        return { name: probe.name, status, passed: false, reason: 'UNAVAILABLE_OR_INVALID_JSON' }
      }
    }),
  )
  return {
    checkedAt: new Date().toISOString(),
    readOnly: true,
    passed: results.every((result) => result.passed),
    scope: '仅公开读取和匿名身份边界；不证明登录、写入、WebSocket、RTC或生产就绪',
    results,
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 4 || process.argv[2] !== '--api-base')
      throw new Error('用法：npm run check:environment -- --api-base http://127.0.0.1:42880/api')
    const result = await checkEnvironment(process.argv[3])
    console.log(JSON.stringify(result, null, 2))
    if (!result.passed) process.exitCode = 1
  } catch (cause) {
    // 参数错误来自上面固定校验；URL解析错误不回显原始输入。
    console.error(cause instanceof TypeError ? 'API地址无效' : cause.message)
    process.exitCode = 2
  }
}
