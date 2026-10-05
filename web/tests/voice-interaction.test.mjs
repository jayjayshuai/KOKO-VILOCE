import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
const source = await readFile(new URL('../src/composables/voice-interaction-workspace.ts', import.meta.url), 'utf8')
const snapshot = (version = '9007199254741001') => ({
  roomId: '1',
  version,
  mySessionId: 'synthetic-session',
  myRole: 'OWNER',
  mediaReady: false,
  members: [],
  administrators: [],
  seats: [],
  requests: [],
})
function deferred() {
  let resolve, reject
  const promise = new Promise((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let i = 0; i < 50 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
async function setup(overrides = {}) {
  const hooks = [],
    calls = [],
    timers = new Set()
  const context = vue.reactive({ roomId: '1', userId: '42', sessionRevision: 1 })
  const network = Object.fromEntries(
    ['capabilities', 'snapshot', 'join', 'command', 'heartbeat', 'actions'].map((name) => [
      name,
      (...args) => {
        calls.push({ name, args })
        if (overrides[name]) return overrides[name](...args)
        return Promise.resolve(
          name === 'capabilities'
            ? { enabled: true, mediaReady: false, version: '9007199254741001', canInspect: true }
            : name === 'snapshot'
              ? snapshot()
              : { type: name.toUpperCase(), version: '9007199254741002', sessionId: null },
        )
      },
    ]),
  )
  const runtime = vm.createContext({
    AbortController,
    Error,
    setInterval: (callback, ms) => {
      const timer = { callback, ms }
      timers.add(timer)
      return timer
    },
    clearInterval: (timer) => timers.delete(timer),
  })
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
      .outputText,
    { context: runtime },
  )
  await module.link((name) => {
    const values =
      name === 'vue'
        ? { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) }
        : { voiceInteractionApi: network, interactionUuid: () => '00000000-0000-0000-0000-000000000042' }
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context: runtime },
    )
  })
  await module.evaluate()
  const scope = vue.effectScope()
  const state = scope.run(() => module.namespace.useVoiceInteractionWorkspace(context, network))
  return {
    state,
    context,
    calls,
    timers,
    dispose() {
      hooks.forEach((fn) => fn())
      scope.stop()
    },
  }
}
test('关闭核心不是假快照，不请求成员或媒体API', async () => {
  const h = await setup({
    capabilities: () => Promise.resolve({ enabled: false, mediaReady: false, version: null, canInspect: false }),
  })
  try {
    await until(() => !h.state.loading.value)
    assert.equal(h.state.snapshot.value, null)
    await h.state.join()
    await h.state.command('LOCK', { seatNo: 1, value: true })
    assert.equal(h.calls.length, 1)
  } finally {
    h.dispose()
  }
})
test('能力读取故障不伪装关闭开关或空态成功', async () => {
  const h = await setup({ capabilities: () => Promise.reject(new Error('真实网络不可用')) })
  try {
    await until(() => !h.state.loading.value)
    assert.match(h.state.readError.value, /不可用/)
    assert.equal(h.state.capabilities.value, null)
    assert.equal(h.state.snapshot.value, null)
  } finally {
    h.dispose()
  }
})
test('未加入者只读能力，JOIN确认后重新读取成员事实', async () => {
  let joined = false
  const h = await setup({
    capabilities: () => Promise.resolve({ enabled: true, mediaReady: false, version: '0', canInspect: joined }),
    join: () => {
      joined = true
      return Promise.resolve({ type: 'JOIN', version: '1', sessionId: 'synthetic-session' })
    },
  })
  try {
    await until(() => !h.state.loading.value)
    assert.equal(h.calls.filter((c) => c.name === 'snapshot').length, 0)
    await h.state.join()
    assert.equal(h.calls.find((c) => c.name === 'join').args[1].expectedVersion, '0')
    assert.ok(h.state.snapshot.value)
    assert.match(h.state.success.value, /已确认提交 JOIN/)
  } finally {
    h.dispose()
  }
})
test('未知写结果保留原UUID会话版本，刷新不能换参，重试才确认', async () => {
  let fails = true
  const h = await setup({
    command: () =>
      fails ? Promise.reject(new Error('响应超时')) : Promise.resolve({ type: 'LOCK', version: '9007199254741002' }),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    const original = JSON.stringify(h.state.pending.value.input)
    await h.state.load()
    await h.state.command('MUTE', { seatNo: 1, value: false })
    assert.equal(h.calls.filter((c) => c.name === 'command').length, 1)
    fails = false
    await h.state.transmit()
    const writes = h.calls.filter((c) => c.name === 'command')
    assert.equal(JSON.stringify(writes[1].args[1]), original)
    assert.equal(writes[1].args[1].expectedVersion, '9007199254741001')
    assert.equal(h.state.pending.value, null)
  } finally {
    h.dispose()
  }
})
test('单写保护重复提交，快照同步失败不改提交成功为失败', async () => {
  const wait = deferred()
  let broken = false
  const h = await setup({
    command: () => wait.promise,
    snapshot: () => (broken ? Promise.reject(new Error('读取失败')) : Promise.resolve(snapshot())),
  })
  try {
    await until(() => !h.state.loading.value)
    const writing = h.state.command('LOCK', { seatNo: 2, value: true })
    await h.state.command('LOCK', { seatNo: 2, value: true })
    assert.equal(h.calls.filter((c) => c.name === 'command').length, 1)
    broken = true
    wait.resolve({ type: 'LOCK', version: '2' })
    await writing
    assert.equal(h.state.pending.value, null)
    assert.match(h.state.success.value, /已确认/)
    assert.match(h.state.readError.value, /读取失败/)
    assert.equal(h.state.writeError.value, '')
  } finally {
    h.dispose()
  }
})
test('同账号新会话撤销旧读写，旧finally不清掉新操作busy', async () => {
  const old = deferred(),
    fresh = deferred()
  let call = 0
  const h = await setup({ command: () => (++call === 1 ? old.promise : fresh.promise) })
  try {
    await until(() => !h.state.loading.value)
    const stale = h.state.command('LOCK', { seatNo: 1, value: true }),
      signal = h.calls.find((c) => c.name === 'command').args[2]
    h.context.sessionRevision++
    await until(() => !h.state.loading.value)
    assert.equal(signal.aborted, true)
    const current = h.state.command('LOCK', { seatNo: 2, value: true })
    old.resolve({ type: 'LOCK', version: '2' })
    await stale
    assert.equal(h.state.busy.value, true)
    assert.ok(h.state.pending.value)
    fresh.resolve({ type: 'LOCK', version: '3' })
    await current
    assert.equal(h.state.busy.value, false)
    assert.equal(h.state.pending.value, null)
  } finally {
    h.dispose()
  }
})
test('卸载停止轮询/心跳并隔离迟到回复', async () => {
  const wait = deferred()
  const h = await setup({ command: () => wait.promise })
  await until(() => !h.state.loading.value)
  const writing = h.state.command('LOCK', { seatNo: 1, value: true })
  const signal = h.calls.find((c) => c.name === 'command').args[2]
  assert.equal(h.timers.size, 2)
  h.dispose()
  assert.equal(h.timers.size, 0)
  assert.equal(signal.aborted, true)
  wait.resolve({ type: 'LOCK', version: '2' })
  await writing
  assert.equal(h.state.snapshot.value, null)
  assert.equal(h.state.success.value, '')
})
test('未确认原命令阻止心跳乱序，续约失败不假装媒体在线', async () => {
  const h = await setup({
    command: () => Promise.reject(new Error('未确认')),
    heartbeat: () => Promise.reject(new Error('会话过期')),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    await h.state.pulse()
    assert.equal(h.calls.filter((c) => c.name === 'heartbeat').length, 0)
    await h.state.discard()
    await h.state.pulse()
    assert.match(h.state.writeError.value, /续约未确认/)
    assert.equal(h.state.capabilities.value.mediaReady, false)
  } finally {
    h.dispose()
  }
})

test('审计撤权立即清私有结果，旧响应不能恢复已失去权限的记录', async () => {
  const wait = deferred()
  const h = await setup({ actions: () => wait.promise })
  try {
    await until(() => !h.state.loading.value)
    const reading = h.state.loadActions()
    const signal = h.calls.find((call) => call.name === 'actions').args[2]
    h.state.snapshot.value = { ...snapshot(), myRole: 'LISTENER' }
    assert.equal(signal.aborted, true)
    wait.resolve({ items: [{ version: '2', actorId: '42' }], nextBefore: null })
    await reading
    assert.equal(h.state.actions.value, null)
    assert.equal(h.state.actionLoading.value, false)
  } finally {
    h.dispose()
  }
})

test('审计分页失败保留旧页和原游标，不伪装空历史', async () => {
  const h = await setup({
    actions: (_room, before) =>
      before
        ? Promise.reject(new Error('审计下一页故障'))
        : Promise.resolve({ items: [{ version: '9007199254741001', type: 'LOCK' }], nextBefore: '9007199254741001' }),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.loadActions()
    await h.state.loadActions(true)
    assert.equal(h.state.actions.value.items.length, 1)
    assert.equal(h.state.actions.value.nextBefore, '9007199254741001')
    assert.match(h.state.actionError.value, /下一页故障/)
    assert.equal(h.calls.filter((call) => call.name === 'actions')[1].args[1], '9007199254741001')
  } finally {
    h.dispose()
  }
})
