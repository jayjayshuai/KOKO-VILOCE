import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

// 执行生产 TS 与真实 Vue watch；网络桩只证明客户端状态，不代替真实 RPC/SQL/浏览器。
async function execute(relative, imports) {
  const source = await readFile(new URL(`../src/${relative}`, import.meta.url), 'utf8')
  const context = vm.createContext({ AbortController, Error, Date })
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = new vm.SourceTextModule(output, { context })
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
const snapshot = (profileReferenced = false, postReferenced = true) => ({
  profileReferenced,
  postReferenced,
  checkedAt: '2026-10-04T07:00:00.123456Z',
})
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function workspace(action = () => snapshot()) {
  const hooks = [],
    calls = []
  const selected = vue.ref({ id: 'asset-a' })
  const session = vue.reactive({
    user: { id: '9007199254740993' },
    sessionRevision: 1,
    expireSession(id, revision) {
      if (id === this.user?.id && revision === this.sessionRevision) {
        this.sessionRevision++
        this.user = null
      }
    },
  })
  const network = {
    inspect: (...args) => {
      calls.push(args)
      return action(...args)
    },
  }
  const module = await execute('composables/asset-reference-inspection.ts', {
    vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) },
    '../services/http': { ApiRequestError },
    '../services/asset-references': { assetReferencesApi: network },
  })
  const state = module.useAssetReferenceInspection(selected, session)
  return { state, selected, session, calls, close: () => hooks.forEach((callback) => callback()) }
}

test('引用适配器编码资产ID并使用 no-store 和原 AbortSignal', async () => {
  const calls = []
  const module = await execute('services/asset-references.ts', {
    './http': {
      request: async (...args) => {
        calls.push(args)
        return snapshot()
      },
    },
  })
  const controller = new AbortController()
  assert.equal((await module.assetReferencesApi.inspect('asset/a?', controller.signal)).postReferenced, true)
  assert.equal(calls[0][0], '/assets/images/asset%2Fa%3F/references')
  assert.equal(calls[0][1].cache, 'no-store')
  assert.equal(calls[0][1].signal, controller.signal)
})

test('缺失布尔值或无偏移时间不显示成成功空引用', async () => {
  for (const value of [
    null,
    {},
    { ...snapshot(), profileReferenced: 'false' },
    { ...snapshot(), checkedAt: '2026-10-04T07:00:00' },
    { ...snapshot(), checkedAt: 'invalidZ' },
  ]) {
    const module = await execute('services/asset-references.ts', { './http': { request: async () => value } })
    await assert.rejects(module.assetReferencesApi.inspect('a'), /响应不完整/)
  }
})

test('显式查询区分有引用和两个域均成功的无引用快照', async () => {
  let current = snapshot()
  const h = await workspace(() => current)
  try {
    assert.equal(h.calls.length, 0)
    assert.equal(h.state.referenced.value, null)
    await h.state.inspect()
    assert.equal(h.state.referenced.value, true)
    current = snapshot(false, false)
    await h.state.inspect()
    assert.equal(h.state.referenced.value, false)
    assert.equal(h.state.error.value, '')
  } finally {
    h.close()
  }
})

test('并发点击只查询一次，503清除旧结果，重试可恢复', async () => {
  const pending = deferred()
  let action = () => pending.promise
  const h = await workspace(() => action())
  try {
    const first = h.state.inspect()
    await h.state.inspect()
    assert.equal(h.calls.length, 1)
    assert.equal(h.state.loading.value, true)
    pending.resolve(snapshot())
    await first
    action = () => {
      throw new ApiRequestError('依赖暂不可用', 503)
    }
    await h.state.inspect()
    assert.equal(h.state.snapshot.value, null)
    assert.equal(h.state.referenced.value, null)
    assert.match(h.state.error.value, /依赖暂不可用/)
    action = () => snapshot(false, false)
    await h.state.inspect()
    assert.equal(h.state.referenced.value, false)
  } finally {
    h.close()
  }
})

test('切换图片立即取消并清空快照，旧成功不能覆盖新图片', async () => {
  const pending = deferred()
  const h = await workspace((id) => (id === 'asset-a' ? pending.promise : snapshot(true, false)))
  try {
    const first = h.state.inspect()
    const signal = h.calls[0][1]
    h.selected.value = { id: 'asset-b' }
    assert.equal(signal.aborted, true)
    assert.equal(h.state.snapshot.value, null)
    await h.state.inspect()
    pending.resolve(snapshot())
    await first
    assert.equal(h.state.snapshot.value.profileReferenced, true)
    assert.equal(h.state.snapshot.value.postReferenced, false)
    h.selected.value = null
    assert.equal(h.state.referenced.value, null)
  } finally {
    h.close()
  }
})

test('换账号隔离旧401，当前401使会话过期并清空结果', async () => {
  const pending = deferred()
  let action = () => pending.promise
  const h = await workspace(() => action())
  try {
    const first = h.state.inspect()
    h.session.user = { id: '9007199254740995' }
    h.session.sessionRevision++
    assert.equal(h.calls[0][1].aborted, true)
    pending.reject(new ApiRequestError('旧会话过期', 401))
    await first
    assert.equal(h.session.user.id, '9007199254740995')
    action = () => {
      throw new ApiRequestError('当前会话过期', 401)
    }
    await h.state.inspect()
    assert.equal(h.session.user, null)
    assert.equal(h.state.snapshot.value, null)
    assert.equal(h.state.loading.value, false)
    const before = h.calls.length
    await h.state.inspect()
    assert.equal(h.calls.length, before)
  } finally {
    h.close()
  }
})

test('卸载取消请求，旧故障不回填错误，也不继续发送请求', async () => {
  const pending = deferred(),
    h = await workspace(() => pending.promise)
  const first = h.state.inspect()
  h.close()
  assert.equal(h.calls[0][1].aborted, true)
  pending.reject(new ApiRequestError('已经离开的页面', 503))
  await first
  assert.equal(h.state.error.value, '')
  assert.equal(h.state.snapshot.value, null)
  await h.state.inspect()
  assert.equal(h.calls.length, 1)
})
