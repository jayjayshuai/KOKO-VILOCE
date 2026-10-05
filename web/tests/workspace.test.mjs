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
test('通知分页去重且未改变已读，不把分页条数当全量', async () => {
  const h = await inbox({
    notifications: (page) =>
      Promise.resolve(
        inboxPage(
          page === 1 ? [notification('1', 'server-read')] : [notification('1', 'server-read'), notification('2')],
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
    assert.equal(h.state.items.value[0].readAt, 'server-read')
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

test('十三个页面具有唯一 URL，房主工作台与其他私有领域不能标成公开发现页', async () => {
  const source = await readFile(new URL('../src/router/pages.ts', import.meta.url), 'utf8')
  const { workspacePages, unknownPage } = await execute(source)
  assert.equal(workspacePages.length, 13)
  assert.equal(new Set(workspacePages.map((page) => page.path)).size, 13)
  assert.equal(new Set(workspacePages.map((page) => page.name)).size, 13)
  assert.equal(workspacePages.find((page) => page.name === 'voice-owner').requiresAuth, true)
  assert.equal(workspacePages.find((page) => page.name === 'binding-operations').requiresAuth, true)
  assert.equal(workspacePages.find((page) => page.name === 'operations').requiresAuth, true)
  for (const page of workspacePages) assert.equal(page.requiresAuth, !page.section)
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
async function app(overrides = {}, authOverrides = {}) {
  const auth = vue.reactive({ user: null, initialized: true, sessionRevision: 1, sessionError: '', ...authOverrides })
  const route = vue.reactive({ name: 'explore', fullPath: '/explore' })
  const h = await setup('App.vue', snapshotApi(overrides), {}, {}, async (api, runtimeVue) => {
    const voiceModule = await execute(
      await readFile(new URL('../src/composables/voice-connection.ts', import.meta.url), 'utf8'),
      {
        vue: runtimeVue,
        '../api': { ApiRequestError },
      },
    )
    return {
      './composables/voice-connection': voiceModule,
      './api': { api, ApiRequestError, managedImageUrl: (id) => `/images/${id}` },
      './stores/auth': { useAuthStore: () => auth },
      './router/pages': { workspacePages: catalog.workspacePages, unknownPage: catalog.unknownPage },
      'vue-router': {
        RouterView: {},
        useRoute: () => route,
        useRouter: () => ({
          push: (path) => {
            route.name = catalog.workspacePages.find((page) => page.path === path)?.name
            route.fullPath = path
          },
        }),
      },
      './composables/dialog-accessibility': { useDialogAccessibility() {} },
      './components/ManagedImagePicker.vue': { default: {} },
      './components/WorkspaceShell.vue': { default: {} },
      './components/CommunityMembershipPanel.vue': { default: {} },
      './components/VoiceInteractionPanel.vue': { default: {} },
    }
  })
  return { ...h, auth, route }
}

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
