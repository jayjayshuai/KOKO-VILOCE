import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

// 执行生产适配器/状态和真实 Vue，网络桩不代表 SQL、Dubbo 或浏览器验收。
async function execute(relative, imports) {
  const source = await readFile(new URL(`../src/${relative}`, import.meta.url), 'utf8')
  const context = vm.createContext({ AbortController, Error, URLSearchParams })
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = new vm.SourceTextModule(output, { context })
  await module.link(
    (specifier) =>
      new vm.SyntheticModule(
        Object.keys(imports[specifier]),
        function () {
          for (const [key, value] of Object.entries(imports[specifier])) this.setExport(key, value)
        },
        { context },
      ),
  )
  await module.evaluate()
  return module.namespace
}
class ApiRequestError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}
const id = (n) => `81000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const time = '2026-10-04T12:00:00.123456'
const row = (n = 1, status = 'DEAD') => ({
  requestId: id(n),
  assetId: id(99),
  purpose: 'AVATAR',
  status,
  attempts: 10,
  replayGeneration: 0,
  generationAttempts: 10,
  lastFailure: 'lease-exhausted',
  createdAt: time,
  updatedAt: time,
  nextAttemptAt: time,
})
const page = (items, nextCursor = null) => ({ items, nextCursor })
const snapshot = () => ({
  pending: 1001,
  leased: 0,
  dead: 1,
  sampleLimit: 1001,
  oldestPendingAgeSeconds: 3,
  oldestDeadAgeSeconds: 4,
  observedAt: time,
})
const access = (permissions) => ({ enabled: true, roles: [], permissions })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let i = 0; i < 20 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true, '预期请求边界尚未到达')
}
async function workspace(overrides = {}, authority = () => access(['asset:binding:read'])) {
  const calls = [],
    hooks = []
  const session = vue.reactive({ user: { id: '10' }, sessionRevision: 1 })
  const network = {
    dead: () => page([row()]),
    detail: (_domain, request) => ({ ...row(), requestId: request }),
    snapshot,
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
  const module = await execute('composables/binding-release-workspace.ts', {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../services/http': { ApiRequestError },
  })
  const state = module.useBindingReleaseWorkspace(api, authority, session)
  return { state, calls, session, close: () => hooks.forEach((callback) => callback()) }
}
async function adapter(value, calls = []) {
  const module = await execute('services/binding-releases.ts', {
    './http': {
      request: async (...args) => {
        calls.push(args)
        return value
      },
    },
  })
  return module.bindingReleaseApi
}
test('真实请求适配保留微秒断点、no-store、signal，拒绝任意域和无效原请求', async () => {
  const calls = [],
    api = await adapter(page([row()]), calls),
    controller = new AbortController()
  await api.dead('identity', { createdAt: time, requestId: id(5) }, controller.signal)
  const url = new URL(`http://test.invalid${calls[0][0]}`)
  assert.equal(url.searchParams.get('beforeCreatedAt'), time)
  assert.equal(url.searchParams.get('beforeRequestId'), id(5))
  assert.equal(calls[0][1].cache, 'no-store')
  assert.equal(calls[0][1].signal, controller.signal)
  await assert.rejects(api.dead('live', null), /业务域/)
  await assert.rejects(api.detail('identity', 'arbitrary/path'), /标准 UUID/)
  assert.equal(calls.length, 1)
})
test('损坏任务、重复行、非 DEAD 和不匹配的游标不冒充成功页', async () => {
  for (const value of [
    null,
    {},
    page([{}]),
    page([row(), row()]),
    page([row(1, 'SENT')]),
    page([{ ...row(), attempts: 11 }]),
    page([{ ...row(), lastFailure: 'private-sql' }]),
    page([{ ...row(), createdAt: `${time}Z` }]),
    page([row()], { createdAt: time, requestId: id(8) }),
    page(Array.from({ length: 21 }, (_, n) => row(n))),
  ]) {
    const api = await adapter(value)
    await assert.rejects(api.dead('community', null), /响应/)
  }
  const api = await adapter(
    page(
      Array.from({ length: 20 }, (_, n) => row(n)),
      { createdAt: time, requestId: id(19) },
    ),
  )
  assert.equal((await api.dead('identity', null)).nextCursor.createdAt, time)
})
test('错误采样和详情目标不匹配被拒绝；有界成功不是精确总量', async () => {
  for (const value of [
    {},
    { ...snapshot(), pending: 1002 },
    { ...snapshot(), sampleLimit: 1000 },
    { ...snapshot(), dead: -1 },
    { ...snapshot(), observedAt: null },
    { ...snapshot(), observedAt: '2026-02-29T12:00:00.123456' },
    { ...snapshot(), observedAt: '2026-10-04T25:00:00' },
    { ...snapshot(), observedAt: '2026-13-04T12:00:00' },
  ]) {
    await assert.rejects((await adapter(value)).snapshot('identity'), /采样响应/)
  }
  assert.equal((await (await adapter(snapshot())).snapshot('identity')).pending, 1001)
  assert.equal(
    (await (await adapter({ ...snapshot(), observedAt: '2028-02-29T12:00:00.123456' })).snapshot('identity'))
      .observedAt,
    '2028-02-29T12:00:00.123456',
  )
  await assert.rejects((await adapter(row(2))).detail('identity', id(1)), /原请求不匹配/)
})
test('损坏权限事实不存入页面状态，不按客户端角色猜测授权', async () => {
  for (const value of [
    null,
    {},
    { enabled: true, roles: [], permissions: 'asset:binding:read' },
    { enabled: true, roles: ['ASSET_BINDING_AUDITOR'], permissions: [1] },
  ]) {
    const h = await workspace({}, () => value)
    await h.state.loadAccess()
    assert.equal(h.state.canRead.value, false)
    assert.equal(h.state.access.value, null)
    assert.match(h.state.accessError.value, /权限响应不完整/)
    assert.equal(h.calls.length, 0)
    h.close()
  }
})
test('开关关闭或只有通知读权限不发送任何绑定查询', async () => {
  for (const fact of [{ ...access(['asset:binding:read']), enabled: false }, access(['notification:outbox:read'])]) {
    const h = await workspace({}, () => fact)
    await h.state.loadAccess()
    await h.state.load()
    await h.state.sample()
    await h.state.select(id(1))
    assert.equal(h.state.canRead.value, false)
    assert.equal(h.calls.length, 0)
    h.close()
  }
})
test('真实空页与功能未启用、故障分别呈现，采样故障清空旧值而不是零', async () => {
  let unavailable = false
  const h = await workspace({
    dead: () => page([]),
    snapshot: () => {
      if (unavailable) throw new ApiRequestError('暂不可用', 503)
      return snapshot()
    },
  })
  await h.state.loadAccess()
  assert.equal(h.state.loaded.value, true)
  assert.equal(h.state.snapshot.value.pending, 1001)
  unavailable = true
  await h.state.sample()
  assert.equal(h.state.snapshot.value, null)
  assert.match(h.state.snapshotError.value, /暂不可用/)
  h.close()
  const off = await workspace({
    dead: () => {
      throw new ApiRequestError('未启用', 503)
    },
  })
  await off.state.loadAccess()
  assert.equal(off.state.loaded.value, false)
  assert.match(off.state.error.value, /未启用/)
  off.close()
})
test('追加页失败保留旧页和游标，重试去重，重复游标阻止继续循环', async () => {
  let fail = true
  const before = { createdAt: time, requestId: id(1) }
  const h = await workspace({
    dead: (_domain, cursor) => {
      if (!cursor) return page([row()], before)
      if (fail) throw new Error('SQL offline')
      return page([row(), row(2)])
    },
  })
  await h.state.loadAccess()
  await h.state.load(false)
  assert.equal(h.state.rows.value.length, 1)
  assert.equal(h.state.cursor.value.requestId, id(1))
  assert.match(h.state.error.value, /offline/)
  fail = false
  await h.state.load(false)
  assert.equal(h.state.rows.value.length, 2)
  assert.equal(h.state.cursor.value, null)
  h.close()
  const stuck = await workspace({ dead: () => page([row()], before) })
  await stuck.state.loadAccess()
  await stuck.state.load(false)
  assert.match(stuck.state.error.value, /没有推进/)
  stuck.close()
})
test('切域同步取消旧详情和采样，所有迟到数据均不能污染新域', async () => {
  const oldSample = deferred(),
    oldDetail = deferred(),
    oldPage = deferred()
  const h = await workspace({
    snapshot: (domain) => (domain === 'identity' ? oldSample.promise : { ...snapshot(), dead: 2 }),
    detail: () => oldDetail.promise,
    dead: (domain) => (domain === 'identity' ? oldPage.promise : page([row(3)])),
  })
  const old = h.state.loadAccess()
  await until(() => h.calls.filter((call) => ['dead', 'snapshot'].includes(call.name)).length === 2)
  const detail = h.state.select(id(1))
  await h.state.chooseDomain('community')
  for (const call of h.calls.filter((call) => call.args[0] === 'identity')) {
    assert.equal(call.args.at(-1).aborted, true)
  }
  oldSample.resolve(snapshot())
  oldDetail.resolve(row())
  oldPage.resolve(page([row()]))
  await old
  await detail
  assert.equal(h.state.rows.value[0].requestId, id(3))
  assert.equal(h.state.snapshot.value.dead, 2)
  assert.equal(h.state.selected.value, null)
  h.close()
})
test('快速切任务拒绝旧详情；刷新权限清除正在加载的详情', async () => {
  const old = deferred(),
    h = await workspace({ detail: (_domain, request) => (request === id(1) ? old.promise : row(2)) })
  await h.state.loadAccess()
  const first = h.state.select(id(1))
  await h.state.select(id(2))
  old.resolve(row())
  await first
  assert.equal(h.state.selected.value.requestId, id(2))
  await h.state.loadAccess()
  assert.equal(h.state.selected.value, null)
  h.close()
})
test('换账号或会话轮次取消查询，旧 403 不撤销新身份且普通资料刷新不清空', async () => {
  const late = deferred(),
    h = await workspace({ detail: () => late.promise })
  await h.state.loadAccess()
  h.session.user = { id: '10', displayName: 'refresh' }
  assert.equal(h.state.canRead.value, true)
  const old = h.state.select(id(1))
  h.session.user = { id: '20' }
  h.session.sessionRevision++
  assert.equal(h.state.rows.value.length, 0)
  assert.equal(h.state.canRead.value, false)
  await h.state.loadAccess()
  late.reject(new ApiRequestError('old forbidden', 403))
  await old
  assert.equal(h.state.canRead.value, true)
  assert.equal(h.state.detailError.value, '')
  h.close()
})
test('当前 403 同步清除任务和采样，卸载后禁止迟到回填与继续查询', async () => {
  const h = await workspace({
    detail: () => {
      throw new ApiRequestError('revoked', 403)
    },
  })
  await h.state.loadAccess()
  await h.state.select(id(1))
  assert.equal(h.state.canRead.value, false)
  assert.equal(h.state.rows.value.length, 0)
  assert.equal(h.state.snapshot.value, null)
  assert.match(h.state.accessError.value, /权限已改变/)
  h.close()
  const late = deferred(),
    disposed = await workspace({ dead: () => late.promise })
  const first = disposed.state.loadAccess()
  disposed.close()
  late.resolve(page([row()]))
  await first
  const calls = disposed.calls.length
  await disposed.state.loadAccess()
  await disposed.state.sample()
  assert.equal(disposed.calls.length, calls)
  assert.equal(disposed.state.rows.value.length, 0)
})
test('缓存最多两百条，达到上限不再发送追加页；刷新从头读取', async () => {
  let start = 0
  const h = await workspace({
    dead: () => {
      const items = Array.from({ length: 20 }, (_, n) => row(start + n + 1))
      start += 20
      return page(items, { createdAt: time, requestId: items.at(-1).requestId })
    },
  })
  await h.state.loadAccess()
  for (let i = 0; i < 15; i++) await h.state.load(false)
  assert.equal(h.state.rows.value.length, 200)
  assert.equal(h.state.capped.value, true)
  assert.equal(h.calls.filter((call) => call.name === 'dead').length, 10)
  await h.state.load(true)
  assert.equal(h.state.rows.value.length, 20)
  assert.equal(h.state.capped.value, false)
  h.close()
})
