import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
import * as pinia from 'pinia'

// 执行真实 TS / SFC 状态逻辑。网络桩不替代真实浏览器、Netty 或生产 API 验收。
const transpile = (source) =>
  ts.transpileModule(source.replaceAll('import.meta.env', '({ VITE_API_BASE: "/koko-api" })'), {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function execute(source, mocks = {}, globals = {}) {
  const context = vm.createContext({
    AbortController,
    AbortSignal,
    Headers,
    FormData,
    Response,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    Error,
    console,
    ...globals,
  })
  const module = new vm.SourceTextModule(transpile(source), { context })
  await module.link((specifier) => {
    const values = mocks[specifier]
    if (!values) throw new Error(`Unconfigured runtime import ${specifier}`)
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [name, value] of Object.entries(values)) this.setExport(name, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}
async function setup(relative, network, props = {}, globals = {}, additionalModules = () => ({})) {
  const { descriptor } = parse(await readFile(new URL(`../src/${relative}`, import.meta.url), 'utf8'))
  const source = compileScript(descriptor, { id: 'workspace-state-test' }).content
  const hooks = [],
    mounted = [],
    events = [],
    calls = []
  const api = new Proxy(network, {
    get(target, key) {
      if (typeof target[key] !== 'function') throw new Error(`Unconfigured API ${String(key)}`)
      return (...args) => {
        calls.push({ name: key, args })
        return target[key](...args)
      }
    },
  })
  const lucideNames =
    source
      .match(/import \{([^}]+)\} from 'lucide-vue-next'/)?.[1]
      .split(',')
      .map((name) => name.trim()) || []
  const modules = {
    vue: {
      ...vue,
      onMounted: (callback) => mounted.push(callback),
      onUnmounted: (callback) => hooks.push(callback),
      onBeforeUnmount: (callback) => hooks.push(callback),
    },
    'lucide-vue-next': Object.fromEntries(lucideNames.map((name) => [name, () => null])),
    '../api': { api },
    '../services/chat': {
      chatApi: api,
      messageUuid: () => 'test-message-uuid',
      socketUrl: () => 'ws://not-used.invalid',
    },
    '../services/chat-safety': { chatSafetyApi: api },
    '../services/http': { captureUnauthorizedSession: () => () => {} },
    '../services/notification-time': await execute(
      await readFile(new URL('../src/services/notification-time.ts', import.meta.url), 'utf8'),
    ),
    './ChatSafetyPanel.vue': { default: {} },
    './ChatHistoryTools.vue': { default: {} },
  }
  const additions = await additionalModules(api, modules.vue)
  const namespace = await execute(
    source,
    { ...modules, ...additions },
    {
      document: { visibilityState: 'visible', addEventListener() {}, removeEventListener() {} },
      window: { addEventListener() {}, removeEventListener() {} },
      ...globals,
    },
  )
  const state = namespace.default.setup(props, { expose() {}, emit: (...event) => events.push(event) })
  return { state, calls, mounted, events, dispose: () => hooks.forEach((callback) => callback()) }
}
const httpSource = await readFile(new URL('../src/services/http.ts', import.meta.url), 'utf8')
const http = (fetch) => execute(httpSource, {}, { fetch })

async function operationsFocusFixture(prepare) {
  const pending = vue.ref(null),
    busy = vue.ref(false)
  const workspace = { pending, busy, prepare: () => prepare(pending), loadAccess() {} }
  const h = await setup(
    'views/OperationsView.vue',
    {},
    { userId: '10' },
    { location: { protocol: 'http:', hostname: '127.0.0.1' } },
    () => ({
      '../services/operations': { operationsApi: {} },
      '../stores/operations-retry': { useOperationsRetryStore: () => ({}) },
      '../composables/outbox-workspace': { useOutboxWorkspace: () => workspace },
    }),
  )
  let focused = 0
  h.state.passwordInput.value = {
    focus: () => {
      focused++
    },
  }
  return { ...h, pending, busy, focused: () => focused }
}
test('运营页面新命令渲染后衔接本人确认焦点', async () => {
  const h = await operationsFocusFixture((pending) => {
    pending.value = { phase: 'prepared', command: { requestId: 'new-request' } }
  })
  try {
    await h.state.prepareReplay()
    assert.equal(h.focused(), 1)
  } finally {
    h.dispose()
  }
})
test('运营准备被校验拒绝时不焦点跳转', async () => {
  const h = await operationsFocusFixture(() => {})
  try {
    await h.state.prepareReplay()
    assert.equal(h.focused(), 0)
  } finally {
    h.dispose()
  }
})
test('已有运营命令不会重新抢回本人确认焦点', async () => {
  const h = await operationsFocusFixture(() => {})
  h.pending.value = { phase: 'prepared', command: { requestId: 'existing-request' } }
  try {
    await h.state.prepareReplay()
    assert.equal(h.focused(), 0)
  } finally {
    h.dispose()
  }
})
test('运营焦点等待渲染时已换命令或进入忙态，不操作旧输入框', async () => {
  for (const change of ['command', 'busy']) {
    const h = await operationsFocusFixture((pending) => {
      pending.value = { phase: 'prepared', command: { requestId: 'old-request' } }
    })
    try {
      const focusing = h.state.prepareReplay()
      if (change === 'command') h.pending.value = { phase: 'prepared', command: { requestId: 'other-request' } }
      else h.busy.value = true
      await focusing
      assert.equal(h.focused(), 0)
    } finally {
      h.dispose()
    }
  }
})

test('请求保留 Cookie / Headers，并返回真正 JSON', async () => {
  let actual
  const module = await http(async (url, options) => {
    actual = { url, options }
    return new Response('{"id":"42"}')
  })
  const result = await module.request('/me', { headers: new Headers({ 'X-Test': 'yes' }) })
  assert.equal(actual.url, '/koko-api/me')
  assert.equal(actual.options.credentials, 'include')
  assert.equal(actual.options.headers.get('X-Test'), 'yes')
  assert.equal(result.id, '42')
})
test('204 和没有 Content-Length 的空 200 均成功，不误报注销失败', async () => {
  for (const response of [new Response(null, { status: 204 }), new Response(''), new Response('   ')]) {
    const module = await http(async () => response)
    assert.equal(await module.request('/logout', { method: 'POST' }), undefined)
  }
})
test('401 与 HTML 503 保留各自 HTTP 状态', async () => {
  for (const [status, body] of [
    [401, '{"message":"会话失效"}'],
    [503, '<html>offline</html>'],
  ]) {
    const module = await http(async () => new Response(body, { status }))
    await assert.rejects(module.request('/me'), (cause) => cause.status === status)
  }
})
test('非 JSON 成功响应不伪造业务对象', async () => {
  const module = await http(async () => new Response('<html>unexpected</html>'))
  await assert.rejects(module.request('/me'), (cause) => cause.status === 200 && /响应格式/.test(cause.message))
})
test('FormData 不手写 multipart boundary', async () => {
  let headers
  const module = await http(async (_url, options) => {
    headers = options.headers
    return new Response('{}')
  })
  await module.request('/images', { method: 'POST', body: new FormData() })
  assert.equal(headers.has('Content-Type'), false)
})
test('请求超时有界且不冒充 401 或自动重试写入', async () => {
  let calls = 0
  const module = await http((_url, options) => {
    calls++
    return new Promise((_resolve, reject) =>
      options.signal.addEventListener('abort', () => reject(new Error('aborted'))),
    )
  })
  await assert.rejects(
    module.request('/write', { method: 'POST', timeoutMs: 15 }),
    (cause) => cause.status === 0 && /可能已提交/.test(cause.message),
  )
  assert.equal(calls, 1)
})
test('页面取消传播到 fetch，成功后解除 Abort 订阅', async () => {
  const controller = new AbortController(),
    wait = deferred()
  let actualSignal
  const module = await http((_url, options) => {
    actualSignal = options.signal
    return wait.promise
  })
  const pending = module.request('/read', { signal: controller.signal })
  controller.abort()
  assert.equal(actualSignal.aborted, true)
  wait.reject(new Error('cancelled'))
  await assert.rejects(pending, /cancelled/)
  const next = new AbortController()
  const success = await http(async (_url, options) => {
    actualSignal = options.signal
    return new Response('{}')
  })
  await success.request('/read', { signal: next.signal })
  next.abort()
  assert.equal(actualSignal.aborted, false)
})

class ApiRequestError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}
const identity = (id) => ({ id, displayName: id, handle: id, email: `${id}@koko.invalid` })
async function authStore(api) {
  pinia.setActivePinia(pinia.createPinia())
  const source = await readFile(new URL('../src/stores/auth.ts', import.meta.url), 'utf8')
  const module = await execute(source, { pinia, '../api': { api, ApiRequestError } })
  return module.useAuthStore()
}
test('仅 401 确认为匿名，网络失败保留会话读取错误', async () => {
  for (const cause of [
    new ApiRequestError('expired', 401),
    new ApiRequestError('offline', 503),
    new Error('network'),
  ]) {
    const store = await authStore({ me: () => Promise.reject(cause) })
    await store.restore()
    assert.equal(store.initialized, true)
    assert.equal(store.user, null)
    assert.equal(!!store.sessionError, cause.status !== 401)
  }
})
test('旧会话恢复晚到不能覆盖新登录用户', async () => {
  const wait = deferred()
  const store = await authStore({ me: () => wait.promise, login: () => Promise.resolve({ user: identity('new') }) })
  const restoring = store.restore()
  await store.login('new', 'unused-test-password')
  wait.resolve(identity('old'))
  await restoring
  assert.equal(store.user.id, 'new')
  assert.equal(store.initialized, true)
})
test('旧 401 晚到不能清除已成功注册身份', async () => {
  const wait = deferred()
  const store = await authStore({
    me: () => wait.promise,
    register: () => Promise.resolve({ user: identity('registered') }),
  })
  const restoring = store.restore()
  await store.register('', '', '', '')
  wait.reject(new ApiRequestError('expired', 401))
  await restoring
  assert.equal(store.user.id, 'registered')
  assert.equal(store.sessionError, '')
})
test('注销失败保留用户；真实成功后才清除', async () => {
  let failed = true
  const store = await authStore({ logout: () => (failed ? Promise.reject(new Error('offline')) : Promise.resolve()) })
  store.user = identity('current')
  await assert.rejects(store.logout(), /offline/)
  assert.equal(store.user.id, 'current')
  failed = false
  await store.logout()
  assert.equal(store.user, null)
})
test('登录失败不会让被取消的首次恢复永久卡在加载状态', async () => {
  const wait = deferred()
  const store = await authStore({ me: () => wait.promise, login: () => Promise.reject(new Error('bad login')) })
  const restoring = store.restore()
  await assert.rejects(store.login('', ''), /bad login/)
  wait.resolve(identity('stale'))
  await restoring
  assert.equal(store.initialized, true)
  assert.equal(store.user, null)
})

test('当前业务 401 清除身份，而旧请求和匿名响应不改变新会话', async () => {
  const store = await authStore({})
  store.user = identity('current')
  store.initialized = true
  store.expireSession('current', store.sessionRevision)
  assert.equal(store.user, null)
  assert.match(store.sessionError, /会话已失效/)
  const expiredRevision = store.sessionRevision
  store.user = identity('new')
  store.sessionRevision++
  store.expireSession('current', expiredRevision)
  store.expireSession(undefined, store.sessionRevision)
  store.expireSession('new', expiredRevision)
  assert.equal(store.user.id, 'new')
})

test('请求观察器使用发起时快照，只通知业务 401，不处理认证或 403/503', async () => {
  let current = 'before',
    captured,
    notifications = []
  const wait = deferred()
  const module = await http(() => wait.promise)
  const dispose = module.observeSession({
    capture: () => current,
    unauthorized: (snapshot) => notifications.push(snapshot),
  })
  const pending = module.request('/notifications')
  current = 'after'
  wait.resolve(new Response('{}', { status: 401 }))
  await assert.rejects(pending, (cause) => cause.status === 401)
  assert.deepEqual(notifications, ['before'])
  dispose()
  for (const [path, status] of [
    ['/auth/login', 401],
    ['/auth/register', 401],
    ['/auth/me', 401],
    ['/notifications', 403],
    ['/notifications', 503],
  ]) {
    const other = await http(async () => new Response('{}', { status }))
    other.observeSession({
      capture: () => {
        captured = 'called'
        return current
      },
      unauthorized: () => {
        throw new Error('Unexpected expiration')
      },
    })
    await assert.rejects(other.request(path), (cause) => cause.status === status)
    if (path.startsWith('/auth/')) assert.equal(captured, undefined)
  }
})

test('解除观察器后不再发送会话失效通知', async () => {
  const module = await http(async () => new Response('{}', { status: 401 }))
  let called = 0
  const dispose = module.observeSession({ capture: () => 'session', unauthorized: () => called++ })
  dispose()
  await assert.rejects(module.request('/chat/conversations'), (cause) => cause.status === 401)
  assert.equal(called, 0)
})

test('WS 失效通知使用连接建立时快照，解除/替换观察器使旧连接通知失效', async () => {
  const module = await http(async () => new Response('{}'))
  let epoch = 'old'
  const notifications = []
  const dispose = module.observeSession({ capture: () => epoch, unauthorized: (value) => notifications.push(value) })
  const expired = module.captureUnauthorizedSession()
  epoch = 'new'
  expired()
  assert.deepEqual(notifications, ['old'])
  dispose()
  expired()
  assert.deepEqual(notifications, ['old'])
  module.observeSession({
    capture: () => 'replacement',
    unauthorized: () => {
      throw new Error('Old subscriber crossed applications')
    },
  })
  expired()
  assert.deepEqual(notifications, ['old'])
})

test('同账号再次登录后旧WS失效不能注销新身份，当前WS失效必须清除身份', async () => {
  const store = await authStore({ login: async () => ({ user: identity('7') }) })
  const module = await http(async () => new Response('{}'))
  module.observeSession({
    capture: () => ({ userId: store.user?.id, revision: store.sessionRevision }),
    unauthorized: (scope) => store.expireSession(scope.userId, scope.revision),
  })
  await store.login('synthetic@example.invalid', 'synthetic-password')
  const oldConnection = module.captureUnauthorizedSession()
  await store.login('synthetic@example.invalid', 'synthetic-password')
  oldConnection()
  assert.equal(store.user.id, '7')
  assert.equal(store.sessionExpired, false)
  module.captureUnauthorizedSession()()
  assert.equal(store.user, null)
  assert.equal(store.sessionExpired, true)
})

test('HTTP 请求开始后解除观察器，迟到业务401不触发已卸载应用', async () => {
  const wait = deferred()
  const module = await http(() => wait.promise)
  let notified = 0
  const dispose = module.observeSession({ capture: () => 'scope', unauthorized: () => notified++ })
  const pending = module.request('/chat/conversations')
  dispose()
  wait.resolve(new Response('{}', { status: 401 }))
  await assert.rejects(pending, (cause) => cause.status === 401)
  assert.equal(notified, 0)
})

const notification = (id, readAt = null) => ({
  id,
  eventType: 'FOLLOW',
  summary: '关注通知',
  createdAt: '2026-10-03T07:00:00',
  readAt,
})
const inboxPage = (items, page = 1, total = items.length) => ({ items, page, total })
const notificationApi = (overrides) => ({
  notifications: () => Promise.resolve(inboxPage([notification('1')])),
  notificationPreference: (type) => Promise.resolve({ eventType: type, enabled: true }),
  markNotificationRead: () => Promise.resolve(),
  saveNotificationPreference: (type, enabled) => Promise.resolve({ eventType: type, enabled }),
  ...overrides,
})
const inbox = (overrides) => setup('views/NotificationCenterView.vue', notificationApi(overrides), { userId: '42' })
test('偏好失败不阻断成功收件箱，未读偏好不伪造默认开启', async () => {
  const h = await inbox({ notificationPreference: () => Promise.reject(new Error('preference offline')) })
  try {
    await Promise.all([h.state.load(), h.state.loadPreferences()])
    assert.equal(h.state.items.value.length, 1)
    assert.equal(h.state.error.value, '')
    assert.match(h.state.preferenceError.value, /offline/)
    assert.equal(h.state.preferences.FOLLOW, undefined)
  } finally {
    h.dispose()
  }
})
test('通知失败不冒充空态，重试成功后保留服务器分页事实', async () => {
  let fail = true
  const h = await inbox({
    notifications: () =>
      fail ? Promise.reject(new Error('inbox offline')) : Promise.resolve(inboxPage([notification('8')], 1, 7)),
  })
  try {
    await h.state.load()
    assert.equal(h.state.page.value, 0)
    assert.match(h.state.error.value, /offline/)
    fail = false
    await h.state.load()
    assert.equal(h.state.total.value, 7)
    assert.equal(h.state.items.value[0].id, '8')
  } finally {
    h.dispose()
  }
})
test('已读重复点击受保护，显示时间从提交后的服务器读取', async () => {
  const wait = deferred(),
    serverReadAt = '2026-10-03T07:30:00'
  let committed = false
  const h = await inbox({
    markNotificationRead: () => wait.promise,
    notifications: () => Promise.resolve(inboxPage([notification('1', committed ? serverReadAt : null)])),
  })
  try {
    await h.state.load()
    const item = h.state.items.value[0]
    const pending = h.state.markRead(item)
    await h.state.markRead(item)
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 1)
    committed = true
    wait.resolve()
    await pending
    assert.equal(h.state.items.value[0].readAt, serverReadAt)
  } finally {
    h.dispose()
  }
})
test('已读已提交但读取失败明确提示，不编造 readAt', async () => {
  let committed = false
  const h = await inbox({
    markNotificationRead: () => {
      committed = true
      return Promise.resolve()
    },
    notifications: () =>
      committed ? Promise.reject(new Error('refresh offline')) : Promise.resolve(inboxPage([notification('1')])),
  })
  try {
    await h.state.load()
    await h.state.markRead(h.state.items.value[0])
    assert.match(h.state.error.value, /已读已提交/)
    assert.equal(h.state.items.value[0].readAt, null)
  } finally {
    h.dispose()
  }
})
test('偏好写入失败不乐观篡改服务器状态', async () => {
  const h = await inbox({ saveNotificationPreference: () => Promise.reject(new Error('rejected')) })
  try {
    await h.state.loadPreferences()
    await h.state.togglePreference('FOLLOW')
    assert.equal(h.state.preferences.FOLLOW, true)
    assert.match(h.state.preferenceError.value, /rejected/)
  } finally {
    h.dispose()
  }
})

test('偏好写入结果未知时不重复切换，读取实际值后按新事实切换', async () => {
  let enabled = true,
    failReply = true
  const h = await inbox({
    notificationPreference: (type) => Promise.resolve({ eventType: type, enabled: type === 'FOLLOW' ? enabled : true }),
    saveNotificationPreference: (type, value) => {
      enabled = value
      return failReply ? Promise.reject(new Error('reply lost')) : Promise.resolve({ eventType: type, enabled: value })
    },
  })
  try {
    await h.state.loadPreferences()
    await h.state.togglePreference('FOLLOW')
    assert.equal(enabled, false)
    assert.equal(h.state.preferences.FOLLOW, true)
    assert.equal(h.state.uncertainPreferences.FOLLOW, true)
    assert.match(h.state.preferenceError.value, /保存结果未确认/)
    await h.state.togglePreference('FOLLOW')
    assert.equal(h.calls.filter((call) => call.name === 'saveNotificationPreference').length, 1)
    await h.state.loadPreferences()
    assert.equal(h.state.preferences.FOLLOW, false)
    assert.equal(h.state.hasUncertainPreference.value, false)
    failReply = false
    await h.state.togglePreference('FOLLOW')
    assert.equal(enabled, true)
    assert.equal(h.state.preferences.FOLLOW, true)
    assert.match(h.state.preferenceSuccess.value, /新关注通知已开启/)
  } finally {
    h.dispose()
  }
})

test('其他偏好保存成功不能吞掉旧的待核实状态或核实入口', async () => {
  const h = await inbox({
    saveNotificationPreference: (type, enabled) =>
      type === 'FOLLOW' ? Promise.reject(new Error('reply lost')) : Promise.resolve({ eventType: type, enabled }),
  })
  try {
    await h.state.loadPreferences()
    await h.state.togglePreference('FOLLOW')
    await h.state.togglePreference('COMMENT')
    assert.equal(h.state.preferences.COMMENT, false)
    assert.equal(h.state.uncertainPreferences.FOLLOW, true)
    assert.equal(h.state.hasUncertainPreference.value, true)
    await h.state.togglePreference('FOLLOW')
    assert.equal(h.calls.filter((call) => call.name === 'saveNotificationPreference').length, 2)
  } finally {
    h.dispose()
  }
})

test('偏好部分读取失败或错类型响应不得局部覆盖或清除未知写入', async () => {
  let mode = 'valid'
  const h = await inbox({
    notificationPreference: (type) => {
      if (mode === 'offline' && type === 'LIVE_STARTED') return Promise.reject(new Error('offline'))
      return Promise.resolve({ eventType: mode === 'wrong' ? 'FOLLOW' : type, enabled: mode === 'valid' })
    },
    saveNotificationPreference: () => Promise.reject(new Error('reply lost')),
  })
  try {
    await h.state.loadPreferences()
    await h.state.togglePreference('FOLLOW')
    for (const next of ['offline', 'wrong']) {
      mode = next
      await h.state.loadPreferences()
      assert.equal(h.state.preferences.FOLLOW, true)
      assert.equal(h.state.preferences.COMMENT, true)
      assert.equal(h.state.hasUncertainPreference.value, true)
      assert.ok(h.state.preferenceError.value)
    }
  } finally {
    h.dispose()
  }
})

test('偏好保存错类型或错误状态不冒充确认成功', async () => {
  for (const reply of [{ eventType: 'COMMENT', enabled: false }, { eventType: 'FOLLOW', enabled: true }, undefined]) {
    const h = await inbox({ saveNotificationPreference: () => Promise.resolve(reply) })
    try {
      await h.state.loadPreferences()
      await h.state.togglePreference('FOLLOW')
      assert.equal(h.state.preferences.FOLLOW, true)
      assert.equal(h.state.uncertainPreferences.FOLLOW, true)
      assert.equal(h.state.preferenceSuccess.value, '')
    } finally {
      h.dispose()
    }
  }
})

test('偏好保存防重复且不阻断收件箱读取和标记已读', async () => {
  const wait = deferred()
  const h = await inbox({ saveNotificationPreference: () => wait.promise })
  try {
    await h.state.loadPreferences()
    const saving = h.state.togglePreference('FOLLOW')
    await h.state.togglePreference('FOLLOW')
    await h.state.loadPreferences()
    assert.equal(h.calls.filter((call) => call.name === 'saveNotificationPreference').length, 1)
    assert.equal(h.calls.filter((call) => call.name === 'notificationPreference').length, 3)
    assert.equal(h.state.preferenceSaving.value, 'FOLLOW')
    await h.state.load()
    await h.state.markRead(h.state.items.value[0])
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 1)
    wait.resolve({ eventType: 'FOLLOW', enabled: false })
    await saving
    assert.equal(h.state.preferenceSaving.value, null)
    assert.match(h.state.success.value, /已读状态已提交/)
    assert.match(h.state.preferenceSuccess.value, /已关闭/)
  } finally {
    h.dispose()
  }
})

test('已读等待不阻断偏好读取和保存', async () => {
  const wait = deferred()
  const h = await inbox({ markNotificationRead: () => wait.promise })
  try {
    await h.state.load()
    const marking = h.state.markRead(h.state.items.value[0])
    await h.state.loadPreferences()
    await h.state.togglePreference('COMMENT')
    assert.equal(h.state.preferences.COMMENT, false)
    assert.equal(h.state.busy.value, true)
    wait.resolve()
    await marking
  } finally {
    h.dispose()
  }
})

test('偏好保存卸载取消等待且忽略迟到回复，不宣称服务器回滚', async () => {
  const wait = deferred()
  const h = await inbox({ saveNotificationPreference: () => wait.promise })
  await h.state.loadPreferences()
  const saving = h.state.togglePreference('FOLLOW')
  const signal = h.calls.find((call) => call.name === 'saveNotificationPreference').args[2]
  h.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve({ eventType: 'FOLLOW', enabled: false })
  await saving
  assert.equal(h.state.preferences.FOLLOW, true)
  assert.equal(h.state.preferenceSuccess.value, '')
})

test('通知分页失败的重试保留列表并重新请求失败页', async () => {
  let fail = true
  const h = await inbox({
    notifications: (page) =>
      page === 2 && fail
        ? Promise.reject(new Error('page offline'))
        : Promise.resolve(inboxPage([notification(String(page))], page, 3)),
  })
  try {
    await h.state.load()
    await h.state.load(false)
    assert.equal(h.state.page.value, 1)
    assert.equal(h.state.items.value[0].id, '1')
    fail = false
    await h.state.retryLoad()
    assert.deepEqual(
      h.calls.filter((call) => call.name === 'notifications').map((call) => call.args[0]),
      [1, 2, 2],
    )
    assert.equal(h.state.page.value, 2)
    assert.equal(h.state.items.value.length, 2)
    assert.equal(h.state.error.value, '')
  } finally {
    h.dispose()
  }
})

test('错误分页响应不覆盖已确认列表或推进页码', async () => {
  for (const reply of [
    inboxPage([notification('wrong-page')], 1, 3),
    inboxPage([notification('wrong-total')], 2, -1),
    inboxPage([{ ...notification('wrong-type'), eventType: 'UNKNOWN' }], 2, 3),
    inboxPage([notification(42)], 2, 3),
    inboxPage(
      Array.from({ length: 21 }, (_, index) => notification(String(index))),
      2,
      50,
    ),
    undefined,
  ]) {
    const h = await inbox({
      notifications: (page) => Promise.resolve(page === 1 ? inboxPage([notification('1')], 1, 3) : reply),
    })
    try {
      await h.state.load()
      await h.state.load(false)
      assert.equal(h.state.page.value, 1)
      assert.equal(h.state.items.value[0].id, '1')
      assert.equal(h.state.total.value, 3)
      assert.match(h.state.error.value, /契约/)
    } finally {
      h.dispose()
    }
  }
})

test('标记已读只操作当前真实列表的未读项，旧按钮不得重复写已读项', async () => {
  let readAt = null
  const h = await inbox({ notifications: () => Promise.resolve(inboxPage([notification('1', readAt)])) })
  try {
    await h.state.load()
    const oldItem = h.state.items.value[0]
    await h.state.markRead(notification('not-present'))
    readAt = '2026-10-07T23:00:00'
    await h.state.load()
    await h.state.markRead(oldItem)
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 0)
  } finally {
    h.dispose()
  }
})

test('已读网络失败提示结果未知，重新读取只查事实不重发写入', async () => {
  let readAt = null
  const h = await inbox({
    notifications: () => Promise.resolve(inboxPage([notification('1', readAt)])),
    markNotificationRead: () => {
      readAt = '2026-10-07T23:00:00'
      return Promise.reject(new Error('reply lost'))
    },
  })
  try {
    await h.state.load()
    await h.state.markRead(h.state.items.value[0])
    assert.match(h.state.error.value, /结果未确认/)
    assert.equal(h.state.items.value[0].readAt, null)
    await h.state.retryLoad()
    assert.equal(h.state.items.value[0].readAt, readAt)
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 1)
  } finally {
    h.dispose()
  }
})
test('已读回应丢失锁定原ID，重复点击或只读取到未读都不重发写入', async () => {
  let readAt = null
  const h = await inbox({
    notifications: () => Promise.resolve(inboxPage([notification('1', readAt)])),
    markNotificationRead: () => Promise.reject(new Error('reply lost')),
  })
  try {
    await h.state.load()
    const target = h.state.items.value[0]
    await h.state.markRead(target)
    await h.state.markRead(target)
    await h.state.load()
    await h.state.markRead(target)
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 1)
    assert.equal(h.state.uncertainReadIds.has('1'), true)
    readAt = '2026-10-12T08:00:00.123456'
    await h.state.load()
    assert.equal(h.state.uncertainReadIds.has('1'), false)
    assert.equal(h.state.items.value[0].readAt, readAt)
  } finally {
    h.dispose()
  }
})

test('已读204后刷新失败也保留门禁，错误已读日期或未见目标不能解锁', async () => {
  let mode = 'initial'
  const h = await inbox({
    notifications: () =>
      mode === 'offline'
        ? Promise.reject(new Error('offline'))
        : Promise.resolve(
            inboxPage(mode === 'absent' ? [] : [notification('1', mode === 'bad' ? 'not-a-date' : null)]),
          ),
    markNotificationRead: () => {
      mode = 'offline'
      return Promise.resolve()
    },
  })
  try {
    await h.state.load()
    const target = h.state.items.value[0]
    await h.state.markRead(target)
    await h.state.markRead(target)
    assert.equal(h.calls.filter((call) => call.name === 'markNotificationRead').length, 1)
    for (const next of ['bad', 'absent']) {
      mode = next
      await h.state.load()
      assert.equal(h.state.uncertainReadIds.has('1'), true)
    }
  } finally {
    h.dispose()
  }
})

test('未读筛选只作用于已加载集合，不把加载条数或筛选数当全量', async () => {
  const h = await inbox({
    notifications: () =>
      Promise.resolve(inboxPage([notification('1'), notification('2', '2026-10-12T08:00:00')], 1, 30)),
  })
  try {
    await h.state.load()
    assert.equal(h.state.loadedUnread.value, 1)
    h.state.unreadOnly.value = true
    assert.deepEqual(
      Array.from(h.state.visibleItems.value, (item) => item.id),
      ['1'],
    )
    assert.equal(h.state.total.value, 30)
    h.state.unreadOnly.value = false
    assert.equal(h.state.visibleItems.value.length, 2)
  } finally {
    h.dispose()
  }
})

test('通知非法ID和时间响应被拒绝，未核验数据不进入列表', async () => {
  for (const patch of [
    { id: '../preferences/FOLLOW' },
    { id: '9223372036854775808' },
    { id: '0' },
    { createdAt: '2026-02-30T00:00:00' },
    { readAt: 'invalid' },
    { summary: null },
  ]) {
    const h = await inbox({ notifications: () => Promise.resolve(inboxPage([{ ...notification('1'), ...patch }])) })
    try {
      await h.state.load()
      assert.equal(h.state.items.value.length, 0)
      assert.ok(h.state.error.value)
    } finally {
      h.dispose()
    }
  }
})

test('通知事实读取使用no-store，保留调用者取消信号', async () => {
  const requests = []
  const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8')
  const module = await execute(source, {
    './services/http': {
      apiBaseUrl: '/api',
      ApiRequestError,
      request: async (path, options) => requests.push({ path, options }),
    },
  })
  const signal = new AbortController().signal
  await module.api.notifications(2, 20, signal)
  await module.api.notificationPreference('COMMENT', signal)
  for (const request of requests) {
    assert.equal(request.options.cache, 'no-store')
    assert.equal(request.options.signal, signal)
  }
})

test('通知分页去重且未改变已读，不把分页条数当全量', async () => {
  const h = await inbox({
    notifications: (page) =>
      Promise.resolve(
        inboxPage(
          page === 1
            ? [notification('1', '2026-10-07T23:00:00')]
            : [notification('1', '2026-10-07T23:00:00'), notification('2')],
          page,
          9,
        ),
      ),
  })
  try {
    await h.state.load()
    await h.state.load(false)
    assert.equal(h.state.items.value.length, 2)
    assert.equal(h.state.total.value, 9)
    assert.equal(h.state.items.value[0].readAt, '2026-10-07T23:00:00')
  } finally {
    h.dispose()
  }
})
test('离开通知页取消请求且忽略卸载后的迟到成功', async () => {
  const wait = deferred(),
    h = await inbox({ notifications: () => wait.promise })
  const pending = h.state.load(),
    signal = h.calls[0].args[2]
  h.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve(inboxPage([notification('late')]))
  await pending
  assert.equal(h.state.items.value.length, 0)
})

test('页面具有唯一 URL，房主工作台与其他私有领域不能标成公开发现页', async () => {
  const source = await readFile(new URL('../src/router/pages.ts', import.meta.url), 'utf8')
  const { workspacePages, unknownPage } = await execute(source)
  assert.equal(workspacePages.length, 15)
  assert.equal(new Set(workspacePages.map((page) => page.path)).size, 15)
  assert.equal(new Set(workspacePages.map((page) => page.name)).size, 15)
  assert.equal(workspacePages.find((page) => page.name === 'voice-owner').requiresAuth, true)
  assert.equal(workspacePages.find((page) => page.name === 'binding-operations').requiresAuth, true)
  assert.equal(workspacePages.find((page) => page.name === 'operations').requiresAuth, true)
  const detail = workspacePages.find((page) => page.name === 'public-creator')
  assert.equal(detail.requiresAuth, false)
  assert.equal(detail.hidden, true)
  assert.equal(detail.path, '/creators/:slug')
  for (const page of workspacePages) assert.equal(page.requiresAuth, !page.section && page.name !== 'public-creator')
  assert.equal(unknownPage.name, 'not-found')
})
const conversation = {
  id: 'room',
  title: '会话',
  kind: 'DIRECT',
  ownerId: '42',
  lastSeq: 0,
  members: [{ userId: '42', readSeq: 0 }],
}
test('移动返回会话使旧历史响应失效，并清除旧收件人的输入草稿', async () => {
  const wait = deferred(),
    h = await setup('components/ChatPanel.vue', { history: () => wait.promise }, { userId: '42', embedded: true })
  try {
    h.state.conversations.value = [conversation]
    const pending = h.state.select(conversation)
    h.state.body.value = '只写给原会话的文字'
    h.state.backToConversations()
    wait.resolve([{ id: 'late', seq: 1 }])
    await pending
    assert.equal(h.state.activeId.value, '')
    assert.equal(h.state.messages.value.length, 0)
    assert.equal(h.state.body.value, '')
    assert.equal(h.state.loading.value, false)
  } finally {
    h.dispose()
  }
})
test('路由卸载关闭 Netty socket、取消所有已发送消息等待定时器', async () => {
  class FakeSocket {
    static OPEN = 1
    constructor() {
      this.readyState = 1
      FakeSocket.instance = this
    }
    close() {
      this.closed = true
    }
  }
  const h = await setup('components/ChatPanel.vue', {}, { userId: '42', embedded: true }, { WebSocket: FakeSocket })
  h.state.connect()
  assert.equal(FakeSocket.instance.closed, undefined)
  h.dispose()
  assert.equal(FakeSocket.instance.closed, true)
  assert.equal(h.state.safetyReads.signal.aborted, true)
  assert.equal(h.state.bookmarkWrites.signal.aborted, true)
})

const catalog = await execute(await readFile(new URL('../src/router/pages.ts', import.meta.url), 'utf8'))
const snapshotApi = (overrides) => ({
  communities: () => Promise.resolve([]),
  liveRooms: () => Promise.resolve([]),
  voiceRooms: () => Promise.resolve([]),
  joinVoiceRoom: () => {
    throw new Error('工作台非语音测试不允许入房')
  },
  creatorPage: () => Promise.resolve({ items: [], page: 1, total: 0 }),
  postPage: () => Promise.resolve({ items: [], page: 1, total: 0 }),
  myPosts: () => Promise.resolve([]),
  myCreatorProfile: () => Promise.reject(new ApiRequestError('profile missing', 404)),
  ...overrides,
})
async function app(overrides = {}, authOverrides = {}, globals = {}) {
  const auth = vue.reactive({ user: null, initialized: true, sessionRevision: 1, sessionError: '', ...authOverrides })
  const route = vue.reactive({ name: 'explore', fullPath: '/explore' })
  const windowStub = { addEventListener() {}, removeEventListener() {}, confirm: () => true, ...globals.window }
  const beforeNavigation = [],
    afterNavigation = []
  const register = (list, callback) => {
    list.push(callback)
    return () => {
      const index = list.indexOf(callback)
      if (index >= 0) list.splice(index, 1)
    }
  }
  const navigation = {
    beforeEach: (callback) => register(beforeNavigation, callback),
    afterEach: (callback) => register(afterNavigation, callback),
    async push(path) {
      const to = { name: catalog.workspacePages.find((page) => page.path === path)?.name, fullPath: path },
        from = { ...route }
      for (const guard of beforeNavigation)
        if ((await guard(to, from)) === false) {
          const failure = { type: 'aborted' }
          for (const after of afterNavigation) after(to, from, failure)
          return failure
        }
      route.name = to.name
      route.fullPath = path
      for (const guard of afterNavigation) guard(to, from, undefined)
      return undefined
    },
  }
  const h = await setup(
    'App.vue',
    snapshotApi(overrides),
    {},
    { ...globals, window: windowStub },
    async (api, runtimeVue) => {
      const editorModule = await execute(
        await readFile(new URL('../src/composables/creator-editor-writes.ts', import.meta.url), 'utf8'),
        {
          vue: runtimeVue,
          '../api': { api, ApiRequestError },
        },
      )
      const voiceModule = await execute(
        await readFile(new URL('../src/composables/voice-connection.ts', import.meta.url), 'utf8'),
        {
          vue: runtimeVue,
          '../api': { ApiRequestError },
        },
      )
      const editorGuardModule = await execute(
        await readFile(new URL('../src/composables/unsaved-editor-guard.ts', import.meta.url), 'utf8'),
        { vue: runtimeVue },
        { window: windowStub },
      )
      const contentActionsModule = await execute(
        await readFile(new URL('../src/composables/creator-content-actions.ts', import.meta.url), 'utf8'),
        { vue: runtimeVue, '../api': { api, ApiRequestError } },
      )
      return {
        './composables/voice-connection': voiceModule,
        './composables/creator-editor-writes': editorModule,
        './composables/unsaved-editor-guard': editorGuardModule,
        './composables/creator-content-actions': contentActionsModule,
        './api': { api, ApiRequestError, managedImageUrl: (id) => `/images/${id}` },
        './stores/auth': { useAuthStore: () => auth },
        './router/pages': { workspacePages: catalog.workspacePages, unknownPage: catalog.unknownPage },
        'vue-router': {
          RouterLink: {},
          RouterView: {},
          useRoute: () => route,
          useRouter: () => navigation,
        },
        './composables/dialog-accessibility': { useDialogAccessibility() {} },
        './components/ManagedImagePicker.vue': { default: {} },
        './components/WorkspaceShell.vue': { default: {} },
        './components/CommunityMembershipPanel.vue': { default: {} },
        './components/VoiceInteractionPanel.vue': { default: {} },
        './components/VoicePlaybackControls.vue': { default: {} },
        './components/PersistentVoiceCallBar.vue': { default: {} },
        './components/CreatorSaveStatus.vue': { default: {} },
      }
    },
  )
  return { ...h, auth, route, navigation, beforeNavigation, afterNavigation }
}

const editorPost = (overrides = {}) => ({
  id: '100',
  ownerId: '42',
  slug: 'article-one',
  title: '真实夹具草稿',
  excerpt: '',
  body: '完整本人正文',
  coverUrl: '',
  coverAssetId: '',
  status: 'DRAFT',
  version: 1,
  kind: 'ARTICLE',
  likeCount: 0,
  commentCount: 0,
  favoriteCount: 0,
  ...overrides,
})
test('编辑器本人重新读取携带取消器并禁用浏览器缓存，不把旧缓存当核实事实', async () => {
  const calls = []
  const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8')
  const module = await execute(source, {
    './services/http': {
      apiBaseUrl: '/koko-api',
      ApiRequestError,
      request: async (path, options) => {
        calls.push({ path, options })
      },
    },
  })
  const signal = new AbortController().signal
  await module.api.myPosts(signal)
  await module.api.myCreatorProfile(signal)
  assert.deepEqual(
    calls.map((call) => call.path),
    ['/content/posts/mine', '/creators/me'],
  )
  for (const call of calls) {
    assert.equal(call.options.signal, signal)
    assert.equal(call.options.cache, 'no-store')
  }
})
const editorProfile = (overrides = {}) => ({
  userId: '42',
  slug: 'synthetic-creator',
  displayName: '合成创作者',
  headline: '',
  bio: '本人介绍',
  avatarUrl: '',
  bannerUrl: '',
  avatarAssetId: '',
  bannerAssetId: '',
  status: 'DRAFT',
  version: 3,
  followerCount: 0,
  ...overrides,
})
const readerApi = (overrides = {}) => ({
  post: (slug) => Promise.resolve(editorPost({ id: slug === 'beta' ? '200' : '100', slug, status: 'PUBLISHED' })),
  postCommentsPage: (_slug, page) => Promise.resolve({ items: [], page, total: 0 }),
  postEngagement: () => Promise.resolve({ likeCount: 0, commentCount: 0, likedByMe: false }),
  postFavoriteState: () => Promise.resolve({ favoriteCount: 0, favoritedByMe: false }),
  ...overrides,
})
async function reader(overrides = {}) {
  const h = await app(readerApi(overrides), { user: identity('42') })
  await h.state.readPost({ slug: 'alpha' })
  return h
}
async function settleUntil(predicate) {
  for (let i = 0; i < 20 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
const readerComment = (overrides = {}) => ({
  id: '1001',
  postId: '100',
  userId: '42',
  body: '本人评论',
  version: 0,
  createdAt: '2026-10-07T23:00:00',
  ...overrides,
})

test('本人关系读取未完成或失败不能用默认false发送点赞/收藏，核验后才放行', async () => {
  const wait = deferred()
  const h = await app(readerApi({ postEngagement: () => wait.promise }), { user: identity('42') })
  try {
    const reading = h.state.readPost({ slug: 'alpha' })
    await settleUntil(() => h.state.readerRelationsLoading.value)
    await h.state.togglePostLike()
    await h.state.togglePostFavorite()
    assert.equal(h.calls.filter((call) => ['likePost', 'favoritePost'].includes(call.name)).length, 0)
    wait.reject(new Error('relation offline'))
    await reading
    assert.equal(h.state.readerRelationsReady.value, false)
    assert.match(h.state.readerRelationsError.value, /offline/)
    await h.state.togglePostLike()
    assert.equal(h.calls.filter((call) => call.name === 'likePost').length, 0)
  } finally {
    h.dispose()
  }
})

test('点赞重复调用与并行收藏都被在途门禁阻止，成功仅更新当前文章', async () => {
  const wait = deferred(),
    h = await reader({ likePost: () => wait.promise })
  try {
    const saving = h.state.togglePostLike()
    await h.state.togglePostLike()
    await h.state.togglePostFavorite()
    assert.equal(h.calls.filter((call) => call.name === 'likePost').length, 1)
    assert.equal(h.calls.filter((call) => call.name === 'favoritePost').length, 0)
    wait.resolve({ likeCount: 1, commentCount: 0, likedByMe: true })
    await saving
    assert.equal(h.state.selectedPost.value.likeCount, 1)
    assert.equal(h.state.interactionSubmitting.value, false)
  } finally {
    h.dispose()
  }
})

test('切文章后旧点赞回复不更新新文章或解除新在途写入', async () => {
  const old = deferred(),
    fresh = deferred()
  const h = await reader({ likePost: (id) => (id === '100' ? old.promise : fresh.promise) })
  try {
    const first = h.state.togglePostLike()
    await h.state.readPost({ slug: 'beta' })
    const second = h.state.togglePostLike()
    old.resolve({ likeCount: 99, commentCount: 0, likedByMe: true })
    await first
    assert.equal(h.state.interactionSubmitting.value, true)
    assert.equal(h.state.selectedPost.value.id, '200')
    assert.equal(h.state.selectedPost.value.likeCount, 0)
    fresh.resolve({ likeCount: 1, commentCount: 0, likedByMe: true })
    await second
    assert.equal(h.state.selectedPost.value.likeCount, 1)
  } finally {
    h.dispose()
  }
})

test('旧账号收藏失败不得回填新账号错误或清除新忙态', async () => {
  const old = deferred(),
    fresh = deferred()
  let first = true
  const h = await reader({ favoritePost: () => (first ? ((first = false), old.promise) : fresh.promise) })
  try {
    const saving = h.state.togglePostFavorite()
    h.auth.user = identity('43')
    h.auth.sessionRevision++
    await vue.nextTick()
    await h.state.readPost({ slug: 'beta' })
    const newSaving = h.state.togglePostFavorite()
    old.reject(new Error('old private error'))
    await saving
    assert.equal(h.state.readerRelationsError.value, '')
    assert.equal(h.state.interactionSubmitting.value, true)
    fresh.resolve({ favoriteCount: 1, favoritedByMe: true })
    await newSaving
    assert.equal(h.state.postFavorite.value.favoritedByMe, true)
  } finally {
    h.dispose()
  }
})

test('收藏结果未知不盲反向切换，只读核验实际状态后才能继续', async () => {
  let favorited = false
  const h = await reader({
    favoritePost: () => {
      favorited = true
      return Promise.reject(new Error('reply lost'))
    },
    postFavoriteState: () => Promise.resolve({ favoriteCount: favorited ? 1 : 0, favoritedByMe: favorited }),
  })
  try {
    await h.state.togglePostFavorite()
    await h.state.togglePostFavorite()
    assert.equal(h.calls.filter((call) => call.name === 'favoritePost').length, 1)
    assert.equal(h.state.readerRelationsReady.value, false)
    await h.state.refreshPostRelations()
    assert.equal(h.state.readerRelationsReady.value, true)
    assert.equal(h.state.postFavorite.value.favoritedByMe, true)
    assert.equal(h.calls.filter((call) => call.name === 'unfavoritePost').length, 0)
  } finally {
    h.dispose()
  }
})

test('评论回复丢失保留原文，列表同文字不等于提交收据，也不自动解锁重发', async () => {
  let hasComment = false
  const h = await reader({
    commentPost: () => {
      hasComment = true
      return Promise.reject(new Error('reply lost'))
    },
    postCommentsPage: () =>
      Promise.resolve({ items: hasComment ? [readerComment()] : [], page: 1, total: hasComment ? 1 : 0 }),
  })
  try {
    h.state.commentBody.value = '本人评论'
    await h.state.submitComment()
    await h.state.submitComment()
    assert.equal(h.state.commentWriteUnconfirmed.value, true)
    assert.equal(h.state.commentBody.value, '本人评论')
    assert.equal(h.calls.filter((call) => call.name === 'commentPost').length, 1)
    await h.state.refreshReaderComments()
    await h.state.submitComment()
    assert.equal(h.state.postComments.value[0].body, '本人评论')
    assert.equal(h.state.commentWriteUnconfirmed.value, true)
    assert.equal(h.calls.filter((call) => call.name === 'commentPost').length, 1)
  } finally {
    h.dispose()
  }
})

test('评论在途捕获原文，成功仅清除仍相同的输入，不覆盖程序晚更新的草稿', async () => {
  const wait = deferred(),
    h = await reader({ commentPost: () => wait.promise })
  try {
    h.state.commentBody.value = '本人评论'
    const saving = h.state.submitComment()
    await h.state.submitComment()
    h.state.commentBody.value = '另外的新评论'
    wait.resolve(readerComment())
    await saving
    assert.equal(h.calls.filter((call) => call.name === 'commentPost').length, 1)
    assert.equal(h.state.postComments.value[0].body, '本人评论')
    assert.equal(h.state.commentBody.value, '另外的新评论')
  } finally {
    h.dispose()
  }
})

test('错误评论归属回复不插入列表或清空原文', async () => {
  const h = await reader({ commentPost: () => Promise.resolve(readerComment({ userId: '99' })) })
  try {
    h.state.commentBody.value = '本人评论'
    await h.state.submitComment()
    assert.equal(h.state.postComments.value.length, 0)
    assert.equal(h.state.commentBody.value, '本人评论')
    assert.equal(h.state.commentWriteUnconfirmed.value, true)
  } finally {
    h.dispose()
  }
})

test('删除核对当前列表和当前权限，旧文章删除回复不能删新文章评论', async () => {
  const wait = deferred(),
    h = await reader({ deletePostComment: () => wait.promise })
  try {
    await h.state.deleteComment(readerComment())
    assert.equal(h.calls.filter((call) => call.name === 'deletePostComment').length, 0)
    h.state.postComments.value = [readerComment()]
    const deleting = h.state.deleteComment(h.state.postComments.value[0])
    await h.state.readPost({ slug: 'beta' })
    h.state.postComments.value = [readerComment({ postId: '200' })]
    wait.resolve()
    await deleting
    assert.equal(h.state.postComments.value.length, 1)
  } finally {
    h.dispose()
  }
})

test('新关系读取替换旧请求，迟到故障不覆盖已核验事实或清除新加载状态', async () => {
  const old = deferred()
  let call = 0
  const h = await reader({
    postEngagement: () =>
      ++call === 2 ? old.promise : Promise.resolve({ likeCount: 2, commentCount: 0, likedByMe: true }),
  })
  try {
    const first = h.state.refreshPostRelations()
    const signal = h.calls.filter((call) => call.name === 'postEngagement').at(-1).args[1]
    await h.state.refreshPostRelations()
    assert.equal(signal.aborted, true)
    old.reject(new Error('old relation error'))
    await first
    assert.equal(h.state.readerRelationsError.value, '')
    assert.equal(h.state.readerRelationsReady.value, true)
    assert.equal(h.state.postEngagement.value.likedByMe, true)
  } finally {
    h.dispose()
  }
})

test('登录轮次改变清除旧评论输入和私人关系，迟到评论不重新展示', async () => {
  const wait = deferred(),
    h = await reader({ commentPost: () => wait.promise })
  try {
    h.state.commentBody.value = '本人评论'
    const saving = h.state.submitComment()
    h.auth.sessionRevision++
    assert.equal(h.state.commentBody.value, '')
    assert.equal(h.state.readerRelationsReady.value, false)
    wait.resolve(readerComment())
    await saving
    assert.equal(h.state.postComments.value.length, 0)
    assert.equal(h.state.interactionSubmitting.value, false)
  } finally {
    h.dispose()
  }
})
function fillEditor(h) {
  h.state.newPostDraft()
  Object.assign(h.state.postEdit, { slug: 'article-one', title: '真实夹具草稿', body: '完整本人正文' })
}

function guardWindow() {
  const listeners = new Map(),
    questions = []
  let consent = false
  return {
    listeners,
    questions,
    consent(value) {
      consent = value
    },
    window: {
      addEventListener(type, callback) {
        listeners.set(type, callback)
      },
      removeEventListener(type, callback) {
        if (listeners.get(type) === callback) listeners.delete(type)
      },
      confirm(text) {
        questions.push(text)
        return consent
      },
    },
  }
}

test('文章脏检查包含正文与封面，输入回退到原值解除警告，只读版本变化不误报', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    h.state.newPostDraft()
    assert.equal(h.state.editorDirty.value, false)
    h.state.postEdit.body = '未保存正文'
    assert.equal(h.state.editorDirty.value, true)
    assert.equal(browser.listeners.has('beforeunload'), true)
    h.state.postEdit.body = ''
    assert.equal(h.state.editorDirty.value, false)
    assert.equal(browser.listeners.has('beforeunload'), false)
    h.state.postEdit.version++
    assert.equal(h.state.editorDirty.value, false)
    h.state.postEdit.coverAssetId = 'synthetic-asset'
    assert.equal(h.state.editorDirty.value, true)
  } finally {
    h.dispose()
  }
})

test('保存确认更新脏检查基线，关闭不再多问但后续修改仍会提醒', async () => {
  const browser = guardWindow(),
    h = await app(
      { createPost: () => Promise.resolve(editorPost()) },
      { user: identity('42') },
      { window: browser.window },
    )
  try {
    fillEditor(h)
    assert.equal(h.state.editorDirty.value, true)
    await h.state.savePost(false)
    assert.equal(h.state.editorDirty.value, false)
    assert.equal(browser.listeners.has('beforeunload'), false)
    h.state.postEdit.title = '之后的未保存修改'
    h.state.closeDialog()
    assert.equal(h.state.dialog.value, 'post')
    assert.equal(browser.questions.length, 1)
    h.state.postEdit.title = '真实夹具草稿'
    h.state.closeDialog()
    assert.equal(h.state.dialog.value, null)
    assert.equal(browser.questions.length, 1)
  } finally {
    h.dispose()
  }
})

test('已读取主页基线排除粉丝数但包含简介和两类图片', async () => {
  const browser = guardWindow(),
    h = await app(
      { myCreatorProfile: () => Promise.resolve(editorProfile()) },
      { user: identity('42') },
      { window: browser.window },
    )
  try {
    await h.state.openCreatorStudio()
    assert.equal(h.state.editorDirty.value, false)
    h.state.creatorProfile.followerCount++
    assert.equal(h.state.editorDirty.value, false)
    h.state.creatorProfile.bio = '新简介'
    assert.equal(h.state.editorDirty.value, true)
    h.state.creatorProfile.bio = '本人介绍'
    h.state.creatorProfile.bannerAssetId = 'synthetic-banner'
    assert.equal(h.state.editorDirty.value, true)
    h.state.creatorProfile.bannerAssetId = ''
    h.state.creatorProfile.avatarAssetId = 'synthetic-avatar'
    assert.equal(h.state.editorDirty.value, true)
  } finally {
    h.dispose()
  }
})

test('拒绝关闭未保存编辑器保留内容，允许关闭不偷偷发送保存或发布', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    fillEditor(h)
    h.state.closeDialog()
    assert.equal(h.state.dialog.value, 'post')
    assert.equal(h.state.postEdit.body, '完整本人正文')
    assert.match(browser.questions[0], /尚未保存/)
    browser.consent(true)
    h.state.closeDialog()
    assert.equal(h.state.dialog.value, null)
    assert.equal(h.calls.filter((call) => ['createPost', 'updatePost', 'publishPost'].includes(call.name)).length, 0)
  } finally {
    h.dispose()
  }
})

test('取消新建/切换文章/打开主页，都不覆盖原未保存表单', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    fillEditor(h)
    h.state.newPostDraft()
    h.state.editPost(editorPost({ title: '别的内容' }))
    await h.state.openCreatorStudio()
    assert.equal(h.state.dialog.value, 'post')
    assert.equal(h.state.postEdit.body, '完整本人正文')
    assert.equal(browser.questions.length, 3)
    assert.equal(h.calls.length, 0)
  } finally {
    h.dispose()
  }
})

test('未保存导航取消保持路由与表单；成功导航才关闭并解除卸载监听', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    fillEditor(h)
    const failure = await h.navigation.push('/voice')
    assert.ok(failure)
    assert.equal(h.route.fullPath, '/explore')
    assert.equal(h.state.dialog.value, 'post')
    browser.consent(true)
    await h.navigation.push('/voice')
    assert.equal(h.route.fullPath, '/voice')
    assert.equal(h.state.dialog.value, null)
    assert.equal(browser.listeners.has('beforeunload'), false)
  } finally {
    h.dispose()
  }
})

test('全局新建文章等待导航成功，不在守卫取消前覆盖旧表单且不会双重确认', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    h.route.name = 'voice'
    h.route.fullPath = '/voice'
    fillEditor(h)
    await h.state.workspaceAction('post')
    assert.equal(h.route.fullPath, '/voice')
    assert.equal(h.state.postEdit.body, '完整本人正文')
    assert.equal(browser.questions.length, 1)
    browser.consent(true)
    await h.state.workspaceAction('post')
    assert.equal(h.route.fullPath, '/studio')
    assert.equal(h.state.dialog.value, 'post')
    assert.equal(h.state.postEdit.body, '')
    assert.equal(browser.questions.length, 2)
    assert.equal(h.state.editorDirty.value, false)
  } finally {
    h.dispose()
  }
})

test('相同URL不提示或关闭表单；后续守卫拒绝时不能提前清除脏检查', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    fillEditor(h)
    await h.navigation.push('/explore')
    assert.equal(browser.questions.length, 0)
    assert.equal(h.state.dialog.value, 'post')
    browser.consent(true)
    const remove = h.navigation.beforeEach(() => false)
    await h.navigation.push('/voice')
    assert.equal(h.state.editorDirty.value, true)
    assert.equal(h.state.dialog.value, 'post')
    remove()
    browser.consent(false)
    await h.navigation.push('/voice')
    assert.equal(h.route.fullPath, '/explore')
    assert.equal(browser.questions.length, 2)
  } finally {
    h.dispose()
  }
})

test('在途保存或上传阻止路由离开，不弹放弃确认，也不会重发写入', async () => {
  const browser = guardWindow(),
    wait = deferred(),
    h = await app({ createPost: () => wait.promise }, { user: identity('42') }, { window: browser.window })
  try {
    fillEditor(h)
    const saving = h.state.savePost(false)
    assert.equal(h.state.editorGuard.shouldWarn.value, true)
    await h.navigation.push('/voice')
    h.state.closeDialog()
    assert.equal(browser.questions.length, 0)
    assert.equal(h.state.dialog.value, 'post')
    wait.resolve(editorPost())
    await saving
    h.state.imageUploading.value = true
    await h.navigation.push('/voice')
    assert.equal(h.route.fullPath, '/explore')
    assert.equal(h.calls.filter((call) => call.name === 'createPost').length, 1)
  } finally {
    h.dispose()
  }
})

test('未知提交优先解释核实信息，确认不会被误表述成单纯未保存', async () => {
  const browser = guardWindow(),
    h = await app(
      { createPost: () => Promise.reject(new Error('reply lost')) },
      { user: identity('42') },
      { window: browser.window },
    )
  try {
    fillEditor(h)
    await h.state.savePost(false)
    h.state.closeDialog()
    assert.match(browser.questions[0], /仍未核实/)
    assert.match(browser.questions[0], /不会撤回/)
    assert.ok(h.state.editorRecovery.value)
    assert.equal(h.state.dialog.value, 'post')
  } finally {
    h.dispose()
  }
})

test('beforeunload只设置浏览器原生提醒，不发送请求；撤销修改后解绑', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  try {
    h.state.newPostDraft()
    h.state.postEdit.title = '本地私有文字'
    let prevented = false
    const event = {
      returnValue: 'initial',
      preventDefault() {
        prevented = true
      },
    }
    browser.listeners.get('beforeunload')(event)
    assert.equal(prevented, true)
    assert.equal(event.returnValue, '')
    assert.equal(h.calls.length, 0)
    h.state.postEdit.title = ''
    assert.equal(browser.listeners.has('beforeunload'), false)
  } finally {
    h.dispose()
  }
})

test('退出登录先确认未保存修改，取消不调用注销；成功注销清除旧正文和提醒', async () => {
  const browser = guardWindow()
  let logouts = 0
  const h = await app(
    {},
    {
      user: identity('42'),
      async logout() {
        logouts++
        this.user = null
        this.sessionRevision++
      },
    },
    { window: browser.window },
  )
  try {
    fillEditor(h)
    await h.state.logout()
    assert.equal(logouts, 0)
    browser.consent(true)
    await h.state.logout()
    assert.equal(logouts, 1)
    assert.equal(h.state.postEdit.body, '')
    assert.equal(h.state.dialog.value, null)
    assert.equal(browser.listeners.has('beforeunload'), false)
  } finally {
    h.dispose()
  }
})

test('账号/同账号登录轮次强制变化不被未保存确认拦住，立即清理私有编辑器', async () => {
  for (const accountChange of [true, false]) {
    const browser = guardWindow(),
      h = await app({}, { user: identity('42') }, { window: browser.window })
    try {
      fillEditor(h)
      if (accountChange) h.auth.user = null
      else h.auth.sessionRevision++
      assert.equal(h.state.dialog.value, null)
      assert.equal(h.state.postEdit.body, '')
      assert.equal(browser.questions.length, 0)
      assert.equal(browser.listeners.has('beforeunload'), false)
    } finally {
      h.dispose()
    }
  }
})

test('编辑器守卫卸载清除beforeunload及两个路由钩子，不保留旧基线监听', async () => {
  const browser = guardWindow(),
    h = await app({}, { user: identity('42') }, { window: browser.window })
  fillEditor(h)
  assert.equal(browser.listeners.has('beforeunload'), true)
  assert.equal(h.beforeNavigation.length, 1)
  assert.equal(h.afterNavigation.length, 1)
  h.dispose()
  assert.equal(browser.listeners.has('beforeunload'), false)
  assert.equal(h.beforeNavigation.length, 0)
  assert.equal(h.afterNavigation.length, 0)
})

test('对话框焦点排除fieldset继承禁用的控件，全禁用时Tab保留在对话框根节点', async () => {
  const hooks = [],
    listeners = new Map()
  let targets = []
  const document = {
    activeElement: null,
    querySelectorAll: () => [root],
    addEventListener(type, handler) {
      listeners.set(type, handler)
    },
    removeEventListener(type, handler) {
      if (listeners.get(type) === handler) listeners.delete(type)
    },
  }
  class Element {
    constructor(disabled = false) {
      this.disabled = disabled
      this.isConnected = true
    }
    matches() {
      return this.disabled
    }
    hasAttribute() {
      return false
    }
    getClientRects() {
      return [1]
    }
    focus() {
      if (!this.disabled) document.activeElement = this
    }
    contains(element) {
      return element === this || targets.includes(element)
    }
    querySelectorAll() {
      return targets
    }
  }
  const root = new Element(),
    inheritedDisabled = new Element(true),
    enabled = new Element()
  targets = [inheritedDisabled, enabled]
  document.activeElement = new Element()
  const namespace = await execute(
    await readFile(new URL('../src/composables/dialog-accessibility.ts', import.meta.url), 'utf8'),
    { vue: { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) } },
    { document, HTMLElement: Element },
  )
  namespace.useDialogAccessibility(
    vue.ref(true),
    () => {},
    () => true,
  )
  await vue.nextTick()
  // VM内异步watch跨上下文恢复需要额外微任务轮次；保持对实际焦点目标的严格断言。
  for (let attempt = 0; attempt < 10 && document.activeElement !== enabled; attempt++)
    await new Promise((resolve) => setImmediate(resolve))
  assert.equal(document.activeElement, enabled)
  targets = [inheritedDisabled]
  let prevented = false
  listeners.get('keydown')({
    key: 'Tab',
    shiftKey: false,
    preventDefault() {
      prevented = true
    },
  })
  assert.equal(prevented, true)
  assert.equal(document.activeElement, root)
  hooks.forEach((hook) => hook())
  assert.equal(listeners.has('keydown'), false)
})

test('草稿已保存但发布失败保留ID/版本并锁定盲重试，只读核实后不重新创建', async () => {
  let published = false
  const h = await app(
    {
      createPost: () => Promise.resolve(editorPost()),
      publishPost: () => Promise.reject(new Error('publish reply lost')),
      myPosts: () =>
        Promise.resolve([editorPost({ status: published ? 'PUBLISHED' : 'DRAFT', version: published ? 2 : 1 })]),
    },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    await h.state.savePost(true)
    assert.equal(h.state.postEdit.id, '100')
    assert.equal(h.state.postEdit.version, 1)
    assert.equal(h.state.ownedPosts.value[0].id, '100')
    assert.match(h.state.editorSuccess.value, /保存已确认/)
    assert.match(h.state.editorError.value, /发布.*未确认/)
    await h.state.savePost(true)
    assert.equal(h.calls.filter((call) => call.name === 'createPost').length, 1)
    assert.equal(h.calls.filter((call) => call.name === 'publishPost').length, 1)
    published = true
    await h.state.creatorEditor.recheck()
    assert.equal(h.state.editorRecovery.value, null)
    assert.equal(h.state.postEdit.status, 'PUBLISHED')
    assert.equal(h.state.postEdit.version, 2)
    assert.equal(h.calls.filter((call) => call.name === 'publishPost').length, 1)
  } finally {
    h.dispose()
  }
})

test('创建回复丢失按原slug查询，查无记录不证明回滚，不解锁重复创建', async () => {
  let found = false
  const h = await app(
    {
      createPost: () => Promise.reject(new Error('reply lost')),
      myPosts: () => Promise.resolve(found ? [editorPost()] : []),
    },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    await h.state.savePost(false)
    await h.state.creatorEditor.recheck()
    assert.match(h.state.editorError.value, /不证明原写入已回滚/)
    assert.ok(h.state.editorRecovery.value)
    h.state.postEdit.slug = 'changed-after-failure'
    await h.state.savePost(false)
    assert.equal(h.calls.filter((call) => call.name === 'createPost').length, 1)
    found = true
    await h.state.creatorEditor.recheck()
    assert.equal(h.state.postEdit.id, '100')
    assert.equal(h.state.postEdit.slug, 'article-one')
  } finally {
    h.dispose()
  }
})

test('发布失败核实到仍为草稿，下一次明确保存沿用服务端ID和当前版本', async () => {
  let fail = true
  const h = await app(
    {
      createPost: () => Promise.resolve(editorPost()),
      publishPost: () =>
        fail ? Promise.reject(new Error('offline')) : Promise.resolve(editorPost({ status: 'PUBLISHED', version: 5 })),
      myPosts: () => Promise.resolve([editorPost({ version: 3 })]),
      updatePost: () => Promise.resolve(editorPost({ version: 4 })),
    },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    await h.state.savePost(true)
    await h.state.creatorEditor.recheck()
    fail = false
    await h.state.savePost(true)
    const update = h.calls.find((call) => call.name === 'updatePost')
    assert.equal(update.args[0], '100')
    assert.equal(update.args[1].version, 3)
    assert.equal(h.calls.filter((call) => call.name === 'createPost').length, 1)
    assert.equal(h.state.postEdit.status, 'PUBLISHED')
  } finally {
    h.dispose()
  }
})

test('错误所有者响应或不完整私人正文不进入编辑器，也不继续发布', async () => {
  for (const reply of [
    editorPost({ ownerId: '99' }),
    editorPost({ body: undefined }),
    editorPost({ version: -1 }),
    editorPost({ id: '1/other' }),
    editorPost({ id: '9223372036854775808' }),
    editorPost({ slug: 'unrelated-content' }),
  ]) {
    const h = await app({ createPost: () => Promise.resolve(reply) }, { user: identity('42') })
    try {
      fillEditor(h)
      await h.state.savePost(true)
      assert.equal(h.state.postEdit.id, '')
      assert.equal(h.state.ownedPosts.value.length, 0)
      assert.equal(h.calls.filter((call) => call.name === 'publishPost').length, 0)
      assert.ok(h.state.editorRecovery.value)
    } finally {
      h.dispose()
    }
  }
})

test('旧账号保存迟到不发布或回填，新账号在途操作不被旧finally解除', async () => {
  const old = deferred(),
    fresh = deferred()
  let first = true
  const h = await app(
    { createPost: () => (first ? ((first = false), old.promise) : fresh.promise) },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    const saving = h.state.savePost(true)
    h.auth.user = identity('43')
    h.auth.sessionRevision++
    await vue.nextTick()
    assert.equal(h.state.postEdit.body, '')
    fillEditor(h)
    const newSaving = h.state.savePost(false)
    old.resolve(editorPost())
    await saving
    assert.equal(h.state.submitting.value, true)
    assert.equal(h.state.postEdit.id, '')
    assert.equal(h.calls.filter((call) => call.name === 'publishPost').length, 0)
    fresh.resolve(editorPost({ id: '200', ownerId: '43' }))
    await newSaving
    assert.equal(h.state.postEdit.id, '200')
  } finally {
    h.dispose()
  }
})

test('同弹窗换表单隔离旧结果，即使账号没有变化也不继续发布', async () => {
  const wait = deferred()
  const h = await app({ createPost: () => wait.promise }, { user: identity('42') })
  try {
    fillEditor(h)
    const saving = h.state.savePost(true)
    h.state.editorFormRevision.value++
    h.state.postEdit.title = '新表单标题'
    wait.resolve(editorPost())
    await saving
    assert.equal(h.state.postEdit.title, '新表单标题')
    assert.equal(h.calls.filter((call) => call.name === 'publishPost').length, 0)
  } finally {
    h.dispose()
  }
})

test('主页初始读取未完成或故障时不使用默认版本保存', async () => {
  const wait = deferred()
  const h = await app({ myCreatorProfile: () => wait.promise }, { user: identity('42') })
  try {
    const reading = h.state.openCreatorStudio()
    await h.state.saveCreatorProfile(false)
    assert.equal(h.calls.filter((call) => call.name === 'saveCreatorProfile').length, 0)
    wait.reject(new Error('profile unavailable'))
    await reading
    assert.equal(h.state.creatorProfileLoaded.value, false)
    await h.state.saveCreatorProfile(false)
    assert.equal(h.calls.filter((call) => call.name === 'saveCreatorProfile').length, 0)
  } finally {
    h.dispose()
  }
})

test('主页保存确认但发布回复丢失，保留新版本并只读核实当前发布事实', async () => {
  let active = false
  const h = await app(
    {
      myCreatorProfile: () =>
        Promise.resolve(editorProfile({ status: active ? 'ACTIVE' : 'DRAFT', version: active ? 5 : 3 })),
      saveCreatorProfile: () => Promise.resolve(editorProfile({ version: 4 })),
      publishCreatorProfile: () => Promise.reject(new Error('reply lost')),
    },
    { user: identity('42') },
  )
  try {
    await h.state.openCreatorStudio()
    await h.state.saveCreatorProfile(true)
    assert.equal(h.state.creatorProfile.version, 4)
    assert.equal(h.state.studioProfile.value.version, 4)
    assert.equal(h.state.editorRecovery.value.kind, 'creator')
    await h.state.saveCreatorProfile(true)
    assert.equal(h.calls.filter((call) => call.name === 'saveCreatorProfile').length, 1)
    active = true
    await h.state.creatorEditor.recheck()
    assert.equal(h.state.creatorProfile.status, 'ACTIVE')
    assert.equal(h.state.creatorProfile.version, 5)
    assert.equal(h.calls.filter((call) => call.name === 'publishCreatorProfile').length, 1)
  } finally {
    h.dispose()
  }
})

test('同账号重登录废弃旧主页保存结果，不以新Cookie继续发布', async () => {
  const wait = deferred()
  const h = await app(
    { myCreatorProfile: () => Promise.resolve(editorProfile()), saveCreatorProfile: () => wait.promise },
    { user: identity('42') },
  )
  try {
    await h.state.openCreatorStudio()
    const saving = h.state.saveCreatorProfile(true)
    h.auth.sessionRevision++
    await vue.nextTick()
    wait.resolve(editorProfile({ bio: '旧私有介绍', version: 4 }))
    await saving
    assert.equal(h.state.dialog.value, null)
    assert.notEqual(h.state.creatorProfile.bio, '旧私有介绍')
    assert.equal(h.calls.filter((call) => call.name === 'publishCreatorProfile').length, 0)
  } finally {
    h.dispose()
  }
})

test('明确400校验拒绝不伪造提交未知；用户可以修正表单后重新保存', async () => {
  let invalid = true
  const h = await app(
    {
      createPost: () =>
        invalid ? Promise.reject(new ApiRequestError('title invalid', 400)) : Promise.resolve(editorPost()),
    },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    await h.state.savePost(false)
    assert.equal(h.state.editorRecovery.value, null)
    assert.match(h.state.editorError.value, /保存被拒绝/)
    invalid = false
    await h.state.savePost(false)
    assert.equal(h.state.postEdit.id, '100')
  } finally {
    h.dispose()
  }
})

test('只读核实错归属或缺完整正文仍锁定未知结果，不能覆盖当前表单', async () => {
  const h = await app(
    {
      createPost: () => Promise.reject(new Error('reply lost')),
      myPosts: () => Promise.resolve([editorPost({ body: undefined })]),
    },
    { user: identity('42') },
  )
  try {
    fillEditor(h)
    await h.state.savePost(false)
    await h.state.creatorEditor.recheck()
    assert.equal(h.state.postEdit.body, '完整本人正文')
    assert.ok(h.state.editorRecovery.value)
    assert.match(h.state.editorError.value, /核实未完成/)
  } finally {
    h.dispose()
  }
})

test('用户取消覆盖和关闭未知编辑器，不读取、不重复写、不丢弃核实信息', async () => {
  const questions = []
  const h = await app(
    { createPost: () => Promise.reject(new Error('reply lost')) },
    { user: identity('42') },
    {
      window: {
        confirm: (text) => {
          questions.push(text)
          return false
        },
      },
    },
  )
  try {
    fillEditor(h)
    await h.state.savePost(false)
    h.state.recheckCreatorSubmission()
    h.state.closeDialog()
    assert.equal(questions.length, 2)
    assert.equal(h.state.dialog.value, 'post')
    assert.ok(h.state.editorRecovery.value)
    assert.equal(h.calls.filter((call) => call.name === 'myPosts').length, 0)
    assert.equal(h.calls.filter((call) => call.name === 'createPost').length, 1)
  } finally {
    h.dispose()
  }
})

test('同账号登录轮次变化重建通知页面，运营命令页面保持原恢复生命周期', async () => {
  const h = await app({}, { user: identity('42') })
  try {
    h.route.name = 'notifications'
    const previous = h.state.workspaceViewKey.value
    h.auth.sessionRevision++
    assert.notEqual(h.state.workspaceViewKey.value, previous)
    h.route.name = 'operations'
    const operationsKey = h.state.workspaceViewKey.value
    h.auth.sessionRevision++
    assert.equal(h.state.workspaceViewKey.value, operationsKey)
  } finally {
    h.dispose()
  }
})

test('语音建房确认后刷新房主工作台，重复提交不发送第二个请求', async () => {
  const wait = deferred()
  const h = await app({ createVoiceRoom: () => wait.promise }, { user: identity('42') })
  try {
    h.state.dialog.value = 'voice'
    await vue.nextTick()
    const pending = h.state.createVoiceRoom()
    await h.state.createVoiceRoom()
    assert.equal(h.calls.filter((call) => call.name === 'createVoiceRoom').length, 1)
    wait.resolve({ id: 'created', status: 'OPEN' })
    await pending
    assert.equal(h.state.voiceRooms.value[0].id, 'created')
    assert.equal(h.state.voiceOwnerRefreshRevision.value, 1)
    assert.equal(h.state.dialog.value, null)
  } finally {
    h.dispose()
  }
})

test('旧语音建房回复不能关闭新账号的表单或污染新账号房间', async () => {
  const wait = deferred()
  const h = await app({ createVoiceRoom: () => wait.promise }, { user: identity('42') })
  try {
    h.state.dialog.value = 'voice'
    await vue.nextTick()
    const pending = h.state.createVoiceRoom()
    h.auth.sessionRevision++
    h.auth.user = identity('43')
    await vue.nextTick()
    h.state.dialog.value = 'voice'
    h.state.formError.value = '新表单错误'
    await vue.nextTick()
    wait.resolve({ id: 'stale', status: 'OPEN' })
    await pending
    assert.equal(h.state.voiceRooms.value.length, 0)
    assert.equal(h.state.voiceOwnerRefreshRevision.value, 0)
    assert.equal(h.state.dialog.value, 'voice')
    assert.equal(h.state.formError.value, '新表单错误')
  } finally {
    h.dispose()
  }
})
test('本人工作台在会话尚未恢复时不调用私有 API', async () => {
  const h = await app({}, { user: identity('42'), initialized: false })
  try {
    await h.state.loadStudio()
    assert.equal(h.calls.length, 0)
  } finally {
    h.dispose()
  }
})
test('语音服务500不隐藏成功内容，分区重试只读取失败领域', async () => {
  let voiceFails = true
  const h = await app({
    communities: () => Promise.resolve([{ id: 'community' }]),
    voiceRooms: () => (voiceFails ? Promise.reject(new ApiRequestError('语音服务暂不可用', 500)) : Promise.resolve([])),
    creatorPage: () => Promise.resolve({ items: [{ userId: 'creator' }], page: 1, total: 1 }),
    postPage: () => Promise.resolve({ items: [{ id: 'post' }], page: 1, total: 1 }),
  })
  try {
    await h.state.loadDiscovery()
    assert.equal(h.state.discoveryStates.voice.status, 'error')
    assert.equal(h.state.discoveryStates.voice.error, '语音服务暂不可用')
    assert.equal(h.state.discoveryStates.voice.loadedAt, null)
    assert.equal(h.state.discoveryStates.communities.status, 'ready')
    assert.equal(h.state.communities.value[0].id, 'community')
    assert.equal(h.state.creators.value[0].userId, 'creator')
    assert.equal(h.state.posts.value[0].id, 'post')
    const contentTime = h.state.discoveryStates.posts.loadedAt
    const initialCalls = h.calls.length
    voiceFails = false
    await h.state.loadDiscovery('voice')
    assert.deepEqual(
      h.calls.slice(initialCalls).map((call) => call.name),
      ['voiceRooms'],
    )
    assert.equal(h.state.discoveryStates.voice.status, 'ready')
    assert.equal(h.state.discoveryStates.voice.error, '')
    assert.equal(h.state.discoveryStates.posts.loadedAt, contentTime)
  } finally {
    h.dispose()
  }
})

test('语音读取缓慢时成功内容立即可见且仍可分页', async () => {
  const pendingVoice = deferred()
  const h = await app({
    voiceRooms: () => pendingVoice.promise,
    postPage: (page) => Promise.resolve({ items: [{ id: `post-${page}` }], page, total: 2 }),
  })
  try {
    const loading = h.state.loadDiscovery()
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(h.state.discoveryStates.voice.status, 'loading')
    assert.equal(h.state.discoveryStates.posts.status, 'ready')
    assert.equal(h.state.posts.value[0].id, 'post-1')
    await h.state.loadMore('posts')
    assert.equal(h.state.posts.value[1].id, 'post-2')
    pendingVoice.resolve([])
    await loading
  } finally {
    h.dispose()
  }
})

test('旧分区错误不能覆盖单独重试后成功的快照', async () => {
  const pendingVoice = deferred()
  let first = true
  const h = await app({
    voiceRooms: () => (first ? ((first = false), pendingVoice.promise) : Promise.resolve([{ id: 'current' }])),
  })
  try {
    const stale = h.state.loadDiscovery()
    const signal = h.calls.find((call) => call.name === 'voiceRooms').args[0]
    await h.state.loadDiscovery('voice')
    assert.equal(signal.aborted, true)
    pendingVoice.reject(new ApiRequestError('旧请求失败', 500))
    await stale
    assert.equal(h.state.discoveryStates.voice.status, 'ready')
    assert.equal(h.state.discoveryStates.voice.error, '')
    assert.equal(h.state.voiceRooms.value[0].id, 'current')
    assert.equal(h.state.discoveryStates.posts.status, 'ready')
  } finally {
    h.dispose()
  }
})

test('公开刷新轮次取消旧快照，迟到响应不能倒退页面', async () => {
  const wait = deferred()
  let first = true
  const h = await app({
    communities: () => (first ? ((first = false), wait.promise) : Promise.resolve([{ id: 'current' }])),
  })
  try {
    const stale = h.state.loadDiscovery(),
      signal = h.calls[0].args[0]
    await h.state.loadDiscovery()
    assert.equal(signal.aborted, true)
    wait.resolve([{ id: 'stale' }])
    await stale
    assert.equal(h.state.communities.value[0].id, 'current')
    assert.equal(h.state.loading.value, false)
  } finally {
    h.dispose()
  }
})
test('刷新与旧分页竞争不能把旧文章追加到新快照', async () => {
  const wait = deferred()
  const h = await app({
    postPage: (page) => (page === 2 ? wait.promise : Promise.resolve({ items: [{ id: 'fresh' }], page: 1, total: 3 })),
  })
  try {
    await h.state.loadDiscovery()
    const stale = h.state.loadMore('posts')
    await h.state.loadDiscovery()
    wait.resolve({ items: [{ id: 'stale' }], page: 2, total: 3 })
    await stale
    assert.equal(h.state.posts.value.length, 1)
    assert.equal(h.state.posts.value[0].id, 'fresh')
    assert.equal(h.state.pageLoading.value, false)
  } finally {
    h.dispose()
  }
})
test('离开本人工作台使私有读取轮次失效，不能把迟到草稿写回', async () => {
  const wait = deferred(),
    h = await app({ myPosts: () => wait.promise }, { user: identity('42') })
  try {
    const stale = h.state.loadStudio(),
      signal = h.calls.find((call) => call.name === 'myPosts').args[0]
    h.route.name = 'live'
    await vue.nextTick()
    assert.equal(signal.aborted, true)
    wait.resolve([{ id: 'private-stale' }])
    await stale
    assert.equal(h.state.ownedPosts.value.length, 0)
    assert.equal(h.state.studioLoading.value, false)
  } finally {
    h.dispose()
  }
})
test('个人主页 404 与服务故障区分，失败不伪装未建立主页', async () => {
  const h = await app(
    { myCreatorProfile: () => Promise.reject(new ApiRequestError('database offline', 503)) },
    { user: identity('42') },
  )
  try {
    await h.state.loadStudio()
    assert.match(h.state.studioError.value, /offline/)
  } finally {
    h.dispose()
  }
})
test('旧 UP 主资料晚到不能覆盖另一个账号的表单', async () => {
  const wait = deferred(),
    h = await app({ myCreatorProfile: () => wait.promise }, { user: identity('42') })
  try {
    const pending = h.state.openCreatorStudio()
    h.auth.user = identity('99')
    await vue.nextTick()
    wait.resolve({ userId: '42', bio: 'previous private bio' })
    await pending
    assert.equal(h.state.creatorProfile.userId, '99')
    assert.equal(h.state.creatorProfile.bio, '')
    assert.equal(h.state.dialog.value, null)
  } finally {
    h.dispose()
  }
})
test('切换文章使旧正文请求失效，不显示错文章', async () => {
  const wait = deferred()
  const h = await app({
    post: (slug) =>
      slug === 'old'
        ? wait.promise
        : Promise.resolve({ id: 'new', slug: 'new', likeCount: 0, commentCount: 0, favoriteCount: 0 }),
    postCommentsPage: () => Promise.resolve({ items: [], page: 1, total: 0 }),
  })
  try {
    const pending = h.state.readPost({ slug: 'old' })
    await h.state.readPost({ slug: 'new' })
    wait.resolve({ id: 'old', slug: 'old' })
    await pending
    assert.equal(h.state.selectedPost.value.id, 'new')
  } finally {
    h.dispose()
  }
})
test('切换本人收藏/关注列表，旧集合不能写回新弹窗', async () => {
  const wait = deferred(),
    h = await app(
      { favorites: () => wait.promise, following: () => Promise.resolve({ items: [{ userId: 'new' }], total: 1 }) },
      { user: identity('42') },
    )
  try {
    const pending = h.state.openCollection('favorites')
    await h.state.openCollection('following')
    wait.resolve({ items: [{ id: 'late-favorite' }], total: 1 })
    await pending
    assert.equal(h.state.favoritePosts.value.length, 0)
    assert.equal(h.state.followedCreators.value[0].userId, 'new')
    assert.equal(h.state.collectionLoading.value, false)
  } finally {
    h.dispose()
  }
})
