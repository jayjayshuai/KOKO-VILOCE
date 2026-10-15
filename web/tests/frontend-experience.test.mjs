import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

async function execute(file, mocks = {}) {
  const context = vm.createContext({
    navigator: {
      clipboard: {
        writeText() {
          throw new Error('not configured')
        },
      },
    },
  })
  const module = new vm.SourceTextModule(
    ts.transpileModule(await readFile(new URL('../src/' + file, import.meta.url), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    }).outputText,
    { context },
  )
  await module.link((name) => {
    if (!mocks[name]) throw new Error('Unconfigured ' + name)
    return new vm.SyntheticModule(
      Object.keys(mocks[name]),
      function () {
        for (const [key, value] of Object.entries(mocks[name])) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  return module.namespace
}
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function clipboard(write, value = 'https://app.example.invalid/#/creators/author-one') {
  const hooks = [],
    url = vue.ref(value),
    calls = []
  const { useClipboardFeedback } = await execute('composables/clipboard-feedback.ts', {
    vue: { ...vue, onBeforeUnmount: (fn) => hooks.push(fn) },
  })
  return {
    state: useClipboardFeedback(
      () => url.value,
      (value) => {
        calls.push(value)
        return write(value)
      },
    ),
    url,
    calls,
    dispose: () => hooks.forEach((fn) => fn()),
  }
}
test('详情导航归属于真实分区，不把其他工作台路由更名', async () => {
  const { navigationSection } = await execute('services/navigation-section.ts')
  assert.equal(navigationSection('public-creator'), 'creators')
  assert.equal(navigationSection('voice-room'), 'voice')
  for (const name of ['messages', 'studio', 'account', 'operations', undefined])
    assert.equal(navigationSection(name), name)
})
test('复制只能明确点击，在途点击只写一次，成功后才显示已复制', async () => {
  const wait = deferred(),
    f = await clipboard(() => wait.promise)
  assert.equal(f.calls.length, 0)
  const pending = f.state.copy()
  await f.state.copy()
  assert.equal(f.calls.length, 1)
  assert.equal(f.state.busy.value, true)
  assert.equal(f.state.copied.value, false)
  wait.resolve()
  await pending
  assert.equal(f.state.busy.value, false)
  assert.equal(f.state.copied.value, true)
  assert.equal(f.state.message.value, '主页地址已复制。')
  f.dispose()
})
test('复制拒绝给手动操作提示，不自动重试，不输出权限异常细节', async () => {
  const f = await clipboard(async () => {
    throw new Error('sensitive-browser-detail')
  })
  await f.state.copy()
  assert.equal(f.state.copied.value, false)
  assert.equal(f.state.busy.value, false)
  assert.match(f.state.message.value, /手动复制/)
  assert.doesNotMatch(f.state.message.value, /sensitive/)
  assert.equal(f.calls.length, 1)
  f.dispose()
})
test('切主页时旧复制回包不写新提示或解除新流程，卸载后不再复制', async () => {
  const old = deferred(),
    fresh = deferred()
  let n = 0
  const f = await clipboard(() => (++n === 1 ? old.promise : fresh.promise))
  const first = f.state.copy()
  f.url.value = 'https://app.example.invalid/#/creators/author-two'
  const second = f.state.copy()
  old.resolve()
  await first
  assert.equal(f.state.message.value, '')
  assert.equal(f.state.busy.value, true)
  fresh.resolve()
  await second
  assert.equal(f.state.copied.value, true)
  f.dispose()
  await f.state.copy()
  assert.equal(f.calls.length, 2)
})
test('无公开地址不能写剪贴板，离开后未知回应不能假装复制成功', async () => {
  const blank = await clipboard(async () => {}, '')
  await blank.state.copy()
  assert.equal(blank.calls.length, 0)
  blank.dispose()
  const wait = deferred(),
    f = await clipboard(() => wait.promise),
    pending = f.state.copy()
  f.dispose()
  wait.resolve()
  await pending
  assert.equal(f.state.message.value, '')
  assert.equal(f.state.copied.value, false)
})
