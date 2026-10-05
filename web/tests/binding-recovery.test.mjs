import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import * as pinia from 'pinia'
// 真实生产TS/Vue/Pinia逻辑；网络为桩，不证明MySQL、RPC或浏览器。
async function execute(path, imports, origin = { protocol: 'http:', hostname: '127.0.0.1' }) {
  const source = await readFile(new URL('../src/' + path, import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context: vm.createContext({ AbortController, URLSearchParams, Error, location: origin }) },
  )
  await module.link(
    (specifier) =>
      new vm.SyntheticModule(
        Object.keys(imports[specifier]),
        function () {
          for (const [key, value] of Object.entries(imports[specifier])) this.setExport(key, value)
        },
        { context: module.context },
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
const id = (n) => '81000000-0000-4000-8000-' + String(n).padStart(12, '0'),
  time = '2026-10-05T01:00:00.123456',
  reason = '工单已经定位根因并完成处理'
const command = () => ({ commandId: id(2), requestId: id(1), expectedGeneration: 0, reason })
const audit = (generation = 1) => ({
  commandId: id(generation + 1),
  requestId: id(1),
  operatorId: '10',
  expectedGeneration: generation - 1,
  acceptedGeneration: generation,
  previousAttempts: generation * 10,
  previousGenerationAttempts: 10,
  previousFailure: 'lease-exhausted',
  reason,
  createdAt: time,
})
const receipt = () => ({ commandId: id(2), requestId: id(1), acceptedGeneration: 1, acceptedAt: time })
const proof = () => ({ confirmationToken: 'a'.repeat(43), expiresAt: time })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function adapter(result, calls = [], origin) {
  const times = await execute('services/binding-releases.ts', { './http': { request: () => {} } })
  const value = await execute(
    'services/binding-recovery.ts',
    {
      './http': {
        request: async (...args) => {
          calls.push(args)
          return result
        },
      },
      './binding-releases': { validBindingTimestamp: times.validBindingTimestamp },
    },
    origin,
  )
  return value.bindingRecoveryApi
}
async function workspace(overrides = {}, permissions = ['asset:binding:read', 'asset:binding:replay'], secure = true) {
  const calls = [],
    hooks = [],
    session = vue.reactive({ user: { id: '10' }, sessionRevision: 1 })
  const domain = vue.ref('identity'),
    task = vue.ref({ requestId: id(1), status: 'DEAD', replayGeneration: 0, generationAttempts: 10 }),
    access = vue.ref({ enabled: true, permissions, roles: [] })
  const recovery = await execute('services/binding-recovery.ts', {
    './http': { request: () => {} },
    './binding-releases': { validBindingTimestamp: () => true },
  })
  const storeModule = await execute('stores/binding-recovery.ts', {
    pinia,
    vue,
    './auth': { useAuthStore: () => session },
    '../services/binding-recovery': { validateBindingRecoveryCommand: recovery.validateBindingRecoveryCommand },
  })
  const container = pinia.createPinia()
  const retry = storeModule.useBindingRecoveryStore(container)
  const network = {
    confirm: proof,
    replay: receipt,
    receipt: () => audit(),
    audits: () => ({ items: [], nextGeneration: null }),
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
  const module = await execute('composables/binding-recovery-workspace.ts', {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../services/http': { ApiRequestError },
  })
  const state = module.useBindingRecoveryWorkspace(
    api,
    domain,
    task,
    access,
    session,
    retry,
    () => id(2),
    undefined,
    () => secure,
  )
  return {
    state,
    retry,
    calls,
    session,
    domain,
    task,
    access,
    close() {
      hooks.forEach((callback) => callback())
      retry.$dispose()
      pinia.disposePinia(container)
    },
  }
}
async function prepare(h) {
  h.state.reason.value = reason
  h.state.prepare()
  h.state.password.value = 'fixture-password'
  await h.state.confirm()
}
test('资产请求只发送固定域、no-store和秘密body，确认不等于受理', async () => {
  const calls = [],
    api = await adapter(proof(), calls)
  await api.confirm('identity', command(), 'fixture-password')
  assert.equal(calls[0][0], '/operations/binding-releases/identity/confirmations')
  assert.equal(calls[0][1].cache, 'no-store')
  assert.equal(JSON.parse(calls[0][1].body).command.commandId, id(2))
  assert.equal(calls.length, 1)
  await assert.rejects(() => api.confirm('live', command(), 'fixture-password'), /恢复域/)
  await assert.rejects(
    () => api.confirm('identity', { ...command(), reason: '😀😀😀😀😀' }, 'fixture-password'),
    /命令无效/,
  )
})
test('公网明文HTTP禁止准备和发送密码/确认票据，环回例外只用于隔离测试', async () => {
  const calls = [],
    api = await adapter(proof(), calls, { protocol: 'http:', hostname: '198.51.100.10' })
  await assert.rejects(() => api.confirm('identity', command(), 'fixture-password'), /非HTTPS/)
  await assert.rejects(() => api.replay('identity', command(), 'a'.repeat(43)), /非HTTPS/)
  assert.equal(calls.length, 0)
  const wrongProtocol = await adapter(proof(), calls, { protocol: 'ftp:', hostname: '127.0.0.1' })
  await assert.rejects(() => wrongProtocol.confirm('identity', command(), 'fixture-password'), /非HTTPS/)
  assert.equal(calls.length, 0)
  const h = await workspace({}, ['asset:binding:read', 'asset:binding:replay'], false)
  h.state.reason.value = reason
  h.state.prepare()
  await h.state.confirm()
  assert.equal(h.retry.pending, null)
  assert.equal(h.calls.length, 0)
  h.close()
})
test('未耗尽本代预算的DEAD不得准备人工恢复', async () => {
  const h = await workspace()
  h.task.value.generationAttempts = 9
  h.state.reason.value = reason
  h.state.prepare()
  assert.equal(h.retry.pending, null)
  assert.equal(h.calls.length, 0)
  h.close()
})
test('刷新重录不生成ID或发写请求；原命令404与重试拒绝仍保留未知', async () => {
  const h = await workspace({
    receipt: () => {
      throw new ApiRequestError('missing', 404)
    },
    replay: () => {
      throw new ApiRequestError('changed', 409)
    },
  })
  h.state.restoreRequestId.value = id(1)
  h.state.restoreCommandId.value = id(2)
  h.state.restoreGeneration.value = '0'
  h.state.restoreReason.value = reason
  h.state.restore()
  assert.equal(h.retry.pending.phase, 'uncertain')
  assert.equal(h.retry.pending.command.commandId, id(2))
  assert.equal(h.calls.length, 0)
  assert.equal(h.state.restoreReason.value, '')
  await h.state.query()
  assert.equal(h.retry.pending.phase, 'uncertain')
  h.state.password.value = 'fixture-password'
  await h.state.confirm()
  await h.state.submit()
  assert.equal(h.retry.pending.phase, 'uncertain')
  h.state.finish()
  assert.notEqual(h.retry.pending, null)
  h.close()
})
test('只读可重录查询已受理事实，坏字段、已有原命令和切域表单隔离', async () => {
  const h = await workspace({}, ['asset:binding:read'])
  h.state.restoreRequestId.value = id(1)
  h.state.restoreCommandId.value = 'invalid'
  h.state.restoreGeneration.value = '0'
  h.state.restoreReason.value = reason
  h.state.restore()
  assert.equal(h.retry.pending, null)
  assert.match(h.state.error.value, /UUID/)
  h.state.restoreCommandId.value = id(2)
  h.state.restore()
  const original = h.retry.pending.command
  h.state.restoreCommandId.value = id(3)
  h.state.restore()
  assert.equal(h.retry.pending.command, original)
  await h.state.query()
  assert.equal(h.retry.pending.phase, 'accepted')
  await h.state.confirm()
  assert.equal(h.calls.length, 1)
  h.domain.value = 'community'
  assert.equal(h.state.restoreCommandId.value, '')
  h.state.finish()
  assert.equal(h.retry.pending, null)
  h.close()
})
test('矛盾受理、错误日历、目标和损坏审计不能显示成成功', async () => {
  for (const result of [
    { ...receipt(), commandId: id(3) },
    { ...receipt(), acceptedGeneration: 2 },
    { ...receipt(), acceptedAt: '2026-02-30T00:00:00' },
  ]) {
    const api = await adapter(result)
    await assert.rejects(() => api.replay('identity', command(), 'a'.repeat(43)), /受理响应/)
  }
  const api = await adapter({ ...audit(), requestId: id(9) })
  await assert.rejects(() => api.receipt('identity', id(1), id(2)), /审计响应/)
})
test('审计页严格验证降序、exclusive游标和最大五条', async () => {
  const items = [5, 4, 3, 2, 1].map(audit),
    api = await adapter({ items, nextGeneration: 1 })
  assert.equal((await api.audits('identity', id(1), null)).items.length, 5)
  for (const page of [
    { items: [audit(), audit()], nextGeneration: null },
    { items: [audit(1), audit(2)], nextGeneration: null },
    { items: [audit(5)], nextGeneration: 4 },
  ]) {
    const bad = await adapter(page)
    await assert.rejects(() => bad.audits('identity', id(1), null), /审计页/)
  }
})
test('只读审核员不能准备或发送密码，空审计与读取故障分别呈现', async () => {
  const h = await workspace({}, ['asset:binding:read'])
  h.state.reason.value = reason
  h.state.prepare()
  await h.state.confirm()
  await h.state.submit()
  assert.equal(h.retry.pending, null)
  assert.equal(h.calls.length, 0)
  await h.state.loadAudits()
  assert.equal(h.state.auditsLoaded.value, true)
  assert.equal(h.state.auditError.value, '')
  h.close()
})
test('两步确认、重复点击和受理成功只说明排队而不是释放完成', async () => {
  const h = await workspace()
  await prepare(h)
  assert.equal(h.retry.pending.phase, 'prepared')
  assert.equal(h.state.password.value, '')
  assert.equal(h.calls.length, 1)
  assert.equal(h.state.confirmationReady.value, true)
  await h.state.submit()
  assert.equal(h.retry.pending.phase, 'accepted')
  assert.equal(h.state.confirmationReady.value, false)
  assert.match(h.state.message.value, /不代表保护释放完成/)
  await h.state.submit()
  assert.equal(h.calls.length, 2)
  h.state.finish()
  assert.equal(h.retry.pending, null)
  h.close()
})
test('写入超时、404和重试403均保留原幂等命令，不允许生成第二个ID', async () => {
  let writes = 0
  const h = await workspace({
    replay: () => {
      writes++
      throw new ApiRequestError('timeout', writes === 1 ? 0 : 403)
    },
    receipt: () => {
      throw new ApiRequestError('missing', 404)
    },
  })
  await prepare(h)
  await h.state.submit()
  assert.equal(h.retry.pending.phase, 'uncertain')
  const original = h.retry.pending.command
  h.state.finish()
  assert.equal(h.retry.pending.command, original)
  await h.state.query()
  assert.match(h.state.error.value, /不证明/)
  assert.equal(h.retry.pending.phase, 'uncertain')
  h.state.reason.value = reason
  h.state.prepare()
  assert.equal(h.retry.pending.command, original)
  h.state.password.value = 'again'
  await h.state.confirm()
  await h.state.submit()
  assert.equal(h.retry.pending.phase, 'uncertain')
  assert.equal(h.access.value, null)
  h.close()
})
test('第一次明确409拒绝可以取消，但不会把既有未知命令当失败丢弃', async () => {
  const h = await workspace({
    replay: () => {
      throw new ApiRequestError('generation changed', 409)
    },
  })
  await prepare(h)
  await h.state.submit()
  assert.equal(h.retry.pending.phase, 'prepared')
  assert.match(h.state.error.value, /明确未受理/)
  h.state.finish()
  assert.equal(h.retry.pending, null)
  h.close()
})
test('原命令查询必须匹配操作者、代次和原因才能确认受理', async () => {
  const h = await workspace({ receipt: () => ({ ...audit(), operatorId: '11' }) })
  await prepare(h)
  h.retry.pending.phase = 'uncertain'
  await h.state.query()
  assert.equal(h.retry.pending.phase, 'uncertain')
  assert.match(h.state.error.value, /不匹配/)
  h.close()
  const good = await workspace()
  await prepare(good)
  good.retry.pending.phase = 'uncertain'
  await good.state.query()
  assert.equal(good.retry.pending.phase, 'accepted')
  assert.equal(good.state.confirmationReady.value, false)
  good.close()
})
test('切域取消旧确认与密码；迟到响应不恢复秘密，原命令目标仍固定', async () => {
  const wait = deferred(),
    h = await workspace({ confirm: () => wait.promise })
  h.state.reason.value = reason
  h.state.prepare()
  h.state.password.value = 'private'
  const confirming = h.state.confirm()
  h.domain.value = 'community'
  assert.equal(h.state.password.value, '')
  assert.equal(h.calls[0].args.at(-1).aborted, true)
  wait.resolve(proof())
  await confirming
  assert.equal(h.state.confirmationReady.value, false)
  assert.equal(h.retry.pending.domain, 'identity')
  assert.equal(h.retry.pending.command.requestId, id(1))
  h.close()
})
test('换账号同步清原命令；旧403不能撤销新权限，普通资料刷新不清空', async () => {
  const wait = deferred(),
    h = await workspace({ replay: () => wait.promise })
  await prepare(h)
  h.session.user = { id: '10', displayName: 'new' }
  assert.notEqual(h.retry.pending, null)
  const submitting = h.state.submit()
  h.session.user = { id: '11' }
  assert.equal(h.retry.pending, null)
  h.access.value = { enabled: true, permissions: ['asset:binding:read'], roles: [] }
  wait.reject(new ApiRequestError('old denied', 403))
  await submitting
  assert.notEqual(h.access.value, null)
  h.close()
})
test('审计追加失败保留旧页，切任务后迟到审计不回填', async () => {
  let call = 0
  const wait = deferred()
  const h = await workspace({
    audits: () => {
      call++
      if (call === 1) return { items: [5, 4, 3, 2, 1].map(audit), nextGeneration: 1 }
      return wait.promise
    },
  })
  await h.state.loadAudits()
  const loading = h.state.loadAudits(false)
  h.task.value = { requestId: id(9), status: 'DEAD', replayGeneration: 0 }
  wait.resolve({ items: [], nextGeneration: null })
  await loading
  assert.equal(h.state.audits.value.length, 0)
  assert.equal(h.state.auditLoading.value, false)
  h.close()
})
test('卸载清密码/秘密但内存原未知命令仍可跨路由查询；无持久浏览器存储', async () => {
  const h = await workspace()
  await prepare(h)
  h.retry.pending.phase = 'uncertain'
  h.state.dispose()
  assert.equal(h.state.password.value, '')
  assert.equal(h.state.confirmationReady.value, false)
  assert.equal(h.retry.pending.phase, 'uncertain')
  const before = h.calls.length
  await h.state.confirm()
  await h.state.query()
  assert.equal(h.calls.length, before)
  h.close()
})
