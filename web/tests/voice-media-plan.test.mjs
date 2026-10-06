import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

const data = (generation = '9007199254741001') => ({
  tracked: true,
  generation,
  active: true,
  publishDesired: false,
  pendingRetirements: 2,
  deadRetirements: 0,
  mediaReady: false,
})
const disabled = {
  tracked: false,
  generation: null,
  active: false,
  publishDesired: false,
  pendingRetirements: null,
  deadRetirements: null,
  mediaReady: false,
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let i = 0; i < 40 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
async function moduleFor(source, imports, context) {
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  await module.link((name) => {
    const values = imports[name]
    if (!values) throw new Error(`Unknown import ${name}`)
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
async function service() {
  const calls = [],
    source = await readFile(new URL('../src/services/voice-media-plan.ts', import.meta.url), 'utf8')
  const api = await moduleFor(
    source,
    {
      './http': {
        request: (...args) => {
          calls.push(args)
          return Promise.resolve(data())
        },
      },
    },
    vm.createContext({ URLSearchParams, encodeURIComponent, Error }),
  )
  return { api, calls }
}
async function setup(network = () => Promise.resolve(data()), allowed = true) {
  const calls = [],
    hooks = [],
    timers = new Set(),
    listeners = { window: new Map(), document: new Map() }
  const surface = (name) => ({
    addEventListener: (type, fn) => {
      const list = listeners[name].get(type) || new Set()
      list.add(fn)
      listeners[name].set(type, list)
    },
    removeEventListener: (type, fn) => listeners[name].get(type)?.delete(fn),
  })
  const navigator = { onLine: true },
    document = { ...surface('document'), visibilityState: 'visible' }
  const context = vue.reactive({ roomId: '1', userId: '42', sessionRevision: 1, allowed })
  const source = await readFile(new URL('../src/composables/voice-media-plan.ts', import.meta.url), 'utf8')
  const api = {
    get: (...args) => {
      calls.push(args)
      return network(...args)
    },
  }
  const result = await moduleFor(
    source,
    {
      vue: { ...vue, onBeforeUnmount: (hook) => hooks.push(hook) },
      '../services/voice-media-plan': { voiceMediaPlanApi: api },
    },
    vm.createContext({
      AbortController,
      Error,
      navigator,
      document,
      window: surface('window'),
      setInterval: (fn, ms) => {
        const timer = { fn, ms }
        timers.add(timer)
        return timer
      },
      clearInterval: (timer) => timers.delete(timer),
    }),
  )
  const scope = vue.effectScope(),
    state = scope.run(() => result.useVoiceMediaPlan(context, api))
  return {
    state,
    context,
    calls,
    navigator,
    document,
    timers,
    listeners,
    dispatch(type, surface = 'window') {
      for (const fn of listeners[surface].get(type) || []) fn()
    },
    dispose() {
      hooks.forEach((fn) => fn())
      scope.stop()
    },
  }
}

test('媒体计划协议严格区分关闭、损坏、希望值和已生效媒体，不转换轮次为number', async () => {
  const { api } = await service()
  assert.equal(api.validateVoiceMediaPlan(data()).generation, '9007199254741001')
  assert.equal(api.validateVoiceMediaPlan(disabled).tracked, false)
  for (const value of [
    null,
    { ...data(), mediaReady: true },
    { ...disabled, pendingRetirements: 0 },
    { ...data(), active: false, publishDesired: true },
    { ...data(), generation: '9223372036854775808' },
    { ...data(), pendingRetirements: -1 },
    { ...data(), deadRetirements: 1.5 },
  ])
    assert.throws(() => api.validateVoiceMediaPlan(value))
})
test('请求适配仅编码房间ID、no-store和原取消信号，不发送身份/媒体凭据或写入', async () => {
  const { api, calls } = await service(),
    signal = new AbortController().signal
  await api.voiceMediaPlanApi.get('9223372036854775807', signal)
  assert.equal(calls[0][0], '/voice/rooms/9223372036854775807/interaction/media-plan')
  assert.equal(calls[0][1].signal, signal)
  assert.equal(calls[0][1].cache, 'no-store')
  assert.equal(calls[0][1].method, undefined)
})
test('父面板授权未确认时不读取，确认后计划关闭不是零任务成功', async () => {
  const h = await setup(() => Promise.resolve(disabled), false)
  try {
    assert.equal(h.calls.length, 0)
    h.context.allowed = true
    await until(() => !h.state.loading.value)
    assert.equal(h.state.plan.value.tracked, false)
    assert.equal(h.state.plan.value.pendingRetirements, null)
  } finally {
    h.dispose()
  }
})
test('网络失败清旧进度而非展示清退完成，手工重取恢复真实队列', async () => {
  let broken = false
  const h = await setup(() => (broken ? Promise.reject(new Error('真实读取故障')) : Promise.resolve(data())))
  try {
    await until(() => !h.state.loading.value)
    broken = true
    await h.state.load()
    assert.equal(h.state.plan.value, null)
    assert.match(h.state.error.value, /读取故障/)
    broken = false
    await h.state.load()
    assert.equal(h.state.plan.value.pendingRetirements, 2)
  } finally {
    h.dispose()
  }
})
test('身份/房间轮次取消旧查询，迟到结果不填回新账号', async () => {
  const wait = deferred()
  let first = true
  const h = await setup(() => (first ? ((first = false), wait.promise) : Promise.resolve(data('2'))))
  try {
    const signal = h.calls[0][1]
    h.context.userId = '43'
    h.context.sessionRevision++
    await until(() => !h.state.loading.value)
    assert.equal(signal.aborted, true)
    wait.resolve(data('9007199254741001'))
    await vue.nextTick()
    assert.equal(h.state.plan.value.generation, '2')
  } finally {
    h.dispose()
  }
})
test('失去父面板授权停止轮询并取消私有进度，旧响应不能恢复', async () => {
  const wait = deferred(),
    h = await setup(() => wait.promise)
  try {
    h.context.allowed = false
    assert.equal(h.timers.size, 0)
    assert.equal(h.calls[0][1].aborted, true)
    wait.resolve(data())
    await vue.nextTick()
    assert.equal(h.state.plan.value, null)
    await h.state.load()
    assert.equal(h.calls.length, 1)
  } finally {
    h.dispose()
  }
})
test('离线/隐藏停止读取，恢复仅GET重新核验，卸载清所有监听和计时器', async () => {
  const h = await setup()
  await until(() => !h.state.loading.value)
  h.navigator.onLine = false
  h.dispatch('offline')
  assert.equal(h.state.plan.value, null)
  const count = h.calls.length
  await h.state.load()
  assert.equal(h.calls.length, count)
  h.navigator.onLine = true
  h.dispatch('online')
  await until(() => !h.state.loading.value)
  assert.ok(h.state.plan.value)
  h.document.visibilityState = 'hidden'
  h.dispatch('visibilitychange', 'document')
  assert.equal(h.state.plan.value, null)
  h.dispose()
  assert.equal(h.timers.size, 0)
  for (const map of Object.values(h.listeners)) for (const callbacks of map.values()) assert.equal(callbacks.size, 0)
})
