import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
class ApiRequestError extends Error {
  constructor(status, code) {
    super('synthetic')
    this.status = status
    this.code = code
  }
}
const post = { id: '100', ownerId: '42', status: 'PUBLISHED', version: 3 }
const deferred = () => {
  let resolve
  const promise = new Promise((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function fixture(network) {
  const hooks = [],
    removed = [],
    context = vue.reactive({ userId: '42', sessionRevision: 1 })
  const global = vm.createContext({ AbortController })
  const source = await readFile(new URL('../src/composables/creator-content-actions.ts', import.meta.url), 'utf8')
  const module = new vm.SourceTextModule(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { context: global },
  )
  await module.link((name) => {
    const values =
      name === 'vue' ? { ...vue, onBeforeUnmount: (callback) => hooks.push(callback) } : { api: {}, ApiRequestError }
    return new vm.SyntheticModule(
      Object.keys(values),
      function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value)
      },
      { context: global },
    )
  })
  await module.evaluate()
  const state = module.namespace.useCreatorContentActions(
    () => context,
    (id) => removed.push(id),
    network,
  )
  return { context, state, removed, dispose: () => hooks.forEach((callback) => callback()) }
}
test('归档串行且只使用原版本，未确认回复前不乐观移除', async () => {
  const wait = deferred(),
    calls = [],
    h = await fixture({
      archivePost: (id, version) => {
        calls.push([id, version])
        return wait.promise
      },
    })
  try {
    const first = h.state.archive(post)
    await h.state.archive(post)
    assert.deepEqual(calls, [['100', 3]])
    assert.deepEqual(h.removed, [])
    wait.resolve()
    await first
    assert.deepEqual(h.removed, ['100'])
  } finally {
    h.dispose()
  }
})
test('回复丢失保留门禁，只读未见内容后采用当前事实而不重复归档', async () => {
  let writes = 0,
    rows = [post]
  const h = await fixture({
    archivePost: async () => {
      writes++
      throw new Error('synthetic network')
    },
    myPosts: async () => rows,
  })
  try {
    await h.state.archive(post)
    await h.state.archive(post)
    assert.equal(writes, 1)
    await h.state.recheck()
    assert.ok(h.state.pending.value)
    assert.deepEqual(h.removed, [])
    rows = []
    await h.state.recheck()
    assert.equal(h.state.pending.value, null)
    assert.deepEqual(h.removed, ['100'])
    assert.equal(writes, 1)
  } finally {
    h.dispose()
  }
})
test('业务确定拒绝不当未知成功；错归属核实不能解锁', async () => {
  const h = await fixture({
    archivePost: async () => {
      throw new ApiRequestError(403, 'FORBIDDEN')
    },
  })
  try {
    await h.state.archive(post)
    assert.equal(h.state.pending.value, null)
    assert.deepEqual(h.removed, [])
  } finally {
    h.dispose()
  }
  const broken = await fixture({
    archivePost: async () => {
      throw new Error('synthetic')
    },
    myPosts: async () => [{ ...post, ownerId: '99' }],
  })
  try {
    await broken.state.archive(post)
    await broken.state.recheck()
    assert.ok(broken.state.pending.value)
    assert.deepEqual(broken.removed, [])
  } finally {
    broken.dispose()
  }
})
test('旧身份归档迟到不覆盖新身份或解除新操作忙态', async () => {
  const first = deferred(),
    second = deferred()
  let count = 0
  const h = await fixture({ archivePost: () => (++count === 1 ? first.promise : second.promise) })
  try {
    const old = h.state.archive(post)
    h.context.userId = '99'
    h.context.sessionRevision++
    const current = h.state.archive({ ...post, ownerId: '99', id: '101' })
    first.resolve()
    await old
    assert.equal(h.state.busy.value, true)
    assert.deepEqual(h.removed, [])
    second.resolve()
    await current
    assert.deepEqual(h.removed, ['101'])
  } finally {
    h.dispose()
  }
})
