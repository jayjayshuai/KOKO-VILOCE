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
  const listeners = { window: new Map(), document: new Map() }
  const surface = (name) => ({
    addEventListener: (event, callback) => {
      const callbacks = listeners[name].get(event) || new Set()
      callbacks.add(callback)
      listeners[name].set(event, callbacks)
    },
    removeEventListener: (event, callback) => listeners[name].get(event)?.delete(callback),
  })
  const document = { ...surface('document'), visibilityState: 'visible' }
  let now = 200000
  class Clock extends Date {
    static now() {
      return now
    }
  }
  const context = vue.reactive({ roomId: '1', userId: '42', sessionRevision: 1 })
  const network = Object.fromEntries(
    ['capabilities', 'snapshot', 'sync', 'receipt', 'join', 'command', 'heartbeat', 'actions'].map((name) => [
      name,
      (...args) => {
        calls.push({ name, args })
        if (overrides[name]) return overrides[name](...args)
        return Promise.resolve(
          name === 'capabilities'
            ? { enabled: true, mediaReady: false, version: '9007199254741001', canInspect: true }
            : name === 'snapshot'
              ? snapshot()
              : name === 'sync'
                ? { version: args[1], checkedAt: 'synthetic-time', snapshot: null }
                : name === 'receipt'
                  ? { committed: false, ack: null }
                  : { type: name.toUpperCase(), version: '9007199254741002', sessionId: null },
        )
      },
    ]),
  )
  const runtime = vm.createContext({
    AbortController,
    Error,
    Date: Clock,
    navigator: { onLine: true },
    window: surface('window'),
    document,
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
    listeners,
    document,
    advance(ms) {
      now += ms
    },
    dispatch(event, surface = 'window') {
      for (const callback of listeners[surface].get(event) || []) callback()
    },
    dispose() {
      hooks.forEach((fn) => fn())
      scope.stop()
    },
  }
}
test('前台焦点切换保留近期有效授权，不抢占用户点击；主动复查失败仍立即失效', async () => {
  let next = null
  const h = await setup({
    capabilities: () =>
      next
        ? next.promise
        : Promise.resolve({ enabled: true, mediaReady: false, version: '9007199254741001', canInspect: true }),
  })
  try {
    await until(() => h.state.fresh.value)
    next = deferred()
    h.dispatch('focus')
    assert.equal(h.state.loading.value, false)
    void h.state.load()
    assert.equal(h.state.loading.value, true)
    assert.equal(h.state.fresh.value, true)
    next.reject(new Error('复查失败'))
    await until(() => !h.state.loading.value)
    assert.equal(h.state.fresh.value, false)
    assert.match(h.state.readError.value, /复查失败/)
  } finally {
    h.dispose()
  }
})
test('新操作前置核验独占读取，续约/轮询不抢占；只使用新版本且不自动发送', async () => {
  let next = null,
    version = '9007199254741001'
  const h = await setup({
    capabilities: () =>
      next ? next.promise : Promise.resolve({ enabled: true, mediaReady: false, version, canInspect: true }),
    snapshot: async () => snapshot(version),
  })
  try {
    await until(() => h.state.fresh.value)
    next = deferred()
    const prepared = h.state.prepareAction()
    assert.equal(h.state.actionPreparing.value, true)
    await h.state.pulse()
    await h.state.load()
    h.dispatch('focus')
    assert.equal(h.calls.filter((call) => call.name === 'heartbeat').length, 0)
    assert.equal(h.calls.filter((call) => call.name === 'capabilities').length, 2)
    assert.equal(h.calls.filter((call) => call.name === 'command').length, 0)
    version = '9007199254741003'
    next.resolve({ enabled: true, mediaReady: false, version, canInspect: true })
    assert.equal(await prepared, true)
    assert.equal(h.state.actionPreparing.value, false)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    assert.equal(h.calls.find((call) => call.name === 'command').args[1].expectedVersion, version)
  } finally {
    h.dispose()
  }
})
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

test('同版本同步只读提示，保留名单但再次核验，不转高精度版本为number', async () => {
  const h = await setup()
  try {
    await until(() => !h.state.loading.value)
    const before = h.state.snapshot.value
    await h.state.load(false)
    assert.equal(h.calls.filter((c) => c.name === 'snapshot').length, 1)
    assert.equal(h.calls.find((c) => c.name === 'sync').args[1], '9007199254741001')
    assert.equal(h.state.snapshot.value, before)
    assert.equal(h.state.fresh.value, true)
  } finally {
    h.dispose()
  }
})

test('变化提示替换整个快照及当前角色，撤权清空审计，不拼接旧私有名单', async () => {
  const h = await setup({
    actions: () => Promise.resolve({ items: [{ type: 'LOCK' }], nextBefore: null }),
    sync: () =>
      Promise.resolve({
        version: '9007199254741002',
        snapshot: { ...snapshot('9007199254741002'), myRole: 'LISTENER' },
      }),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.loadActions()
    assert.ok(h.state.actions.value)
    await h.state.load(false)
    assert.equal(h.state.snapshot.value.myRole, 'LISTENER')
    assert.equal(h.state.actions.value, null)
    assert.equal(h.state.capabilities.value.version, '9007199254741002')
  } finally {
    h.dispose()
  }
})

test('网络读取失败保留旧快照但锁新命令并清审计，手工重取成功后恢复', async () => {
  let broken = false
  const h = await setup({
    actions: () => Promise.resolve({ items: [{ type: 'LOCK' }], nextBefore: null }),
    capabilities: () =>
      broken
        ? Promise.reject(new Error('读取中断'))
        : Promise.resolve({ enabled: true, mediaReady: false, version: '9007199254741001', canInspect: true }),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.loadActions()
    broken = true
    await h.state.load()
    assert.ok(h.state.snapshot.value)
    assert.equal(h.state.fresh.value, false)
    assert.equal(h.state.actions.value, null)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    await h.state.join()
    await h.state.pulse()
    assert.equal(
      h.calls.some((c) => ['command', 'join', 'heartbeat'].includes(c.name)),
      false,
    )
    broken = false
    await h.state.load()
    assert.equal(h.state.fresh.value, true)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    assert.equal(h.calls.filter((c) => c.name === 'command').length, 1)
  } finally {
    h.dispose()
  }
})

test('同步授权拒绝立即清私有快照，不能用同版本证明旧成员仍有权限', async () => {
  const denied = Object.assign(new Error('成员会话失效'), { status: 403 })
  const h = await setup({ sync: () => Promise.reject(denied) })
  try {
    await until(() => !h.state.loading.value)
    await h.state.load(false)
    assert.equal(h.state.snapshot.value, null)
    assert.equal(h.state.capabilities.value, null)
    assert.equal(h.state.fresh.value, false)
  } finally {
    h.dispose()
  }
})

test('审计HTTP拒绝也锁全部新操作并清名单，不等待下次轮询', async () => {
  const h = await setup({ actions: () => Promise.reject(Object.assign(new Error('权限已撤销'), { status: 403 })) })
  try {
    await until(() => !h.state.loading.value)
    await h.state.loadActions()
    assert.equal(h.state.snapshot.value, null)
    assert.equal(h.state.fresh.value, false)
    assert.match(h.state.actionError.value, /撤销/)
  } finally {
    h.dispose()
  }
})

test('缺失变化快照或错误房间不能伪装同步成功', async () => {
  let reply = { version: '9007199254741002', snapshot: null }
  const h = await setup({ sync: () => Promise.resolve(reply) })
  try {
    await until(() => !h.state.loading.value)
    await h.state.load(false)
    assert.equal(h.state.fresh.value, false)
    assert.match(h.state.readError.value, /缺少快照/)
    reply = { version: '9007199254741002', snapshot: { ...snapshot('9007199254741002'), roomId: '2' } }
    h.advance(5000)
    await h.state.load(false)
    assert.match(h.state.readError.value, /响应不一致/)
    assert.equal(h.state.snapshot.value.roomId, '1')
  } finally {
    h.dispose()
  }
})

test('离线暂停请求和续约，恢复先重新授权，旧同步回复不能覆盖', async () => {
  const wait = deferred(),
    h = await setup({ sync: () => wait.promise })
  try {
    await until(() => !h.state.loading.value)
    const old = h.state.load(false)
    const signal = h.calls.find((c) => c.name === 'sync').args[2]
    h.dispatch('offline')
    assert.equal(h.state.online.value, false)
    assert.equal(h.state.fresh.value, false)
    assert.equal(signal.aborted, true)
    const count = h.calls.length
    await h.state.load()
    await h.state.pulse()
    await h.state.join()
    assert.equal(h.calls.length, count)
    h.dispatch('online')
    await until(() => !h.state.loading.value)
    assert.equal(h.state.fresh.value, true)
    wait.resolve({ version: '9007199254741002', snapshot: { ...snapshot('9007199254741002'), myRole: 'LISTENER' } })
    await old
    assert.equal(h.state.snapshot.value.myRole, 'OWNER')
  } finally {
    h.dispose()
  }
})

test('隐藏停止续约，回前台重取事实，卸载移除所有监听', async () => {
  const h = await setup()
  await until(() => !h.state.loading.value)
  h.document.visibilityState = 'hidden'
  h.dispatch('visibilitychange', 'document')
  assert.equal(h.state.fresh.value, false)
  const count = h.calls.length
  await h.state.pulse()
  await h.state.load(false)
  assert.equal(h.calls.length, count)
  h.document.visibilityState = 'visible'
  h.dispatch('visibilitychange', 'document')
  await until(() => !h.state.loading.value)
  assert.equal(h.state.fresh.value, true)
  h.dispose()
  for (const map of Object.values(h.listeners)) for (const callbacks of map.values()) assert.equal(callbacks.size, 0)
})

test('失败轮询有界退避，手工刷新不受限制，超过15秒核验期限锁定操作', async () => {
  const h = await setup({ sync: () => Promise.reject(new Error('网络错误')) })
  try {
    await until(() => !h.state.loading.value)
    await h.state.load(false)
    await h.state.load(false)
    assert.equal(h.calls.filter((c) => c.name === 'sync').length, 1)
    h.advance(5000)
    await h.state.load(false)
    h.advance(5000)
    await h.state.load(false)
    assert.equal(h.calls.filter((c) => c.name === 'sync').length, 2)
    h.advance(5000)
    await h.state.load(false)
    assert.equal(h.calls.filter((c) => c.name === 'sync').length, 3)
    await h.state.load()
    assert.equal(h.state.fresh.value, true)
    h.advance(15000)
    const poll = [...h.timers].find((timer) => timer.ms === 5000)
    poll.callback()
    assert.equal(h.state.fresh.value, false)
    await until(() => !h.state.loading.value)
  } finally {
    h.dispose()
  }
})

test('查询收据不重发写请求，确认后重取事实，不恢复原JOIN会话', async () => {
  const h = await setup({
    command: () => Promise.reject(new Error('原响应丢失')),
    receipt: () =>
      Promise.resolve({ committed: true, ack: { type: 'LOCK', version: '9007199254741002', sessionId: null } }),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    const original = h.state.pending.value.input.requestId
    await h.state.checkReceipt()
    assert.equal(h.calls.filter((c) => c.name === 'command').length, 1)
    assert.equal(h.calls.find((c) => c.name === 'receipt').args[1], original)
    assert.equal(h.state.pending.value, null)
    assert.match(h.state.success.value, /已有 LOCK 提交收据/)
  } finally {
    h.dispose()
  }
})

test('未找到/收据错误/查询失败保持原未知请求，不自动换UUID或认定失败', async () => {
  let response = { committed: false, ack: null },
    broken = false
  const h = await setup({
    command: () => Promise.reject(new Error('超时')),
    receipt: () => (broken ? Promise.reject(new Error('查询故障')) : Promise.resolve(response)),
  })
  try {
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    const original = JSON.stringify(h.state.pending.value)
    await h.state.checkReceipt()
    assert.match(h.state.writeError.value, /可能仍在途中/)
    response = { committed: true, ack: { type: 'MUTE', version: '9007199254741002' } }
    await h.state.checkReceipt()
    assert.match(h.state.writeError.value, /不一致/)
    broken = true
    await h.state.checkReceipt()
    assert.match(h.state.writeError.value, /查询故障/)
    assert.equal(JSON.stringify(h.state.pending.value), original)
    assert.equal(h.calls.filter((c) => c.name === 'command').length, 1)
  } finally {
    h.dispose()
  }
})

test('换身份取消旧收据查询，迟到确认不能清除新用户请求', async () => {
  const wait = deferred(),
    h = await setup({ command: () => Promise.reject(new Error('未知')), receipt: () => wait.promise })
  try {
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 1, value: true })
    const checking = h.state.checkReceipt(),
      signal = h.calls.find((c) => c.name === 'receipt').args[2]
    h.context.userId = '43'
    await until(() => !h.state.loading.value)
    await h.state.command('LOCK', { seatNo: 2, value: true })
    assert.equal(signal.aborted, true)
    wait.resolve({ committed: true, ack: { type: 'LOCK', version: '9007199254741002' } })
    await checking
    assert.equal(h.state.pending.value.input.seatNo, 2)
    assert.equal(h.state.success.value, '')
  } finally {
    h.dispose()
  }
})
