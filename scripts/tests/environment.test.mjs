import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import test from 'node:test'
import { checkEnvironment } from '../check-environment.mjs'

test('真实HTTP：单域500不掩盖其他读取，报告不包含业务记录', async () => {
  const requests = []
  const server = createServer((request, response) => {
    requests.push({
      method: request.method,
      cookie: request.headers.cookie,
      authorization: request.headers.authorization,
    })
    response.setHeader('content-type', 'application/json')
    if (request.url.endsWith('/auth/me')) response.writeHead(401).end('{}')
    else if (request.url.includes('/voice/')) response.writeHead(500).end('{"message":"private-db-diagnostic"}')
    else if (/\/(creators|posts)\/page\?page=1&size=12$/.test(request.url))
      response.end(JSON.stringify({ items: [{ title: 'private-user-record' }], total: 1, page: 1, size: 12 }))
    else response.end('[]')
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  try {
    const result = await checkEnvironment(`http://127.0.0.1:${server.address().port}/api`)
    assert.equal(result.passed, false)
    assert.equal(result.results.filter((item) => item.passed).length, 5)
    assert.equal(result.results.find((item) => item.name === '语音房发现').status, 500)
    assert.doesNotMatch(JSON.stringify(result), /private-db-diagnostic|private-user-record/)
    assert.equal(requests.length, 6)
    assert.ok(requests.every((request) => request.method === 'GET' && !request.cookie && !request.authorization))
  } finally {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
})

test('200 HTML、错误JSON外形和匿名200均不能视为通过', async () => {
  const result = await checkEnvironment('http://localhost/api', {
    fetchImpl: async (url) => {
      if (url.includes('/communities'))
        return new Response('<html>fallback</html>', { headers: { 'content-type': 'text/html' } })
      return Response.json({ items: [], total: 'unknown', page: 1, size: 12 })
    },
  })
  assert.ok(result.results.every((item) => !item.passed))
  assert.equal(result.results[0].reason, 'CONTENT_TYPE')
  assert.equal(result.results[1].reason, 'RESPONSE_SHAPE')
  assert.equal(result.results.at(-1).reason, 'AUTH_BOUNDARY')
})

test('禁止凭据URL，重定向不自动跟随，错误文本不进入报告', async () => {
  let requests = 0
  await assert.rejects(checkEnvironment('http://user:secret@localhost/api', { fetchImpl: async () => requests++ }))
  assert.equal(requests, 0)
  const result = await checkEnvironment('http://localhost/api', {
    fetchImpl: async (_url, options) => {
      assert.equal(options.redirect, 'manual')
      assert.equal(options.credentials, 'omit')
      throw new Error('private-upstream-detail')
    },
  })
  assert.equal(result.passed, false)
  assert.doesNotMatch(JSON.stringify(result), /private-upstream-detail/)
})

test('超大响应有界拒绝并取消，不把裁剪内容当有效结果', async () => {
  const result = await checkEnvironment('http://localhost/api', {
    fetchImpl: async () =>
      new Response(' '.repeat(1024 * 1024 + 1), { headers: { 'content-type': 'application/json' } }),
  })
  assert.equal(result.results[0].reason, 'BODY_LIMIT')
})
