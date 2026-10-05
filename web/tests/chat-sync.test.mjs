import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// 运行真实 ChatPanel setup/响应性。替换网络、定时器和 DOM，不声称浏览器双端验收。
const { descriptor } = parse(await readFile(new URL('../src/components/ChatPanel.vue', import.meta.url), 'utf8'))
const code = ts.transpileModule(compileScript(descriptor, { id: 'chat-sync-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const conversation = (id = 'group') => ({
  id,
  title: id,
  kind: 'GROUP',
  ownerId: '42',
  lastSeq: 2,
  members: [{ userId: '42', readSeq: 2, joinedSeq: 0 }],
})
const message = (seq, id = 'group') => ({
  id: `${id}-${seq}`,
  conversationId: id,
  seq,
  clientMessageId: `${id}-client-${seq}`,
  body: `消息${seq}`,
})
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function until(predicate) {
  for (let index = 0; index < 60 && !predicate(); index++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
async function harness(overrides = {}) {
  const calls = [],
    expired = [],
    mounted = [],
    unmount = [],
    sockets = [],
    timers = new Map(),
    listeners = new Map()
  let timerId = 0
  const network = new Proxy(
    {},
    {
      get(_, name) {
        return (...args) => {
          calls.push({ name, args })
          if (overrides[name]) return overrides[name](...args)
          return Promise.resolve(name === 'list' ? [conversation()] : [])
        }
      },
    },
  )
  class Socket {
    static OPEN = 1
    constructor() {
      this.readyState = 1
      sockets.push(this)
    }
    send(frame) {
      this.sent = JSON.parse(frame)
    }
    close() {
      this.closed = true
    }
    emit(data) {
      this.onmessage?.({ data: JSON.stringify(data) })
    }
  }
  const timer = (callback, ms) => {
    const id = ++timerId
    timers.set(id, { callback, ms })
    return id
  }
  const document = {
    visibilityState: 'hidden',
    addEventListener(name, callback) {
      listeners.set(name, callback)
    },
    removeEventListener(name, callback) {
      if (listeners.get(name) === callback) listeners.delete(name)
    },
  }
  const context = vm.createContext({
    AbortController,
    AbortSignal,
    Error,
    console,
    document,
    window: document,
    WebSocket: Socket,
    setTimeout: timer,
    clearTimeout: (id) => timers.delete(id),
    setInterval: timer,
    clearInterval: (id) => timers.delete(id),
    confirm: () => true,
  })
  const mocks = {
    vue: { ...vue, onMounted: (callback) => mounted.push(callback), onUnmounted: (callback) => unmount.push(callback) },
    '../services/chat': {
      chatApi: network,
      messageUuid: () => 'synthetic-uuid',
      socketUrl: () => 'ws://127.0.0.1/chat',
    },
    '../services/chat-safety': { chatSafetyApi: network },
    '../services/http': { captureUnauthorizedSession: () => () => expired.push('current-connection') },
    './ChatSafetyPanel.vue': { default: {} },
    './ChatHistoryTools.vue': { default: {} },
  }
  const module = new vm.SourceTextModule(code, { context })
  await module.link((name) => {
    const values = mocks[name]
    if (!values) throw new Error(`Unexpected import ${name}`)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const scope = vue.effectScope()
  const state = scope.run(() => module.namespace.default.setup({ userId: '42' }, { expose() {}, emit() {} }))
  const dispose = () => {
    unmount.forEach((callback) => callback())
    scope.stop()
  }
  return { state, calls, expired, mounted, sockets, timers, listeners, document, dispose }
}

test('加载首屏期间收到同步保留一次补拉，不忙循环也不漏掉新消息', async () => {
  const wait = deferred()
  const h = await harness({ history: (_id, query) => (query ? Promise.resolve([message(2)]) : wait.promise) })
  try {
    h.state.conversations.value = [conversation()]
    const selecting = h.state.select(conversation())
    await h.state.sync()
    assert.equal(h.calls.filter((call) => call.name === 'history').length, 1)
    assert.equal(h.state.loading.value, true)
    wait.resolve([message(1)])
    await selecting
    await until(() => h.state.messages.value.length === 2 && !h.state.recovering.value)
    assert.deepEqual(
      Array.from(h.state.messages.value, (item) => item.seq),
      [1, 2],
    )
  } finally {
    h.dispose()
  }
})

test('旧历史加载中收到同步，结束后补拉尾部而不覆盖旧历史', async () => {
  const wait = deferred()
  const h = await harness({
    history: (_id, query) => (query.includes('before') ? wait.promise : Promise.resolve([message(3)])),
  })
  try {
    h.state.conversations.value = [conversation()]
    h.state.activeId.value = 'group'
    h.state.messages.value = [message(2)]
    const older = h.state.older()
    await h.state.sync()
    wait.resolve([message(1)])
    await older
    await until(() => h.state.messages.value.length === 3)
    assert.deepEqual(
      Array.from(h.state.messages.value, (item) => item.seq),
      [1, 2, 3],
    )
  } finally {
    h.dispose()
  }
})

test('二十个并发同步请求合并为正在执行加一次后续读取', async () => {
  const wait = deferred()
  let count = 0
  const h = await harness({ list: () => (++count === 1 ? wait.promise : Promise.resolve([conversation()])) })
  try {
    const first = h.state.sync()
    await Promise.all(Array.from({ length: 20 }, () => h.state.sync()))
    assert.equal(count, 1)
    wait.resolve([conversation()])
    await first
    await until(() => count === 2 && !h.state.recovering.value)
    assert.equal(count, 2)
  } finally {
    h.dispose()
  }
})

test('切换会话取消增量请求，迟到旧消息不能进入新会话', async () => {
  const wait = deferred()
  const h = await harness({
    list: () => Promise.resolve([conversation('old'), conversation('new')]),
    history: (id, query) => (id === 'old' && query ? wait.promise : Promise.resolve([message(1, id)])),
  })
  try {
    await h.state.loadConversations()
    await h.state.select(conversation('old'))
    const sync = h.state.sync()
    await until(() => h.calls.some((call) => call.name === 'history' && call.args[1].includes('after')))
    const oldSignal = h.calls.find((call) => call.name === 'history' && call.args[1].includes('after')).args[2]
    await h.state.select(conversation('new'))
    assert.equal(oldSignal.aborted, true)
    wait.resolve([message(2, 'old')])
    await sync
    await until(() => !h.state.recovering.value)
    assert.equal(h.state.activeId.value, 'new')
    assert.ok(h.state.messages.value.every((item) => item.conversationId === 'new'))
  } finally {
    h.dispose()
  }
})

test('较早会话列表迟到不覆盖更新快照，旧加载状态不能清除新请求', async () => {
  const old = deferred(),
    current = deferred()
  let count = 0
  const h = await harness({ list: () => (++count === 1 ? old.promise : current.promise) })
  try {
    const first = h.state.loadConversations(),
      oldSignal = h.calls[0].args[1]
    const second = h.state.loadConversations()
    assert.equal(oldSignal.aborted, true)
    old.resolve([conversation('old')])
    await first
    assert.equal(h.state.conversationsLoading.value, true)
    assert.equal(h.state.conversations.value.length, 0)
    current.resolve([conversation('new')])
    await second
    assert.equal(h.state.conversations.value[0].id, 'new')
  } finally {
    h.dispose()
  }
})

test('卸载取消会话列表与历史，迟到结果不写回', async () => {
  const list = deferred(),
    history = deferred()
  const h = await harness({ list: () => list.promise, history: () => history.promise })
  h.state.conversations.value = [conversation()]
  const selecting = h.state.select(conversation()),
    loading = h.state.loadConversations()
  const signals = h.calls.filter((call) => ['list', 'history'].includes(call.name)).map((call) => call.args.at(-1))
  h.dispose()
  assert.ok(signals.every((signal) => signal.aborted))
  list.resolve([conversation('late')])
  history.resolve([message(1)])
  await Promise.all([selecting, loading])
  assert.equal(h.state.messages.value.length, 0)
  assert.equal(h.state.conversations.value[0].id, 'group')
})

test('移除成员快照清空私密消息并取消旧历史授权轮次', async () => {
  const h = await harness({ list: () => Promise.resolve([]) })
  try {
    h.state.conversations.value = [conversation()]
    h.state.activeId.value = 'group'
    h.state.messages.value = [message(1)]
    const prior = h.state.historyRequest
    await h.state.sync()
    assert.equal(prior.signal.aborted, true)
    assert.equal(h.state.activeId.value, '')
    assert.equal(h.state.messages.value.length, 0)
    assert.match(h.state.error.value, /移出/)
    assert.equal(h.calls.filter((call) => call.name === 'history').length, 0)
  } finally {
    h.dispose()
  }
})

test('隐藏期间不因焦点事件补拉，恢复可见立即补拉且卸载释放监听和定时器', async () => {
  const h = await harness()
  try {
    await h.mounted[0]()
    assert.equal(h.listeners.size, 2)
    const before = h.calls.filter((call) => call.name === 'list').length
    h.listeners.get('focus')()
    assert.equal(h.calls.filter((call) => call.name === 'list').length, before)
    h.document.visibilityState = 'visible'
    h.listeners.get('visibilitychange')()
    await until(() => !h.state.recovering.value)
    assert.equal(h.calls.filter((call) => call.name === 'list').length, before + 1)
  } finally {
    h.dispose()
  }
  assert.equal(h.listeners.size, 0)
  assert.equal(h.timers.size, 0)
  assert.equal(h.sockets[0].closed, true)
})

test('多个跨节点 SYNC 帧合并，失败保留已有快照并明确反馈', async () => {
  let deny = false
  const h = await harness({
    list: () => (deny ? Promise.reject(new Error('数据库暂不可用')) : Promise.resolve([conversation()])),
  })
  try {
    await h.mounted[0]()
    h.sockets[0].emit({ type: 'SYNC' })
    h.sockets[0].emit({ type: 'SYNC' })
    const scheduled = Array.from(h.timers.values()).filter((timer) => timer.ms === 2000)
    assert.equal(scheduled.length, 1)
    deny = true
    scheduled[0].callback()
    await until(() => !h.state.recovering.value)
    assert.equal(h.state.conversations.value.length, 1)
    assert.match(h.state.syncError.value, /不可用/)
  } finally {
    h.dispose()
  }
})

test('事实同步恢复清除自己的故障提示，但不能吞掉发送或管理错误', async () => {
  let deny = true
  const h = await harness({
    list: () => (deny ? Promise.reject(new Error('同步暂不可用')) : Promise.resolve([conversation()])),
  })
  try {
    h.state.error.value = '原消息提交未确认，请复用原 UUID 重试'
    await h.state.sync()
    assert.match(h.state.syncError.value, /不可用/)
    deny = false
    await h.state.sync()
    assert.equal(h.state.syncError.value, '')
    assert.match(h.state.error.value, /原 UUID/)
  } finally {
    h.dispose()
  }
})

test('Netty 确认登录失效时立即取消在途读取，不继续后台补拉', async () => {
  const wait = deferred()
  const h = await harness({ history: () => wait.promise })
  try {
    h.state.conversations.value = [conversation()]
    h.state.connect()
    const loading = h.state.select(conversation())
    const signal = h.calls.find((call) => call.name === 'history').args[2]
    h.sockets[0].emit({ type: 'ERROR', code: 'AUTH_REQUIRED', message: '登录已过期' })
    assert.equal(signal.aborted, true)
    assert.equal(h.state.reads.signal.aborted, true)
    assert.equal(h.sockets[0].closed, true)
    assert.equal(h.expired.length, 1)
    wait.resolve([message(1)])
    await loading
    await h.state.sync()
    assert.equal(h.state.messages.value.length, 0)
    assert.equal(h.calls.filter((call) => call.name === 'list').length, 0)
  } finally {
    h.dispose()
  }
})

test('READY 恢复仅清连接故障，发送/管理错误保持，旧连接错误不能污染新连接', async () => {
  const h = await harness()
  try {
    h.state.connect()
    const first = h.sockets[0]
    h.state.error.value = '原写入未确认'
    first.onerror()
    assert.match(h.state.connectionError.value, /暂不可用/)
    first.emit({ type: 'READY' })
    assert.equal(h.state.connectionError.value, '')
    assert.equal(h.state.error.value, '原写入未确认')
    h.state.connect()
    const current = h.sockets.at(-1)
    current.emit({ type: 'READY' })
    first.onerror()
    first.emit({ type: 'ERROR', code: 'AUTH_REQUIRED' })
    assert.equal(h.state.connectionError.value, '')
    assert.equal(h.expired.length, 0)
    assert.equal(h.state.connected.value, true)
  } finally {
    h.dispose()
  }
})

test('连接租约过期不注销登录身份，READY清除连接提示但保留原写错误', async () => {
  const h = await harness()
  try {
    h.state.connect()
    const original = { conversationId: 'group', clientMessageId: 'original-uuid', body: '原消息', state: 'sending' }
    h.state.pending.value = [original]
    h.state.error.value = '原写入结果未知'
    h.sockets[0].emit({ type: 'ERROR', code: 'CONNECTION_EXPIRED', message: '连接租约失效' })
    assert.match(h.state.connectionError.value, /租约失效/)
    assert.equal(h.expired.length, 0)
    h.sockets[0].onclose()
    assert.equal(h.state.connected.value, false)
    assert.equal(h.state.pending.value[0].state, 'retry')
    const reconnect = [...h.timers.values()].find((timer) => timer.ms >= 1000 && timer.ms < 1500)
    assert.ok(reconnect, '断开应安排首轮有抖动退避，不注销账号')
    reconnect.callback()
    assert.equal(h.sockets.length, 2)
    h.sockets[1].emit({ type: 'READY' })
    assert.equal(h.state.connectionError.value, '')
    assert.equal(h.state.error.value, '原写入结果未知')
    h.state.transmit(h.state.pending.value[0])
    assert.equal(h.sockets[1].sent.clientMessageId, 'original-uuid')
    assert.equal(h.sockets[1].sent.body, '原消息')
    assert.equal(h.expired.length, 0)
  } finally {
    h.dispose()
  }
})
