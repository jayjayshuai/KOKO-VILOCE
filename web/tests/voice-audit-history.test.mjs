import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'
const { descriptor } = parse(
  await readFile(new URL('../src/components/VoiceAuditHistory.vue', import.meta.url), 'utf8'),
)
const code = ts.transpileModule(compileScript(descriptor, { id: 'voice-audit-history-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
function deferred() {
  let resolve
  const promise = new Promise((value) => (resolve = value))
  return { promise, resolve }
}
async function until(predicate) {
  for (let i = 0; i < 40 && !predicate(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(predicate(), true)
}
async function setup(network) {
  const hooks = [],
    calls = [],
    context = vm.createContext({ AbortController, Error })
  const module = new vm.SourceTextModule(code, { context })
  const api = {
    actions: (...args) => {
      calls.push(args)
      return network(...args)
    },
  }
  await module.link((name) => {
    const values =
      name === 'vue'
        ? { ...vue, onBeforeUnmount: (hook) => hooks.push(hook) }
        : name.endsWith('.vue')
          ? { default: {} }
          : { voiceInteractionApi: api }
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context },
    )
  })
  await module.evaluate()
  const props = vue.reactive({ roomId: '1', userId: '42', sessionRevision: 1 }),
    scope = vue.effectScope()
  const state = scope.run(() => module.namespace.default.setup(props, { expose() {}, emit() {} }))
  return {
    state,
    props,
    calls,
    dispose() {
      hooks.forEach((hook) => hook())
      scope.stop()
    },
  }
}
test('关闭房间审计独占字符串版本分页，失败保留原事实', async () => {
  const h = await setup((_id, before) =>
    before
      ? Promise.reject(new Error('历史下一页失败'))
      : Promise.resolve({ items: [{ version: '9007199254741001', type: 'CLOSED' }], nextBefore: '9007199254741001' }),
  )
  try {
    await until(() => !h.state.loading.value)
    await h.state.load(true)
    assert.equal(h.calls[1][1], '9007199254741001')
    assert.equal(h.state.page.value.items[0].type, 'CLOSED')
    assert.match(h.state.error.value, /下一页失败/)
  } finally {
    h.dispose()
  }
})
test('关闭审计换账号立即清数据，旧成功不能覆盖新账号', async () => {
  const wait = deferred()
  let first = true
  const h = await setup(() =>
    first ? ((first = false), wait.promise) : Promise.resolve({ items: [], nextBefore: null }),
  )
  try {
    const signal = h.calls[0][2]
    h.props.userId = '43'
    h.props.sessionRevision++
    await until(() => !h.state.loading.value)
    assert.equal(signal.aborted, true)
    wait.resolve({ items: [{ version: '2', type: 'private-old' }], nextBefore: null })
    await vue.nextTick()
    assert.equal(h.state.page.value.items.length, 0)
    assert.equal(h.state.error.value, '')
  } finally {
    h.dispose()
  }
})

test('卸载停止审计读取，迟到记录不重新挂载私有数据', async () => {
  const wait = deferred(),
    h = await setup(() => wait.promise),
    signal = h.calls[0][2]
  h.dispose()
  assert.equal(signal.aborted, true)
  wait.resolve({ items: [{ version: '3' }], nextBefore: null })
  await vue.nextTick()
  assert.equal(h.state.page.value, null)
})
