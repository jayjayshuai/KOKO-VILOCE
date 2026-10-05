import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

// 运行生产composable/Vue响应性；网络为明确桩，不冒充浏览器或LiveKit。
const source = await readFile(new URL('../src/composables/voice-owner-workspace.ts', import.meta.url), 'utf8')
const room = (id = '9223372036854775807', status = 'OPEN') => ({ id, status, title: `房间${id}` })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let index = 0; index < 40 && !predicate(); index++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
async function moduleFrom(code, modules, globals = {}) {
  const context = vm.createContext({ AbortController, Error, Set, URLSearchParams, ...globals })
  const module = new vm.SourceTextModule(
    ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context },
  )
  await module.link((name) => {
    const values = modules[name]
    if (!values) throw new Error(`Unknown module ${name}`)
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
async function harness(overrides = {}) {
  const hooks = [],
    calls = [],
    closed = []
  const session = vue.reactive({ userId: '42', sessionRevision: 1 })
  const network = {
    list: (before, signal) => {
      calls.push({ name: 'list', before, signal })
      return overrides.list ? overrides.list(before, signal) : Promise.resolve({ items: [room()], nextBefore: null })
    },
    close: (id, signal) => {
      calls.push({ name: 'close', id, signal })
      return overrides.close ? overrides.close(id, signal) : Promise.resolve()
    },
  }
  const module = await moduleFrom(source, {
    vue: { ...vue, onBeforeUnmount: (hook) => hooks.push(hook) },
    '../services/voice-owner': { voiceOwnerApi: network },
  })
  const scope = vue.effectScope()
  const state = scope.run(() =>
    module.useVoiceOwnerWorkspace(
      session,
      (id) => {
        closed.push(id)
        overrides.closed?.(id)
      },
      network,
    ),
  )
  return {
    state,
    session,
    calls,
    closed,
    dispose() {
      hooks.forEach((hook) => hook())
      scope.stop()
    },
  }
}

test('本人列表游标保持字符串，下一页去重，不读取其他房主参数', async () => {
  const h = await harness({
    list: (before) =>
      Promise.resolve(
        before === null
          ? { items: [room('9223372036854775807')], nextBefore: '9223372036854775807' }
          : { items: [room('9223372036854775807'), room('9223372036854775806')], nextBefore: null },
      ),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.load(false)
    assert.equal(h.calls[1].before, '9223372036854775807')
    assert.equal(h.state.rooms.value.length, 2)
    assert.equal(h.state.nextBefore.value, null)
    await h.state.load(false)
    assert.equal(h.calls.length, 2)
  } finally {
    h.dispose()
  }
})

test('列表失败是可重试故障，不伪装空态成功，也不清掉关闭未知错误', async () => {
  let fail = true
  const h = await harness({
    list: () => (fail ? Promise.reject(new Error('网络故障')) : Promise.resolve({ items: [room()], nextBefore: null })),
  })
  try {
    await until(() => !h.state.loading.value)
    assert.equal(h.state.readError.value, '网络故障')
    h.state.writeError.value = '原关闭结果未知'
    fail = false
    await h.state.load()
    assert.equal(h.state.readError.value, '')
    assert.equal(h.state.writeError.value, '原关闭结果未知')
    assert.equal(h.state.rooms.value.length, 1)
  } finally {
    h.dispose()
  }
})

test('刷新取消旧读取，迟到结果不能倒退新快照', async () => {
  const wait = deferred()
  let first = true
  const h = await harness({
    list: () => (first ? ((first = false), wait.promise) : Promise.resolve({ items: [room('new')], nextBefore: null })),
  })
  try {
    const signal = h.calls[0].signal
    await h.state.load()
    assert.equal(signal.aborted, true)
    wait.resolve({ items: [room('old')], nextBefore: 'old' })
    await vue.nextTick()
    assert.equal(h.state.rooms.value[0].id, 'new')
    assert.equal(h.state.nextBefore.value, null)
  } finally {
    h.dispose()
  }
})

test('只有本页真实OPEN房间可打开确认，未知或非OPEN不发送关闭', async () => {
  const h = await harness({ list: () => Promise.resolve({ items: [room('closed', 'CLOSED')], nextBefore: null }) })
  try {
    await until(() => !h.state.loading.value)
    h.state.prepareClose(room('unknown'))
    await h.state.confirmClose()
    h.state.prepareClose(room('closed'))
    await h.state.confirmClose()
    assert.equal(h.state.confirmation.value, null)
    assert.equal(h.calls.filter((call) => call.name === 'close').length, 0)
  } finally {
    h.dispose()
  }
})

test('关闭重复点击只发一次，等待中不乐观改状态，确认后才通知父页面', async () => {
  const wait = deferred()
  const h = await harness({ close: () => wait.promise })
  try {
    await until(() => !h.state.loading.value)
    h.state.prepareClose(room())
    const pending = h.state.confirmClose()
    await h.state.confirmClose()
    h.state.cancelClose()
    await h.state.load()
    assert.equal(h.calls.filter((call) => call.name === 'close').length, 1)
    assert.equal(h.state.rooms.value[0].status, 'OPEN')
    assert.equal(h.closed.length, 0)
    assert.ok(h.state.confirmation.value)
    wait.resolve()
    await pending
    assert.equal(h.state.rooms.value[0].status, 'CLOSED')
    assert.equal(h.closed[0], '9223372036854775807')
    assert.match(h.state.success.value, /已确认关闭/)
  } finally {
    h.dispose()
  }
})

test('未知关闭结果不伪造成功，按原房间重试才更新CLOSED', async () => {
  let fail = true
  const h = await harness({ close: () => (fail ? Promise.reject(new Error('响应超时')) : Promise.resolve()) })
  try {
    await until(() => !h.state.loading.value)
    h.state.prepareClose(room())
    await h.state.confirmClose()
    assert.equal(h.state.rooms.value[0].status, 'OPEN')
    assert.match(h.state.writeError.value, /服务端可能已执行/)
    assert.equal(h.state.success.value, '')
    assert.equal(h.closed.length, 0)
    fail = false
    await h.state.confirmClose()
    assert.equal(
      h.calls.filter((call) => call.name === 'close').every((call) => call.id === '9223372036854775807'),
      true,
    )
    assert.equal(h.state.rooms.value[0].status, 'CLOSED')
  } finally {
    h.dispose()
  }
})

test('本机关闭通知故障不把服务器已确认成功改成未知关闭', async () => {
  const h = await harness({
    closed: () => {
      throw new Error('synthetic local cleanup failure')
    },
  })
  try {
    await until(() => !h.state.loading.value)
    h.state.prepareClose(room())
    await h.state.confirmClose()
    assert.equal(h.state.rooms.value[0].status, 'CLOSED')
    assert.match(h.state.success.value, /已确认关闭/)
    assert.match(h.state.writeError.value, /关闭已确认.*清理未完成/)
    assert.doesNotMatch(h.state.writeError.value, /结果未确认/)
  } finally {
    h.dispose()
  }
})

test('同账号新会话取消旧关闭等待，旧finally不能清除新写入busy', async () => {
  const old = deferred(),
    fresh = deferred()
  let call = 0
  const h = await harness({ close: () => (++call === 1 ? old.promise : fresh.promise) })
  try {
    await until(() => !h.state.loading.value)
    h.state.prepareClose(room())
    const stale = h.state.confirmClose(),
      signal = h.calls.find((value) => value.name === 'close').signal
    h.session.sessionRevision++
    await until(() => !h.state.loading.value)
    assert.equal(signal.aborted, true)
    h.state.prepareClose(room())
    const current = h.state.confirmClose()
    old.resolve()
    await stale
    assert.equal(h.state.closing.value, '9223372036854775807')
    assert.equal(h.closed.length, 0)
    assert.equal(h.state.rooms.value[0].status, 'OPEN')
    fresh.resolve()
    await current
    assert.equal(h.closed.length, 1)
  } finally {
    h.dispose()
  }
})

test('卸载取消写等待并清私有状态，迟到成功不通知父页面', async () => {
  const wait = deferred()
  const h = await harness({ close: () => wait.promise })
  await until(() => !h.state.loading.value)
  h.state.prepareClose(room())
  const pending = h.state.confirmClose(),
    signal = h.calls.find((value) => value.name === 'close').signal
  h.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve()
  await pending
  assert.equal(h.state.rooms.value.length, 0)
  assert.equal(h.closed.length, 0)
  h.session.userId = '43'
  assert.equal(h.calls.length, 2)
})

test('真实HTTP适配器绑定字符串游标和关闭ID，不传用户身份正文', async () => {
  const calls = []
  const module = await moduleFrom(await readFile(new URL('../src/services/voice-owner.ts', import.meta.url), 'utf8'), {
    './http': {
      request: (path, options) => {
        calls.push({ path, options })
        return Promise.resolve()
      },
    },
  })
  const signal = new AbortController().signal
  await module.voiceOwnerApi.list('9223372036854775807', signal)
  await module.voiceOwnerApi.close('9223372036854775807', signal)
  assert.equal(calls[0].path, '/voice/rooms/mine?size=20&before=9223372036854775807')
  assert.equal(calls[1].path, '/voice/rooms/9223372036854775807')
  assert.equal(calls[1].options.method, 'DELETE')
  assert.equal(calls[1].options.signal, signal)
  assert.equal(calls[1].options.body, undefined)
})
