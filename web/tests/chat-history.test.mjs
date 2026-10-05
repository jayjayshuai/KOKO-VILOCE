import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// 执行真实 SFC setup 与 Vue 响应式逻辑；仅替换网络和卸载钩子，不声称 DOM/布局验收。
const source = await readFile(new URL('../src/components/ChatHistoryTools.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, { id: 'chat-history-test' })
const code = ts.transpileModule(compiled.content, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText

const message = (seq) => ({ id: `message-${seq}`, seq, conversationId: 'conversation-a', body: '正文' })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function harness(overrides = {}, confirm = () => true) {
  const calls = [],
    unmount = [],
    stops = []
  const api = Object.fromEntries(
    ['search', 'bookmarks', 'save', 'unsave', 'clearBookmarks'].map((name) => [
      name,
      (...args) => {
        calls.push({ name, args })
        return overrides[name]?.(...args) ?? Promise.resolve({ items: [], nextBefore: null })
      },
    ]),
  )
  const context = vm.createContext({ AbortController, console, confirm, Error })
  const runtime = {
    ...vue,
    onUnmounted: (callback) => unmount.push(callback),
    watch: (...args) => {
      const stop = vue.watch(...args)
      stops.push(stop)
      return stop
    },
  }
  const vueModule = new vm.SyntheticModule(
    Object.keys(runtime),
    function () {
      for (const [key, value] of Object.entries(runtime)) this.setExport(key, value)
    },
    { context },
  )
  const apiModule = new vm.SyntheticModule(
    ['chatApi'],
    function () {
      this.setExport('chatApi', api)
    },
    { context },
  )
  const module = new vm.SourceTextModule(code, { context })
  await module.link((specifier) => {
    if (specifier === 'vue') return vueModule
    if (specifier === '../services/chat') return apiModule
    throw new Error(`Unexpected import: ${specifier}`)
  })
  await module.evaluate()
  const props = vue.reactive({ conversationId: 'conversation-a', userId: 'owner-a' })
  const state = module.namespace.default.setup(props, { expose() {}, emit() {} })
  const dispose = () => {
    unmount.forEach((callback) => callback())
    stops.forEach((stop) => stop())
  }
  return { state, props, calls, dispose }
}

test('空扫描窗口保留更早游标，分页沿用已提交搜索词', async () => {
  const h = await harness({
    search: (...args) =>
      Promise.resolve(args[2] ? { items: [message(3)], nextBefore: null } : { items: [], nextBefore: 206 }),
  })
  try {
    h.state.query.value = 'needle'
    await h.state.load()
    assert.equal(h.state.searched.value, true)
    assert.equal(h.state.nextBefore.value, 206)
    h.state.query.value = '尚未提交的新词'
    await h.state.load(true)
    assert.equal(h.calls[1].args[1], 'needle')
    assert.equal(h.calls[1].args[2], 206)
    assert.equal(h.state.items.value[0].seq, 3)
  } finally {
    h.dispose()
  }
})

test('切换会话取消旧读取，忽略不遵守取消的迟到响应', async () => {
  const wait = deferred()
  const h = await harness({ search: () => wait.promise })
  try {
    h.state.query.value = 'needle'
    const running = h.state.load()
    const signal = h.calls[0].args[3]
    h.props.conversationId = 'conversation-b'
    await vue.nextTick()
    assert.equal(signal.aborted, true)
    wait.resolve({ items: [message(2)], nextBefore: 2 })
    await running
    assert.equal(h.state.items.value.length, 0)
    assert.equal(h.state.nextBefore.value, null)
  } finally {
    h.dispose()
  }
})

test('读取中禁止取消/清空收藏，避免分页响应撤销本地写入结果', async () => {
  const wait = deferred()
  const h = await harness({ bookmarks: () => wait.promise })
  try {
    h.state.mode.value = 'bookmarks'
    await vue.nextTick()
    assert.equal(h.state.loading.value, true)
    await h.state.save(message(2), true)
    await h.state.clear()
    assert.equal(h.calls.filter((call) => call.name !== 'bookmarks').length, 0)
    wait.resolve({ items: [message(2)], nextBefore: null })
    await new Promise((resolve) => setImmediate(resolve))
    await h.state.save(message(2), true)
    assert.equal(h.state.items.value.length, 0)
    assert.match(h.state.success.value, /已取消/)
  } finally {
    h.dispose()
  }
})

test('失败不移除收藏或展示假成功，写入忙碌拒绝重复提交', async () => {
  const wait = deferred()
  const h = await harness({ unsave: () => wait.promise })
  try {
    h.state.items.value = [message(2)]
    const running = h.state.save(message(2), true)
    await h.state.save(message(2), true)
    assert.equal(h.calls.length, 1)
    wait.reject(new Error('服务暂不可用'))
    await running
    assert.equal(h.state.items.value.length, 1)
    assert.equal(h.state.success.value, '')
    assert.equal(h.state.error.value, '服务暂不可用')
    assert.equal(h.state.busy.value, false)
  } finally {
    h.dispose()
  }
})

test('取消清空确认不请求服务端；确认后仅清空个人收藏视图', async () => {
  let accepted = false
  const h = await harness({}, () => accepted)
  try {
    h.state.items.value = [message(2)]
    await h.state.clear()
    assert.equal(h.calls.length, 0)
    accepted = true
    await h.state.clear()
    assert.equal(h.calls[0].name, 'clearBookmarks')
    assert.equal(h.calls[0].args[0], 'conversation-a')
    assert.equal(h.state.items.value.length, 0)
    assert.match(h.state.success.value, /已清空/)
  } finally {
    h.dispose()
  }
})

test('卸载取消读取，迟到响应不写入已销毁面板', async () => {
  const wait = deferred()
  const h = await harness({ search: () => wait.promise })
  h.state.query.value = 'needle'
  const running = h.state.load()
  h.dispose()
  assert.equal(h.calls[0].args[3].aborted, true)
  wait.resolve({ items: [message(2)], nextBefore: null })
  await running
  assert.equal(h.state.items.value.length, 0)
  assert.equal(h.state.searched.value, false)
})

test('卸载取消写入等待，迟到成功不展示在已销毁面板', async () => {
  const wait = deferred()
  const h = await harness({ save: () => wait.promise })
  const running = h.state.save(message(2))
  h.dispose()
  assert.equal(h.calls[0].args[2].aborted, true)
  wait.resolve()
  await running
  assert.equal(h.state.success.value, '')
})

test('校验无效搜索词，合并分页去重并倒序排列', async () => {
  const h = await harness({
    search: (...args) =>
      Promise.resolve(
        args[2]
          ? { items: [message(2), message(1)], nextBefore: null }
          : { items: [message(2), message(3)], nextBefore: 2 },
      ),
  })
  try {
    h.state.query.value = 'x'
    await h.state.load()
    assert.equal(h.calls.length, 0)
    h.state.query.value = 'needle'
    await h.state.load()
    await h.state.load(true)
    assert.deepEqual(
      Array.from(h.state.items.value, (item) => item.seq),
      [3, 2, 1],
    )
  } finally {
    h.dispose()
  }
})
