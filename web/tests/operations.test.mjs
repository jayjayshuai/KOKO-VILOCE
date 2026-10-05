import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { webcrypto } from 'node:crypto'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import * as pinia from 'pinia'

// 执行生产 TS 和真实 Vue/Pinia。网络桩只验证客户端状态，不代替 SQL、Dubbo 或浏览器验收。
async function execute(relative, imports = {}) {
  const source = await readFile(new URL(`../src/${relative}`, import.meta.url), 'utf8')
  const context = vm.createContext({ AbortController, Error, URLSearchParams, crypto: webcrypto })
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = new vm.SourceTextModule(compiled, { context })
  await module.link((specifier) => {
    const values = imports[specifier]
    if (!values) throw new Error(`Unconfigured import ${specifier}`)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}
class ApiRequestError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}
const eventId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const reason = '人工排查根因后确认重放本事件'
const event = (id = eventId, status = 'DEAD') => ({
  id,
  status,
  replayGeneration: 2,
  recipientId: '9007199254740993',
  totalAttempts: '9007199254740995',
})
const access = (permissions) => ({ enabled: true, roles: [], permissions })
const fullAccess = () => access(['notification:outbox:read', 'notification:outbox:replay'])
const page = (items) => ({ items, nextCursor: null })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function workspace(overrides = {}, secure = true, existing = null) {
  const auth = existing?.auth ?? vue.reactive({ user: { id: '10' }, sessionRevision: 1 })
  const owner = existing?.owner ?? pinia.createPinia()
  pinia.setActivePinia(owner)
  const retry =
    existing?.retry ??
    (
      await execute('stores/operations-retry.ts', {
        pinia,
        vue,
        './auth': { useAuthStore: () => auth },
      })
    ).useOperationsRetryStore()
  const hooks = [],
    calls = []
  const network = {
    access: fullAccess,
    dead: () => page([event()]),
    detail: () => event(),
    audits: () => ({ items: [], nextGeneration: null }),
    confirm: () => ({ confirmationToken: 'a'.repeat(43) }),
    replay: (_domain, command) => ({
      requestId: command.requestId,
      eventId: command.eventId,
      generation: command.expectedGeneration + 1,
      acceptedAt: '2026-10-03T14:00:00.123456',
    }),
    receipt: () => {
      throw new Error('receipt not configured')
    },
    ...overrides,
  }
  const api = Object.fromEntries(
    Object.entries(network).map(([name, action]) => [
      name,
      async (...args) => {
        calls.push({ name, args })
        return action(...args)
      },
    ]),
  )
  const module = await execute('composables/outbox-workspace.ts', {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../services/http': { ApiRequestError },
  })
  const state = module.useOutboxWorkspace(api, retry, secure)
  return {
    state,
    calls,
    retry,
    auth,
    owner,
    dispose: () => hooks.forEach((callback) => callback()),
    close() {
      this.dispose()
      pinia.disposePinia(owner)
    },
  }
}
async function prepared(overrides = {}, secure = true) {
  const h = await workspace(overrides, secure)
  await h.state.loadAccess()
  await h.state.select(eventId)
  h.state.reason.value = reason
  h.state.prepare()
  return h
}
const count = (h, name) => h.calls.filter((call) => call.name === name).length
async function until(predicate) {
  // 观察请求已真实进入网络桩的边界，而非假定两次 microtask 足以跨越 VM Promise 链。
  for (let turn = 0; turn < 20 && !predicate(); turn++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true, '预期异步边界未到达')
}
function audit(command, overrides = {}) {
  return {
    operatorId: '10',
    eventId: command.eventId,
    requestId: command.requestId,
    expectedGeneration: command.expectedGeneration,
    acceptedGeneration: command.expectedGeneration + 1,
    reason: command.reason,
    createdAt: '2026-10-03T14:00:00.123456',
    ...overrides,
  }
}

test('权限关闭或缺少读权限不查询事件，也不创建默认管理员', async () => {
  for (const current of [{ ...fullAccess(), enabled: false }, access([])]) {
    const h = await workspace({ access: () => current })
    try {
      await h.state.loadAccess()
      await h.state.load()
      await h.state.select(eventId)
      assert.equal(h.state.canRead.value, false)
      assert.equal(count(h, 'dead'), 0)
      assert.equal(count(h, 'detail'), 0)
      assert.equal(h.retry.pending, null)
    } finally {
      h.close()
    }
  }
})

test('审计接口每页五条，exclusive 代次和 no-store 传给真实请求适配器', async () => {
  const requests = []
  const { operationsApi } = await execute('services/operations.ts', {
    './http': {
      request: async (path, options) => {
        requests.push({ path, options })
        return {}
      },
    },
  })
  const controller = new AbortController()
  await operationsApi.audits('identity', eventId, null, controller.signal)
  await operationsApi.audits('identity', eventId, 6, controller.signal)
  assert.equal(requests[0].path, `/operations/outbox/identity/events/${eventId}/audits?limit=5`)
  assert.equal(requests[1].path, `/operations/outbox/identity/events/${eventId}/audits?limit=5&beforeGeneration=6`)
  for (const call of requests) {
    assert.equal(call.options.cache, 'no-store')
    assert.equal(call.options.signal, controller.signal)
  }
})

test('追加审计分页故障保留当前五条和游标，重试接续且不混入重复代次', async () => {
  let fail = true
  const items = (accepted) => ({ requestId: `audit-${accepted}`, acceptedGeneration: accepted })
  const h = await workspace({
    audits: (_domain, _event, before) => {
      if (before === null) return { items: [10, 9, 8, 7, 6].map(items), nextGeneration: 6 }
      assert.equal(before, 6)
      if (fail) throw new ApiRequestError('审计服务暂时不可用', 503)
      return { items: [5, 4, 3, 2, 1].map(items), nextGeneration: null }
    },
  })
  try {
    await h.state.loadAccess()
    await h.state.select(eventId)
    await h.state.loadAudits(false)
    assert.equal(h.state.audits.value.length, 5)
    assert.equal(h.state.auditCursor.value, 6)
    assert.match(h.state.detailError.value, /审计读取失败/)
    fail = false
    await h.state.loadAudits(false)
    assert.equal(h.state.auditCursor.value, null)
    assert.equal(h.state.detailError.value, '')
    assert.equal(h.state.audits.value.map((item) => item.acceptedGeneration).join(','), '10,9,8,7,6,5,4,3,2,1')
  } finally {
    h.close()
  }
})
test('404 能力未启用与 503 服务故障都不冒充成功空态', async () => {
  for (const status of [404, 503]) {
    const h = await workspace({ access: () => Promise.reject(new ApiRequestError('offline', status)) })
    try {
      await h.state.loadAccess()
      assert.equal(h.state.loaded.value, false)
      assert.match(h.state.accessError.value, status === 404 ? /尚未启用/ : /offline/)
      assert.equal(count(h, 'dead'), 0)
    } finally {
      h.close()
    }
  }
})
test('只读权限可查 DEAD 但不能准备重放或提交密码', async () => {
  const h = await prepared({ access: () => access(['notification:outbox:read']) })
  try {
    assert.equal(h.state.rows.value.length, 1)
    assert.equal(h.retry.pending, null)
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    assert.equal(count(h, 'confirm'), 0)
    assert.equal(count(h, 'replay'), 0)
  } finally {
    h.close()
  }
})
test('非 HTTPS 公网环境不创建命令也不提交敏感确认', async () => {
  const h = await prepared({}, false)
  try {
    assert.equal(h.retry.pending, null)
    assert.match(h.state.actionError.value, /TLS/)
    h.retry.prepare('identity', {
      requestId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      eventId,
      expectedGeneration: 2,
      reason,
    })
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    assert.equal(h.state.password.value, '')
    assert.equal(count(h, 'confirm'), 0)
  } finally {
    h.close()
  }
})
test('密码 403 不发重放；重新读取权限后同一登录与原命令保留', async () => {
  const h = await prepared({ confirm: () => Promise.reject(new ApiRequestError('wrong password', 403)) })
  try {
    const original = h.retry.pending.command.requestId
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    assert.equal(count(h, 'replay'), 0)
    assert.equal(count(h, 'access'), 2)
    assert.equal(h.auth.user.id, '10')
    assert.equal(h.retry.pending.command.requestId, original)
    assert.equal(h.retry.pending.phase, 'prepared')
    assert.equal(h.state.password.value, '')
    assert.match(h.state.actionError.value, /wrong password/)
  } finally {
    h.close()
  }
})
test('超时只重试原 UUID/事件/代次/原因，不存密码或确认秘密', async () => {
  let first = true
  const h = await prepared({
    replay: (_domain, command) => {
      if (first) {
        first = false
        throw new ApiRequestError('timeout may commit', 0)
      }
      return { requestId: command.requestId, eventId: command.eventId, generation: 3, acceptedAt: 'db-time' }
    },
  })
  try {
    const original = JSON.stringify(h.retry.pending.command)
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    assert.equal(h.retry.pending.phase, 'uncertain')
    h.state.finish()
    assert.equal(JSON.stringify(h.retry.pending.command), original)
    assert.throws(() => h.retry.finish(), /尚未确认/)
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    const writes = h.calls.filter((call) => call.name === 'replay')
    assert.equal(writes.length, 2)
    assert.equal(JSON.stringify(writes[0].args[1]), JSON.stringify(writes[1].args[1]))
    assert.equal(h.retry.pending.phase, 'accepted')
    assert.match(h.state.notice.value, /不代表/)
    assert.equal(JSON.stringify(h.retry.pending).includes('fixture-password'), false)
    assert.equal(JSON.stringify(h.retry.pending).includes('a'.repeat(43)), false)
  } finally {
    h.close()
  }
})
test('原请求查询 404 只表示未观察到，不能丢弃不确定命令', async () => {
  const h = await prepared({
    replay: () => Promise.reject(new Error('connection lost')),
    receipt: () => Promise.reject(new ApiRequestError('not found', 404)),
  })
  try {
    h.state.password.value = 'fixture-password'
    await h.state.confirmAndReplay()
    const original = h.retry.pending.command.requestId
    await h.state.reconcile()
    h.state.finish()
    assert.equal(h.retry.pending.command.requestId, original)
    assert.equal(h.retry.pending.phase, 'uncertain')
    assert.match(h.state.actionError.value, /在途请求仍可能提交/)
    assert.equal(count(h, 'replay'), 1)
  } finally {
    h.close()
  }
})
test('匹配原命令的受理审计可消除未知，但不表示已送达', async () => {
  const h = await prepared({ receipt: () => audit(h.retry.pending.command) })
  try {
    h.retry.pending.phase = 'uncertain'
    await h.state.reconcile()
    assert.equal(h.retry.pending.phase, 'accepted')
    assert.equal(h.retry.pending.receipt.generation, 3)
    assert.equal(h.retry.pending.receipt.acceptedAt, '2026-10-03T14:00:00.123456')
    assert.match(h.state.notice.value, /不代表/)
    assert.equal(count(h, 'replay'), 0)
  } finally {
    h.close()
  }
})
test('受理审计任一绑定字段或新代次不匹配都不能标记成功', async () => {
  for (const mismatch of [
    { operatorId: '99' },
    { eventId: 'other' },
    { requestId: 'other' },
    { expectedGeneration: 1 },
    { acceptedGeneration: 7 },
    { reason: 'changed reason' },
  ]) {
    const h = await prepared({ receipt: () => audit(h.retry.pending.command, mismatch) })
    try {
      h.retry.pending.phase = 'uncertain'
      await h.state.reconcile()
      assert.equal(h.retry.pending.phase, 'uncertain')
      assert.equal(h.retry.pending.receipt, null)
      assert.match(h.state.actionError.value, /不匹配/)
    } finally {
      h.close()
    }
  }
})
test('损坏的确认响应不发送写入；损坏的受理响应保留未知状态', async () => {
  const invalidProof = await prepared({ confirm: () => ({ confirmationToken: 'invalid' }) })
  try {
    invalidProof.state.password.value = 'fixture-password'
    await invalidProof.state.confirmAndReplay()
    assert.equal(count(invalidProof, 'replay'), 0)
    assert.equal(invalidProof.retry.pending.phase, 'prepared')
  } finally {
    invalidProof.close()
  }
  const invalidReceipt = await prepared({ replay: () => ({ requestId: 'other', eventId, generation: 3 }) })
  try {
    invalidReceipt.state.password.value = 'fixture-password'
    await invalidReceipt.state.confirmAndReplay()
    assert.equal(invalidReceipt.retry.pending.phase, 'uncertain')
    assert.equal(invalidReceipt.retry.pending.receipt, null)
  } finally {
    invalidReceipt.close()
  }
})
test('重复确认受 busy 保护，账户切换同步清除原命令', async () => {
  const wait = deferred(),
    h = await prepared({ confirm: () => wait.promise })
  try {
    h.state.password.value = 'fixture-password'
    const sending = h.state.confirmAndReplay()
    h.state.password.value = 'second-password'
    await h.state.confirmAndReplay()
    assert.equal(count(h, 'confirm'), 1)
    h.auth.user = { id: '20' }
    assert.equal(h.retry.pending, null)
    wait.resolve({ confirmationToken: 'a'.repeat(43) })
    await sending
    assert.equal(count(h, 'replay'), 0)
    assert.equal(h.state.password.value, '')
  } finally {
    h.close()
  }
})
test('会话轮次变化同步清除命令；不能在匿名状态准备', async () => {
  const h = await prepared()
  try {
    h.auth.sessionRevision++
    assert.equal(h.retry.pending, null)
    h.auth.user = null
    assert.throws(() => h.retry.prepare('identity', { eventId }), /未认证/)
  } finally {
    h.close()
  }
})
test('同账号同会话的身份投影刷新不丢弃原幂等命令', async () => {
  const h = await prepared()
  try {
    const original = h.retry.pending.command.requestId
    h.auth.user = { id: '10', displayName: 'updated projection' }
    assert.equal(h.retry.pending.command.requestId, original)
  } finally {
    h.close()
  }
})
test('跨路由保留同账号命令，卸载后的写回执不得更改重试状态', async () => {
  const wait = deferred(),
    h = await prepared({ replay: () => wait.promise })
  try {
    h.state.password.value = 'fixture-password'
    const sending = h.state.confirmAndReplay()
    await until(() => count(h, 'replay') === 1)
    assert.equal(h.retry.pending.phase, 'uncertain')
    const original = h.retry.pending.command.requestId
    h.dispose()
    const next = await workspace({}, true, h)
    try {
      assert.equal(next.retry.pending.command.requestId, original)
      wait.resolve({ requestId: original, eventId, generation: 3, acceptedAt: 'late' })
      await sending
      assert.equal(next.retry.pending.phase, 'uncertain')
      assert.equal(h.state.rows.value.length, 0)
    } finally {
      next.dispose()
    }
  } finally {
    h.close()
  }
})
test('切域取消旧查询，迟到 DEAD 页不能写回新域', async () => {
  const wait = deferred(),
    h = await workspace({ dead: (domain) => (domain === 'identity' ? wait.promise : page([event('live-event')])) })
  try {
    const old = h.state.loadAccess()
    await until(() => count(h, 'dead') === 1)
    await h.state.chooseDomain('live')
    assert.equal(h.calls.find((call) => call.name === 'dead').args[2].aborted, true)
    wait.resolve(page([event('stale')]))
    await old
    assert.equal(h.state.rows.value[0].id, 'live-event')
    assert.equal(h.state.loading.value, false)
  } finally {
    h.close()
  }
})
test('刷新权限清除旧审计加载标志，旧审计迟到不得重新出现', async () => {
  const wait = deferred(),
    h = await workspace({ audits: () => wait.promise })
  try {
    await h.state.loadAccess()
    const old = h.state.select(eventId)
    await until(() => count(h, 'audits') === 1)
    assert.equal(h.state.auditLoading.value, true)
    await h.state.loadAccess()
    assert.equal(h.state.auditLoading.value, false)
    wait.resolve({ items: [{ requestId: 'stale' }], nextGeneration: 1 })
    await old
    assert.equal(h.state.audits.value.length, 0)
    assert.equal(h.state.auditCursor.value, null)
  } finally {
    h.close()
  }
})
test('查询 403 清除事件摘要；分页失败保留旧页但显示失败', async () => {
  let fail = false
  const h = await workspace({
    dead: () => (fail ? Promise.reject(new Error('database offline')) : page([event()])),
    detail: () => Promise.reject(new ApiRequestError('revoked', 403)),
  })
  try {
    await h.state.loadAccess()
    fail = true
    await h.state.load()
    assert.equal(h.state.rows.value.length, 1)
    assert.match(h.state.error.value, /offline/)
    await h.state.select(eventId)
    assert.equal(h.state.rows.value.length, 0)
    assert.equal(h.state.canRead.value, false)
    assert.equal(h.state.selected.value, null)
  } finally {
    h.close()
  }
})
test('运营 API 保留原微秒游标、ID 字符串、固定域与 no-store', async () => {
  const calls = []
  const { operationsApi } = await execute('services/operations.ts', {
    './http': {
      request: async (path, options) => {
        calls.push({ path, options })
        return {}
      },
    },
  })
  await operationsApi.dead('identity', { createdAt: '2026-10-03T14:00:00.123456', eventId })
  const url = new URL(`http://test.invalid${calls[0].path}`)
  assert.equal(url.searchParams.get('beforeCreatedAt'), '2026-10-03T14:00:00.123456')
  assert.equal(url.searchParams.get('beforeEventId'), eventId)
  const command = { requestId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', eventId, expectedGeneration: 2, reason }
  await operationsApi.replay('live', command, 'a'.repeat(43))
  assert.equal(calls[1].path, '/operations/outbox/live/replays')
  assert.deepEqual(JSON.parse(calls[1].options.body).command, command)
  assert.equal(JSON.parse(calls[1].options.body).operatorId, undefined)
  for (const call of calls) assert.equal(call.options.cache, 'no-store')
  assert.throws(() => operationsApi.detail('arbitrary-service', eventId), /业务域无效/)
  assert.equal(calls.length, 2)
})
